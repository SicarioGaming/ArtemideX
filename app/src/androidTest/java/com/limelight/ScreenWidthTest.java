package com.limelight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;

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

/**
 * The PC list and the game screen fit whatever screen they're on. Run with the display set to a
 * phone or foldable size (adb shell wm size) to check those; the pictures are saved next to the
 * app's files for a look.
 */
@RunWith(AndroidJUnit4.class)
public class ScreenWidthTest {
    private static final String UUID = "22222222-3333-4444-5555-666666666666";

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

    private ComputerDetails addTestPc() throws Exception {
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
        return details;
    }

    @Test
    public void pcListFits() throws Exception {
        addTestPc();
        try (ActivityScenario<PcView> scenario = ActivityScenario.launch(new Intent(target, PcView.class))) {
            SystemClock.sleep(1500);
            scenario.onActivity(activity -> {
                String name = layOutAndSave(activity, "pc_list");
                assertNothingOffScreen(activity, name);
            });
        }
    }

    @Test
    public void gameScreenFits() throws Exception {
        ComputerDetails details = addTestPc();
        Intent intent = new Intent(target, AppView.class)
                .putExtra(AppView.NAME_EXTRA, details.name)
                .putExtra(AppView.UUID_EXTRA, UUID);
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(intent)) {
            for (int i = 0; i < 3; i++) {
                SystemClock.sleep(1000);
                scenario.onActivity(activity -> layOutAndSave(activity, "game_screen"));
            }
            scenario.onActivity(activity -> {
                String name = layOutAndSave(activity, "game_screen");
                assertFalse("game screen closed", activity.isFinishing());
                assertNothingOffScreen(activity, name);
            });
        }
    }

    // Lays the screen out by hand at the display's size (works with the screen off) and saves it
    private static String layOutAndSave(Activity activity, String what) {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = metrics.widthPixels, height = metrics.heightPixels;
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        String name = what + "_" + activity.getResources().getConfiguration().screenWidthDp + "dp";
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height,
                android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                new java.io.File(activity.getExternalFilesDir(null), name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return name;
    }

    // No visible text or button may start past the right edge of the screen
    private static void assertNothingOffScreen(Activity activity, String name) {
        View root = activity.getWindow().getDecorView();
        checkTree(activity, root, root.getWidth(), name);
    }

    private static void checkTree(Activity activity, View view, int screenWidth, String name) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        boolean meaningful = view.isClickable() || view instanceof android.widget.TextView;
        if (meaningful && view.getWidth() > 0 && !isInScroller(view)) {
            int[] at = new int[2];
            view.getLocationInWindow(at);
            assertTrue(name + ": " + describe(activity, view) + " starts off screen at x=" + at[0],
                    at[0] < screenWidth);
            assertTrue(name + ": " + describe(activity, view) + " is squeezed to " + view.getWidth() + " px",
                    !(view instanceof android.widget.TextView)
                            || ((android.widget.TextView) view).getText().length() == 0
                            || view.getWidth() > 8);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                checkTree(activity, group.getChildAt(i), screenWidth, name);
            }
        }
    }

    // Rows that scroll sideways (game row, settings pills) are allowed past the edge
    private static boolean isInScroller(View view) {
        for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            if (p instanceof android.widget.HorizontalScrollView
                    || p instanceof android.widget.AbsListView
                    || p.getClass().getName().contains("RecyclerView")) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Activity activity, View view) {
        String id = view.getId() != View.NO_ID ? activity.getResources().getResourceEntryName(view.getId()) : "";
        String text = view instanceof android.widget.TextView
                ? " \"" + ((android.widget.TextView) view).getText() + "\"" : "";
        return view.getClass().getSimpleName() + (id.isEmpty() ? "" : " #" + id) + text;
    }
}
