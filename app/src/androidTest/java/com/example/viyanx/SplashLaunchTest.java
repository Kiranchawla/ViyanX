package com.example.viyanx;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

public class SplashLaunchTest {

    @Test
    public void appContextHasExpectedPackage() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.example.viyanx", context.getPackageName());
    }
}
