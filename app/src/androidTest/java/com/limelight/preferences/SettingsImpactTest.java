package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.R;
import com.limelight.ui.SettingsImpact;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * From Reddit: say what each setting does to latency and performance. Settings that change speed,
 * smoothness or picture quality show an "Impact:" line under their description, and the rows
 * still work as before.
 */
@RunWith(AndroidJUnit4.class)
public class SettingsImpactTest {
    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

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

    @Test
    public void impactLineFollowsTheDescription() {
        CharSequence text = SettingsImpact.withImpact(target, "Description", R.string.impact_vsync);
        // A blank (half-height) line separates the two
        assertEquals("Description\n\n" + target.getString(R.string.impact_label) + " "
                + target.getString(R.string.impact_vsync), text.toString());
        // A setting without a description still shows the line
        assertTrue(SettingsImpact.withImpact(target, null, R.string.impact_none).toString()
                .startsWith(target.getString(R.string.impact_label)));
    }

    @Test
    public void settingsScreenShowsTheLinesAndRowsStillWork() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(target);
        boolean stretch = prefs.getBoolean("checkbox_stretch_video", false);
        try (ActivityScenario<StreamSettings> scenario =
                     ActivityScenario.launch(new Intent(target, StreamSettings.class))) {
            SystemClock.sleep(1500);
            scenario.onActivity(activity -> {
                ListView list = activity.findViewById(android.R.id.list);
                ListAdapter adapter = list.getAdapter();
                int resolution = positionOf(adapter, "list_resolution");
                int languages = positionOf(adapter, "list_languages");
                int stretchRow = positionOf(adapter, "checkbox_stretch_video");

                assertTrue(summaryOf(list, adapter, resolution).contains(target.getString(R.string.impact_resolution)));
                assertFalse(summaryOf(list, adapter, languages).contains(target.getString(R.string.impact_label)));

                // Tapping a row still reaches its setting
                list.performItemClick(null, stretchRow, adapter.getItemId(stretchRow));
                assertEquals(!stretch, prefs.getBoolean("checkbox_stretch_video", false));
                list.performItemClick(null, stretchRow, adapter.getItemId(stretchRow));
                assertEquals(stretch, prefs.getBoolean("checkbox_stretch_video", false));
            });
        }
        finally {
            prefs.edit().putBoolean("checkbox_stretch_video", stretch).apply();
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

    private static String summaryOf(ListView list, ListAdapter adapter, int position) {
        TextView summary = adapter.getView(position, null, list).findViewById(android.R.id.summary);
        return summary != null ? summary.getText().toString() : "";
    }
}
