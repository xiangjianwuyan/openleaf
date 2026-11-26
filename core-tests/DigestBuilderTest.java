import org.openleaf.reader.core.ChapterSurvey;
import org.openleaf.reader.core.DigestBuilder;
import org.openleaf.reader.core.DigestBuilder.Digest;
import org.openleaf.reader.core.LazyChapterBook;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

/** 提要首句提取 / 拼装回归；无 JUnit、无第三方依赖。 */
public final class DigestBuilderTest {
    private static Path dir;
    private static int passed;

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("openleaf-digest-tests-");
        try {
            test("空行 / 空白行跳过", DigestBuilderTest::blankLines);
            test("标题行跳过（NFKC + 去空白 + 忽略大小写）", DigestBuilderTest::titleLines);
            test("#{1,6} 前缀行跳过", DigestBuilderTest::markdownLines);
            test("整章只有标题 / head 为空 → 空首句", DigestBuilderTest::emptyChapter);
            test("60 码点截断且不切开代理对", DigestBuilderTest::codePointClip);
            test("placeholderTitle → 第 N 章", DigestBuilderTest::placeholder);
            test("build(): 空标题占位 + truncated", DigestBuilderTest::buildDigest);
            test("常量取值", DigestBuilderTest::constants);
            System.out.println("PASS: " + passed + " DigestBuilder checks");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
        }
    }

    private static void blankLines() {
        equal("第一行正文。", DigestBuilder.lead("第一章", "\n\n   \n\t\n第一行正文。\n第二行正文。", 60));
        equal("短句。", DigestBuilder.lead("第一章", "短句。", 60));
        equal("", DigestBuilder.lead("第一章", "短句。", 0));
    }

    private static void titleLines() {
        equal("春天来了。", DigestBuilder.lead("第一章 春天", "第一章 春天\n春天来了。", 60));
        // 全角数字经 NFKC 与标题一致 → 跳过
        equal("正文开始。", DigestBuilder.lead("第１章 序", "第1章 序\n正文开始。", 60));
        // 去空白 + 忽略大小写
        equal("正文开始。", DigestBuilder.lead("Sci-Fi 序", "  sci-fi序  \n正文开始。", 60));
    }

    private static void markdownLines() {
        equal("真正正文。", DigestBuilder.lead("卷一", "# 卷一\n## 小节\n真正正文。", 60));
        equal("正文。", DigestBuilder.lead("卷一", "###### 六级标题\n正文。", 60));
    }

    private static void emptyChapter() {
        equal("", DigestBuilder.lead("序章", "", 60));
        equal("", DigestBuilder.lead("序章", null, 60));
        equal("", DigestBuilder.lead("序章", "序章\n", 60));
        equal("", DigestBuilder.lead("序章", "序章\n\n  \n", 60));
        equal("", DigestBuilder.lead("序章", "# 序章\n## 小节\n", 60));
    }

    private static void codePointClip() {
        String supplementary = "𠀀"; // U+20000：一个码点、两个 UTF-16 码元
        String line = "甲".repeat(59) + supplementary + "尾巴";
        String r = DigestBuilder.lead("", line, 60);
        equal("甲".repeat(59) + supplementary + "…", r);
        check(r.contains(supplementary), "截断不得切开代理对");
        equal(61, r.codePointCount(0, r.length())); // 60 码点 + 一个省略号
        noBrokenSurrogate(r, "截断结果含孤立代理");
        // maxChars 大于码点数时不追加省略号
        equal("甲乙丙", DigestBuilder.lead("", "甲乙丙", 60));
    }

    private static void placeholder() {
        equal("第1章", DigestBuilder.placeholderTitle(0));
        equal("第10章", DigestBuilder.placeholderTitle(9));
        equal("第50章", DigestBuilder.placeholderTitle(49));
    }

    private static void buildDigest() throws Exception {
        LazyChapterBook book = openBook();
        try {
            ChapterSurvey survey = ChapterSurvey.read(book, 240, 2000, 1500, null);
            equal(3, survey.total);
            survey.titles[0] = ""; // 模拟章节标题为空（数组元素可写，无需构造器）
            Digest d = DigestBuilder.build("《测试书》", Arrays.asList("科幻", "短篇"), survey, 50);
            equal("《测试书》", d.bookTitle);
            equal(2, d.tags.size());
            equal(3, d.lines.size());
            equal(3, d.totalChapters);
            check(!d.truncated, "50 行未截断");
            equal("第1章", d.lines.get(0).title); // 空标题占位
            equal(0, d.lines.get(0).chapter);
            check(!d.lines.get(0).lead.isEmpty(), "首句不应为空");

            Digest cut = DigestBuilder.build("《测试书》", Collections.emptyList(), survey, 2);
            equal(2, cut.lines.size());
            check(cut.truncated, "total(3) > maxLines(2) 应 truncated");

            Digest nullTags = DigestBuilder.build("《测试书》", null, survey, 50);
            equal(0, nullTags.tags.size());
            equal(3, nullTags.lines.size());
        } finally {
            book.close();
        }
    }

    private static void constants() {
        equal(60, DigestBuilder.DEFAULT_LEAD_CHARS);
        equal(50, DigestBuilder.DEFAULT_MAX_LINES);
    }

    private static LazyChapterBook openBook() throws Exception {
        String text = "第一章 春归\n春天来了，冰河解冻。\n" + "春风又绿江南岸。\n".repeat(30) + "\n"
                + "第二章 夏至\n夏日的风掠过麦田。\n" + "蝉声在午后此起彼伏。\n".repeat(30) + "\n"
                + "第三章 秋深\n秋叶落在石阶上。\n" + "霜降之后天气渐凉。\n".repeat(30) + "\n";
        File file = dir.resolve("digest.txt").toFile();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return LazyChapterBook.open(file, "txt", dir.toFile());
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
