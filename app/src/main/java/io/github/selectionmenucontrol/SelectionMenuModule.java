package io.github.selectionmenucontrol;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "StaticFieldLeak"})
public final class SelectionMenuModule extends XposedModule {
    private static final long RULE_CACHE_MS = 1500L;
    private static volatile SystemRuleStore.Snapshot cachedRules = SystemRuleStore.Snapshot.empty();
    private static volatile long nextRefreshAt;
    private static volatile Context systemContext;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (param.isSystemServer()) {
            log(android.util.Log.INFO, "SelectionMenuControl", "Modern module loaded in system_server; waiting for system class loader");
        }
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            Class<?> computerEngine = Class.forName(
                    "com.android.server.pm.ComputerEngine", false, param.getClassLoader());
            Method target = computerEngine.getDeclaredMethod(
                    "queryIntentActivitiesInternal",
                    Intent.class,
                    String.class,
                    long.class,
                    long.class,
                    int.class,
                    int.class,
                    int.class,
                    boolean.class,
                    boolean.class);
            hook(target).intercept(this::interceptProcessTextQuery);
            reportLoaded();
            scheduleStatusReport();
            log(android.util.Log.INFO, "SelectionMenuControl", "Hooked ComputerEngine 9-argument queryIntentActivitiesInternal");
        } catch (Throwable error) {
            log(android.util.Log.ERROR, "SelectionMenuControl", "ComputerEngine hook not installed", error);
        }
    }

    private Object interceptProcessTextQuery(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (!(result instanceof List<?>)) {
            return result;
        }
        Intent intent = findIntentArgument(chain.getArgs());
        if (intent == null || !Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) {
            return result;
        }
        // The provider can be unavailable during direct boot. Refresh the proof once a real query arrives.
        reportLoaded();
        SystemRuleStore.Snapshot rules = readRules();
        if (rules.hiddenComponents.isEmpty()) {
            return result;
        }
        try {
            List<?> original = (List<?>) result;
            List<ResolveInfo> filtered = new ArrayList<>(original.size());
            for (Object entry : original) {
                if (!(entry instanceof ResolveInfo)) {
                    return result;
                }
                ResolveInfo info = (ResolveInfo) entry;
                if (!isHidden(info, rules.hiddenComponents)) {
                    filtered.add(info);
                }
            }
            return filtered.size() == original.size() ? result : filtered;
        } catch (Throwable ignored) {
            return result;
        }
    }

    private static Intent findIntentArgument(List<Object> arguments) {
        for (Object argument : arguments) {
            if (argument instanceof Intent) {
                return (Intent) argument;
            }
        }
        return null;
    }

    private static boolean isHidden(ResolveInfo info, Set<String> hiddenComponents) {
        return info.activityInfo != null && hiddenComponents.contains(
                new ComponentName(info.activityInfo.packageName, info.activityInfo.name).flattenToString());
    }

    private static SystemRuleStore.Snapshot readRules() {
        long now = SystemClock.elapsedRealtime();
        if (now < nextRefreshAt) {
            return cachedRules;
        }
        synchronized (SelectionMenuModule.class) {
            if (now < nextRefreshAt) {
                return cachedRules;
            }
            cachedRules = queryRules();
            nextRefreshAt = now + RULE_CACHE_MS;
            return cachedRules;
        }
    }

    private static SystemRuleStore.Snapshot queryRules() {
        Context context = getSystemContext();
        return context == null ? SystemRuleStore.Snapshot.empty() : SystemRuleStore.read(context);
    }

    private void reportLoaded() {
        Context context = getSystemContext();
        if (context != null) {
            SystemRuleStore.reportSystemServerLoaded(context);
        } else {
            log(android.util.Log.WARN, "SelectionMenuControl", "System context is unavailable; status heartbeat deferred");
        }
    }

    private void scheduleStatusReport() {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(this::reportLoaded, 1000L);
        } catch (Throwable ignored) {
            // The next PROCESS_TEXT query retries the report if the system looper is unavailable.
        }
    }

    private static Context getSystemContext() {
        try {
            Context context = systemContext;
            if (context != null) {
                return context;
            }
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentActivityThread");
            Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
            context = (Context) getSystemContext.invoke(current.invoke(null));
            systemContext = context;
            return context;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
