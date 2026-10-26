package com.example.viyanx;

/** Single-task shortcuts on the home screen. */
enum QuickTool {
    TRIM("trim", R.string.tool_trim),
    MUSIC("music", R.string.tool_music),
    TEXT("text", R.string.tool_text),
    SPEED("speed", R.string.tool_speed),
    FILTERS("filters", R.string.tool_filters);

    final String id;
    final int labelRes;

    QuickTool(String id, int labelRes) {
        this.id = id;
        this.labelRes = labelRes;
    }

    static QuickTool fromId(String id) {
        for (QuickTool tool : values()) {
            if (tool.id.equals(id)) {
                return tool;
            }
        }
        return null;
    }
}
