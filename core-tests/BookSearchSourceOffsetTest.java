import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.BookSearch;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/** Hit 的作品级偏移：旧构造器兼容、两源实现填充、单调与边界相接。 */
public final class BookSearchSourceOffsetTest {
    private static int passed;

    private static void test(String name, Check body) throws Exception {
        body.run(); passed++; System.out.println("PASS " + name);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private interface Check { void run() throws Exception; }

    private static BookParser.BookContent book(String... chapters) {
        List<BookParser.Chapter> data = new ArrayList<BookParser.Chapter>();
        for (int i = 0; i < chapters.length; i++) data.add(new BookParser.Chapter("第" + (i + 1) + "章", chapters[i]));
        return new BookParser.BookContent("作品级偏移样本", data);
    }

    private static BookSearch.Result find(BookParser.BookContent b, String query) {
        return BookSearch.find(b, query, true, -1, 100, () -> false);
    }

    public static void main(String[] args) throws Exception {
        test("legacy Hit constructors keep -1 source offsets", () -> {
            Constructor<BookSearch.Hit> c5 = BookSearch.Hit.class.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, String.class);
            c5.setAccessible(true);
            BookSearch.Hit shortHit = c5.newInstance(0, 1, 2, 0, "x");
            require(shortHit.sourceStart == -1 && shortHit.sourceEnd == -1, "5-arg legacy constructor");
            require(shortHit.chapter == 0 && shortHit.start == 1 && shortHit.end == 2, "legacy fields intact");

            Constructor<BookSearch.Hit> c8 = BookSearch.Hit.class.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, String.class,
                    int.class, int[].class, int[].class);
            c8.setAccessible(true);
            BookSearch.Hit fullHit = c8.newInstance(1, 3, 5, 0, "x", BookSearch.LITERAL,
                    new int[]{3}, new int[]{5});
            require(fullHit.sourceStart == -1 && fullHit.sourceEnd == -1, "8-arg legacy constructor");
            require(fullHit.matchStarts[0] == 3 && fullHit.matchEnds[0] == 5, "legacy match arrays intact");
        });
        test("BookContent source offsets are filled and monotonic", () -> {
            BookParser.BookContent b = book("abc", "xxabcyy");
            BookSearch.Result r = find(b, "abc");
            require(r.hits.size() == 2, "wrong hit count");
            BookSearch.Hit first = r.hits.get(0), second = r.hits.get(1);
            long base1 = b.chapters.get(0).text.length();
            require(first.sourceStart == 0 && first.sourceEnd == 3, "chapter 0 source offsets");
            require(second.sourceStart == base1 + 2 && second.sourceEnd == base1 + 5,
                    "chapter 1 source offsets");
            require(second.chapter == 1 && second.start == 2 && second.end == 5, "in-chapter offsets intact");
            require(first.sourceStart < second.sourceStart, "source offsets must be monotonic");
        });
        test("sourceEnd(ch) equals sourceStart(ch+1) across chapter boundary", () -> {
            BookParser.BookContent b = book("abc", "abcxyz");
            BookSearch.Result r = find(b, "abc");
            require(r.hits.size() == 2, "wrong hit count");
            require(r.hits.get(0).sourceEnd == 3, "chapter 0 ends at 3");
            require(r.hits.get(1).sourceStart == 3, "chapter 1 starts at 3");
            require(r.hits.get(1).sourceEnd == 6, "chapter 1 ends at 6");
        });
        test("regex and near modes keep the same source-offset rule", () -> {
            BookParser.BookContent b = book("aXc aYc", "zzz aZc");
            BookSearch.Result regex = BookSearch.find(b, "a.c", true, -1, 100, () -> false,
                    BookSearch.REGEX, BookSearch.DEFAULT_NEAR_WINDOW);
            require(regex.hits.size() == 3, "regex hit count regressed");
            long[] bases = {0, b.chapters.get(0).text.length()};
            for (BookSearch.Hit h : regex.hits) {
                long base = bases[h.chapter];
                require(h.sourceStart == base + h.start && h.sourceEnd == base + h.end,
                        "regex source offsets must follow chapter base");
            }
            BookSearch.Result near = BookSearch.find(b, "a c", true, -1, 100, () -> false,
                    BookSearch.NEAR, 8);
            for (BookSearch.Hit h : near.hits) {
                long base = bases[h.chapter];
                require(h.sourceStart == base + h.start && h.sourceEnd == base + h.end,
                        "near source offsets must follow chapter base");
            }
        });
        System.out.println("PASS: " + passed + " book search source offset checks");
    }
}
