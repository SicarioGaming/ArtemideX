package com.limelight.binding.video;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.limelight.RepoFiles;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Importing a custom Vulkan driver, and the checks made before one is loaded. A driver is native
 * code that runs inside Starwire, so these are the guard rails around it. No device needed: the
 * fixtures are made here, with just enough of an ELF header for the checks.
 */
public class CustomVulkanDriverImportTest {
    private static final int AARCH64 = 183;
    private static final int X86_64 = 62;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File dir;

    @Before
    public void setUp() throws IOException {
        dir = new File(temp.getRoot(), CustomVulkanDriver.DIR_NAME);
    }

    // An ELF header for a shared library (type 3) or an executable (type 2) on the given machine,
    // then the body
    private static byte[] elf(int machine, int type, String body) {
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[64 + text.length];
        bytes[0] = 0x7F;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;    // 64-bit
        bytes[5] = 1;    // little-endian
        bytes[6] = 1;
        bytes[16] = (byte) type;
        bytes[18] = (byte) machine;
        bytes[19] = (byte) (machine >> 8);
        System.arraycopy(text, 0, bytes, 64, text.length);
        return bytes;
    }

    private static byte[] driver(String body) {
        return elf(AARCH64, 3, body);
    }

    private static byte[] zip(Object[][] entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Object[] entry : entries) {
                zip.putNextEntry(new ZipEntry((String) entry[0]));
                zip.write(entry[1] instanceof byte[] ? (byte[]) entry[1]
                        : ((String) entry[1]).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private CustomVulkanDriver.Imported importBytes(String name, byte[] bytes) throws IOException {
        return CustomVulkanDriver.importDriver(dir, name, new ByteArrayInputStream(bytes));
    }

    private void assertRefused(String name, byte[] bytes, String reason) {
        try {
            importBytes(name, bytes);
            fail(name + " was imported");
        }
        catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(reason));
        }
    }

    private static byte[] read(String path) throws IOException {
        return Files.readAllBytes(new File(path).toPath());
    }

    @Test
    public void importsAnArm64LibraryAndRecordsItsSha256() throws Exception {
        byte[] bytes = driver("turnip");
        CustomVulkanDriver.Imported imported = importBytes("libvulkan_freedreno.so", bytes);
        assertEquals(new File(dir, "libvulkan_freedreno.so"), imported.file);
        assertArrayEquals(bytes, read(imported.file.getPath()));
        assertEquals(sha256(bytes), imported.sha256);
        assertEquals(imported.file.getAbsolutePath(),
                CustomVulkanDriver.resolve(dir, imported.file.getPath(), imported.sha256));
        // Nothing half-copied is left behind
        assertEquals(1, dir.listFiles().length);
    }

    @Test
    public void refusesFilesThatArentArm64Libraries() {
        assertRefused("libvulkan.so", "not a real driver".getBytes(StandardCharsets.UTF_8), "not a 64-bit ARM library");
        assertRefused("libvulkan.so", elf(X86_64, 3, "x86"), "not a 64-bit ARM library");
        assertRefused("libvulkan.so", elf(AARCH64, 2, "an executable"), "not a 64-bit ARM library");
        assertRefused("libvulkan.so", new byte[0], "not a 64-bit ARM library");
        assertEquals(0, dir.listFiles().length);
    }

    @Test
    public void refusesNamesThatArentAPlainFileName() throws Exception {
        for (String name : new String[] {".", "..", "drivers/..", "drivers/", ".hidden.so", "  "}) {
            if (name.trim().isEmpty()) {
                // The picker gave no name at all, which is fine
                assertEquals("custom_driver.so", importBytes(name, driver("x")).file.getName());
                continue;
            }
            assertRefused(name, driver("x"), "can't be used");
        }
        assertEquals("custom_driver.so", importBytes(null, driver("x")).file.getName());
        // Only the last part of a path is used, so a name can't point outside the folder
        assertEquals(new File(dir, "x.so"), importBytes("../../shared_prefs/x.so", driver("x")).file);
        assertEquals(new File(dir, "turnip.zip"), CustomVulkanDriver.importDriver(dir, "D:\\Downloads\\drivers\\turnip.zip",
                new ByteArrayInputStream(zip(new Object[][] {{"libvulkan_freedreno.so", driver("x")}}))).file);
    }

