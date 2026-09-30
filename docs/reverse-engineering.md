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

