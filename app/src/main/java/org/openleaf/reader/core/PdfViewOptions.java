package org.openleaf.reader.core;

/**
 * PDF 渲染的纯逻辑选项：分档缩放与页面反色。
 *
 * <p>缩放做成离散档位而不是连续比例：每一档都重新按更高分辨率渲染，文字是真放大而不是
 * 位图插值；同时渲染宽度始终受内存上限约束，避免放大后 OOM。
 */
public final class PdfViewOptions {

    private PdfViewOptions() { }

    /** 缩放档位；1 表示按屏宽渲染。 */
    public static final float[] ZOOM_LEVELS = {1f, 1.5f, 2f, 3f};

    /** 把任意档位下标夹到合法范围。 */
    public static int clampIndex(int index) {
        if (index < 0) return 0;
        return Math.min(index, ZOOM_LEVELS.length - 1);
    }

    /** 放大一档；已是最大档则保持不动。 */
    public static int zoomIn(int index) { return clampIndex(index + 1); }

    /** 缩小一档；已是最小档则保持不动。 */
    public static int zoomOut(int index) { return clampIndex(index - 1); }

    /**
     * 双指缩放结束后，按累积比例挑最接近的档位。
     * @param index 当前档位
     * @param scaleFactor 手势的比例（>1 放大，<1 缩小）
     */
    public static int zoomByGesture(int index, float scaleFactor) {
        if (Float.isNaN(scaleFactor) || Float.isInfinite(scaleFactor) || scaleFactor <= 0f) {
            return clampIndex(index);
        }
        float target = ZOOM_LEVELS[clampIndex(index)] * scaleFactor;
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < ZOOM_LEVELS.length; i++) {
            float distance = Math.abs(ZOOM_LEVELS[i] - target);
            if (distance < bestDistance) { bestDistance = distance; best = i; }
        }
        return best;
    }

    /** 该档位下希望渲染的位图宽度（像素），受调用方给出的硬上限约束。 */
    public static int renderWidth(int viewportWidth, int index, int maxWidth) {
        if (viewportWidth <= 0) return 1;
        double target = viewportWidth * (double) ZOOM_LEVELS[clampIndex(index)];
        return Math.max(1, Math.min(maxWidth, (int) Math.floor(target)));
    }

    /**
     * 反色用的 4×5 ColorMatrix：RGB 取反、Alpha 不变。
     * <p>返回的是纯 float 数组，方便 core-tests 在没有 Android 环境时校验。
     */
    public static float[] invertedColorMatrix() {
        return new float[]{
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
        };
    }
}
