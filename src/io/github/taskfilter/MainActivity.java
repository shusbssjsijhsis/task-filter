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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private static final int MAX_IMPORT_BYTES = 1 << 20;

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

        CheckBox enabled = new CheckBox(this);
        enabled.setText("启用过滤");
        enabled.setChecked(optEnabled);
        enabled.setOnCheckedChangeListener((b, checked) -> {
            optEnabled = checked;
            save();
        });
        root.addView(enabled);

        final CheckBox autoUnknown = new CheckBox(this);
        CheckBox autoNew = new CheckBox(this);
        autoNew.setText("新安装的应用符合规则时立即隐藏（境外 / 代理 / Xposed / root）");
        autoNew.setChecked(optAutoNew);
        autoNew.setOnCheckedChangeListener((b, checked) -> {
            optAutoNew = checked;
            autoUnknown.setEnabled(checked);
            save();
        });
        root.addView(autoNew);

        autoUnknown.setText("也包括未识别的应用（没有国内推送，也看不出来源）");
        autoUnknown.setChecked(optAutoUnknown);
        autoUnknown.setEnabled(optAutoNew);
        autoUnknown.setPadding(dp(24), 0, 0, 0);
        autoUnknown.setOnCheckedChangeListener((b, checked) -> {
            optAutoUnknown = checked;
            save();
        });
        root.addView(autoUnknown);

        CheckBox verbose = new CheckBox(this);
        verbose.setText("记录日志（只记次数，不记应用名；确认生效后建议关闭）");
        verbose.setChecked(optVerbose);
        verbose.setOnCheckedChangeListener((b, checked) -> {
            optVerbose = checked;
            save();
        });
        root.addView(verbose);

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
        importList.setText("导入列表");
        importList.setOnClickListener(v -> showImportDialog());
        actions.addView(importList, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
        StringBuilder sb = new StringBuilder();
        if (r.picks.isEmpty()) {
            sb.append("没有发现需要新勾选的已识别应用。\n");
        } else {
            sb.append("将新勾选 ").append(r.picks.size()).append(" 个应用：\n");
            AutoSelect.Kind current = null;
            for (AutoSelect.Pick p : r.picks) {
                if (p.kind != current) {
                    current = p.kind;
                    sb.append("\n【").append(current.title).append("】\n");
                }
                describe(sb, p);
            }
        }
        if (!r.unknown.isEmpty()) {
            sb.append("\n【未识别 ").append(r.unknown.size()).append(" 个】没有国内推送 SDK，也看不出是境外应用，默认不勾选：\n");
            for (AutoSelect.Pick p : r.unknown) describe(sb, p);
        }
        sb.append("\n已跳过：系统应用 ").append(r.system).append(" 个，国内应用 ").append(r.domestic)
                .append(" 个（带小米推送等国内推送，或在内置名单里）");
        if (r.protectedApps > 0) sb.append("，桌面 / WebView 等关键组件 ").append(r.protectedApps).append(" 个");
        if (r.failed > 0) sb.append("，无法检查 ").append(r.failed).append(" 个");
        sb.append("。勾选后仍可在列表里逐个取消。");

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("自动勾选")
                .setMessage(sb)
                .setNegativeButton("取消", null);
        if (!r.picks.isEmpty()) {
            b.setPositiveButton("勾选 " + r.picks.size() + " 个", (d, w) -> applyPicks(r.picks, null));
        }
        if (!r.unknown.isEmpty()) {
            b.setNeutralButton("连同未识别共 " + (r.picks.size() + r.unknown.size()) + " 个",
                    (d, w) -> applyPicks(r.picks, r.unknown));
        }
        b.show();
    }

    private static void describe(StringBuilder sb, AutoSelect.Pick p) {
        sb.append("• ").append(p.label);
        if (!p.label.equals(p.pkg)) sb.append("（").append(p.pkg).append("）");
        if (p.cnPush) sb.append(" ⚠ 含国内推送，隐藏后它的通知可能收不到");
        sb.append('\n');
    }

    private void applyPicks(List<AutoSelect.Pick> picks, List<AutoSelect.Pick> unknown) {
        int before = hidden.size();
        for (AutoSelect.Pick p : picks) hidden.add(p.pkg);
        if (unknown != null) for (AutoSelect.Pick p : unknown) hidden.add(p.pkg);
        applyFilter();
        save();
        toast("新勾选了 " + (hidden.size() - before) + " 个应用");
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
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
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

    /** Adds every package named in the text: one per line, '#' comments, `pm list packages` output. */
    private void importText(String text) {
        if (text == null) return;
        Set<String> prot;
        try {
            prot = AutoSelect.protectedPackages(this);
        } catch (Throwable t) {
            prot = new HashSet<>(Arrays.asList(Prefs.MODULE, Prefs.TARGET));
        }
        int found = 0;
        int added = 0;
        int skipped = 0;
        for (String raw : text.split("\r\n|\r|\n")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
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
        if (found == 0) {
            toast("没有找到包名");
            return;
        }
        save();
        reload();
        toast("识别到 " + found + " 个包名，新增 " + added + " 个"
                + (skipped > 0 ? "；跳过桌面 / WebView 等关键组件 " + skipped + " 个" : ""));
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
