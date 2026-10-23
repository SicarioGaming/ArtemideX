package com.limelight.ui.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Custom key commands as they're saved for the in-stream menu.
 */
public class CustomCommandTest {
    // Android's KeyEvent codes, written out since android.jar has no values on the JVM
    private static final int KEYCODE_TAB = 61;
    private static final int KEYCODE_ESCAPE = 111;

    @Test
    public void jsonRoundTrip() throws Exception {
        CustomCommand.KeyCombination keys = new CustomCommand.KeyCombination(true, false, true, false, KEYCODE_ESCAPE);
        CustomCommand command = new CustomCommand("id-1", "Task Manager", OverlayIcons.getIconByName("Task Manager"),
                keys, CustomCommand.POST_ACTION_CLOSE_MENU);

        CustomCommand back = CustomCommand.fromJson(new JSONObject(command.toJson().toString()));
        assertEquals("id-1", back.getId());
        assertEquals("Task Manager", back.getName());
        assertEquals(OverlayIcons.getIconByName("Task Manager"), back.getIconResId());
        assertEquals(CustomCommand.POST_ACTION_CLOSE_MENU, back.getPostAction());
        assertTrue(back.getKeyCombination().isCtrl());
        assertFalse(back.getKeyCombination().isAlt());
        assertTrue(back.getKeyCombination().isShift());
        assertFalse(back.getKeyCombination().isMeta());
        assertEquals(KEYCODE_ESCAPE, back.getKeyCombination().getKeyCode());
    }

    // Saved before post-actions existed, or with an icon a later version removed
    @Test
    public void olderCommandsStillLoad() throws Exception {
        CustomCommand old = CustomCommand.fromJson(new JSONObject("{\"id\":\"x\",\"name\":\"Old\",\"iconName\":\"Gone\","
                + "\"keyCombination\":{\"ctrl\":false,\"alt\":true,\"shift\":false,\"meta\":false,\"keyCode\":61}}"));
        assertEquals(CustomCommand.POST_ACTION_NONE, old.getPostAction());
        assertEquals(OverlayIcons.getDefaultIcon(), old.getIconResId());
        assertEquals(KEYCODE_TAB, old.getKeyCombination().getKeyCode());
    }

    @Test
    public void aCommandWithoutItsKeysIsRefused() {
        for (String json : new String[] {"{\"id\":\"x\",\"name\":\"N\",\"iconName\":\"Close\"}",
                "{\"name\":\"N\",\"iconName\":\"Close\",\"keyCombination\":{\"ctrl\":false,\"alt\":false,\"shift\":false,\"meta\":false,\"keyCode\":61}}",
                "{\"id\":\"x\",\"name\":\"N\",\"iconName\":\"Close\",\"keyCombination\":{\"keyCode\":61}}"}) {
            try {
                CustomCommand.fromJson(new JSONObject(json));
                fail("accepted " + json);
            } catch (JSONException expected) {
            }
        }
    }

    // A command saves its icon by name, so every icon on offer needs its own name that finds the
    // same picture again
    @Test
    public void iconNamesFindTheirIcons() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (OverlayIcons.IconOption icon : OverlayIcons.AVAILABLE_ICONS) {
            assertTrue("two icons named " + icon.name, names.add(icon.name));
            assertEquals(icon.name, icon.resourceId, OverlayIcons.getIconByName(OverlayIcons.getIconName(icon.resourceId)));
        }
        assertEquals("Unknown", OverlayIcons.getIconName(-1));
    }
}
