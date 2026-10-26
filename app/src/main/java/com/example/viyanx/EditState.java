package com.example.viyanx;

import java.util.Objects;

/** What undo and redo move between: the segments and the output size. Never the source video. */
final class EditState {
    final EditTimeline timeline;
    final String ratioId;

    EditState(EditTimeline timeline, String ratioId) {
        this.timeline = timeline;
        this.ratioId = ratioId;
    }

    String serialize() {
        return ratioId + "|" + timeline.serialize();
    }

    /** Returns null if the text is not a state written by {@link #serialize()}. */
    static EditState parse(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split("\\|", 2);
        if (parts.length != 2) {
            return null;
        }
        EditTimeline timeline = EditTimeline.parse(parts[1]);
        return timeline == null ? null : new EditState(timeline, parts[0]);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof EditState)) {
            return false;
        }
        EditState other = (EditState) o;
        return timeline.equals(other.timeline) && Objects.equals(ratioId, other.ratioId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeline, ratioId);
    }
}
