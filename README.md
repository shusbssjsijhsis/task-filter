# Task Filter

一个 LSPosed 模块：让「小米服务框架」（`com.xiaomi.xmsf`）看不到你指定的应用。

[English](#english)

## 为什么需要它

小米服务框架承载小米推送，同时带有事件上报代码。对 7.5.79-C 版本做静态分析可以看到它会：

- 用 `ActivityManager.getRunningTasks` / `getRecentTasks` 收集任务列表，交给 `eventtracking` 模块（日志 `CollectRunningTasks ... report tasks is:`，内容包括每个任务的包名、界面名和最后活动时间）；
- 通过 `miui.process.IForegroundInfoListener` 和 `IProcessObserver` 监听前台应用切换。`ActivationManager` 把记录写进本地数据库 `activation.db`，代码里有 `uploadEventToServer` 和 `onSyncToOneTrackDone` 等上报路径；
- 通过自己解析的 `ITaskStackListener` 事件跟踪任务切换；
- 查询运行中的进程、使用记录（`UsageStatsManager.queryEvents`）和已安装应用列表。

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

## 验证

- LSPosed 日志里应该有 `[task-filter] loaded in com.xiaomi.xmsf (user 0): [...]`（双开空间是 `user 999`），方括号里是生效的钩子；`config (user N): X app(s) hidden` 表示读到了勾选列表（X 为隐藏的应用数）；`filtered so far ...` 是累计过滤次数。模块的日志只记次数，从不记应用名：

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
- 不处理 `content://com.miui.hybrid.appSignature` 这类由其他进程返回的数据。
- `ProcessMonitor`、`TaskStackListenerHelper` 两组钩子依赖小米服务框架的内部类名，它更新后可能失效（LSPosed 日志里的钩子列表会缺项）；其余钩子挂在系统 API 上，不受影响。
- 测试环境：HyperOS 4（Android 17），小米服务框架 7.5.79-C。

## 构建

GitHub Actions 直接用 SDK 自带的 `javac`、`d8`、`aapt2`、`apksigner` 构建，见 [`build.sh`](build.sh) 和 [`.github/workflows/build.yml`](.github/workflows/build.yml)。每次推送到 `main` 都会发布一个预发布版本。

签名：仓库设置了 `SIGNING_KEY_B64`（base64 编码的 PKCS12）和 `SIGNING_KEY_PASS` 两个 Actions secret 时使用该密钥；否则使用一个缓存在 Actions 里的密钥，连续 7 天没有构建会被清除，之后的版本需要卸载重装。

## English

An LSPosed module that hides selected apps from Xiaomi Service Framework (`com.xiaomi.xmsf`), the HyperOS/MIUI component that hosts Mi Push and also runs event-tracking code which collects running tasks, foreground-app changes, running processes, usage events and the installed-app list.

The module is scoped to `com.xiaomi.xmsf` only and filters, inside that process, the results of `getRunningTasks`/`getRecentTasks`, `ITaskStackListener` events, `miui.process.ForegroundInfo` (rewritten to the launcher), `IProcessObserver` callbacks, `getRunningAppProcesses`/`getRunningServices`, `UsageStatsManager` queries, and package-manager/`LauncherApps` lookups (hidden apps look uninstalled). It never touches `system_server`, and its log only contains counters, never package names.

Install the APK, enable it in LSPosed with the scope "Xiaomi Service Framework" (both entries if a dual-app user exists), pick the apps in the module's settings screen, then reboot or `am force-stop com.xiaomi.xmsf`. Do not hide apps that rely on Mi Push.

## License

[MIT](LICENSE)
