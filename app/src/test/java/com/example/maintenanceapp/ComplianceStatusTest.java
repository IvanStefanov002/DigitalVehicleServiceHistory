package com.example.maintenanceapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.util.ComplianceStatus;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Unit tests for the document date arithmetic.
 *
 * <p>Worth testing rather than eyeballing: these are the calculations that decide whether a user is
 * told their insurance lapsed, and their failure modes (off-by-one at a day boundary, a leap year, a
 * renewal that lands in the past) are all invisible until someone gets a wrong notification.
 *
 * <p>All expectations are built relative to <em>today</em>, in the local zone, so the suite can't rot
 * into passing only in the month it was written.
 */
public class ComplianceStatusTest {

    private static String iso(int daysFromToday) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, daysFromToday);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    private static String isoYearsAndDays(int years, int daysFromToday) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, daysFromToday);
        c.add(Calendar.YEAR, years);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    // ---- daysUntil -------------------------------------------------------

    @Test
    public void daysUntil_countsWholeLocalDays() {
        assertEquals(Integer.valueOf(0), ComplianceStatus.daysUntil(iso(0)));
        assertEquals(Integer.valueOf(1), ComplianceStatus.daysUntil(iso(1)));
        assertEquals(Integer.valueOf(-1), ComplianceStatus.daysUntil(iso(-1)));
        assertEquals(Integer.valueOf(45), ComplianceStatus.daysUntil(iso(45)));
    }

    @Test
    public void daysUntil_isNullForUnusableInput() {
        assertNull(ComplianceStatus.daysUntil(null));
        assertNull(ComplianceStatus.daysUntil(""));
        assertNull(ComplianceStatus.daysUntil("   "));
        assertNull(ComplianceStatus.daysUntil("not a date"));
        // Lenient parsing is off, so an impossible date is rejected rather than rolled over.
        assertNull(ComplianceStatus.daysUntil("2026-13-45"));
    }

    // ---- of --------------------------------------------------------------

    @Test
    public void of_classifiesAgainstTheGivenWindow() {
        assertEquals(ComplianceStatus.OVERDUE, ComplianceStatus.of(iso(-1), 30));
        // The expiry day itself is "expiring", not yet "expired".
        assertEquals(ComplianceStatus.DUE, ComplianceStatus.of(iso(0), 30));
        assertEquals(ComplianceStatus.DUE, ComplianceStatus.of(iso(30), 30));   // boundary is inclusive
        assertEquals(ComplianceStatus.OK, ComplianceStatus.of(iso(31), 30));
    }

    @Test
    public void of_windowIsPerDocument() {
        // The same date is fine for a vignette's 7-day window and due for an inspection's 30-day one.
        // This is the whole reason the windows aren't one shared constant.
        assertEquals(ComplianceStatus.OK, ComplianceStatus.of(iso(20), ComplianceStatus.VIGNETTE_DUE_DAYS));
        assertEquals(ComplianceStatus.DUE, ComplianceStatus.of(iso(20), ComplianceStatus.INSPECTION_DUE_DAYS));
    }

    @Test
    public void of_isNullWhenUnknown_neverOk() {
        // "No date" must not read as "fine" anywhere in the app.
        assertNull(ComplianceStatus.of(null, 30));
        assertNull(ComplianceStatus.of("", 30));
    }

    // ---- worst -----------------------------------------------------------

    @Test
    public void worst_ranksBySeverityAndIgnoresNulls() {
        assertEquals(ComplianceStatus.OVERDUE,
                ComplianceStatus.worst(ComplianceStatus.OK, ComplianceStatus.OVERDUE, null));
        assertEquals(ComplianceStatus.DUE,
                ComplianceStatus.worst(null, ComplianceStatus.DUE, ComplianceStatus.OK));
        assertNull(ComplianceStatus.worst(null, null));
        assertNull(ComplianceStatus.worst());
    }

    // ---- declared --------------------------------------------------------

    @Test
    public void declared_takesTheWorstOfTheThreeDates() {
        Vehicle v = new Vehicle("BMW", "320d");
        v.inspectionValidTo = iso(200);            // fine
        v.insuranceValidTo = iso(200);             // fine
        v.insuranceNextInstallment = iso(-3);      // missed: terminates cover mid-term
        assertEquals(ComplianceStatus.OVERDUE, ComplianceStatus.declared(v));
    }

    @Test
    public void declared_isNullWhenNothingIsSet() {
        assertNull(ComplianceStatus.declared(new Vehicle("BMW", "320d")));
        assertNull(ComplianceStatus.declared(null));
    }

    // ---- plusOneYear -----------------------------------------------------

    @Test
    public void plusOneYear_extendsFromTheOldExpiryWhenRenewingEarly() {
        // Renewing 40 days ahead of expiry must not forfeit those 40 days.
        assertEquals(isoYearsAndDays(1, 40), ComplianceStatus.plusOneYear(iso(40)));
    }

    @Test
    public void plusOneYear_extendsFromTodayWhenTheOldDateHasPassed() {
        // The common case: the inspection is booked after it lapsed. A year added to a long-gone
        // expiry would land in the past and the card would come straight back red.
        String renewed = ComplianceStatus.plusOneYear("2020-03-15");
        assertEquals(isoYearsAndDays(1, 0), renewed);
        assertEquals(ComplianceStatus.OK,
                ComplianceStatus.of(renewed, ComplianceStatus.INSPECTION_DUE_DAYS));
    }

    @Test
    public void plusOneYear_handlesAMissingDate() {
        assertEquals(isoYearsAndDays(1, 0), ComplianceStatus.plusOneYear(null));
        assertEquals(isoYearsAndDays(1, 0), ComplianceStatus.plusOneYear(""));
    }

    @Test
    public void plusOneYear_leapDayDoesNotThrowOrSkip() {
        // 29 Feb + 1 year has no counterpart; Calendar clamps to the 28th rather than overflowing.
        // The leap day has to be in the FUTURE to exercise the clamp at all — a past one takes the
        // extend-from-today branch instead, which is what a first version of this test got wrong.
        // Found dynamically so the test doesn't expire the next time a leap year goes by.
        int year = Calendar.getInstance().get(Calendar.YEAR);
        while (!isLeap(year) || ComplianceStatus.daysUntil(year + "-02-29") <= 0) {
            year++;
        }
        // A leap year is never followed by one, so the next 29 Feb doesn't exist and clamps to the 28th.
        assertEquals((year + 1) + "-02-28", ComplianceStatus.plusOneYear(year + "-02-29"));
    }

    private static boolean isLeap(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    // ---- format ----------------------------------------------------------

    @Test
    public void format_rendersBulgarianDayFirstOrNull() {
        assertEquals("18.08.2026", ComplianceStatus.format("2026-08-18"));
        assertNull(ComplianceStatus.format(null));
        assertNull(ComplianceStatus.format("nonsense"));
    }
}
