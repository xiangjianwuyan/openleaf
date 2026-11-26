package org.openleaf.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import org.openleaf.reader.core.MindMapLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地 Canvas 思维导图（0.14.0 T03）：只绘制 {@link MindMapLayout} 已算好坐标的节点，
 * 支持拖动节点、点选编辑（单击/双击均回调 onNodeSelected）、空白处拖动平移。
 * 不联网、不新增依赖、不新增 Manifest 组件。
 */
public final class MindMapView extends View {
    /** 与 §5.4 对齐；加子节点由宿主编辑弹窗触发，本 View 只负责回调。 */
    public interface Listener {
        void onNodeSelected(int index);
        void onNodeMoved(int index, int x, int y);
        void onAddChild(int parentIndex);
    }

    private static final int EDGE = 0xFF9DB8A8;
    private static final int NODE_FILL = 0xFFF2F6F3;
    private static final int NODE_BORDER = 0xFFC9D6CD;
    private static final int TEXT = 0xFF27272A;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path edgePath = new Path();
    private final RectF box = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private List<MindMapLayout.Node> nodes = new ArrayList<>();
    private Listener listener;
    private boolean editable = true;
    private int selected = -1;
    private float offsetX = 16f, offsetY = 16f;
    private float downX, downY;
    private int dragging = -1;
    private boolean moved, panning;
    private int lastTapIndex = -1;
    private long lastTapTime;
    private float clickX, clickY;
    private final Runnable singleTap = () -> {
        int index = selected;
        if (index >= 0 && listener != null) listener.onNodeSelected(index);
    };

    public MindMapView(Context context) {
        super(context);
        linePaint.setColor(EDGE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Ui.dp(context, 2));
        boxPaint.setColor(NODE_FILL);
        boxPaint.setStyle(Paint.Style.FILL);
        borderPaint.setColor(NODE_BORDER);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Ui.dp(context, 1));
        textPaint.setColor(TEXT);
        textPaint.setTextSize(Ui.dp(context, 14));
        textPaint.setTextAlign(Paint.Align.CENTER);
        markPaint.setColor(IdeasPane.ACCENT);
        markPaint.setTextSize(Ui.dp(context, 12));
        markPaint.setTextAlign(Paint.Align.LEFT);
        setClickable(true);
        // onTouchEvent 自行判定点击后调 performClick()；实际命中逻辑放在 onClick 回调里，
        // 满足 ClickableViewAccessibility 规范，且不造成回调重入（回调只更新选中态与延迟单次点击）。
        setOnClickListener(v -> handleTap(clickX, clickY));
    }

    public void setNodes(List<MindMapLayout.Node> laidOut) {
        nodes = laidOut == null ? new ArrayList<>() : laidOut;
        selected = -1;
        dragging = -1;
        offsetX = Ui.dp(getContext(), 16);
        offsetY = Ui.dp(getContext(), 16);
        invalidate();
    }

    public List<MindMapLayout.Node> nodes() { return nodes; }

    public void setEditable(boolean value) { editable = value; }

    public void setListener(Listener value) { listener = value; }

    /** 选中态由外部（保存后重绘）清除；不产生存储副作用。 */
    public void clearSelection() { selected = -1; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.translate(offsetX, offsetY);
        for (int i = 0; i < nodes.size(); i++) {
            MindMapLayout.Node node = nodes.get(i);
            int parent = node.parent;
            if (parent >= 0 && parent < nodes.size()) {
                MindMapLayout.Node p = nodes.get(parent);
                float x1 = p.x + p.w, y1 = p.y + p.h / 2f;
                float x2 = node.x, y2 = node.y + node.h / 2f;
                edgePath.reset();
                edgePath.moveTo(x1, y1);
                edgePath.quadTo((x1 + x2) / 2f, y1, x2, y2);
                canvas.drawPath(edgePath, linePaint);
            }
        }
        for (int i = 0; i < nodes.size(); i++) {
            MindMapLayout.Node node = nodes.get(i);
            box.set(node.x, node.y, node.x + node.w, node.y + node.h);
            float radius = Ui.dp(getContext(), 8);
            canvas.drawRoundRect(box, radius, radius, boxPaint);
            borderPaint.setColor(i == selected ? IdeasPane.ACCENT : NODE_BORDER);
            borderPaint.setStrokeWidth(Ui.dp(getContext(), i == selected ? 2 : 1));
            canvas.drawRoundRect(box, radius, radius, borderPaint);
            CharSequence label = TextUtils.ellipsize(node.title, textPaint,
                    Math.max(8f, node.w - Ui.dp(getContext(), 28)), TextUtils.TruncateAt.END);
            float baseline = node.y + node.h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(label.toString(), node.x + node.w / 2f, baseline, textPaint);
            if (node.collapsed) canvas.drawText("+", node.x + node.w - Ui.dp(getContext(), 16),
                    baseline, markPaint);
        }
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y;
                dragging = hitTest(x - offsetX, y - offsetY);
                moved = false;
                panning = dragging < 0;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
                if (Math.abs(dx) + Math.abs(dy) < Ui.dp(getContext(), 4)) return true;
                if (editable && dragging >= 0 && dragging < nodes.size()) {
                    MindMapLayout.Node node = nodes.get(dragging);
                    node.x += dx; node.y += dy;
                    moved = true;
                    invalidate();
                } else if (panning) {
                    offsetX += dx; offsetY += dy;
                    moved = true;
                    invalidate();
                }
                downX = x; downY = y;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wasDrag = moved && dragging >= 0;
                if (wasDrag && listener != null) {
                    MindMapLayout.Node node = nodes.get(dragging);
                    listener.onNodeMoved(dragging, node.x, node.y);
                }
                handler.removeCallbacks(singleTap);
                if (!moved && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    clickX = x;
                    clickY = y;
                    performClick();
                }
                dragging = -1;
                panning = false;
                moved = false;
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    /** 由 performClick() 触发：单击延迟回调、双击立即回调，逻辑与原 onTouchEvent 内联版逐字一致。 */
    private void handleTap(float x, float y) {
        int index = hitTest(x - offsetX, y - offsetY);
        long now = android.os.SystemClock.uptimeMillis();
        if (index >= 0 && index == lastTapIndex && now - lastTapTime < 350) {
            selected = index;
            lastTapIndex = -1;
            lastTapTime = 0;
            invalidate();
            if (listener != null) listener.onNodeSelected(index);
        } else if (index >= 0) {
            selected = index;
            lastTapIndex = index;
            lastTapTime = now;
            invalidate();
            handler.postDelayed(singleTap, 350);
        } else {
            lastTapIndex = -1;
            lastTapTime = 0;
        }
    }

    private int hitTest(float x, float y) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            MindMapLayout.Node node = nodes.get(i);
            if (x >= node.x && x <= node.x + node.w && y >= node.y && y <= node.y + node.h) return i;
        }
        return -1;
    }
}
