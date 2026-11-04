# Task Filter

一个 LSPosed 模块：让「小米服务框架」（`com.xiaomi.xmsf`）看不到你指定的应用。

[English](#english)

## 为什么需要它

小米服务框架承载小米推送，同时带有事件上报代码。对 7.5.79-C 版本做静态分析可以看到它会：

- 用 `ActivityManager.getRunningTasks` / `getRecentTasks` 收集任务列表，交给 `eventtracking` 模块（日志 `CollectRunningTasks ... report tasks is:`，内容包括每个任务的包名、界面名和最后活动时间）；
- 通过 `miui.process.IForegroundInfoListener` 和 `IProcessObserver` 监听前台应用切换。`ActivationManager` 把记录写进本地数据库 `activation.db`，代码里有 `uploadEventToServer` 和 `onSyncToOneTrackDone` 等上报路径；
- 通过自己解析的 `ITaskStackListener` 事件跟踪任务切换；
- 查询运行中的进程、使用记录（`UsageStatsManager.queryEvents`）和已安装应用列表；
- `eventtracking.PackageMonitor` 接收安装、卸载广播，从中取出包名，并查询该应用的安装来源（`getInstallerPackageName`）。

它持有 `REAL_GET_TASKS`、`PACKAGE_USAGE_STATS`、`INTERACT_ACROSS_USERS_FULL` 等系统权限，普通的「隐藏应用列表」模块只拦截包管理器查询，挡不住这些。

## 做了什么

模块只在小米服务框架自己的进程里生效，把你勾选的应用从它拿到的数据里去掉：

| 数据来源 | 处理 |
|---|---|
| `getRunningTasks`、`getRecentTasks`、`ActivityTaskManager.getTasks` | 从列表中移除 |
| `ITaskStackListener` 事件（任务移到前台、创建、描述变化、重启尝试） | 丢弃该事件 |
| `miui.process.ForegroundInfo`（前台应用变化） | 改写为桌面 |
| `IProcessObserver` 回调（前台界面、进程启动/结束/状态/重要性） | 丢弃该事件 |
| `getRunningAppProcesses`、`getRunningServices` | 从列表中移除 |
| `UsageStatsManager.queryEvents` / `queryUsageStats` / `queryAndAggregateUsageStats` | 移除相关记录 |
| 包管理器：已安装应用列表、`getPackageInfo`、`getApplicationInfo`、`queryIntent*`、`resolve*`、`getPackagesForUid`、`getNameForUid` 等；`LauncherApps` | 视为未安装 |
| 包广播：安装、更新、卸载、清除数据、强行停止（`PACKAGE_ADDED`、`PACKAGE_REMOVED`、`PACKAGE_REPLACED`、`PACKAGE_RESTARTED` 等），以及 `PACKAGES_SUSPENDED` 这类带包列表的广播 | 丢弃该事件，或从包列表里移除 |

所有改动都发生在小米服务框架进程内，不修改系统框架，出问题时最多影响小米服务框架本身，取消作用域即可恢复。

勾选列表存在设置界面自己的私有目录里（`MODE_PRIVATE`，非全局可读），改动时再用 root 写一份到小米服务框架每个用户的数据目录 `/data/user_de/<用户>/com.xiaomi.xmsf/files/.task-filter`（文件权限 600，属主和 SELinux 上下文与该目录一致），钩子从所在进程的沙盒里读取。这样不依赖 LSPosed 已废弃的 `XSharedPreferences`，也不会产生全局可读的配置文件。

## 安装

1. 从 [Releases](../../releases) 下载 APK 并安装。
2. 在 KernelSU/Magisk 里给 **Task Filter** 授予 root。设置是通过 root 写入小米服务框架的数据目录的，没有 root 不会生效。
3. LSPosed 管理器 → 模块 → 启用 **Task Filter**，作用域勾选 **小米服务框架**。如果开了应用双开（用户 999），列表里两个小米服务框架都要勾。
4. 打开 Task Filter，勾选要隐藏的应用。首次会弹出 root 授权，允许后页面下方会显示「已写入 N 个用户空间」，约 3 秒后生效。
   - HyperOS/MIUI 会先弹出「读取应用列表」权限，请允许，否则只能看到系统应用。
   - 列表里找不到的应用，可以在搜索框输入完整包名，点「＋ 手动添加」。
5. 重启手机，或在 root shell 里执行 `am force-stop com.xiaomi.xmsf`，让小米服务框架以加载了模块的状态重新启动。

> 从 0.1.4 及更早版本升级：设置的存储方式变了——改为用 root 写入小米服务框架自己的数据目录，不再使用 LSPosed 的 `XSharedPreferences`（因此不再有「使用了已废弃功能」的提示）。升级后请重新打开 Task Filter、重新勾选一次应用。

### 应用双开（用户 999）

双开空间里运行着另一个小米服务框架，它同样会收到主空间的前台应用变化。LSPosed 要求模块安装在目标应用所在的用户里，所以还需要：

1. root shell 执行 `pm install-existing --user 999 io.github.taskfilter`；
2. LSPosed 管理器 → 模块 → 切到 999 用户的标签页 → 启用 Task Filter，作用域勾选小米服务框架；
3. 重启手机。

双开空间不用单独设置：那里的模块会读取主空间的勾选列表。

**不要勾选依赖小米推送的应用**（微信、QQ、钉钉、企业微信等），否则它们的通知可能收不到。

## 自动勾选、新应用与导入

### 自动勾选

设置页的「自动勾选」只在本机分析已安装的应用（不联网、不上传），按下面的顺序判断，先命中的为准：

1. **Xposed 模块**（`xposedmodule` 元数据，或 `xposed_init` / libxposed 入口文件）→ 建议隐藏。最先检查，因为有的模块会借用国内应用的包名；
2. 内置名单：root 工具、境外应用（如 Google、Telegram、X、加密货币钱包）→ 建议隐藏；国内大平台（腾讯、阿里、字节、运营商、银行、政务、手机厂商、游戏加速器等）→ 不碰；
3. 内置**代理内核或分流规则库**（`geoip.dat`、`geosite.db`、`Country.mmdb`、clash / xray / sing-box 等内核）→ 代理 / VPN；带**应用分身 / 虚拟机框架**的 → root 相关工具。这两项优先于下面的国内 SDK：订阅制「加速器」常常带支付宝、微信支付；
4. 带**国内推送、登录、支付或分享 SDK**（小米、OPPO、vivo、魅族、荣耀推送，个推、极光、友盟推送、腾讯 TPNS，微信、QQ、支付宝、微博、银联 SDK，运营商一键登录等），或**从国内官方应用商店安装**的 → 国内应用，不碰——**即使它声明了 VPN 服务或申请了 Shizuku 权限**：运营商、应用商店、游戏助手常用 VPN 服务做「网络加速」；
5. 声明了 `BIND_VPN_SERVICE` 服务 → 代理 / VPN；申请了 Shizuku 权限 → root 相关工具；
6. 使用 FCM 推送，或从 Google Play / F-Droid 安装 → 推断为境外应用；
7. 其余列为「未识别」，并注明安装来源；只带 Bugly、腾讯 X5、国内广告或统计 SDK 的会标出「可能是国内开发者的应用」。

**系统应用、当前桌面、WebView、Google Play 服务永远不会被自动勾选**；导入时也会跳过后三者。

确认分两步：先复查少数已勾选的应用——关键组件、**自动勾选**的但现在判断为国内应用的（预先勾上，确认即取消勾选），以及用小米推送的（不预先勾，只是提醒隐藏后可能收不到通知）。**你自己勾选或导入的应用不会被质疑**。然后列出建议新勾选的应用，每一项都写明原因，建议项预先勾上、未识别的不勾，可以逐个调整。

### 记住你的选择

设置页会在本机（应用私有目录，不进代码、不上传）记住你的决定：复查时保留的应用以后不再提示；在建议里没勾、或在列表里手动取消勾选的应用，以后不再建议，安装时也不会自动隐藏（写进配置文件时是 `!包名` 行）。导出文件里也包含这些「拒绝过的」应用，导入时一并恢复。在列表里重新勾选即可撤销。

### 新安装的应用

「新安装的应用符合规则时立即隐藏」默认开启：安装广播到达小米服务框架时，钩子先按上面的规则判断，命中就在任何接收器处理这条广播之前把应用隐藏，并把包名记到同一目录的 `.task-filter-auto`。下次打开设置页时，这些应用会合并进勾选列表，可以在那里取消。「也包括未识别的应用」默认关闭。

已勾选应用的安装、更新、卸载广播一律不会到达小米服务框架。

### 导入与导出

「导出」把当前勾选的应用、拒绝过的应用（`!` 开头）和选项保存成一个文本文件（每行一个包名，后面用 `#` 注明应用名）。「导入」可以从这种文件恢复，也可以直接粘贴包名：`#` 开头的行和行尾 `#` 注释会被忽略，兼容 `pm list packages` 的输出；导入只会增加勾选，不会取消已有的。导出的文件包含你的应用名单，请妥善保管，用完建议删掉。本页的输入框关闭了输入法的个性化学习、联想和自动填充。

## 验证

- LSPosed 日志里应该有 `[task-filter] loaded in com.xiaomi.xmsf (user 0): [...]`（双开空间是 `user 999`），方括号里是生效的钩子；`config (user N): X app(s) hidden` 表示读到了勾选列表（X 为隐藏的应用数）；`filtered so far ...` 是累计过滤次数，其中 `packageEvents` 是拦下的包广播，`autoHidden` 是安装时自动隐藏的应用。模块的日志只记次数，从不记应用名：

  ```
  su -c "grep -h 'task-filter' /data/adb/lspd/log/modules*.log | tail -n 30"
  ```

- 设置页的「记录日志」可以关掉：LSPosed 的模块日志也会进 logcat，确认生效后关闭可以少留痕迹。
- 小米服务框架自己的日志（logcat 标签 `MyLog`）里，`CollectRunningTasks ... report tasks is:` 和 `onPackageGoForeground()` 不应再出现被隐藏的应用：

  ```
  su -c "logcat -d -v uid | grep -E 'MyLog.*(CollectRunningTasks|onPackageGoForeground)'"
  ```

## 限制

- 只作用于小米服务框架这一个组件。系统本身以及其他小米系统应用仍然能看到所有应用。
- 启用之前已经记录或上报的数据无法撤回。
- 自动勾选和安装时隐藏靠的是规则和内置名单，可能误判或遗漏；未识别的应用默认不处理。
- 不处理 `content://com.miui.hybrid.appSignature` 这类由其他进程返回的数据。
- `ProcessMonitor`、`TaskStackListenerHelper` 两组钩子依赖小米服务框架的内部类名，它更新后可能失效（LSPosed 日志里的钩子列表会缺项）；其余钩子挂在系统 API 上，不受影响。
- 测试环境：HyperOS 4（Android 17），小米服务框架 7.5.79-C。

## 构建

GitHub Actions 直接用 SDK 自带的 `javac`、`d8`、`aapt2`、`apksigner` 构建，见 [`build.sh`](build.sh) 和 [`.github/workflows/build.yml`](.github/workflows/build.yml)。每次推送到 `main` 都会发布一个预发布版本。

签名：仓库设置了 `SIGNING_KEY_B64`（base64 编码的 PKCS12）和 `SIGNING_KEY_PASS` 两个 Actions secret 时使用该密钥；否则使用一个缓存在 Actions 里的密钥，连续 7 天没有构建会被清除，之后的版本需要卸载重装。

## English

An LSPosed module that hides selected apps from Xiaomi Service Framework (`com.xiaomi.xmsf`), the HyperOS/MIUI component that hosts Mi Push and also runs event-tracking code which collects running tasks, foreground-app changes, running processes, usage events and the installed-app list.

The module is scoped to `com.xiaomi.xmsf` only and filters, inside that process, the results of `getRunningTasks`/`getRecentTasks`, `ITaskStackListener` events, `miui.process.ForegroundInfo` (rewritten to the launcher), `IProcessObserver` callbacks, `getRunningAppProcesses`/`getRunningServices`, `UsageStatsManager` queries, and package-manager/`LauncherApps` lookups (hidden apps look uninstalled). It also drops package broadcasts (install, update, uninstall and the like) about hidden apps, which the framework's `eventtracking.PackageMonitor` records together with the installer. It never touches `system_server`, and its log only contains counters, never package names.

Apps can be picked by hand, imported from or exported to a text file, or suggested by an on-device "auto-select": known root tools and foreign apps, Xposed modules, VPN services, the Shizuku permission, and apps that use FCM or came from Google Play / F-Droid. Xposed modules are recognised first (some borrow a domestic package name); a bundled proxy core or routing database (geoip/geosite, clash, xray ...) and app-cloning frameworks count even when the app carries domestic payment SDKs. Otherwise domestic evidence wins: listed domestic platforms, apps carrying a Chinese push, login, payment or sharing SDK, and apps installed from an official domestic store are never suggested, even when they declare a VPN service (carriers and game helpers use one for "acceleration"), so their notifications keep working. System apps, the launcher, WebView and Play services are never suggested either. Suggestions show their reason and can be unticked one by one. The settings screen remembers, on the device only, which apps the user kept or turned down, so later runs do not contradict those choices; the user's own picks are never questioned. The same rules can hide newly installed apps before any receiver in the framework sees the install.

Install the APK, enable it in LSPosed with the scope "Xiaomi Service Framework" (both entries if a dual-app user exists), pick the apps in the module's settings screen, then reboot or `am force-stop com.xiaomi.xmsf`. Do not hide apps that rely on Mi Push.

## License

[MIT](LICENSE)
