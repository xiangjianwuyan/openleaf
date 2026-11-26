import org.openleaf.reader.core.ChapterRule;
import org.openleaf.reader.core.LazyChapterBook;
import org.openleaf.reader.core.TextChapterIndexer;
import org.openleaf.reader.core.TextEncoding;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 文本编码手动兜底回归：四编码严格解码、AUTO 路径零回归、override 章数/标题与 BOM skip。 */
public final class BookParserEncodingTest {
    private static Path temporary;
    private static int sequence;
    private static int passed;

    public static void main(String[] args) throws Exception {
        temporary = Files.createTempDirectory("openleaf-encoding-tests-");
        try {
            test("四种 charset 严格解码命中预期文字", BookParserEncodingTest::decodeFourCharsets);
            test("UTF-16 BOM 试探与 offset 跳 BOM 解码", BookParserEncodingTest::utf16Bom);
            test("AUTO 路径 UTF-8 / GB18030 章标题零回归", BookParserEncodingTest::autoPath);
            test("override 解码章数/标题正确且逐字等价 AUTO", BookParserEncodingTest::overrideParity);
            test("强制 UTF-16 无 BOM 不丢前两字节", BookParserEncodingTest::utf16WithoutBom);
            test("LazyChapterBook override 透传", BookParserEncodingTest::bookPassthrough);
            System.out.println("BookParserEncodingTest: " + passed + " checks passed");
        } finally {
            deleteRecursively(temporary);
        }
    }

    private static void decodeFourCharsets() throws Exception {
        String expected = "第一章 归来，正文测试。";
        Charset[] charsets = {
                StandardCharsets.UTF_8,
                Charset.forName("GB18030"),
                StandardCharsets.UTF_16LE,
                Charset.forName("Big5")
        };
        // Big5 不含部分简体字，单独用繁体样本；其余三者共用同一句
        String big5Text = "第一章 歸來，正文測試。";
        String[] samples = {expected, expected, expected, big5Text};
        String[] results = new String[charsets.length];
        for (int i = 0; i < charsets.length; i++) {
            byte[] bytes = samples[i].getBytes(charsets[i]);
            results[i] = TextEncoding.decode(bytes, 0, charsets[i]);
            equal(samples[i], results[i]);
        }
        // prefs 索引 → mode → charset 全链路
        equal(StandardCharsets.UTF_8, TextEncoding.charsetOf(TextEncoding.fromIndex(1)));
        equal(Charset.forName("GB18030"), TextEncoding.charsetOf(TextEncoding.fromIndex(2)));
        equal(StandardCharsets.UTF_16LE, TextEncoding.charsetOf(TextEncoding.fromIndex(3)));
        equal(Charset.forName("Big5"), TextEncoding.charsetOf(TextEncoding.fromIndex(4)));
        equal(null, TextEncoding.charsetOf(TextEncoding.fromIndex(0)));
        equal(TextEncoding.Mode.AUTO, TextEncoding.fromIndex(-1));
        equal(TextEncoding.Mode.AUTO, TextEncoding.fromIndex(99)); // 非法索引 clamp 到 AUTO
    }

    private static void utf16Bom() throws Exception {
        String text = "带 BOM 的一行文字。";
        byte[] le = new byte[2 + text.length() * 2];
        le[0] = (byte) 0xFF; le[1] = (byte) 0xFE;
        byte[] raw = text.getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(raw, 0, le, 2, raw.length);
        equal(StandardCharsets.UTF_16LE, TextEncoding.resolveUtf16(le));
        equal(text, TextEncoding.decode(le, 2, StandardCharsets.UTF_16LE));

        byte[] be = new byte[2 + text.length() * 2];
        be[0] = (byte) 0xFE; be[1] = (byte) 0xFF;
        byte[] rawBe = text.getBytes(StandardCharsets.UTF_16BE);
        System.arraycopy(rawBe, 0, be, 2, rawBe.length);
        equal(StandardCharsets.UTF_16BE, TextEncoding.resolveUtf16(be));
        equal(text, TextEncoding.decode(be, 2, StandardCharsets.UTF_16BE));

        equal(StandardCharsets.UTF_16LE, TextEncoding.resolveUtf16(raw)); // 无 BOM 先 LE
        equal(StandardCharsets.UTF_16LE, TextEncoding.resolveUtf16(null));
    }

