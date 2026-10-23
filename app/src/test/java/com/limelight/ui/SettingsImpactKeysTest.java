package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * Which settings get an "Impact:" line, including the per-game copies of them. How the line is
 * shown on the settings screen is checked on the device (SettingsImpactTest).
 */
public class SettingsImpactKeysTest {
    @Test
    public void streamSettingsHaveImpactLines() {
        for (String key : new String[] {"list_resolution", "list_fps", "seekbar_bitrate_kbps", "frame_pacing",
                "vsync_mode", "checkbox_async_decode", "checkbox_async_lfr", "video_format", "video_renderer"}) {
            assertNotEquals(key + " has no impact line", 0, SettingsImpact.impactFor(key));
        }
        // Per-game settings get the same lines as the main ones
        assertEquals(SettingsImpact.impactFor("frame_pacing"), SettingsImpact.impactFor("list_app_frame_pacing"));
        assertEquals(SettingsImpact.impactFor("vsync_mode"), SettingsImpact.impactFor("game_vsync_mode"));
        assertEquals(SettingsImpact.impactFor("list_fps"), SettingsImpact.impactFor("text_app_fps"));
        // Settings with nothing to say about speed get no line
        assertEquals(0, SettingsImpact.impactFor("list_languages"));
        assertEquals(0, SettingsImpact.impactFor(null));
    }

    // Only the start of a key is taken as the per-game prefix
    @Test
    public void onlyALeadingGamePrefixIsDropped() {
        assertEquals(SettingsImpact.impactFor("list_audio_haptics"), SettingsImpact.impactFor("game_list_audio_haptics"));
        assertEquals(0, SettingsImpact.impactFor("game_"));
        assertEquals(0, SettingsImpact.impactFor("x_game_vsync_mode"));
        assertEquals(0, SettingsImpact.impactFor("game_game_vsync_mode"));
    }
}
