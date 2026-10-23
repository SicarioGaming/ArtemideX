package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The parts of the stream settings that don't need the device: default bitrates, Warp factors,
 * screen shapes and the options a game can set for itself.
 */
public class PreferenceConfigurationTest {
    @Test
    public void defaultBitrateTable() {
        assertEquals(1000, PreferenceConfiguration.getDefaultBitrate("640x360", "30"));
        assertEquals(10000, PreferenceConfiguration.getDefaultBitrate("1280x720", "60"));
        assertEquals(10000, PreferenceConfiguration.getDefaultBitrate("1920x1080", "30"));
        assertEquals(20000, PreferenceConfiguration.getDefaultBitrate("1920x1080", "60"));
        assertEquals(40000, PreferenceConfiguration.getDefaultBitrate("2560x1440", "60"));
        assertEquals(80000, PreferenceConfiguration.getDefaultBitrate("3840x2160", "60"));
        // Above 60 FPS it grows with the square root of the rate
        assertEquals(24000, PreferenceConfiguration.getDefaultBitrate("1920x1080", "90"));
        assertEquals(28000, PreferenceConfiguration.getDefaultBitrate("1920x1080", "120"));
    }

    // Sizes between the table's rows are interpolated by pixel count; outside it, they're clamped
    @Test
    public void defaultBitrateBetweenAndBeyondTheTable() {
        // 1920x1200 and 2560x1600 tablets
        assertEquals(23000, PreferenceConfiguration.getDefaultBitrate("1920x1200", "60"));
        assertEquals(44000, PreferenceConfiguration.getDefaultBitrate("2560x1600", "60"));
        assertEquals(62000, PreferenceConfiguration.getDefaultBitrate("2560x1600", "120"));
        // Smaller than 360p counts as 360p, bigger than 4K as 4K
        assertEquals(2000, PreferenceConfiguration.getDefaultBitrate("320x240", "60"));
        assertEquals(80000, PreferenceConfiguration.getDefaultBitrate("7680x4320", "60"));
    }

    @Test
    public void warpFactors() {
        assertEquals(2, PreferenceConfiguration.warpFactorFor("warp"));
        assertEquals(4, PreferenceConfiguration.warpFactorFor("warp2"));
        assertEquals(1, PreferenceConfiguration.warpFactorFor("balanced"));
        assertEquals(1, PreferenceConfiguration.warpFactorFor("latency"));
        assertEquals(1, PreferenceConfiguration.warpFactorFor("WARP"));
        assertEquals(1, PreferenceConfiguration.warpFactorFor(null));
    }

    // The preset sizes in the resolution list aren't "native"; anything else, including a
    // portrait preset, is the device's own size
    @Test
    public void nativeResolutions() {
        for (String preset : new String[] {"640x360", "854x480", "1280x720", "1920x1080", "2560x1440", "3840x2160"}) {
            String[] wh = preset.split("x");
            assertFalse(preset, PreferenceConfiguration.isNativeResolution(Integer.parseInt(wh[0]), Integer.parseInt(wh[1])));
        }
        assertTrue(PreferenceConfiguration.isNativeResolution(2560, 1600));
        assertTrue(PreferenceConfiguration.isNativeResolution(2208, 1840));
        assertTrue(PreferenceConfiguration.isNativeResolution(1080, 1920));
    }

    // Under 1.3:1 counts as square-ish, like a foldable's inner screen
    @Test
    public void squarishScreens() {
        assertTrue(PreferenceConfiguration.isSquarishScreen(2208, 1840));
        assertTrue(PreferenceConfiguration.isSquarishScreen(1840, 2208));
        assertTrue(PreferenceConfiguration.isSquarishScreen(1000, 1000));
        assertTrue(PreferenceConfiguration.isSquarishScreen(1299, 1000));
        assertFalse(PreferenceConfiguration.isSquarishScreen(1300, 1000));
        assertFalse(PreferenceConfiguration.isSquarishScreen(2560, 1600));
        assertFalse(PreferenceConfiguration.isSquarishScreen(1080, 2400));
    }

