package io.github.selectionmenucontrol;

import android.content.Context;
import android.os.Binder;
import android.provider.Settings;
import android.util.Log;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class SystemRuleStore {
    private static final String TAG = "SelectionMenuControl";
    static final String SETTING_KEY = "selection_menu_control_rules_v1";
    static final String PROBE_REQUEST_KEY = "selection_menu_control_probe_request_v1";
    static final String PROBE_RESPONSE_KEY = "selection_menu_control_probe_response_v1";
    static final String PREFERENCES_NAME = "selection_menu_control_rules";
    static final String QUERY_ORIGINAL_EXTRA = "io.github.selectionmenucontrol.QUERY_ORIGINAL";
    static final String MENU_CLASSIFICATION_KEY = "io.github.selectionmenucontrol.MENU_CLASSIFICATION";
    private static final String PROBE_PREFIX = "v1:";
    private static final String PROBE_PENDING_RESPONSE = "v1:pending:0:0:0";
    private static final long PROBE_VALIDITY_MS = 5_000L;

    private SystemRuleStore() {
    }

    static Snapshot read(Context context) {
        String globalRules = readGlobalRules(context);
        return decode(globalRules);
    }

    static Snapshot readApp(Context context) {
        try {
            String globalRules = Settings.Global.getString(context.getContentResolver(), SETTING_KEY);
            if (globalRules != null) {
                return decode(globalRules);
            }
            String legacyRules = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .getString(SETTING_KEY, null);
            return decode(legacyRules);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to read app rules", error);
            return new Snapshot(Collections.emptySet(), Collections.emptyList(), false);
        }
    }

    static ProbeRequest beginProbe() {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        long issuedAt = System.currentTimeMillis();
        String request = PROBE_PREFIX + nonce + ":" + issuedAt;
        RootAccess.deleteGlobalSetting(PROBE_REQUEST_KEY);
        if (!RootAccess.putGlobalSetting(PROBE_RESPONSE_KEY, PROBE_PENDING_RESPONSE)) {
            return null;
        }
        if (!RootAccess.putGlobalSetting(PROBE_REQUEST_KEY, request)) {
            RootAccess.deleteGlobalSetting(PROBE_RESPONSE_KEY);
            return null;
        }
        return new ProbeRequest(nonce, issuedAt);
    }

    static HookStatus readProbeResponse(Context context, ProbeRequest request) {
        if (request == null) {
            return HookStatus.unavailable();
        }
        try {
            String response = readGlobalValue(context, PROBE_RESPONSE_KEY);
            if (response == null) {
                return HookStatus.unavailable();
            }
            String[] parts = response.split(":", -1);
            if (parts.length != 5 || !"v1".equals(parts[0])) {
                return HookStatus.unavailable();
            }
            if (!request.nonce.equals(parts[1])) {
                return HookStatus.unavailable();
            }
            int reportedBoot = Integer.parseInt(parts[2]);
            int reportedVersion = Integer.parseInt(parts[3]);
            long loadedAt = Long.parseLong(parts[4]);
            long now = System.currentTimeMillis();
            int currentBoot = getBootCount(context);
            boolean valid = currentBoot >= 0
                    && currentBoot == reportedBoot
                    && reportedVersion == BuildConfig.VERSION_CODE
                    && loadedAt >= request.issuedAt
                    && loadedAt <= now + 1_000L
                    && now - loadedAt <= PROBE_VALIDITY_MS;
            return new HookStatus(valid);
        } catch (Throwable ignored) {
            return HookStatus.unavailable();
        }
    }

    static void cancelProbe(ProbeRequest request) {
        if (request != null) {
            String prefix = PROBE_PREFIX + request.nonce + ":";
            RootAccess.deleteGlobalSettingWithPrefix(PROBE_REQUEST_KEY, prefix);
            RootAccess.deleteGlobalSettingWithPrefix(PROBE_RESPONSE_KEY, prefix);
        }
    }

    private static int getBootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static synchronized boolean save(Context context, Snapshot snapshot) {
        if (!snapshot.valid) {
            return false;
        }
        String encoded = snapshot.encode();
        if (!RootAccess.putGlobalSettingVerified(SETTING_KEY, encoded)) {
            return false;
        }
        if (!context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(SETTING_KEY, encoded)
                .commit()) {
            Log.w(TAG, "Global rules saved; private backup could not be updated");
        }
        return true;
    }

    static synchronized boolean migrateToGlobal(Context context, Snapshot snapshot) {
        // Existing Global rules are authoritative. Opening the page must not overwrite them.
        try {
            String current = Settings.Global.getString(context.getContentResolver(), SETTING_KEY);
            if (current != null) {
                return decode(current).valid;
            }
            return snapshot.valid && save(context, snapshot);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to migrate rules", error);
            return false;
        }
    }

    private static String readGlobalRules(Context context) {
        return readGlobalValue(context, SETTING_KEY);
    }

    private static String readGlobalValue(Context context, String key) {
        long identity = Binder.clearCallingIdentity();
        try {
            return Settings.Global.getString(context.getContentResolver(), key);
        } catch (Throwable ignored) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    static String respondToProbe(Context context, String lastRespondedNonce) {
        String request = readGlobalValue(context, PROBE_REQUEST_KEY);
        if (request == null || request.isEmpty()) {
            return null;
        }
        try {
            String[] parts = request.split(":", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) {
                clearProbeRequest(context);
                return null;
            }
            long issuedAt = Long.parseLong(parts[2]);
            long now = System.currentTimeMillis();
            if (parts[1].isEmpty() || now < issuedAt || now - issuedAt > PROBE_VALIDITY_MS) {
                clearProbeRequest(context);
                return null;
            }
            if (parts[1].equals(lastRespondedNonce)) {
                return null;
            }
            int bootCount = getBootCount(context);
            if (bootCount < 0) {
                return null;
            }
            String response = PROBE_PREFIX + parts[1] + ":" + bootCount + ":"
                    + BuildConfig.VERSION_CODE + ":" + now;
            long identity = Binder.clearCallingIdentity();
            try {
                boolean accepted = Settings.Global.putString(
                        context.getContentResolver(), PROBE_RESPONSE_KEY, response);
                if (accepted) {
                    Settings.Global.putString(context.getContentResolver(), PROBE_REQUEST_KEY, null);
                    return parts[1];
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to respond to system hook probe", error);
        }
        return null;
    }

    private static void clearProbeRequest(Context context) {
        long identity = Binder.clearCallingIdentity();
        try {
            Settings.Global.putString(context.getContentResolver(), PROBE_REQUEST_KEY, null);
        } catch (Throwable ignored) {
            // Best-effort cleanup; invalid requests are ignored on subsequent probes.
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    static Snapshot decode(String value) {
        try {
            RuleConfig decoded = RuleConfig.decode(value);
            return new Snapshot(decoded.hiddenComponents, decoded.orderedComponents);
        } catch (IllegalArgumentException ignored) {
            return new Snapshot(Collections.emptySet(), Collections.emptyList(), false);
        }
    }

    static final class Snapshot extends RuleConfig {
        final boolean valid;

        Snapshot(Set<String> hiddenComponents) {
            this(hiddenComponents, Collections.emptyList());
        }

        Snapshot(Set<String> hiddenComponents, List<String> orderedComponents) {
            this(hiddenComponents, orderedComponents, true);
        }

        private Snapshot(Set<String> hiddenComponents, List<String> orderedComponents, boolean valid) {
            super(hiddenComponents, orderedComponents);
            this.valid = valid;
        }

        static Snapshot empty() {
            return new Snapshot(Collections.emptySet());
        }
    }

    static final class HookStatus {
        final boolean loadedForCurrentBoot;

        HookStatus(boolean loadedForCurrentBoot) {
            this.loadedForCurrentBoot = loadedForCurrentBoot;
        }

        static HookStatus unavailable() {
            return new HookStatus(false);
        }
    }

    static final class ProbeRequest {
        final String nonce;
        final long issuedAt;

        ProbeRequest(String nonce, long issuedAt) {
            this.nonce = nonce;
            this.issuedAt = issuedAt;
        }
    }
}
