# ColorOS 电源菜单逆向记录

目标设备：OnePlus PJZ110，ColorOS `PJZ110_16.0.5.703(CN01)`，Android 16 (SDK 36)，
`com.android.systemui` 版本 `16.00.12`（`/system_ext/priv-app/SystemUI/SystemUI.apk`，87 MB，7 个 dex）。

## 1. 分析流程

```bash
# 取出 SystemUI（容器内没有 /system_ext，先拷到可读位置）
adb-shell cp /system_ext/priv-app/SystemUI/SystemUI.apk /sdcard/Download/SystemUI.apk

# 拆 dex
unzip -o SystemUI.apk 'classes*.dex' -d dex
for i in 1 2 3 4 5 6 7; do baksmali d dex/classes$i.dex -o smali/out$i; done

# 阅读单个类（比整包反编译快得多）
java -cp jadx-all.jar jadx.cli.JadxCLI \
     --single-class com.oplus.systemui.shutdown.OplusGlobalActionsDialog \
     --single-class-output src dex/classes6.dex
```

先在 dex 的字符串池里搜关键词定位类，再用 baksmali / jadx 读实现：

```bash
grep -a -o -E '[A-Za-z0-9_/$]*GlobalActions[A-Za-z0-9_$]*' dex/*.dex | sort -u
```

## 2. 调用链

```
GlobalActionsComponent                 (com.android.systemui.globalactions, classes2/3)
  implements CommandQueue.Callbacks, GlobalActions.GlobalActionsManager
  handleShowGlobalActionsMenu()
      ├── mStatusBarKeyguardViewManager.setGlobalActionsVisible(true)
      └── mExtension.get().showGlobalActions(this)      # GlobalActions 插件

GlobalActionsImpl                      (com.android.systemui.globalactions, classes2)
  implements com.android.systemui.plugins.GlobalActions
  showGlobalActions(GlobalActionsManager manager)
      ├── mDisabled 为 true 时直接 return
      └── getGlobalActionsDialogEx().showOrHideDialog(keyguardShowing, deviceProvisioned)

GlobalActionsImpl.getGlobalActionsDialogEx()
      └── DependencyEx.sDependency
            .getDependency(ShutDownDependencyEx.class)
            .getGlobalActionsDialog()                    # Kotlin Lazy 缓存单例

ShutDownDependencyEx                   (com.android.systemui.shutdown, classes3)
  globalActionsDialog$delegate: Lazy<GlobalActionsDialogEx>
```

`GlobalActionsDialogEx` 在本 ROM 里是**空壳基类**（`showOrHideDialog` / `dismissDialog` /
`destroy` 全是 `return-void`），真正的实现在 Oplus 命名空间：

```
com.oplus.systemui.shutdown.OplusGlobalActionsDialog
    extends com.android.systemui.shutdown.GlobalActionsDialogEx
    implements DialogInterface.OnDismissListener/OnShowListener,
               ConfigurationController.ConfigurationListener,
               GlobalActionsPanelPlugin.Callbacks, LifecycleOwner
```

它是一个被整体 fork 出来的 `GlobalActionsDialogLite`（字段几乎一一对应），但把菜单项换成了**一个**：

```java
// OplusGlobalActionsDialog.createActionItems()
ShutdownViewControl control = new ShutdownViewControl(mContext);
mOplusShutdownView          = control.getShutdownView();
mOplusShutdownViewContainer = control.getShutdownViewContainer();
mItems.clear(); mOverflowItems.clear(); mPowerItems.clear();
mItems.add(new OplusShutdownAction());          // ← 唯一一项
```

`OplusShutdownAction.create()` 直接返回静态的 `mOplusShutdownViewContainer`，
里面是 `com.oplus.systemui.shutdown.OplusShutdownView` —— 一个 1400 行的 `View` 子类，
`onDraw` 里用 `Path` / `LinearGradient` / `SpringAnimation` 手绘「拖动条 + 取消 / 关机 / 重启 /
紧急」那一套交互。**没有用任何 layout/drawable 资源**，所以它和 AOSP 样式没有任何可复用的交集；
想让样式变回原生，只能整块换掉。

