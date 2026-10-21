package com.limelight.ui.overlay;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.limelight.R;

import java.util.ArrayList;
import java.util.List;

public class OverlayMenuView extends LinearLayout {
    private static final int BUTTON_SPACING_DP = 8;
    private static final float ANALOG_STICK_THRESHOLD = 0.5f;
    private static final long ANALOG_NAV_THROTTLE_MS = 200;

    public interface MenuActionListener {
        void onDisconnect();
        void onQuitSession();
        void onToggleStats();
        void onToggleMouseEmulation();
        void onShowKeyboard();
        void onSendGuideButton();
        void onCustomCommand(CustomCommand command);
        void onMenuClosed();

        // Actions ported from Artemide's in-stream menu
        void onSendKeys(short[] keys);
        void onRotateScreen();
        void onToggleOnscreenController();
        void onToggleFloatingButton();
        void onTogglePanZoom();
        void onSelectMouseMode();
        void onToggleTouchSensitivity();
        void onUploadClipboard();
        void onFetchClipboard();
        void onShowServerCommands();
        void onToggleFullKeyboard();
    }

    // Windows virtual key codes for Artemide's built-in shortcuts
    public static final short VK_TAB = 0x09;
    public static final short VK_RETURN = 0x0D;
    public static final short VK_ESCAPE = 0x1B;
    public static final short VK_LEFT = 0x25;
    public static final short VK_B = 0x42;
    public static final short VK_D = 0x44;
    public static final short VK_G = 0x47;
    public static final short VK_V = 0x56;
    public static final short VK_LWIN = 0x5B;
    public static final short VK_F1 = 0x70;
    public static final short VK_F4 = 0x73;
    public static final short VK_F11 = 0x7A;
    public static final short VK_F12 = 0x7B;
    public static final short VK_LSHIFT = 0xA0;
    public static final short VK_LCONTROL = 0xA2;
    public static final short VK_LMENU = 0xA4; // Left Alt

    private static class Shortcut {
        final int labelResId;
        final short[] keys;

        Shortcut(int labelResId, short... keys) {
            this.labelResId = labelResId;
            this.keys = keys;
        }
    }

    // Same set and order as Artemide's "Send keys" menu
    private static final Shortcut[] SHORTCUTS = {
        new Shortcut(R.string.overlay_menu_send_esc, VK_ESCAPE),
        new Shortcut(R.string.overlay_menu_send_f11, VK_F11),
        new Shortcut(R.string.overlay_menu_send_alt_f4, VK_LMENU, VK_F4),
        new Shortcut(R.string.overlay_menu_send_alt_enter, VK_LMENU, VK_RETURN),
        new Shortcut(R.string.overlay_menu_send_ctrl_v, VK_LCONTROL, VK_V),
        new Shortcut(R.string.overlay_menu_send_win, VK_LWIN),
        new Shortcut(R.string.overlay_menu_send_win_d, VK_LWIN, VK_D),
        new Shortcut(R.string.overlay_menu_send_win_g, VK_LWIN, VK_G),
        new Shortcut(R.string.overlay_menu_send_ctrl_alt_tab, VK_LCONTROL, VK_LMENU, VK_TAB),
        new Shortcut(R.string.overlay_menu_send_shift_tab, VK_LSHIFT, VK_TAB),
        new Shortcut(R.string.overlay_menu_send_win_shift_left, VK_LWIN, VK_LSHIFT, VK_LEFT),
        new Shortcut(R.string.overlay_menu_send_ctrl_alt_shift_f1, VK_LCONTROL, VK_LMENU, VK_LSHIFT, VK_F1),
        new Shortcut(R.string.overlay_menu_send_ctrl_alt_shift_f12, VK_LCONTROL, VK_LMENU, VK_LSHIFT, VK_F12),
        new Shortcut(R.string.overlay_menu_send_win_alt_b, VK_LWIN, VK_LMENU, VK_B),
    };

