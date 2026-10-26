package com.example.viyanx;

/**
 * Holds the state of the running export so the home screen can show a progress card.
 * The export engine calls {@link #start}, {@link #update} and {@link #finish}.
 */
final class ExportTracker {

    private static String projectName;
    private static int percent;

    private ExportTracker() {}

    static synchronized void start(String name) {
        projectName = name;
        percent = 0;
    }

    static synchronized void update(int newPercent) {
        if (projectName != null) {
            percent = Math.max(0, Math.min(100, newPercent));
        }
    }

    static synchronized void finish() {
        projectName = null;
        percent = 0;
    }

    static synchronized boolean isRunning() {
        return projectName != null;
    }

    static synchronized String projectName() {
        return projectName;
    }

    static synchronized int percent() {
        return percent;
    }
}
