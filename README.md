# NativePowerMenu

把 ColorOS / Oplus（一加、OPPO、realme）的电源菜单**恢复成原生 Android（AOSP）样式**的 LSPosed 模块，
并在原生菜单上补上「引导模式 / 恢复模式」两个扩展项。

```
长按电源键
   └── system_server: PhoneWindowManager
         └── IStatusBarService.showGlobalActions()
               └── CommandQueue → GlobalActionsComponent.handleShowGlobalActionsMenu()
                     └── GlobalActions 插件 (GlobalActionsImpl)
                           ├── ColorOS: OplusGlobalActionsDialog  ←  自绘的 ShutdownView
                           └── 本模块 : 直接 inflate AOSP 原版的 global_actions_grid_lite
```

## 效果

**不是自绘的近似，而是 AOSP 原版布局。** 这一点是逆向时发现的：

设备自己的 `SystemUI.apk` 里仍然原封不动地躺着 AOSP 的
`res/layout/global_actions_grid_lite.xml`、`global_actions_grid_item_lite.xml`、
`res/drawable/global_actions_lite_background.xml` 等资源，只是 ColorOS 把整条调用链换成了
`OplusShutdownView`，这些资源再也没人引用。模块现在直接 inflate 它们，并像
`GlobalActionsLayoutLite.onUpdateList()` 那样，把每个 `GlobalActionsItem` 交给
`ConstraintLayout` 的 `Flow` 排布。

因此布局、`GlobalActionsItem` 的按压反馈、dimen、颜色、主题**全部来自设备本身**：

- 面板背景 `global_actions_lite_background` + 圆角 `global_actions_corner_radius`
- 圆形按钮 `global_actions_lite_button` / `global_actions_button_size` / `_button_padding`
- 文字 14sp + `global_actions_lite_text`，列数取 `power_menu_lite_max_columns`
- 图标与文案取自 `framework-res`（`ic_lock_power_off`、`ic_restart`、`ic_screenshot`、
  `ic_lock_lockdown`、`emergency_icon` 以及对应的 `global_action_*` 字符串），自动跟随系统语言

> 顺带对比过 HyperOS 提取出来的 `系统界面_16.03.251211.r.apk`：它那份 `global_actions_grid_lite.xml`
> 与 ColorOS 自带的**逐字节相同**（只差一个 `android:clipChildren`），所以用设备自己那份最准。

菜单项顺序沿用 AOSP 的 `config_globalActionsList`，功能与 AOSP 完全一致：

| 菜单项 | 行为 | 长按 |
| --- | --- | --- |
| 关机 | `GlobalActionsManager.shutdown()` | 重启到安全模式（`reboot(true)`） |
| 重启 | `GlobalActionsManager.reboot(false)` | – |
| 截屏 | `ScreenshotHelper.takeScreenshot(GLOBAL_ACTIONS, …)` | – |
| 紧急 | `android.intent.action.EMERGENCY_ASSISTANCE` | – |
| 锁定 | `LockPatternUtils.requireStrongAuth()` + `IWindowManager.lockNow()` | – |

### 扩展项

| 菜单项 | 行为 |
| --- | --- |
| 引导模式 | 确认后 `PowerManager.reboot("bootloader")` |
| 恢复模式 | 确认后 `PowerManager.reboot("recovery")` |

这两项**不是 AOSP 自带的**，所以点击后先弹一个 `SystemUIDialog` 确认，避免误触。

难点在于权限：SystemUI **没有** `android.permission.REBOOT`（`dumpsys package com.android.systemui`
里连申请都没有），而 `PowerManager.reboot(String)` 是在 system_server 里做权限检查的。
所以模块同时注入 `system` 进程，并借用一个只读的 binder 方法
`IStatusBarService.getDisableFlags(IBinder, int)` 当私有通道：SystemUI 用 `null` token + magic 的
`userId` 调它，system_server 侧的钩子识别 magic、吞掉这次调用（真实实现按 token 查不到记录，
本来也只返回 `{0, 0}`，不会有副作用），再以系统进程身份调用 `IPowerManager.reboot` ——
这条路正是 ColorOS 自己的 `StatusBarManagerService.reboot(boolean)` 走的路，权限天然满足。
挑这个方法的另一个原因是它**有返回值**：system_server 能把 ACK / NAK 回给 SystemUI，
于是「对面根本没加载」和「重启本身失败」能被区分开，而不是静默失败。

