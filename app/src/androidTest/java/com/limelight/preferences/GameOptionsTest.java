package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.preference.ListPreference;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.limelight.R;

/**
 * Voidlink's options in per-game App Settings (AppSettings.gameOptions).
 */
@RunWith(AndroidJUnit4.class)
public class GameOptionsTest {
    private static final String APP_KEY = "test-pc:gameoptions";
    private static final String QUICK_LAUNCH_KEY = "test-pc:gameoptions:1";

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After
    public void removeTestSettings() {
        context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE).edit()
                .remove(APP_KEY).remove(QUICK_LAUNCH_KEY).commit();
    }

    private AppPreferences.AppSettings gameSettings(boolean useGlobal) {
        AppPreferences.AppSettings settings = new AppPreferences.AppSettings();
        settings.useGlobalSettings = useGlobal;
        settings.gameOptions.put("video_format", "forcepyrowave");
        settings.gameOptions.put("vsync_mode", "fast");
        settings.gameOptions.put("checkbox_async_decode", "true");
        settings.gameOptions.put("checkbox_async_lfr", "true");
        settings.gameOptions.put("hide_cursor_mode", "always");
        settings.gameOptions.put("list_audio_haptics", "both");
        settings.gameOptions.put("checkbox_touchscreen_trackpad", "false");
        settings.gameOptions.put("virtual_display_mode", "virtual");
        return settings;
    }

    @Test
    public void optionsSurviveSavingAndLoading() {
        AppPreferences.saveAppSettings(context, APP_KEY, gameSettings(false));
        AppPreferences.AppSettings loaded = AppPreferences.getAppSettings(context, APP_KEY);
        assertEquals("fast", loaded.gameOptions.get("vsync_mode"));
        assertEquals("both", loaded.gameOptions.get("list_audio_haptics"));
        assertEquals(8, loaded.gameOptions.size());
    }

    @Test
    public void gameOptionsReplaceTheGlobalOnes() {
        AppPreferences.saveAppSettings(context, APP_KEY, gameSettings(false));
        PreferenceConfiguration config = AppPreferences.getEffectivePreferences(context, APP_KEY, null, false);

        assertEquals(PreferenceConfiguration.FormatOption.FORCE_PYROWAVE, config.videoFormat);
        assertEquals(PreferenceConfiguration.VSYNC_FAST, config.vsyncMode);
        assertTrue(config.asyncDecode);
        assertTrue(config.asyncLfr);
        assertEquals(PreferenceConfiguration.HIDE_CURSOR_ALWAYS, config.hideCursorMode);
        assertEquals(3, config.audioHapticsOutputs);
        assertFalse(config.touchscreenTrackpad);
        assertEquals("virtual", config.virtualDisplayMode);
    }

    @Test
    public void optionsNotSetForTheGameKeepTheGlobalOnes() {
        PreferenceConfiguration global = PreferenceConfiguration.readPreferences(context);
        AppPreferences.AppSettings settings = new AppPreferences.AppSettings();
        settings.useGlobalSettings = false;
        settings.gameOptions.put("vsync_mode", "vsync");
        AppPreferences.saveAppSettings(context, APP_KEY, settings);

        PreferenceConfiguration config = AppPreferences.getEffectivePreferences(context, APP_KEY, null, false);
        assertEquals(PreferenceConfiguration.VSYNC_ON, config.vsyncMode);
        assertEquals(global.hideCursorMode, config.hideCursorMode);
        assertEquals(global.audioHapticsOutputs, config.audioHapticsOutputs);
        assertEquals(global.videoFormat, config.videoFormat);
    }

    @Test
    public void asyncLfrNeedsAsyncDecode() {
        AppPreferences.AppSettings settings = new AppPreferences.AppSettings();
        settings.useGlobalSettings = false;
        settings.gameOptions.put("checkbox_async_decode", "false");
        settings.gameOptions.put("checkbox_async_lfr", "true");
        AppPreferences.saveAppSettings(context, APP_KEY, settings);

        PreferenceConfiguration config = AppPreferences.getEffectivePreferences(context, APP_KEY, null, false);
        assertFalse(config.asyncLfr);
    }

    @Test
    public void useGlobalSettingsIgnoresTheGameOptions() {
        PreferenceConfiguration global = PreferenceConfiguration.readPreferences(context);
        AppPreferences.saveAppSettings(context, APP_KEY, gameSettings(true));

        PreferenceConfiguration config = AppPreferences.getEffectivePreferences(context, APP_KEY, null, false);
        assertEquals(global.vsyncMode, config.vsyncMode);
        assertEquals(global.audioHapticsOutputs, config.audioHapticsOutputs);
    }

    @Test
    public void appSettingsScreenShowsAndSavesTheOptions() {
        AppPreferences.saveAppSettings(context, APP_KEY, gameSettings(false));
        Intent intent = new Intent(context, AppStreamSettings.class)
                .putExtra(AppStreamSettings.EXTRA_APP_KEY, APP_KEY)
                .putExtra(AppStreamSettings.EXTRA_APP_NAME, "Test Game");

        try (ActivityScenario<AppStreamSettings> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                AppStreamSettings.AppSettingsFragment fragment = (AppStreamSettings.AppSettingsFragment)
                        activity.getFragmentManager().findFragmentById(R.id.stream_settings);
                ListPreference vsync = (ListPreference) fragment.findPreference("game_vsync_mode");
                assertEquals("fast", vsync.getValue());
                assertTrue(vsync.isEnabled());
                ListPreference haptics = (ListPreference) fragment.findPreference("game_list_audio_haptics");
                assertEquals("[Use global default]", haptics.getEntries()[0].toString());

                // Back to the global default for V-Sync, and audio haptics on the controller
                vsync.setValue("");
                haptics.setValue("controller");
                fragment.saveSettings();
            });
        }

        AppPreferences.AppSettings saved = AppPreferences.getAppSettings(context, APP_KEY);
        assertFalse(saved.gameOptions.containsKey("vsync_mode"));
        assertEquals("controller", saved.gameOptions.get("list_audio_haptics"));
        assertEquals("always", saved.gameOptions.get("hide_cursor_mode"));
    }

    @Test
    public void quickLaunchOptionsComeFirst() {
        AppPreferences.saveAppSettings(context, APP_KEY, gameSettings(false));
        AppPreferences.AppSettings quick = new AppPreferences.AppSettings();
        quick.useGlobalSettings = false;
        quick.gameOptions.put("vsync_mode", "off");
        AppPreferences.saveAppSettings(context, QUICK_LAUNCH_KEY, quick);

        PreferenceConfiguration config = AppPreferences.getEffectivePreferences(context, APP_KEY, QUICK_LAUNCH_KEY, false);
        assertEquals(PreferenceConfiguration.VSYNC_OFF, config.vsyncMode);
        // Not set for the quick launch item, so the game's own
        assertEquals(PreferenceConfiguration.HIDE_CURSOR_ALWAYS, config.hideCursorMode);
    }
}
