package com.example.viyanx;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen: start a project (create, quick tool or template), pick up recent work and manage it.
 */
public class HomeActivity extends ComponentActivity {

    static final String EXTRA_PROJECT_ID = "project_id";
    static final String PREFS = "viyanx_prefs";
    static final String PREF_OPEN_PROJECT = "open_project";
    private static final String PREF_TIPS_DISMISSED = "tips_dismissed";

    private static final String STATE_PENDING_TOOL = "pending_tool";
    private static final String STATE_PENDING_TEMPLATE = "pending_template";
    private static final String STATE_PENDING_URI = "pending_uri";

    private static final int MAX_CONTENT_WIDTH_DP = 600;
    private static final int MIN_SIDE_PADDING_DP = 20;
    private static final int MAX_RECENT = 20;
    private static final long UNDO_MILLIS = 5000;
    private static final long LOW_STORAGE_BYTES = 500L * 1024 * 1024;

    // Registered at construction so it survives rotation while the picker is open.
    private final ActivityResultLauncher<PickVisualMediaRequest> pickVideo =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(), this::onVideoPicked);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProjectStore store;
    private SharedPreferences prefs;

    // What the user chose before the picker opened.
    private QuickTool pendingTool;
    private Template pendingTemplate;
    // Picked video waiting for the user to choose a size.
    private Uri pendingUri;

    private Project deletedProject;
    private final Runnable finishDelete = this::commitDelete;
    private final Runnable hideSnackbar = () -> findViewById(R.id.snackbar).setVisibility(View.GONE);
    private AlertDialog ratioDialog;
    private boolean recoveryChecked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        store = ProjectStore.get(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (savedInstanceState != null) {
            pendingTool = QuickTool.fromId(savedInstanceState.getString(STATE_PENDING_TOOL));
            pendingTemplate = Template.fromId(savedInstanceState.getString(STATE_PENDING_TEMPLATE));
            String uri = savedInstanceState.getString(STATE_PENDING_URI);
            pendingUri = uri == null ? null : Uri.parse(uri);
            recoveryChecked = true; // Only offer recovery on a fresh launch, not after rotation.
        }

        findViewById(R.id.tab_home).setSelected(true);
        bindComingSoonTab(R.id.tab_projects, R.string.nav_projects);
        bindComingSoonTab(R.id.tab_profile, R.string.nav_profile);

        View.OnClickListener create = v -> startFlow(null, null);
        findViewById(R.id.btn_create_video).setOnClickListener(create);
        findViewById(R.id.btn_empty_create).setOnClickListener(create);
        findViewById(R.id.fab_create).setOnClickListener(create);
        findViewById(R.id.card_ai).setOnClickListener(v ->
                showSnackbar(getString(R.string.ai_coming_soon), false));
        findViewById(R.id.snackbar_action).setOnClickListener(v -> undoDelete());
        // The Projects screen is not built yet, so be honest about it.
        findViewById(R.id.btn_see_all).setOnClickListener(v -> showSnackbar(
                getString(R.string.nav_coming_soon, getString(R.string.nav_projects)), false));
        findViewById(R.id.btn_tips_dismiss).setOnClickListener(v -> {
            prefs.edit().putBoolean(PREF_TIPS_DISMISSED, true).apply();
            findViewById(R.id.card_tips).setVisibility(View.GONE);
        });

        buildQuickTools();
        buildTemplates();
        applyInsets();
        centerContentOnWideScreens();

        if (pendingUri != null) {
            showRatioDialog();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PENDING_TOOL, pendingTool == null ? null : pendingTool.id);
        outState.putString(STATE_PENDING_TEMPLATE,
                pendingTemplate == null ? null : pendingTemplate.id);
        outState.putString(STATE_PENDING_URI, pendingUri == null ? null : pendingUri.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        findViewById(R.id.card_tips).setVisibility(
                prefs.getBoolean(PREF_TIPS_DISMISSED, false) ? View.GONE : View.VISIBLE);
        updateExportCard();
        updateStorage();
        refreshProjects();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (deletedProject != null) {
            commitDelete(); // Don't leave a delete half-finished when the screen goes away.
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (ratioDialog != null) {
            ratioDialog.dismiss();
        }
        io.shutdown();
    }

    // ---- Starting a project -------------------------------------------------------------

    /** Remembers what the user wants to do, then opens the video picker. */
    private void startFlow(QuickTool tool, Template template) {
        pendingTool = tool;
        pendingTemplate = template;
        pickVideo.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                .build());
    }

    private void onVideoPicked(Uri uri) {
        if (uri == null) {
            pendingTool = null;
            pendingTemplate = null;
            return; // User closed the picker without choosing.
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // The video stays usable this session; later opens may report it as unavailable.
        }
        if (pendingTemplate != null) {
            createProject(uri, pendingTemplate.ratio, pendingTemplate, null);
        } else if (pendingTool != null) {
            createProject(uri, AspectRatio.ORIGINAL, null, pendingTool);
        } else {
            pendingUri = uri;
            showRatioDialog();
        }
    }

    private void showRatioDialog() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, 0);
        final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.ratio_dialog_title)
                .setView(list)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setOnCancelListener(d -> pendingUri = null);
        ratioDialog = builder.create();
        // Cancel via the button also clears the pending video.
        ratioDialog.setOnDismissListener(d -> {
            if (ratioDialog == d) {
                ratioDialog = null;
            }
        });
        for (AspectRatio ratio : AspectRatio.values()) {
            View row = getLayoutInflater().inflate(R.layout.item_ratio, list, false);
            String label = getString(ratio.labelRes);
            String hint = getString(ratio.hintRes);
            ((TextView) row.findViewById(R.id.ratio_label)).setText(label);
            ((TextView) row.findViewById(R.id.ratio_hint)).setText(hint);
            row.setContentDescription(getString(R.string.project_meta, label, hint));
            row.setOnClickListener(v -> {
                Uri uri = pendingUri;
                pendingUri = null;
                ratioDialog.dismiss();
                if (uri != null) {
                    createProject(uri, ratio, null, null);
                }
            });
            list.addView(row);
        }
        ratioDialog.show();
        ratioDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(v -> {
                    pendingUri = null;
                    ratioDialog.dismiss();
                });
    }

    /** Reads the video's details and saves the project off the main thread, then opens the editor. */
    private void createProject(Uri uri, AspectRatio ratio, Template template, QuickTool tool) {
        pendingTool = null;
        pendingTemplate = null;
        final Context app = getApplicationContext();
        setLoading(true);
        io.execute(() -> {
            String id = UUID.randomUUID().toString();
            String name = VideoProbe.displayName(app, uri);
            VideoProbe probe = VideoProbe.read(app, uri);
            if (!VideoProbe.canOpen(app, uri) || (probe.durationMs < 0 && probe.frame == null)) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        setLoading(false);
                        showSnackbar(getString(R.string.video_unreadable), false);
                    }
                });
                return;
            }
            String thumb = null;
            if (probe.frame != null) {
                String fileName = id + ".jpg";
                if (VideoProbe.saveJpeg(probe.frame, new File(store.thumbDir(), fileName))) {
                    thumb = fileName;
                }
            }
            long now = System.currentTimeMillis();
            Project project = new Project(id, cleanName(name, app), uri.toString(),
                    probe.durationMs, ratio.id, template == null ? null : template.id,
                    tool == null ? null : tool.id, now, now, thumb);
            store.add(project);
            runOnUiThread(() -> {
                if (!isDestroyed()) {
                    setLoading(false);
                    openEditor(project.id);
                }
            });
        });
    }

    private void setLoading(boolean loading) {
        findViewById(R.id.loading).setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private static String cleanName(String fileName, Context context) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return context.getString(R.string.project_default_name);
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.trim().isEmpty() ? context.getString(R.string.project_default_name) : base;
    }

    private void openEditor(String projectId) {
        Intent intent = new Intent(this, EditorActivity.class);
        intent.putExtra(EXTRA_PROJECT_ID, projectId);
        startActivity(intent);
    }

    // ---- Quick tools and templates -----------------------------------------------------

    private void buildQuickTools() {
        ViewGroup row = findViewById(R.id.quick_tools);
        for (QuickTool tool : QuickTool.values()) {
            TextView chip = (TextView) getLayoutInflater().inflate(R.layout.item_chip, row, false);
            chip.setText(tool.labelRes);
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(tool.iconRes, 0, 0, 0);
            chip.setContentDescription(getString(R.string.quick_tool_description,
                    getString(tool.labelRes)));
            chip.setOnClickListener(v -> startFlow(tool, null));
            row.addView(chip);
        }
    }

    private void buildTemplates() {
        ViewGroup row = findViewById(R.id.templates);
        for (Template template : Template.values()) {
            View card = getLayoutInflater().inflate(R.layout.item_template, row, false);
            String name = getString(template.nameRes);
            String desc = getString(template.descriptionRes);
            String ratio = getString(template.ratio.labelRes);
            ((TextView) card.findViewById(R.id.template_ratio)).setText(ratio);
            ((TextView) card.findViewById(R.id.template_name)).setText(name);
            ((TextView) card.findViewById(R.id.template_desc)).setText(desc);
            card.setContentDescription(
                    getString(R.string.template_description, name, ratio, desc));
            card.setOnClickListener(v -> startFlow(null, template));
            row.addView(card);
        }
    }

    // ---- Recent projects ---------------------------------------------------------------

    private void refreshProjects() {
        io.execute(() -> {
            List<Project> projects = store.list();
            if (projects.size() > MAX_RECENT) {
                projects = projects.subList(0, MAX_RECENT);
            }
            Map<String, Bitmap> thumbs = new HashMap<>();
            for (Project p : projects) {
                if (p.thumb != null) {
                    Bitmap bitmap = BitmapFactory.decodeFile(store.thumbFile(p.thumb).getPath());
                    if (bitmap != null) {
                        thumbs.put(p.id, bitmap);
                    }
                }
            }
            final List<Project> shown = projects;
            runOnUiThread(() -> {
                if (!isDestroyed()) {
                    showProjects(shown, thumbs);
                    checkRecovery();
                }
            });
        });
    }

    private void showProjects(List<Project> projects, Map<String, Bitmap> thumbs) {
        ViewGroup list = findViewById(R.id.recent_list);
        list.removeAllViews();
        boolean empty = projects.isEmpty();
        findViewById(R.id.recent_empty).setVisibility(empty ? View.VISIBLE : View.GONE);
        findViewById(R.id.recent_scroll).setVisibility(empty ? View.GONE : View.VISIBLE);
        findViewById(R.id.btn_see_all).setVisibility(empty ? View.GONE : View.VISIBLE);

        View continueCard = findViewById(R.id.card_continue);
        if (empty) {
            continueCard.setVisibility(View.GONE);
            return;
        }
        for (Project p : projects) {
            list.addView(buildProjectCard(list, p, thumbs.get(p.id)));
        }

        final Project latest = projects.get(0);
        String edited = relativeTime(latest.lastEdited);
        ((TextView) findViewById(R.id.continue_name)).setText(latest.name);
        ((TextView) findViewById(R.id.continue_meta)).setText(
                getString(R.string.continue_subtitle, ratioLabel(latest), edited));
        bindThumb((ImageView) findViewById(R.id.continue_thumb), thumbs.get(latest.id));
        continueCard.setContentDescription(
                getString(R.string.continue_description, latest.name));
        continueCard.setOnClickListener(v -> openEditor(latest.id));
        continueCard.setVisibility(View.VISIBLE);
    }

    private View buildProjectCard(ViewGroup parent, Project project, Bitmap thumb) {
        View card = getLayoutInflater().inflate(R.layout.item_project, parent, false);
        String length = project.durationMs >= 0
                ? DurationFormat.format(project.durationMs)
                : getString(R.string.editor_unknown);
        String edited = relativeTime(project.lastEdited);
        ((TextView) card.findViewById(R.id.project_name)).setText(project.name);
        ((TextView) card.findViewById(R.id.project_meta)).setText(
                getString(R.string.project_meta, length, ratioLabel(project)));
        ((TextView) card.findViewById(R.id.project_edited)).setText(
                getString(R.string.project_edited, edited));
        bindThumb((ImageView) card.findViewById(R.id.project_thumb), thumb);
        card.setContentDescription(getString(R.string.project_card_description,
                project.name, length, ratioLabel(project), edited));

        View menuButton = card.findViewById(R.id.project_menu);
        menuButton.setContentDescription(
                getString(R.string.project_menu_description, project.name));
        card.setOnClickListener(v -> openEditor(project.id));
        card.setOnLongClickListener(v -> {
            showProjectMenu(menuButton, project);
            return true;
        });
        menuButton.setOnClickListener(v -> showProjectMenu(v, project));
        return card;
    }

    private void bindThumb(ImageView view, Bitmap bitmap) {
        if (bitmap != null) {
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setImageTintList(null);
            view.setImageBitmap(bitmap);
        } else {
            view.setScaleType(ImageView.ScaleType.CENTER);
            view.setImageResource(R.drawable.ic_nav_projects);
            view.setImageTintList(ContextCompat.getColorStateList(this, R.color.vx_text_secondary));
        }
    }

    private String ratioLabel(Project project) {
        return getString(AspectRatio.fromId(project.ratio).labelRes);
    }

    private String relativeTime(long millis) {
        if (System.currentTimeMillis() - millis < DateUtils.MINUTE_IN_MILLIS) {
            return getString(R.string.just_now);
        }
        return DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
    }

    // ---- Rename, duplicate, delete -----------------------------------------------------

    private void showProjectMenu(View anchor, Project project) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.menu_rename);
        menu.getMenu().add(0, 2, 1, R.string.menu_duplicate);
        menu.getMenu().add(0, 3, 2, R.string.menu_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    promptRename(project);
                    return true;
                case 2:
                    duplicateProject(project);
                    return true;
                case 3:
                    deleteProject(project);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void promptRename(Project project) {
        final EditText input = new EditText(this);
        input.setHint(R.string.rename_hint);
        input.setSingleLine(true);
        input.setText(project.name);
        input.selectAll();
        int pad = dp(24);
        LinearLayout frame = new LinearLayout(this);
        frame.setPadding(pad, dp(8), pad, 0);
        frame.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename_title)
                .setView(frame)
                .setPositiveButton(R.string.dialog_save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.equals(project.name)) {
                        return;
                    }
                    io.execute(() -> {
                        Project stored = store.find(project.id);
                        if (stored != null) {
                            stored.name = name;
                            store.update(stored);
                        }
                        runOnUiThread(this::refreshProjectsIfAlive);
                    });
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void duplicateProject(Project project) {
        final String newName = getString(R.string.project_copy_name, project.name);
        io.execute(() -> {
            Project copy = store.duplicate(project.id, newName);
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (copy != null) {
                    showSnackbar(getString(R.string.project_duplicated, copy.name), false);
                }
                refreshProjects();
            });
        });
    }

    /** Removes the project straight away and offers undo for a few seconds. */
    private void deleteProject(Project project) {
        if (deletedProject != null) {
            commitDelete(); // A second delete finalizes the first.
        }
        deletedProject = project;
        io.execute(() -> {
            store.remove(project.id);
            runOnUiThread(this::refreshProjectsIfAlive);
        });
        showSnackbar(getString(R.string.project_deleted, project.name), true);
        handler.postDelayed(finishDelete, UNDO_MILLIS);
    }

    private void undoDelete() {
        final Project restored = deletedProject;
        if (restored == null) {
            return;
        }
        deletedProject = null;
        handler.removeCallbacks(finishDelete);
        handler.removeCallbacks(hideSnackbar);
        findViewById(R.id.snackbar).setVisibility(View.GONE);
        io.execute(() -> {
            store.add(restored);
            runOnUiThread(this::refreshProjectsIfAlive);
        });
    }

    /** Makes a pending delete permanent by removing the thumbnail too. */
    private void commitDelete() {
        final Project gone = deletedProject;
        if (gone == null) {
            return;
        }
        deletedProject = null;
        handler.removeCallbacks(finishDelete);
        if (!io.isShutdown()) {
            io.execute(() -> store.deleteThumb(gone.thumb));
        }
        findViewById(R.id.snackbar).setVisibility(View.GONE);
    }

    private void showSnackbar(String message, boolean withUndo) {
        ((TextView) findViewById(R.id.snackbar_text)).setText(message);
        findViewById(R.id.snackbar_action).setVisibility(withUndo ? View.VISIBLE : View.GONE);
        View bar = findViewById(R.id.snackbar);
        bar.setVisibility(View.VISIBLE);
        bar.announceForAccessibility(message);
        handler.removeCallbacks(hideSnackbar);
        if (!withUndo) {
            handler.postDelayed(hideSnackbar, 3000);
        }
    }

    private void refreshProjectsIfAlive() {
        if (!isDestroyed()) {
            refreshProjects();
        }
    }

    // ---- Export, storage and recovery --------------------------------------------------

    private void updateExportCard() {
        View card = findViewById(R.id.card_export);
        if (!ExportTracker.isRunning()) {
            card.setVisibility(View.GONE);
            return;
        }
        int percent = ExportTracker.percent();
        String title = getString(R.string.export_title, ExportTracker.projectName());
        String percentText = getString(R.string.export_progress, percent);
        ((TextView) findViewById(R.id.export_title)).setText(title);
        ((TextView) findViewById(R.id.export_percent)).setText(percentText);
        ((ProgressBar) findViewById(R.id.export_bar)).setProgress(percent);
        card.setContentDescription(title + ", " + percentText);
        card.setVisibility(View.VISIBLE);
    }

    private void updateStorage() {
        TextView view = findViewById(R.id.storage_text);
        try {
            long free = new StatFs(getFilesDir().getPath()).getAvailableBytes();
            String size = Formatter.formatFileSize(this, free);
            boolean low = free < LOW_STORAGE_BYTES;
            view.setText(low ? getString(R.string.storage_low, size)
                    : getString(R.string.storage_free, size));
            view.setTextColor(ContextCompat.getColor(this,
                    low ? R.color.vx_error : R.color.vx_text_secondary));
            view.setVisibility(View.VISIBLE);
        } catch (RuntimeException e) {
            view.setVisibility(View.GONE);
        }
    }

    /** If the app died while a project was open, offer to jump back into it once per launch. */
    private void checkRecovery() {
        if (recoveryChecked) {
            return;
        }
        recoveryChecked = true;
        final String id = prefs.getString(PREF_OPEN_PROJECT, null);
        if (id == null) {
            return;
        }
        io.execute(() -> {
            Project project = store.find(id);
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (project == null) {
                    prefs.edit().remove(PREF_OPEN_PROJECT).apply();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.recover_title)
                        .setMessage(getString(R.string.recover_body, project.name))
                        .setPositiveButton(R.string.recover_resume,
                                (d, w) -> openEditor(project.id))
                        .setNegativeButton(R.string.recover_dismiss, (d, w) ->
                                prefs.edit().remove(PREF_OPEN_PROJECT).apply())
                        .setOnCancelListener(d ->
                                prefs.edit().remove(PREF_OPEN_PROJECT).apply())
                        .show();
            });
        });
    }

    // ---- Misc --------------------------------------------------------------------------

    private void bindComingSoonTab(int tabId, int labelRes) {
        findViewById(tabId).setOnClickListener(v -> showSnackbar(
                getString(R.string.nav_coming_soon, getString(labelRes)), false));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** The app draws edge to edge on newer Android, so keep content clear of the system bars. */
    private void applyInsets() {
        final View root = findViewById(R.id.home_root);
        final View nav = findViewById(R.id.bottom_nav);
        final int navBottom = nav.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = SystemInsets.safe(this, insets);
            v.setPadding(bars.left, bars.top, bars.right, 0);
            nav.setPadding(nav.getPaddingLeft(), nav.getPaddingTop(),
                    nav.getPaddingRight(), navBottom + bars.bottom);
            return insets;
        });
    }

    /** Keeps cards at a readable width on tablets and landscape. */
    private void centerContentOnWideScreens() {
        View content = findViewById(R.id.home_content);
        float density = getResources().getDisplayMetrics().density;
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        int sideDp = Math.max(MIN_SIDE_PADDING_DP, (screenWidthDp - MAX_CONTENT_WIDTH_DP) / 2);
        int side = Math.round(sideDp * density);
        content.setPadding(side, content.getPaddingTop(), side, content.getPaddingBottom());
    }
}
