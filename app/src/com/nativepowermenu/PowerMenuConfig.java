package com.nativepowermenu;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The module's settings: master switch, item order, per-item switches and the power long-press
 * timeout.
 *
 * <p>The settings app owns the preferences the user edits, and pushes a copy to SystemUI over an
 * ordered broadcast; SystemUI persists that copy in its <em>own</em> preferences, so the config
 * survives a SystemUI restart without either process having to read the other's private files.
 */
final class PowerMenuConfig {

    static final String PREF_FILE = "native_power_menu";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_ORDER = "order";
    static final String KEY_DISABLED = "disabled";
    static final String KEY_LONG_PRESS_MS = "long_press_ms";

    /** Settings app -&gt; SystemUI. */
    static final String ACTION_APPLY = "com.nativepowermenu.action.APPLY";
    static final String EXTRA_ENABLED = "enabled";
    static final String EXTRA_ORDER = "order";
    static final String EXTRA_DISABLED = "disabled";
    static final String EXTRA_LONG_PRESS_MS = "long_press_ms";
    /** {@code true} for "保存并应用" (restart SystemUI), {@code false} for a silent re-sync. */
    static final String EXTRA_RESTART = "restart";
    /** SystemUI -&gt; settings app, in the ordered broadcast's result extras. */
    static final String EXTRA_FRAMEWORK_DEFAULT_MS = "framework_default_ms";

    /**
     * Signature permission declared by the module. SystemUI requires it of the sender when it
     * registers the apply receiver, so no other app can restart SystemUI through that channel.
     */
    static final String APPLY_PERMISSION = "com.nativepowermenu.permission.APPLY";

    /** {@code Activity.RESULT_OK} equivalent used to confirm delivery of the ordered broadcast. */
    static final int RESULT_APPLIED = 1;

    private static final String SEPARATOR = ",";

    private static PowerMenuConfig sInstance;

    final boolean enabled;
    /** Every supported key, in display order. */
    final List<String> order;
    /** Keys the user switched off; never contains {@link PowerMenuItems#ALWAYS_ON}. */
    final Set<String> disabled;
    /** How long the power key has to be held before the menu appears; {@code 0} = leave it alone. */
    final int longPressMs;

    private PowerMenuConfig(boolean enabled, List<String> order, Set<String> disabled,
            int longPressMs) {
        this.enabled = enabled;
        this.order = order;
        this.disabled = disabled;
        this.longPressMs = longPressMs;
    }

    boolean isEnabled(String key) {
        return PowerMenuItems.ALWAYS_ON.contains(key) || !disabled.contains(key);
    }

    // ---------------------------------------------------------------- SystemUI side

    /** Cached per process; the config only changes when the settings app applies new values. */
    static synchronized PowerMenuConfig get(Context context) {
        if (sInstance == null) {
            sInstance = read(context);
        }
        return sInstance;
    }

    static synchronized void invalidate() {
        sInstance = null;
    }

    static PowerMenuConfig read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        return of(prefs.getBoolean(KEY_ENABLED, true),
                prefs.getString(KEY_ORDER, null),
                prefs.getString(KEY_DISABLED, null),
                prefs.getInt(KEY_LONG_PRESS_MS, 0),
                context.getResources());
    }

    /**
     * Persists a config delivered by the settings app, and returns it (or {@code null} when the
     * intent was not ours). Uses {@code commit()} on purpose: SystemUI may be restarted right
     * after, so a lazy {@code apply()} could still be in flight when the process dies.
     */
    static PowerMenuConfig apply(Context context, Intent intent) {
        if (intent == null || !ACTION_APPLY.equals(intent.getAction())) {
            return null;
        }
        PowerMenuConfig config = of(
                intent.getBooleanExtra(EXTRA_ENABLED, true),
                intent.getStringExtra(EXTRA_ORDER),
                intent.getStringExtra(EXTRA_DISABLED),
                intent.getIntExtra(EXTRA_LONG_PRESS_MS, 0),
                context.getResources());
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit();
        config.writeTo(editor);
        boolean stored = editor.commit();
        invalidate();
        ModuleLog.d("config applied: enabled=" + config.enabled
                + " order=" + config.order + " disabled=" + config.disabled
                + " longPressMs=" + config.longPressMs
                + " stored=" + stored);
        return config;
    }

    void writeTo(SharedPreferences.Editor editor) {
        editor.putBoolean(KEY_ENABLED, enabled);
        editor.putString(KEY_ORDER, join(order));
        editor.putString(KEY_DISABLED, join(disabled));
        editor.putInt(KEY_LONG_PRESS_MS, longPressMs);
    }

    // ---------------------------------------------------------------- construction

    static PowerMenuConfig of(boolean enabled, String order, String disabled, int longPressMs,
            Resources res) {
        List<String> keys = parseKeys(order);
        if (keys.size() < 2) {
            keys = defaultOrder(res);
        } else {
            keys = complete(keys);
        }
        Set<String> off = new LinkedHashSet<>(parseKeys(disabled));
        off.removeAll(PowerMenuItems.ALWAYS_ON);
        return new PowerMenuConfig(enabled, keys, off, LongPress.clamp(longPressMs));
    }

    /** Fills in any supported key the stored order does not mention, so nothing goes missing. */
    static List<String> complete(List<String> keys) {
        List<String> result = new ArrayList<>(keys);
        for (String key : PowerMenuItems.SUPPORTED) {
            if (!result.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    /**
     * Mirrors what SystemUI does without any stored settings: the device's own
     * {@code config_globalActionsList}, with the two extended entries right after Restart.
     */
    static List<String> defaultOrder(Resources res) {
        List<String> result = new ArrayList<>();
        String[] raw = ResourceLookup.stringArray(
                res, ResourceLookup.PKG_ANDROID, "config_globalActionsList");
        if (raw != null) {
            for (String key : raw) {
                if (PowerMenuItems.isSupported(key) && !result.contains(key)) {
                    result.add(key);
                }
            }
        }
        if (result.size() < 2) {
            result = new ArrayList<>(PowerMenuItems.DEFAULT_ORDER);
        }
        int restart = result.indexOf(PowerMenuItems.RESTART);
        if (restart >= 0) {
            result.add(restart + 1, PowerMenuItems.BOOTLOADER);
            result.add(restart + 2, PowerMenuItems.RECOVERY);
        } else {
            result.add(PowerMenuItems.BOOTLOADER);
            result.add(PowerMenuItems.RECOVERY);
        }
        return complete(result);
    }

    // ---------------------------------------------------------------- serialisation

    static List<String> parseKeys(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return result;
        }
        for (String part : value.split(SEPARATOR)) {
            String key = part.trim();
            if (PowerMenuItems.isSupported(key) && !result.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    static String join(Collection<String> keys) {
        StringBuilder builder = new StringBuilder();
        for (String key : keys) {
            if (builder.length() > 0) {
                builder.append(SEPARATOR);
            }
            builder.append(key);
        }
        return builder.toString();
    }
}
