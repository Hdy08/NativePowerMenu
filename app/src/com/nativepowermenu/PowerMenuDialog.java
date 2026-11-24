package com.nativepowermenu;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
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
    /** Hidden framework value used by AOSP SystemUIDialog for privileged SystemUI dialogs. */
    private static final int TYPE_STATUS_BAR_SUB_PANEL = 2017;

    /** {@code Context.RECEIVER_NOT_EXPORTED} (API 33+, not present in older compile SDKs). */
    private static final int RECEIVER_NOT_EXPORTED = 0x4;

    private static final ColorStateList AOSP_LITE_BACKGROUND = ColorStateList.valueOf(0xff191c18);
    private static final ColorStateList AOSP_LITE_BUTTON = ColorStateList.valueOf(0xff303030);
    private static final ColorStateList AOSP_LITE_TEXT = ColorStateList.valueOf(0xfff0f0f0);
    private static final ColorStateList AOSP_LITE_EMERGENCY_BACKGROUND =
            ColorStateList.valueOf(0xfff85d4d);
    private static final ColorStateList AOSP_LITE_EMERGENCY_ICON =
            ColorStateList.valueOf(0xff202124);

    private final Context mContext;
    private final PowerMenuActions mActions;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private MenuDialog mDialog;
    private PowerMenuAnimation mAnimation;
    private BroadcastReceiver mReceiver;
    private boolean mDismissing;

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
    boolean toggle(Object manager, Object keyguardStateController, boolean keyguardShowing,
            boolean deviceProvisioned) {
        mActions.setManager(manager);
        mActions.setKeyguardStateController(keyguardStateController);

        if (isShowing()) {
            mActions.onShownCompat();
            dismiss();
            return true;
        }
        return show(keyguardShowing, deviceProvisioned);
    }

    void dismiss() {
        MenuDialog dialog = mDialog;
        if (dialog == null || !dialog.isShowing() || mDismissing) {
            return;
        }
        mDismissing = true;
        PowerMenuAnimation animation = mAnimation;
        if (animation == null || !animation.startDismiss(() -> finishDismiss(dialog))) {
            finishDismiss(dialog);
        }
    }

    private void finishDismiss(MenuDialog dialog) {
        if (mDialog == dialog && dialog.isShowing()) {
            dialog.dismissImmediately();
        }
    }

    private void dismissThen(Runnable action) {
        long delay = mAnimation == null ? 0L : mAnimation.actionDelayMillis();
        dismiss();
        mHandler.postDelayed(action, delay);
    }

    private final class MenuDialog extends Dialog {
        private boolean mDismissImmediately;

        MenuDialog(Context context) {
            super(context);
        }

        @Override
        public void dismiss() {
            if (mDismissImmediately) {
                super.dismiss();
            } else {
                PowerMenuDialog.this.dismiss();
            }
        }

        @Override
        public void cancel() {
            if (mDismissImmediately) {
                super.cancel();
            } else {
                PowerMenuDialog.this.dismiss();
            }
        }

        void dismissImmediately() {
            mDismissImmediately = true;
            super.dismiss();
        }
    }

    // ---------------------------------------------------------------- show / hide

    private boolean show(boolean keyguardShowing, boolean deviceProvisioned) {
        List<PowerMenuItem> items = mActions.createItems(keyguardShowing, deviceProvisioned);
        if (items.isEmpty()) {
            ModuleLog.w("no power menu items available, not showing anything");
            return false;
        }

        Context themed = themedContext();
        if (themed == null) {
            ModuleLog.w("AOSP GlobalActionsLite theme is unavailable");
            return false;
        }
        View content = buildContentView(themed, items);
        if (content == null) {
            ModuleLog.w("could not build the power menu view");
            return false;
        }

        MenuDialog dialog = new MenuDialog(themed);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);

        Window window = dialog.getWindow();
        if (window == null) {
            ModuleLog.w("AOSP power menu window is unavailable");
            return false;
        }
        configureWindow(window);
        Resources resources = themed.getResources();
        View container = content.findViewById(
                ResourceLookup.id(resources, PKG, "global_actions_container"));
        View globalActionsLayout = content.findViewById(
                ResourceLookup.id(resources, PKG, "global_actions_view"));
        PowerMenuAnimation animation = PowerMenuAnimation.prepare(window, globalActionsLayout);
        mAnimation = animation;
        dialog.setOnDismissListener(d -> {
            if (animation != null) {
                animation.cancel();
            }
            unregisterReceiver();
            if (mDialog == dialog) {
                mDialog = null;
                mAnimation = null;
            }
            mDismissing = false;
            mActions.onHiddenCompat();
        });

        registerReceiver();
        try {
            dialog.show();
        } catch (RuntimeException | Error e) {
            if (animation != null) {
                animation.cancel();
            }
            mAnimation = null;
            unregisterReceiver();
            throw e;
        }
        mDialog = dialog;
        if (animation != null) {
            animation.startEntrance();
        }
        mActions.onShownCompat();
        return true;
    }

    private Context themedContext() {
        int theme = ResourceLookup.styleId(
                mContext.getResources(), PKG, "Theme.SystemUI.Dialog.GlobalActionsLite");
        if (theme == 0) {
            return null;
        }
        try {
            return new ContextThemeWrapper(mContext, theme);
        } catch (Throwable t) {
            ModuleLog.w("could not apply AOSP Theme.SystemUI.Dialog.GlobalActionsLite: " + t);
            return null;
        }
    }

    private void configureWindow(Window window) {
        // A SystemUI application context has no Activity token for TYPE_APPLICATION dialogs.
        // Match AOSP SystemUIDialog and GlobalActionsDialogLite.
        window.setType(TYPE_STATUS_BAR_SUB_PANEL);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        // The panel owns the entrance; don't also animate the full-screen window.
        window.setWindowAnimations(0);
        window.setGravity(Gravity.CENTER);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.setFitInsetsTypes(0);
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
            ModuleLog.w("SystemUI has no AOSP global_actions_grid_lite layout");
            return null;
        }

        View root;
        try {
            root = inflater.inflate(rootLayout, null, false);
        } catch (Throwable t) {
            ModuleLog.e("could not inflate global_actions_grid_lite", t);
            return null;
        }

        ViewGroup listView = root.findViewById(android.R.id.list);
        View flow = root.findViewById(ResourceLookup.id(res, PKG, "list_flow"));
        int itemLayout = ResourceLookup.layoutId(res, PKG, "global_actions_grid_item_lite");
        if (listView == null || flow == null || itemLayout == 0) {
            ModuleLog.w("AOSP power menu layout is incomplete");
            return null;
        }
        listView.setBackgroundTintList(AOSP_LITE_BACKGROUND);

        for (PowerMenuItem item : items) {
            View itemView = inflater.inflate(itemLayout, listView, false);
            // ConstraintLayout's Flow references children by id.
            itemView.setId(View.generateViewId());
            bindItem(itemView, item);
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
        View globalActionsLayout = root.findViewById(
                ResourceLookup.id(res, PKG, "global_actions_view"));
        if (container == null || globalActionsLayout == null) {
            ModuleLog.w("AOSP GlobalActionsLite container is incomplete");
            return null;
        }
        // The window fills the screen, so "tap outside" means "tap on this container".
        container.setOnClickListener(v -> dismiss());
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

    private void bindItem(View itemView, PowerMenuItem item) {
        ImageView icon = itemView.findViewById(android.R.id.icon);
        TextView message = itemView.findViewById(android.R.id.message);
        if (message != null) {
            message.setText(item.label);
            message.setTextColor(AOSP_LITE_TEXT.getDefaultColor());
            // Required for the marquee to animate, exactly like AOSP does it.
            message.setSelected(true);
        }
        if (icon != null) {
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Drawable drawable = item.icon;
            if (drawable != null) {
                drawable = drawable.mutate();
                icon.setImageDrawable(drawable);
            }
            icon.setImageTintList(AOSP_LITE_TEXT);
            icon.setBackgroundTintList(AOSP_LITE_BUTTON);
            if (item.emergency) {
                icon.setImageTintList(AOSP_LITE_EMERGENCY_ICON);
                icon.setBackgroundTintList(AOSP_LITE_EMERGENCY_BACKGROUND);
                itemView.setBackgroundTintList(AOSP_LITE_EMERGENCY_BACKGROUND);
            }
        }

        // Run the action from a Handler because dismissal detaches the item view.
        itemView.setOnClickListener(v -> {
            dismissThen(item.onPress);
        });
        if (item.onLongPress != null) {
            itemView.setOnLongClickListener(v -> {
                dismissThen(item.onLongPress);
                return true;
            });
        }
    }

}
