package io.github.taskfilter;

import java.util.regex.Pattern;

/** Names shared by the settings screen and the hooks (no Xposed dependency). */
final class Prefs {
    static final String MODULE = "io.github.taskfilter";
    static final String TARGET = "com.xiaomi.xmsf";

    /** The settings screen's own (private) preferences. */
    static final String LOCAL_FILE = "settings";
    static final String KEY_HIDDEN = "hidden";
    static final String KEY_ENABLED = "enabled";
    /** Write the "[task-filter] ..." counters to the LSPosed log (which also reaches logcat). */
    static final String KEY_VERBOSE = "verbose";

    /**
     * The list handed to the hooks. The settings screen writes it with root into the target's own
     * device-protected data directory of every user, so each Xiaomi Service Framework instance (owner
     * and dual-app space) reads it from its own sandbox: nothing else can read it, and it does not
     * depend on LSPosed's deprecated XSharedPreferences.
     */
    static final String CONFIG_NAME = ".task-filter";

    static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    static boolean isPackageName(String s) {
        return s != null && PACKAGE.matcher(s).matches();
    }

    private Prefs() {}
}
