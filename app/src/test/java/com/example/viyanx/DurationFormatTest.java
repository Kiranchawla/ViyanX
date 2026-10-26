package com.example.viyanx;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DurationFormatTest {

    @Test
    public void formatsSecondsAndMinutes() {
        assertEquals("0:00", DurationFormat.format(0));
        assertEquals("0:42", DurationFormat.format(42_900));
        assertEquals("1:05", DurationFormat.format(65_000));
    }

    @Test
    public void formatsHours() {
        assertEquals("1:00:00", DurationFormat.format(3_600_000));
        assertEquals("2:03:04", DurationFormat.format(7_384_000));
    }

    @Test
    public void negativeBecomesZero() {
        assertEquals("0:00", DurationFormat.format(-5));
    }

    @Test
    public void formatsTenthsOfASecond() {
        assertEquals("0:00.0", DurationFormat.formatPrecise(0));
        assertEquals("0:04.2", DurationFormat.formatPrecise(4_250));
        assertEquals("1:05.9", DurationFormat.formatPrecise(65_999));
    }
}
