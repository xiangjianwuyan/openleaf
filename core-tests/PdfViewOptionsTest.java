import org.openleaf.reader.core.PdfViewOptions;

/** PDF 缩放档位与反色矩阵回归。 */
public final class PdfViewOptionsTest {
    private static int passed;

    public static void main(String[] args) {
        zoomLevels();
        gestureSnapping();
        renderWidthBounds();
        invertMatrix();
        System.out.println("PASS: " + passed + " pdf view option checks");
    }

    private static void zoomLevels() {
        eq(0, PdfViewOptions.clampIndex(-5), "负下标夹到 0");
        eq(3, PdfViewOptions.clampIndex(99), "越界下标夹到最大档");
        eq(1, PdfViewOptions.zoomIn(0), "放大一档");
        eq(3, PdfViewOptions.zoomIn(3), "最大档不越界");
        eq(0, PdfViewOptions.zoomOut(0), "最小档不越界");
        eq(1, PdfViewOptions.zoomOut(2), "缩小一档");
        passed++;
    }

    private static void gestureSnapping() {
        eq(2, PdfViewOptions.zoomByGesture(0, 2.1f), "双指放大约两倍应落到 2×档");
        eq(0, PdfViewOptions.zoomByGesture(2, 0.5f), "缩小回 1×档");
        eq(1, PdfViewOptions.zoomByGesture(1, 1.01f), "微小手势不该跳档");
        eq(2, PdfViewOptions.zoomByGesture(2, Float.NaN), "非法比例保持原档");
        eq(2, PdfViewOptions.zoomByGesture(2, -1f), "负比例保持原档");
        passed++;
    }

    private static void renderWidthBounds() {
        eq(100, PdfViewOptions.renderWidth(100, 0, 2048), "1× 按屏宽");
        eq(150, PdfViewOptions.renderWidth(100, 1, 2048), "1.5× 放大");
        eq(300, PdfViewOptions.renderWidth(100, 3, 2048), "3× 放大");
        eq(2048, PdfViewOptions.renderWidth(1000, 3, 2048), "不得超过硬上限");
        eq(1, PdfViewOptions.renderWidth(0, 0, 2048), "非法屏宽返回 1");
        passed++;
    }

    private static void invertMatrix() {
        float[] m = PdfViewOptions.invertedColorMatrix();
        require(m.length == 20, "ColorMatrix 必须是 20 个浮点数");
        eq(-1f, m[0], "红通道取反");
        eq(-1f, m[6], "绿通道取反");
        eq(-1f, m[12], "蓝通道取反");
        eq(1f, m[18], "Alpha 不变");
        eq(255f, m[4], "白变黑的偏移量");
        passed++;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void eq(float expected, float actual, String message) {
        if (Float.compare(expected, actual) != 0)
            throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
    }
}