顺带发现，AOSP 的 `GlobalActionsDialogLite` 在本 ROM 里仍然完整存在（182 KB smali、
`GlobalActionsDialogLite_Factory` 也在），但**没有任何 Dagger 组件提供它**，
`GlobalActionsImpl` 的构造函数里也没有它的 `Provider`，属于死代码：

```java
GlobalActionsImpl(Context, CommandQueue, BlurUtils,
                  KeyguardStateController, DeviceProvisionedController, ShutdownUi)
```

`grep -r GlobalActionsDialogLite_Factory` 在全部 7 个 dex 里只命中它自己。
它需要 38 个 `Provider` 依赖，手工组装不现实，所以这条「复活 AOSP 对话框」的路被排除。

## 3. 注入点选择

| 候选 | 结论 |
| --- | --- |
| `OplusGlobalActionsDialog.showOrHideDialog` | 需要自己伪造 `GlobalActionsManager`，且 Oplus 类名会随版本变 |
| `ShutDownDependencyEx.getGlobalActionsDialog` | 只是取值，返回类型是空壳基类，拿不到 keyguard / provisioned 状态 |
| **`GlobalActionsImpl.showGlobalActions(GlobalActionsManager)`** | **选它** |

选它的理由：

1. 它是 SystemUI **插件接口** `GlobalActions.showGlobalActions()` 的实现，属于跨 ROM 稳定的契约；
   ColorOS 只换了它内部的对话框，没有换这个入口。
2. 唯一参数就是 `GlobalActionsManager`，而 AOSP 的对话框正是靠它来完成
   `onGlobalActionsShown/Hidden`、`shutdown()`、`reboot(boolean)`——
   复用它可以保证状态栏对「电源菜单可见」的记录不出错（`setGlobalActionsVisible` 在
   `GlobalActionsComponent.onGlobalActionsHidden()` 里）。
3. keyguard / provisioned 状态就挂在 `GlobalActionsImpl` 的
   `mKeyguardStateController`、`mDeviceProvisionedController` 两个字段上，可以直接读。

hook 里 `beforeHookedMethod` **先** `setResult(null)`，保证即使后续代码抛异常，
ColorOS 的自绘界面也绝不会被 inflate 出来。

## 4. AOSP 侧参照物

样式参数不是猜的，而是从 AOSP 源码和**设备自身的资源**里取：

- 布局结构：`packages/SystemUI/res/layout/global_actions_grid_lite.xml`
  （`ConstraintLayout` → `GlobalActionsLayoutLite` → 背景为 `global_actions_lite_background`
  的面板，内部用 `Flow` 以 `power_menu_lite_max_columns` 列排布 `GlobalActionsItem`）；
- 单项：`global_actions_grid_item_lite.xml`（`ImageView` 直径 `global_actions_button_size`、
  内边距 `global_actions_button_padding`、背景 `global_actions_lite_button` 椭圆；
  下方 `TextView` 14sp、`global_actions_lite_text`）；
- 这些 dimen / color 在设备的 `/system_ext/priv-app/SystemUI/SystemUI.apk` 里仍然存在
  （`aapt` 读到 `global_actions_button_size`、`global_actions_corner_radius`、
  `global_actions_lite_padding` …），只是不再被引用。
  模块在运行时用 `Resources.getIdentifier()` 按名字取，取不到才退回 AOSP 默认值。

图标与文案：设备上的 `GlobalActionsDialogLite` 用的是 **framework 内部资源**
（`import com.android.internal.R`），从 smali 里的常量可以证实：

```
GlobalActionsDialogLite$ShutDownAction.<init>:  const v0, 0x1080030   # android.R.drawable.ic_lock_power_off
GlobalActionsDialogLite$RestartAction.<init>:   const v0, 0x10805c5
GlobalActionsDialogLite$ScreenshotAction.<init>:const v0, 0x10805c8
GlobalActionsDialogLite$LockDownAction.<init>:  const v0, 0x108046c
```

