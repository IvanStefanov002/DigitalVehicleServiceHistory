/*
 * ServiceHistoryPdf.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import com.example.maintenanceapp.R;
import com.example.maintenanceapp.model.MaintenanceItem;
import com.example.maintenanceapp.model.Vehicle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders a vehicle's service history to a PDF */
public final class ServiceHistoryPdf {

    // A4 at 72dpi, the unit PdfDocument works in.
    private static final int PAGE_W = 595;
    private static final int PAGE_H = 842;
    private static final int MARGIN = 40;

    private static final int INK = Color.parseColor("#111827");
    private static final int MUTED = Color.parseColor("#6B7280");
    private static final int RULE = Color.parseColor("#D1D5DB");
    private static final int BAND = Color.parseColor("#F3F4F6");

    /** Column x-offsets from the left margin: date, service, mileage, next. */
    private static final int COL_DATE = 0;
    private static final int COL_NAME = 78;
    private static final int COL_MILEAGE = 330;
    private static final int COL_NEXT = 425;

    private final Context ctx;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    private PdfDocument doc;
    private PdfDocument.Page page;
    private Canvas canvas;
    private int y;
    private int pageNo;

    public ServiceHistoryPdf(Context ctx) {
        this.ctx = ctx;
        line.setColor(RULE);
        line.setStrokeWidth(0.7f);
    }

    /** Writes the document and returns the file it landed in cacheDir/exports */
    public File write(Vehicle vehicle, List<MaintenanceItem> items) throws IOException {
        doc = new PdfDocument();
        pageNo = 0;
        startPage();

        drawHeader(vehicle);
        drawSummary(items);
        drawHistory(items);
        finishPage();

        File dir = new File(ctx.getCacheDir(), "exports");
        if (!dir.exists() && !dir.mkdirs()) {
            doc.close();
            throw new IOException("could not create " + dir);
        }
        File file = new File(dir, fileName(vehicle));
        try (FileOutputStream out = new FileOutputStream(file)) {
            doc.writeTo(out);
        } finally {
            doc.close();
        }
        return file;
    }

