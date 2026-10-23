package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Updating to 0.2.1 moves 0.2.0's microphone destination, "vban" (its default, not encrypted), to
 * Starwire's encrypted receiver once; after that the user's choice stays. On the device the same
 * is checked through the real preferences (MicrophoneSettingsTest).
 */
public class MicrophoneTargetTest {
    private static final String TARGET = PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING;
    private static final String DONE = PreferenceConfiguration.MICROPHONE_TARGET_MIGRATED_PREF_STRING;

    @Test
    public void vbanFrom020MovesToTheEncryptedReceiverOnce() {
        MemoryPrefs prefs = new MemoryPrefs();
        prefs.values.put(TARGET, "vban");
        PreferenceConfiguration.migrateMicrophoneTarget(prefs);
        assertEquals("starwire", prefs.values.get(TARGET));
        assertEquals(Boolean.TRUE, prefs.values.get(DONE));

        // Chosen again afterwards: it stays
        prefs.values.put(TARGET, "vban");
        PreferenceConfiguration.migrateMicrophoneTarget(prefs);
        PreferenceConfiguration.migrateMicrophoneTarget(prefs);
        assertEquals("vban", prefs.values.get(TARGET));
    }

    @Test
    public void otherChoicesAndFreshInstallsAreLeftAlone() {
        for (String target : new String[] {"host", "starwire"}) {
            MemoryPrefs prefs = new MemoryPrefs();
            prefs.values.put(TARGET, target);
            PreferenceConfiguration.migrateMicrophoneTarget(prefs);
            assertEquals(target, prefs.values.get(TARGET));
            assertEquals(Boolean.TRUE, prefs.values.get(DONE));
        }
        MemoryPrefs fresh = new MemoryPrefs();
        PreferenceConfiguration.migrateMicrophoneTarget(fresh);
        assertNull("nothing saved: the default (starwire) applies", fresh.values.get(TARGET));
        assertTrue(fresh.values.containsKey(DONE));
        // A fresh install that later picks the unencrypted option keeps it
        fresh.values.put(TARGET, "vban");
        PreferenceConfiguration.migrateMicrophoneTarget(fresh);
        assertEquals("vban", fresh.values.get(TARGET));
    }

    @Test
    public void theDefaultIsTheEncryptedReceiver() throws Exception {
        String xml = com.limelight.RepoFiles.read("app/src/main/res/xml/preferences.xml");
        int at = xml.indexOf("android:key=\"list_microphone_target\"");
        int end = xml.indexOf("/>", at);
        assertTrue(xml.substring(at, end), xml.substring(at, end).contains("android:defaultValue=\"starwire\""));
        String arrays = com.limelight.RepoFiles.read("app/src/main/res/values/arrays.xml").replace("\r\n", "\n");
        assertTrue(arrays.contains("<item>starwire</item>\n        <item>host</item>\n        <item>vban</item>"));
    }

    // Just enough SharedPreferences for the migration
    private static final class MemoryPrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return values;
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            return defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            return defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            return defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            Map<String, Object> changes = new HashMap<>();
            return new Editor() {
                @Override
                public Editor putString(String key, String value) {
                    changes.put(key, value);
                    return this;
                }

                @Override
                public Editor putStringSet(String key, Set<String> v) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Editor putInt(String key, int value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Editor putLong(String key, long value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Editor putFloat(String key, float value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Editor putBoolean(String key, boolean value) {
                    changes.put(key, value);
                    return this;
                }

                @Override
                public Editor remove(String key) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Editor clear() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean commit() {
                    apply();
                    return true;
                }

                @Override
                public void apply() {
                    values.putAll(changes);
                }
            };
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }
    }
}
