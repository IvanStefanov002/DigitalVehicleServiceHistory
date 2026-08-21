package com.example.maintenanceapp.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.annotation.Nullable;

/**
 * An ImageView you can pinch to zoom and drag to pan, plus double-tap to toggle.
 *
 * <p>Written by hand rather than pulling in a photo-view library: the app needs this on exactly one
 * screen, and the whole behaviour is a {@link Matrix}, a {@link ScaleGestureDetector} and a bounds
 * clamp. A dependency would be more code to audit than this is to read.
 *
 * <p>It exists because a document photo is <em>unusable</em> without zoom — the point of attaching a
 * receipt is to read the line items later, and fit-to-width on a phone renders them as grey mush.
 */
public class ZoomableImageView extends AppCompatImageView {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 6f;
    /** What a double-tap zooms to. Short of MAX_SCALE so there's headroom left to pinch further. */
    private static final float DOUBLE_TAP_SCALE = 3f;

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final RectF drawn = new RectF();

    private ScaleGestureDetector scaleDetector;
    private GestureDetector tapDetector;

    /** Set once the first layout has fitted the image; guards against panning a not-yet-measured view. */
    private boolean fitted;

    public ZoomableImageView(Context context) {
        super(context);
        init();
    }

    public ZoomableImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // The matrix IS the state, so the view must not also apply a scale type of its own.
        setScaleType(ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector detector) {
                        // Clamp the *factor*, not the result, so a fast pinch can't overshoot the
                        // limits and then need snapping back.
                        float current = currentScale();
                        float factor = detector.getScaleFactor();
                        float target = Math.max(MIN_SCALE, Math.min(current * factor, MAX_SCALE));
                        matrix.postScale(target / current, target / current,
                                detector.getFocusX(), detector.getFocusY());
                        clampToBounds();
                        setImageMatrix(matrix);
                        return true;
                    }
                });

        tapDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                float current = currentScale();
                // Anything above ~1 counts as zoomed-in: a pinch rarely lands exactly on 1.0, and a
                // double-tap while slightly zoomed should reset rather than zoom further.
                if (current > MIN_SCALE * 1.05f) {
                    fitToView();
                } else {
                    matrix.postScale(DOUBLE_TAP_SCALE, DOUBLE_TAP_SCALE, e.getX(), e.getY());
                    clampToBounds();
                    setImageMatrix(matrix);
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent down, @NonNull MotionEvent move,
                                    float distanceX, float distanceY) {
                if (!fitted) {
                    return false;
                }
                matrix.postTranslate(-distanceX, -distanceY);
                clampToBounds();
                setImageMatrix(matrix);
                return true;
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Both detectors always see the event: a two-finger pinch and a one-finger drag can interleave
        // within a single gesture, and consuming for one would stall the other.
        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);
        return true;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed || !fitted) {
            fitToView();
        }
    }

    /** Re-fits on a new image, so setting a second bitmap doesn't inherit the first one's zoom. */
    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        fitted = false;
        fitToView();
    }

    /** Centres the image and scales it to fit — the "1x" state the double-tap returns to. */
    private void fitToView() {
        if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float iw = getDrawable().getIntrinsicWidth();
        float ih = getDrawable().getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            return;
        }
        float scale = Math.min(getWidth() / iw, getHeight() / ih);
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate((getWidth() - iw * scale) / 2f, (getHeight() - ih * scale) / 2f);
        setImageMatrix(matrix);
        fitted = true;
    }

    /** Scale relative to the fitted size, so MIN_SCALE == 1 means "fits the view". */
    private float currentScale() {
        matrix.getValues(values);
        float sx = values[Matrix.MSCALE_X];
        float sy = values[Matrix.MSKEW_Y];
        float absolute = (float) Math.sqrt(sx * sx + sy * sy);
        return absolute / fitScale();
    }

    private float fitScale() {
        if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) {
            return 1f;
        }
        float iw = getDrawable().getIntrinsicWidth();
        float ih = getDrawable().getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            return 1f;
        }
        return Math.min(getWidth() / iw, getHeight() / ih);
    }

    /**
     * Keeps the image from being dragged off-screen: an axis larger than the view is pinned to its
     * edges, and an axis smaller than the view is centred on it. Without the centring half, zooming
     * out leaves the image stuck against whichever corner the pinch ended on.
     */
    private void clampToBounds() {
        if (getDrawable() == null) {
            return;
        }
        drawn.set(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
        matrix.mapRect(drawn);

        float dx = 0;
        float dy = 0;
        if (drawn.width() <= getWidth()) {
            dx = (getWidth() - drawn.width()) / 2f - drawn.left;
        } else if (drawn.left > 0) {
            dx = -drawn.left;
        } else if (drawn.right < getWidth()) {
            dx = getWidth() - drawn.right;
        }

        if (drawn.height() <= getHeight()) {
            dy = (getHeight() - drawn.height()) / 2f - drawn.top;
        } else if (drawn.top > 0) {
            dy = -drawn.top;
        } else if (drawn.bottom < getHeight()) {
            dy = getHeight() - drawn.bottom;
        }

        matrix.postTranslate(dx, dy);
    }
}
