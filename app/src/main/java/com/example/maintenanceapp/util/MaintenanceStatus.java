package com.example.maintenanceapp.util;

import com.example.maintenanceapp.R;
import com.example.maintenanceapp.model.MaintenanceItem;

import java.util.List;

/**
 * Service status for a maintenance item, derived from how much mileage is left before the next
 * change relative to the change interval. This is the single source of truth for the OK / Due soon
 * / Overdue classification used both on the vehicle detail screen and in the Автопарк list badges.
 *
 * <p>Ordinals are ordered by severity (OK &lt; DUE &lt; OVERDUE) so {@link #worst} can pick the most
 * urgent status across a vehicle's items.
 */
public enum MaintenanceStatus {
    OK(R.color.status_ok, R.string.st_ok),
    DUE(R.color.status_due, R.string.st_due),
    OVERDUE(R.color.status_overdue, R.string.st_overdue);

    public final int colorRes;
    public final int labelRes;

    MaintenanceStatus(int colorRes, int labelRes) {
        this.colorRes = colorRes;
        this.labelRes = labelRes;
    }

    /**
     * @return the status for one item, or {@code null} when there isn't enough data to classify it
     *         (no next-change mileage, or a non-positive interval).
     */
    public static MaintenanceStatus of(int lastChangeMileage, int nextChangeMileage, int currentMileage) {
        int interval = nextChangeMileage - lastChangeMileage;
        if (nextChangeMileage <= 0 || interval <= 0) {
            return null;
        }
        int remainingKm = nextChangeMileage - currentMileage;
        if (remainingKm <= 0) {
            return OVERDUE;
        }
        if (remainingKm <= Math.max(1, interval / 10)) {   // within ~10% of the interval
            return DUE;
        }
        return OK;
    }

    /**
     * @return the most urgent status across all items, or {@code null} when none can be classified
     *         (e.g. the vehicle has no maintenance records yet).
     */
    public static MaintenanceStatus worst(List<MaintenanceItem> items, int currentMileage) {
        MaintenanceStatus worst = null;
        if (items == null) {
            return null;
        }
        for (MaintenanceItem item : items) {
            MaintenanceStatus s = of(item.lastChangeMileage, item.nextChangeMileage, currentMileage);
            if (s != null && (worst == null || s.ordinal() > worst.ordinal())) {
                worst = s;
            }
        }
        return worst;
    }
}
