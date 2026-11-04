package io.github.taskfilter;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Settings screen: pick the apps to hide. Built in code so the module needs no layout resources. */
public class MainActivity extends Activity {

    static final class App {
        final String pkg;
        final String label;
        final boolean system;

        App(String pkg, String label, boolean system) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
        }

        @Override
        public String toString() {
            return label + "\n" + pkg;
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
    private TextView summary;

    @Override
    @SuppressWarnings("deprecation") // MODE_WORLD_READABLE is what LSPosed's XSharedPreferences expects
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= 29) setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        super.onCreate(savedInstanceState);
        try {
            //noinspection deprecation
            prefs = getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            prefs = null; // LSPosed has not enabled this module, so the hooks could not read anything
        }
        if (prefs != null) {
            Set<String> saved = prefs.getStringSet(Prefs.KEY_HIDDEN, null);
            if (saved != null) hidden.addAll(saved);
        }

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
        info.setText(prefs == null
                ? "⚠ 本模块尚未在 LSPosed 中启用，设置不会生效。请在 LSPosed 管理器里启用 Task Filter，"
                + "作用域勾选「小米服务框架」，然后重新打开本页面。"
                : "勾选的应用会对「小米服务框架」隐藏：最近任务、前台应用变化、运行中的进程、使用记录和已安装应用列表。"
                + "修改约 3 秒后生效。不要勾选依赖小米推送的应用（微信、QQ、钉钉、企业微信等）。");
        root.addView(info);

        enabled = new CheckBox(this);
        enabled.setText("启用过滤");
        enabled.setChecked(prefs == null || prefs.getBoolean(Prefs.KEY_ENABLED, true));
        enabled.setEnabled(prefs != null);
        enabled.setOnCheckedChangeListener((b, checked) -> save());
        root.addView(enabled);

        showSystem = new CheckBox(this);
        showSystem.setText("显示系统应用");
        showSystem.setOnCheckedChangeListener((b, checked) -> applyFilter());
        root.addView(showSystem);

        search = new EditText(this);
        search.setHint("搜索应用名或包名");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        root.addView(search);

        summary = new TextView(this);
        summary.setPadding(0, dp(4), 0, dp(4));
        root.addView(summary);

        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, shown);
        list.setAdapter(adapter);
        list.setEnabled(prefs != null);
        list.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            App app = shown.get(position);
            if (list.isItemChecked(position)) hidden.add(app.pkg); else hidden.remove(app.pkg);
            save();
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        summary.setText("正在读取应用列表…");
        new Thread(this::loadApps, "load-apps").start();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<App> apps = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (getPackageName().equals(ai.packageName)) continue;
            String label;
            try {
                label = String.valueOf(ai.loadLabel(pm));
            } catch (Throwable t) {
                label = ai.packageName;
            }
            boolean system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            apps.add(new App(ai.packageName, label, system));
        }
        final Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(apps, (x, y) -> {
            int c = collator.compare(x.label, y.label);
            return c != 0 ? c : x.pkg.compareTo(y.pkg);
        });
        runOnUiThread(() -> {
            all.clear();
            all.addAll(apps);
            applyFilter();
        });
    }

    private void applyFilter() {
        if (adapter == null) return;
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<App> checked = new ArrayList<>();
        List<App> rest = new ArrayList<>();
        for (App a : all) {
            boolean isHidden = hidden.contains(a.pkg);
            if (!isHidden && a.system && !showSystem.isChecked()) continue;
            if (!q.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(q) && !a.pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
            (isHidden ? checked : rest).add(a);
        }
        shown.clear();
        shown.addAll(checked);
        shown.addAll(rest);
        adapter.notifyDataSetChanged();
        for (int i = 0; i < shown.size(); i++) list.setItemChecked(i, hidden.contains(shown.get(i).pkg));
        updateSummary();
    }

    private void save() {
        if (prefs == null) return;
        prefs.edit()
                .putStringSet(Prefs.KEY_HIDDEN, new HashSet<>(hidden))
                .putBoolean(Prefs.KEY_ENABLED, enabled.isChecked())
                .apply();
        updateSummary();
    }

    private void updateSummary() {
        summary.setText("已选择 " + hidden.size() + " 个应用" + (enabled.isChecked() ? "" : "（过滤已关闭）"));
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
