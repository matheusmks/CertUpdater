package com.androidafterlife.certupdater;

/**
 * Runs the actual on-device install: remount /system rw, back up the
 * existing cacerts, copy in the new ones, fix permissions, remount ro.
 * This mirrors the manual steps from the r/androidafterlife tutorial, just
 * driven from one root shell session instead of typed by hand over adb.
 */
public final class CertInstaller {

    public static final String SYSTEM_CACERTS_DIR = "/system/etc/security/cacerts";

    public static final class InstallResult {
        public final boolean success;
        public final String message;
        public final String backupPath;

        InstallResult(boolean success, String message, String backupPath) {
            this.success = success;
            this.message = message;
            this.backupPath = backupPath;
        }
    }

    private CertInstaller() {
    }

    public static InstallResult install(String stagingDirPath, int fileCount) {
        if (fileCount <= 0) {
            return new InstallResult(false, "Nothing to install: 0 certificate files were staged.", null);
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        String backupDir = SYSTEM_CACERTS_DIR + "_backup_" + timestamp;
        String marker = "__CERT_INSTALL_DONE_" + timestamp + "__";

        // NOTE: a plain shell keeps running after a failed command (there is
        // no "set -e" here), and the overall exit code of an interactive su
        // session basically always ends up 0 because the very last thing we
        // send is a bare "exit". So we do NOT trust the overall process exit
        // code to mean "the copy actually worked" — instead the critical
        // "cp" step explicitly reports its own success/failure via a
        // sentinel we can search for in the captured output.
        String[] commands = new String[]{
                // Different ROMs/toolchains accept slightly different remount syntax;
                // try the common ones in order, "||" only moves on if the previous one failed.
                "mount -o rw,remount,rw /system 2>/dev/null || mount -o remount,rw /system 2>/dev/null || busybox mount -o remount,rw /system 2>/dev/null",
                "mkdir -p " + backupDir,
                "cp -a " + SYSTEM_CACERTS_DIR + "/. " + backupDir + "/ 2>/dev/null",
                "cp -f " + stagingDirPath + "/*.0 " + SYSTEM_CACERTS_DIR + "/ && echo __COPY_OK__ || echo __COPY_FAIL__",
                "chmod 644 " + SYSTEM_CACERTS_DIR + "/*.0 2>/dev/null",
                "chown root:root " + SYSTEM_CACERTS_DIR + "/*.0 2>/dev/null || chown 0:0 " + SYSTEM_CACERTS_DIR + "/*.0 2>/dev/null",
                "mount -o ro,remount,ro /system 2>/dev/null || mount -o remount,ro /system 2>/dev/null || busybox mount -o remount,ro /system 2>/dev/null",
                "echo " + marker
        };

        RootUtils.CommandResult result = RootUtils.executeAsRoot(commands, 60000);
        boolean copyOk = result.output.contains("__COPY_OK__");
        boolean reachedEnd = result.output.contains(marker);
        boolean success = copyOk && reachedEnd;

        String message;
        if (success) {
            message = "Installed " + fileCount + " certificate file(s). Previous certs backed up to " + backupDir + ".";
        } else if (!reachedEnd) {
            message = "Installation did not finish (root access was denied or the shell was killed). Check the terminal log.";
        } else {
            message = "Copying the certificate files into /system failed (remount read-write likely failed on this device/ROM). Check the terminal log.";
        }

        return new InstallResult(success, message, reachedEnd ? backupDir : null);
    }
}
