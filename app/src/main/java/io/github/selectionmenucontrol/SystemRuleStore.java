package io.github.selectionmenucontrol;

import android.content.Context;
import android.os.Binder;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Global is authoritative; private data is only a checked backup or a pending transaction. */
final class SystemRuleStore {
    static final String SETTING_KEY = "selection_menu_control_rules_v1";
    // A v3 namespace prevents a stale v1/v2 response left by an older module from
    // being mistaken for the current session or polluting its cleanup fence.
    static final String PROBE_REQUEST_KEY = "selection_menu_control_probe_request_v3";
    static final String PROBE_RESPONSE_KEY = "selection_menu_control_probe_response_v3";
    static final String PREFERENCES_NAME = "selection_menu_control_rules";
    static final String QUERY_ORIGINAL_EXTRA = "io.github.selectionmenucontrol.QUERY_ORIGINAL";
    static final String MENU_CLASSIFICATION_KEY = "io.github.selectionmenucontrol.MENU_CLASSIFICATION";
    static final long PROBE_VALIDITY_MS = 5_000L;
    enum ReadStatus { VALID, MISSING, UNREADABLE, MALFORMED }
    private SystemRuleStore() {}

    static Snapshot read(Context context) {
        long identity = Binder.clearCallingIdentity();
        try { return readGlobal(context); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    static Snapshot readGlobal(Context context) {
        try {
            Snapshot value = decode(Settings.Global.getString(context.getContentResolver(), SETTING_KEY));
            ModuleDiagnostics.recover(ModuleDiagnostics.Code.RULES_READ_FAILED);
            if (value.valid) ModuleDiagnostics.recover(ModuleDiagnostics.Code.RULES_INVALID);
            return value;
        } catch (Exception error) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.RULES_READ_FAILED);
            return Snapshot.unavailable(ReadStatus.UNREADABLE);
        }
    }
    static synchronized Snapshot readApp(Context context) {
        Snapshot global = readGlobal(context);
        if (global.status != ReadStatus.MISSING) {
            if (global.valid) repairBackup(context);
            return global;
        }
        try {
            android.content.SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            // Once Global has existed, its deliberate deletion must not resurrect a private old copy.
            return preferences.getBoolean("global_established", false) ? global : decode(preferences.getString(SETTING_KEY, null));
        } catch (Exception error) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.BACKUP_FAILED);
            return Snapshot.unavailable(ReadStatus.UNREADABLE);
        }
    }
    static ProbeStart beginProbe(Context context) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        int boot = getBootCount(context);
        if (boot < 0) return new ProbeStart(false, "无法读取本次启动信息", null, null);
        long issuedAt = SystemClock.elapsedRealtime();
        ProbeRequest request = new ProbeRequest(nonce, issuedAt, boot, BuildConfig.VERSION_CODE,
                ProbeProtocol.CAPABILITY, BuildConfig.PROBE_BUILD_ID);
        String prefix = ProbeProtocol.requestPrefix(nonce, boot, request.version, request.buildId);
        RootAccess.ExecutionResult prepared = RootAccess.prepareProbe(PROBE_REQUEST_KEY,
                PROBE_RESPONSE_KEY, prefix);
        return preparedProbe(request, prepared);
    }
    static ProbeStart preparedProbe(ProbeRequest request, RootAccess.ExecutionResult prepared) {
        if (!prepared.success) {
            ModuleDiagnostics.fault(rootFailureCode(prepared.failure));
            // Preparation may have committed before verification or timeout failed.
            // Keep ownership even after failure so the caller can fence and clean it.
            return new ProbeStart(false, RootAccess.failureMessage(prepared), request, prepared);
        }
        recoverRootFailures();
        return new ProbeStart(true, "系统作用域检测请求已写入", request, prepared);
    }
    static HookStatus readProbeResponse(Context context, ProbeRequest request) {
        if (request == null) return HookStatus.unavailable();
        long started = SystemClock.elapsedRealtime();
        try {
            ProbeProtocol.Status status = ProbeProtocol.evaluate(Settings.Global.getString(
                    context.getContentResolver(), PROBE_RESPONSE_KEY), request.nonce, request.issuedAt,
                    getBootCount(context), request.version, request.buildId,
                    SystemClock.elapsedRealtime(), PROBE_VALIDITY_MS);
            return probeStatus(status);
        } catch (Exception error) {
            return probeStatus(ProbeProtocol.Status.READ_FAILED);
        } finally {
            ModuleDiagnostics.metric(ModuleDiagnostics.Metric.PROBE_RESPONSE_MS,
                    SystemClock.elapsedRealtime() - started);
        }
    }
    static HookStatus probeStatus(ProbeProtocol.Status status) {
        return probeStatus(status, null);
    }
    static HookStatus probeStatus(ProbeProtocol.Status status, String detail) {
        if (status == ProbeProtocol.Status.READY) {
            for (ModuleDiagnostics.Code code : new ModuleDiagnostics.Code[]{ModuleDiagnostics.Code.PROBE_FAILED,
                    ModuleDiagnostics.Code.PROBE_READ_FAILED, ModuleDiagnostics.Code.PROBE_INCOMPATIBLE,
                    ModuleDiagnostics.Code.PROBE_EXPIRED, ModuleDiagnostics.Code.PROBE_INVALID,
                    ModuleDiagnostics.Code.PROBE_NO_RESPONSE, ModuleDiagnostics.Code.PROBE_PREPARATION_FAILED,
                    ModuleDiagnostics.Code.PROBE_QUERY_FAILED}) ModuleDiagnostics.recover(code);
        } else {
            ModuleDiagnostics.Code code;
            switch (status) {
                case PREPARATION_FAILED: code = ModuleDiagnostics.Code.PROBE_PREPARATION_FAILED; break;
                case QUERY_FAILED: code = ModuleDiagnostics.Code.PROBE_QUERY_FAILED; break;
                case READ_FAILED: code = ModuleDiagnostics.Code.PROBE_READ_FAILED; break;
                case INCOMPATIBLE: code = ModuleDiagnostics.Code.PROBE_INCOMPATIBLE; break;
                case EXPIRED: code = ModuleDiagnostics.Code.PROBE_EXPIRED; break;
                case INVALID_RESPONSE: code = ModuleDiagnostics.Code.PROBE_INVALID; break;
                default: code = ModuleDiagnostics.Code.PROBE_NO_RESPONSE;
            }
            ModuleDiagnostics.fault(code);
        }
        return new HookStatus(status, detail);
    }
    static RootAccess.ExecutionResult cancelProbe(ProbeRequest request) {
        if (request == null) return new RootAccess.ExecutionResult(RootAccess.Failure.NONE, 0L, 0, false);
        String prefix = ProbeProtocol.PREFIX + request.nonce + ":";
        RootAccess.ExecutionResult result = RootAccess.cleanupProbe(PROBE_REQUEST_KEY, PROBE_RESPONSE_KEY, prefix);
        if (result.success) ModuleDiagnostics.recover(ModuleDiagnostics.Code.PROBE_CLEANUP_FAILED);
        else {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.PROBE_CLEANUP_FAILED);
            ModuleDiagnostics.fault(rootFailureCode(result.failure));
        }
        return result;
    }
    private static int getBootCount(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (Exception ignored) { return -1; }
    }
    static synchronized SaveResult saveDetailed(Context context, Snapshot snapshot, boolean conditional, String expected) {
        if (!snapshot.valid) return new SaveResult(false, false, false);
        final String encoded;
        try { encoded = snapshot.encode(); }
        catch (IllegalArgumentException error) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.RULES_LIMIT_EXCEEDED);
            return new SaveResult(false, false, false);
        }
        RootAccess.ExecutionResult result = conditional
                ? RootAccess.putGlobalSettingVerifiedResult(SETTING_KEY, encoded, expected)
                : RootAccess.putGlobalSettingVerifiedResult(SETTING_KEY, encoded);
        ModuleDiagnostics.metric(ModuleDiagnostics.Metric.ROOT_MS, result.elapsedMillis);
        if (!result.success) {
            ModuleDiagnostics.fault(rootFailureCode(result.failure));
            return new SaveResult(false, false, result.uncertainWrite || result.failure == RootAccess.Failure.LOCK_BUSY);
        }
        recoverRootFailures();
        return new SaveResult(true, repairBackup(context), false);
    }
    private static void recoverRootFailures() {
        for (RootAccess.Failure failure : RootAccess.Failure.values()) ModuleDiagnostics.recover(rootFailureCode(failure));
    }
    private static ModuleDiagnostics.Code rootFailureCode(RootAccess.Failure failure) {
        switch (failure) {
            case TIMEOUT: return ModuleDiagnostics.Code.ROOT_TIMEOUT;
            case DENIED: case NOT_ROOT: return ModuleDiagnostics.Code.ROOT_DENIED;
            case NO_SU: case START_FAILED: return ModuleDiagnostics.Code.ROOT_UNAVAILABLE;
            case LOCK_BUSY: return ModuleDiagnostics.Code.ROOT_LOCKED;
            case EXPIRED: return ModuleDiagnostics.Code.ROOT_EXPIRED;
            case STALE_REVISION: return ModuleDiagnostics.Code.ROOT_STALE;
            case STORAGE_FAILED: return ModuleDiagnostics.Code.ROOT_STORAGE;
            case WRITE_FAILED: return ModuleDiagnostics.Code.ROOT_WRITE;
            case VERIFY_FAILED: return ModuleDiagnostics.Code.ROOT_VERIFY;
            case PROTOCOL_FAILED: case INVALID_INPUT: return ModuleDiagnostics.Code.ROOT_PROTOCOL;
            case OUTPUT_FAILED: return ModuleDiagnostics.Code.ROOT_IO;
            case INTERRUPTED: return ModuleDiagnostics.Code.ROOT_INTERRUPTED;
            default: return ModuleDiagnostics.Code.ROOT_FAILED;
        }
    }
    static synchronized boolean repairBackup(Context context) {
        try {
            // Re-read while sharing the writer lock. A page read captured before a save is not a backup source.
            RawValue current = readRawGlobal(context);
            if (!current.valid || current.value == null) return false;
            String encoded = decode(current.value).encode();
            android.content.SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (persistBackup(preferences, encoded)) {
                ModuleDiagnostics.recover(ModuleDiagnostics.Code.BACKUP_FAILED);
                return true;
            }
        } catch (Exception ignored) { /* Report a finite code, never rule contents. */ }
        ModuleDiagnostics.fault(ModuleDiagnostics.Code.BACKUP_FAILED);
        return false;
    }
    static boolean persistBackup(android.content.SharedPreferences preferences, String encoded) {
        // commit=false can still update preferences memory. Equal memory values are
        // not a disk receipt; SharedPreferences skips disk IO for confirmed generations.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (preferences.edit().putString(SETTING_KEY, encoded).putBoolean("global_established", true).commit()) {
                return true;
            }
        }
        return false;
    }
    static synchronized boolean migrateToGlobal(Context context, Snapshot snapshot) {
        Snapshot global = readGlobal(context);
        if (global.status != ReadStatus.MISSING) return global.valid;
        // A genuinely absent Global value is already a valid empty state. Do not
        // turn a pending transaction's null baseline into an unrelated v3:: write.
        if (snapshot.status == ReadStatus.MISSING) return true;
        try {
            android.content.SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            // Old private backup migration must wait for any pending transaction,
            // including a partial record that the coordinator will mark unreadable.
            for (String key : new String[]{"pending_id", "pending_baseline", "pending_target", "pending_inflight"}) {
                if (preferences.contains(key)) return true;
            }
        } catch (Exception error) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED);
            return false;
        }
        return snapshot.valid && saveDetailed(context, snapshot, true, null).verified;
    }
    static String respondToProbe(Context context, String lastRespondedNonce) {
        long identity = Binder.clearCallingIdentity();
        try {
            ProbeProtocol.Record request = ProbeProtocol.parse(Settings.Global.getString(
                    context.getContentResolver(), PROBE_REQUEST_KEY));
            long now = SystemClock.elapsedRealtime();
            if (!ProbeProtocol.currentRequest(request, getBootCount(context), now, PROBE_VALIDITY_MS)
                    || request.nonce.equals(lastRespondedNonce)) return null;
            String response = ProbeProtocol.response(request, BuildConfig.VERSION_CODE, BuildConfig.PROBE_BUILD_ID, now);
            if (Settings.Global.putString(context.getContentResolver(), PROBE_RESPONSE_KEY, response)) {
                ModuleDiagnostics.recover(ModuleDiagnostics.Code.PROBE_FAILED);
                return request.nonce;
            }
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.PROBE_FAILED);
        } catch (Exception error) { ModuleDiagnostics.fault(ModuleDiagnostics.Code.PROBE_FAILED); }
        finally { Binder.restoreCallingIdentity(identity); }
        // Another user may have submitted a new nonce; never clear their request here.
        return null;
    }
    static Snapshot decode(String value) {
        try {
            RuleConfig decoded = RuleConfig.decode(value);
            return new Snapshot(decoded.hiddenComponents, decoded.orderedComponents,
                    value == null ? ReadStatus.MISSING : ReadStatus.VALID);
        } catch (IllegalArgumentException error) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.RULES_INVALID);
            return Snapshot.unavailable(ReadStatus.MALFORMED);
        }
    }
    static RawValue readRawGlobal(Context context) {
        try {
            String value = Settings.Global.getString(context.getContentResolver(), SETTING_KEY);
            boolean valid = decode(value).valid;
            ModuleDiagnostics.recover(ModuleDiagnostics.Code.RULES_READ_FAILED);
            if (valid) ModuleDiagnostics.recover(ModuleDiagnostics.Code.RULES_INVALID);
            return new RawValue(value, valid);
        } catch (Exception error) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.RULES_READ_FAILED);
            return new RawValue(null, false);
        }
    }
    static final class RawValue {
        final String value;
        final boolean valid;
        RawValue(String value, boolean valid) { this.value = value; this.valid = valid; }
    }
    static final class SaveResult {
        final boolean verified, backupCurrent, uncertain;
        SaveResult(boolean verified, boolean backupCurrent, boolean uncertain) {
            this.verified = verified; this.backupCurrent = backupCurrent; this.uncertain = uncertain;
        }
    }
    static final class Snapshot extends RuleConfig {
        final boolean valid;
        final ReadStatus status;
        Snapshot(Set<String> hidden) { this(hidden, Collections.emptyList()); }
        Snapshot(Set<String> hidden, List<String> order) { this(hidden, order, ReadStatus.VALID); }
        private Snapshot(Set<String> hidden, List<String> order, ReadStatus status) {
            super(hidden, order); this.status = status;
            valid = status == ReadStatus.VALID || status == ReadStatus.MISSING;
        }
        static Snapshot empty() { return new Snapshot(Collections.emptySet()); }
        static Snapshot unavailable(ReadStatus status) { return new Snapshot(Collections.emptySet(), Collections.emptyList(), status); }
    }
    static final class HookStatus {
        final boolean loadedForCurrentBoot;
        final ProbeProtocol.Status status;
        final String message;
        HookStatus(ProbeProtocol.Status status) {
            this(status, null);
        }
        HookStatus(ProbeProtocol.Status status, String detail) {
            this.status = status;
            loadedForCurrentBoot = status == ProbeProtocol.Status.READY;
            String message;
            switch (status) {
                case READY: message = "当前构建已响应本次检测"; break;
                case PREPARATION_FAILED: message = "无法准备作用域检测请求，请重新检查"; break;
                case QUERY_FAILED: message = "系统查询未完成，无法确认作用域，请重新检查"; break;
                case READ_FAILED: message = "无法读取检测回执，请重试"; break;
                case INCOMPATIBLE: message = "系统中加载的是其他构建或旧检测协议，需重新加载模块"; break;
                case EXPIRED: message = "检测回执已过期，请重试"; break;
                case INVALID_RESPONSE: message = "检测回执格式或时间无效，请重试"; break;
                default: message = "未收到本次检测回执，暂时无法确认模块状态";
            }
            this.message = detail != null && !detail.isEmpty() ? detail : message;
        }
        static HookStatus unavailable() { return new HookStatus(ProbeProtocol.Status.NO_RESPONSE); }
    }
    static final class ProbeRequest {
        final String nonce;
        final long issuedAt;
        final int boot, version, capability;
        final String buildId;
        ProbeRequest(String nonce, long issuedAt, int boot, int version, int capability, String buildId) {
            this.nonce = nonce; this.issuedAt = issuedAt; this.boot = boot;
            this.version = version; this.capability = capability; this.buildId = buildId;
        }
    }
    static final class ProbeStart {
        /** Whether the v3 probe request was prepared; Root has already been gated separately. */
        final boolean prepared;
        final String preparationMessage;
        final ProbeRequest request;
        final RootAccess.ExecutionResult preparation;
        ProbeStart(boolean prepared, String preparationMessage, ProbeRequest request, RootAccess.ExecutionResult preparation) {
            this.prepared = prepared; this.preparationMessage = preparationMessage; this.request = request;
            this.preparation = preparation;
        }
    }
}