    private static void autoPath() throws Exception {
        String content = "第一章 归来\n这是正文。\n第二章 远行\n更多正文。\n";
        Charset gb = Charset.forName("GB18030");
        TextChapterIndexer.Result utf8 = TextChapterIndexer.index(write(content, StandardCharsets.UTF_8), cacheDir());
        TextChapterIndexer.Result autoGb = TextChapterIndexer.index(write(content, gb), cacheDir());
        try {
            check(utf8.chapters.size() >= 2, "UTF-8 AUTO 应识别至少两章");
            equal(utf8.chapters.size(), autoGb.chapters.size());
            for (int i = 0; i < utf8.chapters.size(); i++) {
                equal(utf8.chapters.get(i).title, autoGb.chapters.get(i).title);
            }
            check(autoGb.chapters.get(0).title.contains("第一章"), "GB18030 AUTO 应正确解码中文标题");
        } finally {
            utf8.cacheFile.delete();
            autoGb.cacheFile.delete();
        }
    }

    private static void overrideParity() throws Exception {
        String content = "第一章 归来\n这是正文。\n第二章 远行\n更多正文。\n";
        Charset gb = Charset.forName("GB18030");
        File file = write(content, gb);
        TextChapterIndexer.Result auto = TextChapterIndexer.index(file, cacheDir(), ChapterRule.conservative());
        TextChapterIndexer.Result forced = TextChapterIndexer.index(file, cacheDir(), ChapterRule.conservative(), gb);
        TextChapterIndexer.Result legacy = TextChapterIndexer.index(file, cacheDir(), ChapterRule.conservative(), null);
        try {
            equal(auto.chapters.size(), forced.chapters.size());
            check(forced.chapters.size() >= 2, "override 后章数应 >=2");
            for (int i = 0; i < auto.chapters.size(); i++) {
                TextChapterIndexer.ChapterMeta a = auto.chapters.get(i);
                TextChapterIndexer.ChapterMeta b = forced.chapters.get(i);
                TextChapterIndexer.ChapterMeta c = legacy.chapters.get(i);
                equal(a.title, b.title);
                equal(a.chapterKey, b.chapterKey);
                equal(a.sourceStart, b.sourceStart);
                equal(a.charCount, b.charCount);
                equal(a.title, c.title); // override=null 必须与原重载逐字一致
            }
            equal(new String(Files.readAllBytes(auto.cacheFile.toPath()), StandardCharsets.UTF_16LE),
                    new String(Files.readAllBytes(forced.cacheFile.toPath()), StandardCharsets.UTF_16LE));
        } finally {
            auto.cacheFile.delete();
            forced.cacheFile.delete();
            legacy.cacheFile.delete();
        }
    }

    private static void utf16WithoutBom() throws Exception {
        String content = "第一章 归来\n这是正文。\n第二章 远行\n更多正文。\n";
        File file = write(content, StandardCharsets.UTF_16LE); // 无 BOM
        TextChapterIndexer.Result forced = TextChapterIndexer.index(
                file, cacheDir(), ChapterRule.conservative(), StandardCharsets.UTF_16LE);
        try {
            check(forced.chapters.size() >= 2, "无 BOM UTF-16LE 强制解码应有至少两章");
            equal("第一章 归来", forced.chapters.get(0).title);
            equal("src:0", forced.chapters.get(0).chapterKey);
            String cached = new String(Files.readAllBytes(forced.cacheFile.toPath()), StandardCharsets.UTF_16LE);
            check(cached.contains("第一章 归来") && cached.contains("第二章 远行"),
                    "强制 UTF-16 无 BOM 时不应丢掉开头两个字节");
        } finally {
            forced.cacheFile.delete();
        }
    }

    private static void bookPassthrough() throws Exception {
        String content = "第一章 归来\n这是正文。\n第二章 远行\n更多正文。\n";
        Charset gb = Charset.forName("GB18030");
        File file = write(content, gb);
        try (LazyChapterBook book = LazyChapterBook.open(file, "txt", cacheDir(),
                ChapterRule.conservative(), gb)) {
            check(book.size() >= 2, "透传 override 后章数应 >=2");
            equal("第一章 归来", book.meta(0).title);
            check(book.load(0).contains("这是正文。"), "透传 override 后正文应正确解码");
            check(book.head(0, 200).contains("第一章"), "head 也应读回正确标题");
        }
    }

    private static File write(String content, Charset charset) throws Exception {
        Path file = temporary.resolve("encoding-" + (++sequence) + ".txt");
        Files.write(file, content.getBytes(charset));
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
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("Expected " + expected + "; got " + actual);
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
