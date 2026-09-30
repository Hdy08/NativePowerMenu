package com.nativepowermenu;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import de.robv.android.xposed.XposedHelpers;

/**
 * Builds the AOSP power-menu entry list and executes the selected action.
 *
 * <p>Every action is routed through the same facilities AOSP's own {@code GlobalActionsDialogLite}
 * uses, so the semantics are identical:
 *
 * <ul>
 *   <li>{@code power} -&gt; {@code GlobalActionsManager.shutdown()} (long press: reboot to safe mode)
 *   <li>{@code restart} -&gt; {@code GlobalActionsManager.reboot(false)}
 *   <li>{@code screenshot} -&gt; {@code com.android.internal.util.ScreenshotHelper}
 *   <li>{@code emergency} -&gt; {@code ACTION_EMERGENCY_ASSISTANCE}
 *   <li>{@code lockdown} -&gt; {@code LockPatternUtils.requireStrongAuth()} + {@code IWindowManager.lockNow()}
 * </ul>
 */
final class PowerMenuActions {

    /** AOSP default when {@code config_globalActionsList} is missing or unusable. */
    private static final String[] AOSP_DEFAULT_KEYS = {
            "emergency", "lockdown", "power", "restart", "screenshot",
    };

    private static final List<String> SUPPORTED_KEYS = Arrays.asList(
            "emergency", "lockdown", "power", "restart", "screenshot");

    private static final String KEY_EMERGENCY = "emergency";
    private static final String KEY_LOCKDOWN = "lockdown";
    private static final String KEY_POWER = "power";
    private static final String KEY_RESTART = "restart";
    private static final String KEY_SCREENSHOT = "screenshot";

    /** {@code LockPatternUtils.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN}. */
    private static final int STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN = 32;
    /** {@code UserHandle.USER_ALL}. */
    private static final int USER_ALL = -1;

    /** Extra wait before capturing, so the power menu is really gone from the screen. */
    private static final long SCREENSHOT_DELAY_MS = 500L;

    private final Context mContext;
    private final ClassLoader mClassLoader;
    private final Resources mSysUiRes;

    /** {@code com.android.systemui.plugins.GlobalActions.GlobalActionsManager}. */
    private Object mManager;
    /** {@code com.android.systemui.statusbar.policy.KeyguardStateController}. */
    private Object mKeyguardStateController;

    PowerMenuActions(Context context, ClassLoader classLoader) {
        mContext = context;
        mClassLoader = classLoader;
        mSysUiRes = context.getResources();
    }

    void setManager(Object manager) {
        mManager = manager;
    }

    void setKeyguardStateController(Object controller) {
        mKeyguardStateController = controller;
    }

    // ---------------------------------------------------------------- status bar bookkeeping

    /** Tells the platform that the power menu became visible. */
    void onShownCompat() {
        invokeManager("onGlobalActionsShown");
    }

    /** Tells the platform that the power menu went away. */
    void onHiddenCompat() {
        invokeManager("onGlobalActionsHidden");
    }

    // ---------------------------------------------------------------- item list

    List<PowerMenuItem> createItems(boolean keyguardShowing, boolean deviceProvisioned) {
        List<PowerMenuItem> items = new ArrayList<>();
        boolean telephony = mContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
        boolean secure = isKeyguardSecure();

        for (String key : resolveKeys()) {
            switch (key) {
                case KEY_POWER:
                    items.add(powerItem());
                    break;
                case KEY_RESTART:
                    items.add(restartItem());
                    break;
                case KEY_EMERGENCY:
                    if (telephony) {
                        items.add(emergencyItem());
                    }
                    break;
                case KEY_LOCKDOWN:
                    if (secure && isLockdownAllowed()) {
                        items.add(lockdownItem());
                    }
                    break;
                case KEY_SCREENSHOT:
                    items.add(screenshotItem());
                    break;
                default:
                    // Unsupported vendor key: ignore it rather than showing a dead button.
                    break;
            }
        }
        return items;
    }

