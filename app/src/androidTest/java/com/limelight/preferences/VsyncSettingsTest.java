package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

/**
 * The V-Sync and Sync LFR settings read back as the renderer expects, and default to off so
 * Moonlight X's frame pacing is unchanged unless they're chosen.
 */
@RunWith(AndroidJUnit4.class)
public class VsyncSettingsTest {
    private Context context;
    private SharedPreferences prefs;
    private Map<String, ?> saved;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // Keep whatever settings the app had, and put them back afterwards
        saved = prefs.getAll();
    }

    @After
    public void tearDown() {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, ?> e : saved.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) editor.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) editor.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) editor.putLong(e.getKey(), (Long) v);
            else if (v instanceof Float) editor.putFloat(e.getKey(), (Float) v);
            else if (v instanceof String) editor.putString(e.getKey(), (String) v);
        }
        editor.commit();
    }

    @Test
    public void defaultsAreOff() {
        prefs.edit().remove("vsync_mode").remove("checkbox_sync_lfr").commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertEquals(PreferenceConfiguration.VSYNC_OFF, config.vsyncMode);
        assertFalse(config.syncLfr);
    }

    @Test
    public void readsEachVsyncMode() {
        prefs.edit().putString("vsync_mode", "vsync").commit();
        assertEquals(PreferenceConfiguration.VSYNC_ON, PreferenceConfiguration.readPreferences(context).vsyncMode);

        prefs.edit().putString("vsync_mode", "fast").commit();
        assertEquals(PreferenceConfiguration.VSYNC_FAST, PreferenceConfiguration.readPreferences(context).vsyncMode);

        prefs.edit().putString("vsync_mode", "off").commit();
        assertEquals(PreferenceConfiguration.VSYNC_OFF, PreferenceConfiguration.readPreferences(context).vsyncMode);
    }

    @Test
    public void readsSyncLfr() {
        prefs.edit().putBoolean("checkbox_sync_lfr", true).commit();
        assertTrue(PreferenceConfiguration.readPreferences(context).syncLfr);
    }

    @Test
    public void warpModesAskForMoreFramesAndShowTheNewest() {
        prefs.edit().putString("frame_pacing", "warp").commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertEquals(PreferenceConfiguration.FRAME_PACING_MIN_LATENCY, config.framePacing);
        assertEquals(2, config.warpFactor);

        prefs.edit().putString("frame_pacing", "warp2").commit();
        config = PreferenceConfiguration.readPreferences(context);
        assertEquals(PreferenceConfiguration.FRAME_PACING_MIN_LATENCY, config.framePacing);
        assertEquals(4, config.warpFactor);

        for (String other : new String[]{"latency", "balanced", "cap-fps", "smoothness", "host-timed"}) {
            prefs.edit().putString("frame_pacing", other).commit();
            assertEquals(other, 1, PreferenceConfiguration.readPreferences(context).warpFactor);
        }
    }

    @Test
    public void touchDefaults() {
        prefs.edit().remove("checkbox_long_press_right_click").remove("hide_cursor_mode").commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertTrue(config.longPressRightClick);
        assertEquals(PreferenceConfiguration.HIDE_CURSOR_NEVER, config.hideCursorMode);

        prefs.edit().putString("hide_cursor_mode", "touching").commit();
        assertEquals(PreferenceConfiguration.HIDE_CURSOR_WHILE_TOUCHING,
                PreferenceConfiguration.readPreferences(context).hideCursorMode);
        prefs.edit().putString("hide_cursor_mode", "always").commit();
        assertEquals(PreferenceConfiguration.HIDE_CURSOR_ALWAYS,
                PreferenceConfiguration.readPreferences(context).hideCursorMode);
    }

    @Test
    public void asyncDecodeIsOffByDefaultAndLfrNeedsIt() {
        prefs.edit().remove("checkbox_async_decode").remove("checkbox_async_lfr").commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertFalse(config.asyncDecode);
        assertFalse(config.asyncLfr);

        // Async LFR on its own does nothing without async decode
        prefs.edit().putBoolean("checkbox_async_lfr", true).commit();
        assertFalse(PreferenceConfiguration.readPreferences(context).asyncLfr);

        prefs.edit().putBoolean("checkbox_async_decode", true).commit();
        config = PreferenceConfiguration.readPreferences(context);
        assertTrue(config.asyncDecode);
        assertTrue(config.asyncLfr);
    }
}
