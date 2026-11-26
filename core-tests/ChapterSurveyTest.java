import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.ChapterSurvey;
import org.openleaf.reader.core.LazyChapterBook;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 章节前缀读取 / 章数上限 / 时间预算回归；临时写 TXT 真实读取，无 JUnit、无第三方依赖。 */
public final class ChapterSurveyTest {
    private static Path dir;
    private static int passed;
    private static int sequence;

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("openleaf-survey-tests-");
        try {
            test("常量取值", ChapterSurveyTest::constants);
            test("3 章 TXT 真实读取：titles/heads/首句/total/complete", ChapterSurveyTest::realBook);
            test("maxHeads=2 → complete=false", ChapterSurveyTest::headCap);
            test("budgetMs=0 → timedOut=true 且不抛", ChapterSurveyTest::zeroBudget);
            test("head() 越界 / 非正 maxChars → \"\"", ChapterSurveyTest::headBounds);
            test("heads 只含前缀，不含整章正文", ChapterSurveyTest::prefixOnly);
            test("head() 末尾高代理裁剪，不切开代理对", ChapterSurveyTest::surrogateHead);
            test("progress 每批回调且为部分结果", ChapterSurveyTest::progressBatches);
            test("EPUB 流式 head parity（含代理对）", ChapterSurveyTest::epubHeadParity);
            System.out.println("PASS: " + passed + " ChapterSurvey checks");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
        }
    }

    private static void constants() {
        equal(240, ChapterSurvey.DEFAULT_HEAD_CHARS);
        equal(2000, ChapterSurvey.DEFAULT_MAX_HEADS);
        equal(1500L, ChapterSurvey.DEFAULT_BUDGET_MS);
    }

    private static void realBook() throws Exception {
        LazyChapterBook book = openThreeChapters();
        try {
            ChapterSurvey s = ChapterSurvey.read(book, ChapterSurvey.DEFAULT_HEAD_CHARS,
                    ChapterSurvey.DEFAULT_MAX_HEADS, ChapterSurvey.DEFAULT_BUDGET_MS, null);
            equal(3, s.total);
            equal(3, s.titles.length);
            equal(3, s.spineIndex.length);
            equal(3, s.heads.size());
            check(s.complete, "3 章全部读完应 complete");
            check(!s.timedOut, "正常预算不应超时");
            check(s.titles[0].contains("春归"), "titles[0]: " + s.titles[0]);
            check(s.titles[1].contains("夏至"), "titles[1]: " + s.titles[1]);
            check(s.titles[2].contains("秋深"), "titles[2]: " + s.titles[2]);
            check(s.heads.get(0).text.contains("春天来了"), "heads[0] 应含首句: " + s.heads.get(0).text);
            check(s.heads.get(1).text.contains("夏日的风"), "heads[1] 应含首句");
            check(s.heads.get(2).text.contains("秋叶落在"), "heads[2] 应含首句");
            for (int i = 0; i < 3; i++) equal(-1, s.spineIndex[i]); // TXT 恒 -1
            equal(s.titles[0], s.heads.get(0).title);
        } finally {
            book.close();
        }
    }

    private static void headCap() throws Exception {
        LazyChapterBook book = openThreeChapters();
        try {
            ChapterSurvey s = ChapterSurvey.read(book, 240, 2, 1500, null);
            equal(3, s.titles.length); // 标题仍完整
            equal(2, s.heads.size());
            equal(2, s.maxHeads);
            check(!s.complete, "maxHeads=2 未覆盖全部 3 章，complete=false");
            check(!s.timedOut, "正常预算不应超时");
        } finally {
            book.close();
        }
    }

    private static void zeroBudget() throws Exception {
        LazyChapterBook book = openThreeChapters();
        try {
            ChapterSurvey s = ChapterSurvey.read(book, 240, 2000, 0, null);
            check(s.timedOut, "budgetMs=0 应 timedOut");
            equal(0, s.heads.size());
            equal(3, s.titles.length); // 无论超时都完整填充
            check(!s.complete, "未完成读取");
            ChapterSurvey neg = ChapterSurvey.read(book, 240, 2000, -1, null);
            check(neg.timedOut, "负预算也应立即 timedOut");
            equal(0, neg.heads.size());
        } finally {
            book.close();
        }
    }

    private static void headBounds() throws Exception {
        LazyChapterBook book = openThreeChapters();
        try {
            equal("", book.head(-1, 10));
            equal("", book.head(3, 10));  // 越界（total=3）
            equal("", book.head(9, 10));
            equal("", book.head(0, 0));
            equal("", book.head(0, -5));
            check(!book.head(0, 10).isEmpty(), "合法 head 不应为空");
        } finally {
            book.close();
        }
    }

    private static void prefixOnly() throws Exception {
        LazyChapterBook book = openThreeChapters();
        try {
            ChapterSurvey s = ChapterSurvey.read(book, 240, 2000, 1500, null);
            String full = book.load(0);
            check(full.length() > 240, "构造的正文应超过 240 码元");
            check(s.heads.get(0).text.length() <= 240, "head 前缀不得超过 240 码元");
            check(s.heads.get(0).text.length() < full.length(), "head 不应等于整章正文");
            check(!s.heads.get(0).text.equals(full), "heads 里不含整章正文（防改成 load()）");
            check(full.startsWith(s.heads.get(0).text), "head 应是整章正文的前缀");
            check(s.heads.get(0).cut, "正文更长，cut 应为 true");
        } finally {
            book.close();
        }
    }

    private static void surrogateHead() throws Exception {
        // 无标题文件：正文第一行以「甲 + U+20000」开头，索引器把整段作为第 0 章，
        // 于是 head(0, 2) 恰好落在高代理上，用于验证末尾高代理裁剪。
        String text = "甲𠀀乙丙丁戊己庚辛\n" + "普通正文内容。\n".repeat(5);
        File file = write("surrogate-" + (++sequence) + ".txt", text);
        LazyChapterBook book = LazyChapterBook.open(file, "txt", dir.toFile());
        try {
            String h2 = book.head(0, 2);
            equal("甲", h2);
            check(!Character.isHighSurrogate(h2.charAt(h2.length() - 1)), "末尾不应残留高代理");
            for (int k = 1; k <= 20; k++) {
                noBrokenSurrogate(book.head(0, k), "head(0," + k + ") 切开代理对");
            }
        } finally {
            book.close();
        }
    }

    private static void progressBatches() throws Exception {
        LazyChapterBook book = openThreeChapters();
        try {
            int[] calls = {0};
            ChapterSurvey s = ChapterSurvey.read(book, 240, 2000, 1500, snapshot -> {
                calls[0]++;
                check(!snapshot.complete, "批次快照应为部分结果");
                equal(3, snapshot.titles.length);
            });
            check(calls[0] >= 1, "至少回调一次 progress");
            check(s.complete, "最终结果应 complete");
        } finally {
            book.close();
        }
    }

    /** T03：EPUB 流式分支的 ChapterSurvey 标题/spineIndex/head 与旧 parse 一致。 */
    private static void epubHeadParity() throws Exception {
        String body = "春天来了，冰河解冻。甲𠀀乙丙丁戊己庚辛".repeat(20);
        File epub = writeEpub("survey-" + (++sequence) + ".epub",
                "<html><body><h1>春归</h1><p>" + body + "</p></body></html>",
                "<html><body><h1>夏至</h1><p>夏日的风掠过麦田😀</p></body></html>");
        try {
            BookParser.BookContent old = BookParser.parse(epub, "epub");
            try (LazyChapterBook book = LazyChapterBook.open(epub, "epub", dir.toFile())) {
                check(book.size() == old.chapters.size(), "EPUB 流式章数一致");
                ChapterSurvey s = ChapterSurvey.read(book, 240, 2000, 1500, null);
                check(s.complete, "EPUB survey 应 complete");
                for (int i = 0; i < old.chapters.size(); i++) {
                    equal(old.chapters.get(i).title, s.titles[i]);
                    equal(old.chapters.get(i).spineIndex, s.spineIndex[i]);
                    for (int n : new int[]{1, 2, 9, 240}) {
                        equal(expectedHead(old.chapters.get(i).text, n), book.head(i, n));
                        noBrokenSurrogate(book.head(i, n), "EPUB head 切开代理对");
                    }
                }
            }
        } finally {
            Files.deleteIfExists(epub.toPath());
        }
    }

    private static String expectedHead(String text, int maxChars) {
        int n = Math.min(text.length(), maxChars);
        String prefix = text.substring(0, n);
        if (n > 0 && Character.isHighSurrogate(prefix.charAt(n - 1))) prefix = prefix.substring(0, n - 1);
        return prefix;
    }

    private static File writeEpub(String name, String... chapters) throws Exception {
        Path p = dir.resolve(name);
        Map<String, String> files = new LinkedHashMap<String, String>();
        files.put("META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/book.opf'"
                + " media-type='application/oebps-package+xml'/></rootfiles></container>");
        StringBuilder items = new StringBuilder();
        StringBuilder spine = new StringBuilder();
        for (int i = 0; i < chapters.length; i++) {
            items.append("<item id='c").append(i).append("' href='c").append(i)
                    .append(".xhtml' media-type='application/xhtml+xml'/>");
            spine.append("<itemref idref='c").append(i).append("'/>");
            files.put("OPS/c" + i + ".xhtml", chapters[i]);
        }
        files.put("OPS/book.opf", "<package><metadata><title>测试书</title></metadata><manifest>"
                + items + "</manifest><spine>" + spine + "</spine></package>");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(p))) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return p.toFile();
    }

    private static LazyChapterBook openThreeChapters() throws Exception {
        String text = "第一章 春归\n春天来了，冰河解冻。\n" + "春风又绿江南岸，草木渐次苏醒。\n".repeat(30) + "\n"
                + "第二章 夏至\n夏日的风掠过麦田。\n" + "蝉声在午后此起彼伏，热浪翻涌。\n".repeat(30) + "\n"
                + "第三章 秋深\n秋叶落在石阶上。\n" + "霜降之后天气渐凉，雁阵南飞。\n".repeat(30) + "\n";
        File file = write("survey-" + (++sequence) + ".txt", text);
        return LazyChapterBook.open(file, "txt", dir.toFile());
    }

    private static File write(String name, String text) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    private static void noBrokenSurrogate(String s, String message) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                check(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)), message);
                i++;
            } else {
                check(!Character.isLowSurrogate(c), message);
            }
        }
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
