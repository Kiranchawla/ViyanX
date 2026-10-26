package com.example.viyanx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The edit: an ordered list of {@link Segment}s cut from one source video. Source times say what
 * part of the video a segment shows; timeline times say where it sits in the edited result.
 * Deleting a segment closes the gap, so later segments move earlier on the timeline while their
 * source times stay the same. Instances never change; every edit returns a new timeline.
 * Pure Java so the rules can be unit tested.
 */
final class EditTimeline {

    /** No segment may be shorter than this, and a split needs this much on both sides. */
    static final long MIN_SEGMENT_MS = 500;

    private static final Random RANDOM = new Random();

    private final List<Segment> segments;

    EditTimeline(List<Segment> segments) {
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
    }

    /** The whole video as one segment. */
    static EditTimeline whole(long sourceDurationMs) {
        List<Segment> list = new ArrayList<>();
        if (sourceDurationMs > 0) {
            list.add(new Segment(newId(), 0, sourceDurationMs));
        }
        return new EditTimeline(list);
    }

    static String newId() {
        return Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36);
    }

    // ---- Reading -----------------------------------------------------------------------

    List<Segment> segments() {
        return segments;
    }

    int size() {
        return segments.size();
    }

    boolean isEmpty() {
        return segments.isEmpty();
    }

    Segment get(int index) {
        return segments.get(index);
    }

    /** Total length of the edited result. */
    long durationMs() {
        long total = 0;
        for (Segment s : segments) {
            total += s.length();
        }
        return total;
    }

    /** Where segment {@code index} begins on the timeline. */
    long startOf(int index) {
        long at = 0;
        for (int i = 0; i < index; i++) {
            at += segments.get(i).length();
        }
        return at;
    }

    long endOf(int index) {
        return startOf(index) + segments.get(index).length();
    }

    /** Index of the segment showing at this timeline time, or -1 if the timeline is empty. */
    int indexAt(long timelineMs) {
        if (segments.isEmpty()) {
            return -1;
        }
        long at = 0;
        for (int i = 0; i < segments.size(); i++) {
            at += segments.get(i).length();
            if (timelineMs < at) {
                return i;
            }
        }
        return segments.size() - 1; // At or past the end belongs to the last segment.
    }

    /** Converts a timeline time to the source time shown there, or -1 if the timeline is empty. */
    long toSource(long timelineMs) {
        int index = indexAt(timelineMs);
        if (index < 0) {
            return -1;
        }
        Segment s = segments.get(index);
        long offset = Math.max(0, Math.min(s.length(), timelineMs - startOf(index)));
        return s.startMs + offset;
    }

    /** Converts a source time inside segment {@code index} to a timeline time. */
    long toTimeline(int index, long sourceMs) {
        Segment s = segments.get(index);
        return startOf(index) + Math.max(0, Math.min(s.length(), sourceMs - s.startMs));
    }

    int indexOfId(String id) {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    // ---- Editing -----------------------------------------------------------------------

    /** True if a split at this timeline time leaves two segments of at least the minimum. */
    boolean canSplit(int index, long timelineMs) {
        if (index < 0 || index >= segments.size()) {
            return false;
        }
        long start = startOf(index);
        long end = start + segments.get(index).length();
        return timelineMs - start >= MIN_SEGMENT_MS && end - timelineMs >= MIN_SEGMENT_MS;
    }

    /**
     * Cuts segment {@code index} in two at this timeline time. The halves meet at exactly the same
     * source time, so no frame is lost or repeated. Returns this timeline if the split is invalid.
     */
    EditTimeline split(int index, long timelineMs, String rightId) {
        if (!canSplit(index, timelineMs)) {
            return this;
        }
        Segment s = segments.get(index);
        long cut = s.startMs + (timelineMs - startOf(index));
        List<Segment> list = new ArrayList<>(segments);
        list.set(index, new Segment(s.id, s.startMs, cut));
        list.add(index + 1, new Segment(rightId, cut, s.endMs));
        return new EditTimeline(list);
    }

    /** Removes segment {@code index}. Later segments close the gap. */
    EditTimeline delete(int index) {
        if (index < 0 || index >= segments.size()) {
            return this;
        }
        List<Segment> list = new ArrayList<>(segments);
        list.remove(index);
        return new EditTimeline(list);
    }

    /**
     * The earliest source time segment {@code index} may start at. Segments never overlap in the
     * source, so it can grow back into a range that was deleted but not into a neighbour.
     */
    long minStart(int index) {
        return index > 0 ? segments.get(index - 1).endMs : 0;
    }

    /** The latest source time segment {@code index} may end at. */
    long maxEnd(int index, long sourceDurationMs) {
        return index < segments.size() - 1 ? segments.get(index + 1).startMs : sourceDurationMs;
    }

    /** Moves the start of segment {@code index} to this source time, within the allowed range. */
    EditTimeline trimStart(int index, long sourceMs, long sourceDurationMs) {
        if (index < 0 || index >= segments.size()) {
            return this;
        }
        Segment s = segments.get(index);
        long min = Math.min(minStart(index), s.endMs);
        long max = Math.max(min, s.endMs - Math.min(MIN_SEGMENT_MS, s.length()));
        long start = Math.max(min, Math.min(max, sourceMs));
        return replace(index, s.withRange(start, s.endMs));
    }

    /** Moves the end of segment {@code index} to this source time, within the allowed range. */
    EditTimeline trimEnd(int index, long sourceMs, long sourceDurationMs) {
        if (index < 0 || index >= segments.size()) {
            return this;
        }
        Segment s = segments.get(index);
        long max = Math.max(s.startMs, maxEnd(index, sourceDurationMs));
        long min = Math.min(max, s.startMs + Math.min(MIN_SEGMENT_MS, s.length()));
        long end = Math.max(min, Math.min(max, sourceMs));
        return replace(index, s.withRange(s.startMs, end));
    }

    private EditTimeline replace(int index, Segment segment) {
        List<Segment> list = new ArrayList<>(segments);
        list.set(index, segment);
        return new EditTimeline(list);
    }

    // ---- Saving ------------------------------------------------------------------------

    /** Text form for the project file and undo history: {@code id,start,end;id,start,end}. */
    String serialize() {
        StringBuilder out = new StringBuilder();
        for (Segment s : segments) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(s.id).append(',').append(s.startMs).append(',').append(s.endMs);
        }
        return out.toString();
    }

    /** Reads {@link #serialize()}. Returns null if the text is damaged. */
    static EditTimeline parse(String text) {
        if (text == null) {
            return null;
        }
        List<Segment> list = new ArrayList<>();
        if (!text.isEmpty()) {
            for (String part : text.split(";", -1)) {
                String[] f = part.split(",", -1);
                if (f.length != 3 || f[0].isEmpty()) {
                    return null;
                }
                try {
                    list.add(new Segment(f[0], Long.parseLong(f[1]), Long.parseLong(f[2])));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return new EditTimeline(list);
    }

    /**
     * Repairs a timeline read from disk: drops empty or out-of-range pieces and anything that
     * overlaps or runs backwards, so a damaged file can never produce an invalid export.
     */
    EditTimeline sanitized(long sourceDurationMs) {
        List<Segment> list = new ArrayList<>();
        long floor = 0;
        for (Segment s : segments) {
            long start = Math.max(floor, s.startMs);
            long end = Math.min(sourceDurationMs, s.endMs);
            if (end > start) {
                list.add(new Segment(s.id, start, end));
                floor = end;
            }
        }
        return new EditTimeline(list);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EditTimeline && serialize().equals(((EditTimeline) o).serialize());
    }

    @Override
    public int hashCode() {
        return serialize().hashCode();
    }
}
