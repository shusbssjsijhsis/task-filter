package io.github.taskfilter;

import android.os.Process;
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
    private static final int PER_USER_RANGE = 100000;
    private static final Object LOCK = new Object();

    private static XSharedPreferences prefs;
    private static String source = "none";
    private static volatile Set<String> hidden = Collections.emptySet();
    private static volatile boolean verbose = true;
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

    /** Whether the module may write its counters to the log. */
    static boolean verbose() {
        hidden();
        return verbose;
    }

    static int userId() {
        return Process.myUid() / PER_USER_RANGE;
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
            boolean wasVerbose = verbose;
            verbose = prefs.getBoolean(Prefs.KEY_VERBOSE, true);
            Set<String> previous = hidden;
            hidden = Collections.unmodifiableSet(next);
            if (!previous.equals(next) || wasVerbose != verbose) {
                Filters.onConfigChanged();
                if (verbose) {
                    XposedBridge.log("[task-filter] config (user " + userId() + "): "
                            + Math.max(0, next.size() - 1) + " app(s) hidden, settings " + source);
                }
            }
        } catch (Throwable t) {
            Filters.logOnce("config", t);
        }
    }

    /**
     * LSPosed keeps module settings per user. A secondary user such as the dual-app space (999)
     * normally has no settings of its own because the settings screen only runs for the owner, so
     * fall back to the owner's copy.
     */
    private static XSharedPreferences open() {
        XSharedPreferences p = new XSharedPreferences(Prefs.MODULE, Prefs.FILE);
        File f = p.getFile();
        if (f == null) {
            source = "unavailable";
            return p;
        }
        if (f.canRead()) {
            source = "found";
            return p;
        }
        int user = userId();
        if (user != 0) {
            String path = f.getPath();
            String[] candidates = {
                    path.replaceFirst("/prefs" + user + "/", "/prefs/"),
                    path.replaceFirst("/" + user + "/", "/0/"),
            };
            for (String candidate : candidates) {
                if (!candidate.equals(path) && new File(candidate).canRead()) {
                    source = "from user 0";
                    return new XSharedPreferences(new File(candidate));
                }
            }
        }
        // Report the layout without LSPosed's randomised base directory.
        source = "not readable (" + f.getPath().replaceFirst("^/data/misc/[^/]+/", "<lspd>/") + ")";
        return p;
    }
}
