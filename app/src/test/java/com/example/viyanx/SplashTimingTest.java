package com.example.viyanx;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SplashTimingTest {

    @Test
    public void exitStartsAfterEveryEntranceAnimationFinishes() {
        long glowEnd = SplashActivity.GLOW_DELAY_MS + SplashActivity.GLOW_MS;
        long logoFadeEnd = SplashActivity.LOGO_DELAY_MS + SplashActivity.LOGO_FADE_MS;
        long logoScaleEnd = SplashActivity.LOGO_DELAY_MS + SplashActivity.LOGO_SCALE_MS;
        long lastEntrance = Math.max(glowEnd, Math.max(logoFadeEnd,
                Math.max(logoScaleEnd, SplashActivity.PROGRESS_MS)));
        assertTrue(SplashActivity.EXIT_DELAY_MS >= lastEntrance);
    }

    @Test
    public void totalDurationStaysUnderSixSeconds() {
        assertTrue(SplashActivity.EXIT_DELAY_MS + SplashActivity.EXIT_MS < 6000);
    }
}
