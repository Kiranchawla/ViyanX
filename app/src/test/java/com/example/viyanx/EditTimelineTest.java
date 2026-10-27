package com.example.viyanx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class EditTimelineTest {

    private static final long S = 1000;
    private static final long SOURCE = 60 * S;
    private static final long[] LENGTHS = {SOURCE};

    private static EditTimeline sixty() {
        return EditTimeline.whole(SOURCE);
    }

    /** The example from the task: A 0-10, B 10-20, C 20-35, D 35-60. */
    private static EditTimeline abcd() {
        return new EditTimeline(Arrays.asList(
                new Segment("A", 0, 10 * S), new Segment("B", 10 * S, 20 * S),
                new Segment("C", 20 * S, 35 * S), new Segment("D", 35 * S, 60 * S)));
    }

    @Test
    public void wholeIsOneSegmentCoveringTheSource() {
        EditTimeline t = sixty();
        assertEquals(1, t.size());
        assertEquals(0, t.get(0).startMs);
        assertEquals(SOURCE, t.get(0).endMs);
        assertEquals(SOURCE, t.durationMs());
    }

    @Test
    public void unknownDurationGivesEmptyTimeline() {
        assertTrue(EditTimeline.whole(0).isEmpty());
        assertTrue(EditTimeline.whole(-1).isEmpty());
    }

    @Test
    public void singleSplitMakesTwoTouchingSegments() {
        EditTimeline t = sixty().split(0, 10 * S, "r");
        assertEquals(2, t.size());
        assertEquals(0, t.get(0).startMs);
        assertEquals(10 * S, t.get(0).endMs);
        assertEquals(10 * S, t.get(1).startMs); // Same cut point: no frame lost or repeated.
        assertEquals(SOURCE, t.get(1).endMs);
        assertEquals(SOURCE, t.durationMs());
    }

    @Test
    public void splittingTwiceMakesThreeSegments() {
        EditTimeline t = sixty().split(0, 10 * S, "b");
        t = t.split(1, 20 * S, "c"); // Timeline time 20 s is inside the second segment.
        assertEquals(3, t.size());
        assertEquals("0-10 10-20 20-60", range(t));
        assertEquals(SOURCE, t.durationMs());
    }

    @Test
    public void deletingMiddleSegmentClosesTheGap() {
        EditTimeline t = abcd().delete(1);
        assertEquals(3, t.size());
        assertEquals(50 * S, t.durationMs());
        assertEquals("A", t.get(0).id);
        assertEquals("C", t.get(1).id);
        assertEquals("D", t.get(2).id);
        // C keeps its source range but now starts at timeline time 10 s.
        assertEquals(20 * S, t.get(1).startMs);
        assertEquals(35 * S, t.get(1).endMs);
        assertEquals(10 * S, t.startOf(1));
        assertEquals(25 * S, t.startOf(2));
    }

    @Test
    public void multipleCutsLeaveOnlyRetainedRanges() {
        EditTimeline t = sixty().split(0, 10 * S, "b");
        t = t.split(1, 20 * S, "c");           // 0-10 | 10-20 | 20-60
        t = t.delete(1);                        // 0-10 | 20-60, timeline 50 s
        t = t.split(1, 30 * S, "d");            // 0-10 | 20-40 | 40-60
        t = t.delete(2);                        // 0-10 | 20-40
        assertEquals("0-10 20-40", range(t));
        assertEquals(30 * S, t.durationMs());
    }

    @Test
    public void timelineTimeMapsToSourceTime() {
        EditTimeline t = abcd().delete(1); // A 0-10, C 20-35, D 35-60
        assertEquals(0, t.toSource(0));
        assertEquals(5 * S, t.toSource(5 * S));
        assertEquals(20 * S, t.toSource(10 * S));       // First moment of C.
        assertEquals(25 * S, t.toSource(15 * S));
        assertEquals(35 * S, t.toSource(25 * S));       // First moment of D.
        assertEquals(60 * S, t.toSource(50 * S));       // The very end.
        assertEquals(60 * S, t.toSource(99 * S));       // Past the end clamps.
        assertEquals(0, t.toSource(-5));
        assertEquals(1, t.indexAt(10 * S));              // A boundary belongs to the next one.
        assertEquals(0, t.indexAt(10 * S - 1));
        assertEquals(15 * S, t.toTimeline(1, 25 * S));
    }

    @Test
    public void emptyTimelineIsSafe() {
        EditTimeline t = abcd().delete(0).delete(0).delete(0).delete(0);
        assertTrue(t.isEmpty());
        assertEquals(0, t.durationMs());
        assertEquals(-1, t.indexAt(0));
        assertEquals(-1, t.toSource(0));
        assertSame(t, t.delete(0));
        assertSame(t, t.split(0, 1000, "x"));
    }

    @Test
    public void splitIsRefusedNearEdgesAndOutsideTheClip() {
        EditTimeline t = sixty();
        long min = EditTimeline.MIN_SEGMENT_MS;
        assertFalse(t.canSplit(0, 0));
        assertFalse(t.canSplit(0, min - 1));
        assertTrue(t.canSplit(0, min));
        assertTrue(t.canSplit(0, SOURCE - min));
        assertFalse(t.canSplit(0, SOURCE - min + 1));
        assertFalse(t.canSplit(0, SOURCE));
        assertFalse(t.canSplit(1, 5 * S)); // No such segment.
        assertFalse(abcd().canSplit(0, 15 * S)); // 15 s is inside B, not A.
        assertSame(t, t.split(0, 100, "x"));
    }

    @Test
    public void trimmingStartChangesTheSourceRange() {
        EditTimeline t = abcd().trimStart(2, 25 * S);
        assertEquals(25 * S, t.get(2).startMs);
        assertEquals(35 * S, t.get(2).endMs);
        assertEquals(55 * S, t.durationMs());
    }

    @Test
    public void trimmingEndChangesTheSourceRange() {
        EditTimeline t = abcd().trimEnd(2, 30 * S, LENGTHS);
        assertEquals(30 * S, t.get(2).endMs);
        assertEquals(55 * S, t.durationMs());
    }

    @Test
    public void trimCannotCrossNeighboursOrTheSource() {
        EditTimeline t = abcd();
        // C cannot start before B ends, which would repeat frames B already shows.
        assertEquals(20 * S, t.trimStart(2, 12 * S).get(2).startMs);
        assertEquals(35 * S, t.trimEnd(2, 50 * S, LENGTHS).get(2).endMs);
        assertEquals(0, t.trimStart(0, -5 * S).get(0).startMs);
        assertEquals(60 * S, t.trimEnd(3, 90 * S, LENGTHS).get(3).endMs);
    }

    @Test
    public void trimCanGrowBackIntoADeletedRange() {
        EditTimeline t = abcd().delete(1); // A 0-10, C 20-35, D 35-60
        EditTimeline grown = t.trimStart(1, 12 * S);
        assertEquals(12 * S, grown.get(1).startMs); // Allowed down to the end of A (10 s).
        assertEquals(10 * S, t.trimStart(1, 0).get(1).startMs);
    }

    @Test
    public void trimKeepsTheMinimumLength() {
        EditTimeline t = abcd();
        long min = EditTimeline.MIN_SEGMENT_MS;
        assertEquals(10 * S - min, t.trimStart(0, 10 * S).get(0).startMs);
        assertEquals(min, t.trimEnd(0, 0, LENGTHS).get(0).endMs);
    }

    @Test
    public void serializeAndParseRoundTrip() {
        EditTimeline t = abcd().delete(1);
        assertEquals(t, EditTimeline.parse(t.serialize()));
        assertTrue(EditTimeline.parse("").isEmpty());
        assertNull(EditTimeline.parse(null));
        assertNull(EditTimeline.parse("a,1"));
        assertNull(EditTimeline.parse("a,x,5"));
    }

    @Test
    public void oldThreeFieldTextReadsAsTheFirstVideo() {
        EditTimeline t = EditTimeline.parse("a,0,5000;b,6000,9000");
        assertEquals(0, t.get(1).source);
        assertEquals(6000, t.get(1).startMs);
    }

    @Test
    public void duplicateInsertsAnIndependentCopyAfterTheClip() {
        EditTimeline t = abcd().duplicate(1, "B2");
        assertEquals(5, t.size());
        assertEquals("B", t.get(1).id);
        assertEquals("B2", t.get(2).id);
        assertEquals(t.get(1).startMs, t.get(2).startMs);
        assertEquals(t.get(1).endMs, t.get(2).endMs);
        assertEquals(70 * S, t.durationMs());
        assertEquals(20 * S, t.startOf(2));
        EditTimeline empty = abcd().delete(0).delete(0).delete(0).delete(0);
        assertSame(empty, empty.duplicate(0, "x"));
    }

    @Test
    public void duplicateCanBeTrimmedWithoutEatingItsOriginal() {
        EditTimeline t = abcd().duplicate(1, "B2"); // B 10-20, B2 10-20
        assertEquals(12 * S, t.trimStart(2, 12 * S).get(2).startMs);
        assertEquals(40 * S, t.trimEnd(1, 40 * S, LENGTHS).get(1).endMs); // Free: the copy repeats B.
    }

    @Test
    public void duplicatesSurviveSanitizing() {
        EditTimeline t = abcd().duplicate(1, "B2");
        assertEquals(t, t.sanitized(LENGTHS));
    }

    @Test
    public void insertedClipFromAnotherVideoKeepsItsSource() {
        EditTimeline t = abcd().insert(2, new Segment("N", 1, 0, 8 * S));
        assertEquals(5, t.size());
        assertEquals("N", t.get(2).id);
        assertEquals(1, t.get(2).source);
        assertEquals(68 * S, t.durationMs());
        assertEquals(20 * S, t.startOf(2));
        assertEquals(t, EditTimeline.parse(t.serialize()));
        assertEquals(1, EditTimeline.parse(t.serialize()).get(2).source);
    }

    @Test
    public void clipsFromDifferentVideosDoNotLimitEachOther() {
        EditTimeline t = new EditTimeline(Arrays.asList(
                new Segment("a", 0, 0, 10 * S), new Segment("b", 1, 0, 20 * S)));
        long[] lengths = {60 * S, 30 * S};
        assertEquals(0, t.trimStart(1, 0).get(1).startMs);
        assertEquals(30 * S, t.trimEnd(1, 99 * S, lengths).get(1).endMs); // Its own video's end.
        assertEquals(60 * S, t.trimEnd(0, 99 * S, lengths).get(0).endMs);
    }

    @Test
    public void moveReordersWithoutChangingContentOrLength() {
        EditTimeline t = abcd().move(0, 2); // B C A D
        assertEquals("B C A D", ids(t));
        assertEquals(60 * S, t.durationMs());
        assertEquals(10 * S, t.get(2).endMs); // A keeps its source range.
        assertEquals(25 * S, t.startOf(2));   // B 10 + C 15 come before it.
        assertEquals("A B C D", ids(t.move(2, 0)));
        assertEquals("A B D C", ids(abcd().move(3, 2)));
        assertEquals("D A B C", ids(abcd().move(3, 0)));
    }

    @Test
    public void moveIgnoresInvalidOrNoOpRequests() {
        EditTimeline t = abcd();
        assertSame(t, t.move(1, 1));
        assertSame(t, t.move(-1, 2));
        assertSame(t, t.move(0, 4));
        assertSame(t, t.move(4, 0));
    }

    @Test
    public void moveKeepsSourceVideoOfEachClip() {
        EditTimeline t = new EditTimeline(Arrays.asList(
                new Segment("a", 0, 0, 5 * S), new Segment("b", 1, 0, 8 * S))).move(1, 0);
        assertEquals("b a", ids(t));
        assertEquals(1, t.get(0).source);
        assertEquals(t, EditTimeline.parse(t.serialize()));
    }

    private static String ids(EditTimeline t) {
        StringBuilder out = new StringBuilder();
        for (Segment s : t.segments()) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(s.id);
        }
        return out.toString();
    }

    @Test
    public void splitKeepsTheSourceVideo() {
        EditTimeline t = new EditTimeline(Arrays.asList(new Segment("b", 1, 0, 20 * S)))
                .split(0, 5 * S, "r");
        assertEquals(1, t.get(0).source);
        assertEquals(1, t.get(1).source);
    }

    @Test
    public void sanitizedDropsUnknownVideos() {
        EditTimeline t = new EditTimeline(Arrays.asList(
                new Segment("a", 0, 0, 10 * S), new Segment("b", 3, 0, 5 * S)));
        assertEquals(1, t.sanitized(LENGTHS).size());
    }

    @Test
    public void sanitizedDropsInvalidPieces() {
        EditTimeline damaged = new EditTimeline(Arrays.asList(
                new Segment("a", 5 * S, 2 * S),            // Backwards.
                new Segment("b", 0, 10 * S),
                new Segment("c", 8 * S, 20 * S),           // Overlap is allowed: clips can repeat.
                new Segment("d", 70 * S, 80 * S)));        // Beyond the source.
        EditTimeline fixed = damaged.sanitized(LENGTHS);
        assertEquals("0-10 8-20", range(fixed));
    }

    private static String range(EditTimeline t) {
        StringBuilder out = new StringBuilder();
        for (Segment s : t.segments()) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(s.startMs / S).append('-').append(s.endMs / S);
        }
        return out.toString();
    }
}
