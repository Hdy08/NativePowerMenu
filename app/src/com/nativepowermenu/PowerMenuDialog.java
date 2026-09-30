package com.nativepowermenu;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * The power menu, rendered with SystemUI's own copy of the AOSP layout.
 *
 * <p>ColorOS still ships {@code res/layout/global_actions_grid_lite.xml} and
 * {@code global_actions_grid_item_lite.xml} untouched - they are the stock AOSP files (the same ones
 * HyperOS ships; only a {@code clipChildren} attribute differs). AOSP's own dialog simply inflates
 * them and feeds the {@code Flow} helper one {@code GlobalActionsItem} per action, so doing the same
 * gives a genuinely native menu rather than an approximation: same layout, same
 * {@code GlobalActionsItem} wrapper, same dimens, colours and per-device theming.
 */
final class PowerMenuDialog {

    private static final String PKG = ResourceLookup.PKG_SYSTEMUI;

    /** {@code WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL}, what SystemUIDialog uses. */
    private static final int TYPE_STATUS_BAR_SUB_PANEL = 2017;

    /** {@code Context.RECEIVER_NOT_EXPORTED} (API 33+, not present in older compile SDKs). */
    private static final int RECEIVER_NOT_EXPORTED = 0x4;

    /** Delay between dismissing the dialog and running the action it triggered. */
    private static final long ACTION_DELAY_MS = 200L;

    private static final int FALLBACK_BUTTON_COLOR = 0xFF303030;
    private static final int FALLBACK_TEXT_COLOR = 0xFFF0F0F0;
    private static final int FALLBACK_EMERGENCY_ICON_COLOR = 0xFFFFB4AB;
    private static final int FALLBACK_EMERGENCY_BACKGROUND = 0xFF690005;

    private final Context mContext;
    private final PowerMenuActions mActions;
    private final ClassLoader mClassLoader;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private Dialog mDialog;
    private BroadcastReceiver mReceiver;

    PowerMenuDialog(Context context, PowerMenuActions actions, ClassLoader classLoader) {
        mContext = context;
        mActions = actions;
        mClassLoader = classLoader;
    }

    boolean isShowing() {
        return mDialog != null && mDialog.isShowing();
    }

    /**
     * Mirrors {@code GlobalActionsDialogLite.showOrHideDialog()}: a second power long-press while
     * the menu is open closes it again.
     */
    void toggle(Object manager, Object keyguardStateController, boolean keyguardShowing,
            boolean deviceProvisioned) {
        mActions.setManager(manager);
        mActions.setKeyguardStateController(keyguardStateController);

        if (isShowing()) {
            mActions.onShownCompat();
            dismiss();
            return;
        }
        show(keyguardShowing, deviceProvisioned);
    }

    void dismiss() {
        Dialog dialog = mDialog;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    // ---------------------------------------------------------------- show / hide

    private void show(boolean keyguardShowing, boolean deviceProvisioned) {
        List<PowerMenuItem> items = mActions.createItems(keyguardShowing, deviceProvisioned);
        if (items.isEmpty()) {
            ModuleLog.w("no power menu items available, not showing anything");
            return;
        }

        Context themed = themedContext();
        View content = buildContentView(themed, items);
        if (content == null) {
            ModuleLog.w("could not build the power menu view");
            return;
        }

        Dialog dialog = new Dialog(themed);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> {
            unregisterReceiver();
            mDialog = null;
            mActions.onHiddenCompat();
        });

        Window window = dialog.getWindow();
        if (window != null) {
            configureWindow(window);
        }

        registerReceiver();
        dialog.show();
        mDialog = dialog;
        mActions.onShownCompat();
    }

    private Context themedContext() {
        int theme = ResourceLookup.styleId(
                mContext.getResources(), PKG, "Theme.SystemUI.Dialog.GlobalActions");
        if (theme != 0) {
            try {
                return new ContextThemeWrapper(mContext, theme);
            } catch (Throwable t) {
                ModuleLog.w("could not apply Theme.SystemUI.Dialog.GlobalActions: " + t);
            }
        }
        return mContext;
    }