    private static final short[] TASK_MANAGER_KEYS = {VK_LCONTROL, VK_LSHIFT, VK_ESCAPE};

    // True while the horizontal row shows the Shortcuts page instead of the main actions
    private boolean showingShortcuts = false;

    private LinearLayout verticalContainer;
    private HorizontalScrollView horizontalScrollView;
    private LinearLayout horizontalContainer;

    private List<OverlayMenuButton> verticalButtons;
    private List<Integer> verticalActions;
    private List<OverlayMenuButton> horizontalButtons;
    private List<Integer> horizontalActions;

    private enum Region { VERTICAL, HORIZONTAL }
    private Region activeRegion = Region.VERTICAL;
    private int verticalIndex = 0;
    private int horizontalIndex = 0;

    private MenuActionListener actionListener;
    private CustomCommandsManager commandsManager;

    private static final int ACTION_DISCONNECT = 0;
    private static final int ACTION_QUIT = 1;
    private static final int ACTION_TOGGLE_STATS = 2;
    private static final int ACTION_CLOSE = 3;
    private static final int ACTION_SHOW_KEYBOARD = 4;
    private static final int ACTION_TOGGLE_MOUSE_EMULATION = 5;
    private static final int ACTION_SEND_GUIDE = 6;
    private static final int ACTION_SHORTCUTS = 7;
    private static final int ACTION_BACK = 8;
    private static final int ACTION_TASK_MANAGER = 9;
    private static final int ACTION_ROTATE_SCREEN = 10;
    private static final int ACTION_ONSCREEN_CONTROLLER = 11;
    private static final int ACTION_FLOATING_BUTTON = 12;
    private static final int ACTION_PAN_ZOOM = 13;
    private static final int ACTION_MOUSE_MODE = 14;
    private static final int ACTION_TOUCH_SENSITIVITY = 15;
    private static final int ACTION_UPLOAD_CLIPBOARD = 16;
    private static final int ACTION_FETCH_CLIPBOARD = 17;
    private static final int ACTION_SERVER_COMMANDS = 18;
    private static final int ACTION_FULL_KEYBOARD = 19;
    private static final int ACTION_CUSTOM_BASE = 100;
    private static final int ACTION_SHORTCUT_BASE = 200;

    private long lastAnalogNavTime = 0;
    private boolean flipFaceButtons = false;

    public OverlayMenuView(Context context) {
        super(context);
        init(context);
    }

