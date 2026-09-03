package io.github.selectionmenucontrol;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

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
    private static volatile String lastRespondedProbeNonce;

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
            log(android.util.Log.INFO, "SelectionMenuControl", "Hooked ComputerEngine 9-argument queryIntentActivitiesInternal");
        } catch (Throwable error) {
            log(android.util.Log.ERROR, "SelectionMenuControl", "ComputerEngine hook not installed", error);
        }
    }

    private Object interceptProcessTextQuery(XposedInterface.Chain chain) throws Throwable {
        Intent intent = findIntentArgument(chain.getArgs());
        boolean processText = intent != null && Intent.ACTION_PROCESS_TEXT.equals(intent.getAction());
        if (processText) {
            respondToProbe();
        }
        Object result = chain.proceed();
        if (!(result instanceof List<?>)) {
            return result;
        }
        if (!processText) {
            return result;
        }
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

    private void respondToProbe() {
        Context context = getSystemContext();
        if (context != null) {
            String nonce = SystemRuleStore.respondToProbe(context, lastRespondedProbeNonce);
            if (nonce != null) {
                lastRespondedProbeNonce = nonce;
            }
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
