/*
 * ScreenInsets.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp.util;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputLayout;

public final class ScreenInsets {

    private static final int FOCUS_GAP_DP = 16;

    private ScreenInsets() {}

    public static void apply(View target) {
        ViewCompat.setOnApplyWindowInsetsListener(target, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());

            // max, never bars.bottom + ime.bottom: the keyboard covers the navigation bar rather
            // than stacking above it, so adding them leaves a nav-bar-tall gap under the keyboard.
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));

            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                scrollFocusedFieldIntoView(v);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(target);
    }

    /**
     * Variant for a screen whose bottom bar is pinned to the window edge */
    public static void applyWithBottomBar(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());

            v.setPadding(bars.left, bars.top, bars.right, ime.bottom);

            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                scrollFocusedFieldIntoView(v);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /** Brings the focused input back above the keyboard. */
    private static void scrollFocusedFieldIntoView(View container) {
        // Posted: the padding set above only takes effect on the next layout pass, so measuring now
        // would aim the scroll at the pre-keyboard geometry.
        container.post(() -> {
            View focused = container.findFocus();
            if (focused == null) {
                return;
            }
            View field = fieldContainer(focused);
            float density = field.getResources().getDisplayMetrics().density;
            Rect visible = new Rect(0, 0, field.getWidth(),
                    field.getHeight() + (int) (FOCUS_GAP_DP * density));
            field.requestRectangleOnScreen(visible, false);   // false = animate
        });
    }

    private static View fieldContainer(View focused) {
        ViewParent parent = focused.getParent();
        while (parent instanceof View) {
            if (parent instanceof TextInputLayout) {
                return (View) parent;
            }
            parent = parent.getParent();
        }
        return focused;
    }
}
