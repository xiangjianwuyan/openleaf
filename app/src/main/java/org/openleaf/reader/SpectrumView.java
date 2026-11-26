package org.openleaf.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 阶段 2-A：实时频谱柱状图（自绘 View，无第三方库）。
 * 数据源是 {@link AudioProcessorChain} 从音频旁路采样的 0..1 频带数组；
 * 本 View 只负责绘制，不触碰播放器或音频线程。
 */
final class SpectrumView extends View {
    private final Paint low = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint high = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] levels = new float[0];

    SpectrumView(Context c) { super(c); init(); }
    SpectrumView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        low.setColor(0xFFC0603C);   // 低频：暖橙
        mid.setColor(0xFF5B8C7B);   // 中频：墨绿
        high.setColor(0xFF3E6E9E);  // 高频：冷蓝
        bg.setColor(0xFFE7E5E4);
    }

    /** UI 线程调用：换入新的频谱快照（副本）。 */
    void setLevels(float[] next) {
        levels = next == null ? new float[0] : next;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || levels.length == 0) return;
        float slot = (float) w / levels.length;
        float gap = Math.max(1f, slot * 0.18f);
        for (int i = 0; i < levels.length; i++) {
            float x = i * slot + gap / 2;
            canvas.drawRect(x, 0, x + slot - gap, h, bg);
            float level = Math.max(0f, Math.min(1f, levels[i]));
            float top = h * (1f - level);
            int third = i * 3 / levels.length; // 0=low, 1=mid, 2=high
            Paint paint = third == 0 ? low : (third == 1 ? mid : high);
            if (top < h) canvas.drawRect(x, top, x + slot - gap, h, paint);
        }
    }
}
