package com.example.viyanx;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** A FrameLayout that fits itself inside the space it is given at a fixed width:height ratio. */
public class RatioFrameLayout extends FrameLayout {

    private float ratio; // Width divided by height; 0 fills the available space.

    public RatioFrameLayout(Context context) {
        super(context);
    }

    public RatioFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setRatio(float ratio) {
        if (this.ratio != ratio) {
            this.ratio = ratio;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int maxWidth = MeasureSpec.getSize(widthSpec);
        int maxHeight = MeasureSpec.getSize(heightSpec);
        if (ratio <= 0 || maxWidth == 0 || maxHeight == 0) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        int width = maxWidth;
        int height = Math.round(width / ratio);
        if (height > maxHeight) {
            height = maxHeight;
            width = Math.round(height * ratio);
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
