package com.example.viyanx;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exports the edit as an MP4. The segments of the project become one sequence of clipped media
 * items for Media3 Transformer, in timeline order, so deleted ranges are never in the output.
 * Success is only reported once the file exists, reads back with a sensible length, and has been
 * saved to the gallery.
 */
public class ExportActivity extends ComponentActivity {

    private static final long PROGRESS_MILLIS = 250;
    private static final String GALLERY_FOLDER = "Movies/VIYANX";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Project project;
    private EditTimeline timeline;
    private AspectRatio ratio;

    private Transformer transformer;
    private File tempFile;
    private boolean exporting;

    private TextView actionButton;
    private TextView openButton;
    private TextView backButton;
    private TextView status;
    private ProgressBar progress;
    private Uri savedUri;

    private final ProgressHolder progressHolder = new ProgressHolder();
    private final Runnable pollProgress = new Runnable() {
        @Override
        public void run() {
            if (!exporting || transformer == null) {
                return;
            }
            if (transformer.getProgress(progressHolder)
                    == Transformer.PROGRESS_STATE_AVAILABLE) {
                showProgress(progressHolder.progress);
            }
            handler.postDelayed(this, PROGRESS_MILLIS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export);

        final View root = findViewById(R.id.export_root);
        final int base = root.getPaddingLeft();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = SystemInsets.safe(this, insets);
            v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom);
            return insets;
        });

        actionButton = findViewById(R.id.export_action);
        openButton = findViewById(R.id.export_open);
        backButton = findViewById(R.id.export_back);
        status = findViewById(R.id.export_status);
        progress = findViewById(R.id.export_progress);

        findViewById(R.id.export_back_icon).setOnClickListener(v -> getOnBackPressedDispatcher()
                .onBackPressed());
        backButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        actionButton.setOnClickListener(v -> startExport());
        openButton.setOnClickListener(v -> openSaved());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (exporting) {
                    confirmCancel();
                } else {
                    finish();
                }
            }
        });

        actionButton.setEnabled(false);
        actionButton.setAlpha(0.4f);
        final String projectId = getIntent().getStringExtra(HomeActivity.EXTRA_PROJECT_ID);
        io.execute(() -> {
            final Project loaded = projectId == null ? null
                    : ProjectStore.get(this).find(projectId);
            final boolean canOpen = loaded != null
                    && VideoProbe.canOpen(this, Uri.parse(loaded.uri));
            runOnUiThread(() -> {
                if (!isDestroyed()) {
                    onProjectLoaded(loaded, canOpen);
                }
            });
        });
    }

    private void onProjectLoaded(Project loaded, boolean canOpen) {
        if (loaded == null) {
            showStatus(getString(R.string.export_no_project));
            return;
        }
        project = loaded;
        timeline = loaded.loadTimeline(loaded.durationMs);
        ratio = AspectRatio.fromId(loaded.ratio);
        ((TextView) findViewById(R.id.export_source)).setText(
                getString(R.string.export_summary_source, loaded.name));
        ((TextView) findViewById(R.id.export_clips)).setText(
                getString(R.string.export_summary_clips, timeline.size()));
        ((TextView) findViewById(R.id.export_length)).setText(getString(
                R.string.export_summary_length,
                DurationFormat.formatPrecise(timeline.durationMs())));
        ((TextView) findViewById(R.id.export_size)).setText(
                getString(R.string.export_summary_size, getString(ratio.labelRes)));
        if (timeline.isEmpty()) {
            showStatus(getString(R.string.export_empty_project));
            return;
        }
        if (!canOpen) {
            showStatus(getString(R.string.export_unreadable));
            return;
        }
        actionButton.setEnabled(true);
        actionButton.setAlpha(1f);
    }

    // ---- Export ------------------------------------------------------------------------

    private void startExport() {
        if (exporting || project == null || timeline == null || timeline.isEmpty()) {
            return; // An empty or missing project can never be exported.
        }
        File dir = new File(getCacheDir(), "exports");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            fail(getString(R.string.export_failed, "no space to work in"));
            return;
        }
        tempFile = new File(dir, "export_" + System.currentTimeMillis() + ".mp4");

        Composition composition = buildComposition();
        transformer = new Transformer.Builder(this)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition c, ExportResult result) {
                        onTransformed();
                    }

                    @Override
                    public void onError(Composition c, ExportResult result,
                            ExportException exception) {
                        fail(getString(R.string.export_failed, describe(exception)));
                    }
                })
                .build();

        exporting = true;
        savedUri = null;
        ExportTracker.start(project.name);
        actionButton.setVisibility(View.GONE);
        openButton.setVisibility(View.GONE);
        backButton.setText(R.string.export_cancel_confirm);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        showStatus(getString(R.string.export_working));
        try {
            transformer.start(composition, tempFile.getAbsolutePath());
        } catch (RuntimeException e) {
            fail(getString(R.string.export_failed, describe(e)));
            return;
        }
        handler.post(pollProgress);
    }

    /** The retained segments, in timeline order, as one sequence. Nothing else is included. */
    private Composition buildComposition() {
        List<Effect> video = new ArrayList<>();
        if (ratio != AspectRatio.ORIGINAL) {
            // Fit inside the chosen shape with bars, the same way the preview frames the video.
            video.add(Presentation.createForAspectRatio(ratio.value,
                    Presentation.LAYOUT_SCALE_TO_FIT));
        }
        Effects effects = new Effects(Collections.emptyList(), video);
        EditedMediaItemSequence.Builder sequence = new EditedMediaItemSequence.Builder();
        for (Segment segment : timeline.segments()) {
            MediaItem item = new MediaItem.Builder()
                    .setUri(Uri.parse(project.uri))
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(segment.startMs)
                            .setEndPositionMs(segment.endMs)
                            .build())
                    .build();
            sequence.addItem(new EditedMediaItem.Builder(item).setEffects(effects).build());
        }
        return new Composition.Builder(sequence.build()).build();
    }

    /** The encoder finished. Check the file, then save it; only then is it a success. */
    private void onTransformed() {
        exporting = false;
        handler.removeCallbacks(pollProgress);
        showProgress(100);
        final File file = tempFile;
        final long expected = timeline.durationMs();
        final String name = fileName();
        io.execute(() -> {
            long actual = probeDuration(file);
            if (!file.isFile() || file.length() <= 0 || actual <= 0) {
                post(() -> fail(getString(R.string.export_failed_verify)));
                deleteQuietly(file);
                return;
            }
            long tolerance = Math.max(1500, expected / 20);
            if (Math.abs(actual - expected) > tolerance) {
                post(() -> fail(getString(R.string.export_failed_check,
                        DurationFormat.formatPrecise(actual),
                        DurationFormat.formatPrecise(expected))));
                deleteQuietly(file);
                return;
            }
            final long size = file.length();
            Saved saved = save(file, name);
            deleteQuietly(file);
            if (saved == null) {
                post(() -> fail(getString(R.string.export_failed_save)));
                return;
            }
            post(() -> succeed(saved, actual, size));
        });
    }

    private static final class Saved {
        final Uri uri; // Null when saved to app storage.
        final String where;

        Saved(Uri uri, String where) {
            this.uri = uri;
            this.where = where;
        }
    }

    /** Copies the file to the gallery (Android 10+) or the app's own movie folder (older). */
    private Saved save(File file, String name) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, GALLERY_FOLDER);
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return null;
                }
                try (InputStream in = new FileInputStream(file);
                        OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        throw new IOException("no output");
                    }
                    copy(in, out);
                } catch (IOException | RuntimeException e) {
                    getContentResolver().delete(uri, null, null);
                    return null;
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
                return new Saved(uri, name);
            }
            File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
                return null;
            }
            File target = new File(dir, name);
            try (InputStream in = new FileInputStream(file);
                    OutputStream out = new java.io.FileOutputStream(target)) {
                copy(in, out);
            }
            return target.length() > 0 ? new Saved(null, target.getAbsolutePath()) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    private static long probeDuration(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? -1 : Long.parseLong(value);
        } catch (RuntimeException e) {
            return -1;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing useful to do.
            }
        }
    }

    private String fileName() {
        String clean = project.name.replaceAll("[^A-Za-z0-9._-]+", "_");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "VIYANX_" + clean + "_" + stamp + ".mp4";
    }

    private void post(Runnable r) {
        handler.post(() -> {
            if (!isDestroyed()) {
                r.run();
            }
        });
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    // ---- Results -----------------------------------------------------------------------

    private void succeed(Saved saved, long actualMs, long sizeBytes) {
        ExportTracker.finish();
        exporting = false;
        savedUri = saved.uri;
        progress.setVisibility(View.GONE);
        String size = android.text.format.Formatter.formatShortFileSize(this, sizeBytes);
        showStatus(getString(
                saved.uri != null ? R.string.export_saved_gallery : R.string.export_saved_app,
                saved.where, DurationFormat.formatPrecise(actualMs), size));
        actionButton.setVisibility(View.GONE);
        openButton.setVisibility(saved.uri != null ? View.VISIBLE : View.GONE);
        backButton.setText(R.string.export_done_button);
    }

    private void fail(String message) {
        ExportTracker.finish();
        exporting = false;
        handler.removeCallbacks(pollProgress);
        deleteQuietly(tempFile);
        progress.setVisibility(View.GONE);
        showStatus(message);
        actionButton.setText(R.string.export_retry);
        actionButton.setVisibility(View.VISIBLE);
        backButton.setText(R.string.export_back);
    }

    private void showProgress(int percent) {
        progress.setProgress(percent);
        ExportTracker.update(percent);
        status.setText(getString(R.string.export_working) + " "
                + getString(R.string.export_percent, percent));
    }

    private void showStatus(String message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private void openSaved() {
        if (savedUri == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(savedUri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            showStatus(getString(R.string.export_failed, describe(e)));
        }
    }

    private void confirmCancel() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.export_cancel_title)
                .setMessage(R.string.export_cancel_message)
                .setNegativeButton(R.string.export_cancel_keep, null)
                .setPositiveButton(R.string.export_cancel_confirm, (d, w) -> cancelExport())
                .show();
    }

    private void cancelExport() {
        if (exporting && transformer != null) {
            transformer.cancel();
        }
        exporting = false;
        ExportTracker.finish();
        deleteQuietly(tempFile);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (exporting && transformer != null) {
            transformer.cancel();
            exporting = false;
            ExportTracker.finish();
            deleteQuietly(tempFile);
        }
        io.shutdown();
    }
}
