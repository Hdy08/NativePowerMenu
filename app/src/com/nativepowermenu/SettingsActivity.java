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
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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
 * with a drag handle on the left and a switch on the right.
 *
 * <p>Dragging is split in two. While the finger is down the grabbed row is the only thing that
 * follows it: it floats above the list on its own background and its {@code translationY} is
 * recomputed from the raw finger position on every event, so it tracks the finger exactly, travels
 * any distance in either direction and is clamped only to the ends of the list. The other rows do
 * move, but only into the neighbouring slot to leave the target slot empty - they are drawn with an
 * offset and never reordered, so the dragged row keeps its index for the whole gesture and the
 * arithmetic never has to be redone. The order is committed once, when the finger comes up: the row
 * glides into the empty slot and everything else keeps the position it had already slid to.
 */
public class SettingsActivity extends Activity {

    /** How long the rows around the dragged one take to slide out of the way. */
    private static final long GAP_ANIM_MS = 140L;
    private static final long DROP_ANIM_MS = 160L;

    private final List<String> mOrder = new ArrayList<>();
    private final Set<String> mDisabled = new LinkedHashSet<>();

    private boolean mEnabled = true;
    /** Power long-press timeout in milliseconds; {@code 0} keeps the framework's own value. */
    private int mLongPressMs;

    private ScrollView mScrollView;
    private LinearLayout mContent;
    private LinearLayout mItemContainer;
    private Switch mMasterSwitch;
    private SeekBar mLongPressSeek;
    private TextView mLongPressValue;
    private Button mSaveButton;

    /** The row currently under the finger, or {@code null} when nothing is being dragged. */
    private ViewGroup mDraggingRow;
    /** Layout index the dragged row started at. It keeps that index for the whole gesture. */
    private int mDragFromIndex = -1;
    /** Finger position when the gesture started, in screen pixels. */
    private float mDragStartRawY;
    /** Slot the dragged row will land in. Only committed to the model when the finger is lifted. */
    private int mDragTargetIndex = -1;
    /** How far the dragged row may travel before it would leave the list, in pixels. */
    private float mDragMinTranslation;
    private float mDragMaxTranslation;