    // Every option App Settings can set per game, with each stored value and a value no version
    // of the app writes
    @Test
    public void everyGameOptionApplies() {
        PreferenceConfiguration c = new PreferenceConfiguration();
        String[][] formats = {{"auto", "AUTO"}, {"forceav1", "FORCE_AV1"}, {"forceh265", "FORCE_HEVC"},
                {"neverh265", "FORCE_H264"}, {"forcepyrowave", "FORCE_PYROWAVE"}, {"garbage", "AUTO"}};
        for (String[] f : formats) {
            c.applyGameOption("video_format", f[0]);
            assertEquals(f[0], PreferenceConfiguration.FormatOption.valueOf(f[1]), c.videoFormat);
        }

        c.applyGameOption("vsync_mode", "vsync");
        assertEquals(PreferenceConfiguration.VSYNC_ON, c.vsyncMode);
        c.applyGameOption("vsync_mode", "fast");
        assertEquals(PreferenceConfiguration.VSYNC_FAST, c.vsyncMode);
        c.applyGameOption("vsync_mode", "garbage");
        assertEquals(PreferenceConfiguration.VSYNC_OFF, c.vsyncMode);

        c.applyGameOption("hide_cursor_mode", "touching");
        assertEquals(PreferenceConfiguration.HIDE_CURSOR_WHILE_TOUCHING, c.hideCursorMode);
        c.applyGameOption("hide_cursor_mode", "always");
        assertEquals(PreferenceConfiguration.HIDE_CURSOR_ALWAYS, c.hideCursorMode);
        c.applyGameOption("hide_cursor_mode", "garbage");
        assertEquals(PreferenceConfiguration.HIDE_CURSOR_NEVER, c.hideCursorMode);

        String[][] haptics = {{"device", "1"}, {"controller", "2"}, {"both", "3"}, {"off", "0"}, {"garbage", "0"}};
        for (String[] h : haptics) {
            c.applyGameOption("list_audio_haptics", h[0]);
            assertEquals(h[0], Integer.parseInt(h[1]), c.audioHapticsOutputs);
        }

        c.applyGameOption("virtual_display_mode", "virtual");
        assertEquals("virtual", c.virtualDisplayMode);

        // The on/off options, stored as "true"/"false"
        c.applyGameOption("checkbox_sync_lfr", "true");
        c.applyGameOption("checkbox_async_decode", "true");
        c.applyGameOption("checkbox_async_lfr", "true");
        c.applyGameOption("checkbox_touchscreen_trackpad", "true");
        assertTrue(c.syncLfr && c.asyncDecode && c.asyncLfr && c.touchscreenTrackpad);
        c.applyGameOption("checkbox_sync_lfr", "false");
        c.applyGameOption("checkbox_async_decode", "yes");
        c.applyGameOption("checkbox_async_lfr", "");
        c.applyGameOption("checkbox_touchscreen_trackpad", "FALSE");
        assertFalse(c.syncLfr || c.asyncDecode || c.asyncLfr || c.touchscreenTrackpad);
    }

    @Test
    public void unknownGameOptionsChangeNothing() {
        PreferenceConfiguration c = new PreferenceConfiguration();
        c.applyGameOption("vsync_mode", "fast");
        c.applyGameOption("not_an_option", "fast");
        c.applyGameOption("game_vsync_mode", "vsync");
        assertEquals(PreferenceConfiguration.VSYNC_FAST, c.vsyncMode);
    }

    // Each key the per-game screen saves is one applyGameOption handles
    @Test
    public void gameOptionKeysAreAllHandled() {
        for (String key : PreferenceConfiguration.GAME_OPTION_KEYS) {
            PreferenceConfiguration before = new PreferenceConfiguration();
            PreferenceConfiguration after = new PreferenceConfiguration();
            after.applyGameOption(key, valueThatChanges(key));
            assertFalse(key + " does nothing", same(before, after));
        }
    }

    private static String valueThatChanges(String key) {
        switch (key) {
            case "video_format": return "forceav1";
            case "vsync_mode": return "vsync";
            case "virtual_display_mode": return "virtual";
            case "hide_cursor_mode": return "always";
            case "list_audio_haptics": return "both";
            default: return "true";
        }
    }

    private static boolean same(PreferenceConfiguration a, PreferenceConfiguration b) {
        return a.videoFormat == b.videoFormat && a.vsyncMode == b.vsyncMode && a.syncLfr == b.syncLfr
                && a.asyncDecode == b.asyncDecode && a.asyncLfr == b.asyncLfr
                && java.util.Objects.equals(a.virtualDisplayMode, b.virtualDisplayMode)
                && a.touchscreenTrackpad == b.touchscreenTrackpad && a.hideCursorMode == b.hideCursorMode
                && a.audioHapticsOutputs == b.audioHapticsOutputs;
    }
}
