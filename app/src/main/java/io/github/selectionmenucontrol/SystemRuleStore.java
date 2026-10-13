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
    static final String PROBE_REQUEST_KEY = "selection_menu_control_probe_request_v1";
    static final String PROBE_RESPONSE_KEY = "selection_menu_control_probe_response_v1";
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
    static ProbeRequest beginProbe(Context context) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        int boot = getBootCount(context);
        if (boot < 0) return null;
        String prefix = "v2:" + nonce + ":" + boot + ":" + BuildConfig.VERSION_CODE + ":";
        RootAccess.ExecutionResult result = RootAccess.prepareProbe(PROBE_REQUEST_KEY,
                PROBE_RESPONSE_KEY, prefix, "v2:pending:0:0:0");
        if (!result.success) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.PROBE_FAILED);
            return null;
        }
        try {
            ProbeProtocol.Record record = ProbeProtocol.parse(Settings.Global.getString(
                    context.getContentResolver(), PROBE_REQUEST_KEY));
            if (record == null || !nonce.equals(record.nonce)) return null;
            return new ProbeRequest(nonce, record.uptime);
        } catch (Exception error) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.PROBE_FAILED);
            return null;
        }
    }
    static HookStatus readProbeResponse(Context context, ProbeRequest request) {
        if (request == null) return HookStatus.unavailable();
        try {
            ProbeProtocol.Record response = ProbeProtocol.parse(Settings.Global.getString(
                    context.getContentResolver(), PROBE_RESPONSE_KEY));
            boolean valid = ProbeProtocol.matches(response, request.nonce, request.issuedAt,
                    getBootCount(context), BuildConfig.VERSION_CODE, SystemClock.elapsedRealtime(), PROBE_VALIDITY_MS);
            if (valid) ModuleDiagnostics.recover(ModuleDiagnostics.Code.PROBE_FAILED);
            return new HookStatus(valid);
        } catch (Exception error) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.PROBE_FAILED);
            return HookStatus.unavailable();
        }
    }
    static void cancelProbe(ProbeRequest request) {
        if (request == null) return;
        String prefix = "v2:" + request.nonce + ":";
        RootAccess.cleanupProbe(PROBE_REQUEST_KEY, PROBE_RESPONSE_KEY, prefix);
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
        for (RootAccess.Failure failure : RootAccess.Failure.values()) ModuleDiagnostics.recover(rootFailureCode(failure));
        return new SaveResult(true, repairBackup(context), false);
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
            if (encoded.equals(preferences.getString(SETTING_KEY, null)) && preferences.getBoolean("global_established", false)) {
                ModuleDiagnostics.recover(ModuleDiagnostics.Code.BACKUP_FAILED);
                return true;
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                if (preferences.edit().putString(SETTING_KEY, encoded).putBoolean("global_established", true).commit()) {
                    ModuleDiagnostics.recover(ModuleDiagnostics.Code.BACKUP_FAILED);
                    return true;
                }
            }
        } catch (Exception ignored) { /* Report a finite code, never rule contents. */ }
        ModuleDiagnostics.fault(ModuleDiagnostics.Code.BACKUP_FAILED);
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
            if (!ProbeProtocol.current(request, getBootCount(context), BuildConfig.VERSION_CODE, now, PROBE_VALIDITY_MS)
                    || request.nonce.equals(lastRespondedNonce)) return null;
            String response = "v2:" + request.nonce + ":" + request.boot + ":" + BuildConfig.VERSION_CODE + ":" + now;
            if (Settings.Global.putString(context.getContentResolver(), PROBE_RESPONSE_KEY, response)) return request.nonce;
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
        HookStatus(boolean loaded) { loadedForCurrentBoot = loaded; }
        static HookStatus unavailable() { return new HookStatus(false); }
    }
    static final class ProbeRequest {
        final String nonce;
        final long issuedAt;
        ProbeRequest(String nonce, long issuedAt) { this.nonce = nonce; this.issuedAt = issuedAt; }
    }
}
