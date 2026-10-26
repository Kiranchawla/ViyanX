package com.example.viyanx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EditHistoryTest {

    private static EditState state(long start, long end) {
        return new EditState(new EditTimeline(java.util.Arrays.asList(
                new Segment("a", start, end))), "original");
    }

    @Test
    public void startsWithNothingToUndoOrRedo() {
        EditHistory h = new EditHistory();
        h.reset(state(0, 10_000));
        assertFalse(h.canUndo());
        assertFalse(h.canRedo());
        assertNull(h.undo());
        assertNull(h.redo());
    }

    @Test
    public void undoAndRedoWalkBetweenStates() {
        EditHistory h = new EditHistory();
        h.reset(state(0, 10_000));
        h.push(state(1_000, 10_000));
        h.push(state(1_000, 8_000));
        assertEquals(state(1_000, 10_000), h.undo());
        assertEquals(state(0, 10_000), h.undo());
        assertFalse(h.canUndo());
        assertEquals(state(1_000, 10_000), h.redo());
        assertTrue(h.canRedo());
    }

    @Test
    public void newEditAfterUndoClearsRedo() {
        EditHistory h = new EditHistory();
        h.reset(state(0, 10_000));
        h.push(state(1_000, 10_000));
        h.undo();
        h.push(state(2_000, 10_000));
        assertFalse(h.canRedo());
        assertEquals(state(0, 10_000), h.undo());
    }

    @Test
    public void identicalStateIsNotRecorded() {
        EditHistory h = new EditHistory();
        h.reset(state(0, 10_000));
        assertFalse(h.push(state(0, 10_000)));
        assertFalse(h.canUndo());
    }

    @Test
    public void ratioChangeIsAnEdit() {
        EditHistory h = new EditHistory();
        h.reset(state(0, 10_000));
        assertTrue(h.push(new EditState(state(0, 10_000).timeline, "9:16")));
        assertEquals("original", h.undo().ratioId);
    }

    @Test
    public void survivesSaveAndRestore() {
        EditHistory h = new EditHistory();
        h.reset(state(0, 10_000));
        h.push(state(1_000, 10_000));
        h.push(state(1_000, 8_000));
        h.undo();
        EditHistory copy = new EditHistory();
        assertTrue(copy.restore(h.toList()));
        assertEquals(state(1_000, 10_000), copy.current());
        assertTrue(copy.canUndo());
        assertTrue(copy.canRedo());
    }

    @Test
    public void rejectsBrokenSavedData() {
        EditHistory h = new EditHistory();
        assertFalse(h.restore(java.util.Arrays.asList("not,a,number", "0")));
        assertFalse(h.restore(java.util.Arrays.asList(state(0, 1_000).serialize(), "5")));
        assertTrue(h.isEmpty());
    }
}
