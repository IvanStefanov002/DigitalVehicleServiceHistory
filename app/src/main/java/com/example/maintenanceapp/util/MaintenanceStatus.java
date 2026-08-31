/*
 * MaintenanceStatus.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp.util;

import androidx.annotation.Nullable;

import com.example.maintenanceapp.R;
import com.example.maintenanceapp.model.MaintenanceItem;

import java.util.List;

public enum MaintenanceStatus {
    OK(R.color.status_ok, R.string.st_ok),
    DUE(R.color.status_due, R.string.st_due),
    OVERDUE(R.color.status_overdue, R.string.st_overdue);

    public final int colorRes;
    public final int labelRes;

    public static final int TIME_DUE_DAYS_MAX = 30;

    MaintenanceStatus(int colorRes, int labelRes) {
        this.colorRes = colorRes;
        this.labelRes = labelRes;
    }

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

    public static MaintenanceStatus ofDate(@Nullable String lastChangeDate, @Nullable String nextChangeDate) {
        Integer days = ComplianceStatus.daysUntil(nextChangeDate);
        if (days == null) {
            return null;
        }
        if (days < 0) {
            return OVERDUE;
        }
        int window = TIME_DUE_DAYS_MAX;
        Integer interval = ComplianceStatus.daysBetween(lastChangeDate, nextChangeDate);
        if (interval != null && interval > 0) {
            window = Math.max(1, Math.min(TIME_DUE_DAYS_MAX, interval / 10));
        }
        return days <= window ? DUE : OK;
    }

    public static MaintenanceStatus of(MaintenanceItem item, int currentMileage) {
        if (item == null) {
            return null;
        }
        return worst(
                of(item.lastChangeMileage, item.nextChangeMileage, currentMileage),
                ofDate(item.lastChangeDate, item.nextChangeDate));
    }

    public static MaintenanceStatus worst(MaintenanceStatus... statuses) {
        MaintenanceStatus worst = null;
        for (MaintenanceStatus s : statuses) {
            if (s != null && (worst == null || s.ordinal() > worst.ordinal())) {
                worst = s;
            }
        }
        return worst;
    }

    public static MaintenanceStatus worst(List<MaintenanceItem> items, int currentMileage) {
        MaintenanceStatus worst = null;
        if (items == null) {
            return null;
        }
        for (MaintenanceItem item : items) {
            MaintenanceStatus s = of(item, currentMileage);
            if (s != null && (worst == null || s.ordinal() > worst.ordinal())) {
                worst = s;
            }
        }
        return worst;
    }
}
