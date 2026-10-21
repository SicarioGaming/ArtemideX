package com.limelight.ui.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Key shortcuts imported from a file, shown on the in-stream menu's Shortcuts page after the
 * built-in ones. Same file format as Artemide's "import custom shortcuts":
 *
 * <pre>
 * {"data": [
 *   {"name": "Snipping tool", "keys": ["VK_LWIN", "VK_LSHIFT", "VK_S"]},
 *   {"name": "Ctrl+Alt+Del", "keys": ["0x11", "0x12", "0x2E"]}
 * ]}
 * </pre>
 *
 * Keys are Windows virtual key names (VK_...) or hex codes, pressed in order and released
 * together. A bare list without the "data" wrapper is accepted too. Importing replaces the
 * previous file.
 */
public final class ImportedShortcuts {
    static final String PREF_KEY = "overlay_imported_shortcuts";
    static final int MAX_SHORTCUTS = 50;
    static final int MAX_KEYS = 8;
    // Shortcut files are tiny; anything bigger is the wrong file
    public static final int MAX_FILE_BYTES = 256 * 1024;

    public static final class Shortcut {
        public final String name;
        public final short[] keys;

        Shortcut(String name, short[] keys) {
            this.name = name;
            this.keys = keys;
        }
    }

    private ImportedShortcuts() {}