    private void configureWindow(Window window) {
        window.setType(TYPE_STATUS_BAR_SUB_PANEL);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        window.setDimAmount(0.6f);
        window.setGravity(Gravity.CENTER);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams attrs = window.getAttributes();
        // Same as the ColorOS dialog: never let the cutout push the panel around.
        attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        window.setAttributes(attrs);
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                dismiss();
            }
        };
        try {
            mContext.registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            try {
                mContext.registerReceiver(receiver, filter);
            } catch (Throwable t2) {
                ModuleLog.w("could not register dismiss receiver: " + t2);
                return;
            }
        }
        mReceiver = receiver;
    }

    private void unregisterReceiver() {
        BroadcastReceiver receiver = mReceiver;
        if (receiver == null) {
            return;
        }
        mReceiver = null;
        try {
            mContext.unregisterReceiver(receiver);
        } catch (Throwable t) {
            ModuleLog.w("could not unregister dismiss receiver: " + t);
        }
    }

    // ---------------------------------------------------------------- view hierarchy

    /**
     * Inflates AOSP's {@code global_actions_grid_lite} and fills it the way
     * {@code GlobalActionsLayoutLite.onUpdateList()} would, minus the adapter.
     */
    private View buildContentView(Context context, List<PowerMenuItem> items) {
        Resources res = context.getResources();
        LayoutInflater inflater = LayoutInflater.from(context);

        int rootLayout = ResourceLookup.layoutId(res, PKG, "global_actions_grid_lite");
        if (rootLayout == 0) {
            ModuleLog.w("SystemUI has no global_actions_grid_lite layout, using the fallback view");
            return buildFallbackContentView(context, items);
        }

        View root;
        try {
            root = inflater.inflate(rootLayout, null, false);
        } catch (Throwable t) {
            ModuleLog.e("could not inflate global_actions_grid_lite", t);
            return buildFallbackContentView(context, items);
        }

        ViewGroup listView = root.findViewById(android.R.id.list);
        View flow = root.findViewById(ResourceLookup.id(res, PKG, "list_flow"));
        int itemLayout = ResourceLookup.layoutId(res, PKG, "global_actions_grid_item_lite");
        if (listView == null || flow == null || itemLayout == 0) {
            ModuleLog.w("AOSP power menu layout is incomplete, using the fallback view");
            return buildFallbackContentView(context, items);
        }

        for (PowerMenuItem item : items) {
            View itemView = inflater.inflate(itemLayout, listView, false);
            // ConstraintLayout's Flow references children by id.
            itemView.setId(View.generateViewId());
            bindItem(context, itemView, item, res);
            listView.addView(itemView);
            addToFlow(flow, listView, itemView);
        }

        // AOSP drives this from power_menu_lite_max_columns; the XML already defaults to 2.
        int columns = ResourceLookup.integer(res, PKG, "power_menu_lite_max_columns", 2);
        if (columns > 0) {
            try {
                XposedHelpers.callMethod(flow, "setMaxElementsWrap", columns);
            } catch (Throwable ignored) {
                // Not fatal: the XML attribute already picks a sensible value.
            }
        }

        View container = root.findViewById(
                ResourceLookup.id(res, PKG, "global_actions_container"));
        if (container != null) {
            // The window fills the screen, so "tap outside" means "tap on this container".
            container.setOnClickListener(v -> dismiss());
        }
        return root;
    }

    /**
     * Registers an item with the {@code Flow} helper. AOSP calls {@code Flow#addView(View)}, which
     * also detaches the view from its parent on some androidx versions; if that happened we put it
     * back under the ConstraintLayout so it still gets laid out.
     */
    private void addToFlow(View flow, ViewGroup listView, View itemView) {
        try {
            XposedHelpers.callMethod(flow, "addView", itemView);
        } catch (Throwable t) {
            ModuleLog.w("Flow#addView failed", t);
        }
        if (itemView.getParent() == null) {
            listView.addView(itemView);
        }
    }

    private void bindItem(Context context, View itemView, PowerMenuItem item, Resources res) {
        ImageView icon = itemView.findViewById(android.R.id.icon);
        TextView message = itemView.findViewById(android.R.id.message);
        if (message != null) {
            message.setText(item.label);
            // Required for the marquee to animate, exactly like AOSP does it.
            message.setSelected(true);
        }
        if (icon != null) {
            Drawable drawable = item.iconDrawable;
            if (drawable == null && item.iconResId != 0) {
                try {
                    drawable = context.getDrawable(item.iconResId);
                } catch (Throwable t) {
                    ModuleLog.w("could not load the icon for " + item.key, t);
                }
            }
            if (drawable != null) {
                drawable = drawable.mutate();
                icon.setImageDrawable(drawable);
            }
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (item.emergency) {
                int iconColor = ResourceLookup.color(res, PKG,
                        "global_actions_lite_emergency_icon", FALLBACK_EMERGENCY_ICON_COLOR);
                int background = ResourceLookup.color(res, PKG,
                        "global_actions_lite_emergency_background", FALLBACK_EMERGENCY_BACKGROUND);
                if (drawable != null) {
                    drawable.setTint(iconColor);
                }
                // Also overrides the layout's android:tint, so it survives applyImageTint().
                icon.setImageTintList(ColorStateList.valueOf(iconColor));
                icon.setBackgroundTintList(ColorStateList.valueOf(background));
                itemView.setBackgroundTintList(ColorStateList.valueOf(background));
            }
        }

        // Run the action from a Handler, not from the view: dismiss() detaches the view and
        // View#postDelayed on a detached view may never run. The delay also lets the window go away
        // first, which matters for the screenshot entry.
        itemView.setOnClickListener(v -> {
            dismiss();
            mHandler.postDelayed(() -> runItem(item), ACTION_DELAY_MS);
        });
        if (item.onLongPress != null) {
            itemView.setOnLongClickListener(v -> {
                dismiss();
                mHandler.postDelayed(item.onLongPress, ACTION_DELAY_MS);
                return true;
            });
        }
    }

    private void runItem(PowerMenuItem item) {
        if (!item.needsConfirmation()) {
            item.onPress.run();
            return;
        }
        showConfirmation(item);
    }

    /**
     * The extended entries ask before rebooting - a stray tap must not drop the phone into fastboot.
     * AOSP has no such dialog, so this reuses SystemUI's own {@code SystemUIDialog} (the class
     * MIUI-style extended power menus use) and falls back to a plain {@link AlertDialog}.
     */
    private void showConfirmation(PowerMenuItem item) {
        DialogInterface.OnClickListener confirm = (dialog, which) ->
                mHandler.postDelayed(item.onPress, ACTION_DELAY_MS);

        try {
            Class<?> systemUiDialog = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.SystemUIDialog", mClassLoader);
            Object instance = XposedHelpers.newInstance(systemUiDialog, mContext);
            AlertDialog dialog = (AlertDialog) instance;
            dialog.setTitle(item.confirmTitle);
            dialog.setMessage(item.confirmMessage);
            dialog.setButton(DialogInterface.BUTTON_POSITIVE,
                    ModuleResources.string(mContext, R.string.reboot_confirm_ok, "OK"), confirm);
            dialog.setButton(DialogInterface.BUTTON_NEGATIVE,
                    ModuleResources.string(mContext, R.string.reboot_confirm_cancel, "Cancel"),
                    (DialogInterface.OnClickListener) null);
            dialog.show();
        } catch (Throwable t) {
            ModuleLog.w("SystemUIDialog unavailable, using a plain AlertDialog", t);
            int theme = ResourceLookup.styleId(mContext.getResources(), PKG,
                    "Theme.SystemUI.Dialog.GlobalActions");
            AlertDialog.Builder builder = theme != 0
                    ? new AlertDialog.Builder(mContext, theme)
                    : new AlertDialog.Builder(mContext);
            builder.setTitle(item.confirmTitle)
                    .setMessage(item.confirmMessage)
                    .setPositiveButton(
                            ModuleResources.string(mContext, R.string.reboot_confirm_ok, "OK"),
                            confirm)
                    .setNegativeButton(
                            ModuleResources.string(
                                    mContext, R.string.reboot_confirm_cancel, "Cancel"),
                            null)
                    .show();
        }
    }

    /**
     * Last-resort view used only when the AOSP layout is missing (a future ColorOS could drop it).
     * Keeps the menu usable instead of leaving the power button dead.
     */
    private View buildFallbackContentView(Context context, List<PowerMenuItem> items) {
        Resources res = context.getResources();
        int textColor = ResourceLookup.color(res, PKG,
                "global_actions_lite_text", FALLBACK_TEXT_COLOR);
        int backgroundColor = ResourceLookup.color(res, PKG,
                "global_actions_lite_background", FALLBACK_BUTTON_COLOR);
        int padding = ResourceLookup.dimen(res, PKG, "global_actions_lite_padding", 24);
        int radius = ResourceLookup.dimen(res, PKG, "global_actions_corner_radius", 28);
        int rowPadding = Math.round(16 * res.getDisplayMetrics().density);
        int iconSize = Math.round(24 * res.getDisplayMetrics().density);

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        for (PowerMenuItem item : items) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(rowPadding, rowPadding, rowPadding, rowPadding);

            ImageView icon = new ImageView(context);
            icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
            Drawable drawable = item.iconDrawable;
            if (drawable == null && item.iconResId != 0) {
                drawable = context.getDrawable(item.iconResId);
            }
            if (drawable != null) {
                drawable = drawable.mutate();
                drawable.setTint(textColor);
                icon.setImageDrawable(drawable);
            }
            row.addView(icon);

            TextView label = new TextView(context);
            label.setText(item.label);
            label.setTextColor(textColor);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            label.setPadding(rowPadding, 0, 0, 0);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(label);

            row.setOnClickListener(v -> {
                dismiss();
                mHandler.postDelayed(() -> runItem(item), ACTION_DELAY_MS);
            });
            list.addView(row);
        }

        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(radius);
        list.setBackground(background);
        list.setPadding(padding, padding, padding, padding);

        FrameLayout root = new FrameLayout(context);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(list, lp);
        root.setOnClickListener(v -> dismiss());
        return root;
    }
}
