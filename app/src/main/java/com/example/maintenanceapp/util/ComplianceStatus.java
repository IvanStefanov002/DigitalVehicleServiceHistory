/*
 * ComplianceStatus.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp.util;

import androidx.annotation.Nullable;

import com.example.maintenanceapp.R;
import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.model.VignetteInfo;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public enum ComplianceStatus {
    OK(R.color.status_ok, R.color.status_ok_text, R.string.cmp_st_valid),
    DUE(R.color.status_due, R.color.status_due_text, R.string.cmp_st_expiring),
    OVERDUE(R.color.status_overdue, R.color.status_overdue_text, R.string.cmp_st_expired);

    public final int colorRes;
    public final int textColorRes;
    public final int labelRes;

    ComplianceStatus(int colorRes, int textColorRes, int labelRes) {
        this.colorRes = colorRes;
        this.textColorRes = textColorRes;
        this.labelRes = labelRes;
    }

    /** How long before expiry each document starts reading as "due" */
    public static final int VIGNETTE_DUE_DAYS = 7;
    public static final int INSPECTION_DUE_DAYS = 30;
    public static final int INSURANCE_DUE_DAYS = 30;
    public static final int INSTALLMENT_DUE_DAYS = 14;
    public static ComplianceStatus of(String isoDate, int dueWithinDays) {
        Integer days = daysUntil(isoDate);
        if (days == null) {
            return null;
        }
        if (days < 0) {
            return OVERDUE;
        }
        if (days <= dueWithinDays) {
            return DUE;
        }
        return OK;
    }

    public static Integer daysUntil(String isoDate) {
        Date expiry = parse(isoDate);
        if (expiry == null) {
            return null;
        }
        long diffMs = expiry.getTime() - todayMidnight().getTimeInMillis();
        return (int) Math.round(diffMs / (double) (24 * 60 * 60 * 1000));
    }

    public static Integer daysBetween(@Nullable String fromIso, @Nullable String toIso) {
        Date from = parse(fromIso);
        Date to = parse(toIso);
        if (from == null || to == null) {
            return null;
        }
        long diffMs = to.getTime() - from.getTime();
        return (int) Math.round(diffMs / (double) (24 * 60 * 60 * 1000));
    }

    public static String plusOneYear(@Nullable String isoDate) {
        Date base = parse(isoDate);
        Calendar c = Calendar.getInstance();
        Calendar today = todayMidnight();
        if (base == null || base.getTime() < today.getTimeInMillis()) {
            c.setTimeInMillis(today.getTimeInMillis());
        } else {
            c.setTime(base);
        }
        c.add(Calendar.YEAR, 1);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    /** Today with the time zeroed, in the device's zone. */
    private static Calendar todayMidnight() {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        return today;
    }

    public static String format(String isoDate) {
        Date d = parse(isoDate);
        return d == null ? null : new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(d);
    }

    public static ComplianceStatus worst(ComplianceStatus... statuses) {
        ComplianceStatus worst = null;
        if (statuses == null) {
            return null;
        }
        for (ComplianceStatus s : statuses) {
            if (s != null && (worst == null || s.ordinal() > worst.ordinal())) {
                worst = s;
            }
        }
        return worst;
    }

    @Nullable
    public static ComplianceStatus declared(@Nullable Vehicle v) {
        if (v == null) {
            return null;
        }
        return worst(
                of(v.inspectionValidTo, INSPECTION_DUE_DAYS),
                of(v.insuranceValidTo, INSURANCE_DUE_DAYS),
                of(v.insuranceNextInstallment, INSTALLMENT_DUE_DAYS));
    }

    @Nullable
    public static ComplianceStatus ofVignette(@Nullable VignetteInfo info) {
        if (info == null) {
            return null;
        }
        return info.isValid() ? of(info.validTo, VIGNETTE_DUE_DAYS) : OVERDUE;
    }

    private static Date parse(String isoDate) {
        if (isoDate == null || isoDate.trim().isEmpty()) {
            return null;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setLenient(false);
        try {
            return fmt.parse(isoDate.trim());
        } catch (ParseException e) {
            return null;
        }
    }
}