对照 AOSP `GlobalActionsDialogLite.java` 可确认这些是
`ic_restart` / `ic_screenshot` / `ic_lock_lockdown` / `emergency_icon`。
由于 `com.android.internal.R` 不在公开 SDK 里，模块改用
`Resources.getIdentifier(name, "drawable", "android")` 按名字取——
framework 包（含隐藏资源）能被这样解析到。

菜单项顺序取自 framework 的 `config_globalActionsList`：

```xml
<item>emergency</item><item>lockdown</item><item>power</item>
<item>restart</item><item>logout</item><item>screenshot</item><item>bugreport</item>
```

模块只保留能正确实现的 `emergency / lockdown / power / restart / screenshot`；
读不到或过滤后少于两项时回退到这份 AOSP 默认顺序。

## 5. ColorOS 自绘界面的关键字段（备查）

```
OplusGlobalActionsDialog.mDialog   : OplusGlobalActionsDialog$ActionsDialog
OplusGlobalActionsDialog.mExt      : BaseGlobalActionsDialogExt
OplusGlobalActionsDialog.mItems    : ArrayList<Action>  // 只有 OplusShutdownAction
OplusGlobalActionsDialog.mWindowManagerFuncs : GlobalActions$GlobalActionsManager
mOplusShutdownView           : OplusShutdownView          (static)
mOplusShutdownViewContainer  : androidx.constraintlayout.widget.ConstraintLayout (static)
```

`showOrHideDialog(z, z2)` 的语义（与 AOSP 一致）：

- 已经显示时再次调用 → `onGlobalActionsShown()` 然后 `dismiss()`
- 否则建 `ActionsDialog` → `show()` → `onGlobalActionsShown()`
- `onDismiss()` → `onGlobalActionsHidden()`

## 6. 模块引用的符号逐个静态校验

模块全部通过反射调用系统内部实现，所以每个名字都在设备字节码里核对过
（`SystemUI.apk` 的 7 个 dex + `/system/framework/framework.jar` 的 6 个 dex）。

| 引用 | 结论 |
| --- | --- |
| `globalactions.GlobalActionsImpl` | 存在，且是**唯一**实现 `plugins.GlobalActions` 的类（`GlobalActionsImpl_Factory` 提供它） |
| `GlobalActionsImpl#showGlobalActions` | 存在且是 `GlobalActions` 接口的抽象方法 |
| `GlobalActionsImpl.mContext / mDisabled / mKeyguardStateController / mDeviceProvisionedController` | 4 个字段都在 |
| `GlobalActionsManager#shutdown / reboot(Z) / onGlobalActionsShown / onGlobalActionsHidden` | 4 个抽象方法都在 |
| `KeyguardStateController` | **本 ROM 是精简接口**：只有 `getDismissAmount/getEx/isUnlocked/isVisible/…`，**没有** `isShowing()`、`isMethodSecure()`；对应值是 `KeyguardStateControllerImpl` 的 `mShowing` / `mSecure` 字段 → 模块先读字段、再退回方法 |
| `DeviceProvisionedControllerImpl#isDeviceProvisioned` | 存在（接口上只有 `getCurrentUser`） |
| `com.android.internal.widget.LockPatternUtils` | 在 `framework.jar` 的 `classes6.dex`；`<init>(Context)`、`requireStrongAuth(II)`、`getStrongAuthForUser(I)` 都在 |
| `com.android.internal.util.ScreenshotHelper` | `<init>(Context)`、`takeScreenshot(ILandroid/os/Handler;Ljava/util/function/Consumer;)` 都在 |
| `android.view.WindowManagerGlobal#getWindowManagerService` | 存在；`IWindowManager#lockNow(Bundle)` 存在 |
| 窗口类型 `0x7e1` | 就是 `WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL`（与 `SystemUIDialog` 一致） |
| SystemUI 资源 `global_actions_lite_padding` / `_corner_radius` / `_button_size` / `_button_padding` / `_grid_container_bottom_margin` / `_translate` / `_lite_background` / `_lite_button_background` / `_lite_text` / `_lite_emergency_icon` / `_lite_emergency_background` / `power_menu_lite_max_columns` | 全部存在 |
| 主题 `Theme.SystemUI.Dialog.GlobalActions` | 存在（注意资源名用点号，不是 R 类里的下划线形式） |
| SystemUI 布局 `global_actions_grid_lite` / `global_actions_grid_item_lite` + id `list_flow` / `global_actions_container` | 全部存在，且是 AOSP 原版（见下节） |
| `com.android.systemui.statusbar.phone.SystemUIDialog` | `extends AlertDialog`，`<init>(Context)` 存在（模块早期版本用它做过扩展项的确认框，现已去掉确认步骤） |
| `com.android.server.statusbar.StatusBarManagerService` | 在 `services.jar`；`extends IStatusBarService.Stub`，`setIcon(String,String,int,int,String)` 与 `reboot(boolean)` 都在 |
| `android.os.IPowerManager#reboot(Z,String,Z)` | 存在；系统进程调用时权限天然满足 |

