package io.github.taskfilter;

import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * The hidden-package list as seen from the hooked process.
 *
 * Read from {@code /data/user_de/<user>/com.xiaomi.xmsf/files/.task-filter}, which the settings
 * screen writes with root, and re-checked at most every few seconds so a change applies without
 * restarting the target. Package names are never logged.
 */
final class Config {
    private static final long CHECK_INTERVAL_MS = 3000;
    private static final int PER_USER_RANGE = 100000;
    private static final Object LOCK = new Object();

    private static volatile Set<String> hidden = Collections.emptySet();
    private static volatile boolean verbose = true;
    private static volatile long lastCheck = Long.MIN_VALUE / 2;
    private static long stamp = Long.MIN_VALUE;

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

    static File file() {
        return new File("/data/user_de/" + userId() + "/" + Prefs.TARGET + "/files/" + Prefs.CONFIG_NAME);
    }

    private static void reload() {
        try {
            File f = file();
            boolean present = f.canRead();
            long current = present ? f.lastModified() * 31 + f.length() : -1;
            if (current == stamp) return;
            stamp = current;
            Set<String> next = new HashSet<>();
            boolean enabled = true;
            boolean log = true;
            if (present) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        if (line.startsWith("enabled=")) enabled = !line.endsWith("=0");
                        else if (line.startsWith("verbose=")) log = !line.endsWith("=0");
                        else if (Prefs.isPackageName(line)) next.add(line);
                    }
                }
            }
            if (!enabled) next.clear();
            // Whenever something is hidden, the filter itself is hidden as well.
            if (!next.isEmpty()) next.add(Prefs.MODULE);
            boolean wasVerbose = verbose;
            verbose = log;
            Set<String> previous = hidden;
            hidden = Collections.unmodifiableSet(next);
            if (!previous.equals(next) || wasVerbose != log) {
                Filters.onConfigChanged();
                if (log) {
                    XposedBridge.log("[task-filter] config (user " + userId() + "): "
                            + Math.max(0, next.size() - 1) + " app(s) hidden"
                            + (present ? "" : "; no settings yet - open Task Filter (needs root) to write them"));
                }
            }
        } catch (Throwable t) {
            Filters.logOnce("config", t);
        }
    }
}
