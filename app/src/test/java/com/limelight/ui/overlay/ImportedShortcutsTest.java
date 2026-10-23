package com.limelight.ui.overlay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import com.limelight.RepoFiles;

import org.junit.Test;

import java.util.List;

/**
 * Reading a shortcuts file in Artemide's format. Saving it and its place on the menu's Shortcuts
 * page are checked on the device (ImportedShortcutsMenuTest).
 */
public class ImportedShortcutsTest {
    private static final String FILE = "{\"data\": ["
            + "{\"name\": \"Snip\", \"keys\": [\"VK_LWIN\", \"VK_LSHIFT\", \"VK_S\"]},"
            + "{\"name\": \"Ctrl+Alt+Del\", \"keys\": [\"0x11\", \"0x12\", \"0x2e\"]},"
            + "{\"name\": \" Task view \", \"keys\": [\"VK_LWIN\", \"VK_TAB\"]}"
            + "]}";

    @Test
    public void readsArtemidesFormat() {
        List<ImportedShortcuts.Shortcut> shortcuts = ImportedShortcuts.parse(FILE);
        assertEquals(3, shortcuts.size());
        assertEquals("Snip", shortcuts.get(0).name);
        assertArrayEquals(new short[]{0x5B, 0xA0, 0x53}, shortcuts.get(0).keys);
        assertArrayEquals(new short[]{0x11, 0x12, 0x2E}, shortcuts.get(1).keys);
        // Spaces around a name are dropped
        assertEquals("Task view", shortcuts.get(2).name);
        assertArrayEquals(new short[]{0x5B, 0x09}, shortcuts.get(2).keys);
    }

    @Test
    public void acceptsABareListAndShortNames() {
        List<ImportedShortcuts.Shortcut> shortcuts = ImportedShortcuts.parse(
                "[{\"name\": \"Save\", \"keys\": [\"vk_ctrl\", \"VK_S\"]}, {\"name\": \"F13\", \"keys\": [124]}]");
        assertArrayEquals(new short[]{0x11, 0x53}, shortcuts.get(0).keys);
        assertArrayEquals(new short[]{0x7C}, shortcuts.get(1).keys);
    }

    @Test
    public void keyCodesInEveryForm() {
        assertEquals(0x5B, ImportedShortcuts.keyCode("vk_lwin"));
        assertEquals(0x5B, ImportedShortcuts.keyCode(" VK_LWIN "));
        assertEquals(0x5B, ImportedShortcuts.keyCode("0x5b"));
        assertEquals(0x5B, ImportedShortcuts.keyCode("0X5B"));
        assertEquals(0x5B, ImportedShortcuts.keyCode(91));
        assertEquals(0x5B, ImportedShortcuts.keyCode("91"));
        assertEquals(0x70, ImportedShortcuts.keyCode("VK_F1"));
        assertEquals(0x87, ImportedShortcuts.keyCode("VK_F24"));
        assertEquals(0x69, ImportedShortcuts.keyCode("VK_NUMPAD9"));
        assertEquals(-1, ImportedShortcuts.keyCode("VK_NOPE"));
        assertEquals(-1, ImportedShortcuts.keyCode("0xZZ"));
        assertEquals(-1, ImportedShortcuts.keyCode(""));
        assertEquals(-1, ImportedShortcuts.keyCode(null));
        assertEquals(-1, ImportedShortcuts.keyCode(true));
    }

    // Windows virtual keys run from 0x01 to 0xFE
    @Test
    public void keyCodesOutsideTheWindowsRangeAreRefused() {
        assertEquals(0x01, ImportedShortcuts.keyCode("0x01"));
        assertEquals(0xFE, ImportedShortcuts.keyCode(254));
        assertEquals(-1, ImportedShortcuts.keyCode(0));
        assertEquals(-1, ImportedShortcuts.keyCode(0xFF));
        assertEquals(-1, ImportedShortcuts.keyCode("0x1FF"));
        assertEquals(-1, ImportedShortcuts.keyCode(-5));
        assertEquals(-1, ImportedShortcuts.keyCode("99999999999"));
    }

    @Test
    public void saysWhatIsWrongWithABadFile() {
        String[][] cases = {
                {"hello", "not a JSON file"},
                {"not json at all", "not a JSON file"},
                {"{\"data\": [", "not a JSON file"},
                {"{}", "no \"data\" list of shortcuts"},
                {"{\"shortcuts\": []}", "no \"data\" list of shortcuts"},
                {"[]", "the \"data\" list is empty"},
                {"{\"data\": []}", "the \"data\" list is empty"},
                {"[{\"keys\":[\"VK_A\"]}]", "shortcut 1 has no name"},
                {"[{\"name\":\"  \",\"keys\":[\"VK_A\"]}]", "shortcut 1 has no name"},
                {"[{\"name\":\"X\"}]", "\"X\" has no keys"},
                {"[{\"name\":\"X\",\"keys\":[]}]", "\"X\" has no keys"},
                {"[{\"name\":\"X\",\"keys\":[\"VK_NOPE\"]}]", "\"X\" has an unknown key: VK_NOPE"},
                {"[{\"name\":\"X\",\"keys\":[\"0x1FF\"]}]", "\"X\" has an unknown key: 0x1FF"},
                {"[1]", "shortcut 1 is not an object"},
                {"[{\"name\":\"A\",\"keys\":[\"VK_A\"]}, \"B\"]", "shortcut 2 is not an object"},
        };
        for (String[] c : cases) {
            try {
                ImportedShortcuts.parse(c[0]);
                fail("accepted " + c[0]);
            } catch (IllegalArgumentException e) {
                assertEquals(c[0], c[1], e.getMessage());
            }
        }
    }

    @Test
    public void limitsOnShortcutsAndKeys() {
        assertEquals(ImportedShortcuts.MAX_SHORTCUTS, ImportedShortcuts.parse(shortcuts(ImportedShortcuts.MAX_SHORTCUTS, 1)).size());
        assertRejected(shortcuts(ImportedShortcuts.MAX_SHORTCUTS + 1, 1), "more than 50 shortcuts");
        assertEquals(ImportedShortcuts.MAX_KEYS, ImportedShortcuts.parse(shortcuts(1, ImportedShortcuts.MAX_KEYS)).get(0).keys.length);
        assertRejected(shortcuts(1, ImportedShortcuts.MAX_KEYS + 1), "\"S1\" has more than 8 keys");
    }

    private static String shortcuts(int count, int keys) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            json.append(i > 1 ? "," : "").append("{\"name\":\"S").append(i).append("\",\"keys\":[");
            for (int k = 0; k < keys; k++) {
                json.append(k > 0 ? "," : "").append("\"VK_A\"");
            }
            json.append("]}");
        }
        return json.append("]").toString();
    }

    private static void assertRejected(String text, String message) {
        try {
            ImportedShortcuts.parse(text);
            fail("accepted " + text);
        } catch (IllegalArgumentException e) {
            assertEquals(message, e.getMessage());
        }
    }

    // The example file in docs/ must stay importable
    @Test
    public void docsExampleParses() throws Exception {
        List<ImportedShortcuts.Shortcut> shortcuts = ImportedShortcuts.parse(RepoFiles.read("docs/shortcuts-example.json"));
        assertFalse(shortcuts.isEmpty());
    }
}