## 7. 设备自带的就是 AOSP 原版布局

我一开始以为 ColorOS 把这些布局也删了，实际并没有 —— `SystemUI.apk` 里原封不动地有：

```
res/layout/global_actions_grid_lite.xml         2052 B
res/layout/global_actions_grid_item_lite.xml    1340 B
res/layout/global_actions_grid_v2.xml           1176 B
res/layout/global_actions_power_dialog.xml       444 B
res/drawable/global_actions_lite_background.xml  448 B
res/drawable/global_actions_lite_button.xml      372 B
```

用 apktool 解出来对比 AOSP main 分支的源码，**结构与属性完全一致**：

```xml
<!-- res/layout/global_actions_grid_lite.xml (ColorOS SystemUI.apk 内) -->
<androidx.constraintlayout.widget.ConstraintLayout android:id="@id/global_actions_container">
  <com.android.systemui.globalactions.GlobalActionsLayoutLite android:id="@id/global_actions_view"
      app:layout_constraintTop_toTopOf="parent" app:layout_constraintBottom_toBottomOf="parent" ...>
    <com.android.systemui.common.ui.view.LaunchableConstraintLayout android:id="@android:id/list"
        android:background="@drawable/global_actions_lite_background"
        android:padding="@dimen/global_actions_lite_padding" ...>
      <androidx.constraintlayout.helper.widget.Flow android:id="@id/list_flow"
          app:flow_wrapMode="chain" app:flow_maxElementsWrap="2" ... />
    </...>
  </...>
</...>
```

也就是说 ColorOS 只是把调用链换成了 `OplusShutdownView`，这些资源变成了死资源。
模块现在直接 inflate 它们，并按 AOSP `GlobalActionsLayoutLite.onUpdateList()` 的做法
把 `GlobalActionsItem` 交给 `Flow`。

`GlobalActionsDialogLite.SinglePressAction.create()` 的关键几步（照抄即可）：

```java
View v = inflater.inflate(R.layout.global_actions_grid_item_lite, parent, false);
v.setId(View.generateViewId());            // Flow 靠 id 引用子 View
ImageView icon = v.findViewById(R.id.icon);        // @android:id/icon
TextView message = v.findViewById(R.id.message);   // @android:id/message
message.setSelected(true);                 // marquee 才动
icon.setImageDrawable(getIcon(context));
```

`com.android.systemui.globalactions.GlobalActionsItem`、`LaunchableConstraintLayout`、
`androidx.constraintlayout.helper.widget.Flow` 三个类在 dex 里也都在。

### 与 HyperOS 提取出来的对比

`Download/系统界面_16.03.251211.r.apk`（HyperOS 16.03.251211）里是同一套资源：

| 文件 | ColorOS | HyperOS |
| --- | --- | --- |
| `global_actions_grid_lite.xml` | 2052 B | 2072 B |
| `global_actions_grid_item_lite.xml` | 1340 B | 1340 B |
| `global_actions_lite_background.xml` | 448 B | 448 B |
| `global_actions_lite_button.xml` | 372 B | 372 B |

apktool 解出来逐行对比，唯一差别是 HyperOS 的根 `ConstraintLayout` 上多一个
`android:clipChildren="false"`。两者都是 AOSP 原版，所以模块直接用**设备自己那份**
（永远与正在运行的 SystemUI 构建匹配），而不是把 HyperOS 的资源搬进来。

