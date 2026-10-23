package com.limelight.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.limelight.AppView;
import com.limelight.Game;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.AppPreferences;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

/**
 * A real stream, only when asked for (device-test.sh --allow-stream, which passes
 * -e starwire.allowStream true): starts Desktop on the paired PC, opens and closes the in-stream
 * menu, and leaves with Disconnect. It never presses Quit and never starts another app: it goes
 * ahead only when the PC is running nothing or Desktop. For the stream it turns off what sends
 * the PC anything besides the stream itself (Hide PC cursor's key presses, the microphone,
 * clipboard sync, audio haptics), and afterwards every setting is put back as it was.
 */
@RunWith(AndroidJUnit4.class)
public class StreamSmokeTest {
    private static final String DESKTOP = "Desktop";
    private static final long STREAM_START_MS = 45_000;

    private final Smoke smoke = new Smoke();

    @Before
    public void setUp() {
        Assume.assumeTrue("streams only with device-test.sh --allow-stream",
                "true".equals(InstrumentationRegistry.getArguments().getString("starwire.allowStream")));
        smoke.checkScreen();
        smoke.saveSettings();
    }

    @After
    public void tearDown() {
        // If a check failed mid-stream, close the stream the way leaving does: closing Game
        // disconnects, and only the menu's Quit would end the session on the PC
        Activity front = Smoke.resumedActivity();
        if (front instanceof Game) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(front::finish);
            long deadline = SystemClock.uptimeMillis() + Smoke.WAIT_MS;
            while (Smoke.alive(Game.class) && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(250);
            }
        }
        smoke.restoreSettings();
    }

    @Test
    public void desktopStreamOpensTheMenuAndDisconnects() throws Exception {
        ComputerDetails pc = smoke.pairedPc();
        Assume.assumeTrue("no paired PC on this device", pc != null);
        Smoke.Host before = smoke.askPc(pc, DESKTOP);
        Assume.assumeTrue("the paired PC didn't answer", before != null);
        Assume.assumeTrue("the PC doesn't have this app as paired any more", before.paired);
        Assume.assumeTrue("the PC has no app called Desktop", before.app != null);
        int desktopId = before.app.getAppId();
        Assume.assumeTrue("the PC is running another app, so this leaves it alone",
                before.runningAppId == 0 || before.runningAppId == desktopId);
        smoke.record("stream.txt", "Desktop running before the stream: " + (before.runningAppId == desktopId));

        // Only the stream goes to the PC (PreferenceConfiguration's keys), and the menu has its
        // button, so opening it never means tapping the stream
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(smoke.target);
        smoke.record("stream.txt", "Hide PC cursor setting: " + prefs.getString("hide_cursor_mode", "never (the default)"));
        assertTrue(prefs.edit()
                .putString("hide_cursor_mode", "never")
                .putBoolean("checkbox_enable_microphone", false)
                .putBoolean("checkbox_smart_clipboard_sync", false)
                .putString("list_audio_haptics", "off")
                .putBoolean("checkbox_enable_floating_button", true)
                .commit());
        // What Game will use, Desktop's own settings included
        PreferenceConfiguration stream = AppPreferences.getEffectivePreferences(smoke.target,
                pc.uuid + ":" + desktopId, null, true);
        Assume.assumeTrue("Desktop has its own Hide PC cursor, microphone or haptics setting",
                stream.hideCursorMode == PreferenceConfiguration.HIDE_CURSOR_NEVER &&
                        !stream.enableMicrophone && stream.audioHapticsOutputs == 0);
        assertFalse(stream.smartClipboardSync);
        assertTrue(stream.enableFloatingButton);

        smoke.launchApp();
        // The games screen can close by itself (Smoke.openGamesWithTiles), so it may take another go
        smoke.openGamesWithTiles(pc);
        for (int attempt = 1; !startDesktop(); attempt++) {
            smoke.noteClosedByItself(pc, "before Play was pressed");
            assertTrue("the games screen closed by itself three times in a row", attempt < 3);
            assertTrue("the PC list didn't come back",
                    smoke.device.wait(Until.hasObject(smoke.id("pcFragmentContainer")), Smoke.WAIT_MS));
            smoke.openGamesWithTiles(pc);
        }
        Game game = waitForStream();
        SystemClock.sleep(3000);
        smoke.shot("06-stream");

        // Open the menu and close it again, and the stream carries on
        String disconnect = smoke.target.getString(R.string.overlay_menu_disconnect);
        openMenu();
        smoke.shot("07-stream-menu");
        UiObject2 close = smoke.device.findObject(By.text(smoke.target.getString(R.string.overlay_menu_close)));
        assertNotNull("the menu has no Close", close);
        close.click();
        assertTrue("the menu didn't close", smoke.device.wait(Until.gone(By.text(disconnect)), Smoke.WAIT_MS));
        SystemClock.sleep(1000);
        assertTrue("the stream stopped when the menu closed", isConnected(game));

        // Leave with Disconnect: Quit sits right beside it and is never pressed
        openMenu();
        UiObject2 leave = smoke.device.findObject(By.text(disconnect));
        assertNotNull("the menu has no Disconnect", leave);
        assertEquals(disconnect, leave.getText());
        leave.click();
        long deadline = SystemClock.uptimeMillis() + 15_000;
        while (Smoke.alive(Game.class) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(250);
        }
        assertFalse("Disconnect didn't close the stream", Smoke.alive(Game.class));
        boolean games = smoke.device.wait(Until.hasObject(smoke.id("appFragmentContainer")), Smoke.WAIT_MS);
        if (!games && !Smoke.alive(AppView.class) && smoke.device.hasObject(smoke.id("pcFragmentContainer"))) {
            smoke.noteClosedByItself(pc, "after the stream");
        }
        else {
            assertTrue("Disconnect didn't go back to the PC's games", games);
        }
        smoke.shot("08-after-disconnect");

        // Disconnect leaves Desktop running on the PC (whether it stays is the PC's choice), and
        // nothing else was started
        Smoke.Host after = smoke.askPc(pc, DESKTOP);
        assertNotNull("the PC stopped answering after the stream", after);
        smoke.record("stream.txt", "Desktop running after Disconnect: " + (after.runningAppId == desktopId));
        assertTrue("another app is running on the PC after the stream",
                after.runningAppId == 0 || after.runningAppId == desktopId);
    }

    // Desktop's tile, then Play (or Resume) for Desktop, checked by its label before the tap.
    // False if the games screen closed by itself first. A view that's redrawn while it's being
    // looked at (StaleObjectException) is looked up again; UI Automator then hasn't tapped it.
    private boolean startDesktop() {
        for (int tries = 0; tries < 3; tries++) {
            try {
                if (!Smoke.waitFor(() -> heroTitle() != null || !Smoke.alive(AppView.class), Smoke.WAIT_MS) ||
                        !Smoke.alive(AppView.class)) {
                    return false;
                }
                if (!DESKTOP.equals(heroTitle())) {
                    UiObject2 tile = smoke.device.wait(Until.findObject(
                            By.desc(Pattern.compile(Pattern.quote(DESKTOP) + "(, .*)?"))), 20_000);
                    if (!Smoke.alive(AppView.class)) {
                        return false;
                    }
                    assertNotNull("no Desktop tile", tile);
                    // One tap only picks it; a second tap would start it
                    tile.click();
                    boolean picked = Smoke.waitFor(() -> DESKTOP.equals(heroTitle()) || !Smoke.alive(AppView.class),
                            Smoke.WAIT_MS);
                    if (!Smoke.alive(AppView.class)) {
                        return false;
                    }
                    assertTrue("Desktop didn't come up in the hero", picked);
                }
                String play = smoke.target.getString(R.string.cg_a11y_play_game, DESKTOP);
                String resume = smoke.target.getString(R.string.cg_a11y_resume_game, DESKTOP);
                UiObject2 button = smoke.device.wait(Until.findObject(smoke.id("heroPlay")
                        .desc(Pattern.compile(Pattern.quote(play) + "|" + Pattern.quote(resume)))), Smoke.WAIT_MS);
                if (!Smoke.alive(AppView.class)) {
                    return false;
                }
                assertNotNull("no Play button for Desktop", button);
                button.click();
                return true;
            }
            catch (StaleObjectException e) {
                if (!Smoke.alive(AppView.class)) {
                    return false;
                }
            }
        }
        fail("the games screen kept changing under the test");
        return false;
    }

    // The hero's title on the games screen in front, read from the app itself; null if none
    private static String heroTitle() {
        String[] title = new String[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Activity front = Smoke.resumedActivityOnMain();
            TextView hero = front instanceof AppView ? front.findViewById(R.id.heroTitle) : null;
            title[0] = hero == null ? null : hero.getText().toString();
        });
        return title[0];
    }

    private Game waitForStream() {
        long deadline = SystemClock.uptimeMillis() + STREAM_START_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            Activity front = Smoke.resumedActivity();
            if (front instanceof Game && isConnected((Game) front)) {
                return (Game) front;
            }
            SystemClock.sleep(500);
        }
        smoke.shot("06-stream-didnt-start");
        StringBuilder shown = new StringBuilder();
        for (UiObject2 text : smoke.device.findObjects(By.clazz("android.widget.TextView"))) {
            if (text.getText() != null) {
                shown.append(" | ").append(text.getText());
            }
        }
        fail("the stream didn't start within " + STREAM_START_MS / 1000 + " s; on screen:" + shown);
        return null;
    }

    private void openMenu() {
        UiObject2 button = smoke.device.wait(Until.findObject(smoke.id("floatingMenuButton")), Smoke.WAIT_MS);
        assertNotNull("the stream has no menu button", button);
        button.click();
        assertTrue("the menu didn't open", smoke.device.wait(Until.hasObject(
                By.text(smoke.target.getString(R.string.overlay_menu_disconnect))), Smoke.WAIT_MS));
    }

    private static boolean isConnected(Game game) {
        boolean[] connected = new boolean[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Field field = Game.class.getDeclaredField("connected");
                field.setAccessible(true);
                connected[0] = field.getBoolean(game) && !game.isFinishing();
            }
            catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        return connected[0];
    }
}
