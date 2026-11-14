package com.udunus.dexplus.util;

import android.content.Context;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.util.List;

/**
 * Distinguishes mice that expose an alphabetic HID keyboard descriptor from genuine physical
 * keyboards so Samsung IME does not hide the on-screen keyboard when only a mouse is connected.
 */
public final class InputDeviceHelper {
    private InputDeviceHelper() {}

    /** Returns true when an external alphabetic InputDevice is actually a mouse with pointer axes. */
    public static boolean isMouseWithKeyboardAttribute(InputDevice dev) {
        if (dev == null || dev.isVirtual()) {
            return false;
        }
        if (dev.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            return false;
        }
        int sources = dev.getSources();
        boolean hasMouse = (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
        boolean hasTouchpad = (sources & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD;
        if (!hasMouse || hasTouchpad) {
            return false;
        }
        return dev.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_MOUSE) != null
                && dev.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_MOUSE) != null;
    }

    /**
     * Returns true when every non-virtual alphabetic keyboard currently attached is a mouse
     * (either via mouse pointer capability or Samsung's mouse VID:PID list), meaning no genuine
     * physical keyboard is connected.
     */
    public static boolean hasOnlyMouseAlphabeticKeyboards(
            Context context, List<?> samsungMouseAllowList) {
        if (context == null) {
            return false;
        }
        Object svc = context.getSystemService(Context.INPUT_SERVICE);
        if (!(svc instanceof InputManager)) {
            return false;
        }
        InputManager im = (InputManager) svc;
        int[] ids = im.getInputDeviceIds();
        if (ids == null || ids.length == 0) {
            return false;
        }
        boolean sawMouseKeyboard = false;
        for (int id : ids) {
            InputDevice dev = im.getInputDevice(id);
            if (dev == null || dev.isVirtual()) {
                continue;
            }
            if (dev.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                continue;
            }
            if (isMouseWithKeyboardAttribute(dev)) {
                sawMouseKeyboard = true;
                continue;
            }
            if (samsungMouseAllowList != null) {
                String vidPid = dev.getVendorId() + ":" + dev.getProductId();
                if (samsungMouseAllowList.contains(vidPid)) {
                    sawMouseKeyboard = true;
                    continue;
                }
            }
            return false;
        }
        return sawMouseKeyboard;
    }
}
