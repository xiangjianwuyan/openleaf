package org.openleaf.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 阶段 4：漫画大图查看的缩放 ImageView（原生 View + Matrix，零新依赖）。
 *
 * <p>交互形态参考什锦铺 comic_image_viewer（事实性设计），实现为 OpenLeaf 自有 Java：
 * 双指缩放（minScale~maxScale）、双击在 1x/2x 间切换、放大后拖动平移、边界钳制不越界。
 * 只负责绘制与手势，不碰页加载/进度/章节逻辑。</p>
 */
final class ZoomImageView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private Bitmap bitmap;
    private float minScale = 1f, maxScale = 4f;
    private float startScale = 1f;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    ZoomImageView(Context context, Bitmap bitmap) {
        super(context);
        this.bitmap = bitmap;
        setContentDescription("漫画大图，双指缩放，双击放大，拖动平移");
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                startScale = getScale();
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector d) {
                float target = startScale * d.getScaleFactor();
                setScaleAt(target, d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                float target = getScale() < minScale * 1.5f ? minScale * 2f : minScale;
                setScaleAt(target, e.getX(), e.getY());
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { performClick(); return true; }
        });
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        reset();
    }

    /** 适配视图：等比缩放使整图完整可见（contain），居中。 */
    void reset() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        float sw = (float) getWidth() / bitmap.getWidth();
        float sh = (float) getHeight() / bitmap.getHeight();
        minScale = Math.min(sw, sh);
        maxScale = Math.max(minScale * 4f, 1f);
        matrix.reset();
        matrix.setScale(minScale, minScale);
        float dx = (getWidth() - bitmap.getWidth() * minScale) / 2f;
        float dy = (getHeight() - bitmap.getHeight() * minScale) / 2f;
        matrix.postTranslate(dx, dy);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        canvas.drawBitmap(bitmap, matrix, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress() && getScale() > minScale * 1.01f) {
            float dx = event.getX() - lastX, dy = event.getY() - lastY;
            matrix.postTranslate(dx, dy);
            clamp();
            invalidate();
        }
        lastX = event.getX(); lastY = event.getY();
        return true;
    }
    private float lastX, lastY;

    private float getScale() {
        matrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    private void setScaleAt(float target, float px, float py) {
        float current = getScale();
        target = Math.max(minScale, Math.min(maxScale, target));
        if (Math.abs(target - current) < 0.001f) return;
        // 焦点在图片坐标系中的位置（缩放前）
        matrix.getValues(values);
        float imgX = (px - values[Matrix.MTRANS_X]) / current;
        float imgY = (py - values[Matrix.MTRANS_Y]) / current;
        // 应用缩放后，把同一个图片点重新对齐到焦点
        matrix.postScale(target / current, target / current);
        matrix.getValues(values);
        float newTx = px - imgX * target;
        float newTy = py - imgY * target;
        matrix.postTranslate(newTx - values[Matrix.MTRANS_X], newTy - values[Matrix.MTRANS_Y]);
        clamp();
        invalidate();
    }

    /** 钳制平移：图不小于视图时居中，大于视图时不允许露出空白。 */
    private void clamp() {
        if (bitmap == null) return;
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
        float tx = values[Matrix.MTRANS_X], ty = values[Matrix.MTRANS_Y];
        if (w <= getWidth()) tx = (getWidth() - w) / 2f;
        else tx = Math.max(getWidth() - w, Math.min(0, tx));
        if (h <= getHeight()) ty = (getHeight() - h) / 2f;
        else ty = Math.max(getHeight() - h, Math.min(0, ty));
        values[Matrix.MTRANS_X] = tx;
        values[Matrix.MTRANS_Y] = ty;
        matrix.setValues(values);
        invalidate();
    }
}
