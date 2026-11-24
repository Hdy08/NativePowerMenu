package com.nativepowermenu;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** Mirrors AOSP GlobalActionsLite's resource-driven layout and scrim transition. */
final class PowerMenuAnimation {

    private static final Interpolator ENTER_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final Interpolator EXIT_INTERPOLATOR =
            new PathInterpolator(0.3f, 0f, 1f, 1f);

    private final Window mWindow;
    private final View mLayout;
    private final float mDimAmount;
    private final float mEnterTranslationPx;
    private final float mExitTranslationPx;
    private final int mRotation;
    private final long mEnterDurationMs;
    private final long mExitDurationMs;

    private ValueAnimator mAnimator;

    private PowerMenuAnimation(Window window, View layout, float dimAmount,
            float enterTranslationPx, float exitTranslationPx, int rotation,
            long enterDurationMs, long exitDurationMs) {
        mWindow = window;
        mLayout = layout;
        mDimAmount = dimAmount;
        mEnterTranslationPx = enterTranslationPx;
        mExitTranslationPx = exitTranslationPx;
        mRotation = rotation;
        mEnterDurationMs = enterDurationMs;
        mExitDurationMs = exitDurationMs;
    }

    static PowerMenuAnimation prepare(Window window, View layout) {
        if (window == null || layout == null || !ValueAnimator.areAnimatorsEnabled()) {
            return null;
        }

        Resources res = layout.getResources();
        float density = res.getDisplayMetrics().density;
        float enterTranslation = dimension(res, "popup_enter_animation_from_y_delta",
                20f * density);
        float exitTranslation = dimension(res, "popup_exit_animation_to_y_delta",
                -10f * density);
        int rotation = Surface.ROTATION_0;
        try {
            rotation = window.getWindowManager().getDefaultDisplay().getRotation();
        } catch (Throwable ignored) {
            // Rotation 0 is the AOSP portrait default.
        }

        PowerMenuAnimation animation = new PowerMenuAnimation(window, layout,
                window.getAttributes().dimAmount, enterTranslation, exitTranslation, rotation,
                ResourceLookup.integer(res, ResourceLookup.PKG_ANDROID,
                        "config_activityDefaultDur", 220),
                ResourceLookup.integer(res, ResourceLookup.PKG_ANDROID,
                        "config_activityShortDur", 150));
        layout.setAlpha(0f);
        animation.applyTranslation(enterTranslation);
        window.setDimAmount(0f);
        return animation;
    }

    void startEntrance() {
        start(true, null);
    }

    /** Starts AOSP's reverse transition; returns false when system animations are disabled. */
    boolean startDismiss(Runnable onEnd) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            return false;
        }
        cancelCurrent();
        mLayout.setAlpha(1f);
        applyTranslation(0f);
        mWindow.setDimAmount(mDimAmount);
        start(false, onEnd);
        return true;
    }

    long actionDelayMillis() {
        return ValueAnimator.areAnimatorsEnabled() ? mExitDurationMs + 16L : 0L;
    }

    void cancel() {
        cancelCurrent();
    }

    private void start(boolean entering, Runnable onEnd) {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setInterpolator(entering ? ENTER_INTERPOLATOR : EXIT_INTERPOLATOR);
        animator.setDuration(entering ? mEnterDurationMs : mExitDurationMs);
        animator.addUpdateListener(value -> {
            float progress = (float) value.getAnimatedValue();
            float alpha = entering ? progress : 1f - progress;
            mLayout.setAlpha(alpha);
            mWindow.setDimAmount(mDimAmount * alpha);
            float translation = entering
                    ? mEnterTranslationPx * (1f - progress)
                    : mExitTranslationPx * progress;
            applyTranslation(translation);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private int mPreviousLayerType;

            @Override
            public void onAnimationStart(Animator animation) {
                mPreviousLayerType = mLayout.getLayerType();
                mLayout.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mLayout.setLayerType(mPreviousLayerType, null);
                if (mAnimator == animator) {
                    mAnimator = null;
                    if (onEnd != null) {
                        onEnd.run();
                    }
                }
            }
        });
        mAnimator = animator;
        animator.start();
    }

    private void cancelCurrent() {
        ValueAnimator animator = mAnimator;
        mAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
    }

    private void applyTranslation(float translation) {
        switch (mRotation) {
            case Surface.ROTATION_0:
                mLayout.setTranslationY(0f);
                mLayout.setTranslationX(translation);
                break;
            case Surface.ROTATION_90:
                mLayout.setTranslationX(0f);
                mLayout.setTranslationY(-translation);
                break;
            case Surface.ROTATION_180:
                mLayout.setTranslationY(0f);
                mLayout.setTranslationX(-translation);
                break;
            case Surface.ROTATION_270:
                mLayout.setTranslationX(0f);
                mLayout.setTranslationY(translation);
                break;
            default:
                mLayout.setTranslationX(0f);
                mLayout.setTranslationY(0f);
                break;
        }
    }

    private static float dimension(Resources res, String name, float fallbackPx) {
        int id = res.getIdentifier(name, "dimen", ResourceLookup.PKG_ANDROID);
        if (id == 0) {
            return fallbackPx;
        }
        try {
            return res.getDimension(id);
        } catch (Throwable ignored) {
            return fallbackPx;
        }
    }
}
