import org.openleaf.reader.core.ComicFlow;
import java.util.*;

/** 漫画 core 回归：ZIP 安全、自然排序、跨章全局索引、锚点恢复、导入意图。 */
public final class ComicFlowTest {
    private static int passed;

    public static void main(String[] args) {
        zipSafety();
        naturalSort();
        pageIndex();
        flatFlow();
        preloadAndAnchor();
        importIntent();
        System.out.println("PASS: " + passed + " comic flow checks");
    }

    private static void zipSafety() {
        check(ComicFlow.safeEntry("chapter1/001.jpg"), "普通路径安全");
        check(!ComicFlow.safeEntry("../secret.jpg"), "越界拒绝");
        check(!ComicFlow.safeEntry("/abs.jpg"), "绝对路径拒绝");
        check(!ComicFlow.safeEntry("C:\\windows"), "盘符反斜杠拒绝");
        check(ComicFlow.isImage("a.PNG") && ComicFlow.isImage("b.webp") && !ComicFlow.isImage("a.txt"), "图片扩展名");
        List<String> filtered = null;
        try { filtered = ComicFlow.imagePages(Arrays.asList("a.jpg", "../b.png", "c.txt", "dir/d.gif")); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
        eq(Arrays.asList("a.jpg", "dir/d.gif"), filtered); // 越界/非图片被过滤
        List<String> pages = null;
        try { pages = ComicFlow.imagePages(Arrays.asList("10.jpg", "2.jpg", "1.jpg", "note.txt")); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
        eq(Arrays.asList("1.jpg", "2.jpg", "10.jpg"), pages);
        try { ComicFlow.imagePages(Arrays.asList("note.txt", "readme.md")); throw new AssertionError("空页未拒绝"); }
        catch (java.io.IOException expected) { }
        passed++;
    }

    private static void naturalSort() {
        check(ComicFlow.naturalCompare("2.jpg", "10.jpg") < 0, "2 在 10 前");
        check(ComicFlow.naturalCompare("page1.jpg", "page2.jpg") < 0, "普通比较");
        check(ComicFlow.naturalCompare("001.jpg", "1.jpg") == 0, "前导零等价");
        check(ComicFlow.naturalCompare("a.jpg", "b.jpg") < 0, "字母序");
        passed++;
    }

    private static void pageIndex() {
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < 30001; i++) pages.add(i + ".jpg");
        try { ComicFlow.imagePages(pages); throw new AssertionError("条目超限未拒绝"); }
        catch (java.io.IOException expected) { }
        passed++;
    }

    private static void flatFlow() {
        List<ComicFlow.Segment> segments = Arrays.asList(
                new ComicFlow.Segment(1, "eid-1", Arrays.asList("1.jpg", "2.jpg")),
                new ComicFlow.Segment(2, "eid-2", Arrays.asList("1.jpg", "2.jpg", "3.jpg")));
        ComicFlow.FlatFlow flow = new ComicFlow.FlatFlow(segments);
        eq(5, flow.size());
        eq(2, flow.chapterCount());
        ComicFlow.ImageRef ref = flow.at(1);
        eq(1, ref.segment.chapter); eq(0, ref.pageIndex); eq("eid-1:0", ref.key);
        ref = flow.at(4);
        eq(2, ref.segment.chapter); eq(1, ref.pageIndex);
        eq(null, flow.at(6));
        eq(4, flow.indexOf(2, 1));
        eq(-1, flow.indexOf(3, 0));
        eq(1, flow.chapterAt(1)); eq(2, flow.chapterAt(3));
        passed++;
    }

    private static void preloadAndAnchor() {
        List<ComicFlow.Segment> segments = Arrays.asList(
                new ComicFlow.Segment(1, "e1", Arrays.asList("1.jpg", "2.jpg")),
                new ComicFlow.Segment(2, "e2", Arrays.asList("1.jpg", "2.jpg")));
        ComicFlow.FlatFlow flow = new ComicFlow.FlatFlow(segments);
        check(flow.shouldLoadAfter(2, 1), "第1章末页应预取下一章");
        check(!flow.shouldLoadAfter(4, 1), "全书末尾不应预取");
        check(!flow.shouldLoadBefore(1, 1), "第1章开头无上一章");
        check(flow.shouldLoadBefore(3, 1), "第2章开头应预取上一章");
        eq(5, flow.anchorAfterPrepend(3, 2));
        passed++;
    }

    private static void importIntent() {
        ComicFlow.ImportIntent intent = new ComicFlow.ImportIntent(
                "content://x", "/files/a.cbz", "abc123", 1000, 12345);
        check(ComicFlow.canResume(intent, 1000, "abc123"), "一致可恢复");
        check(!ComicFlow.canResume(intent, 999, "abc123"), "大小不符拒绝");
        check(!ComicFlow.canResume(intent, 1000, "wrong"), "哈希不符拒绝");
        passed++;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
