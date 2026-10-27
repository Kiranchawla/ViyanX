package com.example.viyanx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The edit: an ordered list of {@link Segment}s cut from the project's source videos. Source times
 * say what part of a video a segment shows; timeline times say where it sits in the edited result.
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
        list.set(index, new Segment(s.id, s.source, s.startMs, cut));
        list.add(index + 1, new Segment(rightId, s.source, cut, s.endMs));
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

    /** Puts an independent copy of segment {@code index} right after it. */
    EditTimeline duplicate(int index, String copyId) {
        if (index < 0 || index >= segments.size()) {
            return this;
        }
        List<Segment> list = new ArrayList<>(segments);
        list.add(index + 1, segments.get(index).withId(copyId));
        return new EditTimeline(list);
    }

    /** Moves segment {@code from} so that it ends up at position {@code to}. */
    EditTimeline move(int from, int to) {
        if (from < 0 || from >= segments.size() || to < 0 || to >= segments.size() || from == to) {
            return this;
        }
        List<Segment> list = new ArrayList<>(segments);
        list.add(to, list.remove(from));
        return new EditTimeline(list);
    }

    /** Inserts a segment so that it becomes {@code index} (0 = first, size() = last). */
    EditTimeline insert(int index, Segment segment) {
        if (index < 0 || index > segments.size() || segment.length() <= 0) {
            return this;
        }
        List<Segment> list = new ArrayList<>(segments);
        list.add(index, segment);
        return new EditTimeline(list);
    }

    /**
     * The earliest source time segment {@code index} may start at. A segment that directly follows
     * an earlier part of the same video cannot grow back into it; otherwise (after a duplicate or
     * across different videos) the video's own start is the limit.
     */
    long minStart(int index) {
        Segment s = segments.get(index);
        if (index > 0) {
            Segment before = segments.get(index - 1);
            if (before.source == s.source && before.endMs <= s.startMs) {
                return before.endMs;
            }
        }
        return 0;
    }

    /** The latest source time segment {@code index} may end at. */
    long maxEnd(int index, long[] sourceLengths) {
        Segment s = segments.get(index);
        if (index < segments.size() - 1) {
            Segment after = segments.get(index + 1);
            if (after.source == s.source && after.startMs >= s.endMs) {
                return after.startMs;
            }
        }
        return s.source >= 0 && s.source < sourceLengths.length ? sourceLengths[s.source] : s.endMs;
    }

    /** Moves the start of segment {@code index} to this source time, within the allowed range. */
    EditTimeline trimStart(int index, long sourceMs) {
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
    EditTimeline trimEnd(int index, long sourceMs, long[] sourceLengths) {
        if (index < 0 || index >= segments.size()) {
            return this;
        }
        Segment s = segments.get(index);
        long max = Math.max(s.startMs, maxEnd(index, sourceLengths));
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

    /** Text form for the project file and undo history: {@code id,start,end,source;...}. */
    String serialize() {
        StringBuilder out = new StringBuilder();
        for (Segment s : segments) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(s.id).append(',').append(s.startMs).append(',').append(s.endMs)
                    .append(',').append(s.source);
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
                // Older projects wrote three fields: they only knew about the first video.
                if ((f.length != 3 && f.length != 4) || f[0].isEmpty()) {
                    return null;
                }
                try {
                    int source = f.length == 4 ? Integer.parseInt(f[3]) : 0;
                    list.add(new Segment(f[0], source, Long.parseLong(f[1]), Long.parseLong(f[2])));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return new EditTimeline(list);
    }

    /**
     * Repairs a timeline read from disk: drops pieces that are empty, come from an unknown video or
     * lie outside it, so a damaged file can never produce an invalid export. Pieces may repeat the
     * same range, because a clip can be duplicated.
     */
    EditTimeline sanitized(long[] sourceLengths) {
        List<Segment> list = new ArrayList<>();
        for (Segment s : segments) {
            if (s.source < 0 || s.source >= sourceLengths.length) {
                continue;
            }
            long start = Math.max(0, s.startMs);
            long end = Math.min(sourceLengths[s.source], s.endMs);
            if (end > start) {
                list.add(new Segment(s.id, s.source, start, end));
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
