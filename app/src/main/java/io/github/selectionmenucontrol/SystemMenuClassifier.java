package io.github.selectionmenucontrol;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.Menu;
import android.view.MenuItem;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Executes the installed ROM's menu policy without launching an activity or touching a window. */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
final class SystemMenuClassifier {
    static final int UNKNOWN = 0;
    static final int ORDINARY = 1;
    static final int FIXED = 2;

    private final Object editor;
    private final Method priority;
    private final Method speech;
    private final Method search;
    private final Method collectMenuItems;
    private final Method requiresActionButton;
    private final Constructor<?> newMenu;
    private final Method compareMenuItems;
    private final MenuPolicyCache cache = new MenuPolicyCache();

    SystemMenuClassifier() throws ReflectiveOperationException {
        Class<?> editorType = Class.forName("android.widget.IEditorExt");
        Class<?> toolbarType = Class.forName("com.android.internal.widget.floatingtoolbar.IFloatingToolbarExt");
        editor = extension(editorType);
        Object toolbar = extension(toolbarType);
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
        requiresActionButton = Class.forName("com.android.internal.view.menu.MenuItemImpl")
                .getMethod("requiresActionButton");
        newMenu = Class.forName("com.android.internal.view.menu.MenuBuilder").getConstructor(Context.class);
        Class<?> floatingToolbarType = Class.forName("com.android.internal.widget.floatingtoolbar.FloatingToolbar");
        collectMenuItems = floatingToolbarType.getDeclaredMethod("getVisibleAndEnabledMenuItems", Menu.class);
        collectMenuItems.setAccessible(true);
        compareMenuItems = findMenuComparator(floatingToolbarType);
        compareMenuItems.setAccessible(true);
    }

