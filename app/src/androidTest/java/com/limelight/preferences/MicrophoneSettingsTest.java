package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.R;
import com.limelight.binding.audio.MicrophoneCaptureManager;
import com.limelight.nvstream.StreamConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

/**
 * The microphone settings: off unless chosen (it only works with logabell's Apollo build), the
 * chosen microphone is read back, and the stream is only told to ask the host when it's on.
 */
@RunWith(AndroidJUnit4.class)
public class MicrophoneSettingsTest {
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
    public void offByDefault() {
        prefs.edit().remove(PreferenceConfiguration.ENABLE_MICROPHONE_PREF_STRING)
                .remove(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING).commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertFalse(config.enableMicrophone);
        assertEquals(0, config.microphoneDeviceId);
    }

    @Test
    public void readsTheChosenMicrophone() {
        prefs.edit().putBoolean(PreferenceConfiguration.ENABLE_MICROPHONE_PREF_STRING, true)
                .putString(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING, "7").commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertTrue(config.enableMicrophone);
        assertEquals(7, config.microphoneDeviceId);
    }

    @Test
    public void aBrokenChoiceFallsBackToTheDefaultMicrophone() {
        prefs.edit().putString(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING, "not a number").commit();
        assertEquals(0, PreferenceConfiguration.readPreferences(context).microphoneDeviceId);
    }

    @Test
    public void theStreamOnlyAsksForTheMicrophoneWhenItsOn() {
        StreamConfiguration off = new StreamConfiguration.Builder().build();
        assertFalse(off.getEnableMicrophone());
        StreamConfiguration on = new StreamConfiguration.Builder().setEnableMicrophone(true).build();
        assertTrue(on.getEnableMicrophone());
    }

    @Test
    public void vbanIsChosenWithItsStreamNameAndPort() {
        // Default: over the stream, to logabell's Apollo build, with VBAN's usual name and port
        prefs.edit().remove(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING)
                .remove(PreferenceConfiguration.VBAN_STREAM_NAME_PREF_STRING)
                .remove(PreferenceConfiguration.VBAN_PORT_PREF_STRING).commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertFalse(config.microphoneToVban);
        assertEquals("Stream1", config.vbanStreamName);
        assertEquals(6980, config.vbanPort);

        prefs.edit().putString(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING, "vban")
                .putString(PreferenceConfiguration.VBAN_STREAM_NAME_PREF_STRING, "Phone")
                .putString(PreferenceConfiguration.VBAN_PORT_PREF_STRING, " 6990 ").commit();
        config = PreferenceConfiguration.readPreferences(context);
        assertTrue(config.microphoneToVban);
        assertEquals("Phone", config.vbanStreamName);
        assertEquals(6990, config.vbanPort);

        // A port that can't be used falls back to 6980
        for (String bad : new String[] {"0", "70000", "port"}) {
            prefs.edit().putString(PreferenceConfiguration.VBAN_PORT_PREF_STRING, bad).commit();
            assertEquals(bad, 6980, PreferenceConfiguration.readPreferences(context).vbanPort);
        }
    }

    @Test
    public void vbanSettingsSayWhatToEnterOnThePc() {
        prefs.edit().putBoolean(PreferenceConfiguration.ENABLE_MICROPHONE_PREF_STRING, true)
                .putString(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING, "vban")
                .putString(PreferenceConfiguration.VBAN_STREAM_NAME_PREF_STRING, "Phone")
                .putString(PreferenceConfiguration.VBAN_PORT_PREF_STRING, "6990").commit();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                context.getPackageName(), android.Manifest.permission.RECORD_AUDIO);

        try (ActivityScenario<StreamSettings> scenario =
                     ActivityScenario.launch(new Intent(context, StreamSettings.class))) {
            SystemClock.sleep(1500);
            scenario.onActivity(activity -> {
                ListView list = activity.findViewById(android.R.id.list);
                ListAdapter adapter = list.getAdapter();
                Preference target = preference(adapter, "list_microphone_target");
                Preference setup = preference(adapter, "vban_setup_info");

                assertEquals(activity.getString(R.string.microphone_target_vban), target.getSummary().toString());
                // Only the VBAN rows show, and the steps carry the chosen name and port
                assertTrue(setup.isEnabled());
                assertTrue(preference(adapter, "text_vban_stream_name").isEnabled());
                assertFalse(isShown(adapter, "microphone_host_info"));
                String steps = setup.getSummary().toString();
                assertTrue(steps, steps.contains("Phone") && steps.contains("6990") && steps.contains("CABLE Input"));

                // Switching to the host swaps the rows (the list catches up on its next frame)
                target.getOnPreferenceChangeListener().onPreferenceChange(target, "host");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ListAdapter adapter = ((ListView) activity.findViewById(android.R.id.list)).getAdapter();
                assertTrue(isShown(adapter, "microphone_host_info"));
                assertFalse(isShown(adapter, "vban_setup_info"));
                assertFalse(isShown(adapter, "text_vban_port"));
                Preference target = preference(adapter, "list_microphone_target");
                target.getOnPreferenceChangeListener().onPreferenceChange(target, "vban");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ListView list = activity.findViewById(android.R.id.list);
                ListAdapter adapter = list.getAdapter();
                // And back, each row in its place
                assertFalse(isShown(adapter, "microphone_host_info"));
                assertTrue(positionOf(adapter, "list_microphone_target") < positionOf(adapter, "vban_setup_info"));
                assertTrue(positionOf(adapter, "vban_setup_info") < positionOf(adapter, "text_vban_port"));

                // A picture of the microphone rows, for a look
                list.setSelection(positionOf(adapter, "checkbox_enable_microphone"));
                android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                View root = activity.getWindow().getDecorView();
                root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, metrics.widthPixels, metrics.heightPixels);
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(metrics.widthPixels,
                        metrics.heightPixels, android.graphics.Bitmap.Config.ARGB_8888);
                root.draw(new android.graphics.Canvas(bitmap));
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                        new java.io.File(activity.getExternalFilesDir(null), "settings_microphone_vban.png"))) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
                catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static int positionOf(ListAdapter adapter, String key) {
        for (int i = 0; i < adapter.getCount(); i++) {
            Object item = adapter.getItem(i);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                return i;
            }
        }
        throw new AssertionError(key + " isn't on the settings screen");
    }

    private static boolean isShown(ListAdapter adapter, String key) {
        for (int i = 0; i < adapter.getCount(); i++) {
            Object item = adapter.getItem(i);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                return true;
            }
        }
        return false;
    }

    private static Preference preference(ListAdapter adapter, String key) {
        return (Preference) adapter.getItem(positionOf(adapter, key));
    }

    @Test
    public void listingMicrophonesNeverFails() {
        // Whatever inputs the device has, the settings list must build without crashing
        assertNotNull(MicrophoneCaptureManager.getAvailableInputDevices(context));
    }
}
