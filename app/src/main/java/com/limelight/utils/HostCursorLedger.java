package com.limelight.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;

/**
 * Remembers, per PC, that Starwire has hidden the PC's cursor and hasn't shown it again yet.
 * Vibepollo's cursor toggle is one switch for the whole host that only resets when Vibepollo
 * restarts, so if Starwire is killed while the cursor is hidden, this record is the only way
 * to know it has to be turned back on at the next connection.
 *
 * One small file per PC: it exists while the cursor is hidden. It's written without fsync,
 * which is enough to survive the app being killed and is cheap enough for every touch.
 */
public class HostCursorLedger {
    private static final Logger LOG = Logger.getLogger("HostCursor");

    private static final String DIR_NAME = "host_cursor";
    private static final String VERSION = "v1";

    /** A cursor that Starwire hid and hasn't shown again. */
    public static class Debt {
        public final long hiddenAtMs;
        public final int appId;
        public final String reason;

        Debt(long hiddenAtMs, int appId, String reason) {
            this.hiddenAtMs = hiddenAtMs;
            this.appId = appId;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return "hidden at " + hiddenAtMs + " (app " + appId + ", " + reason + ")";
        }
    }

    private final File file;
    private final String hostKey;
    private int appId;

    public HostCursorLedger(File baseDir, String hostKey) {
        this.hostKey = hostKey;
        this.file = new File(new File(baseDir, DIR_NAME), fileNameFor(hostKey));
    }

    /** The PC's UUID, or its address when the UUID isn't known. */
    public static String hostKey(String pcUuid, String host) {
        return pcUuid != null ? pcUuid : "addr:" + host;
    }

    static String fileNameFor(String hostKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(hostKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte b : hash) {
                name.append(String.format("%02x", b));
            }
            return name.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every Android has SHA-1, but keep a usable name anyway
            return Integer.toHexString(hostKey.hashCode());
        }
    }

    /** The open debt for this PC, or null when the cursor wasn't left hidden. */
    public Debt read() {
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[512];
            int length = in.read(buffer);
            String[] parts = length > 0 ?
                    new String(buffer, 0, length, StandardCharsets.UTF_8).trim().split(" ", 5) : new String[0];
            if (parts.length < 5 || !VERSION.equals(parts[0]) || !"hidden".equals(parts[1])) {
                LOG.warning("Unreadable cursor record for " + hostKey + ", ignoring it");
                return null;
            }
            return new Debt(Long.parseLong(parts[2]), Integer.parseInt(parts[3]), parts[4]);
        } catch (IOException | NumberFormatException e) {
            LOG.warning("Unreadable cursor record for " + hostKey + ": " + e);
            return null;
        }
    }

    /** Write down that the cursor is about to be hidden. Call before sending the toggle. */
    public void markHidden(int appId, String reason) {
        this.appId = appId;
        write(reason);
    }

    /** Change why the cursor is hidden, keeping it hidden (for the logs). */
    public void setReason(String reason) {
        Debt debt = read();
        if (debt != null) {
            appId = debt.appId;
            write(reason);
        }
    }

    /** The cursor was shown again, so there's nothing left to fix. */
    public void markShown() {
        if (file.exists() && !file.delete()) {
            LOG.warning("Couldn't remove the cursor record for " + hostKey);
        }
    }

    private void write(String reason) {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            LOG.warning("Couldn't create " + dir);
            return;
        }
        String line = VERSION + " hidden " + System.currentTimeMillis() + " " + appId + " " +
                (reason != null && !reason.isEmpty() ? reason.replace('\n', ' ') : "-");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warning("Couldn't save the cursor record for " + hostKey + ": " + e);
        }
    }
}
