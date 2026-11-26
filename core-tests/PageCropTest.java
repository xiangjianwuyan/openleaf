import org.openleaf.reader.core.PageCrop;

/** 白边检测回归：有内容、全空白、透明像素、留边与「值不值得裁」。 */
public final class PageCropTest {
    private static int passed;

    public static void main(String[] args) {
        detectsContentRectangle();
        blankPageIsFull();
        transparentCountsAsContent();
        marginIsClamped();
        gainThreshold();
        System.out.println("PASS: " + passed + " page crop checks");
    }

    private static void detectsContentRectangle() {
        int w = 200, h = 200;
        PageCrop.Bounds found = PageCrop.contentBounds(w, h, (x, y) ->
                (x >= 40 && x < 160 && y >= 50 && y < 150) ? 0xFF223322 : 0xFFFFFFFF);
        eq(40, found.left, "左边界");
        eq(50, found.top, "上边界");
        eq(160, found.right, "右边界（开区间）");
        eq(150, found.bottom, "下边界（开区间）");
        passed++;
    }

    private static void blankPageIsFull() {
        PageCrop.Bounds found = PageCrop.contentBounds(100, 80, (x, y) -> 0xFFFFFFFF);
        eq(0, found.left, "全空白页左边");
        eq(100, found.right, "全空白页右边");
        eq(80, found.bottom, "全空白页下边");
        passed++;
    }

    private static void transparentCountsAsContent() {
        PageCrop.Bounds found = PageCrop.contentBounds(50, 50,
                (x, y) -> (x == 10 && y == 20) ? 0x00000000 : 0xFFFFFFFF);
        require(found.right > found.left && found.bottom > found.top, "透明像素不能当白底丢掉");
        passed++;
    }

    private static void marginIsClamped() {
        PageCrop.Bounds content = new PageCrop.Bounds(5, 5, 15, 15);
        PageCrop.Bounds expanded = content.expanded(100, 20, 20);
        eq(0, expanded.left, "左边距不能越界");
        eq(0, expanded.top, "上边距不能越界");
        eq(20, expanded.right, "右边距不能越界");
        eq(20, expanded.bottom, "下边距不能越界");
        passed++;
    }

    private static void gainThreshold() {
        PageCrop.Bounds small = new PageCrop.Bounds(90, 90, 110, 110);
        require(PageCrop.worthCropping(small, 200, 200, 0.08f), "大块白边值得裁");
        PageCrop.Bounds almostFull = new PageCrop.Bounds(0, 0, 199, 200);
        require(!PageCrop.worthCropping(almostFull, 200, 200, 0.08f), "只省一点点不该裁");
        require(!PageCrop.worthCropping(new PageCrop.Bounds(0, 0, 0, 0), 200, 200, 0f), "空矩形不该裁");
        passed++;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void eq(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
    }
}
