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
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The custom Vulkan driver setting: importing the driver file, and turning the setting into a
 * path libadrenotools can dlopen.
 *
 * A driver is native code that runs inside Starwire with all of its permissions, so the file is
 * checked three times:
 * - When it's imported: a usable name, at most 64 MB, and a 64-bit ARM shared library (for an
 *   archive, the library inside it). Its SHA-256 is recorded, and older imports are deleted.
 * - Before it's loaded ({@link #resolve}): it must be inside Starwire's driver folder and still
 *   have the SHA-256 recorded at import.
 * - In native code (vk_api.cpp): the ELF header again, and Android 10 or newer.
 *
 * A plain .so is used as-is. A .zip/.adpkg, which is how Turnip drivers are usually shared, has
 * its library unpacked first, into a folder named after the archive's SHA-256, using meta.json's
 * "libraryName" to pick which .so to load.
 *
 * libadrenotools can only load the driver from app-private storage, so the archive can't be used
 * in place.
 */
public final class CustomVulkanDriver {
    /** The folder in filesDir that imported drivers live in. Nothing outside it is loaded. */
    public static final String DIR_NAME = "custom_vulkan_driver";

    // Turnip builds are a few MB to a few tens of MB, and their archives hold a handful of files
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final int MAX_ARCHIVE_ENTRIES = 64;

    // Imported names can't start with a dot, so these never clash with a driver's own name
    private static final String IMPORTING = ".importing";
    private static final String UNPACKED_PREFIX = ".unpacked-";

    private CustomVulkanDriver() {}

    /** A driver file that was imported, with its SHA-256 in lower-case hex. */
    public static final class Imported {
        public final File file;
        public final String sha256;

        Imported(File file, String sha256) {
            this.file = file;
            this.sha256 = sha256;
        }
    }

    public static File directory(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    /**
     * Copies a driver into {@code dir} and checks it. If it can be used, every other file in
     * {@code dir} (older drivers and their unpacked libraries) is deleted. If it can't, {@code dir}
     * is left as it was.
     *
     * @param displayName the file's name as the document picker reports it, or null
     * @throws IOException with a short reason to show, when the file can't be used
     */
    public static Imported importDriver(File dir, String displayName, InputStream in) throws IOException {
        String name = displayName == null || displayName.trim().isEmpty() ? "custom_driver.so" : fileName(displayName);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir);
        }

        File partial = new File(dir, IMPORTING);
        String sha256;
        try {
            sha256 = copy(in, partial);
            check(partial, name);
        }
        catch (IOException | RuntimeException e) {
            partial.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }

