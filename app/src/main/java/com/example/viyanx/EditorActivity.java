package com.example.viyanx;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Editor: preview the video, cut it into segments on a timeline (split, trim, delete), pick an
 * output size, and move on to export. The edit is an ordered list of segments of the source video,
 * stored on the project; the original video is never changed. Preview plays one clipped media item
 * per segment, so deleted ranges are skipped, and export builds its sequence from the same list.
 */
public class EditorActivity extends ComponentActivity {

    private static final String STATE_POSITION = "position";
    private static final String STATE_HISTORY = "history";
    private static final String STATE_TOOL = "tool";
    private static final String STATE_SELECTED = "selected";
    private static final long TICK_MILLIS = 50;
    private static final long MESSAGE_MILLIS = 3000;
    private static final long END_MARGIN_MS = 50;
    private static final int FILMSTRIP_RETRIES = 20;

    private static final int TOOL_TRIM = 0;
    private static final int TOOL_RATIO = 1;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<FilmstripLoader> filmstrips = new ArrayList<>(); // One per source video.
    private final EditHistory history = new EditHistory();
    private final Map<AspectRatio, TextView> ratioChips = new EnumMap<>(AspectRatio.class);

    // Registered at construction so it survives rotation while the picker is open.
    private final ActivityResultLauncher<PickVisualMediaRequest> pickClip =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(), this::onClipPicked);

    private ProjectStore store;
    private SharedPreferences prefs;
    private Project project;
    private boolean readable = true;

    private ExoPlayer player;
    private PlayerView playerView;
    private RatioFrameLayout stage;
    private View playButton;
    private TextView timeText;
    private TextView startValue;
    private TextView selectedValue;
    private TextView endValue;
    private TextView continueButton;
    private TextView selectionInfo;
    private View splitButton;
    private View deleteButton;
    private View setStartButton;
    private View setEndButton;
    private View resetButton;
    private View duplicateButton;
    private View addClipButton;
    private View moveLeftButton;
    private View moveRightButton;
    private View undoButton;
    private View redoButton;
    private View messageBar;
    private TextView messageText;
    private TimelineView timelineView;

    private boolean started;
    private long resumePosition = -1;
    private long sourceMs = -1; // Length of the source video; -1 until known.
    private EditTimeline timeline = new EditTimeline(java.util.Collections.emptyList());
    private String selectedId; // The selected segment, kept by id so edits don't lose it.
    private long playhead; // Timeline position, used whenever the player isn't running.
    private boolean trimPreview; // True while a handle drag shows frames of the whole source.
    private boolean trimIsStart;
    private int trimSource = -1; // Source video the trim preview is showing.
    private Uri pendingClip; // Picked before the project finished loading.
    private boolean addingClip;
    private boolean playlistReady; // True once the player holds one item per segment.
    private float videoRatio; // Width over height of the video itself, 0 until known.
    private int tool = TOOL_TRIM;
    private int filmstripTries;

    private final Runnable hideMessage = () -> messageBar.setVisibility(View.GONE);
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            onTick();
            if (player != null && player.isPlaying()) {
                handler.postDelayed(this, TICK_MILLIS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        final View root = findViewById(R.id.editor_root);
        final int base = root.getPaddingLeft();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = SystemInsets.safe(this, insets);
            v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom);
            return insets;
        });

        store = ProjectStore.get(this);
        prefs = getSharedPreferences(HomeActivity.PREFS, MODE_PRIVATE);
        if (savedInstanceState != null) {
            resumePosition = savedInstanceState.getLong(STATE_POSITION, -1);
            tool = savedInstanceState.getInt(STATE_TOOL, TOOL_TRIM);
            selectedId = savedInstanceState.getString(STATE_SELECTED);
            history.restore(savedInstanceState.getStringArrayList(STATE_HISTORY));
        }

        bindViews();
        bindActions();
        buildRatioChips();
        timelineView.setEmptyText(getString(R.string.timeline_empty));
        updateUndoRedo();
        setControlsEnabled(false); // Until the project has loaded.

        final String projectId = getIntent().getStringExtra(HomeActivity.EXTRA_PROJECT_ID);
        if (projectId == null) {
            missingProject();
            return;
        }

        // Reading the project and checking the video can be slow, so keep it off the main thread.
        io.execute(() -> {
            final Project loaded = store.find(projectId);
            final boolean canOpen = loaded != null
                    && VideoProbe.canOpen(this, Uri.parse(loaded.uri));
            if (loaded != null) {
                loaded.lastEdited = System.currentTimeMillis();
                store.update(loaded);
            }
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (loaded == null) {
                    missingProject();
                    return;
                }
                project = loaded;
                readable = canOpen;
                // Remember the open project so Home can offer to resume after a crash or kill.
                prefs.edit().putString(HomeActivity.PREF_OPEN_PROJECT, loaded.id).apply();
                show(loaded);
                if (started && readable) {
                    startPlayer();
                }
            });
        });
    }

    private void bindViews() {
        playerView = findViewById(R.id.player_view);
        stage = findViewById(R.id.stage);
        playButton = findViewById(R.id.btn_play);
        timeText = findViewById(R.id.time_text);
        startValue = findViewById(R.id.trim_start_value);
        selectedValue = findViewById(R.id.trim_selected_value);
        endValue = findViewById(R.id.trim_end_value);
        continueButton = findViewById(R.id.btn_continue);
        selectionInfo = findViewById(R.id.selection_info);
        splitButton = findViewById(R.id.btn_split);
        deleteButton = findViewById(R.id.btn_delete);
        setStartButton = findViewById(R.id.btn_set_start);
        setEndButton = findViewById(R.id.btn_set_end);
        resetButton = findViewById(R.id.btn_reset);
        duplicateButton = findViewById(R.id.btn_duplicate);
        addClipButton = findViewById(R.id.btn_add_clip);
        moveLeftButton = findViewById(R.id.btn_move_left);
        moveRightButton = findViewById(R.id.btn_move_right);
        undoButton = findViewById(R.id.btn_undo);
        redoButton = findViewById(R.id.btn_redo);
        messageBar = findViewById(R.id.msg_bar);
        messageText = findViewById(R.id.msg_text);
        timelineView = findViewById(R.id.timeline);
    }

    private void bindActions() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        playButton.setOnClickListener(v -> togglePlay());
        stage.setOnClickListener(v -> togglePlay());
        undoButton.setOnClickListener(v -> applyHistory(history.undo(), R.string.undo_done));
        redoButton.setOnClickListener(v -> applyHistory(history.redo(), R.string.redo_done));
        findViewById(R.id.btn_export_top).setOnClickListener(v -> openExport());
        continueButton.setOnClickListener(v -> openExport());

        findViewById(R.id.tool_trim).setOnClickListener(v -> selectTool(TOOL_TRIM));
        findViewById(R.id.tool_ratio).setOnClickListener(v -> selectTool(TOOL_RATIO));
        findViewById(R.id.tool_text).setOnClickListener(v -> showMessage(
                getString(R.string.nav_coming_soon, getString(R.string.tool_text_label))));
        findViewById(R.id.tool_more).setOnClickListener(v -> showMessage(
                getString(R.string.nav_coming_soon, getString(R.string.tool_more_label))));

        splitButton.setOnClickListener(v -> splitAtPlayhead());
        deleteButton.setOnClickListener(v -> deleteSelected());
        setStartButton.setOnClickListener(v -> setEdgeFromPlayhead(true));
        setEndButton.setOnClickListener(v -> setEdgeFromPlayhead(false));
        resetButton.setOnClickListener(v -> resetEdit());
        duplicateButton.setOnClickListener(v -> duplicateSelected());
        addClipButton.setOnClickListener(v -> addClip());
        moveLeftButton.setOnClickListener(v -> moveSelected(-1));
        moveRightButton.setOnClickListener(v -> moveSelected(1));

        timelineView.setListener(new TimelineView.Listener() {
            @Override
            public void onSeek(long timelineMs) {
                if (player != null) {
                    player.pause();
                }
                seekToTimeline(timelineMs);
                updateTime();
            }

            @Override
            public void onSelect(int index) {
                selectedId = timeline.get(index).id;
                refreshEditUi();
            }

            @Override
            public void onTrimPreview(EditTimeline preview, int index, boolean isStart,
                    long sourcePositionMs) {
                showTrimFrame(preview.get(index).source, sourcePositionMs);
                trimIsStart = isStart;
                updateInfo(preview, index);
                timeText.setText(getString(R.string.time_position,
                        DurationFormat.formatPrecise(playheadFor(preview, index, isStart)),
                        DurationFormat.formatPrecise(preview.durationMs())));
            }

            @Override
            public void onTrimFinished(EditTimeline result, int index) {
                finishTrim(result, index);
            }

            @Override
            public void onReorder(int from, int to) {
                moveClip(from, to);
            }

            @Override
            public void onTrimCancelled() {
                reloadPlaylist(playhead);
                refreshEditUi();
            }
        });
    }

    private void buildRatioChips() {
        android.view.ViewGroup row = findViewById(R.id.ratio_chips);
        for (AspectRatio ratio : AspectRatio.values()) {
            TextView chip = (TextView) getLayoutInflater().inflate(R.layout.item_chip, row, false);
            chip.setBackgroundResource(R.drawable.bg_chip_choice);
            chip.setTextColor(ContextCompat.getColorStateList(this, R.color.chip_text));
            chip.setText(ratio.labelRes);
            chip.setContentDescription(getString(R.string.project_meta,
                    getString(ratio.labelRes), getString(ratio.hintRes)));
            chip.setOnClickListener(v -> selectRatio(ratio));
            row.addView(chip);
            ratioChips.put(ratio, chip);
        }
    }

    private void missingProject() {
        Toast.makeText(this, R.string.editor_missing_project, Toast.LENGTH_LONG).show();
        finish();
    }

    private void show(Project p) {
        ((TextView) findViewById(R.id.editor_name)).setText(p.name);
        applyRatio(AspectRatio.fromId(p.ratio));
        findViewById(R.id.editor_warning).setVisibility(readable ? View.GONE : View.VISIBLE);
        if (p.durationMs > 0) {
            applySource(p.durationMs);
        } else {
            updateTime();
        }
        setControlsEnabled(readable && sourceMs > 0);
        selectTool(tool);
        updateUndoRedo();
        showSaved(p.lastEdited);
        if (pendingClip != null && sourceMs > 0) {
            Uri uri = pendingClip;
            pendingClip = null;
            onClipPicked(uri);
        }
    }

    private void showSaved(long when) {
        TextView saved = findViewById(R.id.editor_saved);
        saved.setText(getString(R.string.editor_saved,
                DateFormat.getTimeFormat(this).format(new Date(when))));
        saved.setVisibility(View.VISIBLE);
    }

    // ---- Player ------------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (project != null && readable) {
            startPlayer();
        }
    }

    private void startPlayer() {
        if (player != null || project == null) {
            return;
        }
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && sourceMs <= 0 && !playlistReady) {
                    // The length wasn't known when the project was made; the player knows now.
                    long d = player.getDuration();
                    if (d != C.TIME_UNSET && d > 0) {
                        project.durationMs = d;
                        applySource(d);
                        setControlsEnabled(readable);
                        saveEdits();
                        reloadPlaylist(0);
                    }
                } else if (state == Player.STATE_ENDED && playlistReady && !trimPreview) {
                    // The end of the edited project: stop there, with the playhead at the end.
                    player.pause();
                    playhead = timeline.durationMs();
                    updateTime();
                }
                updatePlayButton();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayButton();
                handler.removeCallbacks(ticker);
                if (isPlaying) {
                    handler.post(ticker);
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 0 && size.height > 0) {
                    videoRatio = size.width * size.pixelWidthHeightRatio / size.height;
                    applyRatio(AspectRatio.fromId(project.ratio));
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                readable = false;
                TextView warning = findViewById(R.id.editor_warning);
                warning.setText(R.string.player_error);
                warning.setVisibility(View.VISIBLE);
                setControlsEnabled(false);
            }
        });
        if (sourceMs > 0) {
            reloadPlaylist(resumePosition >= 0 ? resumePosition : playhead);
        } else {
            // The length isn't known yet; load the video whole just to find it.
            player.setMediaItem(MediaItem.fromUri(Uri.parse(project.uri)));
            player.prepare();
        }
        updatePlayButton();
    }

    private void releasePlayer() {
        handler.removeCallbacks(ticker);
        if (player != null) {
            playhead = timelinePosition();
            resumePosition = playhead;
            playerView.setPlayer(null);
            player.release();
            player = null;
            playlistReady = false;
            trimPreview = false;
        }
    }

    /** One clipped media item per segment, so the player skips every deleted range. */
    private MediaItem itemFor(Segment segment) {
        return new MediaItem.Builder()
                .setUri(Uri.parse(project.uriOf(segment.source)))
                .setMediaId(segment.id)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(segment.startMs)
                        .setEndPositionMs(segment.endMs)
                        .build())
                .build();
    }

    /** Loads the current segments into the player and puts it at this timeline position. */
    private void reloadPlaylist(long timelineMs) {
        if (player == null || project == null || sourceMs <= 0) {
            return;
        }
        player.pause();
        trimPreview = false;
        if (timeline.isEmpty()) {
            player.clearMediaItems();
            playlistReady = false;
            playhead = 0;
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        for (Segment segment : timeline.segments()) {
            items.add(itemFor(segment));
        }
        long at = Math.max(0, Math.min(timeline.durationMs(), timelineMs));
        int index = timeline.indexAt(at);
        long offset = Math.min(at - timeline.startOf(index), timeline.get(index).length());
        player.setMediaItems(items, index, offset);
        player.prepare();
        playlistReady = true;
        playhead = at;
    }

    /** Where the preview is, on the edited timeline. */
    private long timelinePosition() {
        if (player == null || !playlistReady || trimPreview
                || player.getMediaItemCount() != timeline.size()) {
            return playhead;
        }
        int index = player.getCurrentMediaItemIndex();
        if (index < 0 || index >= timeline.size()) {
            return playhead;
        }
        long inside = Math.max(0, Math.min(timeline.get(index).length(),
                player.getCurrentPosition()));
        return timeline.startOf(index) + inside;
    }

    /** Moves the preview to a timeline position, mapping it to the right segment and source time. */
    private void seekToTimeline(long timelineMs) {
        playhead = Math.max(0, Math.min(timeline.durationMs(), timelineMs));
        if (player == null || !playlistReady || trimPreview || timeline.isEmpty()) {
            return;
        }
        int index = timeline.indexAt(playhead);
        long offset = Math.min(playhead - timeline.startOf(index), timeline.get(index).length());
        player.seekTo(index, offset);
    }

    private void togglePlay() {
        if (player == null || !readable || timeline.isEmpty() || !playlistReady) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            return;
        }
        if (player.getPlaybackState() == Player.STATE_ENDED
                || timelinePosition() >= timeline.durationMs() - END_MARGIN_MS) {
            seekToTimeline(0);
        }
        player.play();
    }

    /** Keeps the clock and playhead current while playing. */
    private void onTick() {
        if (player == null) {
            return;
        }
        playhead = timelinePosition();
        updateTime();
    }

    private void updateTime() {
        long total = timeline.durationMs();
        timeText.setText(getString(R.string.time_position, DurationFormat.formatPrecise(playhead),
                sourceMs > 0 ? DurationFormat.formatPrecise(total)
                        : getString(R.string.editor_unknown)));
        timelineView.setPlayhead(playhead);
        timelineView.setContentDescription(getString(R.string.timeline_description,
                timeline.size(), DurationFormat.formatPrecise(total),
                DurationFormat.formatPrecise(playhead)));
        updateActions();
    }

    private void updatePlayButton() {
        boolean playing = player != null && player.isPlaying();
        ((android.widget.ImageButton) playButton).setImageResource(
                playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playButton.setContentDescription(getString(playing ? R.string.pause : R.string.play));
    }

    // ---- Segments ----------------------------------------------------------------------

    /** Called once the source length is known. Loads the saved segments. */
    private void applySource(long duration) {
        sourceMs = duration;
        timeline = project.loadTimeline(duration);
        if (selectedId == null || timeline.indexOfId(selectedId) < 0) {
            selectedId = timeline.isEmpty() ? null : timeline.get(0).id;
        }
        timelineView.setSourceLengths(project.sourceLengths(duration));
        if (history.isEmpty()) {
            history.reset(currentState());
        }
        playhead = Math.max(0, Math.min(timeline.durationMs(),
                resumePosition >= 0 ? resumePosition : 0));
        refreshEditUi();
        startFilmstrip();
    }

    private int selectedIndex() {
        return selectedId == null ? -1 : timeline.indexOfId(selectedId);
    }

    /** Pushes the timeline, selection and totals into every view that shows them. */
    private void refreshEditUi() {
        int index = selectedIndex();
        timelineView.setTimeline(timeline, index);
        updateInfo(timeline, index);
        updateTime();
        updateUndoRedo();
    }

    private void updateInfo(EditTimeline shown, int index) {
        if (index < 0 || index >= shown.size()) {
            selectionInfo.setText(R.string.selection_none);
            startValue.setText(R.string.editor_none_value);
            selectedValue.setText(R.string.editor_none_value);
            endValue.setText(R.string.editor_none_value);
        } else {
            boolean canSplit = shown.canSplit(index, playhead);
            selectionInfo.setText(getString(
                    canSplit ? R.string.selection_info : R.string.selection_move_playhead,
                    index + 1, shown.size()));
            startValue.setText(DurationFormat.formatPrecise(shown.startOf(index)));
            selectedValue.setText(DurationFormat.formatPrecise(shown.get(index).length()));
            endValue.setText(DurationFormat.formatPrecise(shown.endOf(index)));
        }
        continueButton.setText(sourceMs > 0
                ? getString(R.string.continue_export_with,
                        DurationFormat.formatPrecise(shown.durationMs()))
                : getString(R.string.continue_export));
    }

    /** Split, delete and the set-edge buttons are only on when they would do something. */
    private void updateActions() {
        boolean on = readable && sourceMs > 0;
        int index = selectedIndex();
        boolean hasSelection = on && index >= 0;
        boolean inside = hasSelection && playhead > timeline.startOf(index)
                && playhead < timeline.endOf(index);
        setEnabledLook(splitButton, on && timeline.canSplit(index, playhead));
        setEnabledLook(deleteButton, hasSelection);
        setEnabledLook(setStartButton, inside);
        setEnabledLook(setEndButton, inside);
        setEnabledLook(resetButton, on && isEdited());
        setEnabledLook(duplicateButton, hasSelection);
        setEnabledLook(addClipButton, on);
        setEnabledLook(moveLeftButton, hasSelection && index > 0);
        setEnabledLook(moveRightButton, hasSelection && index < timeline.size() - 1);
        if (!trimPreview) {
            updateInfo(timeline, index);
        }
    }

    private static void setEnabledLook(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.4f);
    }

    /** True if anything has been cut away from the full video. */
    private boolean isEdited() {
        return !(timeline.size() == 1 && timeline.get(0).source == 0
                && timeline.get(0).startMs == 0 && timeline.get(0).endMs == sourceMs);
    }

    private void splitAtPlayhead() {
        int index = selectedIndex();
        if (index < 0) {
            showMessage(getString(R.string.split_select_first));
            return;
        }
        if (player != null) {
            player.pause();
        }
        playhead = timelinePosition();
        if (!timeline.canSplit(index, playhead)) {
            showMessage(getString(R.string.split_not_possible,
                    DurationFormat.formatPrecise(EditTimeline.MIN_SEGMENT_MS)));
            return;
        }
        String rightId = EditTimeline.newId();
        timeline = timeline.split(index, playhead, rightId);
        selectedId = rightId; // The playhead is at its start.
        commitEdit(playhead);
        showMessage(getString(R.string.split_done, DurationFormat.formatPrecise(playhead)));
    }

    private void deleteSelected() {
        int index = selectedIndex();
        if (index < 0) {
            showMessage(getString(R.string.delete_select_first));
            return;
        }
        long at = timeline.startOf(index);
        timeline = timeline.delete(index);
        // Select whatever now sits where the deleted clip was, so the next Delete just works.
        selectedId = timeline.isEmpty() ? null
                : timeline.get(Math.min(index, timeline.size() - 1)).id;
        commitEdit(Math.min(at, timeline.durationMs()));
        showMessage(getString(R.string.delete_done));
    }

    /** "Set start/end": trims the selected clip to the playhead. */
    private void setEdgeFromPlayhead(boolean isStart) {
        int index = selectedIndex();
        if (index < 0 || sourceMs <= 0) {
            return;
        }
        if (player != null) {
            player.pause();
        }
        playhead = timelinePosition();
        if (playhead <= timeline.startOf(index) || playhead >= timeline.endOf(index)) {
            showMessage(getString(R.string.trim_set_start_outside));
            return;
        }
        long source = timeline.toSource(playhead);
        EditTimeline trimmed = isStart ? timeline.trimStart(index, source)
                : timeline.trimEnd(index, source, project.sourceLengths(sourceMs));
        if (trimmed.equals(timeline)) {
            showMessage(getString(R.string.trim_limit,
                    DurationFormat.formatPrecise(EditTimeline.MIN_SEGMENT_MS)));
            return;
        }
        long keep = playhead;
        timeline = trimmed;
        long at = isStart ? timeline.startOf(index) : Math.min(keep, timeline.endOf(index));
        commitEdit(at);
        showMessage(getString(R.string.trimmed_to,
                DurationFormat.formatPrecise(timeline.get(index).length())));
    }

    private void duplicateSelected() {
        int index = selectedIndex();
        if (index < 0) {
            showMessage(getString(R.string.duplicate_select_first));
            return;
        }
        String copyId = EditTimeline.newId();
        timeline = timeline.duplicate(index, copyId);
        selectedId = copyId;
        commitEdit(timeline.startOf(index + 1));
        showMessage(getString(R.string.duplicate_done));
    }

    private void moveSelected(int step) {
        int index = selectedIndex();
        if (index < 0) {
            showMessage(getString(R.string.move_select_first));
            return;
        }
        moveClip(index, index + step);
    }

    /** Puts a clip at a new position. The clip stays selected and the playhead goes to its start. */
    private void moveClip(int from, int to) {
        EditTimeline moved = timeline.move(from, to);
        if (moved.equals(timeline)) {
            refreshEditUi();
            return;
        }
        if (player != null) {
            player.pause();
        }
        timeline = moved;
        selectedId = timeline.get(to).id;
        commitEdit(timeline.startOf(to));
        showMessage(getString(R.string.move_done, to + 1, timeline.size()));
    }

    private void addClip() {
        if (project == null || sourceMs <= 0 || !readable || addingClip) {
            showMessage(getString(R.string.add_clip_wait));
            return;
        }
        if (player != null) {
            player.pause();
        }
        pickClip.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                .build());
    }

    /** A video was picked: read its length off the main thread, then put all of it on the timeline. */
    private void onClipPicked(Uri uri) {
        if (uri == null) {
            return; // Picker closed without a choice.
        }
        if (project == null || sourceMs <= 0) {
            pendingClip = uri; // Added as soon as the project has loaded.
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // Usable this session; a later open may report it as unavailable.
        }
        addingClip = true;
        final String name = VideoProbe.displayName(this, uri);
        io.execute(() -> {
            final long length = VideoProbe.read(this, uri).durationMs;
            runOnUiThread(() -> {
                addingClip = false;
                if (!isDestroyed()) {
                    insertClip(uri, length, name);
                }
            });
        });
    }

    private void insertClip(Uri uri, long length, String name) {
        if (length < EditTimeline.MIN_SEGMENT_MS) {
            showMessage(getString(R.string.add_clip_failed));
            return;
        }
        // Reuse the entry if this video is already in the project, so it is only stored once.
        int source = -1;
        for (int i = 0; i < project.sourceCount(); i++) {
            if (project.uriOf(i).equals(uri.toString())) {
                source = i;
                break;
            }
        }
        boolean isNew = source < 0;
        if (isNew) {
            project.extraSources.add(new Project.Source(uri.toString(), length));
            source = project.sourceCount() - 1;
        }
        int selected = selectedIndex();
        int at = selected >= 0 ? selected + 1 : timeline.size();
        String id = EditTimeline.newId();
        timeline = timeline.insert(at, new Segment(id, source, 0, length));
        selectedId = id;
        long[] lengths = project.sourceLengths(sourceMs);
        timelineView.setSourceLengths(lengths);
        if (isNew) {
            startFilmstrip(source, length, timelineView.suggestedFrameCount());
        }
        commitEdit(timeline.startOf(at));
        showMessage(getString(R.string.add_clip_done,
                name != null ? name : DurationFormat.formatPrecise(length)));
    }

    private void resetEdit() {
        if (sourceMs <= 0 || !isEdited()) {
            return;
        }
        timeline = EditTimeline.whole(sourceMs);
        selectedId = timeline.get(0).id;
        commitEdit(0);
        showMessage(getString(R.string.trim_reset_done));
    }

    /** Shows the source frame at the edge being dragged, like scrubbing the original video. */
    private void showTrimFrame(int source, long sourcePositionMs) {
        if (player == null) {
            return;
        }
        if (!trimPreview || trimSource != source) {
            trimPreview = true;
            trimSource = source;
            player.pause();
            player.setMediaItem(MediaItem.fromUri(Uri.parse(project.uriOf(source))));
            player.prepare();
        }
        player.seekTo(0, sourcePositionMs);
    }

    /** Where the playhead goes after a trim: the edge that was moved. */
    private long playheadFor(EditTimeline t, int index, boolean isStart) {
        long start = t.startOf(index);
        return isStart ? start : Math.max(start, t.endOf(index) - 100);
    }

    private void finishTrim(EditTimeline result, int index) {
        if (result.equals(timeline)) {
            // Nothing changed (a tap on a handle, or it hit its limit straight away).
            if (trimPreview) {
                reloadPlaylist(playhead);
            }
            refreshEditUi();
            return;
        }
        timeline = result;
        commitEdit(playheadFor(result, index, trimIsStart));
        showMessage(getString(R.string.trimmed_to,
                DurationFormat.formatPrecise(result.get(index).length())));
    }

    // ---- Aspect ratio ------------------------------------------------------------------

    private void selectRatio(AspectRatio ratio) {
        if (project == null || ratio.id.equals(project.ratio)) {
            return;
        }
        project.ratio = ratio.id;
        applyRatio(ratio);
        commit();
    }

    /** Updates the preview frame and the chips. Only the preview changes, never the video file. */
    private void applyRatio(AspectRatio ratio) {
        stage.setRatio(ratio == AspectRatio.ORIGINAL ? videoRatio : ratio.value);
        for (Map.Entry<AspectRatio, TextView> chip : ratioChips.entrySet()) {
            chip.getValue().setSelected(chip.getKey() == ratio);
        }
    }

    // ---- Tools -------------------------------------------------------------------------

    private void selectTool(int selected) {
        tool = selected;
        findViewById(R.id.panel_trim).setVisibility(selected == TOOL_TRIM ? View.VISIBLE : View.GONE);
        findViewById(R.id.panel_ratio).setVisibility(
                selected == TOOL_RATIO ? View.VISIBLE : View.GONE);
        findViewById(R.id.tool_trim).setSelected(selected == TOOL_TRIM);
        findViewById(R.id.tool_ratio).setSelected(selected == TOOL_RATIO);
    }

    // ---- Undo, redo and saving ---------------------------------------------------------

    private EditState currentState() {
        return new EditState(timeline, project.ratio);
    }

    /** An edit changed the segments: bring the preview up to date, record it and save it. */
    private void commitEdit(long newPlayhead) {
        reloadPlaylist(newPlayhead);
        playhead = Math.max(0, Math.min(timeline.durationMs(), newPlayhead));
        commit();
        refreshEditUi();
    }

    /** Records the current edit for undo and saves it with the project. */
    private void commit() {
        if (project == null || sourceMs <= 0) {
            return;
        }
        history.push(currentState());
        saveEdits();
        updateUndoRedo();
    }

    private void applyHistory(EditState state, int messageRes) {
        if (state == null || project == null || sourceMs <= 0) {
            return;
        }
        int oldIndex = selectedIndex();
        timeline = state.timeline.sanitized(project.sourceLengths(sourceMs));
        project.ratio = state.ratioId;
        applyRatio(AspectRatio.fromId(state.ratioId));
        if (selectedId == null || timeline.indexOfId(selectedId) < 0) {
            selectedId = timeline.isEmpty() ? null
                    : timeline.get(Math.max(0, Math.min(oldIndex, timeline.size() - 1))).id;
        }
        long at = Math.min(playhead, timeline.durationMs());
        reloadPlaylist(at);
        playhead = at;
        saveEdits();
        refreshEditUi();
        showMessage(getString(messageRes));
    }

    private void updateUndoRedo() {
        boolean undo = history.canUndo() && readable;
        boolean redo = history.canRedo() && readable;
        undoButton.setEnabled(undo);
        undoButton.setAlpha(undo ? 1f : 0.35f);
        redoButton.setEnabled(redo);
        redoButton.setAlpha(redo ? 1f : 0.35f);
    }

    private void saveEdits() {
        project.segments = timeline.serialize();
        saveProject();
    }

    private void saveProject() {
        final Project snapshot = project.copy();
        snapshot.lastEdited = System.currentTimeMillis();
        project.lastEdited = snapshot.lastEdited;
        if (!io.isShutdown()) {
            io.execute(() -> store.update(snapshot));
        }
        showSaved(snapshot.lastEdited);
    }

    // ---- Export ------------------------------------------------------------------------

    private void openExport() {
        if (project == null || sourceMs <= 0 || !readable) {
            showMessage(getString(R.string.video_not_ready));
            return;
        }
        if (timeline.isEmpty()) {
            showMessage(getString(R.string.export_nothing));
            return;
        }
        if (player != null) {
            player.pause();
        }
        saveEdits(); // Queued before anything the next screen reads.
        Intent intent = new Intent(this, ExportActivity.class);
        intent.putExtra(HomeActivity.EXTRA_PROJECT_ID, project.id);
        startActivity(intent);
    }

    // ---- Timeline frames, messages, enabled state --------------------------------------

    private void startFilmstrip() {
        if (!readable || sourceMs <= 0 || project == null) {
            return;
        }
        if (timelineView.getWidth() == 0 || timelineView.getHeight() == 0) {
            // Not laid out yet; try again shortly.
            if (filmstripTries++ < FILMSTRIP_RETRIES) {
                handler.postDelayed(this::startFilmstrip, 50);
            }
            return;
        }
        int count = timelineView.suggestedFrameCount();
        long[] lengths = project.sourceLengths(sourceMs);
        for (int source = 0; source < lengths.length; source++) {
            startFilmstrip(source, lengths[source], count);
        }
    }

    private void startFilmstrip(int source, long length, int count) {
        if (length <= 0 || project.uriOf(source).isEmpty()) {
            return;
        }
        while (filmstrips.size() <= source) {
            filmstrips.add(new FilmstripLoader());
        }
        timelineView.prepareFrames(source, count);
        filmstrips.get(source).load(this, Uri.parse(project.uriOf(source)), length, count,
                timelineView.trackHeightPx(), (index, frame) -> timelineView.setFrame(source, index, frame));
    }

    private void showMessage(String message) {
        messageText.setText(message);
        messageBar.setVisibility(View.VISIBLE);
        messageBar.announceForAccessibility(message);
        handler.removeCallbacks(hideMessage);
        handler.postDelayed(hideMessage, MESSAGE_MILLIS);
    }

    private void setControlsEnabled(boolean enabled) {
        int[] ids = {R.id.btn_play, R.id.timeline, R.id.btn_continue, R.id.btn_export_top};
        for (int id : ids) {
            View v = findViewById(id);
            v.setEnabled(enabled);
            v.setAlpha(enabled ? 1f : 0.4f);
        }
        for (TextView chip : ratioChips.values()) {
            chip.setEnabled(enabled);
        }
        updateActions();
        updateUndoRedo();
    }

    // ---- Lifecycle ---------------------------------------------------------------------

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_POSITION, player != null ? timelinePosition() : playhead);
        outState.putInt(STATE_TOOL, tool);
        outState.putString(STATE_SELECTED, selectedId);
        if (!history.isEmpty()) {
            outState.putStringArrayList(STATE_HISTORY, new ArrayList<>(history.toList()));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            player.pause();
        }
        // Leaving on purpose means there is nothing to recover. Clear this before Home resumes.
        if (isFinishing()) {
            prefs.edit().remove(HomeActivity.PREF_OPEN_PROJECT).apply();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        releasePlayer();
        // Autosave: stamp the project so "recent" ordering and "edited" times stay right.
        if (project != null && !io.isShutdown()) {
            final Project snapshot = project.copy();
            snapshot.lastEdited = System.currentTimeMillis();
            io.execute(() -> store.update(snapshot));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        for (FilmstripLoader loader : filmstrips) {
            loader.shutdown();
        }
        io.shutdown(); // Lets a queued save finish, then stops.
    }
}
