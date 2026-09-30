package com.nativepowermenu;

import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The module's settings screen.
 *
 * <p>Built with framework widgets on {@code Theme.DeviceDefault.DayNight} so it follows the AOSP
 * design language (and the system light/dark mode) without bundling Material Components.
 *
 * <p>The list is a plain {@code LinearLayout} of fixed-height rows: one row per power-menu entry,
 * with a drag handle on the left and a switch on the right. While dragging, the grabbed row follows
 * the finger continuously (it floats above the list on its own background) and the rows it passes
 * slide out of the way; the order is only committed to the model as the row crosses slot
 * boundaries.
 */
public class SettingsActivity extends Activity {

    private static final long REORDER_ANIM_MS = 140L;
    private static final long DROP_ANIM_MS = 120L;

    private final List<String> mOrder = new ArrayList<>();
    private final Set<String> mDisabled = new LinkedHashSet<>();

    private boolean mEnabled = true;

    private ScrollView mScrollView;
    private LinearLayout mItemContainer;
    private Switch mMasterSwitch;
    private Button mSaveButton;
    private ViewGroup mDraggingRow;
    /** Where inside the grabbed row the finger landed, in pixels from its top. */
    private float mDragGrabOffset;
    private int mRowHeightPx;
    private int mDividerHeightPx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Theme.DeviceDefault.DayNight has an action bar and the public framework has no
        // DayNight + NoActionBar variant, so hide it rather than showing an empty bar.
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        mRowHeightPx = dp(64);
        mDividerHeightPx = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
        loadState();
        setContentView(buildContentView());
        rebuildRows();
        mMasterSwitch.setChecked(mEnabled);
        syncSavedConfig();
    }

    // ---------------------------------------------------------------- state

    private void loadState() {
        SharedPreferences prefs = getSharedPreferences(PowerMenuConfig.PREF_FILE, MODE_PRIVATE);
        PowerMenuConfig config = PowerMenuConfig.of(
                prefs.getBoolean(PowerMenuConfig.KEY_ENABLED, true),
                prefs.getString(PowerMenuConfig.KEY_ORDER, null),
                prefs.getString(PowerMenuConfig.KEY_DISABLED, null),
                getResources());
        mEnabled = config.enabled;
        mOrder.clear();
        mOrder.addAll(config.order);
        mDisabled.clear();
        mDisabled.addAll(config.disabled);
    }

    private void saveAndApply() {
        persistState();
        mSaveButton.setEnabled(false);
        pushConfig(true, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent result) {
                mSaveButton.setEnabled(true);
                boolean applied = getResultCode() == PowerMenuConfig.RESULT_APPLIED;
                Toast.makeText(SettingsActivity.this,
                        applied ? R.string.settings_applied : R.string.settings_not_applied,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Re-sends the stored settings when the screen is opened. This is what makes configuring the
     * module before it is enabled in LSPosed recoverable: the next time the app is opened the
     * values reach SystemUI again (without restarting it).
     */
    private void syncSavedConfig() {
        SharedPreferences prefs = getSharedPreferences(PowerMenuConfig.PREF_FILE, MODE_PRIVATE);
        if (!prefs.contains(PowerMenuConfig.KEY_DISABLED)) {
            // The user has never saved anything; leave SystemUI's own values alone.
            return;
        }
        pushConfig(false, null);
    }

    private void persistState() {
        List<String> disabled = new ArrayList<>(mDisabled);
        disabled.removeAll(PowerMenuItems.ALWAYS_ON);
        getSharedPreferences(PowerMenuConfig.PREF_FILE, MODE_PRIVATE).edit()
                .putBoolean(PowerMenuConfig.KEY_ENABLED, mMasterSwitch.isChecked())
                .putString(PowerMenuConfig.KEY_ORDER, PowerMenuConfig.join(mOrder))
                .putString(PowerMenuConfig.KEY_DISABLED, PowerMenuConfig.join(disabled))
                .apply();
    }

    private void pushConfig(boolean restart, BroadcastReceiver resultReceiver) {
        List<String> disabled = new ArrayList<>(mDisabled);
        disabled.removeAll(PowerMenuItems.ALWAYS_ON);

        Intent intent = new Intent(PowerMenuConfig.ACTION_APPLY);
        intent.setPackage(XposedEntry.TARGET_PACKAGE);
        intent.putExtra(PowerMenuConfig.EXTRA_ENABLED, mMasterSwitch.isChecked());
        intent.putExtra(PowerMenuConfig.EXTRA_ORDER, PowerMenuConfig.join(mOrder));
        intent.putExtra(PowerMenuConfig.EXTRA_DISABLED, PowerMenuConfig.join(disabled));
        intent.putExtra(PowerMenuConfig.EXTRA_RESTART, restart);

        // Ordered broadcast: SystemUI answers with RESULT_APPLIED, which is how we can tell the
        // difference between "applied" and "the module is not running". No receiver permission here
        // - SystemUI's own registration already requires the *sender* to hold
        // com.nativepowermenu.permission.APPLY, which only this module's signature does.
        sendOrderedBroadcast(intent, null, resultReceiver, null, RESULT_CANCELED, null, null);
    }

    // ---------------------------------------------------------------- view construction

    private View buildContentView() {
        mScrollView = new ScrollView(this);
        mScrollView.setFillViewport(true);
        // A dragged row floats above the list and may overhang the card, so nothing on the way down
        // may clip its children.
        mScrollView.setClipChildren(false);
        mScrollView.setClipToPadding(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(20), dp(20), dp(20), dp(32));
        mScrollView.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.settings_subtitle);
        subtitle.setTextSize(14);
        subtitle.setTextColor(themeColorList(android.R.attr.textColorSecondary));
        content.addView(subtitle, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(4), 0, 0, dp(20)));

        content.addView(buildMasterCard(), margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(24)));

        TextView header = new TextView(this);
        header.setText(R.string.settings_items_header);
        header.setTextSize(13);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setAllCaps(true);
        header.setTextColor(themeColorList(android.R.attr.colorAccent));
        header.setLetterSpacing(0.06f);
        content.addView(header, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(4), 0, 0, dp(8)));

        mItemContainer = new LinearLayout(this);
        mItemContainer.setOrientation(LinearLayout.VERTICAL);
        mItemContainer.setClipChildren(false);
        mItemContainer.setClipToPadding(false);
        LinearLayout itemsCard = new LinearLayout(this);
        itemsCard.setOrientation(LinearLayout.VERTICAL);
        itemsCard.setClipChildren(false);
        itemsCard.setClipToPadding(false);
        itemsCard.setBackground(cardBackground());
        itemsCard.addView(mItemContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(itemsCard, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(8)));

        TextView hint = new TextView(this);
        hint.setText(R.string.settings_hint);
        hint.setTextSize(12);
        hint.setTextColor(themeColorList(android.R.attr.textColorSecondary));
        content.addView(hint, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(4), 0, 0, dp(24)));

        mSaveButton = new Button(this);
        mSaveButton.setText(R.string.settings_save);
        mSaveButton.setAllCaps(false);
        mSaveButton.setTextSize(16);
        mSaveButton.setOnClickListener(v -> saveAndApply());
        stylePillButton(mSaveButton);
        content.addView(mSaveButton, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));

        return mScrollView;
    }

    private View buildMasterCard() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(cardBackground());
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setText(R.string.settings_enable);
        label.setTextSize(16);
        label.setTextColor(themeColorList(android.R.attr.textColorPrimary));
        texts.addView(label);

        TextView description = new TextView(this);
        description.setText(R.string.settings_enable_desc);
        description.setTextSize(12);
        description.setTextColor(themeColorList(android.R.attr.textColorSecondary));
        texts.addView(description);

        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mMasterSwitch = new Switch(this);
        row.addView(mMasterSwitch);
        row.setOnClickListener(v -> mMasterSwitch.toggle());
        return row;
    }

    private void rebuildRows() {
        mItemContainer.removeAllViews();
        for (String key : mOrder) {
            mItemContainer.addView(createItemRow(key));
        }
        updateDividers();
    }

    private View createItemRow(String key) {
        FrameLayout holder = new FrameLayout(this);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(6), dp(16), dp(6));

        ImageView handle = new ImageView(this);
        handle.setImageResource(R.drawable.ic_drag_handle);
        handle.setImageTintList(themeColorList(android.R.attr.textColorSecondary));
        handle.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.addView(handle, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageView icon = new ImageView(this);
        Drawable drawable = PowerMenuItems.icon(this, key);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(themeColor(android.R.attr.textColorPrimary));
            icon.setImageDrawable(drawable);
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(8), 0);

        TextView label = new TextView(this);
        label.setText(PowerMenuItems.label(this, key));
        label.setTextSize(16);
        label.setTextColor(themeColorList(android.R.attr.textColorPrimary));
        texts.addView(label);

        TextView description = new TextView(this);
        description.setText(PowerMenuItems.description(this, key));
        description.setTextSize(12);
        description.setSingleLine(true);
        description.setTextColor(themeColorList(android.R.attr.textColorSecondary));
        texts.addView(description);

        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean locked = PowerMenuItems.ALWAYS_ON.contains(key);
        Switch toggle = new Switch(this);
        toggle.setChecked(locked || !mDisabled.contains(key));
        toggle.setEnabled(!locked);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                mDisabled.remove(key);
            } else {
                mDisabled.add(key);
            }
        });
        row.addView(toggle);

        if (!locked) {
            row.setOnClickListener(v -> toggle.toggle());
        }

        holder.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, mRowHeightPx));

        // Always added; updateDividers() hides the one on the last row after every reorder.
        View divider = new View(this);
        divider.setBackgroundColor(dividerColor());
        FrameLayout.LayoutParams dividerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, mDividerHeightPx);
        dividerParams.gravity = Gravity.BOTTOM;
        dividerParams.leftMargin = dp(16);
        holder.addView(divider, dividerParams);

        attachDragHandle(handle, holder);
        return holder;
    }

    /** The last row never shows a separator. */
    private void updateDividers() {
        int count = mItemContainer.getChildCount();
        for (int i = 0; i < count; i++) {
            View holder = mItemContainer.getChildAt(i);
            if (holder instanceof ViewGroup && ((ViewGroup) holder).getChildCount() > 1) {
                ((ViewGroup) holder).getChildAt(1)
                        .setVisibility(i == count - 1 ? View.GONE : View.VISIBLE);
            }
        }
    }

    // ---------------------------------------------------------------- drag to reorder

    /**
     * Continuous (floating) drag.
     *
     * <p>Every row is exactly {@link #mRowHeightPx} tall and the container has no padding, so slot
     * {@code i} always starts at {@code i * rowHeight}. That makes the whole gesture exact
     * arithmetic: the dragged row's {@code translationY} is recomputed from the finger position on
     * every move, which is what keeps it glued to the finger, and its slot is derived by rounding
     * the same value - so it moves any number of slots in one gesture.
     */
    private void attachDragHandle(View handle, ViewGroup row) {
        handle.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startDrag(row, event.getRawY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateDrag(event.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    endDrag();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void startDrag(ViewGroup row, float rawY) {
        mDraggingRow = row;
        // Stop the ScrollView (and everything above it) from stealing the gesture.
        row.requestDisallowInterceptTouchEvent(true);
        row.animate().cancel();
        row.setTranslationY(0f);
        row.setElevation(dp(6));
        row.setBackground(floatingRowBackground());

        int index = mItemContainer.indexOfChild(row);
        mDragGrabOffset = rawY - (containerTopOnScreen() + index * (float) mRowHeightPx);
        if (mDragGrabOffset < 0) {
            mDragGrabOffset = 0;
        }
        if (mDragGrabOffset > mRowHeightPx) {
            mDragGrabOffset = mRowHeightPx;
        }
    }

    private void updateDrag(float rawY) {
        View dragging = mDraggingRow;
        if (dragging == null) {
            return;
        }
        int count = mItemContainer.getChildCount();
        if (count == 0) {
            return;
        }
        float desiredTop = rawY - containerTopOnScreen() - mDragGrabOffset;

        int current = mItemContainer.indexOfChild(dragging);
        int target = Math.round(desiredTop / mRowHeightPx);
        if (target < 0) {
            target = 0;
        }
        if (target > count - 1) {
            target = count - 1;
        }
        if (target != current) {
            moveRow(current, target);
            current = target;
        }
        // Recompute from the finger every time: after a reorder the row's layout position changed,
        // and this keeps it visually under the finger.
        dragging.setTranslationY(desiredTop - current * (float) mRowHeightPx);
    }

    /** Moves the model entry and the view, and lets the rows in between slide to their new slot. */
    private void moveRow(int from, int to) {
        View dragging = mDraggingRow;
        int count = mItemContainer.getChildCount();
        View[] before = new View[count];
        for (int i = 0; i < count; i++) {
            before[i] = mItemContainer.getChildAt(i);
        }

        mOrder.add(to, mOrder.remove(from));
        mItemContainer.removeViewAt(from);
        mItemContainer.addView(dragging, to);
        updateDividers();

        for (int oldIndex = 0; oldIndex < count; oldIndex++) {
            View child = before[oldIndex];
            if (child == dragging) {
                continue;
            }
            int newIndex = mItemContainer.indexOfChild(child);
            if (newIndex == oldIndex) {
                continue;
            }
            // Start where it used to be and slide into place.
            child.animate().cancel();
            child.setTranslationY((oldIndex - newIndex) * (float) mRowHeightPx);
            child.animate().translationY(0f).setDuration(REORDER_ANIM_MS).start();
        }
    }

    private void endDrag() {
        final ViewGroup dragging = mDraggingRow;
        mDraggingRow = null;
        if (dragging == null) {
            return;
        }
        dragging.requestDisallowInterceptTouchEvent(false);
        dragging.animate()
                .translationY(0f)
                .setDuration(DROP_ANIM_MS)
                .withEndAction(() -> {
                    dragging.setBackground(null);
                    dragging.setElevation(0f);
                })
                .start();
    }

    private float containerTopOnScreen() {
        int[] location = new int[2];
        mItemContainer.getLocationOnScreen(location);
        return location[1];
    }

    // ---------------------------------------------------------------- theming helpers

    private ColorStateList themeColorList(int attribute) {
        TypedArray array = obtainStyledAttributes(new int[]{attribute});
        try {
            ColorStateList list = array.getColorStateList(0);
            if (list != null) {
                return list;
            }
        } catch (Throwable ignored) {
            // Fall through to the default below.
        } finally {
            array.recycle();
        }
        return ColorStateList.valueOf(Color.GRAY);
    }

    private int themeColor(int attribute) {
        return themeColorList(attribute).getDefaultColor();
    }

    private Drawable cardBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(themeColor(android.R.attr.colorBackgroundFloating));
        background.setCornerRadius(dp(18));
        int stroke = themeColor(android.R.attr.textColorSecondary);
        background.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density / 2f)),
                (stroke & 0x00FFFFFF) | 0x33000000);
        return background;
    }

    /** Slightly accent-tinted surface so the row reads as lifted while it floats over the list. */
    private Drawable floatingRowBackground() {
        int surface = themeColor(android.R.attr.colorBackgroundFloating);
        int accent = themeColor(android.R.attr.colorAccent);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(blend(surface, accent, 0.12f));
        background.setCornerRadius(dp(14));
        int stroke = themeColor(android.R.attr.textColorSecondary);
        background.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density / 2f)),
                (stroke & 0x00FFFFFF) | 0x33000000);
        return background;
    }

    private int dividerColor() {
        int base = themeColor(android.R.attr.textColorSecondary);
        return (base & 0x00FFFFFF) | 0x1F000000;
    }

    /** Capsule-shaped primary action. */
    private void stylePillButton(Button button) {
        int accent = themeColor(android.R.attr.colorAccent);
        int height = dp(52);

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(accent);
        // Radius >= half the height makes it a true capsule.
        shape.setCornerRadius(height / 2f);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), shape, null));

        // Pick the label colour from the accent's luminance so it stays readable in both themes.
        double luminance = (0.299 * Color.red(accent)
                + 0.587 * Color.green(accent)
                + 0.114 * Color.blue(accent)) / 255d;
        button.setTextColor(luminance > 0.6 ? Color.BLACK : Color.WHITE);
        button.setPadding(dp(24), 0, dp(24), 0);
        button.setMinHeight(height);
        button.setMinimumHeight(height);
    }

    private static int blend(int base, int overlay, float ratio) {
        int r = Math.round(Color.red(base) * (1 - ratio) + Color.red(overlay) * ratio);
        int g = Math.round(Color.green(base) * (1 - ratio) + Color.green(overlay) * ratio);
        int b = Math.round(Color.blue(base) * (1 - ratio) + Color.blue(overlay) * ratio);
        return Color.argb(255, r, g, b);
    }

    private LinearLayout.LayoutParams margins(int width, int height,
            int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
