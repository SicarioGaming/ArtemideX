package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.AppView;
import com.limelight.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

/**
 * The endless animations stay still with animations off, and stop while their screen is out of
 * sight. On the Android 6 emulator with animations off, the game screen's loops ran every frame,
 * its main thread never went idle, and the instrumented suite hung for 40 minutes.
 *
 * Robolectric runs Android 16's animators, which end an endless loop at once when animations are
 * off. Android 5 to 7.1 run it on every frame instead. The tests stand that in by leaving the
 * animators running at full speed while the setting says off, so a loop that starts there moves.
 */
@RunWith(RobolectricTestRunner.class)
// AppView's settings read MoonBridge's constants; instrumenting it skips loading the native library
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class LoopsTest {
    private static final int FRAME_MS = 16;

    private final Context context = RuntimeEnvironment.getApplication();

    // Frames 16 ms apart, run only as wait() moves the clock. By default Robolectric runs every
    // frame of an animation at once. (It also stops an endless one after its second time round.)
    @Before
    public void frameByFrame() {
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(FRAME_MS));
    }

    // The animators' scale outlives the test otherwise
    @After
    public void animatorsAsUsual() {
        setAnimatorScale(1f);
    }

    // The setting, which Robolectric passes on to the animators as Android does
    private void setAnimations(boolean on) {
        Settings.Global.putFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, on ? 1f : 0f);
    }

    // The animators' own scale, apart from the setting
    private static void setAnimatorScale(float scale) {
        ReflectionHelpers.callStaticMethod(ValueAnimator.class, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(float.class, scale));
    }

    // Animations off, with the animators still running as before Android 8
    private void animationsOffAsBeforeAndroid8() {
        setAnimations(false);
        setAnimatorScale(1f);
    }

    // A frame at a time: idleFor() moves the clock, then runs what's due by then
    private static void wait(int ms) {
        for (int waited = 0; waited < ms; waited += FRAME_MS) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS));
        }
    }

    private ObjectAnimator floatLoop(View view) {
        ObjectAnimator loop = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0, -10);
        loop.setDuration(1000);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setRepeatMode(ValueAnimator.REVERSE);
        return loop;
    }

    // The game screen of a PC with no games cached, as it opens
    private ActivityController<AppView> openGameScreen() {
        Intent intent = new Intent(context, AppView.class)
                .putExtra(AppView.NAME_EXTRA, "GAMING-PC")
                .putExtra(AppView.UUID_EXTRA, "00000000-0000-0000-0000-000000000001");
        ActivityController<AppView> screen = Robolectric.buildActivity(AppView.class, intent).setup();
        wait(100);
        return screen;
    }

    private static float[] moving(AppView screen) {
        View glow = screen.findViewById(R.id.coverGlow);
        return new float[] {glow.getTranslationX(), glow.getScaleX(),
                screen.findViewById(R.id.heroCover).getTranslationY()};
    }

    @Test
    public void theGameScreenStaysStillWithAnimationsOff() {
        animationsOffAsBeforeAndroid8();
        AppView screen = openGameScreen().get();
        wait(2000);
        float[] still = moving(screen);
        assertEquals("the glow drifted", 0f, still[0], 0f);
        assertEquals("the glow grew", 1f, still[1], 0f);
        assertEquals("the cover floated", 0f, still[2], 0f);
    }

    @Test
    public void theGameScreenMovesWithAnimationsOnAndStopsOutOfSight() {
        setAnimations(true);
        ActivityController<AppView> controller = openGameScreen();
        wait(1000);
        float[] before = moving(controller.get());
        wait(500);
        float[] shown = moving(controller.get());
        assertNotEquals("the glow doesn't drift", before[0], shown[0], 0f);
        assertNotEquals("the cover doesn't float", before[2], shown[2], 0f);

        // A stream opens over it
        controller.pause().stop();
        float[] hidden = moving(controller.get());
        wait(2000);
        float[] later = moving(controller.get());
        assertEquals("the glow drifted out of sight", hidden[0], later[0], 0f);
        assertEquals("the cover floated out of sight", hidden[2], later[2], 0f);

        // And it carries on when the screen comes back
        controller.start().resume();
        wait(500);
        assertNotEquals("the glow didn't carry on", later[0], moving(controller.get())[0], 0f);
    }

    @Test
    public void noLoopStartsWithAnimationsOff() {
        animationsOffAsBeforeAndroid8();
        assertFalse(Loops.allowed(context));

        View view = new View(context);
        ObjectAnimator loop = floatLoop(view);
        Loops.run(context, loop);
        wait(500);
        assertFalse(loop.isStarted());
        assertEquals(0f, view.getTranslationY(), 0f);

        ValueAnimator rainbow = Rainbow.applyTo(new TextView(context));
        assertFalse("the rainbow flows", rainbow.isStarted());

        FrameLayout tile = new FrameLayout(context);
        Rainbow.Ring ring = new Rainbow.Ring(26, 3);
        tile.setForeground(ring);
        ring.setVisible(true, false);
        assertFalse("the ring turns", ring.isRunning());

        StarfieldView stars = new StarfieldView(context);
        stars.onVisibilityAggregated(true);
        assertFalse("the stars twinkle", stars.isTwinkling());
    }

    @Test
    public void noLoopStartsWhenAndroidTurnsTheAnimatorsOff() {
        // Animations off from Android 8 on
        setAnimations(false);
        assertFalse(Loops.allowed(context));

        // Or the setting says on, but something else stopped the animators (battery saver)
        setAnimations(true);
        setAnimatorScale(0f);
        assertFalse(Loops.allowed(context));
        ObjectAnimator loop = floatLoop(new View(context));
        Loops.run(context, loop);
        assertFalse(loop.isStarted());
    }

    @Test
    public void loopsRunWithAnimationsOn() {
        setAnimations(true);
        assertTrue(Loops.allowed(context));

        View view = new View(context);
        ObjectAnimator loop = floatLoop(view);
        Loops.run(context, loop);
        wait(500);
        assertTrue(loop.isRunning());
        assertNotEquals(0f, view.getTranslationY(), 0f);
        loop.cancel();

        ValueAnimator rainbow = Rainbow.applyTo(new TextView(context));
        assertTrue(rainbow.isStarted());
        rainbow.cancel();

        FrameLayout tile = new FrameLayout(context);
        Rainbow.Ring ring = new Rainbow.Ring(26, 3);
        tile.setForeground(ring);
        ring.setVisible(true, false);
        assertTrue(ring.isRunning());
        ring.setVisible(false, false);

        StarfieldView stars = new StarfieldView(context);
        stars.onVisibilityAggregated(true);
        assertTrue(stars.isTwinkling());
        stars.onVisibilityAggregated(false);
        assertFalse(stars.isTwinkling());
    }

    @Test
    public void beforeAndroid8AHeldLoopIsStoppedAtItsStart() {
        // There a paused animator still wakes the main thread every frame
        setAnimations(true);
        View view = new View(context);
        ObjectAnimator loop = floatLoop(view);
        Loops.run(context, loop);
        wait(300);
        Loops.hold(loop, Build.VERSION_CODES.N_MR1);
        assertFalse(loop.isStarted());
        assertEquals(0f, view.getTranslationY(), 0f);

        // run() starts it again from there
        Loops.run(context, loop);
        wait(300);
        assertTrue(loop.isRunning());
        assertNotEquals(0f, view.getTranslationY(), 0f);
        loop.cancel();
    }

    @Test
    public void fromAndroid8AHeldLoopIsPausedWhereItIs() {
        setAnimations(true);
        View view = new View(context);
        ObjectAnimator loop = floatLoop(view);
        Loops.run(context, loop);
        wait(300);
        Loops.hold(loop, Build.VERSION_CODES.O);
        float held = view.getTranslationY();
        assertTrue(loop.isPaused());
        wait(300);
        assertEquals(held, view.getTranslationY(), 0f);

        // Animations turned off while it was held: run() stops it at its start
        setAnimations(false);
        Loops.run(context, loop);
        assertFalse(loop.isStarted());
        assertEquals(0f, view.getTranslationY(), 0f);
    }
}
