import org.openleaf.reader.core.TagIndex;
import org.openleaf.reader.core.TagIndex.Entry;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 标签聚合回归；无 JUnit、无第三方依赖。 */
public final class TagIndexTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        test("同书同标签只算一次", TagIndexTest::sameBookDedup);
        test("Sci-Fi / sci-fi 合并为一行", TagIndexTest::mergeCase);
        test("显示出现次数最多的写法", TagIndexTest::mostFrequentSpelling);
        test("并列写法取 String.compareTo 最小者", TagIndexTest::tieBreak);
        test("NFKC 全角合并", TagIndexTest::nfkc);
        test("books desc, name asc 排序", TagIndexTest::ordering);
        test("空 map / null → 空表", TagIndexTest::emptyInput);
        test("输入顺序打乱结果不变", TagIndexTest::orderIndependent);
        System.out.println("PASS: " + passed + " TagIndex checks");
    }

    private static void sameBookDedup() {
        Map<String, List<String>> in = new LinkedHashMap<>();
        in.put("b1", Arrays.asList("Sci-Fi", "sci-fi", "SCI-FI"));
        List<Entry> out = TagIndex.summarize(in);
        equal(1, out.size());
        equal("sci-fi", out.get(0).key);
        equal(1, out.get(0).books);
    }

    private static void mergeCase() {
        Map<String, List<String>> in = new LinkedHashMap<>();
        in.put("b1", Arrays.asList("Sci-Fi"));
        in.put("b2", Arrays.asList("sci-fi"));
        in.put("b3", Arrays.asList("Sci-Fi", "武侠"));
        List<Entry> out = TagIndex.summarize(in);
        equal(2, out.size());
        equal("sci-fi", out.get(0).key);  // books=3 排前
        equal(3, out.get(0).books);
        equal("Sci-Fi", out.get(0).name); // "Sci-Fi" 出现 2 次 > "sci-fi" 1 次
    }

    private static void mostFrequentSpelling() {
        Map<String, List<String>> in = new LinkedHashMap<>();
        in.put("b1", Arrays.asList("sci-fi"));
        in.put("b2", Arrays.asList("sci-fi"));
        in.put("b3", Arrays.asList("Sci-Fi"));
        List<Entry> out = TagIndex.summarize(in);
        equal(1, out.size());
        equal("sci-fi", out.get(0).name); // 2 次 > 1 次
        equal(3, out.get(0).books);
    }

    private static void tieBreak() {
        Map<String, List<String>> in = new LinkedHashMap<>();
        in.put("b1", Arrays.asList("Sci-Fi"));
        in.put("b2", Arrays.asList("sci-fi"));
        List<Entry> out = TagIndex.summarize(in);
        equal("Sci-Fi", out.get(0).name); // 各 1 次，'S'(83) < 's'(115)
        equal(2, out.get(0).books);
    }

    private static void nfkc() {
        Map<String, List<String>> in = new LinkedHashMap<>();
        in.put("b1", Arrays.asList("Ｓｃｉ－Ｆｉ")); // 全角拉丁 + 全角连字符
        in.put("b2", Arrays.asList("Sci-Fi"));
        List<Entry> out = TagIndex.summarize(in);
        equal(1, out.size());
        equal("sci-fi", out.get(0).key);
        equal(2, out.get(0).books);
    }

    private static void ordering() {
        Map<String, List<String>> in = new LinkedHashMap<>();
        in.put("b1", Arrays.asList("gamma", "alpha", "beta"));
        List<Entry> out = TagIndex.summarize(in);
        equal(3, out.size());
        equal("alpha", out.get(0).name);
        equal("beta", out.get(1).name);
        equal("gamma", out.get(2).name);
        for (Entry e : out) equal(1, e.books);
    }

    private static void emptyInput() {
        equal(0, TagIndex.summarize(Collections.emptyMap()).size());
        equal(0, TagIndex.summarize(null).size());
    }

    private static void orderIndependent() {
        Map<String, List<String>> a = new LinkedHashMap<>();
        a.put("b1", Arrays.asList("武侠", "Sci-Fi"));
        a.put("b2", Arrays.asList("sci-fi", "历史"));
        a.put("b3", Arrays.asList("武侠", "SCI-FI"));
        Map<String, List<String>> b = new LinkedHashMap<>();
        b.put("b3", Arrays.asList("SCI-FI", "武侠"));
        b.put("b2", Arrays.asList("历史", "sci-fi"));
        b.put("b1", Arrays.asList("Sci-Fi", "武侠"));
        equal(render(TagIndex.summarize(a)), render(TagIndex.summarize(b)));
    }

    private static String render(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) sb.append(e.name).append('|').append(e.key).append('|').append(e.books).append(';');
        return sb.toString();
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
