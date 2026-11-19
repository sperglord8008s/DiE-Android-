// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.content.Context;
import android.graphics.*;
import android.view.*;

import java.util.function.IntConsumer;

/** Fit-preserving grid with exact touch-to-cell mapping and nearest-neighbor raster display. */
final class ByteGridView extends View {
    private final ByteGrid.Result result;
    private final Bitmap bitmap;
    private final Paint paint = new Paint();
    private final RectF bounds = new RectF();
    private final IntConsumer selected;
    private int cell = -1;

    ByteGridView(Context context, ByteGrid.Result result, IntConsumer selected) {
        super(context);
        this.result = result;
        this.selected = selected;
        bitmap =
                Bitmap.createBitmap(
                        result.pixels,
                        result.layout.width,
                        result.layout.height,
                        Bitmap.Config.ARGB_8888);
        setContentDescription(
                result.layout.width
                        + " by "
                        + result.layout.height
                        + " "
                        + result.mode.legend
                        + ". Tap a cell to inspect its original physical bytes.");
        setFocusable(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale =
                Math.min(
                        (float) getWidth() / bitmap.getWidth(),
                        (float) getHeight() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        bounds.set(
                (getWidth() - width) / 2,
                (getHeight() - height) / 2,
                (getWidth() + width) / 2,
                (getHeight() + height) / 2);
        paint.setFilterBitmap(false);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawBitmap(bitmap, null, bounds, paint);
        if (cell >= 0) {
            int x = cell % result.layout.width, y = cell / result.layout.width;
            paint.setColor(Color.RED);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            canvas.drawRect(
                    bounds.left + x * scale,
                    bounds.top + y * scale,
                    bounds.left + (x + 1) * scale,
                    bounds.top + (y + 1) * scale,
                    paint);
        }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        if (!bounds.contains(event.getX(), event.getY())) return true;
        int x =
                Math.min(
                        result.layout.width - 1,
                        (int)
                                ((event.getX() - bounds.left)
                                        * result.layout.width
                                        / bounds.width()));
        int y =
                Math.min(
                        result.layout.height - 1,
                        (int)
                                ((event.getY() - bounds.top)
                                        * result.layout.height
                                        / bounds.height()));
        select(y * result.layout.width + x);
        performClick();
        return true;
    }

    void select(int index) {
        result.layout.range(index);
        cell = index;
        invalidate();
        selected.accept(index);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
