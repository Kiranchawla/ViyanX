package com.example.viyanx;

import java.util.ArrayList;
import java.util.List;

/** Undo and redo for {@link EditState}. The first entry is the starting point and stays put. */
final class EditHistory {

    private static final int MAX_STATES = 100;

    private final List<EditState> states = new ArrayList<>();
    private int index = -1;

    boolean isEmpty() {
        return states.isEmpty();
    }

    /** Starts over with one state, for example when the video has loaded. */
    void reset(EditState initial) {
        states.clear();
        states.add(initial);
        index = 0;
    }

    EditState current() {
        return index >= 0 ? states.get(index) : null;
    }

    /** Records a new state. Ignored if it equals the current one. Clears anything to redo. */
    boolean push(EditState state) {
        if (index >= 0 && states.get(index).equals(state)) {
            return false;
        }
        while (states.size() > index + 1) {
            states.remove(states.size() - 1);
        }
        states.add(state);
        if (states.size() > MAX_STATES) {
            states.remove(0);
        }
        index = states.size() - 1;
        return true;
    }

    boolean canUndo() {
        return index > 0;
    }

    boolean canRedo() {
        return index >= 0 && index < states.size() - 1;
    }

    /** Returns the earlier state, or null if there is nothing to undo. */
    EditState undo() {
        return canUndo() ? states.get(--index) : null;
    }

    /** Returns the later state, or null if there is nothing to redo. */
    EditState redo() {
        return canRedo() ? states.get(++index) : null;
    }

    /** For saving across rotation: the states, then the current index as the last element. */
    ArrayList<String> toList() {
        ArrayList<String> out = new ArrayList<>();
        for (EditState s : states) {
            out.add(s.serialize());
        }
        out.add(String.valueOf(index));
        return out;
    }

    /** Restores what {@link #toList()} wrote. Returns false (and stays empty) if it is invalid. */
    boolean restore(List<String> saved) {
        if (saved == null || saved.size() < 2) {
            return false;
        }
        List<EditState> parsed = new ArrayList<>();
        for (int i = 0; i < saved.size() - 1; i++) {
            EditState s = EditState.parse(saved.get(i));
            if (s == null) {
                return false;
            }
            parsed.add(s);
        }
        int restoredIndex;
        try {
            restoredIndex = Integer.parseInt(saved.get(saved.size() - 1));
        } catch (NumberFormatException e) {
            return false;
        }
        if (restoredIndex < 0 || restoredIndex >= parsed.size()) {
            return false;
        }
        states.clear();
        states.addAll(parsed);
        index = restoredIndex;
        return true;
    }
}