## 8. 扩展电源菜单（引导模式 / 恢复模式）

### 米客（Customiuizer）是怎么做的

`Download/米客-26.08.08-test.apk` 就是 `name.monwf.customiuizer`，用的是新一代 libxposed API
（`META-INF/xposed/module.prop` = `minApiVersion 101`），作用域里同时有 `android` 和 `system`。
它的扩展电源菜单在 `bw1.u()`：

```java
int fastboot = res("epm_fastboot_title");      // 它自己的字符串
int recovery = res("epm_recovery_title");
int[] state = {-1};
Class dialog = findClass("com.android.systemui.globalactions.GlobalActionsDialogLite");
Class powerOptions = findClass("...GlobalActionsDialogLite$PowerOptionsAction");

hookCtor("...GlobalActionsDialogLite$SinglePressAction", (int,int))   // after: 改 messageResId
hookAfter(dialog, "createActionItems") {                             // 往 mItems 里塞两个
    ArrayList items = getField(thisObject, "mItems");
    state[0]=1; items.add(newInstance(powerOptions, thisObject));
    state[0]=2; items.add(newInstance(powerOptions, thisObject));
}
hookBefore(powerOptions, "onPress") {                                // 换成确认框
    int id = getField(thisObject, "mMessageResId");
    if (id == fastboot || id == recovery) {
        AlertDialog d = newInstance(SystemUIDialog, context);
        d.setTitle(...); d.setButton(OK, ...); d.show();
        param.setResult(null);                                       // 不走原来的 onPress
    }
}
```

确认后不是自己重启，而是发一个 MIUI 的系统广播：

```java
Intent i = new Intent("miui.intent.action.FastReboot");  // 由 MIUI 系统侧处理
i.putExtra("mode", recovery ? "recovery" : "bootloader");
context.sendBroadcast(i);
```

**ColorOS 没有这个广播接收者**，所以这条路不能照搬。

### ColorOS 上的难点：SystemUI 没有 REBOOT 权限

```
$ adb-shell dumpsys package com.android.systemui | grep -c REBOOT
0
```

`android.permission.REBOOT` 连"申请"都没有，因此 `PowerManager.reboot("recovery")`
会直接抛 SecurityException。而 `GlobalActionsManager` 只有 `reboot(boolean safeMode)`，
`IStatusBarService` 也只有 `reboot(Z)` / `shutdown()`，都没法带 reason。

系统侧倒是可以做，ColorOS 自己的实现就是：

```java
// com.android.server.statusbar.StatusBarManagerService
public void reboot(final boolean safeMode) {
    enforceStatusBarService();
    ...
    reason = safeMode ? "safemode" : "userrequested";
    ... mHandler.post(() -> ShutdownThread.reboot(getUiContext(), reason, false));
}
```

**结论：必须同时注入 `android` 进程。**

### 模块采用的通道

不注册新服务（自定义服务名在 SELinux 里没有 `service_contexts` 标签，SystemUI 未必有
`find` 权限），改用 SystemUI 本来就持有的 binder：

```
SystemUI                                      system_server
  GlobalActionsComponent.mBarService
  IStatusBarService.getDisableFlags(null, 0x4E504D01|2)
        │
        └────────────── binder ──────────────▶  StatusBarManagerService.getDisableFlags(...)
                                                 └─ 钩子识别 magic userId
                                                    ├─ Binder.clearCallingIdentity()   ← 关键
                                                    ├─ IPowerManager.reboot(false, reason, false)
                                                    └─ setResult(new int[]{ACK|NAK})
```

**为什么用 `getDisableFlags(IBinder, int)` 而不是 `setIcon(...)`：它有返回值。**
最初的版本用废弃的 `setIcon` 当载体，静默失败时 SystemUI 完全无从判断 —— 实测就是
「确定之后毫无反应」。现在 system_server 用 `int[]` 回一个 `ACK`/`NAK`，
SystemUI 据此区分「对面根本没加载」和「重启本身失败」，失败时能直接提示用户去勾作用域。
未挂钩时真实的 `getDisableFlags(null, 任意大 userId)` 匹配不到记录，返回 `{0,0}`，无副作用；
被 `STATUS_BAR` 保护，而 SystemUI 持有它。

