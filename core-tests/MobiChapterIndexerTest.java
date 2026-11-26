import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.LazyChapterBook;
import org.openleaf.reader.core.MobiChapterIndexer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * MOBI 流式索引端到端 parity（纯 JVM；注入 Fake decoder，不加载 native so）。
 *
 * 覆盖：index → LazyChapterBook.open(mobi) → load/head 与 BookParser.parse(mobi) 逐章相等；
 * chapterKey=chapter:&lt;ordinal&gt;；spineIndex/sourceStart=-1；UTF-8/UTF-16LE 量纲分离；
 * 中文 + emoji + 代理对的 load 回读逐字相等（0.17.0 byteSize 量纲 bug 的 MOBI 回归）；
 * 空章与异常路径删缓存。
 */
public final class MobiChapterIndexerTest {
    private static Path dir;
    private static int passed;

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("openleaf-mobi-index-");
        try {
            chapterKeyAndMetadata();
            multiByteLoadParity();
            headParity();
            endToEndOpen();
            emptyBookRollback();
            failureRollback();
            System.out.println("PASS: " + passed + " mobi chapter index checks");
        } finally {
            MobiParserBridgeTest.deleteRecursively(dir);
        }
    }

    /** key=chapter:&lt;ordinal&gt; 全局连续，-1/-1，UTF-8/UTF-16LE 两量纲各自正确。 */
    private static void chapterKeyAndMetadata() throws Exception {
        File book = sample(new String[][] {
                { "第一章", "中文𠀀正文😀" },
                { "第二章", "  " },
                { "第三章", "末章 aA" },
        });
        MobiChapterIndexer.Result indexed = null;
        try {
            indexed = MobiChapterIndexer.index(book, dir.toFile());
            equal(3, indexed.chapters.size());
            for (int i = 0; i < 3; i++) {
                MobiChapterIndexer.ChapterMeta m = indexed.chapters.get(i);
                equal("chapter:" + i, m.chapterKey);
                equal(-1, m.spineIndex);
                equal(-1, m.sourceStart);
                String text = new String[][] {
                        { "第一章", "中文𠀀正文😀" }, { "第二章", "  " }, { "第三章", "末章 aA" }
                }[i][1];
                equal(text.length(), m.charCount);
                equal(text.getBytes(StandardCharsets.UTF_8).length, m.byteSize);
                equal(text.getBytes(StandardCharsets.UTF_16LE).length, m.cacheBytes);
            }
            passed++;
        } finally {
            cleanup(book, indexed);
        }
    }

    /** 中文/emoji/代理对逐章 load 回读与 parse() 逐字相等（量纲 bug 关键回归）。 */
    private static void multiByteLoadParity() throws Exception {
        String[][] chapters = {
                { "多字节一", "中文正文😀与补充平面𠀀字符" },
                { "多字节二", "汉字" + "字".repeat(64) + "🚀尾" },
                { "多字节三", "混合 ASCII + 中文 + 🎉 + 𠀀" },
        };
        File book = sample(chapters);
        MobiChapterIndexer.Result indexed = null;
        LazyChapterBook opened = null;
        try {
            BookParser.BookContent original = parse(book);
            indexed = MobiChapterIndexer.index(book, dir.toFile());
            opened = LazyChapterBook.open(book, "mobi", dir.toFile());
            equal(original.chapters.size(), opened.size());
            equal(original.title, opened.title());
            for (int i = 0; i < original.chapters.size(); i++) {
                String expected = original.chapters.get(i).text;
                String actual = opened.load(i);
                equal(expected, actual);                 // 逐字相等，不截断、不乱码
                equal(expected.length(), actual.length());
                equal(original.chapters.get(i).title, indexed.chapters.get(i).title);
                noBrokenSurrogate(actual);
            }
            // 二次 load（LRU 命中）仍逐字相等。
            for (int i = 0; i < original.chapters.size(); i++) {
                equal(original.chapters.get(i).text, opened.load(i));
            }
            passed++;
        } finally {
            if (opened != null) opened.close();
            cleanup(book, indexed);
        }
    }

    /** head() 前缀读取与 parse() 文本前缀相等（UTF-16LE 定位，含代理对 safe trim）。 */
    private static void headParity() throws Exception {
        String[][] chapters = { { "首章", "中文𠀀正文😀ABC" }, { "次章", "乙😀乙" } };
        File book = sample(chapters);
        LazyChapterBook opened = null;
        try {
            BookParser.BookContent original = parse(book);
            opened = LazyChapterBook.open(book, "mobi", dir.toFile());
            for (int i = 0; i < original.chapters.size(); i++) {
                String full = original.chapters.get(i).text;
                for (int max : new int[] { 1, 2, 3, 7, 64, 1000 }) {
                    equal(expectedHead(full, max), opened.head(i, max));
                }
            }
            passed++;
        } finally {
            if (opened != null) opened.close();
            if (!book.delete()) book.deleteOnExit();
        }
    }

    /** 端到端：index 的元数据与 open 的 Section 一致，逐章 load 与 parse 一致。 */
    private static void endToEndOpen() throws Exception {
        String[][] chapters = { { "第一章", "正文一" }, { "第二章", "正文二😀" }, { "第三章", "正文三𠀀" } };
        File book = sample(chapters);
        MobiChapterIndexer.Result indexed = null;
        LazyChapterBook opened = null;
        try {
            BookParser.BookContent original = parse(book);
            indexed = MobiChapterIndexer.index(book, dir.toFile());
            opened = LazyChapterBook.open(book, "mobi", dir.toFile());
            equal(original.chapters.size(), indexed.chapters.size());
            equal(original.chapters.size(), opened.size());
            for (int i = 0; i < opened.size(); i++) {
                LazyChapterBook.Section s = opened.meta(i);
                equal(indexed.chapters.get(i).chapterKey, s.chapterKey);
                equal(indexed.chapters.get(i).charCount, s.charCount);
                equal(indexed.chapters.get(i).byteSize, s.byteSize);
                equal(-1, s.spineIndex);
                equal(-1, s.sourceStart);
                equal(original.chapters.get(i).text, opened.load(i));
            }
            passed++;
        } finally {
            if (opened != null) opened.close();
            cleanup(book, indexed);
        }
    }

    /** 空章：index 抛「电子书内容为空」且不残留缓存文件。 */
    private static void emptyBookRollback() throws Exception {
        File book = sample(new String[][] { { "空一", "" }, { "空二", "" } });
        long before = cacheCount();
        try {
            MobiChapterIndexer.index(book, dir.toFile());
            throw new AssertionError("全空章应失败");
        } catch (IOException error) {
            equal("电子书内容为空", error.getMessage());
            equal(before, cacheCount());
            passed++;
        } finally {
            if (!book.delete()) book.deleteOnExit();
        }
    }

    /** stream 抛异常：index 原样透出并删除已建的缓存文件。 */
    private static void failureRollback() throws Exception {
        File book = sample(new String[][] { { "章", "正文" } });
        long before = cacheCount();
        try {
            MobiParserBridgeTest.Fake failing = new MobiParserBridgeTest.Fake();
            failing.throwEncrypted = true; // stream 阶段前置拒绝
            BookParser.setMobiDecoder(failing);
            MobiChapterIndexer.index(book, dir.toFile());
            throw new AssertionError("DRM 应被拒绝");
        } catch (Exception error) {
            equal(MobiParserBridgeTest.DRM, error.getMessage());
            equal(before, cacheCount());
            passed++;
        } finally {
            BookParser.setMobiDecoder(null);
            if (!book.delete()) book.deleteOnExit();
        }
    }

    /** 注入 Fake decoder，把 chapters 作为解码结果；file 只是 BookParser 的存在性占位。 */
    private static File sample(String[][] chapters) throws Exception {
        File book = File.createTempFile("openleaf-mobi-index-", ".mobi");
        MobiParserBridgeTest.Fake fake = new MobiParserBridgeTest.Fake();
        fake.fixedChapters = chapters;
        fake.title = "MOBI 样本书名";
        // 真实 Bridge 的 read()/validate() 都取 nativeTitle，书名同源；fake 两字段默认不同，这里对齐。
        fake.validateTitle = fake.title;
        BookParser.setMobiDecoder(fake);
        return book;
    }

    private static BookParser.BookContent parse(File book) throws Exception {
        return BookParser.parse(book, "mobi");
    }

    private static long cacheCount() {
        File[] files = dir.toFile().listFiles((d, n) -> n.startsWith("openleaf-mobi-idx-"));
        return files == null ? 0 : files.length;
    }

    private static void cleanup(File book, MobiChapterIndexer.Result indexed) {
        if (indexed != null && indexed.cacheFile.exists()) indexed.cacheFile.delete();
        if (!book.delete()) book.deleteOnExit();
    }

    /** 与 LazyChapterBook.head 同语义：截断处若留下孤立高代理则丢弃。 */
    private static String expectedHead(String text, int maxChars) {
        int n = Math.min(text.length(), maxChars);
        String prefix = text.substring(0, n);
        if (n > 0 && Character.isHighSurrogate(prefix.charAt(n - 1))) prefix = prefix.substring(0, n - 1);
        return prefix;
    }

    private static void noBrokenSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            check(!(c >= '\uDC00' && c <= '\uDFFF' && (i == 0 || s.charAt(i - 1) < '\uD800'
                    || s.charAt(i - 1) > '\uDBFF')), "孤立低代理 @ " + i);
            check(!(c >= '\uD800' && c <= '\uDBFF'
                    && (i + 1 >= s.length() || s.charAt(i + 1) < '\uDC00' || s.charAt(i + 1) > '\uDFFF')),
                    "孤立高代理 @ " + i);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
