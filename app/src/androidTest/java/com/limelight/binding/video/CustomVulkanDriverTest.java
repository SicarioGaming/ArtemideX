package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The custom (Turnip) Vulkan driver for PyroWave: unpacking driver archives, and falling back to
 * the system driver when the chosen one can't be loaded.
 */
@RunWith(AndroidJUnit4.class)
public class CustomVulkanDriverTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private File zip(String name, String[][] entries) throws IOException {
        File file = new File(context.getCacheDir(), name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (String[] entry : entries) {
                zip.putNextEntry(new ZipEntry(entry[0]));
                zip.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    public void archiveUsesTheLibraryNamedInMetaJson() throws IOException {
        File archive = zip("turnip_test.adpkg", new String[][] {
                {"meta.json", "{\"name\":\"Turnip\",\"libraryName\":\"vulkan.ad07XX.so\"}"},
                {"libother.so", "other"},
                {"vulkan.ad07XX.so", "driver"},
        });
        String path = CustomVulkanDriver.resolve(context, archive.getAbsolutePath());
        assertEquals("vulkan.ad07XX.so", new File(path).getName());
        assertEquals(6, new File(path).length());
    }

    @Test
    public void archiveWithoutMetaJsonPicksTheDriverLibrary() throws IOException {
        File archive = zip("turnip_nometa.zip", new String[][] {
                {"README.txt", "hi"},
                {"libhelper.so", "helper"},
                {"libvulkan_freedreno.so", "freedreno"},
        });
        String path = CustomVulkanDriver.resolve(context, archive.getAbsolutePath());
        assertEquals("libvulkan_freedreno.so", new File(path).getName());
    }

    @Test
    public void missingOrEmptySettingMeansTheSystemDriver() {
        assertNull(CustomVulkanDriver.resolve(context, null));
        assertNull(CustomVulkanDriver.resolve(context, "  "));
        assertNull(CustomVulkanDriver.resolve(context, "/data/nonexistent/driver.so"));
    }

    @Test
    public void brokenDriverFallsBackToTheSystemDriver() throws IOException {
        File fake = new File(context.getFilesDir(), "custom_vulkan_driver_test/libvulkan_fake.so");
        fake.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(fake)) {
            out.write("not a real driver".getBytes(StandardCharsets.UTF_8));
        }

        boolean systemSupported = VulkanRendererBridge.isSupported();
        VulkanRendererBridge.configureCustomDriver(context, fake.getAbsolutePath());
        try {
            // Probing PyroWave opens the custom driver; a broken one must not crash or stick
            VulkanRendererBridge.isPyrowaveSupported();
            assertFalse(VulkanRendererBridge.isCustomDriverActive());
            assertEquals(systemSupported, VulkanRendererBridge.isSupported());
        }
        finally {
            VulkanRendererBridge.configureCustomDriver(context, null);
        }
    }
}
