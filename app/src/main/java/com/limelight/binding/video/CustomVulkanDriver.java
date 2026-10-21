package com.limelight.binding.video;

import android.content.Context;

import com.limelight.LimeLog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Turns the custom-driver setting into a path libadrenotools can dlopen.
 *
 * A plain .so is used as-is. A .zip/.adpkg, which is how Turnip drivers are usually shared, has
 * its library extracted into app-private storage first, using meta.json's "libraryName" to pick
 * which .so to load. An already-extracted driver directory works too.
 *
 * libadrenotools can only load the driver from app-private storage, so the archive can't be used
 * in place.
 */
final class CustomVulkanDriver {
    private static final String EXTRACT_DIR = "custom_vulkan_driver";

    private CustomVulkanDriver() {}

    /** Absolute path to a .so, or null if the setting is empty or couldn't be resolved. */
    static String resolve(Context context, String setting) {
        if (setting == null || setting.trim().isEmpty()) {
            return null;
        }

        File file = new File(setting.trim());
        if (file.isDirectory()) {
            return fromDirectory(file);
        }
        if (!file.isFile()) {
            LimeLog.warning("Custom Vulkan driver " + setting + " not found");
            return null;
        }
        if (!isZip(file)) {
            return file.getAbsolutePath();
        }

        try {
            return extract(context, file);
        }
        catch (IOException | RuntimeException e) {
            LimeLog.warning("Could not unpack custom Vulkan driver " + setting + ": " + e);
            return null;
        }
    }

    private static String fromDirectory(File dir) {
        String wanted = libraryName(new File(dir, "meta.json"));
        File lib = wanted != null ? new File(dir, wanted) : null;
        if (lib == null || !lib.isFile()) {
            lib = firstLibrary(dir.listFiles());
        }
        if (lib == null) {
            LimeLog.warning("No driver .so in " + dir);
            return null;
        }
        return lib.getAbsolutePath();
    }

    private static String extract(Context context, File archive) throws IOException {
        File dir = new File(context.getFilesDir(), EXTRACT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir);
        }

        try (ZipFile zip = new ZipFile(archive)) {
            String wanted = libraryNameInZip(zip);
            ZipEntry entry = wanted != null ? findEntry(zip, wanted) : null;
            if (entry == null) {
                entry = firstLibraryEntry(zip);
            }
            if (entry == null) {
                throw new IOException("no .so found");
            }
            String name = new File(entry.getName()).getName();

            File out = new File(dir, name);
            // Reuse the unpacked library unless the archive is newer
            if (out.isFile() && out.lastModified() >= archive.lastModified()) {
                return out.getAbsolutePath();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                copy(in, out);
            }
            out.setReadable(true, true);
            out.setExecutable(true, true);
            out.setLastModified(Math.max(out.lastModified(), archive.lastModified()));
            LimeLog.info("Unpacked custom Vulkan driver " + name);
            return out.getAbsolutePath();
        }
    }

    private static boolean isZip(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip") || name.endsWith(".adpkg")) {
            return true;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            int read = in.read(magic);
            return read == 4 && magic[0] == 'P' && magic[1] == 'K';
        }
        catch (IOException e) {
            return false;
        }
    }

    private static String libraryName(File metaJson) {
        if (!metaJson.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(metaJson)) {
            return new JSONObject(readAll(in)).optString("libraryName", null);
        }
        catch (Exception e) {
            LimeLog.warning("Unreadable " + metaJson + ": " + e);
            return null;
        }
    }

    private static String libraryNameInZip(ZipFile zip) throws IOException {
        ZipEntry meta = findEntry(zip, "meta.json");
        if (meta == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(meta)) {
            return new JSONObject(readAll(in)).optString("libraryName", null);
        }
        catch (Exception e) {
            LimeLog.warning("Unreadable meta.json in the driver archive: " + e);
            return null;
        }
    }

    private static ZipEntry findEntry(ZipFile zip, String fileName) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && new File(entry.getName()).getName().equals(fileName)) {
                return entry;
            }
        }
        return null;
    }

    private static ZipEntry firstLibraryEntry(ZipFile zip) {
        ZipEntry fallback = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".so")) {
                continue;
            }
            if (isDriverName(entry.getName())) {
                return entry;
            }
            if (fallback == null) {
                fallback = entry;
            }
        }
        return fallback;
    }

    private static File firstLibrary(File[] files) {
        File fallback = null;
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".so")) {
                continue;
            }
            if (isDriverName(file.getName())) {
                return file;
            }
            if (fallback == null) {
                fallback = file;
            }
        }
        return fallback;
    }

    private static boolean isDriverName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("vulkan") || lower.contains("freedreno") || lower.contains("turnip") ||
                lower.contains("adreno");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }

    private static void copy(InputStream in, File out) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
        }
    }
}
