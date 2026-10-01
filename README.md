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
| 引导模式 | 点击直接 `PowerManager.reboot("bootloader")` |
| 恢复模式 | 点击直接 `PowerManager.reboot("recovery")` |

这两项**不是 AOSP 自带的**，但形态与其它项完全一致：点一下就重启，**没有二次确认框**
（进 fastboot 后是音量键选择、长按电源键退出）。

难点在于权限：SystemUI **没有** `android.permission.REBOOT`（`dumpsys package com.android.systemui`
里连申请都没有），而 `PowerManager.reboot(String)` 是在 system_server 里做权限检查的。
所以模块同时注入 `system` 进程，并借用一个只读的 binder 方法
`IStatusBarService.getDisableFlags(IBinder, int)` 当私有通道：SystemUI 用 `null` token + magic 的
`userId` 调它，system_server 侧的钩子识别 magic、吞掉这次调用（真实实现按 token 查不到记录，
本来也只返回 `{0, 0}`，不会有副作用），再以系统进程身份调用 `IPowerManager.reboot` ——
这条路正是 ColorOS 自己的 `StatusBarManagerService.reboot(boolean)` 走的路，权限天然满足。
挑这个方法的另一个原因是它**有返回值**：system_server 能把 ACK / NAK 回给 SystemUI，
于是「对面根本没加载」和「重启本身失败」能被区分开，而不是静默失败。

同一个通道也用来下发「长按电源键延迟」：magic `userId` 的高 16 位区分请求类型，低 16 位带上
毫秒值（`0` = 用系统默认），所以不需要第二条 binder 方法。

> 嵌套的同进程 binder 调用会继承调用方的 uid，所以 system_server 侧在 `IPowerManager.reboot` 前
> 必须 `Binder.clearCallingIdentity()`，否则 `PowerManagerService` 看到的是 SystemUI 的 uid 10237，
> 直接抛 `SecurityException: ... does not have android.permission.REBOOT`。

### 长按电源键延迟

系统里「按住电源键多久弹出电源菜单」由 `PhoneWindowManager` 的一条按键规则决定：
`SingleKeyGestureDetector` 用它给出的超时来判定长按，超时后调用 `powerLongPress()` →
`showGlobalActions()`（也就是本模块替换掉的那个入口）。ColorOS 这一层也确认过：
`powerLongPress()` 会先问 Oplus 的 `oplusInterceptLongPowerPress()`，它只在**屏幕已熄灭**时截胡
（返回 true），亮屏时只做一次振动就放行 —— 所以亮屏下的菜单确实走 AOSP 那条路。

**关键结论：这台设备上的电源菜单不是「长按」弹出来的，而是「超长按」弹出来的**，而且那个超时被
ColorOS 写死在自己的扩展里：

```java
// SingleKeyGestureDetectorExtImpl（oplus-services.jar）
public long modifyPressTimeout(int pressType, long veryLongPressTimeout, KeyEvent event) {
    if (pressType == 1 && event.getKeyCode() == 26) {   // 26 = 电源键
        return 2500L;                                    // ← 就是这 2.5 秒
    }
    return veryLongPressTimeout;
}
```

`SingleKeyGestureDetector.interceptKeyDown()` 给「超长按」排消息时，延时要过这一层
（`modifyPressTimeout(1, rule.getVeryLongPressTimeoutMs(), event)`），于是不管
`config_veryLongPressTimeout` 是多少，电源键永远是 2500 ms；到点后
`PowerKeyRule.onVeryLongPress()` → `powerVeryLongPress()` → `showGlobalActions()` 弹菜单。
而 `PowerKeyRule#getLongPressTimeoutMs()`（普通长按）在这台机器上**根本不会被投递** ——
日志实测：按键到 `showGlobalActions()` 相隔 2.513 s，`onLongPress`/`powerLongPress` 一次都没出现过。

所以模块（2.1.4 起）挂两个点，任一路径都认这个设置：

