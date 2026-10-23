package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.utils.CacheHelper;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.OutputStream;

// Opens the real app list for a PC whose list is already cached, the way it opens after pairing
@RunWith(AndroidJUnit4.class)
public class AppViewTest {
    private static final String UUID = "11111111-2222-3333-4444-555555555555";

    private static final String APP_LIST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<root status_code=\"200\">"
            + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Desktop</AppTitle><ID>881448767</ID></App>"
            + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Steam Big Picture</AppTitle><ID>1093255277</ID></App>"
            + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Cyberpunk 2077</AppTitle><ID>1428765823</ID></App>"
            + "<App><IsHdrSupported>0</IsHdrSupported><AppTitle>Aniimo</AppTitle><ID>203145821</ID></App>"
            + "</root>";

    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After
    public void removeTestPc() {
        ComputerDatabaseManager db = new ComputerDatabaseManager(target);
        ComputerDetails details = db.getComputerByUUID(UUID);
        if (details != null) {
            db.deleteComputer(details);
        }
        db.close();
        CacheHelper.deleteCacheFile(target.getCacheDir(), "applist", UUID);
    }

    // A PC whose app list is already cached, and the intent that opens its game screen
    private Intent cachedPc() throws Exception {
        ComputerDetails details = new ComputerDetails();
        details.uuid = UUID;
        details.name = "TEST-PC";
        // Unroutable, so the PC stays "checking" while the screen is up
        details.manualAddress = new ComputerDetails.AddressTuple("10.255.255.1", 47989);
        ComputerDatabaseManager db = new ComputerDatabaseManager(target);
        db.updateComputer(details);
        db.close();

        try (OutputStream out = CacheHelper.openCacheFileForOutput(target.getCacheDir(), "applist", UUID)) {
            CacheHelper.writeStringToOutputStream(out, APP_LIST);
        }

        return new Intent(target, AppView.class)
                .putExtra(AppView.NAME_EXTRA, details.name)
                .putExtra(AppView.UUID_EXTRA, UUID);
    }

