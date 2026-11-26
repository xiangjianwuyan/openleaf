package org.openleaf.reader.core;

/**
 * 扫描版 PDF 的白边检测（纯 JVM，无 Android 依赖）。
 *
 * <p>按采样网格找「有内容的矩形」，再留出页边距。只在能省下足够面积时才认为值得裁——
 * 否则对排版本来就满页的 PDF 做裁剪只会改变缩放比例、让用户以为页面被切掉了。
 */
public final class PageCrop {

    private PageCrop() { }

    /** 取像素的回调；返回 ARGB 整数。 */
    public interface PixelReader { int at(int x, int y); }

    /** 内容矩形；right / bottom 为开区间。 */
    public static final class Bounds {
        public final int left, top, right, bottom;

        public Bounds(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }

        public int width() { return Math.max(0, right - left); }
        public int height() { return Math.max(0, bottom - top); }

        public Bounds expanded(int margin, int fullWidth, int fullHeight) {
            int l = Math.max(0, left - margin);
            int t = Math.max(0, top - margin);
            int r = Math.min(fullWidth, right + margin);
            int b = Math.min(fullHeight, bottom + margin);
            return new Bounds(l, t, r, b);
        }
    }

    /** 近似白：三个通道都很亮且不透明。 */
    private static boolean isBlank(int argb) {
        if (((argb >>> 24) & 0xFF) < 128) return false; // 透明像素按有内容处理
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r >= 245 && g >= 245 && b >= 245;
    }

    /**
     * 找出非白内容的外接矩形；整页空白时返回整页。
     * <p>用采样网格而不是逐像素：一张 2000×3000 的页面只取约 240×360 个点，
     * 在后台渲染线程上开销可忽略。
     */
    public static Bounds contentBounds(int width, int height, PixelReader pixels) {
        if (width <= 0 || height <= 0 || pixels == null) return new Bounds(0, 0, Math.max(0, width), Math.max(0, height));
        int step = Math.max(1, Math.min(width, height) / 240);
        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                if (!isBlank(pixels.at(x, y))) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x + 1 > maxX) maxX = x + 1;
                    if (y + 1 > maxY) maxY = y + 1;
                }
            }
        }
        if (maxX < 0 || maxY < 0) return new Bounds(0, 0, width, height);
        return new Bounds(minX, minY, maxX, maxY);
    }

    /** 省下的面积占比是否达到阈值。 */
    public static boolean worthCropping(Bounds bounds, int fullWidth, int fullHeight, float minGain) {
        if (bounds == null || fullWidth <= 0 || fullHeight <= 0) return false;
        int w = bounds.width(), h = bounds.height();
        if (w <= 0 || h <= 0) return false;
        long kept = (long) w * h;
        long all = (long) fullWidth * fullHeight;
        if (kept >= all) return false;
        return (all - kept) / (double) all >= minGain;
    }
}
