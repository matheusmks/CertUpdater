package com.androidafterlife.certupdater;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns whatever the user handed us (a Google-style .tar.gz cert bundle, a
 * plain .zip, or a single .pem/.crt/.cer/.der file) into a flat directory of
 * Android-ready "<hash>.0" PEM files.
 *
 * Security note: every extracted entry name is sanitized down to a bare
 * filename (no directories, no "..") before it's written anywhere, so a
 * malicious archive can't write outside the staging directory.
 */
public final class ArchiveExtractor {

    private static final int BLOCK_SIZE = 512;

    private ArchiveExtractor() {
    }

    /** Result of importing a source file: how many cert files ended up staged. */
    public static int importAny(File source, File stagingDir) throws Exception {
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw new IOException("Could not create staging directory: " + stagingDir);
        }
        String lower = source.getName().toLowerCase(Locale.US);

        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            int n = extractTarGz(source, stagingDir);
            if (n == 0) throw new IOException("No certificate files found inside the archive.");
            return n;
        }
        if (lower.endsWith(".zip")) {
            int n = extractZip(source, stagingDir);
            if (n == 0) throw new IOException("No certificate files found inside the archive.");
            return n;
        }
        if (lower.endsWith(".0") || lower.endsWith(".pem") || lower.endsWith(".crt")
                || lower.endsWith(".cer") || lower.endsWith(".der")) {
            importSingleCertificate(source, stagingDir);
            return 1;
        }

        // Unknown extension: sniff the format instead of guessing from the name.
        if (looksLikeGzip(source)) {
            int n = extractTarGz(source, stagingDir);
            if (n > 0) return n;
        }
        if (looksLikeZip(source)) {
            int n = extractZip(source, stagingDir);
            if (n > 0) return n;
        }
        importSingleCertificate(source, stagingDir);
        return 1;
    }

    // ---------------------------------------------------------------
    // .tar.gz support (hand-rolled: java.util.zip has no tar reader)
    // ---------------------------------------------------------------

    public static int extractTarGz(File tarGzFile, File outDir) throws IOException {
        int count = 0;
        GZIPInputStream gzip = null;
        try {
            gzip = new GZIPInputStream(new BufferedInputStream(new FileInputStream(tarGzFile)));
            byte[] header = new byte[BLOCK_SIZE];
            String pendingLongName = null;

            while (true) {
                int read = readFully(gzip, header);
                if (read < BLOCK_SIZE || isAllZero(header)) {
                    break; // truncated stream or end-of-archive marker
                }

                String name = readCString(header, 0, 100);
                String prefix = readCString(header, 345, 155);
                if (prefix.length() > 0) {
                    name = prefix + "/" + name;
                }
                long size = parseOctal(header, 124, 12);
                char typeFlag = (char) header[156];
                long paddedSize = ((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE;

                if (typeFlag == 'L') {
                    // GNU long-name extension: payload is the real name of the NEXT entry.
                    byte[] longNameBytes = new byte[(int) size];
                    readFully(gzip, longNameBytes);
                    skip(gzip, paddedSize - size);
                    pendingLongName = new String(longNameBytes, "UTF-8").trim();
                    continue;
                }

                if (pendingLongName != null) {
                    name = pendingLongName;
                    pendingLongName = null;
                }

                if (typeFlag == '0' || typeFlag == 0) {
                    String safeName = sanitizeEntryName(name);
                    if (safeName != null) {
                        File outFile = new File(outDir, safeName);
                        FileOutputStream fos = new FileOutputStream(outFile);
                        try {
                            copyExact(gzip, fos, size);
                        } finally {
                            fos.close();
                        }
                        count++;
                        skip(gzip, paddedSize - size);
                    } else {
                        skip(gzip, paddedSize);
                    }
                } else {
                    // directory / symlink / other: discard content
                    skip(gzip, paddedSize);
                }
            }
        } finally {
            if (gzip != null) try { gzip.close(); } catch (IOException ignored) { }
        }
        return count;
    }

    // ---------------------------------------------------------------
    // .zip support
    // ---------------------------------------------------------------

    public static int extractZip(File zipFile, File outDir) throws IOException {
        int count = 0;
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String safeName = sanitizeEntryName(entry.getName());
                if (safeName == null) continue;
                File outFile = new File(outDir, safeName);
                FileOutputStream fos = new FileOutputStream(outFile);
                try {
                    byte[] buffer = new byte[8192];
                    int r;
                    while ((r = zis.read(buffer)) != -1) {
                        fos.write(buffer, 0, r);
                    }
                } finally {
                    fos.close();
                }
                count++;
            }
        } finally {
            if (zis != null) try { zis.close(); } catch (IOException ignored) { }
        }
        return count;
    }

    // ---------------------------------------------------------------
    // Single certificate file import
    // ---------------------------------------------------------------

    /**
     * Parses an arbitrary PEM/DER certificate, re-encodes it as clean PEM, and
     * saves it under the filename Android's cacerts directory expects: the
     * 8-hex-digit OpenSSL "old style" subject-name hash + ".0" — the same
     * naming convention used by the prebuilt files in Google's ca-certificates
     * archive, computed here in pure Java (MD5 of the DER-encoded subject
     * name, first 4 bytes, little-endian).
     */
    public static File importSingleCertificate(File certFile, File outDir) throws Exception {
        X509Certificate cert;
        FileInputStream fis = new FileInputStream(certFile);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            cert = (X509Certificate) cf.generateCertificate(fis);
        } finally {
            fis.close();
        }
        if (cert == null) {
            throw new IOException("Could not parse a certificate from " + certFile.getName());
        }

        byte[] derName = cert.getSubjectX500Principal().getEncoded();
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] digest = md5.digest(derName);
        long hash = (digest[0] & 0xffL)
                | ((digest[1] & 0xffL) << 8)
                | ((digest[2] & 0xffL) << 16)
                | ((digest[3] & 0xffL) << 24);
        String hashHex = String.format(Locale.US, "%08x", hash);

        File outFile = new File(outDir, hashHex + ".0");
        OutputStream out = new FileOutputStream(outFile);
        try {
            out.write("-----BEGIN CERTIFICATE-----\n".getBytes("UTF-8"));
            String base64 = Base64.encodeToString(cert.getEncoded(), Base64.NO_WRAP);
            for (int i = 0; i < base64.length(); i += 64) {
                int end = Math.min(i + 64, base64.length());
                out.write(base64.substring(i, end).getBytes("UTF-8"));
                out.write('\n');
            }
            out.write("-----END CERTIFICATE-----\n".getBytes("UTF-8"));
        } finally {
            out.close();
        }
        LogStore.append("Computed Android cert filename: " + hashHex + ".0");
        return outFile;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static String sanitizeEntryName(String rawName) {
        if (rawName == null) return null;
        String name = rawName.replace('\\', '/').trim();
        int slash = name.lastIndexOf('/');
        String base = (slash >= 0) ? name.substring(slash + 1) : name;
        base = base.trim();
        if (base.length() == 0 || base.equals(".") || base.equals("..") || base.contains("..")) {
            return null;
        }
        return base;
    }

    private static String readCString(byte[] data, int offset, int length) throws IOException {
        int len = 0;
        while (len < length && data[offset + len] != 0) len++;
        return new String(data, offset, len, "UTF-8");
    }

    private static long parseOctal(byte[] data, int offset, int length) {
        long result = 0;
        int end = offset + length;
        int i = offset;
        while (i < end && (data[i] == ' ' || data[i] == 0)) i++;
        while (i < end && data[i] >= '0' && data[i] <= '7') {
            result = (result << 3) + (data[i] - '0');
            i++;
        }
        return result;
    }

    private static boolean isAllZero(byte[] data) {
        for (byte b : data) if (b != 0) return false;
        return true;
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int r = in.read(buffer, total, buffer.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static void skip(InputStream in, long bytesToSkip) throws IOException {
        long remaining = bytesToSkip;
        byte[] junk = new byte[BLOCK_SIZE];
        while (remaining > 0) {
            int toRead = (int) Math.min(junk.length, remaining);
            int r = in.read(junk, 0, toRead);
            if (r < 0) break;
            remaining -= r;
        }
    }

    private static void copyExact(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int r = in.read(buffer, 0, toRead);
            if (r < 0) throw new IOException("Unexpected end of archive stream");
            out.write(buffer, 0, r);
            remaining -= r;
        }
    }

    private static boolean looksLikeGzip(File f) {
        byte[] magic = readMagic(f, 2);
        return magic != null && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b;
    }

    private static boolean looksLikeZip(File f) {
        byte[] magic = readMagic(f, 4);
        return magic != null && magic[0] == 'P' && magic[1] == 'K';
    }

    private static byte[] readMagic(File f, int n) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            byte[] buf = new byte[n];
            int read = readFully(fis, buf);
            return read == n ? buf : null;
        } catch (IOException e) {
            return null;
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ignored) { }
        }
    }
}
