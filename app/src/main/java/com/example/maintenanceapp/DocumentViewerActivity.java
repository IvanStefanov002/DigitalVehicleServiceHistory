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

/**
 * Full-screen viewer for the document photo attached to a maintenance record — a receipt, an invoice,
 * a service protocol.
 *
 * <p>Its own Activity rather than a dialog because the whole point is to fill the screen and be
 * zoomable: a receipt shown in a dialog's content area is the same unreadable thumbnail the user
 * tapped to get away from.
 *
 * <p>Takes only the {@code documentId} — never the bytes. Passing a bitmap through an Intent would blow
 * the ~1 MB Binder transaction limit on any real photo, and the bytes are already cached in
 * {@link MaintenanceDocuments} by id, so re-opening is instant anyway.
 */
public class DocumentViewerActivity extends AppCompatActivity {

    public static final String EXTRA_DOCUMENT_ID = "extra_document_id";
    /** Optional subtitle: which service this document belongs to. */
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

        // A cache hit means no spinner at all — the common case when the user opens the same document
        // twice, or after a rotation.
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
                // Says "couldn't load", never renders a blank frame that looks like an empty document.
                error.setVisibility(View.VISIBLE);
            }
        });
    }
}
