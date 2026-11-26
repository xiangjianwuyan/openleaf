import org.openleaf.reader.core.ComicSlots;
import java.util.*;

/** 双页槽位换算回归。 */
public final class ComicSlotsTest {
    private static int passed;

    public static void main(String[] args) {
        buildSlots();
        rtlAndBlank();
        counts();
        pageMapping();
        toggleAndCache();
        coverModeScreens();
        alignFirstIndexCases();
        System.out.println("PASS: " + passed + " comic slots checks");
    }

    private static void buildSlots() {
        List<ComicSlots.Entry> entries = Arrays.asList(
                ComicSlots.Entry.image("p1"), ComicSlots.Entry.image("p2"),
                ComicSlots.Entry.image("p3"), ComicSlots.Entry.transition("ch2"),
                ComicSlots.Entry.image("p4"), ComicSlots.Entry.image("p5"));
        List<ComicSlots.Slot> slots = ComicSlots.buildSlots(entries, false, false);
        eq(4, slots.size());
        eq("[p1 | p2]", slots.get(0).toString());
        eq("[p3 | null]", slots.get(1).toString());
        eq("[ch2 | null]", slots.get(2).toString());
        eq("[p4 | p5]", slots.get(3).toString());
        passed++;
    }

    private static void rtlAndBlank() {
        List<ComicSlots.Entry> entries = Arrays.asList(
                ComicSlots.Entry.image("p1"), ComicSlots.Entry.image("p2"),
                ComicSlots.Entry.transition("ch2"),
                ComicSlots.Entry.image("p3"), ComicSlots.Entry.image("p4"));
        // RTL + 段首空白
        List<ComicSlots.Slot> slots = ComicSlots.buildSlots(entries, true, true);
        check(slots.get(0).isBlank(), "第一段开头空白");
        eq("[p2 | p1]", slots.get(1).toString()); // RTL 首图放视觉右
        eq("[ch2 | null]", slots.get(2).toString());
        check(slots.get(3).isBlank(), "第二段开头也插入空白");
        eq("[p4 | p3]", slots.get(4).toString());
        passed++;
    }

    private static void counts() {
        List<ComicSlots.Entry> entries = Arrays.asList(
                ComicSlots.Entry.image("a"), ComicSlots.Entry.image("b"), ComicSlots.Entry.image("c"),
                ComicSlots.Entry.transition("t"),
                ComicSlots.Entry.image("d"), ComicSlots.Entry.image("e"));
        eq(4, ComicSlots.doubleSlotCount(entries, false)); // 图片3张→2槽 + 过渡1槽 + 图片2张→1槽
        eq(6, ComicSlots.doubleSlotCount(entries, true));  // 两个图片段各+1空白槽
        eq(6, ComicSlots.singleSlotCount(entries));
        passed++;
    }

    private static void pageMapping() {
        List<ComicSlots.Entry> entries = Arrays.asList(
                ComicSlots.Entry.image("a"), ComicSlots.Entry.image("b"),
                ComicSlots.Entry.image("c"), ComicSlots.Entry.image("d"));
        List<ComicSlots.Slot> slots = ComicSlots.buildSlots(entries, false, false);
        eq(0, ComicSlots.storedPageOfSlot(entries, slots, 0)); // 双页槽 a|b -> page0
        eq(2, ComicSlots.storedPageOfSlot(entries, slots, 1)); // c|d -> page2
        eq(0, ComicSlots.slotOfStoredPage(slots, 0));
        eq(1, ComicSlots.slotOfStoredPage(slots, 3));
        passed++;
    }

    private static void toggleAndCache() {
        eq(2, ComicSlots.mapSlotForToggle(5, true));
        eq(6, ComicSlots.mapSlotForToggle(3, false));
        int key1 = ComicSlots.stableSizeCacheIndex(1, 0);
        int key2 = ComicSlots.stableSizeCacheIndex(2, 0);
        check(key1 != key2, "跨章同页索引缓存键不同");
        check(key1 >= 100000, "缓存键保留基址");
        passed++;
    }

