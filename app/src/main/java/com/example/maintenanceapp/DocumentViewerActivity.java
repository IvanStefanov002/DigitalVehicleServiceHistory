/*
 * DocumentViewerActivity.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.maintenanceapp.util.MaintenanceDocuments;
import com.example.maintenanceapp.util.ScreenInsets;
import com.example.maintenanceapp.view.ZoomableImageView;

/** Full-screen viewer for the document photo attached to a maintenance record — a receipt, an invoice, a service protocol. */
public class DocumentViewerActivity extends AppCompatActivity {

    public static final String EXTRA_DOCUMENT_ID = "extra_document_id";
    public static final String EXTRA_LABEL = "extra_label";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_document_viewer);

        ScreenInsets.apply(findViewById(R.id.dvRoot));

        String documentId = getIntent().getStringExtra(EXTRA_DOCUMENT_ID);
        if (documentId == null || documentId.trim().isEmpty()) {
            finish();
            return;
        }

        String label = getIntent().getStringExtra(EXTRA_LABEL);
        TextView title = findViewById(R.id.dvTitle);
        if (label != null && !label.trim().isEmpty()) {
            title.setText(label);
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        ZoomableImageView image = findViewById(R.id.dvImage);
        ProgressBar progress = findViewById(R.id.dvProgress);
        View error = findViewById(R.id.dvError);

        Bitmap hit = MaintenanceDocuments.cached(documentId);
        if (hit != null) {
            image.setImageBitmap(hit);
            return;
        }

        progress.setVisibility(View.VISIBLE);
        MaintenanceDocuments.load(this, documentId, new MaintenanceDocuments.Callbacks() {
            @Override
            public void onLoaded(Bitmap bitmap) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                progress.setVisibility(View.GONE);
                image.setImageBitmap(bitmap);
            }

            @Override
            public void onFailed() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                progress.setVisibility(View.GONE);
                error.setVisibility(View.VISIBLE);
            }
        });
    }
}
