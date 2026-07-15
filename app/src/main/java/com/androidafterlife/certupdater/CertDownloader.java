package com.androidafterlife.certupdater;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads the current CA certificate bundle straight from Google's public
 * AOSP source archive (the same bundle shipped on modern Android builds):
 *
 *   https://android.googlesource.com/platform/system/ca-certificates/+archive/refs/heads/main/files.tar.gz
 *
 * This endpoint is served behind a certificate that very old Android trust
 * stores already recognize (Google's own infrastructure certs predate the
 * problem this app fixes), which is why it's reachable even from devices
 * whose trust store is otherwise too old to reach most modern HTTPS sites.
 * If a particular device still can't reach it, the "import from local
 * storage" option is the fallback.
 */
public final class CertDownloader {

    public static final String DEFAULT_ARCHIVE_URL =
            "https://android.googlesource.com/platform/system/ca-certificates/+archive/refs/heads/main/files.tar.gz";

    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    public static final class DownloadException extends Exception {
        public DownloadException(String message, Throwable cause) {
            super(message, cause);
        }

        public DownloadException(String message) {
            super(message);
        }
    }

    private CertDownloader() {
    }

    public static File download(String urlString, File destinationFile, ProgressListener listener)
            throws DownloadException {

        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;

        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "CertUpdaterApp/1.0 (Android)");
            connection.connect();

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new DownloadException("Server returned HTTP " + status + " " + connection.getResponseMessage());
            }

            long totalBytes = connection.getContentLength();
            input = new BufferedInputStream(connection.getInputStream());
            output = new FileOutputStream(destinationFile);

            byte[] buffer = new byte[8192];
            long bytesRead = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                bytesRead += read;
                if (listener != null) {
                    listener.onProgress(bytesRead, totalBytes);
                }
            }
            output.flush();

            if (bytesRead == 0) {
                throw new DownloadException("Downloaded file was empty");
            }

            return destinationFile;

        } catch (IOException e) {
            throw new DownloadException("Network error: " + e.getMessage(), e);
        } finally {
            closeQuietly(input);
            closeQuietly(output);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void closeQuietly(InputStream c) {
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }

    private static void closeQuietly(OutputStream c) {
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }
}
