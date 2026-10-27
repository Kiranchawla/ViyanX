package com.example.viyanx;

/** Single-task shortcuts on the home screen. */
enum QuickTool {
    TRIM("trim", R.drawable.ic_tool_trim, R.string.tool_trim),
    MUSIC("music", R.drawable.ic_music, R.string.tool_music),
    TEXT("text", R.drawable.ic_tool_text, R.string.tool_text),
    SPEED("speed", R.drawable.ic_speed, R.string.tool_speed),
    FILTERS("filters", R.drawable.ic_filters, R.string.tool_filters);

    final String id;
    final int iconRes;
    final int labelRes;

    QuickTool(String id, int iconRes, int labelRes) {
        this.id = id;
        this.iconRes = iconRes;
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
