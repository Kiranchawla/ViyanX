package com.example.viyanx;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * Slow launch animation: glow rises, the logo fades and scales in, a progress bar fills,
 * then the screen fades out into {@link HomeActivity}. Tapping skips it.
 */
public class SplashActivity extends Activity {

    // Timings in milliseconds. Total is about 5.5 seconds.
    static final long GLOW_DELAY_MS = 0;
    static final long GLOW_MS = 1600;
    static final long LOGO_DELAY_MS = 500;
    static final long LOGO_FADE_MS = 2000;
    static final long LOGO_SCALE_MS = 3000;
    static final long PROGRESS_MS = 4600;
    static final long EXIT_DELAY_MS = 5100;
    static final long EXIT_MS = 450;

    /** Used instead of the animation when the user has turned system animations off. */
    private static final long STATIC_HOLD_MS = 1200;

    private static final float LOGO_START_SCALE = 0.88f;
    private static final float GLOW_END_ALPHA = 1f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AnimatorSet sequence;
    private boolean navigated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        final View root = findViewById(R.id.splash_root);
        View logo = findViewById(R.id.splash_logo);
        View glowViolet = findViewById(R.id.splash_glow_violet);
        View glowLime = findViewById(R.id.splash_glow_lime);
        View progress = findViewById(R.id.splash_progress);

        root.setOnClickListener(v -> goHome());

        if (animationsDisabled()) {
            // Respect "remove animations": show the finished logo briefly, then continue.
            glowViolet.setAlpha(GLOW_END_ALPHA);
            glowLime.setAlpha(GLOW_END_ALPHA);
            progress.setVisibility(View.INVISIBLE);
            handler.postDelayed(this::goHome, STATIC_HOLD_MS);
            return;
        }

        logo.setAlpha(0f);
        logo.setScaleX(LOGO_START_SCALE);
        logo.setScaleY(LOGO_START_SCALE);
        glowViolet.setAlpha(0f);
        glowLime.setAlpha(0f);
        progress.setPivotX(0f);
        progress.setScaleX(0f);

        ObjectAnimator glowA = fade(glowViolet, GLOW_END_ALPHA, GLOW_DELAY_MS, GLOW_MS);
        ObjectAnimator glowB = fade(glowLime, GLOW_END_ALPHA, GLOW_DELAY_MS, GLOW_MS);
        ObjectAnimator logoFade = fade(logo, 1f, LOGO_DELAY_MS, LOGO_FADE_MS);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(logo, View.SCALE_X, LOGO_START_SCALE, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(logo, View.SCALE_Y, LOGO_START_SCALE, 1f);
        for (ObjectAnimator a : new ObjectAnimator[] {scaleX, scaleY}) {
            a.setStartDelay(LOGO_DELAY_MS);
            a.setDuration(LOGO_SCALE_MS);
            a.setInterpolator(new DecelerateInterpolator(1.8f));
        }
        ObjectAnimator bar = ObjectAnimator.ofFloat(progress, View.SCALE_X, 0f, 1f);
        bar.setDuration(PROGRESS_MS);
        bar.setInterpolator(new LinearInterpolator());
        ObjectAnimator exit = ObjectAnimator.ofFloat(root, View.ALPHA, 1f, 0f);
        exit.setStartDelay(EXIT_DELAY_MS);
        exit.setDuration(EXIT_MS);
        exit.setInterpolator(new AccelerateInterpolator());

        sequence = new AnimatorSet();
        sequence.playTogether(glowA, glowB, logoFade, scaleX, scaleY, bar, exit);
        sequence.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                goHome();
            }
        });
        sequence.start();
    }

    private static ObjectAnimator fade(View view, float to, long delay, long duration) {
        ObjectAnimator a = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, to);
        a.setStartDelay(delay);
        a.setDuration(duration);
        a.setInterpolator(new DecelerateInterpolator());
        return a;
    }

    private boolean animationsDisabled() {
        float scale = Settings.Global.getFloat(
                getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        return scale == 0f;
    }

    private void goHome() {
        if (navigated) {
            return;
        }
        navigated = true;
        handler.removeCallbacksAndMessages(null);
        if (sequence != null) {
            sequence.cancel();
        }
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (sequence != null && sequence.isPaused()) {
            sequence.resume();
        }
    }

    @Override
    protected void onStop() {
        // Do not keep running (or open Home) while the app is in the background.
        if (sequence != null && sequence.isRunning() && !navigated) {
            sequence.pause();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        navigated = true;
        handler.removeCallbacksAndMessages(null);
        if (sequence != null) {
            sequence.cancel();
        }
        super.onDestroy();
    }
}
