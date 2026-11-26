import org.openleaf.reader.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** 流式 TXT 章节索引：语义与内存行为回归。 */
public final class TextChapterIndexerTest {
    private static Path dir;
    private static int passed;

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("openleaf-txt-index-");
        try {
            chapterParity();
            bomHandling();
            largeFileStreaming();
            gb18030();
            txtBranchUnaffected();
            largeTxtControl();
            System.out.println("PASS: " + passed + " text chapter index checks");
        } finally {
            try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(p);
            }
        }
    }

    private static void chapterParity() throws Exception {
        String text = "第一章 开端\n正文一\n\n第二章 发展\n正文二\n\n第三章 结局\n正文三\n";
        File file = write("parity.txt", text.getBytes(StandardCharsets.UTF_8));
        BookParser.BookContent original = BookParser.parse(file, "txt");
        TextChapterIndexer.Result indexed = TextChapterIndexer.index(file, dir.toFile());
        check(original.chapters.size() == indexed.chapters.size(),
                "章节数一致: " + original.chapters.size() + " vs " + indexed.chapters.size());
        // 用 LazyChapterBook 打开并 load 每章，验证正文可还原
        LazyChapterBook book = LazyChapterBook.open(file, "txt", dir.toFile());
        check(book.size() == original.chapters.size(), "LazyBook 章节数一致");
        for (int i = 0; i < book.size(); i++) {
            String loaded = book.load(i);
            check(loaded.contains("正文"), "第" + i + "章含正文");
        }
        book.close();
        passed++;
    }

    private static void bomHandling() throws Exception {
        String text = "第一章 开头\n内容\n";
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[utf8.length + 3];
        bom[0] = (byte)0xEF; bom[1] = (byte)0xBB; bom[2] = (byte)0xBF;
        System.arraycopy(utf8, 0, bom, 3, utf8.length);
        File file = write("bom.txt", bom);
        TextChapterIndexer.Result indexed = TextChapterIndexer.index(file, dir.toFile());
        check(indexed.chapters.size() >= 1, "BOM 文件可索引");
        LazyChapterBook book = LazyChapterBook.open(file, "txt", dir.toFile());
        check(book.load(0).contains("内容"), "BOM 跳过正确");
        book.close();
        passed++;
    }

    /** 关键：20MiB 文本在 64MB 堆下能打开（旧路径全量 parse 会 OOM）。 */
    private static void largeFileStreaming() throws Exception {
        File file = dir.resolve("large.txt").toFile();
        byte[] block = ("第十二章 大书测试\n" + ("这是一行正文内容用于验证流式索引。\n".repeat(200)))
                .getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            for (int i = 0; i < 6000; i++) out.write(block); // ~20MB
        }
        check(file.length() > 18L * 1024 * 1024, "构造大文件");
        // 在独立 JVM 用 -Xmx64m 验证（见 shell 层），这里只验证索引可用
        TextChapterIndexer.Result indexed = TextChapterIndexer.index(file, dir.toFile());
        check(indexed.chapters.size() >= 1, "大文件可索引");
        long totalChars = 0;
        for (TextChapterIndexer.ChapterMeta c : indexed.chapters) totalChars += c.charCount;
        check(totalChars > 15_000_000, "字符总数正确: " + totalChars);
        passed++;
    }

    private static void gb18030() throws Exception {
        String text = "第一章 中文\n内容中文\n";
        byte[] encoded = text.getBytes(java.nio.charset.Charset.forName("GB18030"));
        File file = write("gb.txt", encoded);
        TextChapterIndexer.Result indexed = TextChapterIndexer.index(file, dir.toFile());
        check(indexed.chapters.size() >= 1, "GB18030 可索引");
        passed++;
    }

    /** T03：EPUB 分流不能误伤 TXT/MD 的 key 规则、spineIndex 与 UTF-16LE byteSize 语义。 */
    private static void txtBranchUnaffected() throws Exception {
        String text = "第一章 春归\n中文𠀀正文\n\n第二章 夏至\n夏日正文😀\n";
        for (String ext : new String[]{"txt", "md"}) {
            File file = write("unaffected." + ext, text.getBytes(StandardCharsets.UTF_8));
            BookParser.BookContent original = BookParser.parse(file, ext);
            try (LazyChapterBook book = LazyChapterBook.open(file, ext, dir.toFile())) {
                check(book.size() == original.chapters.size(), ext + " 章数未变");
                for (int i = 0; i < book.size(); i++) {
                    LazyChapterBook.Section s = book.meta(i);
                    check(s.spineIndex == -1, ext + " spineIndex 仍为 -1");
                    check(s.chapterKey.startsWith("src:") || s.chapterKey.equals("chapter:" + i),
                            ext + " chapterKey 未变: " + s.chapterKey);
                    check(s.byteSize == book.load(i).getBytes(StandardCharsets.UTF_16LE).length,
                            ext + " byteSize 仍为 UTF-16LE 字节数");
                    // TXT 模板历来在末行补 \n（parse 不补），这是既有语义；只断言正文前缀未被 EPUB 分流改动。
                    check(book.load(i).startsWith(original.chapters.get(i).text), ext + " load 正文前缀未变");
                }
            }
        }
        passed++;
    }

    /** T04 大书对照：EPUB 分流后 TXT 流式索引仍正常，沿用「正文前缀未变」口径。 */
    private static void largeTxtControl() throws Exception {
        byte[] block = ("第十二章 大书对照\n"
                + "这是一行用于验证 TXT 流式索引未回归的正文内容。\n".repeat(120))
                .getBytes(StandardCharsets.UTF_8);
        File file = dir.resolve("large-control.txt").toFile();
        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            for (int i = 0; i < 1200; i++) out.write(block);
        }
        BookParser.BookContent original = BookParser.parse(file, "txt");
        try (LazyChapterBook book = LazyChapterBook.open(file, "txt", dir.toFile())) {
            check(book.size() == original.chapters.size(), "大书 TXT 章数未变");
            for (int i : new int[]{0, book.size() / 2, book.size() - 1}) {
                LazyChapterBook.Section s = book.meta(i);
                check(s.spineIndex == -1, "大书 TXT spineIndex 未变");
                check(s.byteSize == book.load(i).getBytes(StandardCharsets.UTF_16LE).length,
                        "大书 TXT byteSize 仍为 UTF-16LE");
                check(book.load(i).startsWith(original.chapters.get(i).text), "大书 TXT 正文前缀未变");
            }
        }
        passed++;
    }

    private static File write(String name, byte[] bytes) throws IOException {
        File file = dir.resolve(name).toFile();
        Files.write(file.toPath(), bytes);
        return file;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
