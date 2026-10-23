package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * A game's own settings (App Settings) and a quick launch item's: how they're stored, and the
 * order they're laid over the global settings in (quick launch, then the game, then global).
 * Saving through SharedPreferences and the App Settings screen are checked on the device
 * (GameOptionsTest).
 */
public class AppSettingsTest {
    @Test
    public void jsonRoundTrip() throws Exception {
        AppPreferences.AppSettings s = new AppPreferences.AppSettings("1920x1080", 120, "warp", 50000, 119.88, "true", null, false);
        s.gameOptions.put("vsync_mode", "fast");
        s.gameOptions.put("list_audio_haptics", "both");
        AppPreferences.AppSettings back = AppPreferences.AppSettings.fromJson(new JSONObject(s.toJson().toString()));
        assertEquals("1920x1080", back.resolution);
        assertEquals(120, back.fps);
        assertEquals("warp", back.framePacing);
        assertEquals(50000, back.bitrate);
        assertEquals(119.88, back.actualDisplayRefreshRate, 0);
        assertEquals("true", back.enableHdr);
        assertFalse(back.useGlobalSettings);
        assertEquals(2, back.gameOptions.size());
        assertEquals("fast", back.gameOptions.get("vsync_mode"));
        // A field left unset is left out of the JSON, so it comes back unset, not as "null"
        assertNull("enablePerfOverlay", back.enablePerfOverlay);
    }

    @Test
    public void settingsFromAnOlderVersionStillLoad() throws Exception {
        // Saved by a build before gameOptions existed
        AppPreferences.AppSettings old = AppPreferences.AppSettings.fromJson(
                new JSONObject("{\"resolution\":\"1280x720\",\"fps\":60,\"useGlobalSettings\":false}"));
        assertEquals(60, old.fps);
        assertEquals("1280x720", old.resolution);
        assertNull(old.framePacing);
        assertEquals(0, old.bitrate);
        assertTrue(old.gameOptions.isEmpty());
        // And with nothing at all, a game uses the global settings
        assertTrue(AppPreferences.AppSettings.fromJson(new JSONObject("{}")).useGlobalSettings);
    }

    // The global settings the tests start from
    private static PreferenceConfiguration global() {
        PreferenceConfiguration c = new PreferenceConfiguration();
        c.width = 1920;
        c.height = 1080;
        c.fps = 60;
        c.bitrate = 20000;
        c.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
        c.actualDisplayRefreshRate = "60.0";
        c.enableHdr = false;
        c.enablePerfOverlay = false;
        c.vsyncMode = PreferenceConfiguration.VSYNC_OFF;
        c.hideCursorMode = PreferenceConfiguration.HIDE_CURSOR_NEVER;
        return c;
    }

    private static AppPreferences.AppSettings game() {
        AppPreferences.AppSettings s = new AppPreferences.AppSettings("2560x1440", 120, "warp2", 60000, 119.88, "true", "true", false);
        s.gameOptions.put("vsync_mode", "fast");
        s.gameOptions.put("hide_cursor_mode", "always");
        return s;
    }

    @Test
    public void aGamesOwnSettingsReplaceTheGlobalOnes() {
        PreferenceConfiguration c = global();
        AppPreferences.applyAppSettings(c, null, game());
        assertEquals(2560, c.width);
        assertEquals(1440, c.height);
        assertEquals(120, c.fps);
        assertEquals(60000, c.bitrate);
        assertEquals(PreferenceConfiguration.FRAME_PACING_MIN_LATENCY, c.framePacing);
        assertEquals(4, c.warpFactor);
        assertEquals("119.88", c.actualDisplayRefreshRate);
        assertTrue(c.enableHdr);
        assertTrue(c.enablePerfOverlay);
        assertEquals(PreferenceConfiguration.VSYNC_FAST, c.vsyncMode);
        assertEquals(PreferenceConfiguration.HIDE_CURSOR_ALWAYS, c.hideCursorMode);
    }

