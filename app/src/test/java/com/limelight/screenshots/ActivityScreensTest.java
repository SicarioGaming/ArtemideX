package com.limelight.screenshots;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.takahirom.roborazzi.RoborazziKt;
import com.github.takahirom.roborazzi.RoborazziOptions;
import com.limelight.R;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.AppIcons;
import com.limelight.ui.CollapsingHeader;
import com.limelight.ui.CoverImageView;
import com.limelight.ui.overlay.OverlayMenuView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * Starwire's screens run by Robolectric (the real framework on the JVM, no device or emulator)
 * at every size in RoboDevices, and saved as pictures by Roborazzi.
 * Record: gradlew recordRoborazziNonRootDebug. Check: gradlew verifyRoborazziNonRootDebug.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// MoonBridge loads the native streaming library in its static block, and the settings read its
// audio constants. Instrumenting it lets Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class ActivityScreensTest {
    private static final String[] GAMES = {
            "Cyberpunk 2077", "Elden Ring", "Desktop", "Forza Horizon 5", "Hades II",
            "Steam Big Picture", "Baldur's Gate 3", "Hollow Knight: Silksong",
    };

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static List<Object[]> devices() {
        List<Object[]> list = new ArrayList<>();
        for (RoboDevices device : RoboDevices.values()) {
            list.add(new Object[] {device});
        }
        return list;
    }

    private final RoboDevices device;

    public ActivityScreensTest(RoboDevices device) {
        this.device = device;
    }

    @Before
    public void configure() {
        RuntimeEnvironment.setQualifiers(device.qualifiers);
        RuntimeEnvironment.setFontScale(device.fontScale);
    }

    /** Saves (record) or checks (verify) src/test/screenshots/screen[DEVICE].png */
    private void capture(View view, String screen) {
        shadowOf(Looper.getMainLooper()).idle();
        String file = System.getProperty("roborazzi.output.dir", "src/test/screenshots")
                + "/" + screen + "[" + device.name() + "].png";
        // Looked up by exact signature: RoborazziKt also has Compose overloads of captureRoboImage,
        // and neither javac nor Class.getMethod copes without Compose's test classes present
        try {
            MethodHandles.publicLookup().findStatic(RoborazziKt.class, "captureRoboImage",
                            MethodType.methodType(void.class, View.class, String.class, RoborazziOptions.class))
                    .invoke(view, file, new RoborazziOptions());
        }
        catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    /** The real settings Activity: its fragment, preferences.xml, section pills and impact panel. */
    @Test
    public void settings() {
        ActivityController<StreamSettings> controller =
                Robolectric.buildActivity(StreamSettings.class).setup();
        capture(controller.get().getWindow().getDecorView(), "settings");
    }

    /**
     * The game screen layout with AppView's real CollapsingHeader (AppView itself needs a PC).
     * Same order as AppView: the screen is laid out first, the cover grid arrives afterwards
     * (AdapterFragment, once the app list has loaded).
     */
    @Test
    public void gameScreen() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(R.layout.activity_app_view);
        String selected = GAMES[0];
        ((TextView) activity.findViewById(R.id.appListText)).setText("GAMING-PC");
        ((TextView) activity.findViewById(R.id.heroTitle)).setText(selected);
        TextView label = activity.findViewById(R.id.heroLabel);
        label.setText(R.string.cg_last_played);
        label.setVisibility(View.VISIBLE);
        CoverImageView cover = activity.findViewById(R.id.heroCover);
        cover.setImageDrawable(AppIcons.tile(activity, selected));
        cover.setVisibility(View.VISIBLE);
        ((TextView) activity.findViewById(R.id.coverRowLabel))
                .setText(activity.getString(R.string.cg_on_pc, "GAMING-PC"));
        CollapsingHeader header = new CollapsingHeader(activity.findViewById(R.id.appHeader),
                activity.findViewById(R.id.heroSection), activity.findViewById(R.id.miniBar));
        shadowOf(Looper.getMainLooper()).idle();

        ViewGroup container = activity.findViewById(R.id.appFragmentContainer);
        GridView grid = (GridView) LayoutInflater.from(activity)
                .inflate(R.layout.app_grid_view, container, false);
        grid.setAdapter(new CoverAdapter(activity));
        container.addView(grid);
        // Attached after the header's first layout, as in AppView: attaching it in the same frame
        // leaves the header half scrolled away on phones and handhelds
        header.attach(grid);
        capture(activity.getWindow().getDecorView(), "gameScreen");
    }

    /** The PC list layout while it looks for PCs (PcView itself starts network discovery). */
    @Test
    public void pcList() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(R.layout.activity_pc_view);
        // The spinner doesn't show: Robolectric doesn't run its animated drawable
        capture(activity.getWindow().getDecorView(), "pcList");
    }

    /** The in-stream menu sheet over a stream, as it is when it has just opened. */
    @Test
    public void streamMenu() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create();
        Activity activity = controller.get();
        activity.setTheme(R.style.StreamTheme);
        FrameLayout frame = new FrameLayout(activity);
        frame.setBackgroundColor(0xFF203040); // stands in for the video
        LayoutInflater.from(activity).inflate(R.layout.activity_game, frame, true);
        activity.setContentView(frame);
        controller.start().resume().visible();
        OverlayMenuView menu = frame.findViewById(R.id.overlayMenuView);
        menu.setGameInfo(GAMES[0], "1920 x 1080, 120 FPS", AppIcons.accentFor(GAMES[0]));
        menu.setCover(AppIcons.tile(activity, GAMES[0]));
        menu.buildMenu();
        menu.setVisibility(View.VISIBLE);
        capture(frame, "streamMenu");
    }

    private static final class CoverAdapter extends BaseAdapter {
        private final Activity activity;

        CoverAdapter(Activity activity) {
            this.activity = activity;
        }

        @Override
        public int getCount() {
            return GAMES.length;
        }

        @Override
        public Object getItem(int position) {
            return GAMES[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View item = LayoutInflater.from(activity).inflate(R.layout.app_grid_item, parent, false);
            ImageView image = item.findViewById(R.id.grid_image);
            image.setImageDrawable(AppIcons.tile(activity, GAMES[position]));
            ((TextView) item.findViewById(R.id.grid_text)).setText(GAMES[position]);
            return item;
        }
    }
}