    /**
     * Reads {@code config_globalActionsList} from {@code framework-res}. ColorOS ships its own copy
     * of that array, so honouring it keeps the entry set consistent with what the platform thinks
     * is available. If it does not describe a usable AOSP-style menu we fall back to the AOSP
     * default order.
     */
    private String[] resolveKeys() {
        String[] raw = ResourceLookup.stringArray(
                mSysUiRes, ResourceLookup.PKG_ANDROID, "config_globalActionsList");
        if (raw == null) {
            ModuleLog.d("config_globalActionsList not found, using AOSP defaults");
            return AOSP_DEFAULT_KEYS;
        }
        List<String> filtered = new ArrayList<>();
        for (String key : raw) {
            if (key != null && SUPPORTED_KEYS.contains(key) && !filtered.contains(key)) {
                filtered.add(key);
            }
        }
        ModuleLog.d("config_globalActionsList=" + Arrays.toString(raw)
                + " -> using " + filtered);
        if (filtered.size() < 2) {
            return AOSP_DEFAULT_KEYS;
        }
        return filtered.toArray(new String[0]);
    }

    // ---------------------------------------------------------------- items

    private PowerMenuItem powerItem() {
        return new PowerMenuItem(
                KEY_POWER,
                ResourceLookup.drawableId(mSysUiRes, ResourceLookup.PKG_ANDROID, "ic_lock_power_off"),
                ResourceLookup.string(mSysUiRes, ResourceLookup.PKG_ANDROID,
                        "global_action_power_off", "Power off"),
                false,
                () -> invokeManager("shutdown"),
                () -> invokeManager("reboot", Boolean.TRUE));
    }

    private PowerMenuItem restartItem() {
        return new PowerMenuItem(
                KEY_RESTART,
                ResourceLookup.drawableId(mSysUiRes, ResourceLookup.PKG_ANDROID, "ic_restart"),
                ResourceLookup.string(mSysUiRes, ResourceLookup.PKG_ANDROID,
                        "global_action_restart", "Restart"),
                false,
                () -> invokeManager("reboot", Boolean.FALSE),
                null);
    }

    private PowerMenuItem screenshotItem() {
        return new PowerMenuItem(
                KEY_SCREENSHOT,
                ResourceLookup.drawableId(mSysUiRes, ResourceLookup.PKG_ANDROID, "ic_screenshot"),
                ResourceLookup.string(mSysUiRes, ResourceLookup.PKG_ANDROID,
                        "global_action_screenshot", "Screenshot"),
                false,
                this::takeScreenshot,
                null);
    }

    private PowerMenuItem emergencyItem() {
        return new PowerMenuItem(
                KEY_EMERGENCY,
                ResourceLookup.drawableId(mSysUiRes, ResourceLookup.PKG_ANDROID, "emergency_icon"),
                ResourceLookup.string(mSysUiRes, ResourceLookup.PKG_ANDROID,
                        "global_action_emergency", "Emergency"),
                true,
                this::startEmergencyDialer,
                null);
    }

    private PowerMenuItem lockdownItem() {
        return new PowerMenuItem(
                KEY_LOCKDOWN,
                ResourceLookup.drawableId(
                        mSysUiRes, ResourceLookup.PKG_ANDROID, "ic_lock_lockdown"),
                ResourceLookup.string(mSysUiRes, ResourceLookup.PKG_ANDROID,
                        "global_action_lockdown", "Lockdown"),
                false,
                this::lockDown,
                null);
    }

    // ---------------------------------------------------------------- actions

    private void invokeManager(String method, Object... args) {
        Object manager = mManager;
        if (manager == null) {
            ModuleLog.w("no GlobalActionsManager, cannot call " + method);
            return;
        }
        try {
            XposedHelpers.callMethod(manager, method, args);
        } catch (Throwable t) {
            ModuleLog.e("GlobalActionsManager." + method + " failed", t);
        }
    }

    private void startEmergencyDialer() {
        try {
            // Intent.ACTION_EMERGENCY_ASSISTANCE is hidden in the public SDK.
            Intent intent = new Intent("android.intent.action.EMERGENCY_ASSISTANCE");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mContext.startActivity(intent);
        } catch (Throwable t) {
            ModuleLog.e("emergency dialer failed", t);
        }
    }

