import org.openleaf.reader.core.ChapterOutline;
import org.openleaf.reader.core.ChapterOutline.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 章节层级推断 / 折叠投影回归；无 JUnit、无第三方依赖。 */
public final class ChapterOutlineTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        test("无层级信号 → 全 depth=0", ChapterOutlineTest::flat);
        test("卷/章两级 → 0/1 且卷数 < 章数", ChapterOutlineTest::volumeChapter);
        test("只有 1 个卷 → 全 0（不分组）", ChapterOutlineTest::singleVolume);
        test("卷数 >= 章数 → 全 0（不分组）", ChapterOutlineTest::tooManyVolumes);
        test("MD # / ## → 0/1", ChapterOutlineTest::markdown);
        test("MD 只有一种级别 → 全 0", ChapterOutlineTest::singleMarkdownLevel);
        test("折叠卷 → 所有子章不出现在 visible()", ChapterOutlineTest::collapse);
        test("末尾卷无子项 → parent=false", ChapterOutlineTest::trailingVolume);
        test("空列表 / 单元素不抛异常", ChapterOutlineTest::emptyAndSingle);
        test("1000 项 build()+visible() 在 200ms 内（防 O(n²)）", ChapterOutlineTest::linear);
        System.out.println("PASS: " + passed + " ChapterOutline checks");
    }

    private static void flat() {
        List<Node> nodes = ChapterOutline.build(Arrays.asList("起", "承", "转", "合"));
        equal(4, nodes.size());
        for (Node n : nodes) {
            equal(0, n.depth);
            check(!n.parent, "平铺目录不应有展开箭头");
        }
        equal(4, ChapterOutline.visible(nodes, new boolean[4]).length);
    }

    private static void volumeChapter() {
        List<Node> nodes = ChapterOutline.build(Arrays.asList(
                "第一卷 风起", "第一章 初遇", "第二章 相逢",
                "第二卷 云涌", "第三章 别离", "第四章 归途",
                "第三卷 潮落", "第五章 终局"));
        equal(8, nodes.size());
        equal(0, nodes.get(0).depth);
        equal(1, nodes.get(1).depth);
        equal(1, nodes.get(2).depth);
        equal(0, nodes.get(3).depth);
        equal(1, nodes.get(4).depth);
        equal(1, nodes.get(5).depth);
        equal(0, nodes.get(6).depth);
        equal(1, nodes.get(7).depth);
        check(nodes.get(0).parent, "含子章的卷应有展开箭头");
        int volumes = 0, chapters = 0;
        for (Node n : nodes) { if (n.depth == 0) volumes++; else chapters++; }
        equal(3, volumes);
        equal(5, chapters);
        check(volumes < chapters, "卷数应小于章数");
    }

    private static void singleVolume() {
        List<Node> nodes = ChapterOutline.build(Arrays.asList("第一卷 序", "第一章 一", "第二章 二"));
        equal(3, nodes.size());
        for (Node n : nodes) equal(0, n.depth);
    }

    private static void tooManyVolumes() {
        List<Node> nodes = ChapterOutline.build(Arrays.asList(
                "第一卷 序", "第二卷 中", "第三卷 尾", "第一章 一", "第二章 二"));
        for (Node n : nodes) equal(0, n.depth);
    }

    private static void markdown() {
        List<Node> nodes = ChapterOutline.build(Arrays.asList(
                "# 卷一", "## 第一章", "## 第二章", "# 卷二", "## 第三章"));
        equal(0, nodes.get(0).depth);
        equal(1, nodes.get(1).depth);
        equal(1, nodes.get(2).depth);
        equal(0, nodes.get(3).depth);
        equal(1, nodes.get(4).depth);
        check(nodes.get(0).parent, "MD 一级标题应有展开箭头");
        equal(1, ChapterOutline.MAX_DEPTH);
    }

    private static void singleMarkdownLevel() {
        List<Node> nodes = ChapterOutline.build(Arrays.asList("# 第一章", "# 第二章", "# 第三章"));
        for (Node n : nodes) equal(0, n.depth);
    }

    private static void collapse() {
        List<Node> nodes = ChapterOutline.build(Arrays.asList(
                "第一卷 风起", "第一章 初遇", "第二章 相逢",
                "第二卷 云涌", "第三章 别离", "第四章 归途",
                "第三卷 潮落", "第五章 终局"));
        boolean[] collapsed = new boolean[nodes.size()];
        collapsed[0] = true; // 折叠第一卷
        int[] vis = ChapterOutline.visible(nodes, collapsed);
        for (int v : vis) check(v != 1 && v != 2, "折叠卷的子章不应可见: " + v);
        check(contains(vis, 0), "折叠的卷节点本身仍应可见");
        check(contains(vis, 3), "下一个卷不应被连带折叠");
        check(contains(vis, 4), "下一个卷的子章仍应可见");
    }

    private static void trailingVolume() {
        List<Node> nodes = ChapterOutline.build(Arrays.asList(
                "第一卷 风起", "第一章 初遇", "第二章 相逢", "第三章 别离", "第二卷 云涌"));
        equal(0, nodes.get(0).depth);
        equal(1, nodes.get(1).depth);
        check(nodes.get(0).parent, "第一卷应有子项");
        equal(0, nodes.get(4).depth);
        check(!nodes.get(4).parent, "末尾卷无子项，不应出箭头");
    }

    private static void emptyAndSingle() {
        equal(0, ChapterOutline.build(Collections.emptyList()).size());
        equal(0, ChapterOutline.build(null).size());
        List<Node> single = ChapterOutline.build(Collections.singletonList("唯一"));
        equal(1, single.size());
        equal(0, single.get(0).depth);
        check(!single.get(0).parent, "单元素不应有箭头");
        equal(1, ChapterOutline.visible(single, new boolean[1]).length);
        equal(0, ChapterOutline.visible(Collections.emptyList(), new boolean[0]).length);
    }

    private static void linear() {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < 1000; i++) titles.add("第" + (i + 1) + "章 标题" + i);
        long start = System.nanoTime();
        List<Node> nodes = ChapterOutline.build(titles);
        int[] vis = ChapterOutline.visible(nodes, new boolean[1000]);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        equal(1000, nodes.size());
        equal(1000, vis.length);
        check(elapsedMs < 200, "build()+visible() 超过 200ms: " + elapsedMs + "ms");
        System.out.println("  (1000 项耗时 " + elapsedMs + "ms)");
    }

    private static boolean contains(int[] values, int target) {
        for (int v : values) if (v == target) return true;
        return false;
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + "; got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void test(String name, ThrowingRunnable action) throws Exception {
        try { action.run(); passed++; System.out.println("OK " + name); }
        catch (Throwable failure) { throw new AssertionError("FAILED: " + name, failure); }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
