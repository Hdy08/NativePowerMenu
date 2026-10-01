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
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageButton;
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

    /** The content padding every card uses, so their contents line up. */
    private static final int CARD_PADDING_DP = 14;
    /** Fixed metrics for the long-press card; wrap_content made it grow to most of a screen. */
    private static final int HEADER_HEIGHT_DP = 36;
    private static final int INPUT_HEIGHT_DP = 36;
    private static final int SEEK_HEIGHT_DP = 40;
    /**
     * The header centres a ~22dp line in 36dp, and the slider's thumb does not fill its box either,
     * so both carry leading of their own. Subtracting it keeps the visible gap above the title and
     * below the slider the same as in the cards that hold plain text.
     */
    private static final int HEADER_SLACK_DP = 7;
    private static final int SEEK_SLACK_DP = 10;
    /** The slider is inset a little at both ends, so it reads as a control rather than a rule. */
    private static final int SEEK_INSET_DP = 10;

    /** How long the rows around the dragged one take to slide out of the way. */
    private static final long GAP_ANIM_MS = 140L;
    private static final long DROP_ANIM_MS = 160L;

    private final List<String> mOrder = new ArrayList<>();
    private final Set<String> mDisabled = new LinkedHashSet<>();

    private boolean mEnabled = true;
    /** Power long-press timeout in milliseconds; {@code 0} keeps the framework's own value. */
    private int mLongPressMs;
    /** What the device uses without an override, as reported by system_server (0 = not known yet). */
    private int mFrameworkDefaultMs;
    /** Set while the slider is moved from code, so that is not mistaken for a user drag. */
    private boolean mSuppressSeek;

    private ScrollView mScrollView;
    private LinearLayout mContent;
    private LinearLayout mItemContainer;
    private Switch mMasterSwitch;
    private SeekBar mLongPressSeek;
    private EditText mLongPressInput;
    private ImageButton mLongPressReset;
    private ImageButton mItemsReset;
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
                readFrameworkDefault(this);
                Toast.makeText(SettingsActivity.this,
                        applied ? R.string.settings_applied : R.string.settings_not_applied,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Re-sends the stored settings when the screen is opened. This is what makes configuring the
     * module before it is enabled in LSPosed recoverable: the next time the app is opened the
     * values reach SystemUI again (without restarting it), and the reply carries the timeout the
     * device really uses.
     */
    private void syncSavedConfig() {
        SharedPreferences prefs = getSharedPreferences(PowerMenuConfig.PREF_FILE, MODE_PRIVATE);
        if (!prefs.contains(PowerMenuConfig.KEY_DISABLED)) {
            // The user has never saved anything; leave SystemUI's own values alone.
            return;
        }
        pushConfig(false, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent result) {
                readFrameworkDefault(this);
            }
        });
    }

    /**
     * Picks the framework's own long-press timeout out of the ordered broadcast's result extras.
     * Only system_server can measure it - the value is not reliably derivable from
     * {@code config_longPressOnPowerDurationMs} - and it is 0 until it has seen a power key press.
     */
    private void readFrameworkDefault(BroadcastReceiver receiver) {
        Bundle extras = receiver.getResultExtras(false);
        if (extras == null) {
            return;
        }
        int ms = extras.getInt(PowerMenuConfig.EXTRA_FRAMEWORK_DEFAULT_MS, 0);
        if (ms <= 0 || ms == mFrameworkDefaultMs) {
            return;
        }
        mFrameworkDefaultMs = ms;
        syncLongPressViews();
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

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView headerText = new TextView(this);
        headerText.setText(R.string.settings_items_header);
        headerText.setTextSize(13);
        headerText.setTypeface(Typeface.DEFAULT_BOLD);
        headerText.setAllCaps(true);
        headerText.setTextColor(themeColorList(android.R.attr.colorAccent));
        headerText.setLetterSpacing(0.06f);
        header.addView(headerText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mItemsReset = new ImageButton(this);
        mItemsReset.setImageResource(R.drawable.ic_restore);
        mItemsReset.setImageTintList(themeColorList(android.R.attr.colorAccent));
        mItemsReset.setBackground(themeBackground(
                android.R.attr.selectableItemBackgroundBorderless));
        mItemsReset.setContentDescription(getString(R.string.settings_items_reset));
        mItemsReset.setPadding(dp(4), dp(4), dp(4), dp(4));
        mItemsReset.setOnClickListener(v -> resetItems());
        int resetSize = dp(28);
        header.addView(mItemsReset, new LinearLayout.LayoutParams(resetSize, resetSize));
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
     * <p>The slider covers {@link LongPress#MIN_MS}..{@link LongPress#MAX_MS} in
     * {@link LongPress#STEP_MS} steps; the value on the right is an editable field, so an exact
     * number can simply be typed in; and the icon button next to the title restores the device's own
     * value, which is what {@code 0} means in the stored config (and what dims the button).
     */
    private View buildLongPressCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground());
        card.setPadding(dp(16), dp(CARD_PADDING_DP - HEADER_SLACK_DP), dp(16),
                dp(CARD_PADDING_DP - SEEK_SLACK_DP));
        card.setClipChildren(false);
        card.setClipToPadding(false);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.settings_long_press);
        title.setTextSize(16);
        title.setSingleLine(true);
        title.setTextColor(themeColorList(android.R.attr.textColorPrimary));
        header.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mLongPressReset = new ImageButton(this);
        mLongPressReset.setImageResource(R.drawable.ic_restore);
        mLongPressReset.setImageTintList(themeColorList(android.R.attr.colorAccent));
        mLongPressReset.setBackground(themeBackground(
                android.R.attr.selectableItemBackgroundBorderless));
        mLongPressReset.setContentDescription(getString(R.string.settings_long_press_reset));
        mLongPressReset.setOnClickListener(v -> resetLongPress());
        mLongPressReset.setPadding(dp(4), dp(4), dp(4), dp(4));
        // Explicit heights everywhere: with wrap_content the theme's edit/seekbar metrics made this
        // card roughly a screen tall (the row ended up vertically centred in it).
        int button = dp(32);
        header.addView(mLongPressReset, new LinearLayout.LayoutParams(button, button));

        // Everything left of the value is fixed, so the button stays glued to the title and the
        // slack ends up here, between it and the number.
        header.addView(new View(this), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mLongPressInput = new EditText(this);
        mLongPressInput.setTextSize(14);
        mLongPressInput.setTextColor(themeColorList(android.R.attr.colorAccent));
        mLongPressInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        mLongPressInput.setSingleLine(true);
        mLongPressInput.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        mLongPressInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        mLongPressInput.setBackground(themeBackground(android.R.attr.editTextBackground));
        mLongPressInput.setPadding(dp(4), dp(6), dp(4), dp(6));
        // Wrapping the text keeps the underline as long as the number, not as long as a fixed box:
        // the minimum only has to be big enough to tap.
        mLongPressInput.setMinWidth(dp(24));
        // Committed when the field is left or "done" is pressed, so half-typed numbers are not applied.
        mLongPressInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                applyTypedLongPress();
            }
        });
        mLongPressInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyTypedLongPress();
                mLongPressInput.clearFocus();
                return true;
            }
            return false;
        });
        header.addView(mLongPressInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(INPUT_HEIGHT_DP)));

        TextView unit = new TextView(this);
        unit.setText(R.string.settings_long_press_unit);
        // Same size as the number, so centring both in the row puts them on one baseline.
        unit.setTextSize(14);
        unit.setPadding(dp(4), 0, 0, 0);
        unit.setTextColor(themeColorList(android.R.attr.textColorSecondary));
        header.addView(unit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(HEADER_HEIGHT_DP)));

        mLongPressSeek = new SeekBar(this);
        mLongPressSeek.setMax(LongPress.steps());
        // The theme insets the track by the thumb's radius; a fixed, smaller inset keeps the slider
        // a touch narrower than the row above instead of much narrower.
        mLongPressSeek.setPadding(dp(SEEK_INSET_DP), mLongPressSeek.getPaddingTop(),
                dp(SEEK_INSET_DP), mLongPressSeek.getPaddingBottom());
        int accent = themeColor(android.R.attr.colorAccent);
        mLongPressSeek.setProgressTintList(ColorStateList.valueOf(accent));
        mLongPressSeek.setThumbTintList(ColorStateList.valueOf(accent));
        mLongPressSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (mSuppressSeek) {
                    return;
                }
                mLongPressMs = LongPress.stepToMs(progress);
                syncLongPressViews();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        card.addView(mLongPressSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SEEK_HEIGHT_DP)));

        syncLongPressViews();
        // One line in logcat, so a layout that misbehaves again can be diagnosed without guessing.
        card.post(() -> ModuleLog.d("long-press card measured:"
                + " card=" + card.getHeight()
                + " header=" + header.getHeight()
                + " input=" + mLongPressInput.getHeight()
                + " reset=" + mLongPressReset.getHeight()
                + " seek=" + mLongPressSeek.getHeight()));
        return card;
    }

    /** The number shown in the field: the override, or the device's own value. */
    private int displayedLongPressMs() {
        if (mLongPressMs > 0) {
            return mLongPressMs;
        }
        return mFrameworkDefaultMs > 0 ? mFrameworkDefaultMs : LongPress.DEVICE_DEFAULT_MS;
    }

    /**
     * Back to the module's own defaults for the list: the device's {@code config_globalActionsList}
     * order (with the two extended entries right after Restart), both extended entries switched off
     * and everything else on. Nothing is stored until "保存并应用".
     */
    private void resetItems() {
        mOrder.clear();
        mOrder.addAll(PowerMenuConfig.defaultOrder(getResources()));
        mDisabled.clear();
        mDisabled.addAll(defaultDisabled());
        rebuildRows();
    }

    /** What {@link PowerMenuItems} is switched off out of the box: the two extended entries. */
    private Set<String> defaultDisabled() {
        Set<String> disabled = new LinkedHashSet<>();
        disabled.add(PowerMenuItems.BOOTLOADER);
        disabled.add(PowerMenuItems.RECOVERY);
        return disabled;
    }

    /** Dimmed and disabled while the list already is in its default order and switch state. */
    private void updateItemsResetState() {
        if (mItemsReset == null) {
            return;
        }
        boolean isDefault = mOrder.equals(PowerMenuConfig.defaultOrder(getResources()))
                && mDisabled.equals(defaultDisabled());
        mItemsReset.setEnabled(!isDefault);
        mItemsReset.setAlpha(isDefault ? 0.4f : 1f);
    }

    /** Back to "let the device decide", which is what the stored {@code 0} means. */
    private void resetLongPress() {
        mLongPressMs = 0;
        syncLongPressViews();
    }

    private void syncLongPressViews() {
        mSuppressSeek = true;
        mLongPressSeek.setProgress(LongPress.msToStep(displayedLongPressMs()));
        mSuppressSeek = false;
        mLongPressInput.setText(String.valueOf(displayedLongPressMs()));
        // Dimmed while the device's own value is in use, so the state is visible at a glance.
        boolean overridden = mLongPressMs > 0;
        mLongPressReset.setEnabled(overridden);
        mLongPressReset.setAlpha(overridden ? 1f : 0.4f);
    }

    /** Takes whatever was typed into the field, clamped into range. */
    private void applyTypedLongPress() {
        int ms;
        try {
            ms = Integer.parseInt(mLongPressInput.getText().toString().trim());
        } catch (Throwable t) {
            ms = -1;
        }
        if (ms < LongPress.MIN_MS || ms > LongPress.MAX_MS) {
            Toast.makeText(this, getString(R.string.settings_long_press_invalid,
                    LongPress.MIN_MS, LongPress.MAX_MS), Toast.LENGTH_SHORT).show();
            // Keep the previous value instead of guessing what was meant.
            syncLongPressViews();
            return;
        }
        mLongPressMs = ms;
        syncLongPressViews();
    }

    private void rebuildRows() {
        mItemContainer.removeAllViews();
        for (String key : mOrder) {
            mItemContainer.addView(createItemRow(key));
        }
        updateDividers();
        updateItemsResetState();
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
            updateItemsResetState();
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
        dividerParams.rightMargin = dp(16);
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
        updateItemsResetState();
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

    private Drawable themeBackground(int attribute) {
        TypedArray array = obtainStyledAttributes(new int[]{attribute});
        try {
            return array.getDrawable(0);
        } catch (Throwable ignored) {
            return null;
        } finally {
            array.recycle();
        }
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
