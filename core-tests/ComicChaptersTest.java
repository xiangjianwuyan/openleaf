import org.openleaf.reader.core.ComicChapters;
import org.openleaf.reader.core.ComicFlow;
import java.util.*;

/** 阶段 2-B 跨章章节推断 core 回归；纯 JVM、无 JUnit。 */
public final class ComicChaptersTest {
    private static int passed;

    public static void main(String[] args) {
        filesAsChapters();
        subdirsAsChapters();
        rootImagesFallback();
        stableIds();
        globalKeysMatchFlatFlow();
        malformedInput();
        System.out.println("ComicChaptersTest: " + passed + " checks passed");
    }

    private static void filesAsChapters() {
        List<String> files = Arrays.asList("ch02.cbz", "ch10.cbz", "ch01.cbz"); // 乱序输入
        Map<String, List<String>> images = new HashMap<>();
        images.put("ch01.cbz", Arrays.asList("1.jpg"));
        images.put("ch02.cbz", Arrays.asList("2.jpg"));
        images.put("ch10.cbz", Arrays.asList("10.jpg"));
        List<ComicFlow.Segment> segs = ComicChapters.fromFiles(files, images);
        eq(3, segs.size());
        eq("ch01_cbz", segs.get(0).eid); // 自然序，不是字典序（ch10 不能排在 ch02 前）
        eq("ch02_cbz", segs.get(1).eid);
        eq("ch10_cbz", segs.get(2).eid);
        eq(1, segs.get(0).chapter); eq(3, segs.get(2).chapter);
        passed++;
    }

    private static void subdirsAsChapters() {
        List<String> names = Arrays.asList(
                "ch02/003.png", "ch01/002.png", "ch01/001.png", "ch10/010.png");
        List<ComicFlow.Segment> segs = ComicChapters.fromEntryNames(names);
        eq(3, segs.size());
        eq("ch01", segs.get(0).eid);
        eq(2, segs.get(0).images.size());
        eq("ch01/001.png", segs.get(0).images.get(0));
        eq("ch02", segs.get(1).eid);
        eq("ch10", segs.get(2).eid);
        passed++;
    }

    private static void rootImagesFallback() {
        List<String> root = Arrays.asList("10.jpg", "2.jpg", "1.jpg");
        List<ComicFlow.Segment> segs = ComicChapters.fromEntryNames(root);
        eq(1, segs.size()); // 无子目录 → 单章，保持旧行为
        eq("1.jpg", segs.get(0).images.get(0));
        eq("10.jpg", segs.get(0).images.get(2));
        passed++;
    }

    private static void stableIds() {
        eq("abc_01", ComicChapters.stableId("ABC_01"));
        eq("abc_01", ComicChapters.stableId("ABC_01")); // 跨调用一致
        eq("chapter", ComicChapters.stableId(""));
        eq("chapter", ComicChapters.stableId(null));
        eq("a_b", ComicChapters.stableId("a/b"));
        eq("第01话", ComicChapters.stableId("第01话")); // 中文与数字保留、大小写无关
        eq("第01话", ComicChapters.stableId("第01话"));
        String longId = ComicChapters.stableId(new String(new char[200]).replace('\0', 'x'));
        eq(80, longId.length());
        passed++;
    }

    private static void globalKeysMatchFlatFlow() {
        List<ComicFlow.Segment> segs = ComicChapters.fromEntryNames(
                Arrays.asList("ch01/1.jpg", "ch01/2.jpg", "ch02/1.jpg"));
        ComicFlow.FlatFlow flow = new ComicFlow.FlatFlow(segs);
        List<String> keys = ComicChapters.globalKeys(segs);
        eq(3, keys.size());
        for (int i = 1; i <= 3; i++) eq(flow.at(i).key, keys.get(i - 1));
        passed++;
    }

    private static void malformedInput() {
        eq(0, ComicChapters.fromFiles(null, null).size());
        eq(0, ComicChapters.fromEntryNames(null).size());
        eq(0, ComicChapters.fromEntryNames(Collections.emptyList()).size());
        // 非法图片/穿越路径被过滤
        List<ComicFlow.Segment> segs = ComicChapters.fromEntryNames(
                Arrays.asList("../evil.jpg", "note.txt", "ch01/ok.jpg"));
        eq(1, segs.size());
        eq(1, segs.get(0).images.size());
        passed++;
    }

    private static void eq(Object a, Object b) {
        if (!Objects.equals(a, b)) throw new AssertionError("期望 " + a + " 实际 " + b);
    }
}