> 嵌套的同进程 binder 调用会继承调用方的 uid，所以 system_server 侧在 `IPowerManager.reboot` 前
> 必须 `Binder.clearCallingIdentity()`，否则 `PowerManagerService` 看到的是 SystemUI 的 uid 10237，
> 直接抛 `SecurityException: ... does not have android.permission.REBOOT`。

## 设置界面

模块自带一个启动器入口（`SettingsActivity`），用 `Theme.DeviceDefault.DayNight` + 框架 Material 控件
搭出 AOSP 设置风格的界面，不引入 Material Components 依赖：

```
┌──────────────────────────────┐
│ 启用模块              [ ●——] │   总开关，关掉立刻回到 ColorOS 默认电源菜单
│ 关闭后恢复 ColorOS 默认电源菜单   │
└──────────────────────────────┘
电源菜单项
┌──────────────────────────────┐
│ ⠿  ⏻  关机          [——●] │   关机 / 重启 是必选项，开关置灰常开
│ ⠿  ↻  重启          [——●] │
│ ⠿  ⛨  锁定          [●——] │
│ ⠿  ⏏  截屏          [●——] │
│ ⠿  ⬓  引导模式       [●——] │
│ ⠿  ⬓  恢复模式       [●——] │
└──────────────────────────────┘

        [   保存并应用   ]
```

- **拖动排序**：按住手柄后只有被抓住的那一行跟手 —— `translationY` 每帧按手指原始坐标重算
  （上下限夹在列表首尾槽位之间），所以一次手势能连续拖到列表内任意位置，也不会跑出列表。
  其它行会实时让位：落点一变，它们就用 140ms 滑到相邻槽位，把那个位置空出来。让位只改绘制偏移
  （`translationY`），**不改顺序**也**不改被拖行的下标**，所以整套坐标计算不会因为重排而失效；
  松手时被拖行用 160ms 滑进空位，顺序才真正提交 —— 此时其它行已经在正确位置上，不需要再动。
  整条链路的 `clipChildren` 都关掉了，抬起来的行靠 `setElevation` 浮在列表之上
  （`ViewGroup` 会按 Z 重排绘制顺序）。
- **避让系统栏**：模块 `targetSdk 36`，Android 强制 edge-to-edge，设置页把系统栏 + 挖孔 inset
  加进内容内边距；电源菜单那个窗口是全屏、且层级在状态栏之上（`TYPE_STATUS_BAR_SUB_PANEL`），
  系统不会替它避让，同样按 inset 加内边距，保证菜单顶部元素不会跑进状态栏。
- **保存并应用**：写入设置 → 有序广播推给 SystemUI → SystemUI 存盘并重启自己（它是 persistent 应用，
  被系统立刻拉回）。广播的结果码用来判断是否真的送达，没送到会提示「未生效：请确认模块已启用」。
- 打开设置页时会静默重推一次已保存的值（不重启），因此「先配置、后启用模块」也不会丢配置。
- 广播由模块自己声明的 signature 权限保护，SystemUI 注册时要求发送方持有该权限，
  别的应用无法通过这个通道重启 SystemUI。

## 环境要求

- Android 12+（`minSdk 31`），已在 **OnePlus PJZ110 / ColorOS 16.0.5.703 (Android 16, SDK 36)** 上验证。
- LSPosed（含 Zygisk / 内置版本均可）已激活。
- 作用域：**系统界面（com.android.systemui）** + **系统（LSPosed 里 system_server 对应的包名是
  `system`，模块的推荐作用域把 `android` 和 `system` 都列上了，勾中任意一个能命中系统进程的即可）**。

