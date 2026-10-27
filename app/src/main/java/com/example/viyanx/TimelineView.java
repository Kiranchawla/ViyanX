package com.example.viyanx;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.ContextCompat;

/**
 * The timeline: one block per segment, in timeline order, drawn with real frames from the video.
 * Tap a block to select it. Drag the handles on the selected block to trim it. Drag anywhere else
 * to move the playhead. The strip always fits the width, so deleting a segment closes the gap.
 */
public class TimelineView extends View {

    interface Listener {
        /** The playhead was moved by touch; time is on the edited timeline. */
        void onSeek(long timelineMs);

        /** A block was tapped. */
        void onSelect(int index);

        /** Called while a handle is dragged. {@code sourceMs} is the edge being moved. */
        void onTrimPreview(EditTimeline preview, int index, boolean isStart, long sourceMs);

        /** The user let go of a handle. */
        void onTrimFinished(EditTimeline result, int index);

        /** The drag was interrupted; the timeline went back to how it was. */
        void onTrimCancelled();

        /** A block was dragged to a new place: it should end up at index {@code to}. */
        void onReorder(int from, int to);
    }

    private static final int NONE = 0;
    private static final int TRIM_START = 1;
    private static final int TRIM_END = 2;
    private static final int SCRUB = 3;
    private static final int REORDER = 4;

    private static final long STEP_MS = 1000;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF slot = new RectF();
    private final RectF block = new RectF();
    private final Rect src = new Rect();
    private final Path clip = new Path();

    private final float density;
    private final float handleWidth;
    private final float touchSlop;
    private final float moveSlop;
    private final float radius;
    private final float bubbleArea;
    private final float gap;

    private EditTimeline timeline = new EditTimeline(java.util.Collections.emptyList());
    private long[] sourceLengths = new long[0];
    private int selected = -1;
    private long playhead;
    private Bitmap[][] frames = new Bitmap[0][];
    private String emptyText = "";
    private Listener listener;

    // Touch state.
    private int mode = NONE;
    private float downX;
    private boolean moved;
    private EditTimeline dragBase;
    private EditTimeline dragPreview;
    private int dragIndex;
    private float dragScale; // Pixels per millisecond, fixed for the whole drag.
    private int reorderFrom;
    private float reorderX;
    private final Runnable startReorder = this::beginReorder;

    public TimelineView(Context context) {
        this(context, null);
    }