| 挂点 | 作用 |
| --- | --- |
| `SingleKeyGestureDetectorExtImpl#modifyPressTimeout` | **这台设备真正生效的那个**：把写死的 2500 ms 换成设置值；同时把 2500 记下来当作「设备默认值」 |
| `PhoneWindowManager$PowerKeyRule#getLongPressTimeoutMs` | 普通长按路径（原生 AOSP 上是这条），一并替换 |

两者都只认「pressType = 超长按 且 keyCode = 电源键」，不影响其它按键。

> **默认值不能只看 `config_longPressOnPowerDurationMs`。** framework-res 里写的是 500 ms，但实测
> 按住电源键要两秒多菜单才出来，说明真正生效的值另有来源（`Settings.Global` 的
> `power_button_long_press` / `power_button_long_press_duration_ms` 是一层，ColorOS 自己覆盖的
> 资源又是一层）。所以模块**不去猜**：system_server 侧记下框架每次真正返回的值，通过有序广播的
> 结果回传给设置页，卡片上显示的默认值就是设备实测值（本机 2500 ms）。
> 这里刻意**不**记录普通长按规则报告的 500 ms —— 那个值在 ColorOS 上根本不参与弹菜单。该值要按过一次电源键之后才有
> （只有 system_server 观察得到），在那之前只显示「默认」。

设置里改完点「保存并应用」会立刻下发（不用重启 system_server）。取值范围 **500 – 5000 ms**，
拖动步进 100 ms；右侧的数值本身就是一个输入框，可以直接敲精确到毫秒的数字（离开输入框或按
回车才提交），标题右侧的 ⟳ 图标按钮恢复设备默认值（存 0 = 不干预，此时按钮变暗表示当前就是默认）。
上限 5000 ms 是安全的：ColorOS 在按下 3 秒后开始的只是关机状态记录（`HwShutdownRecord` 写标志位、
刷数据库），真正强制断电由硬件在更久之后处理。

> 「默认」这个数字取自 system_server 实际观察到的那一个 —— 也就是
> `modifyPressTimeout` 原本会返回的 2500，**不是**普通长按规则报告的 500 ms。
> 这一条要装完新版本**重启手机**后才生效：设置页显示的默认值是 system_server 回报的，
> 而 system_server 里的模块代码只在开机时加载。

> **改到 system_server 的代码必须重启手机才生效。** 模块注入 system_server 是在它启动时完成的，
> 装完新 APK 只重启「系统界面」不会重新加载系统进程里那份 dex（LSPosed 会为 SystemUI 重新注入，
> 但不会重启动系统进程）。判据：LSPosed 日志里搜 `(system)[com.nativepowermenu`，看不到
> `system_server: hooked ...` 那一行就是系统进程还在跑旧代码。

## 设置界面

模块自带一个启动器入口（`SettingsActivity`），用 `Theme.DeviceDefault.DayNight` + 框架 Material 控件
搭出 AOSP 设置风格的界面，不引入 Material Components 依赖：

