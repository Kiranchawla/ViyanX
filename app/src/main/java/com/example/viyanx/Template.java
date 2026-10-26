package com.example.viyanx;

/** Starting points shown on the home screen. */
enum Template {
    VLOG("vlog", R.string.template_vlog, R.string.template_vlog_desc, AspectRatio.LANDSCAPE),
    SLIDESHOW("slideshow", R.string.template_slideshow, R.string.template_slideshow_desc,
            AspectRatio.SQUARE),
    INTRO("intro", R.string.template_intro, R.string.template_intro_desc, AspectRatio.LANDSCAPE),
    STORY("story", R.string.template_story, R.string.template_story_desc, AspectRatio.PORTRAIT);

    final String id;
    final int nameRes;
    final int descriptionRes;
    final AspectRatio ratio;

    Template(String id, int nameRes, int descriptionRes, AspectRatio ratio) {
        this.id = id;
        this.nameRes = nameRes;
        this.descriptionRes = descriptionRes;
        this.ratio = ratio;
    }

    static Template fromId(String id) {
        for (Template template : values()) {
            if (template.id.equals(id)) {
                return template;
            }
        }
        return null;
    }
}
