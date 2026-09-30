package com.nativepowermenu;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The AOSP-flavoured power menu.
 *
 * <p>The layout mirrors {@code packages/SystemUI/res/layout/global_actions_grid_lite.xml}: a fully
 * transparent dialog window with a rounded, slightly elevated panel in the centre, holding a grid
 * of round icon buttons with a label underneath each one. Dimensions, colours and typography are
 * read from the device's own {@code com.android.systemui} resources, so the result matches the
 * SystemUI build that is actually running.
 */
final class PowerMenuDialog {

    /** {@code WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL}, what SystemUIDialog uses. */
    private static final int TYPE_STATUS_BAR_SUB_PANEL = 2017;

    /** {@code Context.RECEIVER_NOT_EXPORTED} (API 33+, not present in older compile SDKs). */
    private static final int RECEIVER_NOT_EXPORTED = 0x4;

    private static final int FALLBACK_PANEL_COLOR = 0xFF191C18;
    private static final int FALLBACK_BUTTON_COLOR = 0xFF303030;
    private static final int FALLBACK_TEXT_COLOR = 0xFFF0F0F0;
    private static final int FALLBACK_EMERGENCY_ICON_COLOR = 0xFFFFB4AB;
    private static final int FALLBACK_EMERGENCY_BACKGROUND = 0xFF690005;

    private final Context mContext;
    private final PowerMenuActions mActions;

    private Dialog mDialog;
    private BroadcastReceiver mReceiver;

    PowerMenuDialog(Context context, PowerMenuActions actions) {
        mContext = context;
        mActions = actions;
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
        Dialog dialog = new Dialog(themed);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(buildContentView(themed, items));
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
                mContext.getResources(), ResourceLookup.PKG_SYSTEMUI,
                "Theme_SystemUI_Dialog_GlobalActions");
        if (theme != 0) {
            try {
                return new ContextThemeWrapper(mContext, theme);
            } catch (Throwable t) {
                ModuleLog.w("could not apply Theme_SystemUI_Dialog_GlobalActions: " + t);
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

    private View buildContentView(Context context, List<PowerMenuItem> items) {
        Metrics metrics = new Metrics(context);

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setShape(GradientDrawable.RECTANGLE);
        panelBackground.setColor(metrics.panelColor);
        panelBackground.setCornerRadius(metrics.cornerRadius);
        panel.setBackground(panelBackground);
        panel.setPadding(metrics.padding, metrics.padding, metrics.padding, metrics.padding);
        panel.setElevation(metrics.translate);

        int columns = Math.max(1, metrics.columns);
        for (int start = 0; start < items.size(); start += columns) {
            int end = Math.min(start + columns, items.size());
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            for (int i = start; i < end; i++) {
                row.addView(createItemView(context, metrics, items.get(i)));
            }
            // Keep the last row aligned with the rows above it when it is not full.
            for (int i = end; i < start + columns; i++) {
                View spacer = new View(context);
                row.addView(spacer, new LinearLayout.LayoutParams(metrics.buttonSize, 0));
            }
            panel.addView(row);
        }

        FrameLayout root = new FrameLayout(context);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(panel, lp);
        return root;
    }

    private View createItemView(Context context, Metrics metrics, PowerMenuItem item) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                metrics.buttonSize, LinearLayout.LayoutParams.WRAP_CONTENT));

        int foregroundColor = item.emergency ? metrics.emergencyIconColor : metrics.textColor;
        int backgroundColor = item.emergency
                ? metrics.emergencyBackground : metrics.buttonBackground;

        ImageView icon = new ImageView(context);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                metrics.buttonSize, metrics.buttonSize);
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        icon.setLayoutParams(iconParams);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(metrics.buttonPadding, metrics.buttonPadding,
                metrics.buttonPadding, metrics.buttonPadding);

        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(backgroundColor);
        icon.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), oval, null));

        Drawable drawable = item.iconResId != 0 ? context.getDrawable(item.iconResId) : null;
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(foregroundColor);
        }
        icon.setImageDrawable(drawable);
        container.addView(icon);

        TextView label = new TextView(context);
        label.setText(item.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTextColor(foregroundColor);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        label.setMarqueeRepeatLimit(-1);
        label.setSelected(true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = metrics.labelMargin;
        container.addView(label, labelParams);

        container.setOnClickListener(v -> {
            dismiss();
            v.postDelayed(item.onPress, 0);
        });
        if (item.onLongPress != null) {
            container.setOnLongClickListener(v -> {
                dismiss();
                v.postDelayed(item.onLongPress, 0);
                return true;
            });
        }
        return container;
    }

    /** Everything read from the running SystemUI's resources. */
    private static final class Metrics {

        final int padding;
        final int cornerRadius;
        final int buttonSize;
        final int buttonPadding;
        final int labelMargin;
        final int translate;
        final int columns;
        final int panelColor;
        final int buttonBackground;
        final int textColor;
        final int emergencyIconColor;
        final int emergencyBackground;

        Metrics(Context context) {
            Resources res = context.getResources();
            padding = ResourceLookup.dimen(res, ResourceLookup.PKG_SYSTEMUI,
                    "global_actions_lite_padding", dp(context, 24));
            cornerRadius = ResourceLookup.dimen(res, ResourceLookup.PKG_SYSTEMUI,
                    "global_actions_corner_radius", dp(context, 28));
            buttonSize = ResourceLookup.dimen(res, ResourceLookup.PKG_SYSTEMUI,
                    "global_actions_button_size", dp(context, 96));
            buttonPadding = ResourceLookup.dimen(res, ResourceLookup.PKG_SYSTEMUI,
                    "global_actions_button_padding", dp(context, 38));
            labelMargin = ResourceLookup.dimen(res, ResourceLookup.PKG_SYSTEMUI,
                    "global_actions_grid_container_bottom_margin", dp(context, 8));
            translate = ResourceLookup.dimen(res, ResourceLookup.PKG_SYSTEMUI,
                    "global_actions_translate", dp(context, 9));
            columns = ResourceLookup.integer(res, ResourceLookup.PKG_SYSTEMUI,
                    "power_menu_lite_max_columns", 2);
            panelColor = ResourceLookup.color(res, ResourceLookup.PKG_SYSTEMUI,
                    "global_actions_lite_background", FALLBACK_PANEL_COLOR);
            buttonBackground = ResourceLookup.color(res, ResourceLookup.PKG_SYSTEMUI,
                    "global_actions_lite_button_background", FALLBACK_BUTTON_COLOR);
            textColor = ResourceLookup.color(res, ResourceLookup.PKG_SYSTEMUI,
                    "global_actions_lite_text", FALLBACK_TEXT_COLOR);
            emergencyIconColor = ResourceLookup.color(res, ResourceLookup.PKG_SYSTEMUI,
                    "global_actions_lite_emergency_icon", FALLBACK_EMERGENCY_ICON_COLOR);
            emergencyBackground = ResourceLookup.color(res, ResourceLookup.PKG_SYSTEMUI,
                    "global_actions_lite_emergency_background", FALLBACK_EMERGENCY_BACKGROUND);
        }

        private static int dp(Context context, int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }
}