> **系统进程这个作用域是必须的，不是可选项。**
>
> ColorOS 的 `PowerManagerService.reboot()` 会 `enforceCallingOrSelfPermission`：
>
> ```java
> public void reboot(boolean confirm, String reason, boolean wait) {
>     mContext.enforceCallingOrSelfPermission("android.permission.REBOOT", null);
>     if ("recovery".equals(reason) || "recovery-update".equals(reason)) {
>         mContext.enforceCallingOrSelfPermission("android.permission.RECOVERY", null);
>     }
>     ...
> ```
>
> 而 SystemUI **两个权限都没有申请**（`dumpsys package com.android.systemui | grep REBOOT` 是空的），
> 所以带 reason 的重启只能由系统进程发起 —— 这也正是 ColorOS 自己
> `StatusBarManagerService.reboot(boolean)` 的做法。模块因此需要在 system_server 里也跑一份。
>
> **注意：升级安装 APK 不会重置 LSPosed 的作用域**，装了新版本之后要自己回
> 「模块 → 原生电源菜单 → 作用域」确认系统进程那一项被勾上，再重启手机。
> 漏了这一步的表现是：设置项与图标都正常，但点「引导模式 / 恢复模式」确定之后毫无反应
> （新版会弹提示告诉你去哪里开）。

## 安装

1. 安装 `build/NativePowerMenu.apk`。
2. 打开 LSPosed 管理器 → 模块 → 启用「原生电源菜单」。
3. 作用域同时勾选「系统界面」和系统进程那一项。
4. 重启手机（系统进程侧的作用域只对重启后新起的 system_server 生效）。
5. 之后直接打开「原生电源菜单」应用调整开关与顺序，点「保存并应用」即可。

## 排错

日志里搜 `NativePowerMenu`（LSPosed 日志或 `logcat`）就能看出链路走到哪一步：

| 日志 | 含义 |
| --- | --- |
| `installed 1 hook(s) on ...GlobalActionsImpl#showGlobalActions` | SystemUI 侧挂钩成功 |
| `apply receiver installed` | 「保存并应用」的通道就绪 |
| `system_server: hooked 1 getDisableFlags method(s)` | **系统侧挂钩成功**，缺这行就是作用域没勾「系统框架」 |
| `system_server: rebooting to recovery` | 系统进程真的开始重启了 |
| `no answer from system_server` | 系统侧没响应，同样是作用域的问题 |


## 构建

项目**不依赖 Gradle / AGP**：只有 `aapt2 → javac → d8 → zipalign → apksigner` 五步。

```bash
./tools/fetch-deps.sh    # 下载 Xposed API 82 编译桩（不打包进 APK）
./build.sh
# 产物：build/NativePowerMenu.apk
```

需要：JDK 11+、Android SDK 的 `build-tools`（`aapt2`/`d8`/`zipalign`/`apksigner`）以及
platform 32 / platform 36 的 `android.jar`。路径都可以用环境变量覆盖：

| 变量 | 说明 |
| --- | --- |
| `ANDROID_HOME` | 有值时会从中取 `platforms/android-36` 与 `platforms/android-32` |
| `ANDROID_JAR_COMPILE` | 编译 Java 用的 android.jar（默认 platform 36） |
| `ANDROID_JAR_LINK` | aapt2 链接资源用的 android.jar（默认 platform 32，见下） |
| `AAPT2` / `D8` / `ZIPALIGN` / `APKSIGNER` / `KEYTOOL` | 各工具路径 |
| `XPOSED_API_JAR` | Xposed API 桩的位置 |
| `SDK_FALLBACK_DIR` | 找不到工具时的兜底目录 |

> **为什么链接用 platform 32 的 android.jar？**
> 链接时 aapt2 需要解析 framework 的 `resources.arsc`。Android 15/16 的资源表格式比老版本
> aapt2 能解析的要新，用 36 的 jar 会报 `RES_TABLE_TYPE_TYPE entry offsets overlap actual
> entry data`。清单里只用到长期存在的属性，用 32 的 framework 表链接完全等价；Java 侧仍然
> 用 36 的 jar 编译。

## 目录

```
app/AndroidManifest.xml    模块清单（xposedmodule / xposedscope）
app/assets/xposed_init     入口类
app/assets/xposed_scope    默认作用域：com.android.systemui + android
app/res/drawable/          扩展项的两个矢量图标（其余资源都用设备自带的）
app/src/.../               模块源码
build.sh                   无 Gradle 构建脚本
tools/fetch-deps.sh        拉取编译期依赖
docs/reverse-engineering.md ColorOS 电源菜单逆向记录
```

## 卸载

在 LSPosed 里停用模块并重启 SystemUI 即可；ColorOS 的关机界面从未被改动，只是不再被调用。
system_server 侧只挂了一个死方法的钩子，停用后不会留下任何东西。

## 许可

仅用于个人设备上的界面定制。