    private static void coverModeScreens() {
        ComicSlots.CoverMode single = ComicSlots.CoverMode.ALL_SINGLE;
        ComicSlots.CoverMode noCover = ComicSlots.CoverMode.DOUBLE_NO_COVER;
        ComicSlots.CoverMode allDouble = ComicSlots.CoverMode.ALL_DOUBLE;

        // total=5（奇数）：本屏索引
        eqArr(new int[]{0}, ComicSlots.screenImages(0, 5, single));
        eqArr(new int[]{4}, ComicSlots.screenImages(4, 5, single));
        eqArr(new int[]{0, 1}, ComicSlots.screenImages(0, 5, allDouble));
        eqArr(new int[]{2, 3}, ComicSlots.screenImages(2, 5, allDouble));
        eqArr(new int[]{4}, ComicSlots.screenImages(4, 5, allDouble));
        eqArr(new int[]{0}, ComicSlots.screenImages(0, 5, noCover)); // 封面独占
        eqArr(new int[]{1, 2}, ComicSlots.screenImages(1, 5, noCover));
        eqArr(new int[]{3, 4}, ComicSlots.screenImages(3, 5, noCover));
        // total=6（偶数）封面/末页边界
        eqArr(new int[]{0}, ComicSlots.screenImages(0, 6, noCover));
        eqArr(new int[]{5}, ComicSlots.screenImages(5, 6, noCover));
        eqArr(new int[]{4, 5}, ComicSlots.screenImages(4, 6, allDouble));
        // 越界
        eqArr(new int[0], ComicSlots.screenImages(-1, 5, allDouble));
        eqArr(new int[0], ComicSlots.screenImages(5, 5, allDouble));
        eqArr(new int[0], ComicSlots.screenImages(0, 0, single));
        eqArr(new int[0], ComicSlots.screenImages(0, -1, allDouble));

        // advanceFirst 前进：ALL_SINGLE=1，ALL_DOUBLE=2
        eq(1, ComicSlots.advanceFirst(0, 5, single, true));
        eq(2, ComicSlots.advanceFirst(0, 5, allDouble, true));
        eq(4, ComicSlots.advanceFirst(2, 5, allDouble, true));
        eq(-1, ComicSlots.advanceFirst(4, 5, allDouble, true));
        // DOUBLE_NO_COVER 前进 0→1(step=1)→3(step=2)→越界
        eq(1, ComicSlots.advanceFirst(0, 5, noCover, true));
        eq(3, ComicSlots.advanceFirst(1, 5, noCover, true));
        eq(-1, ComicSlots.advanceFirst(3, 5, noCover, true));
        // advanceFirst 后退：对称
        eq(3, ComicSlots.advanceFirst(4, 5, single, false));
        eq(2, ComicSlots.advanceFirst(4, 5, allDouble, false));
        eq(0, ComicSlots.advanceFirst(2, 5, allDouble, false));
        eq(-1, ComicSlots.advanceFirst(0, 5, allDouble, false));
        // DOUBLE_NO_COVER 后退 3→1(step=2)→0(step=1)→越界
        eq(1, ComicSlots.advanceFirst(3, 5, noCover, false));
        eq(0, ComicSlots.advanceFirst(1, 5, noCover, false));
        eq(-1, ComicSlots.advanceFirst(0, 5, noCover, false));
        // 偶数 total=6 封面边界前进/后退
        eq(5, ComicSlots.advanceFirst(3, 6, noCover, true));
        eq(-1, ComicSlots.advanceFirst(5, 6, noCover, true));
        eq(3, ComicSlots.advanceFirst(5, 6, noCover, false));
        // 越界
        eq(-1, ComicSlots.advanceFirst(-1, 5, single, true));
        eq(-1, ComicSlots.advanceFirst(0, 0, allDouble, true));
        passed++;
    }

    private static void alignFirstIndexCases() {
        ComicSlots.CoverMode single = ComicSlots.CoverMode.ALL_SINGLE;
        ComicSlots.CoverMode noCover = ComicSlots.CoverMode.DOUBLE_NO_COVER;
        ComicSlots.CoverMode allDouble = ComicSlots.CoverMode.ALL_DOUBLE;

        // ALL_SINGLE：范围内原样，越界 clamp 到 [0,total-1]
        eq(0, ComicSlots.alignFirstIndex(0, single, 5));
        eq(3, ComicSlots.alignFirstIndex(3, single, 5));
        eq(4, ComicSlots.alignFirstIndex(4, single, 5));
        eq(0, ComicSlots.alignFirstIndex(-1, single, 5));
        eq(4, ComicSlots.alignFirstIndex(9, single, 5));
        // ALL_DOUBLE：偶数合法，奇数回退到前一偶数
        eq(0, ComicSlots.alignFirstIndex(0, allDouble, 5));
        eq(2, ComicSlots.alignFirstIndex(2, allDouble, 5));
        eq(4, ComicSlots.alignFirstIndex(4, allDouble, 5));
        eq(2, ComicSlots.alignFirstIndex(3, allDouble, 5));
        eq(0, ComicSlots.alignFirstIndex(1, allDouble, 5));
        // 偶数 total=6：越界 clamp 到 5（奇数）再回退到 4
        eq(4, ComicSlots.alignFirstIndex(10, allDouble, 6));
        // DOUBLE_NO_COVER：0 与奇数合法，大于 0 的偶数回退到前一奇数
        eq(0, ComicSlots.alignFirstIndex(0, noCover, 5));
        eq(1, ComicSlots.alignFirstIndex(1, noCover, 5));
        eq(3, ComicSlots.alignFirstIndex(3, noCover, 5));
        eq(1, ComicSlots.alignFirstIndex(2, noCover, 5));
        eq(3, ComicSlots.alignFirstIndex(4, noCover, 5));
        eq(3, ComicSlots.alignFirstIndex(10, noCover, 5)); // 越界 clamp 到 4 → 3
        eq(5, ComicSlots.alignFirstIndex(10, noCover, 6)); // 越界 clamp 到 5（奇数合法）
        // 边界：total<=0 安全返回 0；total=1 时唯一合法起点 0
        eq(0, ComicSlots.alignFirstIndex(0, allDouble, 0));
        eq(0, ComicSlots.alignFirstIndex(5, noCover, -1));
        eq(0, ComicSlots.alignFirstIndex(0, allDouble, 1));
        eq(0, ComicSlots.alignFirstIndex(0, noCover, 1));
        eq(0, ComicSlots.alignFirstIndex(1, single, 1));
        passed++;
    }

    private static void eqArr(int[] expected, int[] actual) {
        if (!Arrays.equals(expected, actual))
            throw new AssertionError("expected " + Arrays.toString(expected) + " but got " + Arrays.toString(actual));
    }
    private static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
