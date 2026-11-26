package org.openleaf.reader;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native, network-free preview. Zoom and pan are independent of chapter scroll state. */
final class EpubImagePreview {
    private EpubImagePreview() {}

    static Dialog show(Activity activity, Bitmap bitmap) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        LinearLayout body = Ui.column(activity);
        body.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout bar = Ui.row(activity);
        bar.addView(Ui.button(activity, "关闭", dialog::dismiss));
        TextView label = Ui.text(activity, "书内插图", 16, Color.WHITE);
        bar.addView(label, Ui.weight());
        ZoomView picture = new ZoomView(activity, bitmap, label);
        bar.addView(Ui.button(activity, "缩小", () -> picture.zoomBy(0.8f)));
        bar.addView(Ui.button(activity, "放大", () -> picture.zoomBy(1.25f)));
        bar.addView(Ui.button(activity, "适屏", picture::reset));
        body.addView(bar);
        body.addView(picture, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView help = Ui.text(activity, "双指缩放 · 放大后可拖动", 13, Color.WHITE);
        help.setGravity(android.view.Gravity.CENTER);
        Ui.pad(help, 8);
        body.addView(help);
        dialog.setContentView(body);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        return dialog;
    }

    private static final class ZoomView extends View {
        private final Bitmap bitmap;
        private final TextView label;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF();
        private final ScaleGestureDetector detector;
        private float fit = 1, zoom = 1, x, y, lastX, lastY;
        private int pointer = -1;

        ZoomView(Context context, Bitmap bitmap, TextView label) {
            super(context);
            this.bitmap = bitmap;
            this.label = label;
            setContentDescription("EPUB 书内插图");
            setBackgroundColor(0xFF101816);
            detector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector gesture) {
                    scale(gesture.getScaleFactor(), gesture.getFocusX(), gesture.getFocusY());
                    return true;
                }
            });
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            if (w > 0 && h > 0) { fit = Math.min((float) w / bitmap.getWidth(), (float) h / bitmap.getHeight()); reset(); }
        }

        private float width() { return bitmap.getWidth() * fit * zoom; }
        private float height() { return bitmap.getHeight() * fit * zoom; }
        private static float constrain(float offset, float size, int viewport) {
            return size <= viewport ? (viewport - size) / 2 : Math.max(viewport - size, Math.min(0, offset));
        }
        private void position() {
            x = constrain(x, width(), getWidth()); y = constrain(y, height(), getHeight());
            invalidate();
        }
        void reset() { zoom = 1; position(); updateLabel(); }
        void zoomBy(float factor) { scale(factor, getWidth() / 2f, getHeight() / 2f); }
        private void updateLabel() { label.setText(getResources().getString(R.string.epub_preview_zoom, Math.round(zoom * 100))); }
        private void scale(float factor, float focusX, float focusY) {
            if (!Float.isFinite(factor) || factor <= 0) return;
            float target = Math.max(1, Math.min(5, zoom * factor));
            float change = target / zoom;
            x = focusX + (x - focusX) * change; y = focusY + (y - focusY) * change;
            zoom = target; position(); updateLabel();
        }
        @Override protected void onDraw(Canvas canvas) {
            rect.set(x, y, x + width(), y + height()); canvas.drawBitmap(bitmap, null, rect, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            detector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: begin(event, 0); break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerCount() == 2) begin(event, event.getActionIndex() == 0 ? 1 : 0);
                    break;
                case MotionEvent.ACTION_MOVE:
                    int index = event.findPointerIndex(pointer);
                    if (index >= 0) {
                        float currentX = event.getX(index), currentY = event.getY(index);
                        if (event.getPointerCount() == 1 && !detector.isInProgress()) {
                            x += currentX - lastX; y += currentY - lastY; position();
                        }
                        lastX = currentX; lastY = currentY;
                    }
                    break;
                case MotionEvent.ACTION_UP: performClick(); // Fall through to clear pointer state.
                case MotionEvent.ACTION_CANCEL: pointer = -1; break;
                default: break;
            }
            return true;
        }
        private void begin(MotionEvent e, int i) { pointer = e.getPointerId(i); lastX = e.getX(i); lastY = e.getY(i); }
        @Override public boolean performClick() { return super.performClick(); }
    }
}
