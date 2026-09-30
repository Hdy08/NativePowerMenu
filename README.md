# NativePowerMenu

把 ColorOS / Oplus（一加、OPPO、realme）的电源菜单**恢复成原生 Android（AOSP）样式**的 LSPosed 模块。

模块只作用于 `com.android.systemui`，不碰系统服务、不碰 framework，功能边界就是「长按电源键弹出的那个菜单」。

```
长按电源键
   └── system_server: PhoneWindowManager
         └── IStatusBarService.showGlobalActions()
               └── CommandQueue → GlobalActionsComponent.handleShowGlobalActionsMenu()
                     └── GlobalActions 插件 (GlobalActionsImpl)
                           ├── ColorOS: OplusGlobalActionsDialog  ←  自绘的 ShutdownView
                           └── 本模块 : AOSP 风格的图标网格 + 文案
```

## 效果

- 用 AOSP 的 `global_actions_grid_lite` 结构渲染：居中的圆角面板 + 圆形按钮 + 下方文字。
- 尺寸、颜色、字号全部从设备自身的 `com.android.systemui` 资源里按名字读取
  （`global_actions_button_size`、`global_actions_lite_background`、`global_actions_corner_radius` …），
  因此会跟随系统主题和 ColorOS 的版本变化。
- 图标与文案取自 `framework-res`（`android:drawable/ic_lock_power_off`、`ic_restart`、
  `ic_screenshot`、`ic_lock_lockdown`、`emergency_icon` 以及对应的 `global_action_*` 字符串），
  会自动跟随系统语言。
- 菜单项沿用 AOSP 的 `config_globalActionsList` 顺序：紧急 / 锁定 / 关机 / 重启 / 截屏。

功能与 AOSP 完全一致：

| 菜单项 | 行为 | 长按 |
| --- | --- | --- |
| 关机 | `GlobalActionsManager.shutdown()` | 重启到安全模式（`reboot(true)`） |
| 重启 | `GlobalActionsManager.reboot(false)` | – |
| 截屏 | `ScreenshotHelper.takeScreenshot(GLOBAL_ACTIONS, …)` | – |
| 紧急 | `android.intent.action.EMERGENCY_ASSISTANCE` | – |
| 锁定 | `LockPatternUtils.requireStrongAuth()` + `IWindowManager.lockNow()` | – |

## 环境要求

- Android 12+（`minSdk 31`），已在 **OnePlus PJZ110 / ColorOS 16.0.5.703 (Android 16, SDK 36)** 上验证。
- LSPosed（含 Zygisk / 内置版本均可）已激活。
- 作用域只需要勾选 **系统界面（com.android.systemui）**。

## 安装

1. 安装 `build/NativePowerMenu.apk`。
2. 打开 LSPosed 管理器 → 模块 → 启用「原生电源菜单」。
3. 作用域勾选「系统界面」。
4. 重启 SystemUI（或重启手机）。

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
app/src/.../               模块源码
build.sh                   无 Gradle 构建脚本
tools/fetch-deps.sh        拉取编译期依赖
docs/reverse-engineering.md ColorOS 电源菜单逆向记录
```

## 卸载

在 LSPosed 里停用模块并重启 SystemUI 即可；ColorOS 的关机界面从未被改动，只是不再被调用。

## 许可

仅用于个人设备上的界面定制。
