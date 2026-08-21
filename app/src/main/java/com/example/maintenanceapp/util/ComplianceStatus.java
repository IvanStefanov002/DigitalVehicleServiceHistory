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

/**
 * Validity status of a time-limited vehicle document (vignette, periodic technical inspection,
 * third-party liability insurance), derived from how many days are left before it expires.
 *
 * <p>The date-based sibling of {@link MaintenanceStatus}, which classifies by remaining mileage.
 * Deliberately a separate enum rather than a reuse of that one: the two answer different questions
 * ("is this car serviced?" vs "is this car legal to drive?"), carry different labels, and a document
 * has no notion of an interval to measure "due soon" against — the threshold is per-document and
 * passed in by the caller.
 *
 * <p>Ordinals run OK &lt; DUE &lt; OVERDUE so {@link #worst} can pick the most urgent of several.
 */
public enum ComplianceStatus {
    OK(R.color.status_ok, R.color.status_ok_text, R.string.cmp_st_valid),
    DUE(R.color.status_due, R.color.status_due_text, R.string.cmp_st_expiring),
    OVERDUE(R.color.status_overdue, R.color.status_overdue_text, R.string.cmp_st_expired);

    /** Solid badge fill — white text sits on top of it, so it stays dark in both schemes. */
    public final int colorRes;

    /**
     * The same three states as a text/icon tint used <em>directly on the page background</em>. Not
     * interchangeable with {@link #colorRes}: a fill colour used as text on the dark scheme's navy
     * is unreadable. See the status-colour note in CLAUDE.md.
     */
    public final int textColorRes;

    public final int labelRes;

    ComplianceStatus(int colorRes, int textColorRes, int labelRes) {
        this.colorRes = colorRes;
        this.textColorRes = textColorRes;
        this.labelRes = labelRes;
    }

    // How long before expiry each document starts reading as "due". Per-document on purpose: a
    // weekend vignette measured against a 30-day window would be "expiring" for its entire life,
    // while a month's warning on an annual inspection is barely enough to book a slot.
    //
    // They live here rather than on the compliance screen because four call sites now need them --
    // that screen, the detail screen's warning glyph, the Автопарк row badges and the reminder
    // worker -- and a badge that disagreed with the notification about the same car would be worse
    // than either alone.
    public static final int VIGNETTE_DUE_DAYS = 7;
    public static final int INSPECTION_DUE_DAYS = 30;
    public static final int INSURANCE_DUE_DAYS = 30;
    public static final int INSTALLMENT_DUE_DAYS = 14;

    /**
     * Classifies an expiry date.
     *
     * @param isoDate       expiry as {@code yyyy-MM-dd}; blank or unparseable yields {@code null}
     * @param dueWithinDays how many days ahead still counts as {@link #DUE} — per-document, because
     *                      a weekend vignette and an annual insurance policy have nothing in common
     *                      here. A 30-day window would leave a weekend vignette "expiring" for its
     *                      entire life.
     * @return the status, or {@code null} when there is no usable date. {@code null} means
     *         <em>unknown</em>, never <em>fine</em> — callers must render it as "no data" rather
     *         than defaulting to {@link #OK}.
     */
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

    /**
     * Whole days from today until {@code isoDate}: 0 on the expiry day itself, negative once past.
     *
     * <p>Computed against the <em>local</em> calendar day, not UTC. That looks inconsistent with
     * {@code AddMaintenanceActivity}, which formats in UTC — but that code converts a picker result
     * (UTC midnight) into a string, whereas this asks "how many days from the user's today", and in
     * UTC+3 those disagree for the first hours of every day. Rounding rather than truncating the
     * division absorbs the 23/25-hour days around a DST switch.
     *
     * @return the day count, or {@code null} when the date is missing or unparseable.
     */
    public static Integer daysUntil(String isoDate) {
        Date expiry = parse(isoDate);
        if (expiry == null) {
            return null;
        }
        long diffMs = expiry.getTime() - todayMidnight().getTimeInMillis();
        return (int) Math.round(diffMs / (double) (24 * 60 * 60 * 1000));
    }

    /**
     * The renewal date after a document is re-issued for another year: <b>one year on from whichever
     * is later, the old expiry or today</b>.
     *
     * <p>Both halves of that rule matter. Renewing <em>early</em> should extend from the old expiry so
     * the user doesn't forfeit the days they had left. Renewing <em>late</em> — the common case, since
     * people book the inspection once it has lapsed — must extend from today, because a year added to
     * an expiry three years gone would land in the past and the card would come back still red.
     *
     * <p>Annual is right for ГТП on a car past its first three years, and for a ГО policy. It is
     * <em>not</em> right for an instalment (those are quarterly or monthly), which is why the renewal
     * action is never offered for one.
     *
     * @return the new expiry as {@code yyyy-MM-dd}; a year from today when {@code isoDate} is
     *         missing or unparseable.
     */
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

    /** Today with the time zeroed, in the device's zone. See {@link #daysUntil} on why not UTC. */
    private static Calendar todayMidnight() {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        return today;
    }

    /** @return the date as {@code dd.MM.yyyy} for display, or {@code null} if unparseable. */
    public static String format(String isoDate) {
        Date d = parse(isoDate);
        return d == null ? null : new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(d);
    }

    /** @return the most urgent of the given statuses, ignoring {@code null}s; {@code null} if all are. */
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

    /**
     * Worst status across a vehicle's <em>declared</em> documents — ГТП, the ГО policy and its next
     * instalment. Needs no network call: those three ride along on {@code GET /vehicles}.
     *
     * @return the worst of the three, or {@code null} when the user has set none of them.
     */
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

    /**
     * Classifies a vignette answer.
     *
     * <p>Keeps the three-outcome contract intact: a {@code null} info means the check could not be
     * made and maps to {@code null} (<em>unknown</em>), while {@link VignetteInfo#STATUS_NONE} is a
     * real answer from the authority and maps to {@link #OVERDUE}. Never fold the former into the
     * latter — that would badge a car red every time the network hiccuped.
     */
    @Nullable
    public static ComplianceStatus ofVignette(@Nullable VignetteInfo info) {
        if (info == null) {
            return null;
        }
        return info.isValid() ? of(info.validTo, VIGNETTE_DUE_DAYS) : OVERDUE;
    }

        /** Parses {@code yyyy-MM-dd} at local midnight. Lenient parsing is off so "2026-13-45" fails. */
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
