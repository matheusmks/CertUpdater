package com.androidafterlife.certupdater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Date;

public class MainActivity extends Activity {

    private static final int REQUEST_CODE_PICK_FILE = 1001;

    private ProgressBar rootCheckSpinner;
    private TextView rootStatusText;
    private RadioGroup sourceRadioGroup;
    private Button pickFileButton;
    private TextView selectedFileText;
    private Button updateButton;
    private ProgressBar updateProgressBar;
    private TextView statusText;
    private Button viewLogButton;
    private Button rebootButton;

    private volatile boolean isRooted = false;
    private File selectedLocalFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashLogger();

        try {
            setContentView(R.layout.activity_main);
            bindViews();
            wireListeners();

            LogStore.clear();
            LogStore.append("App launched.");

            new RootCheckTask().execute();

        } catch (Throwable t) {
            // Last-resort safety net: never let a UI-setup problem crash the
            // app on launch. Fall back to a minimal readable error screen.
            showFatalInitError(t);
        }
    }

    private void bindViews() {
        rootCheckSpinner = (ProgressBar) findViewById(R.id.rootCheckSpinner);
        rootStatusText = (TextView) findViewById(R.id.rootStatusText);
        sourceRadioGroup = (RadioGroup) findViewById(R.id.sourceRadioGroup);
        pickFileButton = (Button) findViewById(R.id.pickFileButton);
        selectedFileText = (TextView) findViewById(R.id.selectedFileText);
        updateButton = (Button) findViewById(R.id.updateButton);
        updateProgressBar = (ProgressBar) findViewById(R.id.updateProgressBar);
        statusText = (TextView) findViewById(R.id.statusText);
        viewLogButton = (Button) findViewById(R.id.viewLogButton);
        rebootButton = (Button) findViewById(R.id.rebootButton);
    }

    private void wireListeners() {
        sourceRadioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                boolean local = checkedId == R.id.radioLocal;
                pickFileButton.setVisibility(local ? View.VISIBLE : View.GONE);
                selectedFileText.setVisibility(local ? View.VISIBLE : View.GONE);
            }
        });

        pickFileButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchFilePicker();
            }
        });

        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onUpdateClicked();
            }
        });

        viewLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LogActivity.class));
            }
        });

        rebootButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReboot();
            }
        });
    }

    // -----------------------------------------------------------------
    // Root check
    // -----------------------------------------------------------------

    private class RootCheckTask extends AsyncTask<Void, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                return RootUtils.isDeviceRooted();
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean rooted) {
            isRooted = rooted != null && rooted;
            rootCheckSpinner.setVisibility(View.GONE);
            if (isRooted) {
                rootStatusText.setText(getString(R.string.root_ok));
                rootStatusText.setTextColor(getResources().getColor(R.color.holo_green_light));
                LogStore.append("Root check: granted.");
            } else {
                rootStatusText.setText(getString(R.string.root_missing));
                rootStatusText.setTextColor(getResources().getColor(R.color.holo_red_light));
                updateButton.setEnabled(false);
                LogStore.append("Root check: NOT rooted / access denied.");
            }
        }
    }

    // -----------------------------------------------------------------
    // File picking
    // -----------------------------------------------------------------

    private void launchFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, getString(R.string.btn_pick_file)), REQUEST_CODE_PICK_FILE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker app is available on this device.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_PICK_FILE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        new ImportFileTask().execute(data.getData());
    }

    private class ImportFileTask extends AsyncTask<Uri, Void, File> {
        @Override
        protected File doInBackground(Uri... uris) {
            try {
                return copyUriToCacheFile(uris[0]);
            } catch (Exception e) {
                LogStore.append("File import error: " + RootUtils.describeThrowable(e));
                return null;
            }
        }

        @Override
        protected void onPostExecute(File file) {
            if (file == null) {
                Toast.makeText(MainActivity.this, "Could not read the selected file.", Toast.LENGTH_LONG).show();
                return;
            }
            selectedLocalFile = file;
            selectedFileText.setText(file.getName());
            LogStore.append("Selected local file: " + file.getAbsolutePath());
        }
    }

    private File copyUriToCacheFile(Uri uri) throws IOException {
        String displayName = queryDisplayName(uri);
        if (displayName == null || displayName.length() == 0) {
            displayName = "imported_cert_" + System.currentTimeMillis();
        }
        displayName = displayName.replaceAll("[^a-zA-Z0-9._-]", "_");

        File outFile = new File(getCacheDir(), displayName);
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("Content provider returned no data for the selected file.");
            out = new FileOutputStream(outFile);
            byte[] buffer = new byte[8192];
            int r;
            while ((r = in.read(buffer)) != -1) {
                out.write(buffer, 0, r);
            }
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            if (out != null) try { out.close(); } catch (IOException ignored) { }
        }
        return outFile;
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null) return name;
                }
            }
        } catch (Exception ignored) {
            // Not every provider (or plain file:// uri) supports this query.
        } finally {
            if (cursor != null) cursor.close();
        }
        String path = uri.getPath();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
        return null;
    }

    // -----------------------------------------------------------------
    // Update flow
    // -----------------------------------------------------------------

    private void onUpdateClicked() {
        if (!isRooted) {
            Toast.makeText(this, getString(R.string.root_missing), Toast.LENGTH_LONG).show();
            return;
        }
        boolean useDownload = sourceRadioGroup.getCheckedRadioButtonId() == R.id.radioDownload;
        if (!useDownload && selectedLocalFile == null) {
            Toast.makeText(this, "Please choose a certificate file first.", Toast.LENGTH_LONG).show();
            return;
        }
        new UpdateTask(useDownload, selectedLocalFile).execute();
    }

    private static class UpdateOutcome {
        final boolean success;
        final String message;

        UpdateOutcome(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private class UpdateTask extends AsyncTask<Void, String, UpdateOutcome> {
        private final boolean useDownload;
        private final File localFile;

        UpdateTask(boolean useDownload, File localFile) {
            this.useDownload = useDownload;
            this.localFile = localFile;
        }

        @Override
        protected void onPreExecute() {
            updateButton.setEnabled(false);
            rebootButton.setVisibility(View.GONE);
            updateProgressBar.setVisibility(View.VISIBLE);
            updateProgressBar.setIndeterminate(true);
            statusText.setText("Starting\u2026");
            LogStore.clear();
            LogStore.append("=== CA certificate update started ===");
        }

        @Override
        protected UpdateOutcome doInBackground(Void... voids) {
            try {
                File stagingDir = new File(getExternalFilesDirCompat(), "cert_staging");
                deleteRecursive(stagingDir);
                if (!stagingDir.mkdirs() && !stagingDir.exists()) {
                    return new UpdateOutcome(false, "Could not create a working directory for staging certificates.");
                }

                File sourceFile;
                if (useDownload) {
                    publishProgress("Downloading latest CA bundle from Google\u2026");
                    File dest = new File(getCacheDir(), "downloaded_certs.tar.gz");
                    sourceFile = CertDownloader.download(CertDownloader.DEFAULT_ARCHIVE_URL, dest,
                            new CertDownloader.ProgressListener() {
                                @Override
                                public void onProgress(long bytesRead, long totalBytes) {
                                    if (totalBytes > 0) {
                                        publishProgress("PROGRESS:" + (int) (bytesRead * 100 / totalBytes));
                                    }
                                }
                            });
                    LogStore.append("Download complete: " + sourceFile.getAbsolutePath());
                } else {
                    sourceFile = localFile;
                    LogStore.append("Using local file: " + sourceFile.getAbsolutePath());
                }

                publishProgress("Extracting certificate files\u2026");
                int count = ArchiveExtractor.importAny(sourceFile, stagingDir);
                LogStore.append("Staged " + count + " certificate file(s) in " + stagingDir.getAbsolutePath());

                publishProgress("Installing via root shell\u2026");
                CertInstaller.InstallResult result = CertInstaller.install(stagingDir.getAbsolutePath(), count);
                return new UpdateOutcome(result.success, result.message);

            } catch (CertDownloader.DownloadException e) {
                LogStore.append("DOWNLOAD ERROR: " + e.getMessage());
                return new UpdateOutcome(false, "Download failed: " + e.getMessage()
                        + " \u2014 try \"Import from local storage\" instead.");
            } catch (Exception e) {
                LogStore.append("ERROR: " + RootUtils.describeThrowable(e));
                return new UpdateOutcome(false, "Update failed: " + RootUtils.describeThrowable(e));
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            String value = values[0];
            if (value.startsWith("PROGRESS:")) {
                updateProgressBar.setIndeterminate(false);
                try {
                    updateProgressBar.setProgress(Integer.parseInt(value.substring("PROGRESS:".length())));
                } catch (NumberFormatException ignored) {
                }
            } else {
                statusText.setText(value);
                LogStore.append(value);
            }
        }

        @Override
        protected void onPostExecute(UpdateOutcome outcome) {
            updateButton.setEnabled(true);
            updateProgressBar.setVisibility(View.GONE);
            if (outcome == null) {
                statusText.setText("Update failed unexpectedly.");
                return;
            }
            statusText.setText(outcome.message);
            if (outcome.success) {
                rebootButton.setVisibility(View.VISIBLE);
                Toast.makeText(MainActivity.this, "Certificates updated successfully.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(MainActivity.this, "Update failed \u2014 see status or terminal log.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // -----------------------------------------------------------------
    // Reboot
    // -----------------------------------------------------------------

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_reboot)
                .setMessage("Reboot the device now to fully apply the new certificates?")
                .setPositiveButton("Reboot", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        new RebootTask().execute();
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private class RebootTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... voids) {
            RootUtils.executeAsRoot(new String[]{"reboot"}, 15000);
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            // If the device actually reboots, this callback may never fire at all.
            Toast.makeText(MainActivity.this, "Reboot command sent.", Toast.LENGTH_SHORT).show();
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private File getExternalFilesDirCompat() {
        File dir = getExternalFilesDir(null);
        return dir != null ? dir : getFilesDir();
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }

    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    File dir = getExternalFilesDirCompat();
                    File logFile = new File(dir, "crash_log.txt");
                    PrintWriter pw = new PrintWriter(new FileWriter(logFile, true));
                    pw.println("=== Crash at " + new Date() + " ===");
                    ex.printStackTrace(pw);
                    pw.close();
                } catch (Throwable ignored) {
                    // Best-effort only; the logger itself must never cause a secondary crash.
                }
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, ex);
                }
            }
        });
    }

    private void showFatalInitError(Throwable t) {
        try {
            TextView errorView = new TextView(this);
            errorView.setPadding(32, 64, 32, 32);
            errorView.setText("CA Cert Updater failed to start:\n\n" + RootUtils.describeThrowable(t)
                    + "\n\nPlease reinstall the app, or report this via the terminal log if available.");
            setContentView(errorView);
        } catch (Throwable ignored) {
            // If even this fails, there is nothing further we can safely do.
        }
    }
}
