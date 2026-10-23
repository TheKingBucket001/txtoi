package io.github.selectionmenucontrol;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "StaticFieldLeak"})
public final class SelectionMenuModule extends XposedModule {
    private static final long RULE_CACHE_MS = 1500L;
    private static final LastValidRules<SystemRuleStore.Snapshot> lastValidRules = new LastValidRules<>(30_000L);
    private static volatile SystemRuleStore.Snapshot cachedRules = SystemRuleStore.Snapshot.empty();
    private static volatile long nextRefreshAt;
    private static volatile Context systemContext;
    private static volatile String lastRespondedProbeNonce;
    private static volatile int moduleAppId = -1;
    private static SystemMenuClassifier menuClassifier;
    private static long nextClassifierAttemptAt;
    private static volatile boolean observersRegistered;
    private static long nextObserverAttemptAt;
    private static volatile boolean probePending = true;
    private static long nextProbePollAt;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (param.isSystemServer()) {
            ModuleDiagnostics.configure((warning, summary) -> log(warning ? android.util.Log.WARN : android.util.Log.INFO,
                    "SelectionMenuControl", summary));
            log(android.util.Log.INFO, "SelectionMenuControl", "Modern module loaded in system_server; waiting for system class loader");
        }
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            ClassLoader classLoader = param.getClassLoader();
            Class<?> packageManager = Class.forName(
                    "com.android.server.pm.IPackageManagerBase", false, classLoader);
            Method query = packageManager.getDeclaredMethod("queryIntentActivities",
                    Intent.class, String.class, long.class, int.class);
            Class<?> sliceClass = Class.forName("android.content.pm.ParceledListSlice", false, classLoader);
            Method getList = sliceClass.getMethod("getList");
            Constructor<?> newSlice = sliceClass.getConstructor(List.class);
            hook(query).intercept(chain -> interceptPublicProcessTextQuery(chain, sliceClass, getList, newSlice));
            log(android.util.Log.INFO, "SelectionMenuControl", "Hooked public PROCESS_TEXT queryIntentActivities boundary");
        } catch (Throwable error) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.HOOK_LOAD_FAILED);
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

    private static String componentOf(ResolveInfo info) {
        return info.activityInfo == null ? "" : new ComponentName(
                info.activityInfo.packageName, info.activityInfo.name).flattenToString();
    }

    private Object interceptPublicProcessTextQuery(XposedInterface.Chain chain, Class<?> sliceClass,
                                                   Method getList, Constructor<?> newSlice) throws Throwable {
        Intent intent = findIntentArgument(chain.getArgs());
        boolean processText = intent != null && Intent.ACTION_PROCESS_TEXT.equals(intent.getAction());
        Object result = chain.proceed();
        if (!processText || !sliceClass.isInstance(result)) {
            return result;
        }
        long queryStarted = SystemClock.elapsedRealtime();
        try {
            boolean originalQuery = isOriginalQuery(intent);
            SystemRuleStore.Snapshot rules = readRules();
            if (!originalQuery && (!rules.valid || (rules.hiddenComponents.isEmpty()
                    && rules.orderedComponents.isEmpty() && rules.aliases.isEmpty()))) {
                respondToProbe(false);
                ModuleDiagnostics.recover(ModuleDiagnostics.Code.QUERY_FAILED);
                return result;
            }
            Object contents = getList.invoke(result);
            if (!(contents instanceof List<?>)) {
                return result;
            }
            List<?> original = (List<?>) contents;
            List<ResolveInfo> resolved = new ArrayList<>(original.size());
            for (Object entry : original) {
                if (!(entry instanceof ResolveInfo)) {
                    return result;
                }
                resolved.add((ResolveInfo) entry);
            }
            Map<ResolveInfo, Integer> classifications = new IdentityHashMap<>();
            if (originalQuery || !rules.orderedComponents.isEmpty()) {
                long identity = Binder.clearCallingIdentity();
                try {
                    Context context = getSystemContext();
                    SystemMenuClassifier classifier = getMenuClassifier();
                    if (classifier != null) {
                        int userId = (Integer) chain.getArgs().get(chain.getArgs().size() - 1);
                        SystemMenuClassifier.Result policy = classifier.evaluate(contextForUser(context, userId), resolved, originalQuery, userId);
                        classifications = policy.classifications;
                        // Only the module UI receives the system toolbar's display order.
                        // Host queries retain their PM slots before applying ordinary rules.
                        if (originalQuery) resolved = policy.displayOrder;
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            if (originalQuery) {
                List<ResolveInfo> annotated = new ArrayList<>(resolved.size());
                for (ResolveInfo entry : resolved) {
                    ResolveInfo copy = new ResolveInfo(entry);
                    if (entry.activityInfo != null) {
                        copy.activityInfo = new ActivityInfo(entry.activityInfo);
                        copy.activityInfo.metaData = entry.activityInfo.metaData == null ? new Bundle()
                                : new Bundle(entry.activityInfo.metaData);
                        copy.activityInfo.metaData.putInt(SystemRuleStore.MENU_CLASSIFICATION_KEY,
                                classifications.getOrDefault(entry, SystemMenuClassifier.UNKNOWN));
                    }
                    annotated.add(copy);
                }
                Object annotatedResult = newSlice.newInstance(annotated);
                ModuleDiagnostics.recover(ModuleDiagnostics.Code.QUERY_FAILED);
                respondToProbe(true);
                return annotatedResult;
            }
            Map<ResolveInfo, Integer> effectiveClassifications = classifications;
            List<ResolveInfo> filtered = rules.apply(resolved, SelectionMenuModule::componentOf,
                    entry -> effectiveClassifications.getOrDefault(entry, SystemMenuClassifier.UNKNOWN) != SystemMenuClassifier.ORDINARY);
            // Public query results may share objects with PM caches. Change labels only on
            // response copies, after classification has used the original ROM labels.
            if (!rules.aliases.isEmpty()) {
                for (int index = 0; index < filtered.size(); index++) {
                    ResolveInfo entry = filtered.get(index);
                    String alias = rules.aliases.get(componentOf(entry));
                    if (alias != null) {
                        ResolveInfo copy = new ResolveInfo(entry);
                        copy.nonLocalizedLabel = alias;
                        filtered.set(index, copy);
                    }
                }
            }
            // Keep the original slice and ResolveInfo objects untouched. No internal resolution
            // query is filtered or reordered, including resolveActivity with resolveForStart=false.
            Object effectiveResult = filtered.equals(original) ? result : newSlice.newInstance(filtered);
            ModuleDiagnostics.recover(ModuleDiagnostics.Code.QUERY_FAILED);
            // Respond only after the original query and any required result replacement succeed.
            respondToProbe(false);
            return effectiveResult;
        } catch (Throwable error) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.QUERY_FAILED);
            return result;
        } finally {
            ModuleDiagnostics.metric(ModuleDiagnostics.Metric.QUERY_MS, SystemClock.elapsedRealtime() - queryStarted);
        }
    }

    private static synchronized SystemMenuClassifier getMenuClassifier() {
        if (menuClassifier == null && SystemClock.elapsedRealtime() >= nextClassifierAttemptAt) {
            nextClassifierAttemptAt = SystemClock.elapsedRealtime() + 5_000L;
            try {
                menuClassifier = new SystemMenuClassifier();
                ModuleDiagnostics.recover(ModuleDiagnostics.Code.POLICY_UNAVAILABLE);
            } catch (Throwable error) {
                ModuleDiagnostics.fault(ModuleDiagnostics.Code.POLICY_UNAVAILABLE);
            }
        }
        return menuClassifier;
    }

    private static boolean isOriginalQuery(Intent intent) {
        return isTrustedModuleQuery(intent, SystemRuleStore.QUERY_ORIGINAL_EXTRA);
    }

    private static boolean isTrustedModuleQuery(Intent intent, String extraKey) {
        try {
            if (!intent.getBooleanExtra(extraKey, false)) {
                return false;
            }
            int callingAppId = Binder.getCallingUid() % 100_000;
            int appId = moduleAppId;
            if (appId < 0) {
                Context context = getSystemContext();
                if (context == null) {
                    return false;
                }
                long identity = Binder.clearCallingIdentity();
                try {
                    appId = context.getPackageManager().getPackageUid(BuildConfig.APPLICATION_ID, 0) % 100_000;
                    moduleAppId = appId;
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            // Extra-based privileged query paths are accepted only from the module app.
            return callingAppId == appId;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static SystemRuleStore.Snapshot readRules() {
        long now = SystemClock.elapsedRealtime();
        if (now < nextRefreshAt) {
            ModuleDiagnostics.metric(ModuleDiagnostics.Metric.RULE_CACHE_HIT, 1);
            return cachedRules;
        }
        synchronized (SelectionMenuModule.class) {
            if (now < nextRefreshAt) {
                return cachedRules;
            }
            SystemRuleStore.Snapshot observed = queryRules();
            cachedRules = lastValidRules.accept(observed, observed.valid, now);
            // Notifications can be delayed by a busy system main thread. Keep the bounded
            // fallback even after registration, rather than extending stale rules to 30 seconds.
            nextRefreshAt = now + RULE_CACHE_MS;
            if (cachedRules != observed) nextRefreshAt = Math.min(nextRefreshAt, lastValidRules.expiresAt());
            return cachedRules;
        }
    }

    private static SystemRuleStore.Snapshot queryRules() {
        Context context = getSystemContext();
        if (context == null) return SystemRuleStore.Snapshot.unavailable(SystemRuleStore.ReadStatus.UNREADABLE);
        registerObservers(context);
        return SystemRuleStore.read(context);
    }

    private void respondToProbe(boolean trustedModuleQuery) {
        synchronized (SelectionMenuModule.class) {
            long now = SystemClock.elapsedRealtime();
            // Only the verified module caller bypasses polling. Its environment check must
            // not depend on delivery of a ContentObserver callback on the system main thread.
            if (!trustedModuleQuery && !probePending && now < nextProbePollAt) return;
            nextProbePollAt = now + (observersRegistered ? 30_000L : RULE_CACHE_MS);
            probePending = false;
            Context context = getSystemContext();
            if (context != null) {
                String nonce = SystemRuleStore.respondToProbe(context, lastRespondedProbeNonce);
                if (nonce != null) {
                    lastRespondedProbeNonce = nonce;
                }
            }
        }
    }

    private static void invalidatePolicy() {
        synchronized (SelectionMenuModule.class) {
            if (menuClassifier != null) menuClassifier.invalidate();
            moduleAppId = -1;
        }
        ModuleDiagnostics.event(ModuleDiagnostics.Code.CACHE_INVALIDATED, 0, 0);
    }

    private static void registerObservers(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (observersRegistered || now < nextObserverAttemptAt) return;
        nextObserverAttemptAt = now + 30_000L;
        if (Looper.getMainLooper() == null) return;
        long identity = Binder.clearCallingIdentity();
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange, android.net.Uri uri) {
                synchronized (SelectionMenuModule.class) {
                    if (Settings.Global.getUriFor(SystemRuleStore.PROBE_REQUEST_KEY).equals(uri)) probePending = true;
                    else nextRefreshAt = 0;
                }
            }
        };
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { invalidatePolicy(); }
        };
        try {
            context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(SystemRuleStore.SETTING_KEY), false, observer);
            context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(SystemRuleStore.PROBE_REQUEST_KEY), false, observer);
            IntentFilter packages = new IntentFilter();
            packages.addAction(Intent.ACTION_PACKAGE_ADDED);
            packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
            packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
            packages.addDataScheme("package");
            registerSystemReceiver(context, receiver, packages);
            IntentFilter configuration = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
            configuration.addAction("android.intent.action.USER_SWITCHED");
            registerSystemReceiver(context, receiver, configuration);
            observersRegistered = true;
        } catch (Exception error) {
            // Undo partial registration before a bounded retry, rather than duplicating listeners.
            try { context.getContentResolver().unregisterContentObserver(observer); } catch (Exception ignored) { }
            try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.RULES_READ_FAILED);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Before API 33 these protected system broadcasts use the legacy overload.
    private static void registerSystemReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, filter);
    }

    private static Context contextForUser(Context context, int userId) throws ReflectiveOperationException {
        if (context == null || userId == 0) return context;
        Class<?> userType = Class.forName("android.os.UserHandle");
        Object user = userType.getMethod("of", int.class).invoke(null, userId);
        return (Context) Context.class.getMethod("createContextAsUser", userType, int.class).invoke(context, user, 0);
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