    /**
     * AOSP delays the capture on purpose ("to give the dialog a chance to go away before it takes a
     * screenshot"); the dialog itself is already dismissed by the caller, but the window removal is
     * asynchronous, so wait a little longer before asking for the shot.
     */
    private void takeScreenshot() {
        new Handler(Looper.getMainLooper())
                .postDelayed(this::doTakeScreenshot, SCREENSHOT_DELAY_MS);
    }

    private void doTakeScreenshot() {
        try {
            Class<?> helperClass = XposedHelpers.findClass(
                    "com.android.internal.util.ScreenshotHelper", mClassLoader);
            Object helper = XposedHelpers.newInstance(helperClass, mContext);
            Method takeScreenshot = XposedHelpers.findMethodExact(
                    helperClass, "takeScreenshot", int.class, Handler.class, Consumer.class);
            // 0 == ScreenshotHelper.ScreenshotSource.GLOBAL_ACTIONS
            takeScreenshot.invoke(helper, 0, new Handler(Looper.getMainLooper()), null);
        } catch (Throwable t) {
            ModuleLog.e("screenshot failed", t);
        }
    }

    private void lockDown() {
        try {
            Object utils = newLockPatternUtils();
            if (utils == null) {
                return;
            }
            XposedHelpers.findMethodExact(utils.getClass(), "requireStrongAuth", int.class, int.class)
                    .invoke(utils, STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN, USER_ALL);

            Object windowManager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.view.WindowManagerGlobal", mClassLoader),
                    "getWindowManagerService");
            // IWindowManager.lockNow(Bundle) is hidden and the argument is null, so resolve the
            // method by shape instead of by argument type.
            for (Method method : windowManager.getClass().getMethods()) {
                if ("lockNow".equals(method.getName())
                        && method.getParameterTypes().length == 1) {
                    method.invoke(windowManager, new Object[]{null});
                    break;
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("lockdown failed", t);
        }
    }

    // ---------------------------------------------------------------- capability probes

    private boolean isKeyguardSecure() {
        // ColorOS only exposes this as KeyguardStateControllerImpl.mSecure; the interface has
        // neither isShowing() nor isMethodSecure(), unlike AOSP.
        return Reflect.booleanValue(mKeyguardStateController, "mSecure", "isMethodSecure", false);
    }

    /**
     * AOSP hides the lockdown button while the device is already locked down, because it would be a
     * no-op. {@code LockPatternUtils.STRONG_AUTH_NOT_REQUIRED == 0} and
     * {@code SOME_AUTH_REQUIRED_AFTER_USER_REQUEST == 4} are the two states where it still matters.
     */
    private boolean isLockdownAllowed() {
        try {
            Object utils = newLockPatternUtils();
            if (utils == null) {
                return false;
            }
            int state = (Integer) XposedHelpers.findMethodExact(
                    utils.getClass(), "getStrongAuthForUser", int.class)
                    .invoke(utils, currentUserId());
            return state == 0 || state == 4;
        } catch (Throwable t) {
            ModuleLog.w("could not query strong auth state: " + t);
            return false;
        }
    }

    private Object newLockPatternUtils() {
        try {
            Class<?> utilsClass = XposedHelpers.findClass(
                    "com.android.internal.widget.LockPatternUtils", mClassLoader);
            return XposedHelpers.newInstance(utilsClass, mContext);
        } catch (Throwable t) {
            ModuleLog.w("could not create LockPatternUtils: " + t);
            return null;
        }
    }

    /** {@code ActivityManager.getCurrentUser()} is hidden in the public SDK. */
    private int currentUserId() {
        try {
            Object userId = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityManager", mClassLoader),
                    "getCurrentUser");
            return userId instanceof Integer ? (Integer) userId : 0;
        } catch (Throwable t) {
            ModuleLog.w("could not resolve current user: " + t);
            return 0;
        }
    }
}
