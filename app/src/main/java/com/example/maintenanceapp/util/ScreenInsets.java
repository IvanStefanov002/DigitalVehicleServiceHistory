package com.example.maintenanceapp.util;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputLayout;

/**
 * The one place window insets are applied. Every Activity calls {@link #apply(View)} on the view
 * that should hold the padding — the scrolling container, not necessarily the root (on the auth
 * screens padding the root would inset the background photo and leave bars of raw window colour
 * down the edges).
 *
 * <p><b>Why this exists: the keyboard.</b> {@code targetSdk 36} means the app is edge-to-edge and
 * owns its insets, so the old {@code android:windowSoftInputMode="adjustResize"} no longer shrinks
 * the window — the IME simply draws over the form, hiding the very field being typed into. The
 * padding here is what gives that height back. Each screen previously carried its own copy of this
 * listener that unioned {@code systemBars()} (some also {@code displayCutout()}) and never
 * {@code ime()}, which is exactly the bug.
 */
public final class ScreenInsets {

    /** Breathing room kept below the focused field so it doesn't sit flush against the keyboard. */
    private static final int FOCUS_GAP_DP = 16;

    private ScreenInsets() {
    }

    /**
     * Pads {@code target} for the system bars, the display cutout <em>and</em> the on-screen
     * keyboard, and scrolls the focused field back into view when the keyboard opens.
     *
     * <p>{@code displayCutout()} is in the union for every screen, not just the ones with a control
     * in the top-left corner: the status-bar inset can be shorter than a camera notch, and in
     * landscape the cutout is on an edge the status bar doesn't cover at all.
     */
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
     * Variant for a screen whose bottom bar is pinned to the window edge (currently only
     * {@code MainActivity}'s {@code BottomNavigationView}).
     *
     * <p>{@link #apply(View)} pads the <em>root</em> by the navigation-bar inset, which lifts the
     * whole bar off the bottom edge and leaves a band of bare window colour under it — barely
     * visible with gesture navigation (a ~16dp inset), obviously wrong with the classic
     * three-button bar (~48dp). And it is pure duplication: Material's {@code NavigationBarView}
     * already adds the bottom system-bar inset to its own padding, so its background extends behind
     * the navigation bar while its items stay above it. Hence no bottom padding here at all — do
     * not "fix" this by padding the bar by hand either, since Material recomputes that padding from
     * the value captured at construction and would overwrite it.
     *
     * <p>The keyboard is still handled on the root: the field being typed into lives in the content
     * area, not in the bar.
     */
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

    /**
     * Brings the focused input back above the keyboard.
     *
     * <p>Shrinking the container is not enough on its own: the field can already be scrolled past
     * the new bottom edge, and nothing asks the {@code ScrollView} to move. (Tapping a
     * <em>second</em> field while the keyboard is already up is handled by the framework —
     * {@code ScrollView.requestChildFocus} scrolls to it — but that path never runs for the first
     * tap, because the insets and the focus change land in the same frame.)
     */
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

    /**
     * Walks up to the enclosing {@link TextInputLayout}, so the scroll accounts for the floating
     * label, the counter and the helper/error text — asking for the {@code EditText}'s own bounds
     * would leave the error message under the keyboard, which is where the user needs to read it.
     */
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
