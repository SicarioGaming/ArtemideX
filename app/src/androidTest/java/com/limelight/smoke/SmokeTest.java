package com.limelight.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.limelight.AppView;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.StreamSettings;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The screens a person goes through, driven the way they'd drive them (UI Automator): the app
 * opens to the PC list, every settings section opens from its pill, the screens survive being
 * recreated as on a rotation, and a paired PC shows its games. Nothing here streams or changes a
 * setting. The step that needs the PC skips when no paired PC is online. device-test.sh runs
 * these and pulls the screenshots.
 */
@RunWith(AndroidJUnit4.class)
public class SmokeTest {
    private final Smoke smoke = new Smoke();

    @Before
    public void setUp() {
        smoke.checkScreen();
        smoke.saveSettings();
    }

    @After
    public void tearDown() {
        smoke.restoreSettings();
    }

    @Test
    public void appOpensToThePcList() {
        smoke.launchApp();
        assertTrue("neither the PC grid nor the searching message showed",
                smoke.device.wait(Until.hasObject(smoke.id("fragmentView")), Smoke.WAIT_MS) ||
                        smoke.device.hasObject(smoke.id("no_pc_found_layout")));
        // Every saved PC gets a card, whether or not it's online
        ComputerDatabaseManager db = new ComputerDatabaseManager(smoke.target);
        List<ComputerDetails> saved;
        try {
            saved = db.getAllComputers();
        }
        finally {
            db.close();
        }
        for (ComputerDetails pc : saved) {
            assertTrue("a saved PC has no card",
                    smoke.device.wait(Until.hasObject(By.descStartsWith(pc.name + ", ")), Smoke.WAIT_MS));
        }
        smoke.shot("01-pc-list");
    }