### 踩到的坑：嵌套 binder 调用会继承外层的 calling uid

第一版实现在真机上失败了，LSPosed 日志给出了完整堆栈：

```
Caused by: java.lang.SecurityException: Neither user 10237 nor current process has
        android.permission.REBOOT.
    at android.app.ContextImpl.enforceCallingOrSelfPermission(ContextImpl.java:2521)
    at com.android.server.power.PowerManagerService$BinderService.reboot(PowerManagerService.java:7686)
```

钩子是在**正在处理 SystemUI 那个 binder 调用**的线程上被触发的，此时线程的
`mCallingUid` 还是 10237（SystemUI），而 system_server 内部再发起一次同进程 binder 调用时
把这个身份带了过去，于是 `PowerManagerService` 认为调用方是 SystemUI。

修法是标准的 `Binder.clearCallingIdentity()`：

```java
long identity = Binder.clearCallingIdentity();   // mCallingUid = getuid() = 1000
try { ...IPowerManager.reboot(...)... } finally { Binder.restoreCallingIdentity(identity); }
```

ColorOS 自己的 `StatusBarManagerService.reboot(boolean)` 在调 `ShutdownThread` 之前
也是这么做的，原因完全一样。

清掉身份之后权限检查确实会过，这一点在设备字节码里核对过：

```java
// android.app.ActivityManager
public static boolean canAccessUnexportedComponents(int uid) {
    int appId = UserHandle.getAppId(uid);
    return appId == 0 || appId == 1000;          // root / system
}
public static int checkComponentPermission(String permission, int uid, ...) {
    if (canAccessUnexportedComponents(uid)) return PERMISSION_GRANTED;
    ...
}
```

### 作用域名字

LSPosed 作用域选择器里 system_server 对应的是包名 `system`（不是 `android`）；
实测勾选 `system` 后模块才会被注入系统进程，日志里表现为行首的 `(system)`。
模块的 `xposed_scope` 现在把 `android` 和 `system` 都列上了。

## 9. 模块自己的界面不会自动避让系统栏

菜单本身是 AOSP 原版布局，但它落在两种「系统不帮忙避让」的窗口里，所以两处都得自己算 inset：

- **设置页（`SettingsActivity`）**：模块 `targetSdk 36`，Android 16 对 targetSdk ≥ 35 的应用强制
  edge-to-edge，`setDecorFitsSystemWindows(true)` 也已经不起作用。窗口铺满整屏、状态栏直接压在内容
  之上，所以内容内边距里必须加上 `systemBars() | displayCutout()` 的 inset，否则列表第一行会钻到
  状态栏下面。`OnApplyWindowInsetsListener` 在第一次 traversal 就会回调，因此首帧就是对的。
- **电源菜单窗口**：沿用 ColorOS / AOSP 的窗口参数 —— `TYPE_STATUS_BAR_SUB_PANEL`(2017) + 全屏 +
  `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`。2017 的层级在状态栏（2000）之上，WindowManager 不会替它
  避让；而且窗口本身是全屏的（否则 `FLAG_DIM_BEHIND` 的遮罩盖不满整屏），所以只能在 inflate 出来的
  根布局上按 inset 加内边距，`ConstraintLayout` 才会把面板居中到安全区里。

## 10. 长按电源键多久才算长按

菜单弹出的入口是 system_server，不是 SystemUI，链路是：

```
电源键按下
  └── PhoneWindowManager.interceptKeyBeforeQueueing / interceptKey
        └── SingleKeyGestureDetector.interceptKey(KeyEvent)
              ├── mLongPressTimeoutMs = rule.getLongPressTimeoutMs()   // 每条按键规则各自的超时
              └── 超时后 → PowerKeyRule.onLongPress(eventTime)
                    └── PhoneWindowManager.powerLongPress(eventTime)
                          └── behavior == LONG_PRESS_POWER_GLOBAL_ACTIONS(1)
                                ├── mPhoneWindowManagerExt.getInputExtension().interceptLongPowerPress()
                                │     （Oplus 扩展；返回 true 就不走 AOSP 那支）
                                └── showGlobalActions()  ← 本模块替换的入口
```

