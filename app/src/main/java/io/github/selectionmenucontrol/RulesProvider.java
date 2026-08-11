package io.github.selectionmenucontrol;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.content.UriMatcher;

public final class RulesProvider extends ContentProvider {
    private static final int RULES = 1;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(SystemRuleStore.RULES_AUTHORITY, SystemRuleStore.RULES_PATH, RULES);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (MATCHER.match(uri) != RULES || getContext() == null || !isAllowedReader()) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{
                SystemRuleStore.COLUMN_RULES,
                SystemRuleStore.COLUMN_HOOK_BOOT_COUNT,
                SystemRuleStore.COLUMN_HOOK_LOADED_AT,
                SystemRuleStore.COLUMN_HOOK_MODULE_VERSION}, 1);
        android.content.SharedPreferences preferences = getContext()
                .getSharedPreferences(SystemRuleStore.PREFERENCES_NAME, 0);
        cursor.addRow(new Object[]{
                preferences.getString(SystemRuleStore.SETTING_KEY, null),
                preferences.getInt(SystemRuleStore.COLUMN_HOOK_BOOT_COUNT, -1),
                preferences.getLong(SystemRuleStore.COLUMN_HOOK_LOADED_AT, 0L),
                preferences.getInt(SystemRuleStore.COLUMN_HOOK_MODULE_VERSION, -1)});
        return cursor;
    }

    private boolean isAllowedReader() {
        int uid = Binder.getCallingUid();
        return uid == Process.SYSTEM_UID || uid == getContext().getApplicationInfo().uid;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!SystemRuleStore.METHOD_REPORT_SYSTEM_SERVER.equals(method)
                || getContext() == null
                || Binder.getCallingUid() != Process.SYSTEM_UID
                || extras == null) {
            return Bundle.EMPTY;
        }
        int bootCount = extras.getInt(SystemRuleStore.EXTRA_BOOT_COUNT, -1);
        long loadedAt = extras.getLong(SystemRuleStore.EXTRA_LOADED_AT, 0L);
        int moduleVersion = extras.getInt(SystemRuleStore.EXTRA_MODULE_VERSION, -1);
        if (bootCount < 0 || loadedAt <= 0L || moduleVersion < 0) {
            return Bundle.EMPTY;
        }
        boolean saved = getContext().getSharedPreferences(SystemRuleStore.PREFERENCES_NAME, 0)
                .edit()
                .putInt(SystemRuleStore.COLUMN_HOOK_BOOT_COUNT, bootCount)
                .putLong(SystemRuleStore.COLUMN_HOOK_LOADED_AT, loadedAt)
                .putInt(SystemRuleStore.COLUMN_HOOK_MODULE_VERSION, moduleVersion)
                .commit();
        Bundle result = new Bundle();
        result.putBoolean("accepted", saved);
        return result;
    }

    @Override
    public String getType(Uri uri) {
        return MATCHER.match(uri) == RULES ? "vnd.android.cursor.item/vnd.selectionmenucontrol.rules" : null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
