package com.limelight.testing;

import android.content.pm.PackageManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Assume;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * What the device under test has, so a test that needs real hardware skips on an emulator (a skip
 * shows in the results; it isn't a pass) instead of failing there:
 *
 * <pre>
 *   DeviceCaps.assumeHardware("a microphone", DeviceCaps.hasMicrophone());
 * </pre>
 *
 * With {@code -e starwire.requireHardware true} the same calls fail instead of skipping, so a
 * device that should have the hardware can't quietly skip those tests.
 */
public final class DeviceCaps {
    private static Boolean emulator;

    private DeviceCaps() {}

    /**
     * The Android Emulator, whatever the system image. Same test as device-test.sh and
     * device-run.sh: ro.boot.qemu or ro.kernel.qemu is 1.
     */
    public static synchronized boolean isEmulator() {
        if (emulator == null) {
            emulator = "1".equals(getprop("ro.boot.qemu")) || "1".equals(getprop("ro.kernel.qemu"));
        }
        return emulator;
    }

    static String getprop(String name) {
        try {
            Process p = new ProcessBuilder("getprop", name).redirectErrorStream(true).start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = in.readLine();
                p.waitFor();
                return line == null ? "" : line.trim();
            }
        }
        catch (IOException e) {
            return "";
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** A hardware decoder for this MIME type: not a software one, and not the emulator's own. */
    public static boolean hasHardwareDecoder(String mime) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            boolean supports = false;
            for (String type : info.getSupportedTypes()) {
                supports |= type.equalsIgnoreCase(mime);
            }
            if (!supports) {
                continue;
            }
            String name = info.getName().toLowerCase(Locale.ROOT);
            boolean software = name.startsWith("omx.google.") || name.startsWith("c2.android.")
                    || name.contains("goldfish") || name.contains("ranchu");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                software |= info.isSoftwareOnly() || !info.isHardwareAccelerated();
            }
            if (!software) {
                return true;
            }
        }
        return false;
    }

    /** A microphone the app could record from. (CI emulators run with -noaudio.) */
    public static boolean hasMicrophone() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_MICROPHONE) && !isEmulator();
    }

    /** Whether the run was started with -e starwire.requireHardware true. */
    public static boolean requireHardware() {
        return "true".equals(InstrumentationRegistry.getArguments().getString("starwire.requireHardware"));
    }

    /** Skips the test when the hardware is missing, or fails it under starwire.requireHardware. */
    public static void assumeHardware(String what, boolean present) {
        if (present) {
            return;
        }
        String message = "needs " + what + " (" + Build.MANUFACTURER + " " + Build.MODEL
                + (isEmulator() ? ", an emulator" : "") + ")";
        if (requireHardware()) {
            Assert.fail(message);
        }
        Assume.assumeTrue(message, false);
    }
}