    /** sections */
    private void drawHeader(Vehicle v) {
        text.setColor(INK);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextSize(20);
        canvas.drawText(ctx.getString(R.string.pdf_title), MARGIN, y, text);
        y += 26;

        text.setTextSize(14);
        canvas.drawText(join(v.make, v.model), MARGIN, y, text);
        y += 20;

        // Identity block — what a buyer checks the car's papers against.
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(10);
        text.setColor(MUTED);
        y = drawSpec(ctx.getString(R.string.spec_plate), orDash(v.licensePlate), y);
        y = drawSpec(ctx.getString(R.string.spec_year), v.year > 0 ? String.valueOf(v.year) : "-", y);
        y = drawSpec(ctx.getString(R.string.spec_vin), orDash(v.vin), y);
        y = drawSpec(ctx.getString(R.string.spec_mileage), km(v.mileage), y);
        y = drawSpec(ctx.getString(R.string.pdf_exported), today(), y);

        y += 8;
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line);
        y += 24;
    }

    private void drawSummary(List<MaintenanceItem> items) {
        Map<String, MaintenanceItem> latest = new LinkedHashMap<>();
        for (MaintenanceItem item : items) {
            // items arrive newest-first, so the first sighting of a type is its latest record.
            // Fall back to the name when typeId is absent (older server build).
            String key = item.typeId == null || item.typeId.isEmpty() ? item.name : item.typeId;
            if (!latest.containsKey(key)) {
                latest.put(key, item);
            }
        }
        if (latest.isEmpty()) {
            return;
        }

        drawSectionTitle(ctx.getString(R.string.pdf_section_summary));
        drawColumnHeads();
        for (MaintenanceItem item : new ArrayList<>(latest.values())) {
            drawRow(item, false);
        }
        y += 18;
    }

    private void drawHistory(List<MaintenanceItem> items) {
        drawSectionTitle(ctx.getString(R.string.pdf_section_history));
        if (items.isEmpty()) {
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(10);
            text.setColor(MUTED);
            canvas.drawText(ctx.getString(R.string.pdf_no_records), MARGIN, y, text);
            y += 16;
            return;
        }
        drawColumnHeads();
        boolean band = false;
        for (MaintenanceItem item : items) {
            drawRow(item, band);
            band = !band;
        }
    }

    /** primitives */
    private void drawSectionTitle(String title) {
        ensureSpace(40);
        text.setColor(INK);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextSize(12);
        canvas.drawText(title, MARGIN, y, text);
        y += 14;
    }

    private void drawColumnHeads() {
        ensureSpace(30);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextSize(9);
        text.setColor(MUTED);
        canvas.drawText(ctx.getString(R.string.pdf_col_date), MARGIN + COL_DATE, y, text);
        canvas.drawText(ctx.getString(R.string.pdf_col_service), MARGIN + COL_NAME, y, text);
        canvas.drawText(ctx.getString(R.string.pdf_col_mileage), MARGIN + COL_MILEAGE, y, text);
        canvas.drawText(ctx.getString(R.string.pdf_col_next), MARGIN + COL_NEXT, y, text);
        y += 5;
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line);
        y += 13;
    }

    private void drawRow(MaintenanceItem item, boolean banded) {
        List<String> noteLines = item.notes == null || item.notes.trim().isEmpty()
                ? new ArrayList<>()
                : wrap(item.notes.trim(), PAGE_W - 2 * MARGIN - COL_NAME, 8.5f);
        int rowHeight = 14 + noteLines.size() * 11;

        if (!ensureSpace(rowHeight + 4)) {
            drawColumnHeads();   // new page — repeat the heads so the table stays readable
        }

        if (banded) {
            fill.setColor(BAND);
            canvas.drawRect(MARGIN - 4, y - 9, PAGE_W - MARGIN + 4, y + rowHeight - 11, fill);
        }

        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(9.5f);
        text.setColor(INK);
        canvas.drawText(orDash(item.lastChangeDate), MARGIN + COL_DATE, y, text);
        canvas.drawText(ellipsize(item.name, COL_MILEAGE - COL_NAME - 8, 9.5f), MARGIN + COL_NAME, y, text);
        canvas.drawText(km(item.lastChangeMileage), MARGIN + COL_MILEAGE, y, text);
        canvas.drawText(nextDue(item), MARGIN + COL_NEXT, y, text);
        y += 14;

        if (!noteLines.isEmpty()) {
            text.setTextSize(8.5f);
            text.setColor(MUTED);
            for (String noteLine : noteLines) {
                canvas.drawText(noteLine, MARGIN + COL_NAME, y, text);
                y += 11;
            }
        }
    }

    private int drawSpec(String label, String value, int atY) {
        canvas.drawText(label + ":  " + value, MARGIN, atY, text);
        return atY + 14;
    }

    /** pagination */
    private void startPage() {
        pageNo++;
        page = doc.startPage(new PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create());
        canvas = page.getCanvas();
        y = MARGIN + 14;
    }

    private void finishPage() {
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(8);
        text.setColor(MUTED);
        String footer = ctx.getString(R.string.pdf_page, pageNo);
        canvas.drawText(footer, PAGE_W - MARGIN - text.measureText(footer), PAGE_H - 24, text);
        doc.finishPage(page);
    }

    private boolean ensureSpace(int needed) {
        if (y + needed <= PAGE_H - MARGIN - 20) {   // 20pt reserved for the footer
            return true;
        }
        finishPage();
        startPage();
        return false;
    }

    /** makes longer words overflow from screen */
    private List<String> wrap(String value, float maxWidth, float size) {
        Paint measure = new Paint(text);
        measure.setTextSize(size);
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : value.split("\\s+")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (measure.measureText(candidate) <= maxWidth || current.length() == 0) {
                current.setLength(0);
                current.append(candidate);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private String ellipsize(String value, float maxWidth, float size) {
        String safe = value == null ? "" : value;
        Paint measure = new Paint(text);
        measure.setTextSize(size);
        if (measure.measureText(safe) <= maxWidth) {
            return safe;
        }
        int end = safe.length();
        while (end > 1 && measure.measureText(safe.substring(0, end) + "…") > maxWidth) {
            end--;
        }
        return safe.substring(0, end) + "…";
    }

    private String fileName(Vehicle v) {
        String plate = v.licensePlate == null ? "" : v.licensePlate.replaceAll("[^A-Za-z0-9]", "");
        String stamp = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        return "service-history-" + (plate.isEmpty() ? "vehicle" : plate) + "-" + stamp + ".pdf";
    }

    private String today() {
        return new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());
    }

    private String km(int value) {
        return String.format(Locale.US, "%,d", value).replace(',', ' ') + " км";
    }

    private static String join(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        return (left + " " + right).trim();
    }

    private String nextDue(MaintenanceItem item) {
        if (item.nextChangeMileage > 0) {
            return km(item.nextChangeMileage);
        }
        if (item.nextChangeDate != null && !item.nextChangeDate.trim().isEmpty()) {
            return item.nextChangeDate.trim();
        }
        return "-";
    }

    private static String orDash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }
}