`PowerKeyRule`（`PhoneWindowManager` 的内部类）覆写了超时：

```java
@Override
long getLongPressTimeoutMs() {
    if (PhoneWindowManager.this.getResolvedLongPressOnPowerBehavior() == 5) {   // 长按唤起助理
        return PhoneWindowManager.this.mLongPressOnPowerAssistantTimeoutMs;
    }
    return super.getLongPressTimeoutMs();     // = SingleKeyGestureDetector.sDefaultLongPressTimeout
}
```

- `sDefaultLongPressTimeout` 是 `SingleKeyGestureDetector.init(Context)` 里从资源读出来的**静态**值，
  所有按键规则共用；所以改它会影响别的键，模块改成 hook `PowerKeyRule#getLongPressTimeoutMs` 的返回值，
  只动电源键。
- 资源值：`config_longPressOnPowerDurationMs = 500`、`config_veryLongPressTimeout = 3500`、
  `config_longPressOnPowerBehavior = 5`、`config_veryLongPressOnPowerBehavior = 0`（解出来的是
  framework-res 的**基础值**；75 个 RRO 里没有哪个覆盖这四个键，`Settings.Global` 里
  `power_button_long_press*` 也是空的）。
  但**实测按住电源键要两秒多才弹菜单**，和 500 ms 对不上 —— 说明 `SingleKeyGestureDetector.init()`
  在这台机器上读的不是那个 500 的资源（jadx 给这两个 `getInteger` 解出来的名字
  `config_lidNavigationAccessibility` / `device_idle_light_max_idle_to_ms` 明显是错的，说明它用的
  资源表和设备对不上）。结论：**不要去推断这个值**，让 system_server 每次按键时把
  `getLongPressTimeoutMs()` 的真实返回值记下来回报给设置页。
- 上限因此设在 3000 ms 以内，避免和超长按（3500 ms）叠在一起。
- 但在这台设备上，**普通长按这条链路整个不会被投递**：LSPosed 日志实测，从按键到
  `showGlobalActions()` 相隔 2.513 s，而 `PowerKeyRule#onLongPress` / `powerLongPress()` 一次都
  没出现过。真正弹菜单的是**超长按**：

  ```
  SingleKeyGestureDetector.interceptKeyDown()
    └── sendMessageDelayed(msg, mSingleKeyGestureDetectorExt.modifyPressTimeout(1, rule.getVeryLongPressTimeoutMs(), event))
          └── SingleKeyGestureDetectorExtImpl（ColorOS 自己实现，oplus-services.jar）：
                if (pressType == 1 && event.getKeyCode() == 26) return 2500L;   // 写死
    └── 到点 → PowerKeyRule.onVeryLongPress() → PhoneWindowManager.powerVeryLongPress()
          └── case VERY_LONG_PRESS_POWER_GLOBAL_ACTIONS(1) → showGlobalActions()
  ```

  也就是说 `config_veryLongPressTimeout`（3500）对电源键无效，永远是 2500 ms。要改这个时间，
  必须挂 `SingleKeyGestureDetectorExtImpl#modifyPressTimeout`，而不是
  `getLongPressTimeoutMs`（2.1.0~2.1.3 挂错了地方，所以"改了没反应"）。
  顺带：`getResolvedLongPressOnPowerBehavior()` 在这台机器上取不到（反射失败，日志里是 -1），
  `getLongPressTimeoutMs()` 倒是会被 `interceptKeyUp()` 调用，所以它仍然"看起来正常"。
- 顺带确认：`powerLongPress()` 里先问 Oplus 的 `interceptLongPowerPress()`，返回 true 时不调用
  `showGlobalActions()`；设备实测走到了 `showGlobalActions()`（模块的钩子能拦住并弹出 AOSP 菜单），
  说明 ColorOS 这条扩展没有截胡。


