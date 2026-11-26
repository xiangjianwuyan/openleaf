import org.openleaf.reader.core.OnlineChapter;
import java.util.*;

/** 在线书逐章缓存契约回归。 */
public final class OnlineChapterTest {
    private static int passed;

    public static void main(String[] args) {
        skeleton();
        prefetch();
        cacheKey();
        System.out.println("PASS: " + passed + " online chapter checks");
    }

    private static void skeleton() {
        List<OnlineChapter.Entry> entries = Arrays.asList(
                new OnlineChapter.Entry(0, "第一章 开端", "https://x.com/1"),
                new OnlineChapter.Entry(1, "第二章 发展", "https://x.com/2"));
        String text = OnlineChapter.skeletonText(entries);
        check(text.contains("第一章 开端"), "骨架含第一章标题");
        check(text.contains("第二章 发展"), "骨架含第二章标题");
        check(text.contains(OnlineChapter.PLACEHOLDER), "骨架含占位符");
        check(text.split(OnlineChapter.PLACEHOLDER).length == 3, "两章各一个占位");
        passed++;
    }

    private static void prefetch() {
        List<OnlineChapter.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) entries.add(new OnlineChapter.Entry(i, "第" + i + "章", "u" + i));
        eq(Arrays.asList(3, 4, 5), OnlineChapter.prefetchOrders(entries, 2, 3));
        eq(Arrays.asList(9), OnlineChapter.prefetchOrders(entries, 8, 5)); // 有界到书末
        eq(Collections.emptyList(), OnlineChapter.prefetchOrders(entries, 9, 3));
        passed++;
    }

    private static void cacheKey() {
        OnlineChapter.Entry a = new OnlineChapter.Entry(0, "标题", "https://X.com/BOOK/1?p=2");
        OnlineChapter.Entry b = new OnlineChapter.Entry(9, "不同标题", "https://x.com/book/1");
        // 同一规范化 URL 应得到同一缓存键（去 query、小写 host）
        eq(a.cacheKey("src"), b.cacheKey("src"));
        passed++;
    }

    private static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
