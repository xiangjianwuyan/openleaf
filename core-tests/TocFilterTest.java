import org.openleaf.reader.core.ChapterOutline;
import org.openleaf.reader.core.ChapterOutline.Node;
import org.openleaf.reader.core.TocFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 目录标题过滤回归：子串/大小写、空查询、折叠投影求交、当前章定位。 */
public final class TocFilterTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        test("子串过滤与大小写不敏感", TocFilterTest::containsAndCase);
        test("空查询与 null 查询返回全部原索引", TocFilterTest::emptyQuery);
        test("无匹配返回空数组", TocFilterTest::noMatch);
        test("与 ChapterOutline.visible() 组合不越界", TocFilterTest::withVisible);
        test("当前章定位与不在结果中返回 -1", TocFilterTest::currentPosition);
        test("null 标题与脏 visible 下标不抛异常", TocFilterTest::defensive);
        System.out.println("TocFilterTest: " + passed + " checks passed");
    }

    private static void containsAndCase() {
        List<String> titles = Arrays.asList("第一章 初遇", "第二章 Chapter Two", "第三章 重逢", "番外篇");
        equalArray(new int[]{1}, TocFilter.filter(titles, "chapter"));
        equalArray(new int[]{1}, TocFilter.filter(titles, "CHAPTER"));
        equalArray(new int[]{0, 1, 2}, TocFilter.filter(titles, "章"));
        equalArray(new int[]{0, 1, 2, 3}, TocFilter.filter(titles, ""));
    }

    private static void emptyQuery() {
        List<String> titles = Arrays.asList("A", "B", "C");
        equalArray(new int[]{0, 1, 2}, TocFilter.filter(titles, null));
        equalArray(new int[]{0, 1, 2}, TocFilter.filter(titles, ""));
        equalArray(new int[0], TocFilter.filter(null, "x"));
    }

    private static void noMatch() {
        equalArray(new int[0], TocFilter.filter(Arrays.asList("甲", "乙"), "不存在"));
    }

    private static void withVisible() {
        List<String> titles = Arrays.asList(
                "第一卷 风起", "第一章 初遇", "第二章 相逢",
                "第二卷 云涌", "第三章 别离", "第四章 归途",
                "第三卷 潮落", "第五章 终局");
        List<Node> nodes = ChapterOutline.build(titles);
        int[] visible = ChapterOutline.visible(nodes, null);
        equal(8, visible.length);
        int[] all = TocFilter.filterVisible(nodes, visible, "");
        equalArray(visible, all);
        int[] matched = TocFilter.filterVisible(nodes, visible, "第三章");
        equalArray(new int[]{4}, matched);
        // 折叠首卷后，子项 1/2 不可见；过滤结果不得包含被折叠项
        boolean[] collapsed = new boolean[8];
        collapsed[0] = true;
        int[] folded = ChapterOutline.visible(nodes, collapsed);
        equal(6, folded.length);
        int[] foldedMatch = TocFilter.filterVisible(nodes, folded, "章");
        equalArray(new int[]{4, 5, 7}, foldedMatch);
    }

    private static void currentPosition() {
        int[] filtered = {2, 5, 9};
        equal(0, TocFilter.positionOf(filtered, 2));
        equal(2, TocFilter.positionOf(filtered, 9));
        equal(-1, TocFilter.positionOf(filtered, 3));
        equal(-1, TocFilter.positionOf(new int[0], 0));
    }

    private static void defensive() {
        List<Node> nodes = ChapterOutline.build(Arrays.asList("第一章 甲", "第二章 乙"));
        int[] dirty = {-1, 0, 2, 99};
        int[] result = TocFilter.filterVisible(nodes, dirty, "章");
        equalArray(new int[]{0}, result);
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add("命中");
        equalArray(new int[]{1}, TocFilter.filter(withNull, "命中"));
    }

    private static void equalArray(int[] expected, int[] actual) {
        if (expected.length != actual.length)
            throw new AssertionError("长度不符: expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual));
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i])
                throw new AssertionError("索引 " + i + " 不符: expected=" + Arrays.toString(expected)
                        + " actual=" + Arrays.toString(actual));
        }
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + "; got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void test(String name, Runnable action) {
        try { action.run(); passed++; System.out.println("OK " + name); }
        catch (Throwable failure) { throw new AssertionError("FAILED: " + name, failure); }
    }
}
