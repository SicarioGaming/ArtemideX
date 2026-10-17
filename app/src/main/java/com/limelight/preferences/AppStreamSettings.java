package com.limelight.preferences;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.limelight.R;
import com.limelight.ui.CoverGlowSettings;
import com.limelight.utils.UiHelper;

public class AppStreamSettings extends Activity {
    public static final String EXTRA_APP_KEY = "AppKey";
    public static final String EXTRA_APP_NAME = "AppName";

    private String appKey;
    private String appName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        appKey = getIntent().getStringExtra(EXTRA_APP_KEY);
        appName = getIntent().getStringExtra(EXTRA_APP_NAME);

        if (appKey == null || appName == null) {
            finish();
            return;
        }

        UiHelper.setLocale(this);
        setContentView(R.layout.activity_stream_settings);
        setTitle("Settings - " + appName);
        CoverGlowSettings.setUpHeader(this, getString(R.string.cg_settings_for, appName));

        getFragmentManager().beginTransaction().replace(
                R.id.stream_settings, new AppSettingsFragment()
        ).commitAllowingStateLoss();

        UiHelper.notifyNewRootView(this);
    }

    // Starwire options: the global setting's key (stored in AppSettings.gameOptions, and the
    // preference here is "game_" plus it), and the choices' names and values
    private static final Object[][] GAME_OPTIONS = {
            {"video_format", R.array.video_format_names, R.array.video_format_values},
            {"vsync_mode", R.array.vsync_mode_names, R.array.vsync_mode_values},
            {"checkbox_sync_lfr", R.array.hdr_setting_names, R.array.hdr_setting_values},
            {"checkbox_async_decode", R.array.hdr_setting_names, R.array.hdr_setting_values},
            {"checkbox_async_lfr", R.array.hdr_setting_names, R.array.hdr_setting_values},
            {"virtual_display_mode", R.array.virtual_display_mode_names, R.array.virtual_display_mode_values},
            {"checkbox_touchscreen_trackpad", R.array.game_touch_mode_names, R.array.game_touch_mode_values},
            {"hide_cursor_mode", R.array.hide_cursor_mode_names, R.array.hide_cursor_mode_values},
            {"list_audio_haptics", R.array.audio_haptics_names, R.array.audio_haptics_values},
            {"jitter_buffer", R.array.jitter_buffer_names, R.array.jitter_buffer_values},
    };

    public static class AppSettingsFragment extends PreferenceFragment {
        private boolean isQuickLaunch;

        // The name of the option for leaving a setting to the app's or global setting
        private String getDefaultEntryName() {
            return isQuickLaunch ? "[Use app default]" : "[Use global default]";
        }

        private void setupListPreference(ListPreference pref, int entriesResourceId, int valuesResourceId, String currentValue) {
            // Get original arrays from resources
            String[] originalEntries = getResources().getStringArray(entriesResourceId);
            String[] originalValues = getResources().getStringArray(valuesResourceId);

            // Create new arrays with default option at the beginning
            String[] newEntries = new String[originalEntries.length + 1];
            String[] newValues = new String[originalValues.length + 1];

            // Use different label depending on whether this is a quick launch item or app
            newEntries[0] = getDefaultEntryName();
            newValues[0] = ""; // Empty string represents null/default

            // Copy original arrays starting from index 1
            System.arraycopy(originalEntries, 0, newEntries, 1, originalEntries.length);
            System.arraycopy(originalValues, 0, newValues, 1, originalValues.length);

            // Update the preference
            pref.setEntryValues(newValues);
            pref.setEntries(newEntries);

            // Set current value (null becomes empty string for default)
            pref.setValue(currentValue == null ? "" : currentValue);
        }

        // A list's empty value represents the default, which is saved as null
        private static String emptyToNull(String value) {
            return value != null && value.isEmpty() ? null : value;
        }

        private static int parseInt(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        // Shows a list's chosen entry, or the default entry, as its summary
        private static void showChosenEntry(ListPreference pref) {
            CharSequence[] entries = pref.getEntries();
            int index = pref.findIndexOfValue(pref.getValue() == null ? "" : pref.getValue());
            if (entries != null && entries.length > 0) {
                pref.setSummary(entries[index >= 0 && index < entries.length ? index : 0]);
            }
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = super.onCreateView(inflater, container, savedInstanceState);
            UiHelper.applyStatusBarPadding(view);
            return view;
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            CoverGlowSettings.attach(this, view);
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            addPreferencesFromResource(R.xml.app_preferences);

            AppStreamSettings activity = (AppStreamSettings) getActivity();

            AppPreferences.AppSettings currentSettings = AppPreferences.getAppSettings(getActivity(), activity.appKey);

            // Detect if this is a quick launch item (key format: uuid:appid:timestamp vs uuid:appid)
            isQuickLaunch = activity.appKey != null && activity.appKey.split(":").length == 3;

            CheckBoxPreference useGlobalPref = (CheckBoxPreference) findPreference("checkbox_use_global_settings");
            ResolutionListPreference resolutionPref = (ResolutionListPreference) findPreference("list_app_resolution");
            FpsListPreference fpsPref = (FpsListPreference) findPreference("list_app_fps");
            EditTextPreference bitratePref = (EditTextPreference) findPreference("text_app_bitrate_kbps");
            EditTextPreference actualDisplayRefreshRatePref = (EditTextPreference) findPreference("text_app_actual_display_refresh_rate");
            ListPreference enableHdrPref = (ListPreference) findPreference("list_app_enable_hdr");
            ListPreference enablePerfOverlayPref = (ListPreference) findPreference("list_app_enable_perf_overlay");
            ListPreference framePacingPref = (ListPreference) findPreference("list_app_frame_pacing");

            // Update checkbox label and summary based on whether this is a quick launch item
            if (isQuickLaunch) {
                useGlobalPref.setTitle("Use App Settings");
                useGlobalPref.setSummary("Do not use custom settings, instead use the app or global settings");
            }

            // The refresh rate's summary shows its value, so its help goes in its dialog
            actualDisplayRefreshRatePref.setDialogMessage(actualDisplayRefreshRatePref.getSummary());

            // Initialize fields
            useGlobalPref.setChecked(currentSettings.useGlobalSettings);
            // Resolution and FPS go straight to entering a value, with
            // Clear going back to the default
            resolutionPref.setCustomOnly(true);
            resolutionPref.setDefaultEntry(getDefaultEntryName());
            resolutionPref.setValue(currentSettings.resolution != null ? currentSettings.resolution : "");
            fpsPref.setCustomOnly(true);
            fpsPref.setDefaultEntry(getDefaultEntryName());
            fpsPref.setValue(currentSettings.fps > 0 ? String.valueOf(currentSettings.fps) : "");
            bitratePref.setText(currentSettings.bitrate > 0 ? String.valueOf(currentSettings.bitrate / 1000) : "");
            actualDisplayRefreshRatePref.setText(currentSettings.actualDisplayRefreshRate > 0 ? String.valueOf(currentSettings.actualDisplayRefreshRate) : "");
            setupListPreference(framePacingPref, R.array.video_frame_pacing_names, R.array.video_frame_pacing_values, currentSettings.framePacing);
            setupListPreference(enableHdrPref, R.array.hdr_setting_names, R.array.hdr_setting_values, currentSettings.enableHdr);
            setupListPreference(enablePerfOverlayPref, R.array.perf_overlay_setting_names, R.array.perf_overlay_setting_values, currentSettings.enablePerfOverlay);
            for (Object[] option : GAME_OPTIONS) {
                ListPreference pref = (ListPreference) findPreference("game_" + option[0]);
                setupListPreference(pref, (Integer) option[1], (Integer) option[2],
                        currentSettings.gameOptions.get((String) option[0]));
            }

            updatePreferenceSummaries();
            updatePreferenceStates(useGlobalPref.isChecked());

            // Set the app-specific category title with the app name
            PreferenceCategory appCategory = (PreferenceCategory) findPreference("category_app_specific");
            if (appCategory != null) {
                appCategory.setTitle(activity.appName + " Settings");
            }

            // Save each change once the preference has taken its new value
            Preference.OnPreferenceChangeListener saveOnChange = new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            updatePreferenceSummaries();
                            saveSettings();
                        }
                    });
                    return true;
                }
            };
            resolutionPref.setOnPreferenceChangeListener(saveOnChange);
            fpsPref.setOnPreferenceChangeListener(saveOnChange);
            bitratePref.setOnPreferenceChangeListener(saveOnChange);
            framePacingPref.setOnPreferenceChangeListener(saveOnChange);
            actualDisplayRefreshRatePref.setOnPreferenceChangeListener(saveOnChange);
            enableHdrPref.setOnPreferenceChangeListener(saveOnChange);
            enablePerfOverlayPref.setOnPreferenceChangeListener(saveOnChange);
            for (Object[] option : GAME_OPTIONS) {
                findPreference("game_" + option[0]).setOnPreferenceChangeListener(saveOnChange);
            }

            useGlobalPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            boolean useGlobal = (Boolean) newValue;
                            updatePreferenceStates(useGlobal);
                            saveSettings();
                        }
                    });
                    return true;
                }
            });
        }

        private void updatePreferenceStates(boolean useGlobal) {
            findPreference("list_app_resolution").setEnabled(!useGlobal);
            findPreference("list_app_fps").setEnabled(!useGlobal);
            findPreference("text_app_bitrate_kbps").setEnabled(!useGlobal);
            findPreference("list_app_frame_pacing").setEnabled(!useGlobal);
            findPreference("text_app_actual_display_refresh_rate").setEnabled(!useGlobal);
            findPreference("list_app_enable_hdr").setEnabled(!useGlobal);
            findPreference("list_app_enable_perf_overlay").setEnabled(!useGlobal);
            for (Object[] option : GAME_OPTIONS) {
                findPreference("game_" + option[0]).setEnabled(!useGlobal);
            }
        }

        private void updatePreferenceSummaries() {
            EditTextPreference bitratePref = (EditTextPreference) findPreference("text_app_bitrate_kbps");
            EditTextPreference actualDisplayRefreshRatePref = (EditTextPreference) findPreference("text_app_actual_display_refresh_rate");

            // Resolution and FPS: the standard entry's name (like "1080p"), the custom
            // value, or the default entry
            showChosenEntry((ListPreference) findPreference("list_app_resolution"));
            showChosenEntry((ListPreference) findPreference("list_app_fps"));

            // Set bitrate summary
            String bitrateMbps = bitratePref.getText();
            if (bitrateMbps != null && !bitrateMbps.isEmpty() && !bitrateMbps.equals("0")) {
                bitratePref.setSummary(bitrateMbps + " Mbps");
            } else {
                bitratePref.setSummary(getDefaultEntryName());
            }

            // Frame pacing, HDR and the perf overlay: the human readable name or default label
            showChosenEntry((ListPreference) findPreference("list_app_frame_pacing"));
            showChosenEntry((ListPreference) findPreference("list_app_enable_hdr"));
            showChosenEntry((ListPreference) findPreference("list_app_enable_perf_overlay"));

            // Set actual display refresh rate summary
            String actualDisplayRefreshRate = actualDisplayRefreshRatePref.getText();
            if (actualDisplayRefreshRate != null && !actualDisplayRefreshRate.isEmpty() && !actualDisplayRefreshRate.equals("0")) {
                actualDisplayRefreshRatePref.setSummary(actualDisplayRefreshRate + "Hz");
            } else {
                actualDisplayRefreshRatePref.setSummary(getDefaultEntryName());
            }

            // Starwire options: the chosen entry's name, or the default label
            for (Object[] option : GAME_OPTIONS) {
                showChosenEntry((ListPreference) findPreference("game_" + option[0]));
            }
        }

        public void saveSettings() {
            AppStreamSettings activity = (AppStreamSettings) getActivity();
            if (activity == null) return;

            CheckBoxPreference useGlobalPref = (CheckBoxPreference) findPreference("checkbox_use_global_settings");
            ListPreference resolutionPref = (ListPreference) findPreference("list_app_resolution");
            ListPreference fpsPref = (ListPreference) findPreference("list_app_fps");
            EditTextPreference bitratePref = (EditTextPreference) findPreference("text_app_bitrate_kbps");
            ListPreference framePacingPref = (ListPreference) findPreference("list_app_frame_pacing");
            EditTextPreference actualDisplayRefreshRatePref = (EditTextPreference) findPreference("text_app_actual_display_refresh_rate");
            ListPreference enableHdrPref = (ListPreference) findPreference("list_app_enable_hdr");
            ListPreference enablePerfOverlayPref = (ListPreference) findPreference("list_app_enable_perf_overlay");

            // 0 represents the default for FPS
            int fps = parseInt(fpsPref.getValue());

            // Convert bitrate from Mbps back to kbps for storage
            int bitrateKbps = parseInt(bitratePref.getText()) * 1000;

            double actualDisplayRefreshRate = 0;
            String actualDisplayRefreshRateText = actualDisplayRefreshRatePref.getText();
            if (actualDisplayRefreshRateText != null && !actualDisplayRefreshRateText.isEmpty()) {
                try {
                    actualDisplayRefreshRate = Double.parseDouble(actualDisplayRefreshRateText);
                } catch (NumberFormatException ignored) {
                }
            }

            // Empty strings represent the default and are saved as null
            AppPreferences.AppSettings settings = new AppPreferences.AppSettings(
                emptyToNull(resolutionPref.getValue()),
                fps,
                emptyToNull(framePacingPref.getValue()),
                bitrateKbps,
                actualDisplayRefreshRate,
                emptyToNull(enableHdrPref.getValue()),
                emptyToNull(enablePerfOverlayPref.getValue()),
                useGlobalPref.isChecked()
            );
            for (Object[] option : GAME_OPTIONS) {
                String value = ((ListPreference) findPreference("game_" + option[0])).getValue();
                if (value != null && !value.isEmpty()) {
                    settings.gameOptions.put((String) option[0], value);
                }
            }

            AppPreferences.saveAppSettings(getActivity(), activity.appKey, settings);
        }
    }
}
