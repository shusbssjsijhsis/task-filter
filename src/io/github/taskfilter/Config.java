package io.github.taskfilter;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * The hidden-package list as seen from the hooked process.
 *
 * Two files in the target's own device-protected data directory,
 * {@code /data/user_de/<user>/com.xiaomi.xmsf/files/}: {@code .task-filter}, written with root by the
 * settings screen (flags, then the selected packages), and {@code .task-filter-auto}, which the hook
 * appends to when it hides a newly installed app; the settings screen merges that one into the
 * selection the next time it runs. Both are re-checked at most every few seconds, so changes apply
 * without restarting the target. Package names are never logged.
 */
final class Config {
    private static final long CHECK_INTERVAL_MS = 3000;
    private static final int PER_USER_RANGE = 100000;
    private static final Object LOCK = new Object();

    private static volatile Set<String> hidden = Collections.emptySet();
    private static volatile boolean verbose = true;
    private static volatile boolean enabled = true;
    private static volatile boolean present;
    private static volatile boolean autoNew = true;
    private static volatile boolean autoUnknown;
    /** "!package" lines: apps the user turned down, never hidden automatically at install time. */
    private static volatile Set<String> never = Collections.emptySet();
    private static volatile long lastCheck = Long.MIN_VALUE / 2;

    // Guarded by LOCK.
    private static long stamp = Long.MIN_VALUE;
    private static boolean announced;
    private static Set<String> selected = Collections.emptySet();
    private static Set<String> auto = Collections.emptySet();
    /** Hidden at install time but the write to the auto file failed: kept for this process's lifetime. */
    private static final Set<String> unsaved = new HashSet<>();

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

    /** Whether newly installed apps that match the automatic rules are hidden right away. */
    static boolean autoNew() {
        hidden();
        return present && enabled && autoNew;
    }

    /** Whether that also applies to apps the rules cannot place either way. */
    static boolean autoUnknown() {
        hidden();
        return autoUnknown;
    }

    /** Apps the user turned down: never hidden automatically. */
    static Set<String> neverAuto() {
        hidden();
        return never;
    }

    static int userId() {
        return Process.myUid() / PER_USER_RANGE;
    }

    static File dir() {
        return new File("/data/user_de/" + userId() + "/" + Prefs.TARGET + "/files");
    }

    static File file() {
        return new File(dir(), Prefs.CONFIG_NAME);
    }

    static File autoFile() {
        return new File(dir(), Prefs.CONFIG_AUTO_NAME);
    }

    /** Hides a newly installed app from now on, and across restarts through the auto file. */
    static void addAuto(String pkg) {
        boolean saved = append(pkg);
        synchronized (LOCK) {
            if (!saved) unsaved.add(pkg);
            Set<String> next = new HashSet<>(auto);
            next.add(pkg);
            auto = next;
            publish(false, false);
        }
    }

    private static boolean append(String pkg) {
        try {
            if (!dir().isDirectory()) {
                // Let the framework create it with the target's own owner and SELinux label.
                Context app = Filters.application();
                if (app != null) app.createDeviceProtectedStorageContext().getFilesDir();
            }
            try (FileOutputStream out = new FileOutputStream(autoFile(), true)) {
                out.write((pkg + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Throwable t) {
            Filters.logOnce("autoFile", t);
            return false;
        }
    }

    private static void reload() {
        try {
            File f = file();
            File a = autoFile();
            boolean has = f.canRead();
            boolean hasAuto = a.canRead();
            long s = (has ? f.lastModified() * 31 + f.length() : -1) * 1000003L
                    + (hasAuto ? a.lastModified() * 31 + a.length() : -1);
            if (s == stamp) return;
            stamp = s;
            Set<String> sel = new HashSet<>();
            Set<String> nev = new HashSet<>();
            boolean en = true;
            boolean log = true;
            boolean an = true;
            boolean au = false;
            if (has) {
                try (BufferedReader r = reader(f)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        if (line.startsWith("enabled=")) en = !line.endsWith("=0");
                        else if (line.startsWith("verbose=")) log = !line.endsWith("=0");
                        else if (line.startsWith("autounknown=")) au = line.endsWith("=1");
                        else if (line.startsWith("auto=")) an = !line.endsWith("=0");
                        else if (line.startsWith("!")) {
                            if (Prefs.isPackageName(line.substring(1))) nev.add(line.substring(1));
                        } else if (Prefs.isPackageName(line)) sel.add(line);
                    }
                }
            }
            Set<String> fromAuto = new HashSet<>();
            if (hasAuto) {
                try (BufferedReader r = reader(a)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (Prefs.isPackageName(line)) fromAuto.add(line);
                    }
                }
            }
            boolean flags = verbose != log || enabled != en || present != has || autoNew != an || autoUnknown != au;
            verbose = log;
            enabled = en;
            present = has;
            autoNew = an;
            autoUnknown = au;
            never = Collections.unmodifiableSet(nev);
            selected = sel;
            auto = fromAuto;
            publish(flags, true);
        } catch (Throwable t) {
            Filters.logOnce("config", t);
        }
    }

    private static BufferedReader reader(File f) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
    }

    /** Recomputes the effective set; call with LOCK held. */
    private static void publish(boolean flagsChanged, boolean mayLog) {
        Set<String> next = new HashSet<>();
        if (enabled) {
            next.addAll(selected);
            next.addAll(auto);
            next.addAll(unsaved);
        }
        // Whenever something is hidden, the filter itself is hidden as well.
        if (!next.isEmpty()) next.add(Prefs.MODULE);
        boolean changed = !next.equals(hidden);
        if (changed) {
            hidden = Collections.unmodifiableSet(next);
            Filters.onConfigChanged();
        }
        if (mayLog && verbose && (changed || flagsChanged || !announced)) {
            announced = true;
            Set<String> extra = new HashSet<>(auto);
            extra.addAll(unsaved);
            extra.removeAll(selected);
            XposedBridge.log("[task-filter] config (user " + userId() + "): "
                    + Math.max(0, next.size() - 1) + " app(s) hidden"
                    + (enabled && !extra.isEmpty() ? " (" + extra.size() + " hidden at install time)" : "")
                    + (enabled ? "" : "; filtering is off")
                    + (present ? "" : "; no settings yet - open Task Filter (needs root) to write them"));
        }
    }
}