    public OverlayMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public OverlayMenuView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.BOTTOM);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundDrawable(null);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }

        verticalContainer = new LinearLayout(context);
        verticalContainer.setOrientation(LinearLayout.VERTICAL);
        verticalContainer.setBackgroundDrawable(null);
        addView(verticalContainer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        horizontalScrollView = new HorizontalScrollView(context);
        horizontalScrollView.setHorizontalScrollBarEnabled(false);
        horizontalScrollView.setFocusable(false);
        horizontalScrollView.setFocusableInTouchMode(false);
        horizontalScrollView.setBackgroundDrawable(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            horizontalScrollView.setDefaultFocusHighlightEnabled(false);
        }

        horizontalContainer = new LinearLayout(context);
        horizontalContainer.setOrientation(LinearLayout.HORIZONTAL);
        horizontalContainer.setBackgroundDrawable(null);
        horizontalScrollView.addView(horizontalContainer);
        addView(horizontalScrollView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        verticalButtons = new ArrayList<>();
        verticalActions = new ArrayList<>();
        horizontalButtons = new ArrayList<>();
        horizontalActions = new ArrayList<>();

        activeRegion = Region.VERTICAL;
        verticalIndex = 0;
        horizontalIndex = 0;

        commandsManager = new CustomCommandsManager(context);

        setVisibility(GONE);
    }

    public void buildMenu() {
        activeRegion = Region.VERTICAL;
        verticalIndex = 0;
        horizontalIndex = 0;
        clearFocus();

        verticalContainer.removeAllViews();
        horizontalContainer.removeAllViews();
        verticalButtons.clear();
        verticalActions.clear();
        horizontalButtons.clear();
        horizontalActions.clear();

        float density = getContext().getResources().getDisplayMetrics().density;
        int spacing = (int) (BUTTON_SPACING_DP * density);

        // Vertical column: top → bottom
        addVerticalButton(R.drawable.ic_overlay_guide,
            getContext().getString(R.string.overlay_menu_guide), ACTION_SEND_GUIDE, spacing);
        addVerticalButton(R.drawable.ic_overlay_mouse,
                getContext().getString(R.string.overlay_menu_mouse_emulation), ACTION_TOGGLE_MOUSE_EMULATION, spacing);
        addVerticalButton(R.drawable.ic_overlay_keyboard_toggle,
            getContext().getString(R.string.overlay_menu_keyboard), ACTION_SHOW_KEYBOARD, spacing);
        addVerticalButton(R.drawable.ic_overlay_perf,
                getContext().getString(R.string.overlay_menu_toggle_stats), ACTION_TOGGLE_STATS, spacing);
        addVerticalButton(R.drawable.ic_overlay_power,
            getContext().getString(R.string.overlay_menu_quit_session), ACTION_QUIT, spacing);
        addVerticalButton(R.drawable.ic_overlay_monitor,
            getContext().getString(R.string.overlay_menu_disconnect), ACTION_DISCONNECT, 0);

        // Add spacing between vertical column and horizontal row
        ((LinearLayout.LayoutParams) horizontalScrollView.getLayoutParams()).leftMargin = spacing;

        if (showingShortcuts) {
            // Horizontal row: Artemide's built-in key shortcuts then Back
            for (int i = 0; i < SHORTCUTS.length; i++) {
                addHorizontalButton(0, getContext().getString(SHORTCUTS[i].labelResId),
                    ACTION_SHORTCUT_BASE + i, spacing);
            }
            addHorizontalButton(0,
                getContext().getString(R.string.overlay_menu_back), ACTION_BACK, 0);
        } else {
            // Horizontal row: Artemide actions, custom commands, then Close
            addHorizontalButton(R.drawable.ic_overlay_key_press,
                getContext().getString(R.string.overlay_menu_shortcuts), ACTION_SHORTCUTS, spacing);
            addHorizontalButton(R.drawable.ic_overlay_mouse,
                getContext().getString(R.string.overlay_menu_mouse_mode), ACTION_MOUSE_MODE, spacing);
            addHorizontalButton(R.drawable.ic_overlay_maximize,
                getContext().getString(R.string.overlay_menu_pan_zoom), ACTION_PAN_ZOOM, spacing);
            addHorizontalButton(R.drawable.ic_overlay_settings,
                getContext().getString(R.string.overlay_menu_touch_sensitivity), ACTION_TOUCH_SENSITIVITY, spacing);
            addHorizontalButton(R.drawable.ic_overlay_save,
                getContext().getString(R.string.overlay_menu_upload_clipboard), ACTION_UPLOAD_CLIPBOARD, spacing);
            addHorizontalButton(R.drawable.ic_overlay_folder,
                getContext().getString(R.string.overlay_menu_fetch_clipboard), ACTION_FETCH_CLIPBOARD, spacing);
            addHorizontalButton(R.drawable.ic_overlay_desktop,
                getContext().getString(R.string.overlay_menu_server_commands), ACTION_SERVER_COMMANDS, spacing);
            addHorizontalButton(R.drawable.ic_overlay_task_manager,
                getContext().getString(R.string.overlay_menu_task_manager), ACTION_TASK_MANAGER, spacing);
            addHorizontalButton(R.drawable.ic_overlay_restart,
                getContext().getString(R.string.overlay_menu_rotate_screen), ACTION_ROTATE_SCREEN, spacing);
            addHorizontalButton(R.drawable.ic_overlay_gamepad,
                getContext().getString(R.string.overlay_menu_onscreen_controller), ACTION_ONSCREEN_CONTROLLER, spacing);
            addHorizontalButton(R.drawable.ic_overlay_keyboard,
                getContext().getString(R.string.overlay_menu_full_keyboard), ACTION_FULL_KEYBOARD, spacing);
            addHorizontalButton(R.drawable.ic_overlay_window_menu,
                getContext().getString(R.string.overlay_menu_floating_button), ACTION_FLOATING_BUTTON, spacing);

            List<CustomCommand> customCommands = commandsManager.getCommands();
            for (int i = 0; i < customCommands.size(); i++) {
                CustomCommand command = customCommands.get(i);
                addHorizontalButton(command.getIconResId(), command.getName(),
                    ACTION_CUSTOM_BASE + i, spacing);
            }
            addHorizontalButton(0,
                getContext().getString(R.string.overlay_menu_close), ACTION_CLOSE, 0);
        }

        verticalContainer.invalidate();
        verticalContainer.requestLayout();
        horizontalContainer.invalidate();
        horizontalContainer.requestLayout();
    }

    private void addVerticalButton(int iconResId, String label, int action, int bottomMarginPx) {
        OverlayMenuButton button = OverlayMenuButton.create(getContext(), iconResId, label);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = bottomMarginPx;
        verticalContainer.addView(button, params);

        verticalButtons.add(button);
        verticalActions.add(action);

        final int index = verticalButtons.size() - 1;
        button.setOnClickListener(v -> selectAndActivate(Region.VERTICAL, index));
        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                activeRegion = Region.VERTICAL;
                verticalIndex = index;
                button.setSelected(true);
            } else {
                button.setSelected(false);
            }
        });
    }

    private void addHorizontalButton(int iconResId, String label, int action, int rightMarginPx) {
        OverlayMenuButton button = OverlayMenuButton.create(getContext(), iconResId, label);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.rightMargin = rightMarginPx;
        horizontalContainer.addView(button, params);

        horizontalButtons.add(button);
        horizontalActions.add(action);

        final int index = horizontalButtons.size() - 1;
        button.setOnClickListener(v -> selectAndActivate(Region.HORIZONTAL, index));
        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                activeRegion = Region.HORIZONTAL;
                horizontalIndex = index;
                button.setSelected(true);
            } else {
                button.setSelected(false);
            }
        });
    }

    public void setMenuActionListener(MenuActionListener listener) {
        this.actionListener = listener;
    }

    public void setFlipFaceButtons(boolean flip) {
        this.flipFaceButtons = flip;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (!isFullGamepadEvent(event) && event.getRepeatCount() == 0) {
                closeOrGoBack();
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (flipFaceButtons) {
                keyCode = handleFlipFaceButtons(keyCode);
            }

            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    navigateLeft();
                    return true;

                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    navigateRight();
                    return true;

                case KeyEvent.KEYCODE_DPAD_UP:
                    navigateUp();
                    return true;

                case KeyEvent.KEYCODE_DPAD_DOWN:
                    navigateDown();
                    return true;

                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    activateSelected();
                    return true;

                case KeyEvent.KEYCODE_BUTTON_B:
                    closeOrGoBack();
                    return true;

                case KeyEvent.KEYCODE_BUTTON_X:
                    if (event.getRepeatCount() == 0) {
                        activateMouseEmulation();
                    }
                    return true;

                case KeyEvent.KEYCODE_BUTTON_Y:
                    if (event.getRepeatCount() == 0) {
                        activateKeyboard();
                    }
                    return true;

                case KeyEvent.KEYCODE_BUTTON_START:
                case KeyEvent.KEYCODE_MENU:
                    if (event.getRepeatCount() == 0) {
                        activateGuideButton();
                    }
                    return true;

                case KeyEvent.KEYCODE_BUTTON_R1:
                    if (event.getRepeatCount() == 0) {
                        activateToggleStats();
                    }
                    return true;
            }
        }

        if (isGamepadEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!isFullGamepadEvent(event) && event.getRepeatCount() == 0) {
                closeOrGoBack();
            }
            return true;
        }

        if (isGamepadEvent(event)) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isGamepadMotionEvent(event)) {
            float x = event.getAxisValue(MotionEvent.AXIS_X);
            float y = event.getAxisValue(MotionEvent.AXIS_Y);
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);

            float combinedX = Math.abs(x) > Math.abs(hatX) ? x : hatX;
            float combinedY = Math.abs(y) > Math.abs(hatY) ? y : hatY;

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastAnalogNavTime >= ANALOG_NAV_THROTTLE_MS) {
                // Dominant axis wins to prevent cross-region jumps on diagonal inputs
                if (Math.abs(combinedX) >= Math.abs(combinedY)) {
                    if (combinedX < -ANALOG_STICK_THRESHOLD) {
                        navigateLeft();
                        lastAnalogNavTime = currentTime;
                    } else if (combinedX > ANALOG_STICK_THRESHOLD) {
                        navigateRight();
                        lastAnalogNavTime = currentTime;
                    }
                } else {
                    if (combinedY < -ANALOG_STICK_THRESHOLD) {
                        navigateUp();
                        lastAnalogNavTime = currentTime;
                    } else if (combinedY > ANALOG_STICK_THRESHOLD) {
                        navigateDown();
                        lastAnalogNavTime = currentTime;
                    }
                }
            }

            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private boolean isGamepadEvent(KeyEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
               (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private boolean isFullGamepadEvent(KeyEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private int handleFlipFaceButtons(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return KeyEvent.KEYCODE_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_B: return KeyEvent.KEYCODE_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_X: return KeyEvent.KEYCODE_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_Y: return KeyEvent.KEYCODE_BUTTON_X;
            default: return keyCode;
        }
    }

    private boolean isGamepadMotionEvent(MotionEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private void navigateUp() {
        if (activeRegion == Region.VERTICAL) {
            if (!verticalButtons.isEmpty()) {
                setVerticalIndex((verticalIndex - 1 + verticalButtons.size()) % verticalButtons.size());
            }
        } else {
            // From horizontal → Up: jump to button above Disconnect (second-to-last in vertical)
            clearHorizontalSelection();
            activeRegion = Region.VERTICAL;
            int target = verticalButtons.size() >= 2 ? verticalButtons.size() - 2 : 0;
            setVerticalIndex(target);
        }
    }

    private void navigateDown() {
        if (activeRegion == Region.VERTICAL) {
            if (!verticalButtons.isEmpty()) {
                setVerticalIndex((verticalIndex + 1) % verticalButtons.size());
            }
        } else {
            // From horizontal → Down: jump to topmost vertical button
            clearHorizontalSelection();
            activeRegion = Region.VERTICAL;
            setVerticalIndex(0);
        }
    }

    private void navigateLeft() {
        if (activeRegion == Region.HORIZONTAL) {
            if (horizontalIndex > 0) {
                setHorizontalIndex(horizontalIndex - 1);
            } else {
                // At leftmost horizontal button — cross to Disconnect (last vertical button)
                clearHorizontalSelection();
                activeRegion = Region.VERTICAL;
                setVerticalIndex(verticalButtons.size() - 1);
            }
        } else {
            // In vertical region — wrap around to rightmost horizontal button
            if (!horizontalButtons.isEmpty()) {
                clearVerticalSelection();
                activeRegion = Region.HORIZONTAL;
                setHorizontalIndex(horizontalButtons.size() - 1);
            }
        }
    }

    private void navigateRight() {
        if (activeRegion == Region.VERTICAL) {
            if (!horizontalButtons.isEmpty()) {
                clearVerticalSelection();
                activeRegion = Region.HORIZONTAL;
                setHorizontalIndex(0);
            }
        } else {
            if (horizontalIndex < horizontalButtons.size() - 1) {
                setHorizontalIndex(horizontalIndex + 1);
            } else {
                // At rightmost horizontal button — cross to Disconnect (last vertical button)
                clearHorizontalSelection();
                activeRegion = Region.VERTICAL;
                setVerticalIndex(verticalButtons.size() - 1);
            }
        }
    }

    private void setVerticalIndex(int index) {
        if (index < 0 || index >= verticalButtons.size()) return;

        for (OverlayMenuButton b : verticalButtons) {
            b.setSelected(false);
        }

        verticalIndex = index;
        verticalButtons.get(index).setSelected(true);
        verticalButtons.get(index).requestFocus();
    }

    private void setHorizontalIndex(int index) {
        if (index < 0 || index >= horizontalButtons.size()) return;

        for (OverlayMenuButton b : horizontalButtons) {
            b.setSelected(false);
        }

        horizontalIndex = index;
        horizontalButtons.get(index).setSelected(true);
        horizontalButtons.get(index).requestFocus();
    }

    private void clearVerticalSelection() {
        for (OverlayMenuButton b : verticalButtons) {
            b.setSelected(false);
        }
    }

    private void clearHorizontalSelection() {
        for (OverlayMenuButton b : horizontalButtons) {
            b.setSelected(false);
        }
    }

    private void selectAndActivate(Region region, int index) {
        activeRegion = region;
        if (region == Region.VERTICAL) {
            setVerticalIndex(index);
        } else {
            setHorizontalIndex(index);
        }
        activateSelected();
    }

    private void activateSelected() {
        int action;
        if (activeRegion == Region.VERTICAL) {
            if (verticalIndex < 0 || verticalIndex >= verticalActions.size()) return;
            action = verticalActions.get(verticalIndex);
        } else {
            if (horizontalIndex < 0 || horizontalIndex >= horizontalActions.size()) return;
            action = horizontalActions.get(horizontalIndex);
        }

        boolean shouldCloseMenu = false;

        if (actionListener != null) {
            if (action == ACTION_DISCONNECT) {
                actionListener.onDisconnect();
                shouldCloseMenu = true;
            } else if (action == ACTION_QUIT) {
                actionListener.onQuitSession();
                shouldCloseMenu = true;
            } else if (action == ACTION_TOGGLE_STATS) {
                actionListener.onToggleStats();
                // Keep menu open
            } else if (action == ACTION_TOGGLE_MOUSE_EMULATION) {
                actionListener.onToggleMouseEmulation();
                shouldCloseMenu = true;
            } else if (action == ACTION_SHOW_KEYBOARD) {
                activateKeyboard();
                return;
            } else if (action == ACTION_SEND_GUIDE) {
                actionListener.onSendGuideButton();
                shouldCloseMenu = true;
            } else if (action == ACTION_CLOSE) {
                closeMenu();
                return;
            } else if (action == ACTION_SHORTCUTS) {
                switchPage(true);
                return;
            } else if (action == ACTION_BACK) {
                switchPage(false);
                return;
            } else if (action == ACTION_TASK_MANAGER) {
                actionListener.onSendKeys(TASK_MANAGER_KEYS);
                shouldCloseMenu = true;
            } else if (action == ACTION_ROTATE_SCREEN) {
                actionListener.onRotateScreen();
                shouldCloseMenu = true;
            } else if (action == ACTION_ONSCREEN_CONTROLLER) {
                actionListener.onToggleOnscreenController();
                shouldCloseMenu = true;
            } else if (action == ACTION_FLOATING_BUTTON) {
                actionListener.onToggleFloatingButton();
                // Keep menu open so the change is visible
            } else if (action == ACTION_PAN_ZOOM) {
                actionListener.onTogglePanZoom();
                shouldCloseMenu = true;
            } else if (action == ACTION_MOUSE_MODE) {
                // The picker dialog takes over; close the menu behind it
                closeMenu();
                actionListener.onSelectMouseMode();
                return;
            } else if (action == ACTION_TOUCH_SENSITIVITY) {
                actionListener.onToggleTouchSensitivity();
                // Keep menu open
            } else if (action == ACTION_UPLOAD_CLIPBOARD) {
                actionListener.onUploadClipboard();
                shouldCloseMenu = true;
            } else if (action == ACTION_FETCH_CLIPBOARD) {
                actionListener.onFetchClipboard();
                shouldCloseMenu = true;
            } else if (action == ACTION_FULL_KEYBOARD) {
                actionListener.onToggleFullKeyboard();
                shouldCloseMenu = true;
            } else if (action == ACTION_SERVER_COMMANDS) {
                closeMenu();
                actionListener.onShowServerCommands();
                return;
            } else if (action >= ACTION_SHORTCUT_BASE) {
                int shortcutIndex = action - ACTION_SHORTCUT_BASE;
                if (shortcutIndex >= 0 && shortcutIndex < SHORTCUTS.length) {
                    actionListener.onSendKeys(SHORTCUTS[shortcutIndex].keys);
                }
                shouldCloseMenu = true;
            } else if (action >= ACTION_CUSTOM_BASE) {
                int commandIndex = action - ACTION_CUSTOM_BASE;
                List<CustomCommand> commands = commandsManager.getCommands();
                if (commandIndex >= 0 && commandIndex < commands.size()) {
                    actionListener.onCustomCommand(commands.get(commandIndex));
                }
                // Keep menu open
            }
        }

        if (shouldCloseMenu) {
            closeMenu();
        }
    }

    private void closeOrGoBack() {
        if (showingShortcuts) {
            switchPage(false);
        } else {
            closeMenu();
        }
    }

    public void closeMenu() {
        hide(() -> {
            if (actionListener != null) {
                actionListener.onMenuClosed();
            }
        });
    }

    private void activateMouseEmulation() {
        if (actionListener != null) {
            actionListener.onToggleMouseEmulation();
        }
        closeMenu();
    }

    private void activateKeyboard() {
        // Hide first, then post the keyboard request so the window manager has a frame
        // to process the visibility change and return focus to the game view before the
        // IME is requested. Without this, newer Android silently drops showSoftInput()
        // because the overlay view still holds focus at the moment of the call.
        setVisibility(GONE);
        post(() -> {
            if (actionListener != null) {
                actionListener.onShowKeyboard();
            }
        });
    }

    private void activateGuideButton() {
        if (actionListener != null) {
            actionListener.onSendGuideButton();
        }
        closeMenu();
    }

    private void activateQuitSession() {
        if (actionListener != null) {
            actionListener.onQuitSession();
        }
        closeMenu();
    }

    private void activateToggleStats() {
        if (actionListener != null) {
            actionListener.onToggleStats();
        }
        closeMenu();
    }

    /**
     * Swap the horizontal row between the main actions and the Shortcuts page,
     * keeping the menu open and the selection on the first button of the new row.
     */
    private void switchPage(boolean shortcuts) {
        showingShortcuts = shortcuts;
        buildMenu();
        post(() -> {
            if (!horizontalButtons.isEmpty()) {
                activeRegion = Region.HORIZONTAL;
                setHorizontalIndex(0);
                horizontalScrollView.scrollTo(0, 0);
            }
        });
    }

    public void show() {
        showingShortcuts = false;
        buildMenu();
        setVisibility(VISIBLE);

        invalidate();
        requestLayout();

        requestFocus();

        post(() -> {
            // Equalize all vertical button widths to the widest one
            int maxWidth = 0;
            for (OverlayMenuButton b : verticalButtons) {
                maxWidth = Math.max(maxWidth, b.getWidth());
            }
            if (maxWidth > 0) {
                for (OverlayMenuButton b : verticalButtons) {
                    b.setMinimumWidth(maxWidth);
                }
                verticalContainer.requestLayout();
            }

            if (!verticalButtons.isEmpty()) {
                setVerticalIndex(verticalButtons.size() - 1);
            }
        });
    }

    public void hide(Runnable onComplete) {
        setVisibility(GONE);
        if (onComplete != null) {
            onComplete.run();
        }
    }
}
