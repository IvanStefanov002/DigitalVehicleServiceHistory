package com.example.maintenanceapp.util;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.color.MaterialColors;

public final class SwipeRefresh {

    private SwipeRefresh() { }
    public static void theme(SwipeRefreshLayout layout) {
        layout.setColorSchemeColors(
                MaterialColors.getColor(layout, androidx.appcompat.R.attr.colorPrimary));
        layout.setProgressBackgroundColorSchemeColor(
                MaterialColors.getColor(layout, com.google.android.material.R.attr.colorSurfaceContainerHigh));
    }
}
