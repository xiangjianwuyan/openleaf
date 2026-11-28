import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.EpubChapterIndexer;
import org.openleaf.reader.core.LazyChapterBook;
import org.openleaf.reader.core.ProgressModel;
import org.openleaf.reader.core.ReadingAnchor;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * EPUB 流式索引 parity 与语义守护：覆盖预研报告 §3 的 R1~R11、
 * T03 端到端 open/load/head，以及多字节量纲 bug 的关键回归。
 * 完整 S2/S4/S5/S6 大样本与受限堆探针由 T04 补充。
 */
public final class EpubChapterIndexerTest {
    private static Path dir;
    private static int passed;

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("openleaf-epub-index-");
        try {
            chapterParity();             // R1/R3/R4/R6
            chunkBoundaryParity();       // R2/R11
            titleAndSurrogateParity();   // R11
            metadataContract();          // §2.2 + 量纲契约
            progressParity();            // R5
            anchorKeyStability();        // R10
            fullTextParity();            // R6 + 多字节量纲 bug 关键回归
            headParity();                // R7
            securityAndRollback();       // R8/R9
            emptyBookRollback();         // R9
            endToEndOpen();              // T03 端到端
            largeSampleS2();             // S2 多 spine 中等章
            largeSampleS4();             // S4 单条目逼近 24MiB
            largeSampleS4Over();         // S4-over 超限拒绝
            largeSampleS6();             // S6 UTF-16 XHTML
            s5SecurityGroup();           // S5 安全样本组补充
            System.out.println("PASS: " + passed + " epub chapter index checks");
        } finally {
            try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** R1/R3/R4/R6：3 spine（长章切 2 片 + 空正文 + 正常章）逐字段 parity。 */
    private static void chapterParity() throws Exception {
        File epub = epub(spineEntries("Sample",
                "a".repeat(BookParser.MAX_CHAPTER_CHARS + 50), "  ", "Body three"));
        EpubChapterIndexer.Result indexed = null;
        try {
            BookParser.BookContent original = BookParser.parse(epub, "epub");
            indexed = EpubChapterIndexer.index(epub, dir.toFile());
            check(original.chapters.size() == 3, "长章分片+空 spine 应产出 3 章，实际 " + original.chapters.size());
            assertParity(original, indexed);
            passed++;
        } finally {
            cleanup(epub, indexed);
        }
    }

    /** R2/R11：19999/20000/20001/40001 边界与分片点 emoji，逐章 text/title 相等且不切坏代理对。 */
    private static void chunkBoundaryParity() throws Exception {
        String withEmojiAtBoundary = "c".repeat(10000) + "𠀀" + "c".repeat(10000);
        File epub = epub(spineEntries("Boundaries", "a".repeat(19999), "b".repeat(20000),
                withEmojiAtBoundary, "d".repeat(40001)));
        EpubChapterIndexer.Result indexed = null;
        try {
            BookParser.BookContent original = BookParser.parse(epub, "epub");
            indexed = EpubChapterIndexer.index(epub, dir.toFile());
            check(original.chapters.size() > 4, "应发生长章分片，实际 " + original.chapters.size());
            assertParity(original, indexed);
            for (EpubChapterIndexer.ChapterMeta meta : indexed.chapters) {
                noBrokenSurrogate(meta.title, "title 代理对损坏");
            }
            passed++;
        } finally {
            cleanup(epub, indexed);
        }
    }

    /** R11：200 码元标题边界 + 高代理裁剪，新旧标题逐章相等。 */
    private static void titleAndSurrogateParity() throws Exception {
        String longTitle = "标".repeat(205) + "𠀀" + "尾";
        File epub = epub(spineEntries(longTitle, "甲𠀀乙丙丁戊己庚辛", "正文二"));
        EpubChapterIndexer.Result indexed = null;
        try {
            BookParser.BookContent original = BookParser.parse(epub, "epub");
            indexed = EpubChapterIndexer.index(epub, dir.toFile());
            assertParity(original, indexed);
            check(indexed.title.length() <= 201, "书名未受 boundedTitle 约束");
            check(indexed.chapters.get(0).charCount == "甲𠀀乙丙丁戊己庚辛".length(), "代理对 charCount 错");
            passed++;
        } finally {
            cleanup(epub, indexed);
        }
    }

    /** §2.2 元数据契约：key 格式、ordinal 全局连续、两种字节量纲、重复索引确定性。 */
    private static void metadataContract() throws Exception {
        File epub = epub(spineEntries("Contract", "中文𠀀正文😀", "  ", "末章"));
        EpubChapterIndexer.Result first = null;
        EpubChapterIndexer.Result second = null;
        try {
            first = EpubChapterIndexer.index(epub, dir.toFile());
            second = EpubChapterIndexer.index(epub, dir.toFile());
            check(first.chapters.size() == 2, "空正文 spine 不应产出章节");
            for (int i = 0; i < first.chapters.size(); i++) {
                EpubChapterIndexer.ChapterMeta m = first.chapters.get(i);
                check(m.chapterKey.matches("spine:\\d+:\\d+"), "chapterKey 格式错: " + m.chapterKey);
                check(m.chapterKey.equals("spine:" + m.spineIndex + ":" + i), "ordinal 非全局连续: " + m.chapterKey);
                check(m.spineIndex == (i == 0 ? 0 : 2), "spineIndex 错位: " + m.spineIndex);
                check(m.sourceStart == -1 && m.spineIndex >= 0, "EPUB 元数据约束不符");
                check(m.cacheBytes == m.charCount * 2, "cacheBytes 应为 UTF-16LE 字节数");
                String text = readChapter(first.cacheFile, m);
                check(text.getBytes(StandardCharsets.UTF_8).length == m.byteSize,
                        "byteSize 应为 UTF-8 字节数");
                EpubChapterIndexer.ChapterMeta again = second.chapters.get(i);
                check(m.chapterKey.equals(again.chapterKey) && m.offset == again.offset
                        && m.charCount == again.charCount && m.byteSize == again.byteSize
                        && m.cacheBytes == again.cacheBytes, "重复索引结果不确定");
            }
            passed++;
        } finally {
            cleanup(epub, first);
            cleanup(epub, second);
        }
    }

    /** R5：以旧 parse 章节序列构造参考前缀和，在 0/1/25/50/99/100% 与各章边界比较 ProgressModel。 */
    private static void progressParity() throws Exception {
        File epub = epub(spineEntries("Progress", "甲乙丙丁".repeat(6000), "第二章节", "第三章😀"));
        EpubChapterIndexer.Result indexed = null;
        try {
            BookParser.BookContent old = BookParser.parse(epub, "epub");
            indexed = EpubChapterIndexer.index(epub, dir.toFile());
            assertParity(old, indexed);
            long[] prefix = new long[old.chapters.size() + 1];
            for (int i = 0; i < old.chapters.size(); i++) {
                prefix[i + 1] = prefix[i] + Math.max(0,
                        old.chapters.get(i).text.getBytes(StandardCharsets.UTF_8).length);
            }
            try (LazyChapterBook book = LazyChapterBook.open(epub, "epub", dir.toFile())) {
                ProgressModel model = new ProgressModel(book);
                int[] probes = {0, 1, 25, 50, 99, 100};
                for (int p : probes) {
                    int[] expected = refFromPercent(old, prefix, p);
                    int[] actual = model.fromPercent(p);
                    check(expected[0] == actual[0] && expected[1] == actual[1], "fromPercent@" + p);
                    check(model.percent(actual[0], actual[1], false)
                            == refPercent(old, prefix, actual[0], actual[1], false), "percent@" + p);
                }
                for (int i = 0; i < old.chapters.size(); i++) {
                    check(model.percent(i, 0, true) == refPercent(old, prefix, i, 0, true), "章边界 percent@" + i);
                }
            }
            passed++;
        } finally {
            cleanup(epub, indexed);
        }
    }

    /** R10：chapterKey 序列化往返后 ProgressModel.resolve 仍定位到同一章。 */
    private static void anchorKeyStability() throws Exception {
        File epub = epub(spineEntries("Anchor", "甲章", "  ", "丙章"));
        EpubChapterIndexer.Result indexed = null;
        try {
            indexed = EpubChapterIndexer.index(epub, dir.toFile());
            try (LazyChapterBook book = LazyChapterBook.open(epub, "epub", dir.toFile())) {
                ProgressModel model = new ProgressModel(book);
                for (int i = 0; i < indexed.chapters.size(); i++) {
                    EpubChapterIndexer.ChapterMeta m = indexed.chapters.get(i);
                    ReadingAnchor anchor = ReadingAnchor.parse(
                            ReadingAnchor.epub(m.chapterKey, i, m.spineIndex, 0, 0f).serialize());
                    int[] offset = new int[1];
                    check(model.resolve(anchor, offset) == i, "chapterKey 无法定位第 " + i + " 章: " + m.chapterKey);
                }
            }
            passed++;
        } finally {
            cleanup(epub, indexed);
        }
    }

    /** R6 + 量纲 bug 关键回归：中文/emoji 章节经 LazyChapterBook.load() 回读逐字相等。 */
    private static void fullTextParity() throws Exception {
        String first = "中文正文𠀀与 emoji 😀 混合，第一段。\n第二段：甲乙丙丁。";
        String second = "第二章：仿真测试 📖 与生僻字 𠀀。";
        File epub = epub(spineEntries("多字节书", first, second));
        EpubChapterIndexer.Result indexed = null;
        try {
            BookParser.BookContent old = BookParser.parse(epub, "epub");
            indexed = EpubChapterIndexer.index(epub, dir.toFile());
            assertParity(old, indexed);
            try (LazyChapterBook book = LazyChapterBook.open(epub, "epub", dir.toFile())) {
                for (int i = 0; i < old.chapters.size(); i++) {
                    check(old.chapters.get(i).text.equals(book.load(i)),
                            "第" + i + "章 load() 回读不一致（UTF-16LE/UTF-8 量纲错误？）");
                    check(old.chapters.get(i).text.equals(readChapter(indexed.cacheFile, indexed.chapters.get(i))),
                            "第" + i + "章缓存直接回读不一致");
                }
            }
            passed++;
        } finally {
            cleanup(epub, indexed);
        }
    }

    /** R7：head(i,maxChars) 与旧正文按同规则取前缀相等，含 emoji 落在 head 边界。 */
    private static void headParity() throws Exception {
        String body = "甲𠀀乙丙丁戊己庚辛".repeat(30) + "😀尾句。";
        File epub = epub(spineEntries("Head", body, "第二章正文"));
        EpubChapterIndexer.Result indexed = null;
        try {
            BookParser.BookContent old = BookParser.parse(epub, "epub");
            indexed = EpubChapterIndexer.index(epub, dir.toFile());
            assertParity(old, indexed);
            try (LazyChapterBook book = LazyChapterBook.open(epub, "epub", dir.toFile())) {
                for (int i = 0; i < old.chapters.size(); i++) {
                    for (int n : new int[]{1, 2, 3, 9, 240, 1000}) {
                        check(expectedHead(old.chapters.get(i).text, n).equals(book.head(i, n)),
                                "head(" + i + "," + n + ") 与旧正文不一致");
                        noBrokenSurrogate(book.head(i, n), "head 切开代理对");
                    }
                }
            }
            passed++;
        } finally {
            cleanup(epub, indexed);
        }
    }

    /** R8/R9：DRM、路径穿越、外部实体、畸形 XML 均被拒且不残留缓存。 */
    private static void securityAndRollback() throws Exception {
        expectIndexFailure(epub(withRights()), "DRM");
        expectIndexFailure(epub(withEntry("../evil.txt", "x")), "ZIP");
        expectIndexFailure(epub(spineEntries("XXE",
                "<?xml version='1.0'?><!DOCTYPE html [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
                        + "<html><body><p>&xxe;</p></body></html>")), null);
        expectIndexFailure(epub(spineEntries("Broken", "<html><body><p title='unterminated>text</p></body></html>")), "标签未闭合");
        passed++;
    }

    private static void emptyBookRollback() throws Exception {
        expectIndexFailure(epub(spineEntries("Empty", "  ")), "电子书内容为空");
        passed++;
    }

    /** T03 端到端：open() 产出的 Section/offset/load 与旧 parse 逐字段一致。 */
    private static void endToEndOpen() throws Exception {
        File epub = epub(spineEntries("端到端", "第一章 中文😀正文", "  ", "末章内容"));
        EpubChapterIndexer.Result indexed = null;
        try {
            BookParser.BookContent old = BookParser.parse(epub, "epub");
            indexed = EpubChapterIndexer.index(epub, dir.toFile());
            assertParity(old, indexed);
            try (LazyChapterBook book = LazyChapterBook.open(epub, "epub", dir.toFile())) {
                check(book.title().equals(old.title), "book.title 不一致");
                check(book.size() == old.chapters.size(), "book.size 不一致");
                for (int i = 0; i < old.chapters.size(); i++) {
                    BookParser.Chapter c = old.chapters.get(i);
                    LazyChapterBook.Section s = book.meta(i);
                    check(s.title.equals(c.title) && s.spineIndex == c.spineIndex
                            && s.sourceStart == -1 && s.charCount == c.text.length()
                            && s.byteSize == c.text.getBytes(StandardCharsets.UTF_8).length,
                            "第" + i + "章 Section 不一致");
                    check(s.chapterKey.equals("spine:" + c.spineIndex + ":" + i), "第" + i + "章 key 不一致");
                    check(c.text.equals(book.load(i)), "第" + i + "章 load 不一致");
                }
            }
            passed++;
        } finally {
            cleanup(epub, indexed);
        }
    }

    // --------------------------- T04 大样本 ---------------------------

    /** S2：30 spine × 约 1MiB 展开，章数/字符总数/抽样正文与旧 parse 一致。 */
    private static void largeSampleS2() throws Exception {
        File epub = dir.resolve("s2.epub").toFile();
        EpubStreamingOomProbe.generate("s2", epub);
        EpubChapterIndexer.Result indexed = null;
        try {
            BookParser.BookContent old = BookParser.parse(epub, "epub");
            indexed = EpubChapterIndexer.index(epub, dir.toFile());
            check(old.title.equals(indexed.title), "S2 书名不一致");
            check(old.chapters.size() == indexed.chapters.size(), "S2 章数不一致");
            long oldChars = 0;
            long newChars = 0;
            for (int i = 0; i < old.chapters.size(); i++) oldChars += old.chapters.get(i).text.length();
            for (EpubChapterIndexer.ChapterMeta m : indexed.chapters) newChars += m.charCount;
            check(oldChars == newChars && oldChars > 20_000_000, "S2 字符总数不一致: " + oldChars + " vs " + newChars);
            for (int i : new int[]{0, 1, indexed.chapters.size() / 2, indexed.chapters.size() - 1}) {
                check(old.chapters.get(i).text.equals(readChapter(indexed.cacheFile, indexed.chapters.get(i))),
                        "S2 抽样第" + i + "章正文不一致");
            }
            passed++;
        } finally {
            cleanup(epub, indexed);
        }
    }

    /** S4：单个约 23MiB XHTML 条目（未超 24MiB），逐字段 parity。 */
    private static void largeSampleS4() throws Exception {
        File epub = dir.resolve("s4.epub").toFile();
        EpubStreamingOomProbe.generate("s4", epub);
        EpubChapterIndexer.Result indexed = null;
        try {
            BookParser.BookContent old = BookParser.parse(epub, "epub");
            indexed = EpubChapterIndexer.index(epub, dir.toFile());
            assertParity(old, indexed);
            passed++;
        } finally {
            cleanup(epub, indexed);
        }
    }

    /** S4-over：24MiB+ 单条目必须被 Archive 拒绝且不留缓存。 */
    private static void largeSampleS4Over() throws Exception {
        File epub = dir.resolve("s4over.epub").toFile();
        EpubStreamingOomProbe.generate("s4over", epub);
        expectIndexFailure(epub, "24MB");
        passed++;
    }

    /** S6：UTF-16LE BOM 的 XHTML 条目，编码探测与缓存正文逐字段 parity。 */
    private static void largeSampleS6() throws Exception {
        File epub = dir.resolve("s6.epub").toFile();
        EpubStreamingOomProbe.generate("s6", epub);
        EpubChapterIndexer.Result indexed = null;
        try {
            BookParser.BookContent old = BookParser.parse(epub, "epub");
            indexed = EpubChapterIndexer.index(epub, dir.toFile());
            assertParity(old, indexed);
            check(!indexed.title.startsWith("\uFEFF") && !indexed.chapters.get(0).title.startsWith("\uFEFF"),
                    "UTF-16 BOM 泄漏到标题");
            passed++;
        } finally {
            cleanup(epub, indexed);
        }
    }

    /** S5 补充：重复 ZIP 路径、不支持的 encryption 算法均被拒且无缓存残留。 */
    private static void s5SecurityGroup() throws Exception {
        expectIndexFailure(epubDuplicate(), "重复路径");
        Map<String, String> encoded = spineEntries("Encrypted", "正文");
        encoded.put("META-INF/encryption.xml", "<encryption><EncryptedData>"
                + "<EncryptionMethod Algorithm='http://www.w3.org/2001/04/xmlenc#aes128-cbc'/>"
                + "</EncryptedData></encryption>");
        expectIndexFailure(epub(encoded), "DRM");
        passed++;
    }

    /** ZipOutputStream 自身禁止重名，必须手写 STORED ZIP 才能覆盖 Archive 的重复路径检查。 */
    private static File epubDuplicate() throws Exception {
        File out = dir.resolve("duplicate.epub").toFile();
        Map<String, String> files = spineEntries("Duplicate", "正文");
        List<String> names = new ArrayList<String>();
        List<byte[]> datas = new ArrayList<byte[]>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            names.add(e.getKey());
            datas.add(e.getValue().getBytes(StandardCharsets.UTF_8));
            if ("OPS/Chapters/c0.xhtml".equals(e.getKey())) {
                names.add(e.getKey());
                datas.add(e.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        writeStoredZip(out, names, datas);
        return out;
    }

    private static void writeStoredZip(File out, List<String> names, List<byte[]> datas) throws Exception {
        ByteArrayOutputStream local = new ByteArrayOutputStream();
        ByteArrayOutputStream directory = new ByteArrayOutputStream();
        DataOutputStream l = new DataOutputStream(local);
        DataOutputStream c = new DataOutputStream(directory);
        for (int i = 0; i < names.size(); i++) {
            byte[] name = names.get(i).getBytes(StandardCharsets.UTF_8);
            byte[] data = datas.get(i);
            CRC32 crc = new CRC32();
            crc.update(data);
            int offset = local.size();
            le32(l, 0x04034b50); le16(l, 20); le16(l, 0); le16(l, 0);
            le16(l, 0); le16(l, 0);
            le32(l, crc.getValue()); le32(l, data.length); le32(l, data.length);
            le16(l, name.length); le16(l, 0); l.write(name); l.write(data);
            le32(c, 0x02014b50); le16(c, 20); le16(c, 20); le16(c, 0); le16(c, 0);
            le16(c, 0); le16(c, 0);
            le32(c, crc.getValue()); le32(c, data.length); le32(c, data.length);
            le16(c, name.length); le16(c, 0); le16(c, 0); le16(c, 0); le16(c, 0);
            le32(c, 0); le32(c, offset); c.write(name);
        }
        int directoryOffset = local.size();
        int directorySize = directory.size();
        try (DataOutputStream z = new DataOutputStream(Files.newOutputStream(out.toPath()))) {
            z.write(local.toByteArray());
            z.write(directory.toByteArray());
            le32(z, 0x06054b50); le16(z, 0); le16(z, 0);
            le16(z, names.size()); le16(z, names.size());
            le32(z, directorySize); le32(z, directoryOffset); le16(z, 0);
        }
    }

    private static void le16(DataOutputStream out, long value) throws IOException {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
    }

    private static void le32(DataOutputStream out, long value) throws IOException {
        for (int i = 0; i < 4; i++) out.write((int) ((value >>> (8 * i)) & 0xFF));
    }

    private static void putEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // --------------------------- parity helpers ---------------------------

    private static void assertParity(BookParser.BookContent old, EpubChapterIndexer.Result indexed)
            throws Exception {
        check(old.title.equals(indexed.title), "书名不一致: " + old.title + " vs " + indexed.title);
        check(old.chapters.size() == indexed.chapters.size(), "章数不一致: "
                + old.chapters.size() + " vs " + indexed.chapters.size());
        for (int i = 0; i < old.chapters.size(); i++) {
            BookParser.Chapter c = old.chapters.get(i);
            EpubChapterIndexer.ChapterMeta m = indexed.chapters.get(i);
            check(c.title.equals(m.title), "第" + i + "章 title 不一致: " + c.title + " vs " + m.title);
            check(c.spineIndex == m.spineIndex, "第" + i + "章 spineIndex 不一致");
            check(m.sourceStart == -1 && m.spineIndex >= 0, "第" + i + "章 EPUB 元数据约束不符");
            check(("spine:" + c.spineIndex + ":" + i).equals(m.chapterKey),
                    "第" + i + "章 chapterKey 不一致: " + m.chapterKey);
            check(c.text.length() == m.charCount, "第" + i + "章 charCount 不一致");
            check(c.text.getBytes(StandardCharsets.UTF_8).length == m.byteSize,
                    "第" + i + "章 byteSize 应为 UTF-8 字节数");
            check(m.cacheBytes == m.charCount * 2, "第" + i + "章 cacheBytes 应为 UTF-16LE 字节数");
            check(c.text.equals(readChapter(indexed.cacheFile, m)), "第" + i + "章缓存正文回读不一致");
        }
    }

    private static String readChapter(File cache, EpubChapterIndexer.ChapterMeta meta) throws Exception {
        byte[] bytes = new byte[meta.cacheBytes];
        try (RandomAccessFile in = new RandomAccessFile(cache, "r")) {
            in.seek(meta.offset);
            in.readFully(bytes);
        }
        return new String(bytes, StandardCharsets.UTF_16LE);
    }

    private static String expectedHead(String text, int maxChars) {
        int n = Math.min(text.length(), maxChars);
        String prefix = text.substring(0, n);
        if (n > 0 && Character.isHighSurrogate(prefix.charAt(n - 1))) prefix = prefix.substring(0, n - 1);
        return prefix;
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

    private static int refPercent(BookParser.BookContent old, long[] prefix, int ch, int off, boolean atEnd) {
        BookParser.Chapter c = old.chapters.get(ch);
        int size = c.text.getBytes(StandardCharsets.UTF_8).length;
        long within = atEnd ? size
                : (long) size * Math.max(0, Math.min(off, c.text.length())) / Math.max(1, c.text.length());
        long total = prefix[prefix.length - 1];
        return total <= 0 ? 0 : (int) Math.min(100, Math.round(100.0 * (prefix[ch] + within) / total));
    }

    private static int[] refFromPercent(BookParser.BookContent old, long[] prefix, int percent) {
        long total = prefix[prefix.length - 1];
        if (total <= 0 || old.chapters.isEmpty()) return new int[]{0, 0};
        long target = (long) Math.ceil(total * Math.max(0, Math.min(100, percent)) / 100.0);
        int ch = 0;
        while (ch + 1 < old.chapters.size() && prefix[ch + 1] <= target) ch++;
        BookParser.Chapter c = old.chapters.get(ch);
        int size = c.text.getBytes(StandardCharsets.UTF_8).length;
        long within = Math.max(0, target - prefix[ch]);
        int offset = within >= size ? c.text.length()
                : (int) (c.text.length() * within / Math.max(1, size));
        return new int[]{ch, Math.max(0, Math.min(offset, c.text.length()))};
    }

    // --------------------------- sample builders ---------------------------

    private static Map<String, String> spineEntries(String title, String... bodies) {
        Map<String, String> files = new LinkedHashMap<String, String>();
        files.put("META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/book.opf'"
                + " media-type='application/oebps-package+xml'/></rootfiles></container>");
        StringBuilder items = new StringBuilder();
        StringBuilder spine = new StringBuilder();
        for (int i = 0; i < bodies.length; i++) {
            items.append("<item id='c").append(i).append("' href='Chapters/c").append(i)
                    .append(".xhtml' media-type='application/xhtml+xml'/>");
            spine.append("<itemref idref='c").append(i).append("'/>");
            String body = bodies[i];
            String html = body.startsWith("<html") ? body : "<html><body><p>" + body + "</p></body></html>";
            files.put("OPS/Chapters/c" + i + ".xhtml", html);
        }
        files.put("OPS/book.opf", "<package><metadata><title>" + title + "</title></metadata><manifest>"
                + items + "</manifest><spine>" + spine + "</spine></package>");
        return files;
    }

    private static Map<String, String> withRights() {
        Map<String, String> files = spineEntries("DRM", "正文");
        files.put("META-INF/rights.xml", "<rights/>");
        return files;
    }

    private static Map<String, String> withEntry(String name, String content) {
        Map<String, String> files = spineEntries("BadPath", "正文");
        files.put(name, content);
        return files;
    }

    private static File epub(Map<String, String> files) throws Exception {
        File file = File.createTempFile("openleaf-epub-index-", ".epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                putEntry(zip, entry.getKey(), entry.getValue());
            }
        }
        return file;
    }

    private static void expectIndexFailure(File epub, String fragment) throws Exception {
        long before = countCaches();
        try {
            EpubChapterIndexer.index(epub, dir.toFile());
            throw new AssertionError("应拒绝但成功: " + epub.getName());
        } catch (Exception expected) {
            if (fragment != null) {
                check(expected.getMessage() != null && expected.getMessage().contains(fragment),
                        "错误消息不符: " + expected.getMessage());
            }
        } finally {
            Files.deleteIfExists(epub.toPath());
        }
        check(countCaches() == before, "异常路径未回滚缓存文件");
    }

    private static long countCaches() throws Exception {
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("openleaf-epub-idx-")).count();
        }
    }

    private static void cleanup(File epub, EpubChapterIndexer.Result indexed) throws Exception {
        Files.deleteIfExists(epub.toPath());
        if (indexed != null) Files.deleteIfExists(indexed.cacheFile.toPath());
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
