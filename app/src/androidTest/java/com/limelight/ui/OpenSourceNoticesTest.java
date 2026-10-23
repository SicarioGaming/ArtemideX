package com.limelight.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * The open-source licences page: every licence text it points to is in the APK, and every native
 * library Starwire ships is covered, so a new .so can't slip in without its notice.
 */
@RunWith(AndroidJUnit4.class)
public class OpenSourceNoticesTest {
    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void everyLicenceTextIsInTheApk() throws Exception {
        for (OpenSourceNotices.Component component : OpenSourceNotices.ALL) {
            String text = OpenSourceNotices.readLicence(target, component);
            assertTrue(component.name + ": " + component.asset + " is empty", text.trim().length() > 100);
            assertFalse(component.asset + " has CR line endings", text.contains("\r"));
            assertTrue(component.name + " has no project page", component.url.startsWith("https://"));

            // A copyright line shown on the page is the one in the licence file
            if (component.notice.startsWith("Copyright")) {
                String line = component.notice.replace(" and others", "");
                assertTrue(component.name + ": \"" + line + "\" isn't in " + component.asset,
                        text.contains(line));
            }
        }
    }

    @Test
    public void everyNativeLibraryHasANotice() {
        File[] libraries = new File(target.getApplicationInfo().nativeLibraryDir).listFiles();
        assertNotNull("no native library folder", libraries);
        int checked = 0;
        for (File library : libraries) {
            if (!library.getName().endsWith(".so")) {
                continue;
            }
            assertFalse(library.getName() + " isn't in OpenSourceNotices",
                    OpenSourceNotices.forLibrary(library.getName()).isEmpty());
            checked++;
        }
        // libmoonlight-core and libvulkan_renderer on every device, and the arm64 prebuilts
        assertTrue("only " + checked + " native libraries found", checked >= 2);
    }

    @Test
    public void eachSectionHasSomething() {
        for (int section : OpenSourceNotices.sections()) {
            int count = 0;
            for (OpenSourceNotices.Component component : OpenSourceNotices.ALL) {
                if (component.section == section) {
                    count++;
                }
            }
            assertTrue(target.getString(section) + " is empty", count > 0);
        }
    }
}