    // Locate the installed comparator by its unique signature, not a generated lambda name.
    static Method findMenuComparator(Class<?> toolbarType) throws ReflectiveOperationException {
        Method result = null;
        for (Method method : toolbarType.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == int.class
                    && parameters.length == 2 && parameters[0] == MenuItem.class
                    && parameters[1] == MenuItem.class) {
                if (result != null) throw new ReflectiveOperationException("Ambiguous installed menu comparator");
                result = method;
            }
        }
        if (result == null) throw new ReflectiveOperationException("Installed menu comparator unavailable");
        return result;
    }

    static final class Result {
        final List<ResolveInfo> displayOrder;
        final Map<ResolveInfo, Integer> classifications;

        Result(List<ResolveInfo> displayOrder, Map<ResolveInfo, Integer> classifications) {
            this.displayOrder = displayOrder;
            this.classifications = classifications;
        }
    }

    private static final class Entry {
        final ResolveInfo info;
        final boolean handled;
        boolean requiredBefore;
        MenuItem item;

        Entry(ResolveInfo info, boolean handled, MenuItem item) {
            this.info = info;
            this.handled = handled;
            this.item = item;
        }
    }

    private static Object extension(Class<?> type) throws ReflectiveOperationException {
        Object builder = Class.forName("system.ext.loader.core.ExtLoader")
                .getMethod("type", Class.class).invoke(null, type);
        builder.getClass().getMethod("base", Object.class).invoke(builder, new Object[]{null});
        return builder.getClass().getMethod("create").invoke(builder);
    }

    synchronized Result evaluate(Context context, List<ResolveInfo> originals, boolean forDisplay) {
        return evaluate(context, originals, forDisplay, 0);
    }

    synchronized void invalidate() { cache.clear(); }

    synchronized Result evaluate(Context context, List<ResolveInfo> originals, boolean forDisplay, int userId) {
        Map<ResolveInfo, Integer> classifications = new IdentityHashMap<>();
        for (ResolveInfo original : originals) classifications.put(original, UNKNOWN);
        if (context == null || originals.size() > RuleConfig.MAX_ENTRIES) return new Result(originals, classifications);
        long started = SystemClock.elapsedRealtime();
        try {
            String key = cacheKey(context, originals, userId);
            MenuPolicyCache.Value cached = cache.get(key, started);
            if (cached != null) {
                List<ResolveInfo> display = new ArrayList<>(originals.size());
                for (int index = 0; index < originals.size(); index++) classifications.put(originals.get(index), cached.kinds[index]);
                for (int index : cached.order) display.add(originals.get(index));
                ModuleDiagnostics.metric(ModuleDiagnostics.Metric.POLICY_CACHE_HIT, 1);
                return new Result(forDisplay ? display : originals, classifications);
            }
            Menu menu = (Menu) newMenu.newInstance(context);
            Map<MenuItem, Entry> entries = new IdentityHashMap<>();
            for (int index = 0; index < originals.size(); index++) {
                ResolveInfo original = originals.get(index);
                if (original.activityInfo == null) throw new IllegalStateException("Missing menu activity");
                Entry entry = addItem(context, original, index, menu, entries);
                entries.put(entry.item, entry);
            }
            // MenuBuilder has already applied the ROM's menu order/category rules. This is
            // essential when the toolbar comparator ties action buttons or overflow items.
            for (int index = 0; index < menu.size(); index++) {
                Entry entry = entries.get(menu.getItem(index));
                entry.requiredBefore = (Boolean) requiresActionButton.invoke(entry.item);
            }
            // The installed collector applies AI policy before the toolbar comparator.
            Object collected = collectMenuItems.invoke(null, menu);
            if (!(collected instanceof List<?>) || ((List<?>) collected).size() != originals.size()) {
                throw new IllegalStateException("Incomplete installed toolbar menu");
            }
            List<Entry> ordered = new ArrayList<>(entries.size());
            Map<ResolveInfo, Boolean> seen = new IdentityHashMap<>();
            for (Object object : (List<?>) collected) {
                if (!(object instanceof MenuItem)) throw new IllegalStateException("Invalid toolbar menu item");
                MenuItem modified = (MenuItem) object;
                Entry entry = entries.get(modified);
                if (entry == null || seen.put(entry.info, true) != null) {
                    throw new IllegalStateException("Unmapped or duplicate toolbar menu item");
                }
                boolean requiredAfter = (Boolean) requiresActionButton.invoke(modified);
                entry.item = modified;
                classifications.put(entry.info, entry.handled || (!entry.requiredBefore && requiredAfter) ? FIXED : ORDINARY);
                ordered.add(entry);
            }
            ordered.sort((left, right) -> {
                try {
                    return (Integer) compareMenuItems.invoke(null, left.item, right.item);
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException("Installed menu comparison failed", error);
                }
            });
            List<ResolveInfo> displayOrder = new ArrayList<>(ordered.size());
            for (Entry entry : ordered) displayOrder.add(entry.info);
            int[] kinds = new int[originals.size()];
            int[] indices = new int[originals.size()];
            IdentityHashMap<ResolveInfo, Integer> positions = new IdentityHashMap<>();
            for (int index = 0; index < originals.size(); index++) {
                if (positions.put(originals.get(index), index) != null) throw new IllegalStateException("Duplicate input");
                kinds[index] = classifications.get(originals.get(index));
            }
            for (int index = 0; index < displayOrder.size(); index++) indices[index] = positions.get(displayOrder.get(index));
            cache.put(key, kinds, indices, started);
            ModuleDiagnostics.recover(ModuleDiagnostics.Code.POLICY_FAILED);
            return new Result(forDisplay ? displayOrder : originals, classifications);
        } catch (Throwable error) {
            // A partial menu cannot establish trustworthy slots for the settings list.
            for (ResolveInfo original : originals) classifications.put(original, UNKNOWN);
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.POLICY_FAILED);
            return new Result(originals, classifications);
        } finally {
            ModuleDiagnostics.metric(ModuleDiagnostics.Metric.POLICY_MS, SystemClock.elapsedRealtime() - started);
        }
    }

    private static String cacheKey(Context context, List<ResolveInfo> originals, int userId) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((userId + ":" + context.getResources().getConfiguration()).getBytes(StandardCharsets.UTF_8));
        Parcel parcel = Parcel.obtain();
        try {
            for (ResolveInfo info : originals) {
                parcel.setDataPosition(0);
                parcel.setDataSize(0);
                info.writeToParcel(parcel, 0);
                byte[] value = parcel.marshall();
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());
                digest.update(value);
            }
        } finally { parcel.recycle(); }
        return java.util.Base64.getEncoder().encodeToString(digest.digest());
    }

    private Entry addItem(Context context, ResolveInfo original, int index, Menu menu,
                          Map<MenuItem, Entry> existing) throws ReflectiveOperationException {
        ResolveInfo info = new ResolveInfo(original);
        info.activityInfo = new ActivityInfo(original.activityInfo);
        Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                .setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
        CharSequence label = info.loadLabel(context.getPackageManager());
        boolean handled = (Boolean) priority.invoke(editor, 100 + index, label, intent, info, menu);
        if (!handled) handled = (Boolean) speech.invoke(editor, 5, intent, label, info, menu);
        if (!handled) handled = (Boolean) search.invoke(editor, 6, intent, label, info, menu);
        if (!handled) menu.add(0, 0, 100 + index, label).setIntent(intent)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        if (menu.size() != existing.size() + 1) throw new IllegalStateException("Unexpected generated menu size");
        for (int itemIndex = 0; itemIndex < menu.size(); itemIndex++) {
            MenuItem item = menu.getItem(itemIndex);
            if (!existing.containsKey(item)) return new Entry(original, handled, item);
        }
        throw new IllegalStateException("Generated menu item unavailable");
    }
}
