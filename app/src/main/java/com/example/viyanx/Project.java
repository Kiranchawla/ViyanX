package com.example.viyanx;

import org.json.JSONException;
import org.json.JSONObject;

/** A saved editing project. Instances are copied in and out of {@link ProjectStore}. */
final class Project {
    final String id;
    String name;
    final String uri;
    long durationMs;
    String ratio;
    final String template; // Template id, or null.
    final String tool; // QuickTool id, or null.
    final long createdAt;
    long lastEdited;
    String thumb; // File name inside the thumbnail folder, or null.
    /** The edit as {@link EditTimeline#serialize()} text, or null if the project has none yet. */
    String segments;
    // Older projects stored one kept range instead of segments. Only read to migrate them.
    long trimStartMs;
    long trimEndMs = -1; // -1 keeps everything to the end.

    Project(String id, String name, String uri, long durationMs, String ratio, String template,
            String tool, long createdAt, long lastEdited, String thumb) {
        this.id = id;
        this.name = name;
        this.uri = uri;
        this.durationMs = durationMs;
        this.ratio = ratio;
        this.template = template;
        this.tool = tool;
        this.createdAt = createdAt;
        this.lastEdited = lastEdited;
        this.thumb = thumb;
    }

    Project copy() {
        Project copy = new Project(id, name, uri, durationMs, ratio, template, tool, createdAt,
                lastEdited, thumb);
        copy.segments = segments;
        copy.trimStartMs = trimStartMs;
        copy.trimEndMs = trimEndMs;
        return copy;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("uri", uri);
        o.put("durationMs", durationMs);
        o.put("ratio", ratio);
        o.put("template", template == null ? JSONObject.NULL : template);
        o.put("tool", tool == null ? JSONObject.NULL : tool);
        o.put("createdAt", createdAt);
        o.put("lastEdited", lastEdited);
        o.put("thumb", thumb == null ? JSONObject.NULL : thumb);
        o.put("segments", segments == null ? JSONObject.NULL : segments);
        o.put("trimStartMs", trimStartMs);
        o.put("trimEndMs", trimEndMs);
        return o;
    }

    static Project fromJson(JSONObject o) throws JSONException {
        Project project = new Project(
                o.getString("id"),
                o.getString("name"),
                o.getString("uri"),
                o.optLong("durationMs", -1),
                o.optString("ratio", AspectRatio.ORIGINAL.id),
                o.isNull("template") ? null : o.optString("template", null),
                o.isNull("tool") ? null : o.optString("tool", null),
                o.optLong("createdAt", 0),
                o.optLong("lastEdited", 0),
                o.isNull("thumb") ? null : o.optString("thumb", null));
        project.segments = o.isNull("segments") ? null : o.optString("segments", null);
        project.trimStartMs = o.optLong("trimStartMs", 0);
        project.trimEndMs = o.optLong("trimEndMs", -1);
        return project;
    }

    /**
     * The saved edit for a source of this length: the stored segments, repaired if damaged, or the
     * old trim range for projects saved before segments existed, or the whole video.
     */
    EditTimeline loadTimeline(long sourceDurationMs) {
        if (sourceDurationMs <= 0) {
            return new EditTimeline(java.util.Collections.emptyList());
        }
        EditTimeline saved = EditTimeline.parse(segments);
        if (saved != null) {
            return saved.sanitized(sourceDurationMs);
        }
        long end = trimEndMs < 0 ? sourceDurationMs : Math.min(trimEndMs, sourceDurationMs);
        long start = Math.max(0, Math.min(trimStartMs, end));
        if (end > start) {
            return new EditTimeline(java.util.Collections.singletonList(
                    new Segment(EditTimeline.newId(), start, end)));
        }
        return EditTimeline.whole(sourceDurationMs);
    }
}
