package com.example.viyanx;

/** Output sizes offered when starting a project. */
enum AspectRatio {
    ORIGINAL("original", R.string.ratio_original, R.string.ratio_original_hint, 0f),
    PORTRAIT("9:16", R.string.ratio_portrait, R.string.ratio_portrait_hint, 9f / 16f),
    LANDSCAPE("16:9", R.string.ratio_landscape, R.string.ratio_landscape_hint, 16f / 9f),
    SQUARE("1:1", R.string.ratio_square, R.string.ratio_square_hint, 1f),
    FOUR_FIVE("4:5", R.string.ratio_four_five, R.string.ratio_four_five_hint, 4f / 5f);

    final String id;
    final int labelRes;
    final int hintRes;
    /** Width divided by height, or 0 to follow the video itself. */
    final float value;

    AspectRatio(String id, int labelRes, int hintRes, float value) {
        this.id = id;
        this.labelRes = labelRes;
        this.hintRes = hintRes;
        this.value = value;
    }

    static AspectRatio fromId(String id) {
        for (AspectRatio ratio : values()) {
            if (ratio.id.equals(id)) {
                return ratio;
            }
        }
        return ORIGINAL;
    }
}
