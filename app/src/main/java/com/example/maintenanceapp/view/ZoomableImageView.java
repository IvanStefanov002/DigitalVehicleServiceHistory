/*
 * ZoomableImageView.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

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

public class ZoomableImageView extends AppCompatImageView {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 6f;
    private static final float DOUBLE_TAP_SCALE = 3f;

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final RectF drawn = new RectF();

    private ScaleGestureDetector scaleDetector;
    private GestureDetector tapDetector;
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

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        fitted = false;
        fitToView();
    }

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
