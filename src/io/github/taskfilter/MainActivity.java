package io.github.taskfilter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Settings screen: pick the apps to hide. Built in code so the module needs no layout resources.
 *
 * The selection is stored in this app's own private preferences and, on every change, written with
 * root into Xiaomi Service Framework's own data directory for each user
 * ({@code /data/user_de/<u>/com.xiaomi.xmsf/files/.task-filter}), where the hook reads it from inside
 * that process. This replaces LSPosed's deprecated {@code XSharedPreferences}: nothing is
 * world-readable and the module declares no {@code xposedsharedprefs}.
 *
 * The app list is gathered from every source available, because HyperOS/MIUI only returns system
 * apps to callers that lack its "read installed apps" permission, and other hiding modules may hide
 * apps from this screen too: the package manager, launcher activities, and (when root is granted)
 * {@code pm list packages}. A package can also be typed in by hand.
 */
public class MainActivity extends Activity {

    /** HyperOS/MIUI runtime permission that gates getInstalledApplications() for third-party apps. */
    private static final String MIUI_GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS";
    private static final int REQ_APPS = 1;

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
    private final Set<String> hidden = new HashSet<>();
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private ArrayAdapter<App> adapter;
    private ListView list;
    private EditText search;
    private CheckBox showSystem;
    private CheckBox enabled;
    private CheckBox verbose;
    private TextView summary;
    private TextView source;
    private TextView syncStatus;
    private volatile boolean loading;
    private Boolean lastPermissionState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= 29) setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Prefs.LOCAL_FILE, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(Prefs.KEY_HIDDEN, null);
        if (saved != null) hidden.addAll(saved);

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
        info.setPadding(0, dp(8), 0, dp(8));
        info.setText("勾选的应用会对「小米服务框架」隐藏：最近任务、前台应用变化、运行中的进程、使用记录和已安装应用列表。\n"
                + "设置通过 root 写入小米服务框架的数据目录，请先在 KernelSU/Magisk 里给 Task Filter 授予 root；修改约 3 秒后生效。\n"
                + "还需在 LSPosed 管理器里启用 Task Filter，作用域勾选「小米服务框架」。不要勾选依赖小米推送的应用（微信、QQ、钉钉、企业微信等）。\n"
                + "用了应用双开时，在 999 用户页也要启用（root 执行 pm install-existing --user 999 " + Prefs.MODULE
                + "）；但只需在主空间勾选一次，双开空间会自动写入。");
        root.addView(info);

        enabled = new CheckBox(this);
        enabled.setText("启用过滤");
        enabled.setChecked(prefs.getBoolean(Prefs.KEY_ENABLED, true));
        enabled.setOnCheckedChangeListener((b, checked) -> save());
        root.addView(enabled);

        verbose = new CheckBox(this);
        verbose.setText("记录日志（只记过滤次数，不记应用名；确认生效后建议关闭，减少痕迹）");
        verbose.setChecked(prefs.getBoolean(Prefs.KEY_VERBOSE, true));
        verbose.setOnCheckedChangeListener((b, checked) -> save());
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
        syncToTarget(); // ensure the target has the current selection (e.g. after reinstall or data clear)
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The permission may have been granted in system settings meanwhile.
        Boolean state = miuiPermissionState();
        if (lastPermissionState != null && state != null && !state.equals(lastPermissionState)) reload();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_APPS) reload();
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
            final Process proc = p;
            watchdog = new Thread(() -> {
                try {
                    Thread.sleep(15000);
                    proc.destroy();
                } catch (InterruptedException ignored) {
                }
            }, "su-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
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

    private void save() {
        prefs.edit()
                .putStringSet(Prefs.KEY_HIDDEN, new HashSet<>(hidden))
                .putBoolean(Prefs.KEY_ENABLED, enabled.isChecked())
                .putBoolean(Prefs.KEY_VERBOSE, verbose.isChecked())
                .apply();
        updateSummary();
        syncToTarget();
    }

    private void updateSummary() {
        summary.setText(loading ? "正在读取应用列表…"
                : "已选择 " + hidden.size() + " 个应用" + (enabled.isChecked() ? "" : "（过滤已关闭）"));
    }

    /** The plain-text config the hook reads: enabled/verbose flags then one package name per line. */
    private String buildConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(enabled.isChecked() ? "1" : "0").append('\n');
        sb.append("verbose=").append(verbose.isChecked() ? "1" : "0").append('\n');
        List<String> pkgs = new ArrayList<>(hidden);
        Collections.sort(pkgs);
        for (String pkg : pkgs) {
            if (Prefs.isPackageName(pkg)) sb.append(pkg).append('\n');
        }
        return sb.toString();
    }

    private void syncToTarget() {
        final String content = buildConfig();
        syncStatus.setText("正在写入小米服务框架…");
        new Thread(() -> {
            final String result = writeConfigAsRoot(content);
            runOnUiThread(() -> syncStatus.setText(result));
        }, "tf-sync").start();
    }

    /**
     * Writes the config into {@code /data/user_de/<u>/com.xiaomi.xmsf/files/.task-filter} for every
     * user, matching the target's own owner and SELinux context so each Xiaomi Service Framework
     * process can read it from its sandbox. Returns a short status for the screen.
     */
    private String writeConfigAsRoot(String content) {
        Process p = null;
        Thread watchdog = null;
        try {
            p = Runtime.getRuntime().exec("su");
            final Process proc = p;
            watchdog = new Thread(() -> {
                try {
                    Thread.sleep(15000);
                    proc.destroy();
                } catch (InterruptedException ignored) {
                }
            }, "tf-sync-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            OutputStream os = p.getOutputStream();
            os.write(buildScript(content).getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int users = -1;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("TF_DONE")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            try {
                                users = Integer.parseInt(parts[1]);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) return "同步失败：root 命令未正常结束，请确认已给 Task Filter 授予 root。";
            if (users < 0) return "同步失败：未能写入，请确认已授予 root 且小米服务框架已安装。";
            if (users == 0) return "未找到小米服务框架的数据目录，未写入。";
            return "已写入 " + users + " 个用户空间，约 3 秒后生效。确认生效后可关闭上面的「记录日志」。";
        } catch (Throwable t) {
            return "同步失败：无法获取 root（" + t.getClass().getSimpleName() + "），请在 KernelSU/Magisk 里给 Task Filter 授权。";
        } finally {
            if (watchdog != null) watchdog.interrupt();
            if (p != null) p.destroy();
        }
    }

    /** The root script, with the config embedded in a quoted here-doc (no shell expansion). */
    private static String buildScript(String content) {
        String cfg = Prefs.CONFIG_NAME;
        return "cfg=$(cat <<'__TF_EOF__'\n"
                + content
                + "__TF_EOF__\n)\n"
                + "n=0\n"
                + "for d in /data/user_de/*/" + Prefs.TARGET + "; do\n"
                + "  [ -d \"$d\" ] || continue\n"
                + "  f=\"$d/files\"\n"
                + "  mkdir -p \"$f\" 2>/dev/null || continue\n"
                + "  printf '%s\\n' \"$cfg\" > \"$f/" + cfg + "\" 2>/dev/null || continue\n"
                + "  owner=$(stat -c %u:%g \"$f\" 2>/dev/null)\n"
                + "  [ -n \"$owner\" ] && chown \"$owner\" \"$f/" + cfg + "\" 2>/dev/null\n"
                + "  ctx=$(ls -Zd \"$f\" 2>/dev/null | awk '{print $1}')\n"
                + "  case \"$ctx\" in u:object_r:*) chcon \"$ctx\" \"$f/" + cfg + "\" 2>/dev/null;; esac\n"
                + "  chmod 600 \"$f/" + cfg + "\" 2>/dev/null\n"
                + "  n=$((n+1))\n"
                + "done\n"
                + "echo TF_DONE $n\n"
                + "exit\n";
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
