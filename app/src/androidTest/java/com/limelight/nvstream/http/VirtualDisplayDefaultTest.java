package com.limelight.nvstream.http;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.limelight.nvstream.StreamConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A new stream leaves the display to the host. On the device because StreamConfiguration loads
 * the native library; the launch argument itself is a JVM test (VirtualDisplayLaunchTest).
 */
@RunWith(AndroidJUnit4.class)
public class VirtualDisplayDefaultTest {
    @Test
    public void streamDefaultsToHostDefault() {
        StreamConfiguration config = new StreamConfiguration.Builder().build();
        assertEquals(StreamConfiguration.VIRTUAL_DISPLAY_HOST_DEFAULT, config.getVirtualDisplay());
    }
}
