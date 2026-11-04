package io.github.taskfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings screen: pick the apps to hide. Built in code so the module needs no layout resources.
 *
 * The selection is stored in this app's own private preferences and, on every change, written with
 * root into Xiaomi Service Framework's own data directory for each user
 * ({@code /data/user_de/<u>/com.xiaomi.xmsf/files/.task-filter}), where the hook reads it from inside
 * that process. The same root call collects the apps the hook hid at install time
 * ({@code .task-filter-auto}) and merges them into the selection.
 *
 * The app list is gathered from every source available, because HyperOS/MIUI only returns system
 * apps to callers that lack its "read installed apps" permission, and other hiding modules may hide
 * apps from this screen too: the package manager, launcher activities, and (when root is granted)
 * {@code pm list packages}. Packages can also be typed in, imported, or suggested by
 * {@link AutoSelect}, which looks only at this device.
 */
public class MainActivity extends Activity {

    /** HyperOS/MIUI runtime permission that gates getInstalledApplications() for third-party apps. */
    private static final String MIUI_GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS";
    private static final int REQ_APPS = 1;
    private static final int REQ_IMPORT = 2;
    private static final int REQ_EXPORT = 3;
    private static final int MAX_IMPORT_BYTES = 1 << 20;
    /** Option lines in an exported file, restored on import. */
    private static final Pattern OPTION = Pattern.compile("(enabled|verbose|auto|autounknown)=([01])");

    static final class App {
        final String pkg;
        final String label;
        final boolean system;
        final boolean addRow;

        App(String pkg, String label, boolean system, boolean addRow) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
            this.addRow = addRow;
        }