    @Test
    public void refusesFilesBiggerThan64MB() {
        byte[] header = driver("");
        // Zeros after the header, made as they're read rather than held in memory
        InputStream zeros = new InputStream() {
            private long left = CustomVulkanDriver.MAX_BYTES + 1 - header.length;

            @Override
            public int read() {
                return left-- > 0 ? 0 : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (left <= 0) {
                    return -1;
                }
                int n = (int) Math.min(len, left);
                Arrays.fill(b, off, off + n, (byte) 0);
                left -= n;
                return n;
            }
        };
        try {
            CustomVulkanDriver.importDriver(dir, "libvulkan.so",
                    new SequenceInputStream(new ByteArrayInputStream(header), zeros));
            fail("a file over 64 MB was imported");
        }
        catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("64 MB"));
        }
        assertEquals(0, dir.listFiles().length);
    }

    @Test
    public void aRefusedFileKeepsTheDriverImportedBefore() throws Exception {
        CustomVulkanDriver.Imported first = importBytes("libvulkan_a.so", driver("a"));
        assertRefused("libvulkan_b.so", "junk".getBytes(StandardCharsets.UTF_8), "not a 64-bit ARM library");
        assertEquals(first.file.getAbsolutePath(), CustomVulkanDriver.resolve(dir, first.file.getPath(), first.sha256));
    }

    @Test
    public void aNewImportDeletesTheOldDriverAndWhatWasUnpackedFromIt() throws Exception {
        CustomVulkanDriver.Imported archive = importBytes("turnip.adpkg",
                zip(new Object[][] {{"vulkan.ad07XX.so", driver("old")}}));
        assertNotNull(CustomVulkanDriver.resolve(dir, archive.file.getPath(), archive.sha256));
        assertEquals(2, dir.listFiles().length);    // the archive and its unpacked library

        CustomVulkanDriver.Imported plain = importBytes("libvulkan_new.so", driver("new"));
        File[] left = dir.listFiles();
        assertEquals(1, left.length);
        assertEquals(plain.file, left[0]);
    }

    @Test
    public void removeAllDeletesEverything() throws Exception {
        CustomVulkanDriver.Imported archive = importBytes("turnip.zip",
                zip(new Object[][] {{"vulkan.ad07XX.so", driver("x")}}));
        assertNotNull(CustomVulkanDriver.resolve(dir, archive.file.getPath(), archive.sha256));
        CustomVulkanDriver.removeAll(dir);
        assertEquals(0, dir.listFiles().length);
        CustomVulkanDriver.removeAll(new File(temp.getRoot(), "never-made"));
    }

    @Test
    public void onlyFilesInsideTheDriverFolderAreLoaded() throws Exception {
        CustomVulkanDriver.Imported imported = importBytes("libvulkan.so", driver("inside"));

        // The same bytes somewhere else, with the right SHA-256
        File outside = new File(temp.newFolder("elsewhere"), "libvulkan.so");
        Files.write(outside.toPath(), driver("inside"));
        assertNull(CustomVulkanDriver.resolve(dir, outside.getPath(), imported.sha256));
        // Climbing out with ".." doesn't get around it
        assertNull(CustomVulkanDriver.resolve(dir,
                dir.getPath() + File.separator + ".." + File.separator + "elsewhere" + File.separator + "libvulkan.so",
                imported.sha256));
        // A folder with a name that starts like it doesn't count either
        File lookalike = new File(temp.getRoot(), CustomVulkanDriver.DIR_NAME + "2");
        assertTrue(lookalike.mkdirs());
        File copy = new File(lookalike, "libvulkan.so");
        Files.write(copy.toPath(), driver("inside"));
        assertNull(CustomVulkanDriver.resolve(dir, copy.getPath(), imported.sha256));
        // A relative path isn't inside it
        assertNull(CustomVulkanDriver.resolve(dir, "libvulkan.so", imported.sha256));

        assertEquals(imported.file.getAbsolutePath(),
                CustomVulkanDriver.resolve(dir, imported.file.getPath(), imported.sha256));
    }

    @Test
    public void aDriverChangedSinceItWasImportedIsntLoaded() throws Exception {
        CustomVulkanDriver.Imported imported = importBytes("libvulkan.so", driver("original"));
        try (FileOutputStream out = new FileOutputStream(imported.file)) {
            out.write(driver("swapped"));
        }
        assertNull(CustomVulkanDriver.resolve(dir, imported.file.getPath(), imported.sha256));
        // Upper case is the same hash
        assertEquals(imported.file.getAbsolutePath(), CustomVulkanDriver.resolve(dir, imported.file.getPath(),
                sha256(driver("swapped")).toUpperCase(Locale.ROOT)));
    }

    @Test
    public void aDriverWithNoRecordedSha256IsntLoaded() throws Exception {
        // How Starwire 0.2.0 left it: the file, but no hash
        CustomVulkanDriver.Imported imported = importBytes("libvulkan.so", driver("from 0.2.0"));
        assertNull(CustomVulkanDriver.resolve(dir, imported.file.getPath(), null));
        assertNull(CustomVulkanDriver.resolve(dir, imported.file.getPath(), " "));
    }

    @Test
    public void emptyOrMissingSettingMeansTheSystemDriver() throws Exception {
        assertNull(CustomVulkanDriver.resolve(dir, null, null));
        assertNull(CustomVulkanDriver.resolve(dir, "  ", "00"));
        assertNull(CustomVulkanDriver.resolve(dir, new File(dir, "gone.so").getPath(), sha256(driver("x"))));
    }

    @Test
    public void archiveUsesTheLibraryNamedInMetaJson() throws Exception {
        CustomVulkanDriver.Imported archive = importBytes("turnip_test.adpkg", zip(new Object[][] {
                {"meta.json", "{\"name\":\"Turnip\",\"libraryName\":\"vulkan.ad07XX.so\"}"},
                {"libother.so", driver("other")},
                {"vulkan.ad07XX.so", driver("driver")},
        }));
        String path = CustomVulkanDriver.resolve(dir, archive.file.getPath(), archive.sha256);
        assertEquals("vulkan.ad07XX.so", new File(path).getName());
        assertArrayEquals(driver("driver"), read(path));
    }

    @Test
    public void archiveWithoutMetaJsonPicksTheDriverLibrary() throws Exception {
        CustomVulkanDriver.Imported archive = importBytes("turnip_nometa.zip", zip(new Object[][] {
                {"README.txt", "hi"},
                {"libhelper.so", driver("helper")},
                {"lib/arm64-v8a/libvulkan_freedreno.so", driver("freedreno")},
        }));
        String path = CustomVulkanDriver.resolve(dir, archive.file.getPath(), archive.sha256);
        assertEquals("libvulkan_freedreno.so", new File(path).getName());
        assertArrayEquals(driver("freedreno"), read(path));
    }

    // Regression: the unpacked library was reused whenever it was newer than the archive, so a
    // different archive with the same library name kept loading the old library
    @Test
    public void archivesWithTheSameLibraryNameEachUnpackTheirOwn() throws Exception {
        assertTrue(dir.mkdirs());
        File one = new File(dir, "one.adpkg");
        File two = new File(dir, "two.adpkg");
        Files.write(one.toPath(), zip(new Object[][] {{"vulkan.ad07XX.so", driver("one")}}));
        Files.write(two.toPath(), zip(new Object[][] {{"vulkan.ad07XX.so", driver("two")}}));
        // The second archive is older on disk, as one copied from somewhere else can be
        assertTrue(two.setLastModified(one.lastModified() - 60_000));
        String shaOne = CustomVulkanDriver.sha256(one);
        String shaTwo = CustomVulkanDriver.sha256(two);

        assertArrayEquals(driver("one"), read(CustomVulkanDriver.resolve(dir, one.getPath(), shaOne)));
        assertArrayEquals(driver("two"), read(CustomVulkanDriver.resolve(dir, two.getPath(), shaTwo)));
        assertArrayEquals(driver("one"), read(CustomVulkanDriver.resolve(dir, one.getPath(), shaOne)));
    }

    @Test
    public void refusesArchivesWithoutAUsableLibrary() throws Exception {
        assertRefused("empty.zip", zip(new Object[][] {{"README.txt", "no driver here"}}), "no driver library");
        assertRefused("text.zip", zip(new Object[][] {{"libvulkan_freedreno.so", "not a real driver"}}),
                "not a 64-bit ARM library");
        assertRefused("x86.zip", zip(new Object[][] {{"libvulkan_freedreno.so", elf(X86_64, 3, "x86")}}),
                "not a 64-bit ARM library");
        Object[][] many = new Object[CustomVulkanDriver.MAX_ARCHIVE_ENTRIES + 1][];
        for (int i = 0; i < many.length; i++) {
            many[i] = new Object[] {"file" + i + ".txt", "x"};
        }
        many[0] = new Object[] {"libvulkan_freedreno.so", driver("x")};
        assertRefused("many.zip", zip(many), "more than " + CustomVulkanDriver.MAX_ARCHIVE_ENTRIES + " files");
        assertRefused("broken.zip", "PK not really".getBytes(StandardCharsets.UTF_8), "");
        assertEquals(0, dir.listFiles().length);
    }

    @Test
    public void theHeaderCheckMatchesTheNativeOne() {
        assertTrue(CustomVulkanDriver.isArm64SharedLibrary(driver("")));
        assertFalse(CustomVulkanDriver.isArm64SharedLibrary(elf(X86_64, 3, "")));
        assertFalse(CustomVulkanDriver.isArm64SharedLibrary(elf(AARCH64, 2, "")));
        byte[] bigEndian = driver("");
        bigEndian[5] = 2;
        assertFalse(CustomVulkanDriver.isArm64SharedLibrary(bigEndian));
        byte[] thirtyTwoBit = driver("");
        thirtyTwoBit[4] = 1;
        assertFalse(CustomVulkanDriver.isArm64SharedLibrary(thirtyTwoBit));
        assertFalse(CustomVulkanDriver.isArm64SharedLibrary(new byte[19]));
    }

    // The driver folder is native code chosen on this device, so a backup never brings one back
    @Test
    public void theDriverFolderIsLeftOutOfBackups() throws Exception {
        String rule = "<exclude domain=\"file\" path=\"" + CustomVulkanDriver.DIR_NAME + "\"/>";
        String full = RepoFiles.read("app/src/main/res/xml/backup_rules.xml");
        assertTrue("backup_rules.xml (Android 11 and older)", full.contains(rule));

        String rules = RepoFiles.read("app/src/main/res/xml/backup_rules_s.xml");
        for (String section : new String[] {"cloud-backup", "device-transfer"}) {
            Matcher m = Pattern.compile("<" + section + "[^>]*>(.*?)</" + section + ">", Pattern.DOTALL).matcher(rules);
            assertTrue(section + " in backup_rules_s.xml", m.find());
            assertTrue(section + " in backup_rules_s.xml (Android 12 and newer)", m.group(1).contains(rule));
        }
    }
}
