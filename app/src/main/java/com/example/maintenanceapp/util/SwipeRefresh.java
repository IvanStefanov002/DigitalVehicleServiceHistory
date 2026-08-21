package com.example.maintenanceapp.util;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.color.MaterialColors;

/**
 * Themes a {@link SwipeRefreshLayout} spinner from the app's color scheme.
 *
 * <p>Without this the widget uses its stock look — a white puck with a Holo-blue arrow — which is
 * both off-palette in light mode and glaringly wrong on the navy dark surface. Called from every
 * screen that has pull-to-refresh so they stay consistent.
 */
public final class SwipeRefresh {

    private SwipeRefresh() { }

    /**
     * Applies the accent color to the arrow and a raised surface tone to the puck behind it.
     *
     * <p>Note the two different R classes: {@code colorPrimary} is declared by appcompat, while the
     * {@code colorSurfaceContainer*} roles are Material-only — referencing either from the wrong
     * package doesn't compile.
     */
    public static void theme(SwipeRefreshLayout layout) {
        layout.setColorSchemeColors(
                MaterialColors.getColor(layout, androidx.appcompat.R.attr.colorPrimary));
        layout.setProgressBackgroundColorSchemeColor(
                MaterialColors.getColor(layout, com.google.android.material.R.attr.colorSurfaceContainerHigh));
    }
}
