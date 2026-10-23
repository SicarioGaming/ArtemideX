package com.limelight.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.media.MediaFormat;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.AssumptionViolatedException;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The checks tests use to skip on emulators: they have to tell an emulator from a real device, or
 * hardware tests would fail on CI or quietly skip on the maintainer's devices.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceCapsTest {
    @Test
    public void emulatorAgreesWithTheHardwareName() {
        // Every Android Emulator image reports ranchu (or goldfish before Android 7) as its hardware
        boolean qemuHardware = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish");
        assertEquals("ro.boot.qemu/ro.kernel.qemu against Build.HARDWARE " + Build.HARDWARE,
                qemuHardware, DeviceCaps.isEmulator());
    }

    @Test
    public void onlyRealDevicesHaveAHardwareDecoder() {
        assertEquals(!DeviceCaps.isEmulator(), DeviceCaps.hasHardwareDecoder(MediaFormat.MIMETYPE_VIDEO_AVC));
    }

    @Test
    public void hardwareThatIsThereLetsTheTestGoOn() {
        DeviceCaps.assumeHardware("nothing in particular", true);
    }

    @Test
    public void missingHardwareSkipsOrFailsWhenItIsRequired() {
        Throwable thrown = null;
        try {
            DeviceCaps.assumeHardware("a test part", false);
        }
        catch (Throwable t) {
            thrown = t;
        }
        assertNotNull("assumeHardware let the test go on without the hardware", thrown);
        assertTrue(thrown.getMessage(), thrown.getMessage().startsWith("needs a test part ("));
        if (DeviceCaps.requireHardware()) {
            assertEquals(AssertionError.class, thrown.getClass());
        }
        else {
            assertTrue(thrown.getClass().getName(), thrown instanceof AssumptionViolatedException);
        }
    }

    // device-test.sh leaves this out on an emulator; if it ever runs on one, that filter broke
    @Test
    @HardwareOnly("a real device")
    public void hardwareOnlyTestsNeverRunOnAnEmulator() {
        assertFalse("a @HardwareOnly test ran on an emulator", DeviceCaps.isEmulator());
    }
}
