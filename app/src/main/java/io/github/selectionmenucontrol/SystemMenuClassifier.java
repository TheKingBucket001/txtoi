package io.github.selectionmenucontrol;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Executes the installed ROM's menu policy without launching an activity or touching a window. */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
final class SystemMenuClassifier {
    static final int UNKNOWN = 0;
    static final int ORDINARY = 1;
    static final int FIXED = 2;
    private static final String TAG = "SelectionMenuControl";

    private final Object editor;
    private final Object toolbar;
    private final Method priority;
    private final Method speech;
    private final Method search;
    private final Method modify;
    private final Method requiresActionButton;
    private final Constructor<?> newMenu;
    private boolean reportedFailure;

    SystemMenuClassifier() throws ReflectiveOperationException {
        Class<?> editorType = Class.forName("android.widget.IEditorExt");
        Class<?> toolbarType = Class.forName("com.android.internal.widget.floatingtoolbar.IFloatingToolbarExt");
        editor = extension(editorType);
        toolbar = extension(toolbarType);
        // A default extension is not evidence that the ROM has no fixed items.
        if (!Class.forName("android.widget.EditorExtImpl").isInstance(editor)
                || !Class.forName("com.android.internal.widget.floatingtoolbar.FloatingToolbarExtImpl").isInstance(toolbar)) {
            throw new ReflectiveOperationException("Installed menu policy implementation unavailable");
        }
        priority = editorType.getMethod("raiseOplusMenuPriority", int.class, CharSequence.class,
                Intent.class, ResolveInfo.class, Menu.class);
        speech = editorType.getMethod("setSpeechAssistMenuItem", int.class, Intent.class,
                CharSequence.class, ResolveInfo.class, Menu.class);
        search = editorType.getMethod("setSearchMenuItem", int.class, Intent.class,
                CharSequence.class, ResolveInfo.class, Menu.class);
        modify = toolbarType.getMethod("modifyAiWriterMenu", MenuItem.class);
        requiresActionButton = Class.forName("com.android.internal.view.menu.MenuItemImpl")
                .getMethod("requiresActionButton");
        newMenu = Class.forName("com.android.internal.view.menu.MenuBuilder").getConstructor(Context.class);
    }

    private static Object extension(Class<?> type) throws ReflectiveOperationException {
        Object builder = Class.forName("system.ext.loader.core.ExtLoader")
                .getMethod("type", Class.class).invoke(null, type);
        builder.getClass().getMethod("base", Object.class).invoke(builder, new Object[]{null});
        return builder.getClass().getMethod("create").invoke(builder);
    }

    synchronized int classify(Context context, ResolveInfo original, int index) {
        if (context == null || original.activityInfo == null) return UNKNOWN;
        try {
            ResolveInfo info = new ResolveInfo(original);
            info.activityInfo = new ActivityInfo(original.activityInfo);
            Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                    .setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
            CharSequence label = info.loadLabel(context.getPackageManager());
            Menu menu = (Menu) newMenu.newInstance(context);
            boolean handled = (Boolean) priority.invoke(editor, 100 + index, label, intent, info, menu);
            if (!handled) handled = (Boolean) speech.invoke(editor, 5, intent, label, info, menu);
            if (!handled) handled = (Boolean) search.invoke(editor, 6, intent, label, info, menu);
            if (!handled) menu.add(0, 0, 100 + index, label).setIntent(intent)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            if (menu.size() != 1) return UNKNOWN;
            MenuItem item = menu.getItem(0);
            boolean requiredBefore = (Boolean) requiresActionButton.invoke(item);
            MenuItem modified = (MenuItem) modify.invoke(toolbar, item);
            if (modified == null) return UNKNOWN;
            boolean requiredAfter = (Boolean) requiresActionButton.invoke(modified);
            return handled || (!requiredBefore && requiredAfter) ? FIXED : ORDINARY;
        } catch (Throwable error) {
            if (!reportedFailure) {
                reportedFailure = true;
                Log.w(TAG, "Installed menu policy evaluation failed; sorting disabled for unclassified entries", error);
            }
            return UNKNOWN;
        }
    }
}
