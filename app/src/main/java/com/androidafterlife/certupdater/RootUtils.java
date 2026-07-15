package com.androidafterlife.certupdater;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Helper for detecting root and running commands through an "su" shell.
 *
 * IMPORTANT reliability notes (why this class is written the way it is):
 *
 *  - We open exactly ONE su session and feed it every command over stdin,
 *    rather than spawning a new "su -c '...'" process per command. Spawning
 *    su repeatedly is slower and, on some root managers (SuperSU, Magisk),
 *    triggers a fresh permission prompt every single time.
 *
 *  - We read the child process's stdout on a dedicated background thread
 *    WHILE we are still writing to it. Process stdout pipes have a small,
 *    fixed-size OS buffer (often 64KB). If a command produces more output
 *    than that and nobody is draining the pipe, the child process blocks
 *    forever writing to it, and process.waitFor() then blocks forever too.
 *    This is a very common source of "app just hangs" bugs in Android
 *    shell/root wrappers, so we avoid it deliberately here.
 *
 *  - We merge stderr into stdout with "exec 2>&1" as the very first command
 *    sent to the shell, so a single reader thread sees everything and we
 *    don't need a second drain thread for the error stream.
 *
 *  - Every stream is closed in a finally block and the process is always
 *    destroyed, even on exceptions/timeouts, so we never leak a hung su
 *    process if something goes wrong.
 */
public final class RootUtils {

    private RootUtils() {
    }

    public static final class CommandResult {
        public final boolean success;
        public final int exitCode;
        public final String output;

        CommandResult(boolean success, int exitCode, String output) {
            this.success = success;
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /** Quick check: is there a working su binary that grants root? */
    public static boolean isDeviceRooted() {
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
            }
            int exit = waitForWithTimeout(process, 10000);
            return exit == 0 && out.toString().contains("uid=0");
        } catch (Throwable t) {
            // Any failure (su not found, permission denied, timeout...) simply
            // means "not rooted" as far as this app is concerned.
            return false;
        } finally {
            closeQuietly(reader);
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * Runs a sequence of shell commands inside a single root shell.
     * All output (stdout + stderr) is captured, and each line is streamed
     * live to LogStore as it arrives.
     */
    public static CommandResult executeAsRoot(String[] commands, long timeoutMillis) {
        Process process = null;
        DataOutputStream stdin = null;
        final StringBuilder captured = new StringBuilder();
        Thread readerThread = null;
        try {
            process = Runtime.getRuntime().exec("su");
            stdin = new DataOutputStream(process.getOutputStream());
            final BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String line;
                        while ((line = stdout.readLine()) != null) {
                            captured.append(line).append('\n');
                            LogStore.append(line);
                        }
                    } catch (IOException ignored) {
                        // stream closed because the process ended; normal.
                    }
                }
            }, "root-shell-reader");
            readerThread.start();

            // Merge stderr into stdout at the shell level so the single
            // reader thread above sees error output too.
            stdin.writeBytes("exec 2>&1\n");
            stdin.flush();

            for (String command : commands) {
                LogStore.append("$ " + command);
                stdin.writeBytes(command + "\n");
                stdin.flush();
            }
            stdin.writeBytes("echo __CMD_DONE_" + System.currentTimeMillis() + "__\n");
            stdin.writeBytes("exit\n");
            stdin.flush();

            int exitCode = waitForWithTimeout(process, timeoutMillis);
            readerThread.join(5000);

            boolean success = exitCode == 0;
            return new CommandResult(success, exitCode, captured.toString());

        } catch (Throwable t) {
            LogStore.append("ERROR: " + describeThrowable(t));
            return new CommandResult(false, -1, captured.toString());
        } finally {
            closeQuietly(stdin);
            if (readerThread != null && readerThread.isAlive()) {
                readerThread.interrupt();
            }
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static int waitForWithTimeout(Process process, long timeoutMillis) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (true) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                if (System.currentTimeMillis() - start > timeoutMillis) {
                    process.destroy();
                    throw new InterruptedException("Timed out waiting for root shell");
                }
                Thread.sleep(100);
            }
        }
    }

    private static void closeQuietly(Object closeable) {
        if (closeable == null) return;
        try {
            if (closeable instanceof java.io.Closeable) {
                ((java.io.Closeable) closeable).close();
            }
        } catch (Throwable ignored) {
        }
    }

    static String describeThrowable(Throwable t) {
        return t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
    }
}
