package com.example.viyanx;

/**
 * One kept piece of a source video, in source milliseconds. {@code source} says which video of the
 * project it comes from: 0 is the video the project was made from, later numbers are clips added
 * in the editor. Never changes once created.
 */
final class Segment {
    final String id;
    final int source;
    final long startMs;
    final long endMs;

    Segment(String id, int source, long startMs, long endMs) {
        this.id = id;
        this.source = source;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    /** A piece of the project's first video. */
    Segment(String id, long startMs, long endMs) {
        this(id, 0, startMs, endMs);
    }

    long length() {
        return endMs - startMs;
    }

    Segment withRange(long newStartMs, long newEndMs) {
        return new Segment(id, source, newStartMs, newEndMs);
    }

    Segment withId(String newId) {
        return new Segment(newId, source, startMs, endMs);
    }
}
