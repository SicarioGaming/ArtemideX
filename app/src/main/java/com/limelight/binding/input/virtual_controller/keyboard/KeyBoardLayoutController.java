/**
 * Created by Karim Mreisi.
 *
 * Starwire: Artemide's on-screen full keyboard. Keys go to a KeySender instead of Game's
 * static instance, and the long-press table belongs to each keyboard.
 */

package com.limelight.binding.input.virtual_controller.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class KeyBoardLayoutController {
    private static final Set<Integer> MODIFIER_KEY_CODES = new HashSet<>();
    private static final Set<Integer> SPECIAL_KEY_CODES = new HashSet<>();
    private static final long POPUP_DURATION_MS = 75;
    private static final long LONG_CLICK_TIMEOUT_MS = 300;

    // Receives the keyboard's key presses (Game sends them to the host)
    public interface KeySender {
        void sendKey(KeyEvent keyEvent);
    }

    private final Context context;
    private final PreferenceConfiguration prefConfig;
    private final KeySender keySender;
    private final FrameLayout frameLayout;
    private final Handler handler;
    public boolean shown = false;
    private final LinearLayout keyboardView;
    private PopupWindow keyPopup;
    private TextView keyPopupText;
    private Runnable hidePopupRunnable;

    static {
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_ALT_LEFT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_ALT_RIGHT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_CTRL_LEFT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_CTRL_RIGHT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_SHIFT_LEFT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_SHIFT_RIGHT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_META_LEFT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_META_RIGHT);

        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_TAB);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_ENTER);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_SPACE);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_DEL);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_FORWARD_DEL);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_ESCAPE);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_CAPS_LOCK);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_INSERT);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_DPAD_UP);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_DPAD_DOWN);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_DPAD_LEFT);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_DPAD_RIGHT);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_PAGE_UP);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_PAGE_DOWN);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_MOVE_HOME);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_MOVE_END);
    }

    // Long-pressing a modifier with sticky keys on locks it down until it's tapped again
    private final HashMap<Integer, Runnable> longClickRunnables = new HashMap<>();
    private final BitSet modifierKeyStates = new BitSet();

    public boolean isModifierKeyPressed(int keyCode) {
        return modifierKeyStates.get(keyCode);
    }

    private boolean isModifierKey(int keyCode) {
        return prefConfig.stickyModifierKeys && MODIFIER_KEY_CODES.contains(keyCode);
    }

    private boolean isSpecialKey(int keyCode) {
        return SPECIAL_KEY_CODES.contains(keyCode) || MODIFIER_KEY_CODES.contains(keyCode);
    }

    public KeyBoardLayoutController(FrameLayout layout, Context context, PreferenceConfiguration prefConfig,
                                    KeySender keySender) {
        this.frameLayout = layout;
        this.context = context;
        this.prefConfig = prefConfig;
        this.keySender = keySender;
        this.keyboardView = (LinearLayout) LayoutInflater.from(context).inflate(R.layout.layout_axixi_keyboard, null);
        this.handler = new Handler(Looper.getMainLooper());
        initKeyPopup();
        initKeyboard();
    }

    private void initKeyboard() {
        @SuppressLint("ClickableViewAccessibility")
        View.OnTouchListener touchListener = (View v, MotionEvent event) -> {
            int eventAction = event.getAction();
            String tag = (String) v.getTag();
            if (TextUtils.equals("hide", tag)) {
                if (eventAction == MotionEvent.ACTION_UP || eventAction == MotionEvent.ACTION_CANCEL) {
                    hide();
                }
                return true;
            }

            int keyCode = Integer.parseInt(tag);
            int keyAction;
            boolean modifierKey = isModifierKey(keyCode);
            boolean specialKey = isSpecialKey(keyCode);
            switch (eventAction) {
                case MotionEvent.ACTION_DOWN:
                    if (modifierKey && isModifierKeyPressed(keyCode)) {
                        modifierKeyStates.clear(keyCode);
                        return true;
                    }

                    // Show the character above the key, like a phone keyboard
                    if (!specialKey) {
                        showKeyPopup(v, keyCode);
                    }

                    keyAction = KeyEvent.ACTION_DOWN;
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (modifierKey && isModifierKeyPressed(keyCode)) {
                        return true;
                    }

                    handler.removeCallbacks(hidePopupRunnable);
                    handler.postDelayed(hidePopupRunnable, POPUP_DURATION_MS);

                    keyAction = KeyEvent.ACTION_UP;
                    break;
                default:
                    return false;
            }

            KeyEvent keyEvent = new KeyEvent(keyAction, keyCode);
            keyEvent.setSource(0);
            keySender.sendKey(keyEvent);

            if (modifierKey) {
                Runnable longClickRunnable = longClickRunnables.get(keyCode);
                if (longClickRunnable != null) {
                    handler.removeCallbacks(longClickRunnable);
                    if (keyAction == KeyEvent.ACTION_DOWN) {
                        handler.postDelayed(longClickRunnable, LONG_CLICK_TIMEOUT_MS);
                    }
                }
            }

            if (keyAction == KeyEvent.ACTION_DOWN) {
                if (prefConfig.keyboardVibrate) {
                    keyboardView.performHapticFeedback(
                            HapticFeedbackConstants.VIRTUAL_KEY,
                            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING |
                                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    );
                }
                v.setBackgroundResource(R.drawable.bg_ax_keyboard_button_confirm);
            } else {
                if (prefConfig.keyboardVibrate) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        keyboardView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                    } else {
                        keyboardView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                }
                v.setBackgroundResource(R.drawable.bg_ax_keyboard_button);
            }
            return true;
        };

        for (int i = 0; i < keyboardView.getChildCount(); i++) {
            LinearLayout keyboardRow = (LinearLayout) keyboardView.getChildAt(i);
            for (int j = 0; j < keyboardRow.getChildCount(); j++) {
                View child = keyboardRow.getChildAt(j);
                child.setOnTouchListener(touchListener);
                String keyTag = (String) child.getTag();
                if (keyTag.equals("hide")) {
                    continue;
                }
                int keycode = Integer.parseInt(keyTag);
                if (isModifierKey(keycode)) {
                    longClickRunnables.put(keycode, () -> {
                        modifierKeyStates.set(keycode);
                        if (prefConfig.keyboardVibrate) {
                            child.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        }
                    });
                }
            }
        }
    }

    private void showKeyPopup(View key, int keyCode) {
        // A popup needs the key to be on screen in a window
        if (key.getWindowToken() == null) {
            return;
        }

        int unicodeChar = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode).getUnicodeChar(0);
        String popupText = unicodeChar != 0 ?
                String.valueOf((char) unicodeChar) :
                KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "");
        keyPopupText.setText(popupText);

        keyPopupText.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popupWidth = keyPopupText.getMeasuredWidth();

        // Centered above the key
        int[] location = new int[2];
        key.getLocationInWindow(location);
        int x = location[0] + (key.getWidth() - popupWidth) / 2;
        int y = (int) (location[1] - key.getHeight() * 1.5);

        if (keyPopup.isShowing()) {
            keyPopup.update(x, y, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            keyPopup.setWidth(popupWidth);
            keyPopup.showAtLocation(key, Gravity.NO_GRAVITY, x, y);
        }
    }

    private void initKeyPopup() {
        keyPopupText = new TextView(context);
        keyPopupText.setBackgroundResource(R.drawable.key_popup_background);
        keyPopupText.setTextColor(Color.WHITE);
        keyPopupText.setTextSize(32);
        keyPopupText.setGravity(Gravity.CENTER);
        keyPopupText.setPadding(24, 16, 24, 16);

        keyPopup = new PopupWindow(keyPopupText,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        hidePopupRunnable = () -> keyPopup.dismiss();
    }

    public boolean isKeyboardVisible() {
        return keyboardView.getVisibility() == View.VISIBLE;
    }

    public void hide() {
        if (prefConfig.keyboardVibrate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                keyboardView.performHapticFeedback(HapticFeedbackConstants.REJECT);
            } else {
                keyboardView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }
        keyboardView.setVisibility(View.GONE);
        keyPopup.dismiss();
        shown = false;
    }

    public void show() {
        keyboardView.setVisibility(View.VISIBLE);
        shown = true;
    }

    public void toggleVisibility() {
        if (isKeyboardVisible()) {
            hide();
        } else {
            show();
        }
    }

    // Full width along the bottom, half the screen tall (Artemide's auto-fit size)
    public void refreshLayout() {
        frameLayout.removeView(keyboardView);

        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                screen.widthPixels, (int) (screen.heightPixels * 0.5));
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

        keyboardView.setAlpha(prefConfig.keyboardOpacity / 100f);
        frameLayout.addView(keyboardView, params);
    }
}
