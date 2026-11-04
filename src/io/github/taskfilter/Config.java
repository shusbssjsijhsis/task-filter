package io.github.taskfilter;

import android.os.SystemClock;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * The hidden-package list as seen from the hooked process.
 *
 * Read through LSPosed's XSharedPreferences and re-checked at most every few seconds, so a change
 * made in the settings screen applies without restarting the target. Package names are never logged.
 */
final class Config {
    private static final long CHECK_INTERVAL_MS = 3000;
    private static final Object LOCK = new Object();

    private static XSharedPreferences prefs;
    private static volatile Set<String> hidden = Collections.emptySet();
    private static volatile long lastCheck = Long.MIN_VALUE / 2;

    private Config() {}

    /** Packages to hide right now; empty when disabled or not configured. Never throws. */
    static Set<String> hidden() {
        long now = SystemClock.uptimeMillis();
        if (now - lastCheck >= CHECK_INTERVAL_MS) {
            synchronized (LOCK) {
                if (now - lastCheck >= CHECK_INTERVAL_MS) {
                    lastCheck = now;
                    reload();
                }
            }
        }
        return hidden;
    }

    private static void reload() {
        try {
            boolean fresh = false;
            if (prefs == null || !prefs.getFile().canRead()) {
                prefs = open();
                fresh = true;
            }
            if (!fresh && !prefs.hasFileChanged()) return;
            prefs.reload();
            Set<String> next = new HashSet<>();
            if (prefs.getBoolean(Prefs.KEY_ENABLED, true)) {
                Set<String> configured = prefs.getStringSet(Prefs.KEY_HIDDEN, null);
                if (configured != null) next.addAll(configured);
                // Whenever something is hidden, the filter itself is hidden as well.
                if (!next.isEmpty()) next.add(Prefs.MODULE);
            }
            Set<String> previous = hidden;
            hidden = Collections.unmodifiableSet(next);
            if (!previous.equals(next)) {
                Filters.onConfigChanged();
                XposedBridge.log("[task-filter] config: " + Math.max(0, next.size() - 1) + " app(s) hidden"
                        + (prefs.getFile().canRead() ? "" : " (settings not readable yet)"));
            }
        } catch (Throwable t) {
            Filters.logOnce("config", t);
        }
    }

    private static XSharedPreferences open() {
        XSharedPreferences p = new XSharedPreferences(Prefs.MODULE, Prefs.FILE);
        File f = p.getFile();
        if (f == null || f.canRead()) return p;
        // A secondary user (e.g. the dual-app space) has no settings of its own: use the owner's.
        String path = f.getPath();
        String owner = path.replaceFirst("/prefs\\d+/", "/prefs/");
        if (!owner.equals(path) && new File(owner).canRead()) return new XSharedPreferences(new File(owner));
        return p;
    }
}
