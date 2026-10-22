package com.limelight.ui.overlay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Shortcuts imported from a file in Artemide's format, and their place on the menu's
 * Shortcuts page.
 */
@RunWith(AndroidJUnit4.class)
public class ImportedShortcutsTest {
    private static final String FILE = "{\"data\": ["
            + "{\"name\": \"Snip\", \"keys\": [\"VK_LWIN\", \"VK_LSHIFT\", \"VK_S\"]},"
            + "{\"name\": \"Ctrl+Alt+Del\", \"keys\": [\"0x11\", \"0x12\", \"0x2e\"]}"
            + "]}";

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After
    public void tearDown() {
        ImportedShortcuts.clear(context);
    }

    @Test
    public void readsArtemidesFormat() {
        List<ImportedShortcuts.Shortcut> shortcuts = ImportedShortcuts.parse(FILE);
        assertEquals(2, shortcuts.size());
        assertEquals("Snip", shortcuts.get(0).name);
        assertArrayEquals(new short[]{0x5B, 0xA0, 0x53}, shortcuts.get(0).keys);
        assertArrayEquals(new short[]{0x11, 0x12, 0x2E}, shortcuts.get(1).keys);
    }

    @Test
    public void acceptsABareListAndShortNames() {
        List<ImportedShortcuts.Shortcut> shortcuts = ImportedShortcuts.parse(
                "[{\"name\": \"Save\", \"keys\": [\"vk_ctrl\", \"VK_S\"]}, {\"name\": \"F13\", \"keys\": [124]}]");
        assertArrayEquals(new short[]{0x11, 0x53}, shortcuts.get(0).keys);
        assertArrayEquals(new short[]{0x7C}, shortcuts.get(1).keys);
    }

    @Test
    public void saysWhatIsWrongWithABadFile() {
        assertRejected("not json at all", "not a JSON file");
        assertRejected("{\"shortcuts\": []}", "no \"data\" list");
        assertRejected("{\"data\": []}", "empty");
        assertRejected("{\"data\": [{\"keys\": [\"VK_A\"]}]}", "shortcut 1 has no name");
        assertRejected("{\"data\": [{\"name\": \"X\", \"keys\": []}]}", "\"X\" has no keys");
        assertRejected("{\"data\": [{\"name\": \"X\", \"keys\": [\"VK_NOPE\"]}]}", "unknown key: VK_NOPE");
        assertRejected("{\"data\": [{\"name\": \"X\", \"keys\": [\"0x1FF\"]}]}", "unknown key");
    }

    private static void assertRejected(String text, String reason) {
        try {
            ImportedShortcuts.parse(text);
            fail("accepted " + text);
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage() + " should mention " + reason, e.getMessage().contains(reason));
        }
    }

    @Test
    public void aBadFileKeepsTheEarlierImport() {
        assertEquals(2, ImportedShortcuts.save(context, FILE));
        try {
            ImportedShortcuts.save(context, "{\"data\": []}");
            fail();
        }
        catch (IllegalArgumentException expected) {
        }
        assertEquals(2, ImportedShortcuts.load(context).size());
    }

    @Test
    public void importedShortcutsAppearOnTheShortcutsPageAndSendTheirKeys() {
        ImportedShortcuts.save(context, FILE);
        short[][] sent = new short[1][];
        OverlayMenuView[] menu = new OverlayMenuView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            menu[0] = new OverlayMenuView(context);
            menu[0].setMenuActionListener(new RecordingKeys(sent));
            menu[0].buildMenu();
            findButton(menu[0], context.getString(com.limelight.R.string.overlay_menu_shortcuts)).performClick();
            View snip = findButton(menu[0], "Snip");
            assertNotNull("imported shortcut missing from the Shortcuts page", snip);
            snip.performClick();
        });
        assertArrayEquals(new short[]{0x5B, 0xA0, 0x53}, sent[0]);
    }

    private static View findButton(View root, String label) {
        if (root instanceof OverlayMenuButton) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView && label.contentEquals(((TextView) child).getText())) {
                    return root;
                }
            }
            return null;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findButton(group.getChildAt(i), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static class RecordingKeys implements OverlayMenuView.MenuActionListener {
        private final short[][] sent;

        RecordingKeys(short[][] sent) {
            this.sent = sent;
        }

        @Override public void onSendKeys(short[] keys) { sent[0] = keys; }
        @Override public void onDisconnect() {}
        @Override public void onQuitSession() {}
        @Override public void onToggleStats() {}
        @Override public void onToggleMouseEmulation() {}
        @Override public void onShowKeyboard() {}
        @Override public void onSendGuideButton() {}
        @Override public void onCustomCommand(CustomCommand command) {}
        @Override public void onMenuClosed() {}
        @Override public void onRotateScreen() {}
        @Override public void onToggleOnscreenController() {}
        @Override public void onToggleFloatingButton() {}
        @Override public void onTogglePanZoom() {}
        @Override public void onSelectMouseMode() {}
        @Override public void onToggleTouchSensitivity() {}
        @Override public void onUploadClipboard() {}
        @Override public void onFetchClipboard() {}
        @Override public void onShowServerCommands() {}
        @Override public void onToggleFullKeyboard() {}
        @Override public void onChangeBitrate() {}
    }
}