```
┌──────────────────────────────┐
│ 启用模块              [ ●——] │   总开关，关掉立刻回到 ColorOS 默认电源菜单
│ 关闭后恢复 ColorOS 默认电源菜单   │
└──────────────────────────────┘
┌──────────────────────────────┐
│ 电源菜单长按时间 ⟳    2500 ms │   ⟳ = 恢复默认（默认状态下变暗）；数值可直接编辑
│ ●────────────────────────── │   500 – 5000 ms，步进 100 ms；滑杆两端各内缩 10dp
└──────────────────────────────┘
电源菜单项 ⟳                        ⟳ = 恢复默认排序与开关（引导/恢复 关，其余开）；
                                    已经是默认状态时变暗不可点
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
- **列表重置**：「电源菜单项」右边的 ⟳ 把顺序恢复成设备 `config_globalActionsList` 的默认
  顺序（扩展项紧跟在「重启」之后），开关恢复成「引导模式 / 恢复模式 关，其余开」——
  和模块首次运行的默认值一致；顺序与开关都已是默认值时，按钮变暗且不可点（拖动排序、
  拨动开关会立刻让它重新可用）。和别的改动一样，按下「保存并应用」之前不会写入。
- **长按卡片的顶部/底部留白要和上面那张纯文字卡片"看起来一样"**：卡片内容内边距统一是
  `CARD_PADDING_DP = 14`，但标题行（36dp 里居中一行 22dp 的文字）和滑杆（40dp 里居中一个
  缩略图）各自带着约 7dp / 10dp 的空隙，所以这张卡片写的是 `14 - 7` 和 `14 - 10`，
  让文字与滑杆的**可见**边缘落在和其它卡片相同的位置上（这两个常量就是拿去微调的）。
- **长按卡片的高度是写死的**（标题行 36dp / 输入框 36dp / 滑杆 40dp）：这台机器的主题下
  `wrap_content` 会把这张卡片撑到接近一整屏（标题行被垂直居中、滑杆被挤出可视区），
  而且 EditText 的宽度改成随内容收缩，下划线才会跟着数字长度走。
- **避让系统栏**：模块 `targetSdk 36`，Android 强制 edge-to-edge，设置页把系统栏 + 挖孔 inset
  加进内容内边距；电源菜单那个窗口是全屏、且层级在状态栏之上（`TYPE_STATUS_BAR_SUB_PANEL`），
  系统不会替它避让，同样按 inset 加内边距，保证菜单顶部元素不会跑进状态栏。
- **保存并应用**：写入设置 → 有序广播推给 SystemUI → SystemUI 存盘并重启自己（它是 persistent 应用，
  被系统立刻拉回）。广播的结果码用来判断是否真的送达，没送到会提示「未生效：请确认模块已启用」。
- 打开设置页时会静默重推一次已保存的值（不重启），因此「先配置、后启用模块」也不会丢配置。
- **长按延迟立即生效**：它由 system_server 里的钩子执行，广播一到就先下发过去，不必等 SystemUI
  重启完（SystemUI 每次启动也会重新下发一次，因为 system_server 比它活得久）。
- 广播由模块自己声明的 signature 权限保护，SystemUI 注册时要求发送方持有该权限，
  别的应用无法通过这个通道重启 SystemUI。
- `ModuleLog` 走**反射**访问 Xposed API，并且探测不到就直接只写 logcat：设置应用进程里没有
  Xposed API，直接引用它会以 `NoClassDefFoundError` 闪退（2.2.2 的诊断日志就这么崩过一次）。

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
| `system_server: hooked 1 getLongPressTimeoutMs method(s) on ...PowerKeyRule` | 长按延迟的钩子就位（2.1.0 起） |
| `system_server: hooked 1 modifyPressTimeout method(s) on ...SingleKeyGestureDetectorExtImpl` | ColorOS 那个写死 2500 ms 的钩子就位（2.1.4 起，**这台设备靠它生效**） |
| `power key long press: device=2500 ms, applied=300 ms` | 按电源键时钩子被调用：设备自己会用多久、模块实际返回多久。每进程前几次 + 值变化时记录 |
| `PowerKeyRule.onVeryLongPress` / `powerVeryLongPress()` / `showGlobalActions()` | 长按链路的时间线，用来确认菜单是哪条路径弹出来的 |
| `system_server accepted the long-press timeout: N ms (framework's own: M ms)` | 设置页下发成功；`M` 就是设置页显示的「默认」 |
| `system_server: rebooting to recovery` | 系统进程真的开始重启了 |
| `no answer from system_server` | 系统侧没响应：作用域没勾「系统框架」，**或者装了新版本之后没重启手机**（系统进程里的 dex 还是旧的） |

排查顺序：先看有没有 `(system)[com.nativepowermenu` 开头的行 → 没有就是系统进程没注入（作用域 / 没重启）；
有的话看 `hooked ...` 那几行有没有出现，缺哪条就是对应版本的代码没加载。


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