        @Override
        public String toString() {
            return addRow ? "＋ 手动添加：" + pkg : label + "\n" + pkg;
        }
    }

    private SharedPreferences prefs;
    /** Read and written on the UI thread and by the sync worker. */
    private final Set<String> hidden = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private ArrayAdapter<App> adapter;
    private ListView list;
    private EditText search;
    private CheckBox showSystem;
    private TextView summary;
    private TextView source;
    private TextView syncStatus;
    private volatile boolean loading;
    private volatile boolean classifying;
    private Boolean lastPermissionState;
    /** One root call at a time, each built from the state at the moment it starts. */
    private final ExecutorService syncWorker = Executors.newSingleThreadExecutor();

    private volatile boolean optEnabled = true;
    private volatile boolean optVerbose = true;
    private volatile boolean optAutoNew = true;
    private volatile boolean optAutoUnknown;
    private CheckBox cbEnabled;
    private CheckBox cbVerbose;
    private CheckBox cbAutoNew;
    private CheckBox cbAutoUnknown;
    /** Set while options are applied in code, so each change does not write on its own. */
    private boolean suppress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= 29) setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Prefs.LOCAL_FILE, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(Prefs.KEY_HIDDEN, null);
        if (saved != null) hidden.addAll(saved);
        optEnabled = prefs.getBoolean(Prefs.KEY_ENABLED, true);
        optVerbose = prefs.getBoolean(Prefs.KEY_VERBOSE, true);
        optAutoNew = prefs.getBoolean(Prefs.KEY_AUTO_NEW, true);
        optAutoUnknown = prefs.getBoolean(Prefs.KEY_AUTO_UNKNOWN, false);

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, 0);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("Task Filter");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        TextView info = new TextView(this);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setPadding(0, dp(6), 0, dp(6));
        info.setText("勾选的应用会对「小米服务框架」隐藏：最近任务、前台变化、进程、使用记录、应用列表和安装卸载事件。"
                + "设置用 root 写入，需在 KernelSU/Magisk 给本应用授权，并在 LSPosed 启用、作用域勾选小米服务框架"
                + "（双开空间：root 执行 pm install-existing --user 999 " + Prefs.MODULE + "，再在 999 用户页启用；只需在主空间勾选）。"
                + "不要勾选依赖小米推送的应用（微信、QQ、企业微信、钉钉等）。");
        root.addView(info);

        cbEnabled = new CheckBox(this);
        cbEnabled.setText("启用过滤");
        cbEnabled.setChecked(optEnabled);
        cbEnabled.setOnCheckedChangeListener((b, checked) -> {
            optEnabled = checked;
            if (!suppress) save();
        });
        root.addView(cbEnabled);

        cbAutoNew = new CheckBox(this);
        cbAutoUnknown = new CheckBox(this);
        cbAutoNew.setText("新安装的应用符合规则时立即隐藏（境外 / 代理 / Xposed / root，国内应用不会）");
        cbAutoNew.setChecked(optAutoNew);
        cbAutoNew.setOnCheckedChangeListener((b, checked) -> {
            optAutoNew = checked;
            cbAutoUnknown.setEnabled(checked);
            if (!suppress) save();
        });
        root.addView(cbAutoNew);

        cbAutoUnknown.setText("也包括未识别的应用（看不出是国内还是境外）");
        cbAutoUnknown.setChecked(optAutoUnknown);
        cbAutoUnknown.setEnabled(optAutoNew);
        cbAutoUnknown.setPadding(dp(24), 0, 0, 0);
        cbAutoUnknown.setOnCheckedChangeListener((b, checked) -> {
            optAutoUnknown = checked;
            if (!suppress) save();
        });
        root.addView(cbAutoUnknown);

        cbVerbose = new CheckBox(this);
        cbVerbose.setText("记录日志（只记次数，不记应用名；确认生效后建议关闭）");
        cbVerbose.setChecked(optVerbose);
        cbVerbose.setOnCheckedChangeListener((b, checked) -> {
            optVerbose = checked;
            if (!suppress) save();
        });
        root.addView(cbVerbose);

        showSystem = new CheckBox(this);
        showSystem.setText("显示系统应用");
        showSystem.setOnCheckedChangeListener((b, checked) -> applyFilter());
        root.addView(showSystem);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        search = new EditText(this);
        search.setHint("搜索应用名，或输入包名手动添加");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        privateInput(search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        row.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(v -> reload());
        row.addView(refresh);
        root.addView(row);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button auto = new Button(this);
        auto.setText("自动勾选");
        auto.setOnClickListener(v -> runAutoSelect());
        actions.addView(auto, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button importList = new Button(this);
        importList.setText("导入");
        importList.setOnClickListener(v -> showImportDialog());
        actions.addView(importList, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button export = new Button(this);
        export.setText("导出");
        export.setOnClickListener(v -> startExport());
        actions.addView(export, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(actions);

        source = new TextView(this);
        source.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(source);

        summary = new TextView(this);
        summary.setPadding(0, dp(4), 0, dp(2));
        root.addView(summary);

        syncStatus = new TextView(this);
        syncStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        syncStatus.setPadding(0, 0, 0, dp(4));
        root.addView(syncStatus);

        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, shown);
        list.setAdapter(adapter);
        list.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> onItemClick(position));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        requestAppListPermission();
        reload();
        syncToTarget(); // bring the target up to date and collect what it hid at install time
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The permission may have been granted in system settings meanwhile.
        Boolean state = miuiPermissionState();
        if (lastPermissionState != null && state != null && !state.equals(lastPermissionState)) reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        syncWorker.shutdown(); // a write in progress still finishes
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_APPS) reload();
    }

    /** Keeps what is typed here out of the keyboard's learning, suggestions and autofill. */
    private static void privateInput(EditText e) {
        if (Build.VERSION.SDK_INT >= 26) {
            e.setImeOptions(e.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
    }

    /** null when this ROM has no such permission, otherwise whether it is granted. */
    private Boolean miuiPermissionState() {
        try {
            getPackageManager().getPermissionInfo(MIUI_GET_INSTALLED_APPS, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        return checkSelfPermission(MIUI_GET_INSTALLED_APPS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAppListPermission() {
        Boolean state = miuiPermissionState();
        if (Boolean.FALSE.equals(state)) requestPermissions(new String[]{MIUI_GET_INSTALLED_APPS}, REQ_APPS);
    }

    // ------------------------------------------------------------------------------------------
    // App list

    private void reload() {
        if (loading) return;
        loading = true;
        summary.setText("正在读取应用列表…");
        final Set<String> selected = new HashSet<>(hidden);
        new Thread(() -> loadApps(selected), "load-apps").start();
    }

    private void loadApps(Set<String> selected) {
        final Boolean permission = miuiPermissionState();
        PackageManager pm = getPackageManager();
        Map<String, App> found = new HashMap<>();
        int fromPm = 0;
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (put(found, pm, ai)) fromPm++;
            }
        } catch (Throwable ignored) {
        }
        int fromLauncher = 0;
        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)) {
                if (ri.activityInfo != null && put(found, pm, ri.activityInfo.applicationInfo)) fromLauncher++;
            }
        } catch (Throwable ignored) {
        }
        RootList rootList = rootPackages();
        int fromRoot = 0;
        if (rootList != null) {
            for (String pkg : rootList.packages) {
                if (found.containsKey(pkg) || getPackageName().equals(pkg)) continue;
                found.put(pkg, new App(pkg, labelOf(pm, pkg), rootList.system.contains(pkg), false));
                fromRoot++;
            }
        }
        for (String pkg : selected) {
            if (!found.containsKey(pkg)) found.put(pkg, new App(pkg, pkg, false, false));
        }
        List<App> apps = new ArrayList<>(found.values());
        final Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(apps, (x, y) -> {
            int c = collator.compare(x.label, y.label);
            return c != 0 ? c : x.pkg.compareTo(y.pkg);
        });
        final String sourceText = "共 " + apps.size() + " 个应用（包管理器 " + fromPm + "，启动器补充 " + fromLauncher
                + "，root 补充 " + (rootList == null ? "未授权" : String.valueOf(fromRoot)) + "）｜读取应用列表权限："
                + (permission == null ? "本机无此权限" : permission ? "已允许" : "未允许");
        runOnUiThread(() -> {
            loading = false;
            lastPermissionState = permission;
            all.clear();
            all.addAll(apps);
            source.setText(sourceText);
            applyFilter();
        });
    }

    private boolean put(Map<String, App> found, PackageManager pm, ApplicationInfo ai) {
        if (ai == null || ai.packageName == null || found.containsKey(ai.packageName)) return false;
        if (getPackageName().equals(ai.packageName)) return false;
        String label;
        try {
            label = String.valueOf(ai.loadLabel(pm));
        } catch (Throwable t) {
            label = ai.packageName;
        }
        boolean system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        found.put(ai.packageName, new App(ai.packageName, label, system, false));
        return true;
    }

    private String labelOf(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm));
        } catch (Throwable t) {
            return pkg; // invisible to this app (filtered by the ROM or another module): show the package
        }
    }

    static final class RootList {
        final List<String> packages = new ArrayList<>();
        final Set<String> system = new HashSet<>();
    }

    /** `pm list packages` through su; null when root is unavailable or not granted to this app. */
    private static RootList rootPackages() {
        Process p = null;
        Thread watchdog = null;
        try {
            p = new ProcessBuilder("su", "-c", "pm list packages -3; echo __SYSTEM__; pm list packages -s")
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            // A root manager that shows a prompt must not leave this screen loading forever.
            watchdog = watchdog(p);
            RootList out = new RootList();
            boolean system = false;
            boolean sawMarker = false;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.equals("__SYSTEM__")) {
                        system = true;
                        sawMarker = true;
                    } else if (line.startsWith("package:")) {
                        String pkg = line.substring("package:".length()).trim();
                        if (!Prefs.isPackageName(pkg)) continue;
                        out.packages.add(pkg);
                        if (system) out.system.add(pkg);
                    }
                }
            }
            if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0 || !sawMarker) return null;
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (watchdog != null) watchdog.interrupt();
            if (p != null) p.destroy();
        }
    }

    private static Thread watchdog(final Process proc) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(15000);
                proc.destroy();
            } catch (InterruptedException ignored) {
            }
        }, "su-watchdog");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void onItemClick(int position) {
        App app = shown.get(position);
        if (app.addRow) {
            hidden.add(app.pkg);
            all.add(new App(app.pkg, app.pkg, false, false));
            save();
            search.setText("");
            return;
        }
        if (list.isItemChecked(position)) hidden.add(app.pkg); else hidden.remove(app.pkg);
        save();
    }

    private void applyFilter() {
        if (adapter == null) return;
        String raw = search.getText().toString().trim();
        String q = raw.toLowerCase(Locale.ROOT);
        List<App> checked = new ArrayList<>();
        List<App> rest = new ArrayList<>();
        boolean exact = false;
        for (App a : all) {
            if (a.pkg.equalsIgnoreCase(raw)) exact = true;
            boolean isHidden = hidden.contains(a.pkg);
            if (!isHidden && a.system && !showSystem.isChecked() && q.isEmpty()) continue;
            if (!q.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(q) && !a.pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
            (isHidden ? checked : rest).add(a);
        }
        shown.clear();
        if (!exact && Prefs.isPackageName(raw)) shown.add(new App(raw, raw, false, true));
        shown.addAll(checked);
        shown.addAll(rest);
        adapter.notifyDataSetChanged();
        for (int i = 0; i < shown.size(); i++) {
            App a = shown.get(i);
            list.setItemChecked(i, !a.addRow && hidden.contains(a.pkg));
        }
        updateSummary();
    }

    private void updateSummary() {
        summary.setText(loading ? "正在读取应用列表…"
                : classifying ? "正在分析应用…"
                : "已选择 " + hidden.size() + " 个应用" + (optEnabled ? "" : "（过滤已关闭）"));
    }

    private void toast(String text) {
        if (!isFinishing() && !isDestroyed()) Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------------------------------
    // Auto-select

    private void runAutoSelect() {
        if (loading) {
            toast("应用列表还在读取，请稍候");
            return;
        }
        if (classifying) return;
        classifying = true;
        updateSummary();
        final List<String> pkgs = new ArrayList<>();
        for (App a : all) pkgs.add(a.pkg);
        final Set<String> already = new HashSet<>(hidden);
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            AutoSelect.Result r;
            try {
                r = AutoSelect.classify(ctx, pkgs, already);
            } catch (Throwable t) {
                r = null;
            }
            final AutoSelect.Result result = r;
            runOnUiThread(() -> {
                classifying = false;
                updateSummary();
                if (!isFinishing() && !isDestroyed()) showAutoResult(result);
            });
        }, "auto-select").start();
    }

    private void showAutoResult(final AutoSelect.Result r) {
        if (r == null) {
            toast("分析失败");
            return;
        }
        if (r.untick.isEmpty()) showSuggestions(r);
        else showUntick(r, () -> showSuggestions(r));
    }

    /** Step 1: apps that are ticked but judged domestic (or essential), offered for unticking. */
    private void showUntick(final AutoSelect.Result r, final Runnable next) {
        final List<AutoSelect.Pick> items = r.untick;
        final boolean[] checked = new boolean[items.size()];
        CharSequence[] labels = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); i++) {
            checked[i] = items.get(i).preChecked;
            labels[i] = itemText(items.get(i), items.get(i).reason);
        }
        new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitle("先检查已勾选的应用（" + items.size() + " 个）",
                        "这些已经勾选，但判断为国内应用或关键组件。勾上的会被取消勾选，恢复对小米服务框架可见；"
                                + "没勾上的只是用了国内 SDK，可能是国内开发者做的 root / 代理工具，请自己判断。"))
                .setMultiChoiceItems(labels, checked, (dlg, which, isChecked) -> { })
                .setPositiveButton("取消勾选选中的", (dlg, w) -> {
                    int n = 0;
                    for (int i = 0; i < checked.length; i++) if (checked[i] && hidden.remove(items.get(i).pkg)) n++;
                    if (n > 0) {
                        applyFilter();
                        save();
                        toast("已取消勾选 " + n + " 个应用");
                    }
                    next.run();
                })
                .setNegativeButton("跳过", (dlg, w) -> next.run())
                .show();
    }

    /** Step 2: suggestions for apps not ticked yet; recognised ones pre-selected, unknown ones not. */
    private void showSuggestions(final AutoSelect.Result r) {
        final List<AutoSelect.Pick> items = new ArrayList<>(r.picks);
        items.addAll(r.unknown);
        StringBuilder skipped = new StringBuilder("已跳过：系统应用 ").append(r.system).append(" 个，国内应用 ")
                .append(r.domestic).append(" 个");
        if (r.protectedApps > 0) skipped.append("，关键组件 ").append(r.protectedApps).append(" 个");
        if (r.failed > 0) skipped.append("，无法检查 ").append(r.failed).append(" 个");
        skipped.append("。");
        if (items.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("自动勾选")
                    .setMessage("没有发现需要新勾选的应用。\n" + skipped)
                    .setPositiveButton("好", null)
                    .show();
            return;
        }
        final boolean[] checked = new boolean[items.size()];
        CharSequence[] labels = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); i++) {
            AutoSelect.Pick p = items.get(i);
            checked[i] = p.preChecked;
            labels[i] = itemText(p, p.kind.title + " · " + p.reason);
        }
        final Button[] toggle = new Button[1];
        String title = "自动勾选：建议 " + r.picks.size() + " 个"
                + (r.unknown.isEmpty() ? "" : "，未识别 " + r.unknown.size() + " 个");
        final AlertDialog d = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitle(title, skipped + "建议项已预先勾上，未识别的没有；确认前可以逐个调整。"))
                .setMultiChoiceItems(labels, checked, (dlg, which, isChecked) -> {
                    if (toggle[0] != null) toggle[0].setText(allTrue(checked) ? "全不选" : "全选");
                })
                .setPositiveButton("勾选选中的", (dlg, w) -> {
                    int n = 0;
                    for (int i = 0; i < checked.length; i++) if (checked[i] && hidden.add(items.get(i).pkg)) n++;
                    applyFilter();
                    save();
                    toast("新勾选了 " + n + " 个应用");
                })
                .setNeutralButton("全选", null)
                .setNegativeButton("取消", null)
                .create();
        d.show();
        toggle[0] = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        toggle[0].setText(allTrue(checked) ? "全不选" : "全选");
        toggle[0].setOnClickListener(v -> { // keeps the dialog open
            boolean value = !allTrue(checked);
            for (int i = 0; i < checked.length; i++) {
                checked[i] = value;
                d.getListView().setItemChecked(i, value);
            }
            toggle[0].setText(value ? "全不选" : "全选");
        });
    }

    private static boolean allTrue(boolean[] a) {
        for (boolean b : a) if (!b) return false;
        return true;
    }

    private static String itemText(AutoSelect.Pick p, String tag) {
        return p.label + (p.label.equals(p.pkg) ? "" : "（" + p.pkg + "）") + "\n" + tag;
    }

    private View dialogTitle(String title, String detail) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(8));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        box.addView(t);
        TextView d = new TextView(this);
        d.setText(detail);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        d.setPadding(0, dp(6), 0, 0);
        box.addView(d);
        return box;
    }

    // ------------------------------------------------------------------------------------------
    // Import

    private void showImportDialog() {
        final EditText input = new EditText(this);
        input.setHint("粘贴包名，每行一个；以 # 开头的行会被忽略");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(5);
        input.setMaxLines(10);
        input.setGravity(Gravity.TOP | Gravity.START);
        privateInput(input);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("导入要隐藏的应用")
                .setView(box)
                .setPositiveButton("导入", (d, w) -> importText(input.getText().toString()))
                .setNeutralButton("从文件选择…", (d, w) -> pickImportFile())
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickImportFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            toast("没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_EXPORT) {
            writeExport(data.getData());
            return;
        }
        if (requestCode != REQ_IMPORT) return;
        final Uri uri = data.getData();
        new Thread(() -> {
            String text = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < MAX_IMPORT_BYTES) out.write(buf, 0, n);
                    text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {
            }
            final String result = text;
            runOnUiThread(() -> {
                if (result == null) toast("读取文件失败");
                else importText(result);
            });
        }, "import").start();
    }

    /**
     * Adds every package named in the text (one per line, '#' comments, `pm list packages` output) and
     * restores the option lines of an exported file.
     */
    private void importText(String text) {
        if (text == null) return;
        Set<String> prot;
        try {
            prot = AutoSelect.protectedPackages(this);
        } catch (Throwable t) {
            prot = new HashSet<>(Arrays.asList(Prefs.MODULE, Prefs.TARGET));
        }
        Map<String, Boolean> options = new HashMap<>();
        int found = 0;
        int added = 0;
        int skipped = 0;
        for (String raw : text.split("\r\n|\r|\n")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher option = OPTION.matcher(line);
            if (option.matches()) {
                options.put(option.group(1), "1".equals(option.group(2)));
                continue;
            }
            if (line.startsWith("package:")) line = line.substring("package:".length()).trim();
            String pkg = line.split("[\\s,;，；]+", 2)[0];
            if (!Prefs.isPackageName(pkg)) continue;
            found++;
            if (prot.contains(pkg)) {
                skipped++;
                continue;
            }
            if (hidden.add(pkg)) added++;
        }
        if (found == 0 && options.isEmpty()) {
            toast("没有找到包名");
            return;
        }
        if (!options.isEmpty()) applyOptions(options);
        save();
        reload();
        toast("识别到 " + found + " 个包名，新增 " + added + " 个"
                + (options.isEmpty() ? "" : "，已恢复选项")
                + (skipped > 0 ? "；跳过桌面 / WebView 等关键组件 " + skipped + " 个" : ""));
    }

    private void applyOptions(Map<String, Boolean> o) {
        suppress = true;
        try {
            if (o.containsKey("enabled")) cbEnabled.setChecked(o.get("enabled"));
            if (o.containsKey("verbose")) cbVerbose.setChecked(o.get("verbose"));
            if (o.containsKey("auto")) cbAutoNew.setChecked(o.get("auto"));
            if (o.containsKey("autounknown")) cbAutoUnknown.setChecked(o.get("autounknown"));
        } finally {
            suppress = false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Export

    private void startExport() {
        if (hidden.isEmpty()) {
            toast("还没有勾选任何应用");
            return;
        }
        String name = "task-filter-" + new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date()) + ".txt";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Throwable t) {
            toast("没有可用的文件选择器");
        }
    }

    /** The same format the import reads: option lines, then one package per line with its name as a comment. */
    private String exportText() {
        Map<String, String> labels = new HashMap<>();
        for (App a : all) labels.put(a.pkg, a.label);
        List<String> pkgs = new ArrayList<>(hidden);
        Collections.sort(pkgs);
        StringBuilder sb = new StringBuilder();
        sb.append("# Task Filter 配置，导出于 ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())).append('\n');
        sb.append("# 共 ").append(pkgs.size()).append(" 个应用。Task Filter →「导入」→「从文件选择…」可以恢复，包括下面的选项。\n");
        sb.append("# 这份文件包含你的应用名单，请妥善保管，不要公开。\n");
        sb.append("enabled=").append(optEnabled ? 1 : 0).append('\n');
        sb.append("verbose=").append(optVerbose ? 1 : 0).append('\n');
        sb.append("auto=").append(optAutoNew ? 1 : 0).append('\n');
        sb.append("autounknown=").append(optAutoUnknown ? 1 : 0).append('\n');
        sb.append('\n');
        for (String pkg : pkgs) {
            sb.append(pkg);
            String label = labels.get(pkg);
            if (label != null && !label.equals(pkg)) {
                sb.append("    # ").append(label.replaceAll("[\\r\\n]+", " ").trim());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void writeExport(final Uri uri) {
        final String content = exportText();
        final int count = hidden.size();
        new Thread(() -> {
            boolean ok = false;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out != null) {
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                    ok = true;
                }
            } catch (Throwable ignored) {
            }
            final boolean done = ok;
            runOnUiThread(() -> toast(done
                    ? "已导出 " + count + " 个应用。文件里是你的应用名单，请妥善保管"
                    : "导出失败"));
        }, "export").start();
    }

    // ------------------------------------------------------------------------------------------
    // Persisting and writing to the target

    private void persist() {
        prefs.edit()
                .putStringSet(Prefs.KEY_HIDDEN, new HashSet<>(hidden))
                .putBoolean(Prefs.KEY_ENABLED, optEnabled)
                .putBoolean(Prefs.KEY_VERBOSE, optVerbose)
                .putBoolean(Prefs.KEY_AUTO_NEW, optAutoNew)
                .putBoolean(Prefs.KEY_AUTO_UNKNOWN, optAutoUnknown)
                .apply();
    }

    private void save() {
        persist();
        updateSummary();
        syncToTarget();
    }

    private void syncToTarget() {
        syncStatus.setText("正在写入小米服务框架…");
        try {
            syncWorker.execute(this::syncNow);
        } catch (Throwable ignored) {
            // shutting down
        }
    }

    /** On the sync worker: one root call writes every user's config and collects install-time hides. */
    private void syncNow() {
        SyncResult res = writeConfigAsRoot(buildConfig());
        int merged = 0;
        for (String pkg : res.auto) {
            if (Prefs.isPackageName(pkg) && !Prefs.MODULE.equals(pkg) && hidden.add(pkg)) merged++;
        }
        if (merged > 0) persist(); // the file just written already contains them
        final String msg = res.message(merged);
        final boolean refresh = merged > 0;
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            syncStatus.setText(msg);
            if (refresh) reload();
        });
    }

    /** The plain-text config the hook reads: flags, then one package name per line. */
    private String buildConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(optEnabled ? 1 : 0).append('\n');
        sb.append("verbose=").append(optVerbose ? 1 : 0).append('\n');
        sb.append("auto=").append(optAutoNew ? 1 : 0).append('\n');
        sb.append("autounknown=").append(optAutoUnknown ? 1 : 0).append('\n');
        List<String> pkgs = new ArrayList<>(hidden);
        Collections.sort(pkgs);
        for (String pkg : pkgs) {
            if (Prefs.isPackageName(pkg)) sb.append(pkg).append('\n');
        }
        return sb.toString();
    }

    static final class SyncResult {
        int users = -1;
        String error;
        final List<String> auto = new ArrayList<>();

        String message(int merged) {
            if (error != null) return error;
            if (users <= 0) return "未找到小米服务框架的数据目录，未写入。";
            return "已写入 " + users + " 个用户空间，约 3 秒后生效。"
                    + (merged > 0 ? "合并了 " + merged + " 个安装时自动隐藏的应用。" : "");
        }
    }

    /**
     * Writes the config into {@code /data/user_de/<u>/com.xiaomi.xmsf/files/.task-filter} for every
     * user, owned and labelled like the target's own data, after merging in what the hook recorded in
     * {@code .task-filter-auto} (which is then emptied).
     */
    private SyncResult writeConfigAsRoot(String content) {
        SyncResult res = new SyncResult();
        Process p = null;
        Thread watchdog = null;
        try {
            p = Runtime.getRuntime().exec("su");
            watchdog = watchdog(p);
            OutputStream os = p.getOutputStream();
            os.write(buildScript(content).getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("TF_AUTO ")) {
                        res.auto.add(line.substring("TF_AUTO ".length()).trim());
                    } else if (line.startsWith("TF_DONE ")) {
                        try {
                            res.users = Integer.parseInt(line.substring("TF_DONE ".length()).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok || res.users < 0) {
                res.error = "同步失败：root 命令未正常结束，请确认已在 KernelSU/Magisk 里给 Task Filter 授予 root。";
                res.auto.clear();
            }
        } catch (Throwable t) {
            res.error = "同步失败：无法获取 root（" + t.getClass().getSimpleName() + "），请在 KernelSU/Magisk 里给 Task Filter 授权。";
            res.auto.clear();
        } finally {
            if (watchdog != null) watchdog.interrupt();
            if (p != null) p.destroy();
        }
        return res;
    }

    /**
     * The root script, with the config embedded in a quoted here-doc (no shell expansion). Owner and
     * SELinux label come from the target's own data directory, so its files/ directory is created (or
     * repaired, if an earlier version created it as root) exactly as the framework would.
     */
    private static String buildScript(String content) {
        String cfg = Prefs.CONFIG_NAME;
        String auto = Prefs.CONFIG_AUTO_NAME;
        String base = "/data/user_de/*/" + Prefs.TARGET;
        return "cfg=$(cat <<'__TF_EOF__'\n"
                + content
                + "__TF_EOF__\n)\n"
                + "auto=$(cat " + base + "/files/" + auto + " 2>/dev/null"
                + " | grep -E '^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$' | sort -u)\n"
                + "[ -n \"$auto\" ] && printf '%s\\n' \"$auto\" | sed 's/^/TF_AUTO /'\n"
                + "n=0\n"
                + "for d in " + base + "; do\n"
                + "  [ -d \"$d\" ] || continue\n"
                + "  owner=$(stat -c %u:%g \"$d\" 2>/dev/null)\n"
                + "  ctx=$(ls -Zd \"$d\" 2>/dev/null | awk '{print $1}')\n"
                + "  case \"$ctx\" in u:object_r:*) ;; *) ctx= ;; esac\n"
                + "  f=\"$d/files\"\n"
                + "  if [ ! -d \"$f\" ] || [ \"$(stat -c %u \"$f\" 2>/dev/null)\" = 0 ]; then\n"
                + "    mkdir -p \"$f\" 2>/dev/null || continue\n"
                + "    [ -n \"$owner\" ] && chown \"$owner\" \"$f\" 2>/dev/null\n"
                + "    chmod 771 \"$f\" 2>/dev/null\n"
                + "    [ -n \"$ctx\" ] && chcon \"$ctx\" \"$f\" 2>/dev/null\n"
                + "  fi\n"
                + "  { printf '%s\\n' \"$cfg\"; if [ -n \"$auto\" ]; then printf '%s\\n' \"$auto\"; fi; } > \"$f/" + cfg + "\" 2>/dev/null || continue\n"
                + "  [ -n \"$owner\" ] && chown \"$owner\" \"$f/" + cfg + "\" 2>/dev/null\n"
                + "  [ -n \"$ctx\" ] && chcon \"$ctx\" \"$f/" + cfg + "\" 2>/dev/null\n"
                + "  chmod 600 \"$f/" + cfg + "\" 2>/dev/null\n"
                + "  [ -f \"$f/" + auto + "\" ] && : > \"$f/" + auto + "\"\n"
                + "  n=$((n+1))\n"
                + "done\n"
                + "echo TF_DONE $n\n"
                + "exit 0\n";
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
