package com.example.viyanx;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Reads a video's name, length and a preview frame. Slow for big files: call off the main thread. */
final class VideoProbe {

    private static final int THUMB_WIDTH = 480;

    final long durationMs; // -1 when unknown.
    final Bitmap frame; // null when no frame could be read.

    private VideoProbe(long durationMs, Bitmap frame) {
        this.durationMs = durationMs;
        this.frame = frame;
    }

    static VideoProbe read(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        long duration = -1;
        Bitmap frame = null;
        try {
            retriever.setDataSource(context, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value != null) {
                duration = Long.parseLong(value);
            }
            long atUs = duration > 0 ? Math.min(1_000_000L, duration * 500L) : 0;
            Bitmap raw = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            frame = scaleDown(raw);
        } catch (RuntimeException ignored) {
            // Leave whatever was read; callers handle unknown length and a missing frame.
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing useful to do.
            }
        }
        return new VideoProbe(duration, frame);
    }

    /** Width over height as the video is shown (after rotation), or 0 if it can't be read. */
    static float displayAspect(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            float w = Float.parseFloat(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            float h = Float.parseFloat(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int degrees = rotation == null ? 0 : Integer.parseInt(rotation);
            return degrees == 90 || degrees == 270 ? h / w : w / h;
        } catch (RuntimeException e) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing useful to do.
            }
        }
    }

    static String displayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(
                uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // Fall through to null.
        }
        return null;
    }

    /** True if the video can still be opened, for example after its permission was lost. */
    static boolean canOpen(Context context, Uri uri) {
        try (android.os.ParcelFileDescriptor fd =
                     context.getContentResolver().openFileDescriptor(uri, "r")) {
            return fd != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static boolean saveJpeg(Bitmap bitmap, File target) {
        try (OutputStream out = new FileOutputStream(target)) {
            return bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            return false;
        }
    }

    private static Bitmap scaleDown(Bitmap raw) {
        if (raw == null || raw.getWidth() <= THUMB_WIDTH) {
            return raw;
        }
        int height = Math.round(raw.getHeight() * (THUMB_WIDTH / (float) raw.getWidth()));
        Bitmap scaled = Bitmap.createScaledBitmap(raw, THUMB_WIDTH, Math.max(1, height), true);
        if (scaled != raw) {
            raw.recycle();
        }
        return scaled;
    }
}
