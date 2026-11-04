package io.github.taskfilter;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Entry point: only acts inside Xiaomi Service Framework (com.xiaomi.xmsf) processes. */
public final class Hook implements IXposedHookLoadPackage {
    static final String TARGET = "com.xiaomi.xmsf";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        try {
            Filters.install(lpparam.classLoader, lpparam.processName);
        } catch (Throwable t) {
            XposedBridge.log("[task-filter] install failed: " + t.getClass().getName());
        }
    }
}
