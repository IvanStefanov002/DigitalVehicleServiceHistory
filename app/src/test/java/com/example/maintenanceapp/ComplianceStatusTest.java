/*
 * ComplianceStatusTest.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.example.maintenanceapp.model.Vehicle;
import com.example.maintenanceapp.util.ComplianceStatus;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Unit tests for the document date arithmetic. */
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
        assertNull(ComplianceStatus.daysUntil("2026-13-45"));
    }

    // ---- of --------------------------------------------------------------

    @Test
    public void of_classifiesAgainstTheGivenWindow() {
        assertEquals(ComplianceStatus.OVERDUE, ComplianceStatus.of(iso(-1), 30));
        assertEquals(ComplianceStatus.DUE, ComplianceStatus.of(iso(0), 30));
        assertEquals(ComplianceStatus.DUE, ComplianceStatus.of(iso(30), 30));   // boundary is inclusive
        assertEquals(ComplianceStatus.OK, ComplianceStatus.of(iso(31), 30));
    }

    @Test
    public void of_windowIsPerDocument() {
        assertEquals(ComplianceStatus.OK, ComplianceStatus.of(iso(20), ComplianceStatus.VIGNETTE_DUE_DAYS));
        assertEquals(ComplianceStatus.DUE, ComplianceStatus.of(iso(20), ComplianceStatus.INSPECTION_DUE_DAYS));
    }

    @Test
    public void of_isNullWhenUnknown_neverOk() {
        assertNull(ComplianceStatus.of(null, 30));
        assertNull(ComplianceStatus.of("", 30));
    }

    @Test
    public void worst_ranksBySeverityAndIgnoresNulls() {
        assertEquals(ComplianceStatus.OVERDUE,
                ComplianceStatus.worst(ComplianceStatus.OK, ComplianceStatus.OVERDUE, null));
        assertEquals(ComplianceStatus.DUE,
                ComplianceStatus.worst(null, ComplianceStatus.DUE, ComplianceStatus.OK));
        assertNull(ComplianceStatus.worst(null, null));
        assertNull(ComplianceStatus.worst());
    }

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

    @Test
    public void plusOneYear_extendsFromTheOldExpiryWhenRenewingEarly() {
        assertEquals(isoYearsAndDays(1, 40), ComplianceStatus.plusOneYear(iso(40)));
    }

    @Test
    public void plusOneYear_extendsFromTodayWhenTheOldDateHasPassed() {
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
        int year = Calendar.getInstance().get(Calendar.YEAR);
        while (!isLeap(year) || ComplianceStatus.daysUntil(year + "-02-29") <= 0) {
            year++;
        }
        assertEquals((year + 1) + "-02-28", ComplianceStatus.plusOneYear(year + "-02-29"));
    }

    private static boolean isLeap(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    @Test
    public void format_rendersBulgarianDayFirstOrNull() {
        assertEquals("18.08.2026", ComplianceStatus.format("2026-08-18"));
        assertNull(ComplianceStatus.format(null));
        assertNull(ComplianceStatus.format("nonsense"));
    }
}