    public TimelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        handleWidth = 16 * density;
        touchSlop = 28 * density;
        moveSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        radius = 8 * density;
        bubbleArea = 26 * density;
        gap = 1 * density;
        textPaint.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(Paint.Align.CENTER);
        setFocusable(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setEmptyText(String text) {
        emptyText = text;
    }

    /** Length of each source video, by source number; says which frame belongs where. */
    void setSourceLengths(long[] lengths) {
        sourceLengths = lengths.clone();
        if (frames.length < lengths.length) {
            frames = java.util.Arrays.copyOf(frames, lengths.length);
        }
        invalidate();
    }

    void setTimeline(EditTimeline value, int selectedIndex) {
        timeline = value;
        selected = selectedIndex < value.size() ? selectedIndex : -1;
        invalidate();
    }

    void setSelected(int index) {
        selected = index >= 0 && index < timeline.size() ? index : -1;
        invalidate();
    }

    void setPlayhead(long timelineMs) {
        long clamped = Math.max(0, Math.min(timeline.durationMs(), timelineMs));
        if (playhead != clamped) {
            playhead = clamped;
            invalidate();
        }
    }

    // ---- Frames ------------------------------------------------------------------------

    /** How many frames to fetch across the whole source, a little denser than the strip needs. */
    int suggestedFrameCount() {
        float trackWidth = trackRight() - trackLeft();
        float slotWidth = (getHeight() - bubbleArea) * 0.8f;
        if (trackWidth <= 0 || slotWidth <= 0) {
            return 0;
        }
        return Math.max(8, Math.min(48, (int) Math.ceil(trackWidth / slotWidth) * 2));
    }

    int trackHeightPx() {
        return Math.max(1, Math.round(getHeight() - bubbleArea));
    }

    void prepareFrames(int source, int count) {
        if (source < 0) {
            return;
        }
        if (frames.length <= source) {
            frames = java.util.Arrays.copyOf(frames, source + 1);
        }
        frames[source] = new Bitmap[Math.max(0, count)];
        invalidate();
    }

    void setFrame(int source, int index, Bitmap bitmap) {
        if (source >= 0 && source < frames.length && frames[source] != null
                && index >= 0 && index < frames[source].length) {
            frames[source][index] = bitmap;
            invalidate();
        }
    }

    // ---- Geometry ----------------------------------------------------------------------

    private float trackLeft() {
        return 14 * density;
    }

    private float trackRight() {
        return getWidth() - 14 * density;
    }

    private float trackTop() {
        return bubbleArea;
    }

    private float scale() {
        long total = timeline.durationMs();
        return total <= 0 ? 0 : (trackRight() - trackLeft()) / total;
    }

    private float xFor(long timelineMs) {
        return trackLeft() + timelineMs * scale();
    }

    private long msFor(float x) {
        float s = scale();
        if (s <= 0) {
            return 0;
        }
        long ms = Math.round((x - trackLeft()) / s);
        return Math.max(0, Math.min(timeline.durationMs(), ms));
    }

    /** The on-screen rectangle of a block, including how it looks while its handle is dragged. */
    private void blockRect(int index, RectF out) {
        if (mode == TRIM_START || mode == TRIM_END) {
            // While trimming, everything stays where it was and only the dragged block changes,
            // so the layout never moves under the finger.
            float s = dragScale;
            float left = trackLeft() + dragBase.startOf(index) * s;
            float right = left + dragBase.get(index).length() * s;
            if (index == dragIndex) {
                Segment before = dragBase.get(index);
                Segment now = dragPreview.get(index);
                if (mode == TRIM_START) {
                    left += (now.startMs - before.startMs) * s;
                } else {
                    right += (now.endMs - before.endMs) * s;
                }
            }
            out.set(left, trackTop(), right, getHeight());
            return;
        }
        float s = scale();
        float left = trackLeft() + timeline.startOf(index) * s;
        out.set(left, trackTop(), left + timeline.get(index).length() * s, getHeight());
    }

    private EditTimeline shown() {
        return (mode == TRIM_START || mode == TRIM_END) ? dragBase : timeline;
    }

    // ---- Drawing -----------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        int accent = ContextCompat.getColor(getContext(), R.color.vx_timeline_accent);
        if (timeline.isEmpty()) {
            drawEmpty(canvas);
            return;
        }
        EditTimeline t = shown();
        int count = t.size();

        // Unselected blocks first, then the selected one on top of its neighbours.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < count; i++) {
                boolean isSelected = i == selected;
                if (isSelected == (pass == 0)) {
                    continue;
                }
                blockRect(i, block);
                block.inset(gap, 0);
                drawBlock(canvas, i, block, isSelected, accent);
            }
        }

        if (mode == REORDER) {
            drawReorder(canvas, accent);
        }

        // Playhead.
        float x = mode == TRIM_START || mode == TRIM_END
                ? xFor(playhead) : xFor(Math.min(playhead, timeline.durationMs()));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        float w = 2 * density;
        canvas.drawRect(x - w / 2, trackTop() - 4 * density, x + w / 2, getHeight(), paint);
        canvas.drawCircle(x, trackTop() - 6 * density, 5 * density, paint);

        // Time bubble while dragging.
        if (mode == TRIM_START || mode == TRIM_END) {
            blockRect(dragIndex, rect);
            float cx = mode == TRIM_START ? rect.left + handleWidth / 2
                    : rect.right - handleWidth / 2;
            drawBubble(canvas, cx,
                    DurationFormat.formatPrecise(dragPreview.get(dragIndex).length()), accent);
        } else if (mode == SCRUB) {
            drawBubble(canvas, x, DurationFormat.formatPrecise(playhead), accent);
        }
    }

    /** The lifted block following the finger, and a marker where it will land. */
    private void drawReorder(Canvas canvas, int accent) {
        blockRect(reorderFrom, block);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99000000); // The block's old place looks empty while it is lifted.
        canvas.drawRect(block, paint);
        float width = block.width();
        float left = Math.max(trackLeft(), Math.min(trackRight() - width,
                reorderX - width / 2));
        block.set(left, trackTop(), left + width, getHeight());
        drawBlock(canvas, reorderFrom, block, true, accent);

        float marker = markerX(reorderTarget());
        float w = 3 * density;
        paint.setColor(accent);
        canvas.drawRect(marker - w / 2, trackTop() - 4 * density, marker + w / 2,
                getHeight(), paint);
    }

    /** Where the lifted block would end up: how many of the other blocks lie left of the finger. */
    private int reorderTarget() {
        float s = scale();
        int target = 0;
        for (int i = 0; i < timeline.size(); i++) {
            if (i == reorderFrom) {
                continue;
            }
            float center = trackLeft() + (timeline.startOf(i) + timeline.get(i).length() / 2f) * s;
            if (center < reorderX) {
                target++;
            }
        }
        return target;
    }

    /** The x position of the gap before the {@code target}-th block of those that stay put. */
    private float markerX(int target) {
        float s = scale();
        int seen = 0;
        float x = trackLeft();
        for (int i = 0; i < timeline.size() && seen < target; i++) {
            if (i == reorderFrom) {
                continue;
            }
            x = trackLeft() + timeline.endOf(i) * s;
            seen++;
        }
        return x;
    }

    private void beginReorder() {
        if (mode != SCRUB || moved || timeline.size() < 2) {
            return;
        }
        int index = timeline.indexAt(msFor(downX));
        if (index < 0) {
            return;
        }
        mode = REORDER;
        reorderFrom = index;
        reorderX = downX;
        selected = index;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (listener != null) {
            listener.onSelect(index);
        }
        invalidate();
    }

    private void drawBlock(Canvas canvas, int index, RectF r, boolean isSelected, int accent) {
        if (r.width() <= 0) {
            return;
        }
        float corner = Math.min(radius, r.width() / 2);
        clip.reset();
        clip.addRoundRect(r, corner, corner, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ContextCompat.getColor(getContext(), R.color.vx_surface_variant));
        canvas.drawRect(r, paint);
        EditTimeline t = shown();
        Segment segment = ((mode == TRIM_START || mode == TRIM_END) && index == dragIndex)
                ? dragPreview.get(index) : t.get(index);
        drawFrames(canvas, r, segment);
        if (!isSelected) {
            paint.setColor(0x59000000); // Dim, so the selected block stands out.
            canvas.drawRect(r, paint);
        }
        canvas.restore();

        if (isSelected) {
            paint.setColor(accent);
            float line = 3 * density;
            canvas.drawRect(r.left, r.top, r.right, r.top + line, paint);
            canvas.drawRect(r.left, r.bottom - line, r.right, r.bottom, paint);
            float hw = Math.min(handleWidth, r.width() / 2);
            rect.set(r.left, r.top, r.left + hw, r.bottom);
            canvas.drawRoundRect(rect, corner, corner, paint);
            rect.set(r.right - hw, r.top, r.right, r.bottom);
            canvas.drawRoundRect(rect, corner, corner, paint);
            drawGrip(canvas, r.left + hw / 2, r.top, r.bottom);
            drawGrip(canvas, r.right - hw / 2, r.top, r.bottom);
        }
    }

    /** Draws frames across the block, each taken from the part of the source shown there. */
    private void drawFrames(Canvas canvas, RectF r, Segment segment) {
        if (segment.source < 0 || segment.source >= frames.length
                || segment.source >= sourceLengths.length || frames[segment.source] == null) {
            return;
        }
        Bitmap[] strip = frames[segment.source];
        long sourceDuration = sourceLengths[segment.source];
        int n = strip.length;
        if (n == 0 || sourceDuration <= 0) {
            return;
        }
        float tile = Math.max(1, (getHeight() - bubbleArea) * 0.8f);
        int tiles = Math.max(1, (int) Math.ceil(r.width() / tile));
        float tileWidth = r.width() / tiles;
        for (int i = 0; i < tiles; i++) {
            float center = (i + 0.5f) / tiles;
            long sourceMs = segment.startMs + (long) (center * segment.length());
            int index = (int) Math.min(n - 1, Math.max(0, sourceMs * n / sourceDuration));
            Bitmap b = strip[index];
            if (b == null) {
                continue;
            }
            slot.set(r.left + i * tileWidth, r.top, r.left + (i + 1) * tileWidth, r.bottom);
            float slotAspect = slot.width() / slot.height();
            float bitmapAspect = b.getWidth() / (float) b.getHeight();
            if (bitmapAspect > slotAspect) {
                int w = Math.round(b.getHeight() * slotAspect);
                int x = (b.getWidth() - w) / 2;
                src.set(x, 0, x + w, b.getHeight());
            } else {
                int h = Math.round(b.getWidth() / slotAspect);
                int y = (b.getHeight() - h) / 2;
                src.set(0, y, b.getWidth(), y + h);
            }
            canvas.drawBitmap(b, src, slot, null);
        }
    }

    private void drawEmpty(Canvas canvas) {
        rect.set(trackLeft(), trackTop(), trackRight(), getHeight());
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ContextCompat.getColor(getContext(), R.color.vx_surface_variant));
        canvas.drawRoundRect(rect, radius, radius, paint);
        textPaint.setColor(ContextCompat.getColor(getContext(), R.color.vx_text_secondary));
        float baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2;
        canvas.drawText(emptyText, rect.centerX(), baseline, textPaint);
    }

    private void drawGrip(Canvas canvas, float cx, float top, float bottom) {
        paint.setColor(ContextCompat.getColor(getContext(), R.color.vx_timeline_on_accent));
        float w = 2.5f * density;
        float h = (bottom - top) * 0.35f;
        float y = (top + bottom - h) / 2;
        canvas.drawRoundRect(cx - w / 2, y, cx + w / 2, y + h, w / 2, w / 2, paint);
    }

    private void drawBubble(Canvas canvas, float cx, String text, int accent) {
        float padX = 8 * density;
        float width = textPaint.measureText(text) + padX * 2;
        float height = 22 * density;
        float left = Math.max(0, Math.min(getWidth() - width, cx - width / 2));
        rect.set(left, 0, left + width, height);
        paint.setColor(accent);
        canvas.drawRoundRect(rect, height / 2, height / 2, paint);
        textPaint.setColor(ContextCompat.getColor(getContext(), R.color.vx_timeline_on_accent));
        float baseline = height / 2 - (textPaint.descent() + textPaint.ascent()) / 2;
        canvas.drawText(text, left + width / 2, baseline, textPaint);
    }

    // ---- Touch -------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (timeline.isEmpty() || !isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(event);
            case MotionEvent.ACTION_MOVE:
                onMove(event);
                return true;
            case MotionEvent.ACTION_UP:
                onUp(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                onUp(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean onDown(MotionEvent event) {
        float x = event.getX();
        downX = x;
        moved = false;
        if (selected >= 0 && selected < timeline.size()) {
            blockRect(selected, block);
            float toStart = Math.abs(x - (block.left + handleWidth / 2));
            float toEnd = Math.abs(x - (block.right - handleWidth / 2));
            if (Math.min(toStart, toEnd) <= touchSlop) {
                // In a very short block both handles are close; pick the nearer one.
                boolean start = toStart < toEnd || (toStart == toEnd && x < block.centerX());
                dragBase = timeline;
                dragPreview = timeline;
                dragIndex = selected;
                dragScale = scale();
                mode = start ? TRIM_START : TRIM_END;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
        }
        if (event.getY() < trackTop() - 8 * density) {
            return false;
        }
        mode = SCRUB;
        getParent().requestDisallowInterceptTouchEvent(true);
        scrubTo(x);
        postDelayed(startReorder, ViewConfiguration.getLongPressTimeout());
        return true;
    }

    private void onMove(MotionEvent event) {
        float x = event.getX();
        if (mode == TRIM_START || mode == TRIM_END) {
            if (dragScale <= 0) {
                return;
            }
            Segment base = dragBase.get(dragIndex);
            long delta = Math.round((x - downX) / dragScale);
            boolean isStart = mode == TRIM_START;
            EditTimeline preview = isStart
                    ? dragBase.trimStart(dragIndex, base.startMs + delta)
                    : dragBase.trimEnd(dragIndex, base.endMs + delta, sourceLengths);
            if (!preview.equals(dragPreview)) {
                dragPreview = preview;
                Segment now = preview.get(dragIndex);
                invalidate();
                if (listener != null) {
                    listener.onTrimPreview(preview, dragIndex, isStart,
                            isStart ? now.startMs : now.endMs);
                }
            }
        } else if (mode == SCRUB) {
            if (Math.abs(x - downX) > moveSlop) {
                moved = true;
                removeCallbacks(startReorder);
            }
            scrubTo(x);
        } else if (mode == REORDER) {
            reorderX = x;
            invalidate();
        }
    }

    private void onUp(boolean completed) {
        int finished = mode;
        mode = NONE;
        removeCallbacks(startReorder);
        if (finished == REORDER) {
            int to = reorderTarget();
            invalidate();
            if (completed && to != reorderFrom && listener != null) {
                listener.onReorder(reorderFrom, to);
            }
            return;
        }
        if (finished == TRIM_START || finished == TRIM_END) {
            EditTimeline result = completed ? dragPreview : dragBase;
            int index = dragIndex;
            dragBase = null;
            dragPreview = null;
            timeline = result;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
            if (listener != null) {
                if (completed) {
                    listener.onTrimFinished(result, index);
                } else {
                    listener.onTrimCancelled();
                }
            }
        } else if (finished == SCRUB) {
            if (completed && !moved) {
                int index = timeline.indexAt(msFor(downX));
                if (index >= 0) {
                    selected = index;
                    performClick();
                    if (listener != null) {
                        listener.onSelect(index);
                    }
                }
            }
            invalidate();
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void scrubTo(float x) {
        playhead = msFor(x);
        invalidate();
        if (listener != null) {
            listener.onSeek(playhead);
        }
    }

    // ---- Accessibility: move the playhead a second at a time, and step between clips ----

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (!timeline.isEmpty()) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    R.id.action_previous_clip, getContext().getString(R.string.previous_clip)));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    R.id.action_next_clip, getContext().getString(R.string.next_clip)));
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (!timeline.isEmpty()) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                long delta = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        ? STEP_MS : -STEP_MS;
                playhead = Math.max(0, Math.min(timeline.durationMs(), playhead + delta));
                invalidate();
                if (listener != null) {
                    listener.onSeek(playhead);
                }
                return true;
            }
            if (action == R.id.action_previous_clip || action == R.id.action_next_clip) {
                int step = action == R.id.action_next_clip ? 1 : -1;
                int index = Math.max(0, Math.min(timeline.size() - 1,
                        (selected < 0 ? timeline.indexAt(playhead) : selected) + step));
                selected = index;
                invalidate();
                if (listener != null) {
                    listener.onSelect(index);
                }
                return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
