package io.github.taskfilter;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.VersionedPackage;
import android.os.Parcel;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * All hooks live in the target's own process: they rewrite what the system hands back to it.
 *
 * Every hook is fail-open (an error leaves the original result untouched) except where the
 * original data has already been consumed, and nothing here ever logs a package name.
 */
final class Filters {
    private static final String T = "[task-filter] ";
    private static final int PER_USER_RANGE = 100000;
    private static final int FIRST_APPLICATION_UID = 10000;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    /** Set while this module queries the package manager itself, so its own lookups are not filtered. */
    private static final ThreadLocal<Boolean> BYPASS = new ThreadLocal<>();

    private static volatile boolean idsStale = true;
    private static volatile Set<Integer> hiddenAppIds = Collections.emptySet();
    private static volatile String homePkg = "com.miui.home";
    private static volatile int homeAppId = -1;
    private static volatile int homePid = -1;

    private Filters() {}

    interface Body {
        void run(XC_MethodHook.MethodHookParam p) throws Throwable;
    }

    interface Pred {
        boolean hidden(Object item, Set<String> hidden);
    }

    // ------------------------------------------------------------------------------------------
    // Installation

    static void install(ClassLoader cl, String process) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        List<String> ok = new ArrayList<>();
        hookTasks(cl, ok);
        hookProcesses(ok);
        hookUsage(ok);
        hookForegroundInfo(cl, ok);
        hookProcessObservers(cl, ok);
        hookTaskStackListener(cl, ok);
        hookPackageManager(cl, ok);
        hookLauncherApps(ok);
        if (Config.verbose()) {
            XposedBridge.log(T + "loaded in " + process + " (user " + Config.userId() + "): " + ok);
        }
    }

    static void onConfigChanged() {
        idsStale = true;
    }

    private static void add(List<String> ok, String name, Set<XC_MethodHook.Unhook> hooks) {
        if (hooks != null && !hooks.isEmpty()) ok.add(name);
    }

    private static XC_MethodHook after(final String what, final Body body) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(BYPASS.get())) return;
                try {
                    body.run(p);
                } catch (Throwable t) {
                    logOnce(what, t);
                }
            }
        };
    }

    private static XC_MethodHook before(final String what, final Body body) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(BYPASS.get())) return;
                try {
                    body.run(p);
                } catch (Throwable t) {
                    logOnce(what, t);
                }
            }
        };
    }

    // ------------------------------------------------------------------------------------------
    // Tasks: ActivityManager.getRunningTasks / getRecentTasks (the "CollectRunningTasks" event)

    private static void hookTasks(ClassLoader cl, List<String> ok) {
        XC_MethodHook h = after("tasks", p -> filterList(p, "tasks", Filters::taskHidden));
        add(ok, "getRunningTasks", XposedBridge.hookAllMethods(ActivityManager.class, "getRunningTasks", h));
        add(ok, "getRecentTasks", XposedBridge.hookAllMethods(ActivityManager.class, "getRecentTasks", h));
        Class<?> atm = XposedHelpers.findClassIfExists("android.app.ActivityTaskManager", cl);
        if (atm != null) add(ok, "ATM.getTasks", XposedBridge.hookAllMethods(atm, "getTasks", h));
    }

    private static final String[] TASK_COMPONENTS = {"baseActivity", "topActivity", "origActivity", "realActivity"};

    static boolean taskHidden(Object task, Set<String> h) {
        if (task == null) return false;
        Object intent = field(task, "baseIntent");
        if (intent instanceof Intent) {
            Intent i = (Intent) intent;
            if (i.getComponent() != null && h.contains(i.getComponent().getPackageName())) return true;
            if (i.getPackage() != null && h.contains(i.getPackage())) return true;
        }
        for (String name : TASK_COMPONENTS) {
            Object cn = field(task, name);
            if (cn instanceof ComponentName && h.contains(((ComponentName) cn).getPackageName())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Processes and services

    private static void hookProcesses(List<String> ok) {
        add(ok, "getRunningAppProcesses", XposedBridge.hookAllMethods(ActivityManager.class, "getRunningAppProcesses",
                after("processes", p -> filterList(p, "processes", (o, h) -> {
                    if (!(o instanceof ActivityManager.RunningAppProcessInfo)) return false;
                    ActivityManager.RunningAppProcessInfo pi = (ActivityManager.RunningAppProcessInfo) o;
                    if (pi.processName != null && h.contains(basePkg(pi.processName))) return true;
                    if (pi.pkgList != null) for (String s : pi.pkgList) if (h.contains(s)) return true;
                    return false;
                }))));
        add(ok, "getRunningServices", XposedBridge.hookAllMethods(ActivityManager.class, "getRunningServices",
                after("services", p -> filterList(p, "services", (o, h) -> {
                    if (!(o instanceof ActivityManager.RunningServiceInfo)) return false;
                    ActivityManager.RunningServiceInfo si = (ActivityManager.RunningServiceInfo) o;
                    return (si.service != null && h.contains(si.service.getPackageName()))
                            || (si.process != null && h.contains(basePkg(si.process)));
                }))));
    }

    static String basePkg(String process) {
        int i = process.indexOf(':');
        return i < 0 ? process : process.substring(0, i);
    }

    // ------------------------------------------------------------------------------------------
    // Usage statistics

    private static void hookUsage(List<String> ok) {
        add(ok, "queryEvents", XposedBridge.hookAllMethods(UsageStatsManager.class, "queryEvents",
                after("usageEvents", p -> {
                    Object r = p.getResult();
                    Object filtered = filterUsageEvents(r);
                    if (filtered != r) p.setResult(filtered);
                })));
        add(ok, "queryUsageStats", XposedBridge.hookAllMethods(UsageStatsManager.class, "queryUsageStats",
                after("usageStats", p -> filterList(p, "usageStats",
                        (o, h) -> o instanceof UsageStats && h.contains(((UsageStats) o).getPackageName())))));
        add(ok, "queryAndAggregateUsageStats", XposedBridge.hookAllMethods(UsageStatsManager.class,
                "queryAndAggregateUsageStats", after("usageStats", p -> {
                    Object r = p.getResult();
                    if (!(r instanceof Map)) return;
                    Set<String> h = Config.hidden();
                    if (h.isEmpty()) return;
                    Map<?, ?> in = (Map<?, ?>) r;
                    Map<Object, Object> out = new HashMap<>();
                    for (Map.Entry<?, ?> e : in.entrySet()) {
                        if (!(e.getKey() instanceof String && h.contains((String) e.getKey()))) out.put(e.getKey(), e.getValue());
                    }
                    if (out.size() != in.size()) {
                        count("usageStats", in.size() - out.size());
                        p.setResult(out);
                    }
                })));
    }

    private static volatile Constructor<?> usageCtor;
    private static volatile boolean usageCtorResolved;
    private static volatile Field[] eventStringFields;

    private static Constructor<?> usageCtor() {
        if (!usageCtorResolved) {
            Constructor<?> found = null;
            try {
                for (Constructor<?> c : UsageEvents.class.getDeclaredConstructors()) {
                    Class<?>[] t = c.getParameterTypes();
                    if (t.length < 2 || t[0] != List.class || t[1] != String[].class) continue;
                    if (t.length == 3 && t[2] == boolean.class) found = c;
                    else if (t.length == 2 && found == null) found = c;
                }
                if (found != null) found.setAccessible(true);
            } catch (Throwable t) {
                logOnce("usageCtor", t);
                found = null;
            }
            usageCtor = found;
            usageCtorResolved = true;
        }
        return usageCtor;
    }

    static Object filterUsageEvents(Object r) {
        if (!(r instanceof UsageEvents)) return r;
        Set<String> h = Config.hidden();
        if (h.isEmpty()) return r;
        Constructor<?> ctor = usageCtor();
        if (ctor == null) return r; // cannot rebuild: leave it untouched
        UsageEvents src = (UsageEvents) r;
        boolean includeTaskRoots = !Boolean.FALSE.equals(field(src, "mIncludeTaskRoots"));
        ArrayList<UsageEvents.Event> kept = new ArrayList<>();
        TreeSet<String> pool = new TreeSet<>();
        int removed = 0;
        try {
            while (src.hasNextEvent()) {
                UsageEvents.Event e = new UsageEvents.Event();
                if (!src.getNextEvent(e)) break;
                Object root = field(e, "mTaskRootPackage");
                if (h.contains(e.getPackageName()) || (root instanceof String && h.contains((String) root))) {
                    removed++;
                    continue;
                }
                kept.add(e);
                collectStrings(e, pool);
            }
            String[] strings = pool.toArray(new String[0]);
            Object probe = newUsageEvents(ctor, kept, strings, includeTaskRoots);
            UsageEvents.Event tmp = new UsageEvents.Event();
            int n = 0;
            while (((UsageEvents) probe).getNextEvent(tmp)) n++;
            if (n != kept.size()) throw new IllegalStateException("rebuild mismatch");
            Object out = newUsageEvents(ctor, kept, strings, includeTaskRoots);
            count("usageEvents", removed);
            return out;
        } catch (Throwable t) {
            logOnce("usageEvents", t);
            return emptyUsageEvents(r); // the original iterator is already consumed
        }
    }

    private static Object newUsageEvents(Constructor<?> ctor, List<UsageEvents.Event> events, String[] pool,
                                         boolean includeTaskRoots) throws Exception {
        return ctor.getParameterTypes().length == 3
                ? ctor.newInstance(new ArrayList<>(events), pool, includeTaskRoots)
                : ctor.newInstance(new ArrayList<>(events), pool);
    }

    private static Object emptyUsageEvents(Object fallback) {
        try {
            Constructor<?> c = UsageEvents.class.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable ignored) {
        }
        try {
            Constructor<?> c = usageCtor();
            if (c != null) return newUsageEvents(c, Collections.<UsageEvents.Event>emptyList(), new String[0], true);
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static void collectStrings(UsageEvents.Event e, Set<String> pool) {
        Field[] fields = eventStringFields;
        if (fields == null) {
            List<Field> list = new ArrayList<>();
            for (Field f : UsageEvents.Event.class.getDeclaredFields()) {
                if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) {
                    try {
                        f.setAccessible(true);
                        list.add(f);
                    } catch (Throwable ignored) {
                    }
                }
            }
            fields = list.toArray(new Field[0]);
            eventStringFields = fields;
        }
        for (Field f : fields) {
            try {
                Object v = f.get(e);
                if (v instanceof String) pool.add((String) v);
            } catch (Throwable ignored) {
            }
        }
        if (e.getPackageName() != null) pool.add(e.getPackageName());
        if (e.getClassName() != null) pool.add(e.getClassName());
    }

    // ------------------------------------------------------------------------------------------
    // Foreground app: miui.process.ForegroundInfo (IForegroundInfoListener, "onPackageGoForeground")

    private static void hookForegroundInfo(ClassLoader cl, List<String> ok) {
        Class<?> fi = XposedHelpers.findClassIfExists("miui.process.ForegroundInfo", cl);
        if (fi != null) {
            add(ok, "ForegroundInfo", XposedBridge.hookAllConstructors(fi, after("foreground", p -> {
                if (p.args.length > 0 && p.args[0] instanceof Parcel) learnHome(p.thisObject);
                sanitizeForeground(p.thisObject);
            })));
        }
        Class<?> listener = XposedHelpers.findClassIfExists("com.xiaomi.xmsf.pushcontrol.ProcessMonitor$1", cl);
        if (listener != null) {
            add(ok, "onForegroundInfoChanged", XposedBridge.hookAllMethods(listener, "onForegroundInfoChanged",
                    before("foreground", p -> {
                        if (p.args.length > 0) sanitizeForeground(p.args[0]);
                    })));
        }
    }

    private static void learnHome(Object info) {
        Object pkg = field(info, "mForegroundPackageName");
        Object pid = field(info, "mForegroundPid");
        if (pkg != null && pkg.equals(homePkg) && pid instanceof Integer && (Integer) pid > 0) homePid = (Integer) pid;
    }

    /** Rewrites every hidden package in the object (current, last, multi-window) to the launcher. */
    static void sanitizeForeground(Object info) {
        if (info == null) return;
        Set<String> h = Config.hidden();
        if (h.isEmpty()) return;
        ensureIds();
        boolean changed = false;
        for (Field f : stringFields(info.getClass())) {
            Object v;
            try {
                v = f.get(info);
            } catch (Throwable t) {
                continue;
            }
            if (!(v instanceof String) || !h.contains(v)) continue;
            try {
                f.set(info, homePkg);
            } catch (Throwable t) {
                continue;
            }
            changed = true;
            String name = f.getName();
            if (!name.endsWith("PackageName")) continue;
            String base = name.substring(0, name.length() - "PackageName".length());
            Field uid = intField(info.getClass(), base + "Uid");
            if (uid != null) {
                try {
                    int old = uid.getInt(info);
                    if (homeAppId > 0) uid.setInt(info, (old / PER_USER_RANGE) * PER_USER_RANGE + homeAppId);
                } catch (Throwable ignored) {
                }
            }
            Field pid = intField(info.getClass(), base + "Pid");
            if (pid != null && homePid > 0) {
                try {
                    pid.setInt(info, homePid);
                } catch (Throwable ignored) {
                }
            }
        }
        if (changed) count("foreground", 1);
    }

    // ------------------------------------------------------------------------------------------
    // IProcessObserver callbacks (process started/died, foreground activities, importance)

    private static final String[] OBSERVERS = {
            "com.xiaomi.xmsf.pushcontrol.ProcessObserver",
            "com.xiaomi.xmsf.pushcontrol.ProcessMonitor",
    };

    private static void hookProcessObservers(ClassLoader cl, List<String> ok) {
        XC_MethodHook gate = before("processEvents", p -> {
            if (observerEventHidden(p.method.getName(), p.args)) {
                p.setResult(null);
                count("processEvents", 1);
            }
        });
        for (String name : OBSERVERS) {
            Class<?> c = XposedHelpers.findClassIfExists(name, cl);
            if (c == null) continue;
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                String mn = m.getName();
                if (Modifier.isAbstract(m.getModifiers()) || m.getReturnType() != void.class) continue;
                if (!mn.startsWith("onForeground") && !mn.startsWith("onProcess") && !mn.equals("onImportanceChanged")) continue;
                try {
                    XposedBridge.hookMethod(m, gate);
                    n++;
                } catch (Throwable t) {
                    logOnce("observer", t);
                }
            }
            if (n > 0) ok.add(name.substring(name.lastIndexOf('.') + 1) + "x" + n);
        }
    }

    static boolean observerEventHidden(String method, Object[] a) {
        if (Config.hidden().isEmpty() || a == null) return false;
        if ("onProcessStarted".equals(method)) {
            // (pid, processUid, packageUid, packageName, processName)
            Set<String> h = Config.hidden();
            for (int i = 1; i < a.length; i++) {
                if (a[i] instanceof String) {
                    String s = (String) a[i];
                    if (h.contains(s) || h.contains(basePkg(s))) return true;
                } else if ((i == 1 || i == 2) && a[i] instanceof Integer && uidHidden((Integer) a[i])) {
                    return true;
                }
            }
            return false;
        }
        // (pid, uid, ...)
        return a.length > 1 && a[1] instanceof Integer && uidHidden((Integer) a[1]);
    }

    static boolean uidHidden(int uid) {
        int appId = uid % PER_USER_RANGE;
        if (appId < FIRST_APPLICATION_UID) return false;
        ensureIds();
        return hiddenAppIds.contains(appId);
    }

    // ------------------------------------------------------------------------------------------
    // Task stack events: xmsf parses ITaskStackListener transactions itself in TaskStackListenerHelper$1

    private static void hookTaskStackListener(ClassLoader cl, List<String> ok) {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.xiaomi.xmsf.push.service.notificationcollection.TaskStackListenerHelper$1", cl);
        Class<?> stub = XposedHelpers.findClassIfExists("android.app.ITaskStackListener$Stub", cl);
        if (c == null || stub == null) return;
        final Map<Integer, String> codes = new HashMap<>();
        for (String n : new String[]{"onTaskMovedToFront", "onTaskDescriptionChanged", "onActivityRestartAttempt", "onTaskCreated"}) {
            try {
                Field f = stub.getDeclaredField("TRANSACTION_" + n);
                f.setAccessible(true);
                codes.put(f.getInt(null), n);
            } catch (Throwable ignored) {
            }
        }
        if (codes.isEmpty()) return;
        add(ok, "TaskStackListener", XposedBridge.hookAllMethods(c, "onTransact", before("taskEvents", p -> {
            if (p.args.length < 2 || !(p.args[0] instanceof Integer) || !(p.args[1] instanceof Parcel)) return;
            String name = codes.get((Integer) p.args[0]);
            if (name == null) return;
            Set<String> h = Config.hidden();
            if (h.isEmpty()) return;
            Parcel data = (Parcel) p.args[1];
            Parcel copy = Parcel.obtain();
            try {
                copy.appendFrom(data, 0, data.dataSize());
                copy.setDataPosition(0);
                copy.enforceInterface("android.app.ITaskStackListener");
                boolean hide;
                if ("onTaskCreated".equals(name)) {
                    copy.readInt();
                    ComponentName cn = copy.readTypedObject(ComponentName.CREATOR);
                    hide = cn != null && h.contains(cn.getPackageName());
                } else {
                    hide = taskHidden(copy.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR), h);
                }
                if (hide) {
                    p.setResult(Boolean.TRUE); // consumed: the event never reaches xmsf
                    count("taskEvents", 1);
                }
            } finally {
                copy.recycle();
            }
        })));
    }

    // ------------------------------------------------------------------------------------------
    // Package manager: hidden packages look uninstalled to the target

    private static final String[] PM_LISTS = {"getInstalledPackages", "getInstalledPackagesAsUser",
            "getInstalledApplications", "getInstalledApplicationsAsUser", "getPackagesHoldingPermissions"};
    private static final String[] PM_RESOLVE_LISTS = {"queryIntentActivities", "queryIntentActivitiesAsUser",
            "queryIntentActivityOptions", "queryIntentServices", "queryIntentServicesAsUser", "queryBroadcastReceivers",
            "queryBroadcastReceiversAsUser", "queryIntentContentProviders", "queryIntentContentProvidersAsUser"};
    private static final String[] PM_LOOKUPS = {"getPackageInfo", "getPackageInfoAsUser", "getApplicationInfo",
            "getApplicationInfoAsUser", "getPackageUid", "getPackageUidAsUser", "getPackageGids", "getInstallSourceInfo",
            "getApplicationIcon", "getApplicationBanner", "getApplicationLogo", "getResourcesForApplication",
            "getResourcesForApplicationAsUser"};
    private static final String[] PM_RESOLVE_ONE = {"resolveActivity", "resolveActivityAsUser", "resolveService",
            "resolveServiceAsUser", "resolveContentProvider", "resolveContentProviderAsUser"};
    private static final String[] PM_NULL_FOR_HIDDEN = {"getLaunchIntentForPackage", "getLeanbackLaunchIntentForPackage"};
    private static final String[] PM_ILLEGAL_FOR_HIDDEN = {"getInstallerPackageName", "getApplicationEnabledSetting"};

    private static void hookPackageManager(ClassLoader cl, List<String> ok) {
        Class<?> apm = XposedHelpers.findClassIfExists("android.app.ApplicationPackageManager", cl);
        if (apm == null) return;
        int n = 0;
        XC_MethodHook lists = after("pkgList", p -> filterList(p, "pkgList", (o, h) ->
                (o instanceof PackageInfo && h.contains(((PackageInfo) o).packageName))
                        || (o instanceof ApplicationInfo && h.contains(((ApplicationInfo) o).packageName))));
        for (String m : PM_LISTS) n += XposedBridge.hookAllMethods(apm, m, lists).size();

        XC_MethodHook resolveLists = after("resolve", p -> filterList(p, "resolve", Filters::resolveHidden));
        for (String m : PM_RESOLVE_LISTS) n += XposedBridge.hookAllMethods(apm, m, resolveLists).size();

        XC_MethodHook resolveOne = after("resolve", p -> {
            Object r = p.getResult();
            if (r != null && resolveHidden(r, Config.hidden())) {
                p.setResult(null);
                count("resolve", 1);
            }
        });
        for (String m : PM_RESOLVE_ONE) n += XposedBridge.hookAllMethods(apm, m, resolveOne).size();

        // Single-package lookups throw NameNotFoundException - but only where the method declares it.
        XC_MethodHook notFound = before("pkgLookup", p -> {
            String pkg = pkgArg(p.args);
            if (pkg != null && Config.hidden().contains(pkg)) {
                p.setThrowable(new PackageManager.NameNotFoundException(pkg));
                count("pkgLookup", 1);
            }
        });
        Set<String> lookups = new HashSet<>(Arrays.asList(PM_LOOKUPS));
        for (Method m : apm.getDeclaredMethods()) {
            if (!lookups.contains(m.getName()) || Modifier.isAbstract(m.getModifiers())) continue;
            Class<?>[] t = m.getParameterTypes();
            if (t.length == 0 || (t[0] != String.class && t[0] != VersionedPackage.class)) continue;
            if (!Arrays.asList(m.getExceptionTypes()).contains(PackageManager.NameNotFoundException.class)) continue;
            try {
                XposedBridge.hookMethod(m, notFound);
                n++;
            } catch (Throwable t2) {
                logOnce("pkgLookup", t2);
            }
        }

        XC_MethodHook nullResult = before("pkgLookup", p -> {
            String pkg = pkgArg(p.args);
            if (pkg != null && Config.hidden().contains(pkg)) {
                p.setResult(null);
                count("pkgLookup", 1);
            }
        });
        for (String m : PM_NULL_FOR_HIDDEN) n += XposedBridge.hookAllMethods(apm, m, nullResult).size();

        XC_MethodHook illegal = before("pkgLookup", p -> {
            String pkg = pkgArg(p.args);
            if (pkg != null && Config.hidden().contains(pkg)) {
                p.setThrowable(new IllegalArgumentException("Unknown package: " + pkg));
                count("pkgLookup", 1);
            }
        });
        for (String m : PM_ILLEGAL_FOR_HIDDEN) n += XposedBridge.hookAllMethods(apm, m, illegal).size();

        n += XposedBridge.hookAllMethods(apm, "checkSignatures", before("pkgLookup", p -> {
            if (p.args.length == 2 && p.args[0] instanceof String && p.args[1] instanceof String) {
                Set<String> h = Config.hidden();
                if (h.contains((String) p.args[0]) || h.contains((String) p.args[1])) {
                    p.setResult(PackageManager.SIGNATURE_UNKNOWN_PACKAGE);
                }
            }
        })).size();

        n += XposedBridge.hookAllMethods(apm, "getPackagesForUid", after("uidNames", p -> {
            Object r = p.getResult();
            if (!(r instanceof String[])) return;
            Set<String> h = Config.hidden();
            if (h.isEmpty()) return;
            List<String> keep = new ArrayList<>();
            for (String s : (String[]) r) if (!h.contains(s)) keep.add(s);
            if (keep.size() != ((String[]) r).length) {
                count("uidNames", 1);
                p.setResult(keep.isEmpty() ? null : keep.toArray(new String[0]));
            }
        })).size();
        n += XposedBridge.hookAllMethods(apm, "getNameForUid", after("uidNames", p -> {
            Object r = p.getResult();
            if (r instanceof String && (Config.hidden().contains(r) || (p.args.length > 0 && p.args[0] instanceof Integer
                    && uidHidden((Integer) p.args[0])))) {
                p.setResult(null);
                count("uidNames", 1);
            }
        })).size();
        n += XposedBridge.hookAllMethods(apm, "getNamesForUids", after("uidNames", p -> {
            Object r = p.getResult();
            if (!(r instanceof String[])) return;
            String[] names = ((String[]) r).clone();
            boolean changed = false;
            for (int i = 0; i < names.length; i++) {
                if (names[i] != null && Config.hidden().contains(names[i])) {
                    names[i] = null;
                    changed = true;
                }
            }
            if (changed) {
                count("uidNames", 1);
                p.setResult(names);
            }
        })).size();
        if (n > 0) ok.add("PackageManager x" + n);
    }

    static boolean resolveHidden(Object o, Set<String> h) {
        if (o instanceof ResolveInfo) {
            ResolveInfo ri = (ResolveInfo) o;
            if (ri.activityInfo != null && h.contains(ri.activityInfo.packageName)) return true;
            if (ri.serviceInfo != null && h.contains(ri.serviceInfo.packageName)) return true;
            if (ri.providerInfo != null && h.contains(ri.providerInfo.packageName)) return true;
            return ri.resolvePackageName != null && h.contains(ri.resolvePackageName);
        }
        return o instanceof ProviderInfo && h.contains(((ProviderInfo) o).packageName);
    }

    private static String pkgArg(Object[] args) {
        if (args == null || args.length == 0) return null;
        if (args[0] instanceof String) return (String) args[0];
        if (args[0] instanceof VersionedPackage) return ((VersionedPackage) args[0]).getPackageName();
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // LauncherApps

    private static void hookLauncherApps(List<String> ok) {
        add(ok, "LauncherApps.getActivityList", XposedBridge.hookAllMethods(LauncherApps.class, "getActivityList",
                after("launcher", p -> filterList(p, "launcher", (o, h) -> o instanceof LauncherActivityInfo
                        && h.contains(((LauncherActivityInfo) o).getComponentName().getPackageName())))));
        add(ok, "LauncherApps.isPackageEnabled", XposedBridge.hookAllMethods(LauncherApps.class, "isPackageEnabled",
                before("launcher", p -> {
                    if (p.args.length > 0 && p.args[0] instanceof String && Config.hidden().contains((String) p.args[0])) {
                        p.setResult(Boolean.FALSE);
                    }
                })));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers

    /** Replaces a List result with a copy that has the hidden entries removed. */
    static void filterList(XC_MethodHook.MethodHookParam p, String what, Pred pred) {
        Object r = p.getResult();
        if (!(r instanceof List)) return;
        Set<String> h = Config.hidden();
        if (h.isEmpty()) return;
        List<?> in = (List<?>) r;
        ArrayList<Object> out = null;
        for (int i = 0; i < in.size(); i++) {
            Object item = in.get(i);
            if (pred.hidden(item, h)) {
                if (out == null) {
                    out = new ArrayList<>(in.size());
                    for (int j = 0; j < i; j++) out.add(in.get(j));
                }
            } else if (out != null) {
                out.add(item);
            }
        }
        if (out != null) {
            count(what, in.size() - out.size());
            p.setResult(out);
        }
    }

    private static void ensureIds() {
        if (idsStale) recomputeIds();
    }

    private static synchronized void recomputeIds() {
        if (!idsStale) return;
        Context ctx = currentApplication();
        if (ctx == null) return; // too early; try again on the next event
        Set<String> config = Config.hidden();
        Set<Integer> ids = new HashSet<>();
        Boolean previous = BYPASS.get();
        BYPASS.set(Boolean.TRUE);
        try {
            PackageManager pm = ctx.getPackageManager();
            for (String pkg : config) {
                try {
                    int appId = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).uid % PER_USER_RANGE;
                    if (appId >= FIRST_APPLICATION_UID) ids.add(appId);
                } catch (Throwable ignored) {
                }
            }
            try {
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
                if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
                    homePkg = ri.activityInfo.packageName;
                    homeAppId = ri.activityInfo.applicationInfo.uid % PER_USER_RANGE;
                } else {
                    homeAppId = pm.getApplicationInfo(homePkg, 0).uid % PER_USER_RANGE;
                }
            } catch (Throwable ignored) {
            }
        } finally {
            BYPASS.set(previous);
        }
        hiddenAppIds = ids;
        if (Config.hidden() == config) idsStale = false;
    }

    private static Context currentApplication() {
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", null);
            return (Context) XposedHelpers.callStaticMethod(at, "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }

    private static final Map<String, Object> FIELDS = new ConcurrentHashMap<>();
    private static final Object MISSING = new Object();
    private static final Map<Class<?>, Field[]> STRING_FIELDS = new ConcurrentHashMap<>();

    static Object field(Object obj, String name) {
        if (obj == null) return null;
        Field f = findField(obj.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> c, String name) {
        String key = c.getName() + '#' + name;
        Object cached = FIELDS.get(key);
        if (cached == null) {
            Field found = null;
            for (Class<?> k = c; k != null && found == null; k = k.getSuperclass()) {
                try {
                    found = k.getDeclaredField(name);
                    found.setAccessible(true);
                } catch (Throwable ignored) {
                    found = null;
                }
            }
            cached = found != null ? found : MISSING;
            FIELDS.put(key, cached);
        }
        return cached == MISSING ? null : (Field) cached;
    }

    private static Field intField(Class<?> c, String name) {
        Field f = findField(c, name);
        return f != null && f.getType() == int.class ? f : null;
    }

    private static Field[] stringFields(Class<?> c) {
        Field[] fields = STRING_FIELDS.get(c);
        if (fields == null) {
            List<Field> list = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    list.add(f);
                } catch (Throwable ignored) {
                }
            }
            fields = list.toArray(new Field[0]);
            STRING_FIELDS.put(c, fields);
        }
        return fields;
    }

    // ------------------------------------------------------------------------------------------
    // Logging: counts only, never package names

    private static final Map<String, int[]> COUNTS = new TreeMap<>();
    private static long lastSummary;
    private static final Set<String> LOGGED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    static void count(String what, int n) {
        if (n <= 0) return;
        String summary = null;
        synchronized (COUNTS) {
            int[] c = COUNTS.get(what);
            boolean first = c == null;
            if (first) {
                c = new int[1];
                COUNTS.put(what, c);
            }
            c[0] += n;
            long now = SystemClock.uptimeMillis();
            if (first || now - lastSummary > 10 * 60 * 1000L) {
                lastSummary = now;
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, int[]> e : COUNTS.entrySet()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(e.getKey()).append('=').append(e.getValue()[0]);
                }
                summary = sb.toString();
            }
        }
        if (summary != null && Config.verbose()) {
            XposedBridge.log(T + "filtered so far (user " + Config.userId() + "): " + summary);
        }
    }

    static void logOnce(String where, Throwable t) {
        String key = where + ':' + t.getClass().getName();
        if (!LOGGED.add(key)) return;
        StackTraceElement[] st = t.getStackTrace();
        XposedBridge.log(T + "error in " + where + ": " + t.getClass().getName()
                + (st.length > 0 ? " at " + st[0] : ""));
    }
}