        // The new driver replaces whatever was imported before
        for (File old : listFiles(dir)) {
            if (!old.equals(partial)) {
                delete(old);
            }
        }
        File out = new File(dir, name);
        if (!partial.renameTo(out)) {
            partial.delete();
            throw new IOException("could not save " + name);
        }
        out.setReadable(true, true);
        out.setExecutable(true, true);
        LimeLog.info("Imported custom Vulkan driver " + name + ", SHA-256 " + sha256);
        return new Imported(out, sha256);
    }

    /** Deletes every imported driver and unpacked library in {@code dir}. */
    public static void removeAll(File dir) {
        for (File file : listFiles(dir)) {
            delete(file);
        }
    }

    /**
     * Absolute path to the .so to load, or null to use the system driver: the setting is empty,
     * the file is outside Starwire's driver folder, it's gone, or it no longer has the SHA-256
     * recorded when it was imported.
     */
    public static String resolve(Context context, String setting, String sha256) {
        return resolve(directory(context), setting, sha256);
    }

    static String resolve(File dir, String setting, String sha256) {
        if (setting == null || setting.trim().isEmpty()) {
            return null;
        }

        try {
            // The canonical path has no ".." and no symlinks left in it
            File file = new File(setting.trim()).getCanonicalFile();
            if (!isInside(file, dir)) {
                LimeLog.warning("Custom Vulkan driver " + setting + " is outside " + dir + "; using the system driver");
                return null;
            }
            if (!file.isFile()) {
                LimeLog.warning("Custom Vulkan driver " + setting + " not found");
                return null;
            }
            if (sha256 == null || sha256.trim().isEmpty()) {
                // Imported by Starwire 0.2.0, which didn't record it
                LimeLog.warning("Custom Vulkan driver " + file.getName() + " has no recorded SHA-256; "
                        + "choose the file again to use it");
                return null;
            }
            String actual = sha256(file);
            if (!actual.equalsIgnoreCase(sha256.trim())) {
                LimeLog.warning("Custom Vulkan driver " + file.getName() + " changed since it was imported (SHA-256 "
                        + actual + ", expected " + sha256.trim() + "); using the system driver");
                return null;
            }
            LimeLog.info("Custom Vulkan driver " + file.getName() + ", SHA-256 " + actual);

            if (!isZip(file, file.getName())) {
                return file.getAbsolutePath();
            }
            return unpack(dir, file, actual);
        }
        catch (IOException | RuntimeException e) {
            LimeLog.warning("Could not use custom Vulkan driver " + setting + ": " + e);
            return null;
        }
    }

    /**
     * The name to save an imported file under: the last part of the picker's name. Names that
     * are empty or start with a dot (".", "..", hidden files) are refused.
     */
    static String fileName(String displayName) throws IOException {
        String name = displayName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.startsWith(".") || name.indexOf('\0') >= 0) {
            throw new IOException("the file name \"" + displayName + "\" can't be used");
        }
        return name;
    }

    /** The check vk_api.cpp makes too: a 64-bit, little-endian ARM (AArch64) shared library. */
    static boolean isArm64SharedLibrary(byte[] header) {
        return header.length >= 20 &&
                header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' && header[3] == 'F' &&
                header[4] == 2 && header[5] == 1 &&          // ELFCLASS64, little-endian
                header[16] == 3 && header[17] == 0 &&        // ET_DYN
                (header[18] & 0xFF) == 183 && header[19] == 0;  // EM_AARCH64
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    static boolean isInside(File file, File dir) throws IOException {
        return file.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator);
    }

    // A plain library must be one; an archive must hold one, and not too many other files
    private static void check(File file, String name) throws IOException {
        if (!isZip(file, name)) {
            if (!isArm64SharedLibrary(readHeader(file))) {
                throw new IOException(name + " is not a 64-bit ARM library");
            }
            return;
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = libraryEntry(zip);
            if (entry.getSize() > MAX_BYTES) {
                throw new IOException(entry.getName() + " in the archive is bigger than 64 MB");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                if (!isArm64SharedLibrary(readHeader(in))) {
                    throw new IOException(entry.getName() + " in the archive is not a 64-bit ARM library");
                }
            }
        }
    }

    private static String unpack(File dir, File archive, String archiveSha256) throws IOException {
        // Named after the archive's bytes, so a different archive never reuses another's library
        File target = new File(dir, UNPACKED_PREFIX + archiveSha256);
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = libraryEntry(zip);
            String name = fileName(entry.getName());
            File out = new File(target, name);
            if (out.isFile()) {
                return out.getAbsolutePath();
            }

            // Libraries unpacked from earlier archives aren't needed any more
            for (File old : listFiles(dir)) {
                if (old.getName().startsWith(UNPACKED_PREFIX) && !old.equals(target)) {
                    delete(old);
                }
            }
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IOException("could not create " + target);
            }

            // Unpacked under another name first, so a library that's there is always complete
            File partial = new File(target, IMPORTING);
            try (InputStream in = zip.getInputStream(entry)) {
                copy(in, partial);
                if (!isArm64SharedLibrary(readHeader(partial))) {
                    throw new IOException(name + " in the archive is not a 64-bit ARM library");
                }
                if (!partial.renameTo(out)) {
                    throw new IOException("could not unpack " + name);
                }
            }
            finally {
                partial.delete();
            }
            out.setReadable(true, true);
            out.setExecutable(true, true);
            LimeLog.info("Unpacked custom Vulkan driver " + name);
            return out.getAbsolutePath();
        }
    }

    private static ZipEntry libraryEntry(ZipFile zip) throws IOException {
        if (zip.size() > MAX_ARCHIVE_ENTRIES) {
            throw new IOException("the archive has more than " + MAX_ARCHIVE_ENTRIES + " files");
        }
        String wanted = libraryNameInZip(zip);
        ZipEntry entry = wanted != null ? findEntry(zip, wanted) : null;
        if (entry == null) {
            entry = firstLibraryEntry(zip);
        }
        if (entry == null) {
            throw new IOException("no driver library (.so) in the archive");
        }
        return entry;
    }

    private static boolean isZip(File file, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip") || lower.endsWith(".adpkg")) {
            return true;
        }
        byte[] magic = readHeader(file);
        return magic.length >= 4 && magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
    }

    private static byte[] readHeader(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return readHeader(in);
        }
        catch (IOException e) {
            return new byte[0];
        }
    }

    private static byte[] readHeader(InputStream in) throws IOException {
        byte[] header = new byte[20];
        int total = 0;
        int read;
        while (total < header.length && (read = in.read(header, total, header.length - total)) != -1) {
            total += read;
        }
        if (total < header.length) {
            byte[] shorter = new byte[total];
            System.arraycopy(header, 0, shorter, 0, total);
            return shorter;
        }
        return header;
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

    private static boolean isDriverName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("vulkan") || lower.contains("freedreno") || lower.contains("turnip") ||
                lower.contains("adreno");
    }

    private static File[] listFiles(File dir) {
        File[] files = dir.listFiles();
        return files != null ? files : new File[0];
    }

    // Starwire only makes plain files and one level of folders in here. A symlink is deleted
    // itself, never what it points to.
    private static void delete(File file) {
        if (file.isDirectory() && !isSymlink(file)) {
            for (File child : listFiles(file)) {
                child.delete();
            }
        }
        file.delete();
    }

    private static boolean isSymlink(File file) {
        try {
            File parent = file.getAbsoluteFile().getParentFile().getCanonicalFile();
            File here = new File(parent, file.getName());
            return !here.getCanonicalFile().equals(here.getAbsoluteFile());
        }
        catch (IOException e) {
            return true;
        }
    }

    // Copies at most MAX_BYTES and returns the SHA-256 of what was copied
    private static String copy(InputStream in, File out) throws IOException {
        MessageDigest digest = newSha256();
        long total = 0;
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) {
                    throw new IOException("the file is bigger than 64 MB, too big for a driver");
                }
                digest.update(buffer, 0, read);
                os.write(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
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
}
