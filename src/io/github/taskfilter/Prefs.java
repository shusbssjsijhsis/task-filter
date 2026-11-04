package io.github.taskfilter;

/** Preference names shared by the settings screen and the hooks (no Xposed dependency). */
final class Prefs {
    static final String MODULE = "io.github.taskfilter";
    static final String FILE = "config";
    static final String KEY_HIDDEN = "hidden";
    static final String KEY_ENABLED = "enabled";
    /** Write the "[task-filter] ..." counters to the LSPosed log (which also reaches logcat). */
    static final String KEY_VERBOSE = "verbose";

    private Prefs() {}
}