    @Test
    public void aGameUsingTheGlobalSettingsChangesNothing() {
        AppPreferences.AppSettings s = game();
        s.useGlobalSettings = true;
        PreferenceConfiguration c = global();
        AppPreferences.applyAppSettings(c, null, s);
        assertEquals(1920, c.width);
        assertEquals(60, c.fps);
        assertEquals(20000, c.bitrate);
        assertEquals(1, c.warpFactor);
        assertFalse(c.enableHdr);
        assertEquals(PreferenceConfiguration.VSYNC_OFF, c.vsyncMode);
    }

    // Unset fields (null, 0, a missing option) keep the global value
    @Test
    public void settingsAGameLeavesUnsetKeepTheGlobalOnes() {
        AppPreferences.AppSettings s = new AppPreferences.AppSettings(null, 0, null, 0, 0, null, null, false);
        s.gameOptions.put("vsync_mode", "vsync");
        PreferenceConfiguration c = global();
        AppPreferences.applyAppSettings(c, null, s);
        assertEquals(1920, c.width);
        assertEquals(60, c.fps);
        assertEquals(20000, c.bitrate);
        assertEquals(PreferenceConfiguration.FRAME_PACING_BALANCED, c.framePacing);
        assertEquals("60.0", c.actualDisplayRefreshRate);
        assertEquals(PreferenceConfiguration.VSYNC_ON, c.vsyncMode);
        assertEquals(PreferenceConfiguration.HIDE_CURSOR_NEVER, c.hideCursorMode);
    }

    @Test
    public void quickLaunchSettingsComeFirst() {
        AppPreferences.AppSettings quick = new AppPreferences.AppSettings("1280x720", 0, "latency", 0, 0, "false", null, false);
        quick.gameOptions.put("vsync_mode", "off");
        PreferenceConfiguration c = global();
        AppPreferences.applyAppSettings(c, quick, game());
        assertEquals(1280, c.width);
        assertEquals(720, c.height);
        assertEquals(PreferenceConfiguration.FRAME_PACING_MIN_LATENCY, c.framePacing);
        assertEquals(1, c.warpFactor);
        assertFalse(c.enableHdr);
        assertEquals(PreferenceConfiguration.VSYNC_OFF, c.vsyncMode);
        // Not set for the quick launch item, so the game's own
        assertEquals(120, c.fps);
        assertEquals(60000, c.bitrate);
        assertTrue(c.enablePerfOverlay);
        assertEquals(PreferenceConfiguration.HIDE_CURSOR_ALWAYS, c.hideCursorMode);
    }

    @Test
    public void asyncLfrNeedsAsyncDecode() {
        AppPreferences.AppSettings s = new AppPreferences.AppSettings(null, 0, null, 0, 0, null, null, false);
        s.gameOptions.put("checkbox_async_decode", "false");
        s.gameOptions.put("checkbox_async_lfr", "true");
        PreferenceConfiguration c = global();
        AppPreferences.applyAppSettings(c, null, s);
        assertFalse(c.asyncLfr);

        s.gameOptions.put("checkbox_async_decode", "true");
        c = global();
        AppPreferences.applyAppSettings(c, null, s);
        assertTrue(c.asyncLfr);

        // Also when async LFR was on globally and the game turns async decode off
        AppPreferences.AppSettings off = new AppPreferences.AppSettings(null, 0, null, 0, 0, null, null, false);
        off.gameOptions.put("checkbox_async_decode", "false");
        c = global();
        c.asyncDecode = true;
        c.asyncLfr = true;
        AppPreferences.applyAppSettings(c, null, off);
        assertFalse(c.asyncLfr);
    }

    // A resolution that doesn't read as WIDTHxHEIGHT keeps the global one
    @Test
    public void aBrokenResolutionKeepsTheGlobalOne() {
        for (String bad : new String[] {"1080p", "x", "axb", "1920x1080x2"}) {
            AppPreferences.AppSettings s = new AppPreferences.AppSettings(bad, 0, null, 0, 0, null, null, false);
            PreferenceConfiguration c = global();
            AppPreferences.applyAppSettings(c, null, s);
            assertEquals(bad, 1920, c.width);
            assertEquals(bad, 1080, c.height);
        }
    }
}
