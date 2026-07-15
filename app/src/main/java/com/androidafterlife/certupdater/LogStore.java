package com.androidafterlife.certupdater;

import android.os.Handler;
import android.os.Looper;

/**
 * Simple process-wide log buffer.
 *
 * We deliberately do NOT pass the (potentially large) terminal output through
 * an Intent extra between MainActivity and LogActivity: Android's Binder
 * transaction buffer is small (~1MB shared across the whole process) and
 * doing so is a classic, easy-to-hit cause of a crash
 * (TransactionTooLargeException) on exactly the kind of verbose shell output
 * this app produces. Instead both activities just read this single
 * in-memory buffer.
 */
public final class LogStore {

    private static final StringBuilder buffer = new StringBuilder();
    private static Listener listener;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onLogChanged(String fullText);
    }

    private LogStore() {
    }

    public static synchronized void clear() {
        buffer.setLength(0);
        notifyListener();
    }

    public static synchronized void append(final String line) {
        buffer.append(line);
        if (!line.endsWith("\n")) {
            buffer.append('\n');
        }
        notifyListener();
    }

    public static synchronized String getAll() {
        return buffer.toString();
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    private static void notifyListener() {
        final String snapshot = buffer.toString();
        // Always hop to the main thread; append() may be called from
        // background worker threads.
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onLogChanged(snapshot);
                }
            }
        });
    }
}
