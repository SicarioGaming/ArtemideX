package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.R;
import com.limelight.preferences.StreamSettings;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Cover Glow: the colour taken from a game's cover, and the home screen layout drawn at the
 * tablet's size (saved as a picture to look at, since the screen needs a paired PC).
 */
@RunWith(AndroidJUnit4.class)
public class CoverGlowTest {
    private static float hue(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[0];
    }

    @Test
    public void aRedCoverTintsRed() {
        int[] pixels = new int[100];
        Arrays.fill(pixels, Color.rgb(200, 30, 40));
        float h = hue(CoverGlow.tintFromPixels(pixels));
        assertTrue("hue " + h, h < 10 || h > 350);
    }

    @Test
    public void theVividPartOfADarkCoverWins() {
        // Mostly near-black with a blue logo
        int[] pixels = new int[100];
        Arrays.fill(pixels, Color.rgb(12, 12, 14));
        for (int i = 0; i < 15; i++) {
            pixels[i] = Color.rgb(30, 90, 230);
        }
        float h = hue(CoverGlow.tintFromPixels(pixels));
        assertTrue("hue " + h, h > 210 && h < 235);
    }

    @Test
    public void aGreyCoverKeepsTheDefault() {
        int[] pixels = new int[100];
        Arrays.fill(pixels, Color.rgb(128, 128, 128));
        assertEquals(CoverGlow.DEFAULT_TINT, CoverGlow.tintFromPixels(pixels));
    }

    @Test
    public void whiteTextStaysReadableOnTheBackground() {
        for (int tint : new int[] {CoverGlow.DEFAULT_TINT, Color.YELLOW, Color.WHITE, 0xFFA3E635}) {
            int deep = CoverGlow.deep(tint);
            double contrast = (1.05) / (luminance(deep) + 0.05);
            assertTrue("contrast " + contrast + " on " + Integer.toHexString(deep), contrast >= 7);
        }
    }

    private static double luminance(int color) {
        double[] c = {Color.red(color) / 255.0, Color.green(color) / 255.0, Color.blue(color) / 255.0};
        for (int i = 0; i < 3; i++) {
            c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    @Test
    public void pcListRendersAtTabletSize() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.res.Configuration landscape = new android.content.res.Configuration(
                target.getResources().getConfiguration());
        landscape.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        Context context = new ContextThemeWrapper(target.createConfigurationContext(landscape), R.style.AppTheme);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LayoutInflater inflater = LayoutInflater.from(context);
            View root = inflater.inflate(R.layout.activity_pc_view, null);
            root.findViewById(R.id.no_pc_found_layout).setVisibility(View.GONE);

            ViewGroup container = root.findViewById(R.id.pcFragmentContainer);
            LinearLayout row = new LinearLayout(context);
            String[] names = {"GAMING-PC", "LAPTOP"};
            for (String name : names) {
                View item = inflater.inflate(R.layout.pc_grid_item, row, false);
                ((ImageView) item.findViewById(R.id.grid_image)).setImageResource(R.drawable.ic_computer);
                ((TextView) item.findViewById(R.id.grid_text)).setText(name);
                item.findViewById(R.id.grid_spinner).setVisibility(View.GONE);
                row.addView(item);
            }
            container.addView(row);

            save(root, target, "cover_glow_pcs.png");
        });
    }

    @Test
    public void settingsScreenHasSectionPills() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<StreamSettings> scenario = ActivityScenario.launch(StreamSettings.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                LinearLayout pills = activity.findViewById(R.id.settingsSections);
                assertTrue("only " + pills.getChildCount() + " sections", pills.getChildCount() >= 5);
                assertEquals("Basic", ((TextView) pills.getChildAt(0)).getText().toString());
                assertTrue(pills.getChildAt(0).isSelected());

                // Tapping a section moves the highlight to it
                pills.getChildAt(2).performClick();
                assertTrue(pills.getChildAt(2).isSelected());
                assertFalse(pills.getChildAt(0).isSelected());
                pills.getChildAt(0).performClick();

                save(activity.getWindow().getDecorView(), target, "cover_glow_settings.png");
            });
        }
    }

    @Test
    public void sectionNamesDropTheWordSettings() {
        assertEquals("Audio", CoverGlowSettings.shortName("Audio Settings"));
        assertEquals("Overlay Menu", CoverGlowSettings.shortName("Overlay Menu"));
        assertEquals("Settings", CoverGlowSettings.shortName("Settings"));
    }

    private static void save(View root, Context target, String name) {
        int width = 2400, height = 1504;
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File out = new File(target.getExternalFilesDir(null), name);
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void homeScreenRendersAtTabletSize() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context context = new ContextThemeWrapper(target, R.style.AppTheme);
        final int tint = 0xFFE11D48;

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LayoutInflater inflater = LayoutInflater.from(context);
            View root = inflater.inflate(R.layout.activity_app_view, null);
            root.setBackgroundColor(CoverGlow.deep(tint));
            root.findViewById(R.id.coverGlow).setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(tint));
            ((TextView) root.findViewById(R.id.appListText)).setText("GAMING-PC");
            ((TextView) root.findViewById(R.id.coverRowLabel)).setText("On GAMING-PC");
            ((TextView) root.findViewById(R.id.heroLabel)).setText(R.string.cg_last_played);
            ((TextView) root.findViewById(R.id.heroTitle)).setText("Desktop");
            ((ImageView) root.findViewById(R.id.heroCover)).setImageDrawable(
                    new android.graphics.drawable.ColorDrawable(tint));

            float d = context.getResources().getDisplayMetrics().density;
            LinearLayout chips = root.findViewById(R.id.heroChips);
            for (String chip : new String[] {"PyroWave", "1440p · 120 FPS", "Warp Drive", "Fast V-Sync"}) {
                TextView view = new TextView(context);
                view.setText(chip);
                view.setTextColor(Color.WHITE);
                view.setTextSize(14);
                view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                view.setBackgroundResource(R.drawable.cg_chip);
                view.setPadding((int) (14 * d), (int) (7 * d), (int) (14 * d), (int) (7 * d));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMarginEnd((int) (8 * d));
                chips.addView(view, params);
            }

            // Stand-in covers for the cover row
            FrameLayout container = root.findViewById(R.id.appFragmentContainer);
            LinearLayout row = new LinearLayout(context);
            int[] colours = {tint, 0xFFF97316, 0xFF3B82F6, 0xFF14B8A6, 0xFFA3E635, 0xFFEAB308};
            String[] names = {"Desktop", "Steam Big Picture", "Game", "Game", "Game", "Game"};
            for (int i = 0; i < colours.length; i++) {
                View item = inflater.inflate(R.layout.app_grid_item, row, false);
                ((ImageView) item.findViewById(R.id.grid_image)).setImageDrawable(
                        new android.graphics.drawable.ColorDrawable(colours[i]));
                ((TextView) item.findViewById(R.id.grid_text)).setText(names[i]);
                if (i == 0) {
                    item.setForeground(context.getDrawable(R.drawable.cg_cover_ring));
                }
                else {
                    item.setAlpha(0.82f);
                }
                row.addView(item);
            }
            container.addView(row);

            int width = 2400, height = 1504;
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);

            // Everything fits: at least one full row of covers is left below the hero
            View coverRow = root.findViewById(R.id.appFragmentContainer);
            assertTrue("cover row only " + coverRow.getHeight() + " px",
                    coverRow.getHeight() >= 176 * d);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));
            File out = new File(target.getExternalFilesDir(null), "cover_glow_home.png");
            try (FileOutputStream stream = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
