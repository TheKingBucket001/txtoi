package io.github.selectionmenucontrol;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class SystemRuleStore {
    private static final String TAG = "SelectionMenuControl";
    static final String SETTING_KEY = "selection_menu_control_rules_v1";
    static final String RULES_AUTHORITY = "io.github.selectionmenucontrol.rules";
    static final String RULES_PATH = "rules";
    static final String COLUMN_RULES = "rules";
    static final String COLUMN_HOOK_BOOT_COUNT = "hook_boot_count";
    static final String COLUMN_HOOK_LOADED_AT = "hook_loaded_at";
    static final String COLUMN_HOOK_MODULE_VERSION = "hook_module_version";
    static final String METHOD_REPORT_SYSTEM_SERVER = "report_system_server_loaded";
    static final String EXTRA_BOOT_COUNT = "boot_count";
    static final String EXTRA_LOADED_AT = "loaded_at";
    static final String EXTRA_MODULE_VERSION = "module_version";
    static final String PREFERENCES_NAME = "selection_menu_control_rules";
    private static final Uri RULES_URI = new Uri.Builder()
            .scheme("content")
            .authority(RULES_AUTHORITY)
            .appendPath(RULES_PATH)
            .build();
    private static final String FORMAT_PREFIX = "v1:";

    private SystemRuleStore() {
    }

    static Snapshot read(Context context) {
        long identity = Binder.clearCallingIdentity();
        try (Cursor cursor = context.getContentResolver().query(RULES_URI, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return Snapshot.empty();
            }
            int column = cursor.getColumnIndex(COLUMN_RULES);
            return column < 0 ? Snapshot.empty() : decode(cursor.getString(column));
        } catch (Throwable error) {
            Log.w(TAG, "Unable to read rules from the module provider", error);
            return Snapshot.empty();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    static HookStatus readHookStatus(Context context) {
        try (Cursor cursor = context.getContentResolver().query(RULES_URI, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return HookStatus.unavailable();
            }
            int bootColumn = cursor.getColumnIndex(COLUMN_HOOK_BOOT_COUNT);
            int loadedColumn = cursor.getColumnIndex(COLUMN_HOOK_LOADED_AT);
            int versionColumn = cursor.getColumnIndex(COLUMN_HOOK_MODULE_VERSION);
            if (bootColumn < 0 || loadedColumn < 0 || versionColumn < 0) {
                return HookStatus.unavailable();
            }
            int currentBoot = getBootCount(context);
            int reportedBoot = cursor.getInt(bootColumn);
            long loadedAt = cursor.getLong(loadedColumn);
            int reportedVersion = cursor.getInt(versionColumn);
            return new HookStatus(
                    currentBoot >= 0
                            && currentBoot == reportedBoot
                            && loadedAt > 0
                            && reportedVersion == BuildConfig.VERSION_CODE,
                    loadedAt);
        } catch (Throwable ignored) {
            return HookStatus.unavailable();
        }
    }

    static void reportSystemServerLoaded(Context context) {
        long identity = Binder.clearCallingIdentity();
        try {
            Bundle extras = new Bundle();
            extras.putInt(EXTRA_BOOT_COUNT, getBootCount(context));
            extras.putLong(EXTRA_LOADED_AT, System.currentTimeMillis());
            extras.putInt(EXTRA_MODULE_VERSION, BuildConfig.VERSION_CODE);
            context.getContentResolver().call(RULES_URI, METHOD_REPORT_SYSTEM_SERVER, null, extras);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to report system_server status", error);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static int getBootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static boolean save(Context context, boolean enabled, Set<String> hiddenComponents) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(SETTING_KEY, encode(enabled, hiddenComponents))
                .commit();
    }

    static Snapshot decode(String value) {
        if (value == null || !value.startsWith(FORMAT_PREFIX)) {
            return Snapshot.empty();
        }
        try {
            String[] parts = value.split(":", 3);
            if (parts.length != 3 || (!"0".equals(parts[1]) && !"1".equals(parts[1]))) {
                return Snapshot.empty();
            }
            String decoded = new String(Base64.decode(parts[2], Base64.NO_WRAP), StandardCharsets.UTF_8);
            Set<String> hidden = new HashSet<>();
            if (!decoded.isEmpty()) {
                Collections.addAll(hidden, decoded.split("\\n"));
                hidden.remove("");
            }
            return new Snapshot("1".equals(parts[1]), hidden);
        } catch (IllegalArgumentException ignored) {
            return Snapshot.empty();
        }
    }

    static String encode(boolean enabled, Set<String> hiddenComponents) {
        ArrayList<String> orderedComponents = new ArrayList<>(hiddenComponents);
        Collections.sort(orderedComponents);
        String payload = String.join("\n", orderedComponents);
        String encoded = Base64.encodeToString(payload.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return FORMAT_PREFIX + (enabled ? "1" : "0") + ":" + encoded;
    }

    static final class Snapshot {
        final boolean enabled;
        final Set<String> hiddenComponents;

        Snapshot(boolean enabled, Set<String> hiddenComponents) {
            this.enabled = enabled;
            this.hiddenComponents = Collections.unmodifiableSet(new HashSet<>(hiddenComponents));
        }

        static Snapshot empty() {
            return new Snapshot(true, Collections.emptySet());
        }
    }

    static final class HookStatus {
        final boolean loadedForCurrentBoot;
        final long loadedAt;

        HookStatus(boolean loadedForCurrentBoot, long loadedAt) {
            this.loadedForCurrentBoot = loadedForCurrentBoot;
            this.loadedAt = loadedAt;
        }

        static HookStatus unavailable() {
            return new HookStatus(false, 0L);
        }
    }
}
