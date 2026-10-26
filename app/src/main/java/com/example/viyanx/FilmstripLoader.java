package com.example.viyanx;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Pulls evenly spaced frames out of a video, off the main thread, for the timeline strip. */
final class FilmstripLoader {

    interface Callback {
        /** Runs on the main thread, once per frame, as each one is ready. */
        void onFrame(int index, Bitmap frame);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();

    /** Starts a new load. Any load still running is abandoned. */
    void load(Context context, Uri uri, long durationMs, int count, int heightPx,
            Callback callback) {
        final int mine = generation.incrementAndGet();
        final Context app = context.getApplicationContext();
        if (count <= 0 || durationMs <= 0) {
            return;
        }
        executor.execute(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(app, uri);
                for (int i = 0; i < count && generation.get() == mine; i++) {
                    long atUs = (long) ((i + 0.5) / count * durationMs * 1000);
                    Bitmap raw = retriever.getFrameAtTime(
                            atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (raw == null) {
                        continue;
                    }
                    final Bitmap frame = scaleToHeight(raw, heightPx);
                    final int index = i;
                    main.post(() -> {
                        if (generation.get() == mine) {
                            callback.onFrame(index, frame);
                        }
                    });
                }
            } catch (RuntimeException e) {
                // The strip just stays empty; trimming still works without it.
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                    // Nothing useful to do.
                }
            }
        });
    }

    /** Abandons any load in progress and stops the worker. */
    void shutdown() {
        generation.incrementAndGet();
        executor.shutdownNow();
    }

    private static Bitmap scaleToHeight(Bitmap raw, int heightPx) {
        if (raw.getHeight() <= heightPx) {
            return raw;
        }
        int width = Math.max(1, Math.round(raw.getWidth() * (heightPx / (float) raw.getHeight())));
        Bitmap scaled = Bitmap.createScaledBitmap(raw, width, heightPx, true);
        if (scaled != raw) {
            raw.recycle();
        }
        return scaled;
    }
}