    @Test
    public void tileMenuOpensOnIconTiles() throws Exception {
        // Tapping the selected tile while a game runs opens this menu (from Reddit: it crashed on
        // Desktop). Icon tiles are drawn, not bitmaps, and the "create shortcut" check cast them.
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(cachedPc())) {
            for (int i = 0; i < 3; i++) {
                SystemClock.sleep(1000);
                scenario.onActivity(AppViewTest::drawOffscreen);
            }
            scenario.onActivity(activity -> {
                android.widget.AbsListView grid = findGrid(activity.getWindow().getDecorView());
                assertNotNull("no game grid", grid);
                int opened = 0;
                for (int i = 0; i < grid.getChildCount(); i++) {
                    android.view.View tile = grid.getChildAt(i);
                    Object item = grid.getItemAtPosition(grid.getFirstVisiblePosition() + i);
                    String name = ((AppView.AppObject) item).app.getAppName();
                    if (!name.equals("Desktop") && !name.equals("Steam Big Picture")) {
                        continue;
                    }
                    activity.openContextMenu(tile);
                    activity.closeContextMenu();
                    opened++;
                    // And the shortcut gets the tile's picture
                    android.widget.ImageView image = tile.findViewById(R.id.grid_image);
                    android.graphics.Bitmap picture = AppView.shortcutBitmap(image);
                    assertNotNull(name + " tile has no shortcut picture", picture);
                    assertFalse(name + " shortcut picture is a GPU bitmap",
                            picture.getConfig() == android.graphics.Bitmap.Config.HARDWARE);
                }
                assertEquals("Desktop and Steam Big Picture tiles", 2, opened);
                assertFalse("app list closed", activity.isFinishing());
            });
        }
    }

    @Test
    public void quitSitsBesideResumeWhileAGameRuns() throws Exception {
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(cachedPc())) {
            for (int i = 0; i < 3; i++) {
                SystemClock.sleep(1000);
                scenario.onActivity(AppViewTest::drawOffscreen);
            }
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field selected = AppView.class.getDeclaredField("selectedApp");
                    selected.setAccessible(true);
                    AppView.AppObject app = (AppView.AppObject) selected.get(activity);
                    assertNotNull("hero picked no game", app);
                    java.lang.reflect.Field running = AppView.class.getDeclaredField("lastRunningAppId");
                    running.setAccessible(true);
                    java.lang.reflect.Method refresh = AppView.class.getDeclaredMethod("refreshHero", boolean.class);
                    refresh.setAccessible(true);
                    android.widget.Button quit = activity.findViewById(R.id.heroQuit);
                    android.widget.Button play = activity.findViewById(R.id.heroPlay);

                    // The PC reports the selected game running, as the poller would
                    running.setInt(activity, app.app.getAppId());
                    refresh.invoke(activity, false);
                    assertEquals(android.view.View.VISIBLE, quit.getVisibility());
                    assertEquals(activity.getString(R.string.cg_resume), play.getText().toString());
                    assertEquals(activity.getString(R.string.cg_quit), quit.getText().toString());
                    quit.animate().cancel();
                    quit.setAlpha(1f);
                    quit.setTranslationX(0);
                    drawOffscreen(activity, "app_view_running.png");

                    // And it goes once nothing runs
                    running.setInt(activity, 0);
                    refresh.invoke(activity, false);
                    assertEquals(android.view.View.GONE, quit.getVisibility());
                    assertEquals(activity.getString(R.string.cg_play), play.getText().toString());
                }
                catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    @Test
    public void tilesAndButtonsSayWhichGame() throws Exception {
        // Once a cover loads the tile shows no name, so TalkBack needs it spelled out
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(cachedPc())) {
            for (int i = 0; i < 3; i++) {
                SystemClock.sleep(1000);
                scenario.onActivity(AppViewTest::drawOffscreen);
            }
            scenario.onActivity(activity -> {
                android.widget.AbsListView grid = findGrid(activity.getWindow().getDecorView());
                assertNotNull("no game grid", grid);
                assertEquals("all four tiles laid out", 4, grid.getChildCount());
                String selectedSuffix = ", " + activity.getString(R.string.cg_a11y_selected);
                int selected = 0;
                for (int i = 0; i < grid.getChildCount(); i++) {
                    android.view.View tile = grid.getChildAt(i);
                    Object item = grid.getItemAtPosition(grid.getFirstVisiblePosition() + i);
                    String name = ((AppView.AppObject) item).app.getAppName();
                    CharSequence label = tile.getContentDescription();
                    assertNotNull(name + " tile has no label", label);
                    assertTrue(label.toString(), label.toString().startsWith(name));
                    if (label.toString().endsWith(selectedSuffix)) {
                        selected++;
                    }
                    // The picture and the name under it are left out, so the tile is read once
                    assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            tile.findViewById(R.id.grid_image).getImportantForAccessibility());
                    assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            tile.findViewById(R.id.grid_text).getImportantForAccessibility());
                }
                assertEquals("tiles read as selected", 1, selected);

                try {
                    java.lang.reflect.Field field = AppView.class.getDeclaredField("selectedApp");
                    field.setAccessible(true);
                    String name = ((AppView.AppObject) field.get(activity)).app.getAppName();
                    // Nothing runs on the unreachable test PC, so it's Play
                    assertEquals(activity.getString(R.string.cg_a11y_play_game, name),
                            activity.findViewById(R.id.heroPlay).getContentDescription().toString());
                    assertEquals(activity.getString(R.string.cg_a11y_play_game, name),
                            activity.findViewById(R.id.miniPlay).getContentDescription().toString());
                    assertEquals(activity.getString(R.string.cg_a11y_quit_game, name),
                            activity.findViewById(R.id.heroQuit).getContentDescription().toString());
                }
                catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    private static android.widget.AbsListView findGrid(android.view.View view) {
        if (view instanceof android.widget.AbsListView && ((android.widget.AbsListView) view).getAdapter() != null) {
            return (android.widget.AbsListView) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.AbsListView found = findGrid(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void appListOpensWithoutClosing() throws Exception {
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(cachedPc())) {
            // The crash came a moment after the list showed, before any cover art. Lay out and draw
            // the screen by hand, so the covers load even when the tablet's screen is off.
            for (int i = 0; i < 5; i++) {
                SystemClock.sleep(1000);
                scenario.onActivity(AppViewTest::drawOffscreen);
            }

            // A host sends real box art, which arrives as a GPU (HARDWARE) bitmap
            scenario.onActivity(activity -> {
                android.graphics.Bitmap soft = android.graphics.Bitmap.createBitmap(
                        120, 160, android.graphics.Bitmap.Config.ARGB_8888);
                soft.eraseColor(android.graphics.Color.rgb(40, 160, 90));
                ((android.widget.ImageView) activity.findViewById(R.id.heroCover)).setImageBitmap(
                        soft.copy(android.graphics.Bitmap.Config.HARDWARE, false));
            });
            SystemClock.sleep(1000);
            scenario.onActivity(AppViewTest::drawOffscreen);
            scenario.onActivity(activity -> {
                assertFalse("app list closed", activity.isFinishing());
                try {
                    java.lang.reflect.Field adapter = AppView.class.getDeclaredField("appGridAdapter");
                    adapter.setAccessible(true);
                    assertEquals(4, ((android.widget.BaseAdapter) adapter.get(activity)).getCount());
                    java.lang.reflect.Field selected = AppView.class.getDeclaredField("selectedApp");
                    selected.setAccessible(true);
                    assertNotNull("hero picked no game", selected.get(activity));
                }
                catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    private static void drawOffscreen(AppView activity) {
        drawOffscreen(activity, "app_view.png");
    }

    private static void drawOffscreen(AppView activity, String fileName) {
        android.view.View root = activity.getWindow().getDecorView();
        int width = 2400, height = 1504;
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                new java.io.File(activity.getExternalFilesDir(null), fileName))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