    private int mRowHeightPx;
    private int mDividerHeightPx;
    /** Whether the system bar insets actually reached {@link #applySystemBarInsets()}. */
    private boolean mInsetsApplied;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Theme.DeviceDefault.DayNight has an action bar and the public framework has no
        // DayNight + NoActionBar variant, so hide it rather than showing an empty bar.
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        mRowHeightPx = dp(56);
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
                prefs.getInt(PowerMenuConfig.KEY_LONG_PRESS_MS, 0),
                getResources());
        mEnabled = config.enabled;
        mOrder.clear();
        mOrder.addAll(config.order);
        mDisabled.clear();
        mDisabled.addAll(config.disabled);
        mLongPressMs = config.longPressMs;
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
                .putInt(PowerMenuConfig.KEY_LONG_PRESS_MS, mLongPressMs)
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
        intent.putExtra(PowerMenuConfig.EXTRA_LONG_PRESS_MS, mLongPressMs);
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

        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setClipChildren(false);
        mContent.setClipToPadding(false);
        mContent.setPadding(dp(20), dp(20), dp(20), dp(32));
        mScrollView.addView(mContent, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mContent.addView(buildMasterCard(), margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(12)));

        mContent.addView(buildLongPressCard(), margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(24)));

        TextView header = new TextView(this);
        header.setText(R.string.settings_items_header);
        header.setTextSize(13);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setAllCaps(true);
        header.setTextColor(themeColorList(android.R.attr.colorAccent));
        header.setLetterSpacing(0.06f);
        mContent.addView(header, margins(ViewGroup.LayoutParams.MATCH_PARENT,
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
        mContent.addView(itemsCard, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(24)));

        mSaveButton = new Button(this);
        mSaveButton.setText(R.string.settings_save);
        mSaveButton.setAllCaps(false);
        mSaveButton.setTextSize(16);
        mSaveButton.setOnClickListener(v -> saveAndApply());
        stylePillButton(mSaveButton);
        mContent.addView(mSaveButton, margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));

        applySystemBarInsets();
        return mScrollView;
    }

    /**
     * Keeps the content out of the status bar and the navigation bar.
     *
     * <p>The module targets SDK 36, so Android forces it edge-to-edge: the window covers the whole
     * display and the system bars are drawn on top of it. Without this padding the first row of the
     * list would sit underneath the status bar. Insets arrive with the first traversal, before
     * anything is drawn, so the layout is already correct on the first frame.
     */
    private void applySystemBarInsets() {
        final int left = mContent.getPaddingLeft();
        final int top = mContent.getPaddingTop();
        final int right = mContent.getPaddingRight();
        final int bottom = mContent.getPaddingBottom();
        mScrollView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            if (bars.left != 0 || bars.top != 0 || bars.right != 0 || bars.bottom != 0) {
                mInsetsApplied = true;
                mContent.setPadding(left + bars.left, top + bars.top, right + bars.right,
                        bottom + bars.bottom);
            }
            return windowInsets;
        });
        // Belt and braces: should an OEM build consume the insets inside the decor, they never
        // reach the listener above. The scroll view sitting at y = 0 means the window really is
        // edge-to-edge, and in that case the framework's own status bar height is used instead.
        mScrollView.post(() -> {
            if (mInsetsApplied) {
                return;
            }
            int[] location = new int[2];
            mScrollView.getLocationOnScreen(location);
            if (location[1] > 0) {
                return;
            }
            int statusBar = statusBarHeight();
            if (statusBar > 0) {
                mContent.setPadding(left, top + statusBar, right, bottom);
            }
        });
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
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

    /**
     * How long the power key has to be held before the menu appears.
     *
     * <p>Discrete steps rather than a slider over every millisecond: the value is handed to
     * {@code PhoneWindowManager}'s long-press rule, and steps keep the label readable. "Default"
     * leaves the framework's own value ({@code config_longPressOnPowerDurationMs}) alone.
     */
    private View buildLongPressCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground());
        card.setPadding(dp(16), dp(14), dp(16), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.settings_long_press);
        title.setTextSize(16);
        title.setTextColor(themeColorList(android.R.attr.textColorPrimary));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mLongPressValue = new TextView(this);
        mLongPressValue.setTextSize(14);
        mLongPressValue.setTextColor(themeColorList(android.R.attr.colorAccent));
        header.addView(mLongPressValue);
        card.addView(header);

        mLongPressSeek = new SeekBar(this);
        mLongPressSeek.setMax(LongPress.PRESETS.length - 1);
        mLongPressSeek.setProgress(LongPress.presetIndex(mLongPressMs));
        int accent = themeColor(android.R.attr.colorAccent);
        mLongPressSeek.setProgressTintList(ColorStateList.valueOf(accent));
        mLongPressSeek.setThumbTintList(ColorStateList.valueOf(accent));
        mLongPressSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mLongPressMs = LongPress.PRESETS[progress];
                updateLongPressLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        card.addView(mLongPressSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        updateLongPressLabel();
        return card;
    }

    private void updateLongPressLabel() {
        int ms = mLongPressMs;
        if (ms > 0) {
            mLongPressValue.setText(getString(R.string.settings_long_press_ms, ms));
            return;
        }
        int deviceDefault = deviceLongPressMs();
        mLongPressValue.setText(deviceDefault > 0
                ? getString(R.string.settings_long_press_default_ms, deviceDefault)
                : getString(R.string.settings_long_press_default));
    }

    /** What the framework would use without the module: {@code config_longPressOnPowerDurationMs}. */
    private int deviceLongPressMs() {
        return ResourceLookup.integer(getResources(), ResourceLookup.PKG_ANDROID,
                "config_longPressOnPowerDurationMs", 0);
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
        row.setPadding(dp(6), 0, dp(16), 0);

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

        TextView label = new TextView(this);
        label.setText(PowerMenuItems.label(this, key));
        label.setTextSize(16);
        label.setSingleLine(true);
        label.setPadding(dp(14), 0, dp(8), 0);
        label.setTextColor(themeColorList(android.R.attr.textColorPrimary));
        row.addView(label, new LinearLayout.LayoutParams(
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
     * Drags from the handle. The grabbed row follows the finger for the whole gesture while the
     * other rows slide out of the way to leave a free slot under it; the order itself is only
     * committed when the finger is lifted, so dropping simply drops the row into that free slot.
     */
    private void attachDragHandle(View handle, ViewGroup row) {
        handle.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    return startDrag(row, event.getRawY());
                case MotionEvent.ACTION_MOVE:
                    updateDrag(event.getRawY());
                    return mDraggingRow != null;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    endDrag();
                    return true;
                default:
                    return false;
            }
        });
    }

    private boolean startDrag(ViewGroup row, float rawY) {
        // A second finger on another handle must not take over: the first gesture owns the drag,
        // and its row would be left floating for good if the reference were overwritten.
        if (mDraggingRow != null) {
            return false;
        }
        settlePendingDrop();
        int count = mItemContainer.getChildCount();
        int index = mItemContainer.indexOfChild(row);
        if (count < 2 || index < 0 || row.getHeight() == 0) {
            return false;
        }

        // Slot geometry comes from the live layout rather than from a constant, so a row that ends
        // up a pixel taller or shorter than expected can never desynchronise the drag.
        int listTop = mItemContainer.getChildAt(0).getTop();
        int listBottom = mItemContainer.getChildAt(count - 1).getBottom();
        mDragMinTranslation = listTop - row.getTop();
        mDragMaxTranslation = Math.max(mDragMinTranslation,
                listBottom - row.getHeight() - row.getTop());

        mDraggingRow = row;
        mDragFromIndex = index;
        mDragStartRawY = rawY;
        mDragTargetIndex = index;
        row.requestDisallowInterceptTouchEvent(true);
        // A cancelled drop may have left this very row animating; it has to stop now, or the
        // animator would overwrite the translation the finger is about to drive.
        row.animate().cancel();
        row.setTranslationY(0f);
        row.setElevation(dp(8));
        row.setBackground(floatingRowBackground());
        // The lifted row is a floating card of its own; its separator would ride along with it.
        if (row.getChildCount() > 1) {
            row.getChildAt(1).setVisibility(View.GONE);
        }
        return true;
    }

    private void updateDrag(float rawY) {
        ViewGroup dragging = mDraggingRow;
        if (dragging == null) {
            return;
        }
        float translation = rawY - mDragStartRawY;
        if (translation < mDragMinTranslation) {
            translation = mDragMinTranslation;
        } else if (translation > mDragMaxTranslation) {
            translation = mDragMaxTranslation;
        }
        dragging.setTranslationY(translation);

        // Nearest slot centre wins. Rows are compared at their laid-out positions, which the gap
        // underneath them never changes, so the target cannot end up chasing its own animation.
        float center = dragging.getTop() + translation + dragging.getHeight() / 2f;
        int count = mItemContainer.getChildCount();
        int best = mDragTargetIndex;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            View child = mItemContainer.getChildAt(i);
            float distance = Math.abs(child.getTop() + child.getHeight() / 2f - center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        int target = Math.max(0, Math.min(count - 1, best));
        if (target != mDragTargetIndex) {
            mDragTargetIndex = target;
            openGap(mDragFromIndex, target);
        }
    }

    /**
     * Moves every other row so that the slot the dragged row would land in is left empty.
     *
     * <p>Nothing is reordered here: the dragged row keeps its index for the whole gesture, and the
     * rows around it are only <em>drawn</em> shifted by a {@code translationY}. Visual slot
     * {@code s} is always {@code getChildAt(s).getTop()} because the layout never changes while
     * dragging, so a row's offset is simply "the top of the slot it should occupy minus its own".
     */
    private void openGap(int from, int target) {
        int count = mItemContainer.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = mItemContainer.getChildAt(i);
            if (child == mDraggingRow) {
                continue;
            }
            // Where this row sits in the list as it would be without the dragged row...
            int withoutDragging = i < from ? i : i - 1;
            // ...and the slot it has to occupy once the dragged row is put back at `target`.
            int slot = withoutDragging < target ? withoutDragging : withoutDragging + 1;
            float wanted = mItemContainer.getChildAt(slot).getTop() - child.getTop();
            if (Math.abs(child.getTranslationY() - wanted) < 0.5f) {
                continue;
            }
            child.animate().cancel();
            child.animate().translationY(wanted).setDuration(GAP_ANIM_MS).start();
        }
    }

    private void endDrag() {
        final ViewGroup dragging = mDraggingRow;
        mDraggingRow = null;
        if (dragging == null) {
            return;
        }
        dragging.requestDisallowInterceptTouchEvent(false);

        final int from = mItemContainer.indexOfChild(dragging);
        int to = mDragTargetIndex;
        if (from < 0) {
            dragging.setTranslationY(0f);
            dragging.setBackground(null);
            dragging.setElevation(0f);
            return;
        }
        to = Math.max(0, Math.min(mItemContainer.getChildCount() - 1, to));
        final int target = to;
        // Where the row has to travel to sit exactly on the slot that was left open for it, or
        // straight back into its own slot when it is dropped where it started.
        float destination = target == from
                ? 0f
                : mItemContainer.getChildAt(target).getTop() - dragging.getTop();

        dragging.animate()
                .translationY(destination)
                .setDuration(DROP_ANIM_MS)
                .withEndAction(() -> placeRow(dragging, from, target))
                .start();
    }

    /** Runs when the drop animation ends (or is cancelled), at most once per gesture. */
    private void placeRow(ViewGroup dragging, int from, int to) {
        if (from != to && mItemContainer.indexOfChild(dragging) == from) {
            mOrder.add(to, mOrder.remove(from));
            mItemContainer.removeViewAt(from);
            mItemContainer.addView(dragging, to);
        }

        // Every row is now laid out exactly where it was already being drawn - the dragged one in
        // the slot that was opened for it, the others where they had slid to - so clearing the
        // offsets in the same pass as the reorder changes nothing on screen.
        int count = mItemContainer.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = mItemContainer.getChildAt(i);
            child.animate().cancel();
            child.setTranslationY(0f);
        }
        dragging.setBackground(null);
        dragging.setElevation(0f);
        // Brings back the separator that was hidden when the row was picked up.
        updateDividers();
    }

    /**
     * A gesture can start while the previous row is still gliding into place. Cancelling the
     * animation runs its end action, which commits that pending reorder, so the indices read
     * afterwards are always the settled ones.
     */
    private void settlePendingDrop() {
        int count = mItemContainer.getChildCount();
        View[] children = new View[count];
        for (int i = 0; i < count; i++) {
            children[i] = mItemContainer.getChildAt(i);
        }
        for (View child : children) {
            child.animate().cancel();
            child.setTranslationY(0f);
        }
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
