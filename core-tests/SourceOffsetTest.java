import org.openleaf.reader.core.LazyChapterBook;
import org.openleaf.reader.core.SourceOffset;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Q2 作品级偏移：与 ReaderActivity.anchorSourceOffset/resolveAnchor 金标逐字同口径。 */
public final class SourceOffsetTest {
    private static int passed;

    private static void test(String name, Check body) throws Exception {
        body.run(); passed++; System.out.println("PASS " + name);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private interface Check { void run() throws Exception; }

    /** 复制 ReaderActivity.cumulativeSourceOffset 的累加算法作金标。 */
    private static long goldenCumulative(LazyChapterBook book, int chapter) {
        if (book == null || chapter < 0 || chapter >= book.size()) return -1;
        long sum = 0;
        for (int i = 0; i < chapter; i++) sum += Math.max(0, book.meta(i).charCount);
        return sum;
    }

    /** 复制 ReaderActivity.anchorSourceOffset 作金标。 */
    private static long goldenAnchor(LazyChapterBook book, int chapter, int effective) {
        long base = goldenCumulative(book, chapter);
        return base < 0 ? -1 : base + Math.max(0, effective);
    }

    /** 复制 ReaderActivity.resolveAnchor 的源码偏移分支（无 atEnd）作金标。 */
    private static int[] goldenResolve(LazyChapterBook book, long source) {
        long cum = 0;
        for (int i = 0; i < book.size(); i++) {
            long count = Math.max(0, book.meta(i).charCount);
            if (source >= cum && source < cum + count) return new int[]{i, (int) (source - cum)};
            cum += count;
        }
        return new int[]{-1, -1};
    }

    private static LazyChapterBook threeChapters(Path dir) throws Exception {
        File file = dir.resolve("three-chapters.txt").toFile();
        String text = "第一章 开端\n正文一\n\n第二章 发展\n正文二\n\n第三章 结局\n正文三\n";
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        LazyChapterBook book = LazyChapterBook.open(file, "txt", dir.toFile());
        if (book.size() != 3) throw new AssertionError("fixture should have 3 chapters, got " + book.size());
        return book;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("ol-source-offset-");
        LazyChapterBook book = null;
        try {
            book = threeChapters(dir);
            final LazyChapterBook b = book;
            test("cumulative monotonic and chapter boundary joined", () -> {
                long c0 = SourceOffset.cumulative(b, 0);
                long c1 = SourceOffset.cumulative(b, 1);
                long c2 = SourceOffset.cumulative(b, 2);
                require(c0 == 0, "chapter 0 starts at 0");
                require(c1 > c0 && c2 > c1, "cumulative must be strictly monotonic");
                require(c1 == b.meta(0).charCount, "chapter 1 start equals chapter 0 charCount");
                require(c2 == c1 + b.meta(1).charCount, "chapter 2 start equals sum of prior charCounts");
                long end0 = SourceOffset.of(b, 0, b.meta(0).charCount);
                long end1 = SourceOffset.of(b, 1, b.meta(1).charCount);
                require(end0 == c1 && end1 == c2, "sourceEnd(ch) == sourceStart(ch+1)");
            });
            test("of matches ReaderActivity anchorSourceOffset gold", () -> {
                for (int chapter = 0; chapter < b.size(); chapter++) {
                    int count = b.meta(chapter).charCount;
                    int[] offsets = {0, 1, count / 2, count - 1, count, -5};
                    for (int in : offsets) {
                        long actual = SourceOffset.of(b, chapter, in);
                        long gold = goldenAnchor(b, chapter, in);
                        require(actual == gold, "of(" + chapter + "," + in + ")=" + actual + " gold=" + gold);
                    }
                }
            });
            test("resolve matches ReaderActivity resolveAnchor source branch", () -> {
                long total = goldenCumulative(b, b.size() - 1) + b.meta(b.size() - 1).charCount;
                for (long source = 0; source < total; source++) {
                    int[] actual = SourceOffset.resolve(b, source);
                    int[] gold = goldenResolve(b, source);
                    require(actual[0] == gold[0] && actual[1] == gold[1],
                            "resolve(" + source + ") mismatch at source " + source);
                }
                // 章边界取后一章 0 偏移
                int[] boundary = SourceOffset.resolve(b, goldenCumulative(b, 2));
                require(boundary[0] == 2 && boundary[1] == 0, "boundary must land on next chapter");
            });
            test("out-of-range and null are safe", () -> {
                require(SourceOffset.cumulative(null, 0) == -1, "null book cumulative");
                require(SourceOffset.cumulative(b, -1) == -1, "negative chapter cumulative");
                require(SourceOffset.cumulative(b, b.size()) == -1, "past-end chapter cumulative");
                require(SourceOffset.of(null, 0, 0) == -1, "null book of");
                require(SourceOffset.of(b, -1, 0) == -1, "negative chapter of");
                require(SourceOffset.of(b, 0, -7) == 0, "negative inChapter clamps to 0");
                int[] neg = SourceOffset.resolve(b, -1);
                require(neg[0] == -1 && neg[1] == -1, "negative source offset");
                long total = goldenCumulative(b, b.size() - 1) + b.meta(b.size() - 1).charCount;
                int[] beyond = SourceOffset.resolve(b, total);
                require(beyond[0] == -1 && beyond[1] == -1, "past-end source offset");
                int[] nil = SourceOffset.resolve(null, 0);
                require(nil[0] == -1 && nil[1] == -1, "null book resolve");
            });
            System.out.println("PASS: " + passed + " source offset checks");
        } finally {
            if (book != null) book.close();
            try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(p);
            }
        }
    }
}
