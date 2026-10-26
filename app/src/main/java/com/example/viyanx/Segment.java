package com.example.viyanx;

/** One kept piece of the source video, in source milliseconds. Never changes once created. */
final class Segment {
    final String id;
    final long startMs;
    final long endMs;

    Segment(String id, long startMs, long endMs) {
        this.id = id;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    long length() {
        return endMs - startMs;
    }

    Segment withRange(long newStartMs, long newEndMs) {
        return new Segment(id, newStartMs, newEndMs);
    }
}
