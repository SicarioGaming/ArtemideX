package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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
    public void listingMicrophonesNeverFails() {
        // Whatever inputs the device has, the settings list must build without crashing
        assertNotNull(MicrophoneCaptureManager.getAvailableInputDevices(context));
    }
}