    @Test
    public void everySettingsSectionOpensFromItsPill() {
        smoke.launchApp();
        HashMap<String, HashMap<String, Object>> before = smoke.readSettings();

        UiObject2 settings = smoke.device.wait(Until.findObject(smoke.id("settingsButton")), Smoke.WAIT_MS);
        assertNotNull("no settings button", settings);
        settings.click();
        assertTrue("settings didn't open", smoke.device.wait(Until.hasObject(smoke.id("stream_settings")), Smoke.WAIT_MS));

        List<String> pills = pillLabels();
        assertTrue("only " + pills.size() + " section pills", pills.size() >= 2);
        List<String> missed = new ArrayList<>();
        for (int i = 0; i < pills.size(); i++) {
            UiObject2 pill = findPill(pills.get(i));
            assertNotNull("can't reach the " + pills.get(i) + " pill", pill);
            pill.click();
            assertTrue("the " + pills.get(i) + " pill didn't light up", pill.wait(Until.selected(true), 3000));
            String stopped = waitForSection(i);
            if (stopped != null) {
                missed.add(pills.get(i) + ": " + stopped);
            }
            smoke.shot(String.format("02-settings-%02d", i));
        }
        assertTrue("these pills didn't bring their section to the top: " + missed, missed.isEmpty());

        // And the list goes to its very end. Moved in code, not swiped, so no swipe can catch a
        // slider or a switch on the way.
        Activity settingsScreen = Smoke.resumedActivity();
        assertNotNull("settings isn't in front", settingsScreen);
        int[] positions = new int[2];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ListView list = settingsScreen.findViewById(android.R.id.list);
            positions[1] = list.getCount() - 1;
            list.setSelection(positions[1]);
        });
        SystemClock.sleep(500);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ListView list = settingsScreen.findViewById(android.R.id.list);
            positions[0] = list.getLastVisiblePosition();
        });
        assertEquals("the last setting isn't on screen", positions[1], positions[0]);
        smoke.shot("03-settings-end");

        smoke.backToPcList();
        // Every setting there before is still there with its value. (A screen may save the
        // default of a setting it shows for the first time, so new ones are allowed.)
        HashMap<String, HashMap<String, Object>> after = smoke.readSettings();
        for (String file : before.keySet()) {
            for (Map.Entry<String, Object> setting : before.get(file).entrySet()) {
                assertEquals("looking through the settings changed " + setting.getKey(),
                        setting.getValue(), after.get(file).get(setting.getKey()));
            }
        }
    }

    // Null once the pill's section heading is at the top of the list, or showing with the list as
    // far down as it goes (the last sections are too short to reach the top); otherwise where
    // the list stopped
    private String waitForSection(int index) {
        Activity screen = Smoke.resumedActivity();
        assertNotNull("settings isn't in front", screen);
        String[] stopped = {"not checked"};
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (stopped[0] != null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(200);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ListView list = screen.findViewById(android.R.id.list);
                // The pills are the settings' top-level headings that have a title, in order
                PreferenceScreen top = ((PreferenceFragment) screen.getFragmentManager()
                        .findFragmentById(R.id.stream_settings)).getPreferenceScreen();
                Preference section = null;
                for (int i = 0, seen = -1; i < top.getPreferenceCount() && section == null; i++) {
                    Preference preference = top.getPreference(i);
                    if (preference instanceof PreferenceCategory && preference.getTitle() != null &&
                            ++seen == index) {
                        section = preference;
                    }
                }
                int heading = -1;
                for (int p = 0; p < list.getCount() && heading < 0; p++) {
                    if (list.getItemAtPosition(p) == section) {
                        heading = p;
                    }
                }
                int first = list.getFirstVisiblePosition();
                View row = heading >= first && heading <= list.getLastVisiblePosition() ?
                        list.getChildAt(heading - first) : null;
                int fromTop = row == null ? Integer.MIN_VALUE : row.getTop() - list.getPaddingTop();
                stopped[0] = row != null && (Math.abs(fromTop) <= 2 || !list.canScrollList(1)) ? null
                        : "its heading is row " + heading + ", the list shows rows " + first + " to " +
                        list.getLastVisiblePosition() + (row == null ? "" : ", the heading " + fromTop + " px down");
            });
        }
        return stopped[0];
    }

    // The pills' labels, read from the screen itself (they're made from the settings' sections)
    private List<String> pillLabels() {
        List<String> labels = new ArrayList<>();
        long deadline = SystemClock.uptimeMillis() + Smoke.WAIT_MS;
        while (labels.size() < 2 && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(200);
            labels.clear();
            Activity activity = Smoke.resumedActivity();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                LinearLayout row = activity == null ? null : activity.findViewById(R.id.settingsSections);
                for (int i = 0; row != null && i < row.getChildCount(); i++) {
                    labels.add(((TextView) row.getChildAt(i)).getText().toString());
                }
            });
        }
        return labels;
    }

    // A pill by its label, scrolling the row of pills sideways if it's off screen
    private UiObject2 findPill(String label) {
        for (int tries = 0; tries < 8; tries++) {
            UiObject2 row = smoke.device.findObject(smoke.id("settingsSections"));
            UiObject2 pill = row == null ? null : row.findObject(By.text(label));
            if (pill != null) {
                return pill;
            }
            UiObject2 scroll = smoke.device.findObject(smoke.id("settingsSectionsScroll"));
            if (scroll == null) {
                return null;
            }
            scroll.scroll(Direction.RIGHT, 0.6f);
        }
        return null;
    }

    // Through the app, not the device's rotation setting, which is left alone. Screens that
    // handle rotation themselves aren't recreated by it, so each is also recreated outright, as
    // the games screen does itself when the screen changes size. On the RedMagic tablet this
    // crashed the app in 3 of 4 runs: the phone maker's game mode, which runs code inside apps
    // that say they're games (com.zte.gameassist GameActivityStub), stops an observer that's null.
    @Test
    public void screensSurviveRotationAndBeingRecreated() {
        smoke.launchApp();
        checkSurvives(PcView.class, R.id.pcFragmentContainer, "04-recreated-pc-list");

        UiObject2 settings = smoke.device.wait(Until.findObject(smoke.id("settingsButton")), Smoke.WAIT_MS);
        assertNotNull("no settings button", settings);
        settings.click();
        assertTrue("settings didn't open", Smoke.waitFor(() -> Smoke.resumedActivity() instanceof StreamSettings, Smoke.WAIT_MS));
        checkSurvives(StreamSettings.class, R.id.stream_settings, "04-recreated-settings");
        smoke.backToPcList();
    }

    private void checkSurvives(Class<? extends Activity> screen, int mainView, String shotName) {
        Activity before = Smoke.resumedActivity();
        assertTrue(screen.getSimpleName() + " isn't in front", screen.isInstance(before));
        for (int orientation : new int[] {ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> before.setRequestedOrientation(orientation));
            SystemClock.sleep(700);
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(before::recreate);
        assertTrue(screen.getSimpleName() + " didn't come back", Smoke.waitFor(() -> {
            Activity now = Smoke.resumedActivity();
            return screen.isInstance(now) && now != before;
        }, Smoke.WAIT_MS));
        Activity after = Smoke.resumedActivity();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(screen.getSimpleName() + " is closing", after.isFinishing());
            assertNotNull(screen.getSimpleName() + " lost its content", after.findViewById(mainView));
        });
        smoke.shot(shotName);
    }

    @Test
    public void pairedPcShowsItsGames() {
        ComputerDetails pc = smoke.pairedPc();
        Assume.assumeTrue("no paired PC on this device", pc != null);

        smoke.launchApp();
        // From the cached list straight away, or from the PC a moment later
        smoke.openGamesWithTiles(pc);
        smoke.shot("05-games");

        if (Smoke.alive(AppView.class)) {
            smoke.device.pressBack();
            boolean pcList = smoke.device.wait(Until.hasObject(smoke.id("pcFragmentContainer")), Smoke.WAIT_MS);
            if (!pcList && Smoke.resumedActivity() == null) {
                // It closed by itself just before Back, so Back left the app
                smoke.noteClosedByItself(pc, "just before Back");
            }
            else {
                assertTrue("Back didn't return to the PC list", pcList);
            }
        }
        else {
            smoke.noteClosedByItself(pc, "after its games showed");
            assertTrue("the PC list didn't come back",
                    smoke.device.wait(Until.hasObject(smoke.id("pcFragmentContainer")), Smoke.WAIT_MS));
        }
    }
}