    /** Reads a shortcuts file, or throws IllegalArgumentException saying what is wrong. */
    public static List<Shortcut> parse(String text) {
        Object root;
        try {
            root = new JSONTokener(text.trim()).nextValue();
        }
        catch (JSONException e) {
            throw new IllegalArgumentException("not a JSON file");
        }

        JSONArray list;
        if (root instanceof JSONObject && ((JSONObject) root).optJSONArray("data") != null) {
            list = ((JSONObject) root).optJSONArray("data");
        }
        else if (root instanceof JSONArray) {
            list = (JSONArray) root;
        }
        else if (root instanceof JSONObject) {
            throw new IllegalArgumentException("no \"data\" list of shortcuts");
        }
        else {
            // The JSON reader takes a bare word as a value, so plain text ends up here
            throw new IllegalArgumentException("not a JSON file");
        }
        if (list.length() == 0) {
            throw new IllegalArgumentException("the \"data\" list is empty");
        }
        if (list.length() > MAX_SHORTCUTS) {
            throw new IllegalArgumentException("more than " + MAX_SHORTCUTS + " shortcuts");
        }

        List<Shortcut> shortcuts = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            String where = "shortcut " + (i + 1);
            if (item == null) {
                throw new IllegalArgumentException(where + " is not an object");
            }
            String name = item.optString("name", "").trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException(where + " has no name");
            }
            JSONArray keys = item.optJSONArray("keys");
            if (keys == null || keys.length() == 0) {
                throw new IllegalArgumentException("\"" + name + "\" has no keys");
            }
            if (keys.length() > MAX_KEYS) {
                throw new IllegalArgumentException("\"" + name + "\" has more than " + MAX_KEYS + " keys");
            }
            short[] codes = new short[keys.length()];
            for (int k = 0; k < keys.length(); k++) {
                int code = keyCode(keys.opt(k));
                if (code < 0) {
                    throw new IllegalArgumentException("\"" + name + "\" has an unknown key: " + keys.opt(k));
                }
                codes[k] = (short) code;
            }
            shortcuts.add(new Shortcut(name, codes));
        }
        return shortcuts;
    }

    /** The imported shortcuts, or none if there are none or the saved file no longer reads. */
    public static List<Shortcut> load(Context context) {
        String text = prefs(context).getString(PREF_KEY, null);
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return parse(text);
        }
        catch (IllegalArgumentException e) {
            return Collections.emptyList();
        }
    }

    /** Checks and saves a file's text in place of any earlier import; returns how many. */
    public static int save(Context context, String text) {
        int count = parse(text).size();
        prefs(context).edit().putString(PREF_KEY, text).apply();
        return count;
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(PREF_KEY).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    // A key from the file: "VK_LWIN", "0x5B", or a number; -1 if it isn't a key
    static int keyCode(Object key) {
        int code = -1;
        if (key instanceof Number) {
            code = ((Number) key).intValue();
        }
        else if (key instanceof String) {
            String text = ((String) key).trim().toUpperCase(Locale.ROOT);
            try {
                if (text.startsWith("0X")) {
                    code = Integer.parseInt(text.substring(2), 16);
                }
                else if (text.startsWith("VK_")) {
                    Integer named = VK_NAMES.get(text);
                    code = named != null ? named : -1;
                }
                else {
                    code = Integer.parseInt(text);
                }
            }
            catch (NumberFormatException e) {
                code = -1;
            }
        }
        return code >= 0x01 && code <= 0xFE ? code : -1;
    }

    // Windows virtual key names. Listed out rather than looked up by reflection, since release
    // builds shrink and rename fields.
    private static final Map<String, Integer> VK_NAMES = new HashMap<>();

    static {
        for (char c = 'A'; c <= 'Z'; c++) {
            VK_NAMES.put("VK_" + c, (int) c);
        }
        for (int i = 0; i <= 9; i++) {
            VK_NAMES.put("VK_" + i, 0x30 + i);
            VK_NAMES.put("VK_NUMPAD" + i, 0x60 + i);
        }
        for (int i = 1; i <= 24; i++) {
            VK_NAMES.put("VK_F" + i, 0x6F + i);
        }
        Object[][] named = {
                {"VK_BACK", 0x08}, {"VK_TAB", 0x09}, {"VK_CLEAR", 0x0C}, {"VK_RETURN", 0x0D},
                {"VK_SHIFT", 0x10}, {"VK_CONTROL", 0x11}, {"VK_MENU", 0x12}, {"VK_PAUSE", 0x13},
                {"VK_CAPITAL", 0x14}, {"VK_ESCAPE", 0x1B}, {"VK_SPACE", 0x20},
                {"VK_PRIOR", 0x21}, {"VK_NEXT", 0x22}, {"VK_END", 0x23}, {"VK_HOME", 0x24},
                {"VK_LEFT", 0x25}, {"VK_UP", 0x26}, {"VK_RIGHT", 0x27}, {"VK_DOWN", 0x28},
                {"VK_SNAPSHOT", 0x2C}, {"VK_INSERT", 0x2D}, {"VK_DELETE", 0x2E},
                {"VK_LWIN", 0x5B}, {"VK_RWIN", 0x5C}, {"VK_APPS", 0x5D},
                {"VK_MULTIPLY", 0x6A}, {"VK_ADD", 0x6B}, {"VK_SEPARATOR", 0x6C},
                {"VK_SUBTRACT", 0x6D}, {"VK_DECIMAL", 0x6E}, {"VK_DIVIDE", 0x6F},
                {"VK_NUMLOCK", 0x90}, {"VK_SCROLL", 0x91},
                {"VK_LSHIFT", 0xA0}, {"VK_RSHIFT", 0xA1}, {"VK_LCONTROL", 0xA2},
                {"VK_RCONTROL", 0xA3}, {"VK_LMENU", 0xA4}, {"VK_RMENU", 0xA5},
                {"VK_VOLUME_MUTE", 0xAD}, {"VK_VOLUME_DOWN", 0xAE}, {"VK_VOLUME_UP", 0xAF},
                {"VK_MEDIA_NEXT_TRACK", 0xB0}, {"VK_MEDIA_PREV_TRACK", 0xB1},
                {"VK_MEDIA_STOP", 0xB2}, {"VK_MEDIA_PLAY_PAUSE", 0xB3},
                {"VK_OEM_1", 0xBA}, {"VK_OEM_PLUS", 0xBB}, {"VK_OEM_COMMA", 0xBC},
                {"VK_OEM_MINUS", 0xBD}, {"VK_OEM_PERIOD", 0xBE}, {"VK_OEM_2", 0xBF},
                {"VK_OEM_3", 0xC0}, {"VK_OEM_4", 0xDB}, {"VK_OEM_5", 0xDC},
                {"VK_OEM_6", 0xDD}, {"VK_OEM_7", 0xDE},
                // Common short names
                {"VK_ALT", 0x12}, {"VK_LALT", 0xA4}, {"VK_RALT", 0xA5}, {"VK_CTRL", 0x11},
                {"VK_LCTRL", 0xA2}, {"VK_RCTRL", 0xA3}, {"VK_WIN", 0x5B}, {"VK_ENTER", 0x0D},
                {"VK_ESC", 0x1B}, {"VK_BACKSPACE", 0x08}, {"VK_DEL", 0x2E},
                {"VK_PAGEUP", 0x21}, {"VK_PAGEDOWN", 0x22}, {"VK_PRINTSCREEN", 0x2C},
        };
        for (Object[] pair : named) {
            VK_NAMES.put((String) pair[0], (Integer) pair[1]);
        }
    }
}
