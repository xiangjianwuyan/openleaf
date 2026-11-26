import org.openleaf.reader.core.ChapterRule;
import org.openleaf.reader.core.LazyChapterBook;
import org.openleaf.reader.core.TextChapterIndexer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Standalone rule-injection regression; no JUnit, Android SDK, or third-party libraries. */
public final class TextChapterIndexerRuleTest {
    private static Path temporary;
    private static int sequence;
    private static int passed;

    public static void main(String[] args) throws Exception {
        temporary = Files.createTempDirectory("openleaf-rule-tests-");
        try {
            test("保守档零回归：原重载与注入保守档逐字一致", TextChapterIndexerRuleTest::conservativeParity);
            test("档位改变章数/标题/chapterKey/sourceStart 且缓存 UTF-16LE 一致",
                    TextChapterIndexerRuleTest::tieredIndex);
            test("LazyChapterBook 规则透传与目录 head 读取", TextChapterIndexerRuleTest::bookPassthrough);
            System.out.println("TextChapterIndexerRuleTest: " + passed + " checks passed");
        } finally {
            deleteRecursively(temporary);
        }
    }

    private static void conservativeParity() throws Exception {
        String content = "第一章 归来\n这是正文。\n第二章 远行\n更多正文。\n";
        File file = writeText(content);
        TextChapterIndexer.Result original = TextChapterIndexer.index(file, cacheDir());
        TextChapterIndexer.Result injected = TextChapterIndexer.index(file, cacheDir(), ChapterRule.conservative());
        TextChapterIndexer.Result nullRule = TextChapterIndexer.index(file, cacheDir(), null);
        try {
            check(original.chapters.size() >= 2, "保守样本至少应有两章");
            equal(original.chapters.size(), injected.chapters.size());
            equal(original.chapters.size(), nullRule.chapters.size());
            for (int i = 0; i < original.chapters.size(); i++) {
                TextChapterIndexer.ChapterMeta a = original.chapters.get(i);
                TextChapterIndexer.ChapterMeta b = injected.chapters.get(i);
                equal(a.title, b.title);
                equal(a.chapterKey, b.chapterKey);
                equal(a.spineIndex, b.spineIndex);
                equal(a.sourceStart, b.sourceStart);
                equal(a.charCount, b.charCount);
                equal(a.byteSize, b.byteSize);
                equal(a.offset, b.offset);
            }
            String cacheA = new String(Files.readAllBytes(original.cacheFile.toPath()), StandardCharsets.UTF_16LE);
            String cacheB = new String(Files.readAllBytes(injected.cacheFile.toPath()), StandardCharsets.UTF_16LE);
            equal(cacheA, cacheB);
            check(cacheA.contains("第一章 归来") && cacheA.contains("第二章 远行"), "缓存正文应保留标题行");
        } finally {
            original.cacheFile.delete();
            injected.cacheFile.delete();
            nullRule.cacheFile.delete();
        }
    }

    private static void tieredIndex() throws Exception {
        String content = "开头正文\n1. 起航\n正文二\n[5] 暗流\n正文三\n";
        File file = writeText(content);
        TextChapterIndexer.Result conservative = TextChapterIndexer.index(
                file, cacheDir(), ChapterRule.conservative());
        TextChapterIndexer.Result standard = TextChapterIndexer.index(
                file, cacheDir(), ChapterRule.of(ChapterRule.Mode.STANDARD, ""));
        TextChapterIndexer.Result loose = TextChapterIndexer.index(
                file, cacheDir(), ChapterRule.of(ChapterRule.Mode.LOOSE, ""));
        try {
            equal(1, conservative.chapters.size());
            equal(2, standard.chapters.size());
            equal(3, loose.chapters.size());
            check(standard.chapters.get(1).title.contains("起航"), "标准档应识别 1. 起航");
            check(loose.chapters.get(1).title.contains("起航"), "宽松档应包含标准档命中");
            check(loose.chapters.get(2).title.contains("暗流"), "宽松档应识别 [5] 暗流");

            int previousSource = -1;
            for (TextChapterIndexer.ChapterMeta chapter : loose.chapters) {
                check(chapter.sourceStart >= 0, "sourceStart 不应为负");
                check(chapter.sourceStart > previousSource, "sourceStart 应严格递增");
                equal("src:" + chapter.sourceStart, chapter.chapterKey);
                check(chapter.byteSize > 0 && chapter.byteSize % 2 == 0, "UTF-16LE 缓存字节数应为正偶数");
                check(chapter.charCount > 0, "charCount 应为正");
                previousSource = chapter.sourceStart;
            }
            String cached = new String(Files.readAllBytes(loose.cacheFile.toPath()), StandardCharsets.UTF_16LE);
            check(cached.contains("开头正文") && cached.contains("1. 起航") && cached.contains("[5] 暗流"),
                    "重建后的缓存应逐行保留原文");
        } finally {
            conservative.cacheFile.delete();
            standard.cacheFile.delete();
            loose.cacheFile.delete();
        }
    }

    private static void bookPassthrough() throws Exception {
        String content = "开头正文\n1. 起航\n正文二\n[5] 暗流\n正文三\n";
        File file = writeText(content);
        try (LazyChapterBook conservativeBook = LazyChapterBook.open(
                    file, "txt", cacheDir(), ChapterRule.conservative());
             LazyChapterBook looseBook = LazyChapterBook.open(
                    file, "txt", cacheDir(), ChapterRule.of(ChapterRule.Mode.LOOSE, ""));
             LazyChapterBook plainBook = LazyChapterBook.open(file, "txt", cacheDir())) {
            equal(1, conservativeBook.size());
            equal(3, looseBook.size());
            equal(conservativeBook.size(), plainBook.size());
            equal(conservativeBook.meta(0).title, plainBook.meta(0).title);
            check(looseBook.head(0, 200).contains("开头正文"), "head 应读回第一章正文前缀");
            check(looseBook.meta(2).title.contains("暗流"), "新目录应反映宽松档标题");
            equal("src:" + looseBook.meta(1).sourceStart, looseBook.meta(1).chapterKey);
            check(conservativeBook.meta(0).sourceStart + "开头正文\n".length() == looseBook.meta(1).sourceStart,
                    "sourceStart 应与原文字符偏移一致");
        }
    }

    private static File writeText(String content) throws Exception {
        Path file = temporary.resolve("book-" + (++sequence) + ".txt");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file.toFile();
    }

    private static File cacheDir() throws Exception {
        Path dir = temporary.resolve("cache-" + (++sequence));
        Files.createDirectories(dir);
        return dir.toFile();
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
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
