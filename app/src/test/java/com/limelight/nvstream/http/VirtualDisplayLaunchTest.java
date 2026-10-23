package com.limelight.nvstream.http;

import static org.junit.Assert.assertEquals;

import com.limelight.nvstream.StreamConfiguration;

import org.junit.Test;

/**
 * The virtualDisplay launch argument Vibepollo reads (nvhttp.cpp: any non-zero value asks for
 * a virtual display, 0 turns virtual display mode off for the session). That the stream's
 * default is "host default" is checked on the device (VirtualDisplayDefaultTest), since building
 * a StreamConfiguration loads the native library.
 */
public class VirtualDisplayLaunchTest {
    @Test
    public void hostDefaultSendsNothing() {
        assertEquals("", NvHTTP.virtualDisplayLaunchArgument(false, StreamConfiguration.VIRTUAL_DISPLAY_HOST_DEFAULT));
    }

    @Test
    public void virtualAndPrimaryAreSent() {
        assertEquals("&virtualDisplay=1", NvHTTP.virtualDisplayLaunchArgument(false, StreamConfiguration.VIRTUAL_DISPLAY_ON));
        assertEquals("&virtualDisplay=0", NvHTTP.virtualDisplayLaunchArgument(false, StreamConfiguration.VIRTUAL_DISPLAY_OFF));
    }

    @Test
    public void neverSentToGeForceExperience() {
        assertEquals("", NvHTTP.virtualDisplayLaunchArgument(true, StreamConfiguration.VIRTUAL_DISPLAY_ON));
    }
}
