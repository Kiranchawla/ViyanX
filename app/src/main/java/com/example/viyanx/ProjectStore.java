package com.example.viyanx;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Keeps projects in a small JSON file in app storage. Methods do file I/O, so call them off the
 * main thread.
 */
final class ProjectStore {

    private static final String FILE_NAME = "projects.json";
    private static final String THUMB_DIR = "thumbs";

    private static ProjectStore instance;

    private final File file;
    private final File thumbDir;
    private List<Project> projects; // Loaded lazily.

    static synchronized ProjectStore get(Context context) {
        if (instance == null) {
            instance = new ProjectStore(context.getApplicationContext());
        }
        return instance;
    }

    private ProjectStore(Context context) {
        file = new File(context.getFilesDir(), FILE_NAME);
        thumbDir = new File(context.getFilesDir(), THUMB_DIR);
    }

    /** Newest first. */
    synchronized List<Project> list() {
        load();
        List<Project> copy = new ArrayList<>();
        for (Project p : projects) {
            copy.add(p.copy());
        }
        Collections.sort(copy, (a, b) -> Long.compare(b.lastEdited, a.lastEdited));
        return copy;
    }

    synchronized Project find(String id) {
        load();
        for (Project p : projects) {
            if (p.id.equals(id)) {
                return p.copy();
            }
        }
        return null;
    }

    synchronized void add(Project project) {
        load();
        projects.add(project.copy());
        save();
    }

    /** Replaces the stored project that has the same id. */
    synchronized void update(Project project) {
        load();
        for (int i = 0; i < projects.size(); i++) {
            if (projects.get(i).id.equals(project.id)) {
                projects.set(i, project.copy());
                save();
                return;
            }
        }
    }

    /** Removes the project but leaves its thumbnail so a delete can be undone. */
    synchronized void remove(String id) {
        load();
        for (int i = 0; i < projects.size(); i++) {
            if (projects.get(i).id.equals(id)) {
                projects.remove(i);
                save();
                return;
            }
        }
    }

    synchronized Project duplicate(String id, String newName) {
        Project source = find(id);
        if (source == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        String newId = UUID.randomUUID().toString();
        String newThumb = null;
        if (source.thumb != null) {
            newThumb = newId + ".jpg";
            if (!copyFile(thumbFile(source.thumb), thumbFile(newThumb))) {
                newThumb = null;
            }
        }
        Project copy = new Project(newId, newName, source.uri, source.durationMs, source.ratio,
                source.template, source.tool, now, now, newThumb);
        copy.segments = source.segments;
        copy.trimStartMs = source.trimStartMs;
        copy.trimEndMs = source.trimEndMs;
        add(copy);
        return copy;
    }

    File thumbFile(String name) {
        return new File(thumbDir, name);
    }

    /** Creates the thumbnail folder if needed. */
    File thumbDir() {
        //noinspection ResultOfMethodCallIgnored
        thumbDir.mkdirs();
        return thumbDir;
    }

    void deleteThumb(String name) {
        if (name != null) {
            //noinspection ResultOfMethodCallIgnored
            thumbFile(name).delete();
        }
    }

    private void load() {
        if (projects != null) {
            return;
        }
        projects = new ArrayList<>();
        if (!file.exists()) {
            return;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            JSONArray array = new JSONArray(new String(bytes, 0, read, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                try {
                    projects.add(Project.fromJson(array.getJSONObject(i)));
                } catch (JSONException skipBadEntry) {
                    // Drop one unreadable entry rather than losing the whole list.
                }
            }
        } catch (IOException | JSONException e) {
            // Unreadable file: start empty. The next save replaces it.
        }
    }

    /** Writes to a temp file first so a crash mid-write cannot corrupt the list. */
    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (Project p : projects) {
                array.put(p.toJson());
            }
            File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                throw new IOException("Could not replace " + file);
            }
        } catch (IOException | JSONException e) {
            // Keep the in-memory list; the next change retries the write.
        }
    }

    private static boolean copyFile(File from, File to) {
        if (!from.exists()) {
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        to.getParentFile().mkdirs();
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
