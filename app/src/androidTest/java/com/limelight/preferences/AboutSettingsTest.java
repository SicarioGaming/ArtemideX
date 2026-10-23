package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.BuildConfig;
import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Settings > About, and the Credits and Open-source licences pages it opens. */
@RunWith(AndroidJUnit4.class)
public class AboutSettingsTest {
    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void aboutIsTheLastSection() {
        try (ActivityScenario<StreamSettings> scenario = ActivityScenario.launch(StreamSettings.class)) {
            SystemClock.sleep(1500);
            scenario.onActivity(activity -> {
                LinearLayout pills = activity.findViewById(R.id.settingsSections);
                TextView last = (TextView) pills.getChildAt(pills.getChildCount() - 1);
                assertEquals(activity.getString(R.string.category_about), last.getText().toString());

                PreferenceFragment fragment = (PreferenceFragment)
                        activity.getFragmentManager().findFragmentById(R.id.stream_settings);
                Preference version = fragment.findPreference("about_version");
                assertNotNull(version);
                String summary = version.getSummary().toString();
                assertTrue(summary, summary.startsWith(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"));
                // Built from a git checkout, so the commit is known
                assertTrue("commit " + BuildConfig.GIT_COMMIT, BuildConfig.GIT_COMMIT.matches("[0-9a-f]{12}"));
                assertTrue(summary, summary.contains(BuildConfig.GIT_COMMIT));

                for (String key : new String[] {"about_github", "about_issues", "about_source",
                        "about_credits", "about_licences"}) {
                    Preference item = fragment.findPreference(key);
                    assertNotNull(key, item);
                    assertTrue(key + " has no summary", !TextUtils.isEmpty(item.getSummary()));
                }
                assertTrue(fragment.findPreference("about_source").getSummary().toString()
                        .contains(BuildConfig.VERSION_NAME));
            });
        }
    }

    @Test
    public void versionDetailsTellBuildsApart() {
        String details = StreamSettings.SettingsFragment.versionDetails();
        assertTrue(details, details.startsWith("Starwire " + BuildConfig.VERSION_NAME));
        assertTrue(details, details.contains(BuildConfig.GIT_COMMIT));
        assertTrue(details, details.contains(BuildConfig.BUILD_TYPE));
        assertTrue(details, details.contains("API " + Build.VERSION.SDK_INT));
        assertTrue(details, details.contains(Build.MODEL));
    }

    @Test
    public void licencesPageListsEveryComponent() {
        checkPage(OpenSourceActivity.PAGE_LICENCES, R.string.title_about_licences, 4);
    }

    @Test
    public void creditsPageListsEveryProject() {
        checkPage(OpenSourceActivity.PAGE_CREDITS, R.string.title_about_credits, 5);
    }

    private void checkPage(String page, int title, int sections) {
        Intent intent = new Intent(target, OpenSourceActivity.class)
                .putExtra(OpenSourceActivity.EXTRA_PAGE, page);
        try (ActivityScenario<OpenSourceActivity> scenario = ActivityScenario.launch(intent)) {
            SystemClock.sleep(1500);
            scenario.onActivity(activity -> {
                assertEquals(activity.getString(title),
                        ((TextView) activity.findViewById(R.id.settingsTitle)).getText().toString());
                LinearLayout pills = activity.findViewById(R.id.settingsSections);
                assertEquals(page + " section pills", sections, pills.getChildCount());

                PreferenceFragment fragment = (PreferenceFragment)
                        activity.getFragmentManager().findFragmentById(R.id.stream_settings);
                PreferenceScreen screen = fragment.getPreferenceScreen();
                assertEquals(sections, screen.getPreferenceCount());
                int items = 0;
                for (int i = 0; i < screen.getPreferenceCount(); i++) {
                    PreferenceCategory category = (PreferenceCategory) screen.getPreference(i);
                    assertTrue(category.getTitle() + " is empty", category.getPreferenceCount() > 0);
                    for (int j = 0; j < category.getPreferenceCount(); j++) {
                        Preference item = category.getPreference(j);
                        assertTrue(!TextUtils.isEmpty(item.getTitle()));
                        assertTrue(item.getTitle() + " has no summary", !TextUtils.isEmpty(item.getSummary()));
                        items++;
                    }
                }
                int expected = page.equals(OpenSourceActivity.PAGE_CREDITS) ?
                        com.limelight.ui.AboutCredits.ALL.length : com.limelight.ui.OpenSourceNotices.ALL.length;
                assertEquals(expected, items);
            });
        }
    }
}
