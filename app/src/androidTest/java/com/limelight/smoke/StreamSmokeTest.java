package com.limelight.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.View;
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
 * -e starwire.allowStream true): starts Desktop on the paired PC, checks the floating menu button
 * starts in its saved spot and saves the spot it's dragged to, opens and closes the in-stream
 * menu with it, and leaves with Disconnect. It never presses Quit and never starts another app:
 * it goes ahead only when the PC is running nothing or Desktop. For the stream it turns off what
 * sends the PC anything besides the stream itself (Hide PC cursor's key presses, the microphone,
 * clipboard sync, audio haptics), and afterwards every setting is put back as it was, the menu
 * button's saved spot among them.
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
        // The menu button starts in the middle, saved as if dragged there: well away from the
        // edges, where a drag could be taken for the system's back or home gesture
        PreferenceConfiguration.saveFloatingButtonSpot(prefs, false, 0.5f, 0.5f);
        PreferenceConfiguration.saveFloatingButtonSpot(prefs, true, 0.5f, 0.5f);
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

        // The stream screen starts the menu button in its saved spot, the button goes where it's
        // dragged, and the stream screen saves the new spot
        float[] start = menuButton(game);
        assertEquals("the menu button didn't start in its saved spot", 0.5f * start[2], start[0], 1);
        assertEquals("the menu button didn't start in its saved spot", 0.5f * start[3], start[1], 1);
        String disconnect = smoke.target.getString(R.string.overlay_menu_disconnect);
        float[] spot = dragMenuButton(game, disconnect);

        // Open the menu with a tap on the button where it is now, close it again, and the stream
        // carries on
        openMenu();
        smoke.shot("07-stream-menu");
        UiObject2 close = smoke.device.findObject(By.text(smoke.target.getString(R.string.overlay_menu_close)));
        assertNotNull("the menu has no Close", close);
        close.click();
        assertTrue("the menu didn't close", smoke.device.wait(Until.gone(By.text(disconnect)), Smoke.WAIT_MS));
        SystemClock.sleep(1000);
        assertTrue("the stream stopped when the menu closed", isConnected(game));
        float[] afterMenu = menuButton(game);
        assertEquals("the menu moved the button from its spot", spot[0] * afterMenu[2], afterMenu[0], 1);
        assertEquals("the menu moved the button from its spot", spot[1] * afterMenu[3], afterMenu[1], 1);

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

    // Drags the floating menu button by its middle a quarter of the screen each way, then checks
    // the spot it was left in is saved (FloatingButton): as shares of the room it moves in within
    // Game's window, for that window's orientation. Returns the saved spot.
    private float[] dragMenuButton(Game game, String disconnect) {
        UiObject2 button = smoke.device.wait(Until.findObject(smoke.id("floatingMenuButton")), Smoke.WAIT_MS);
        assertNotNull("the stream has no menu button", button);
        Rect from = button.getVisibleBounds();
        int width = smoke.device.getDisplayWidth();
        int height = smoke.device.getDisplayHeight();
        // Far enough to be a drag and not a tap, and from the middle, still away from the edges
        int toX = from.centerX() < width / 2 ? width * 3 / 4 : width / 4;
        int toY = from.centerY() < height / 2 ? height * 3 / 4 : height / 4;
        // The button takes the whole touch, so none of it reaches the PC
        assertTrue("the drag wasn't sent", smoke.device.swipe(from.centerX(), from.centerY(), toX, toY, 20));
        smoke.device.waitForIdle();
        assertFalse("the drag opened the menu", smoke.device.hasObject(By.text(disconnect)));

        UiObject2 moved = smoke.device.findObject(smoke.id("floatingMenuButton"));
        assertNotNull("the menu button went away after the drag", moved);
        Rect to = moved.getVisibleBounds();
        assertTrue("the button didn't follow the finger: " + from + " to " + to,
                Math.abs(to.centerX() - from.centerX()) > Math.abs(toX - from.centerX()) / 2 &&
                        Math.abs(to.centerY() - from.centerY()) > Math.abs(toY - from.centerY()) / 2);

        float[] left = menuButton(game);
        float[] spot = PreferenceConfiguration.readFloatingButtonSpot(
                PreferenceManager.getDefaultSharedPreferences(smoke.target), left[4] == 1);
        assertEquals("the saved spot isn't where the button was left", left[0] / left[2], spot[0], 0.001f);
        assertEquals("the saved spot isn't where the button was left", left[1] / left[3], spot[1], 0.001f);
        smoke.record("stream.txt", "Menu button dragged to " + spot[0] + ", " + spot[1] +
                (left[4] == 1 ? " (portrait)" : " (landscape)"));
        return spot;
    }

    // The menu button's place in Game's window, the room it moves in there, and whether the window
    // counts as portrait: {x, y, room across, room down, 1 for portrait or 0}
    private static float[] menuButton(Game game) {
        float[] at = new float[5];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View window = game.getWindow().getDecorView();
            View button = game.findViewById(R.id.floatingMenuButton);
            at[0] = button.getX();
            at[1] = button.getY();
            at[2] = window.getWidth() - button.getWidth();
            at[3] = window.getHeight() - button.getHeight();
            at[4] = window.getHeight() > window.getWidth() ? 1 : 0;
        });
        return at;
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
