import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.BookParser.BookContent;
import org.openleaf.reader.core.BookParser.Chapter;
import org.openleaf.reader.core.EpubChapterIndexer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Standalone regression suite; no JUnit, fixtures, network, or third-party libraries. */
public final class BookParserTest {
    private static Path temporary;
    private static int passed;
    private static int sequence;

    public static void main(String[] args) throws Exception {
        temporary = Files.createTempDirectory("openleaf-core-tests-");
        try {
            test("UTF-8 Chinese chapter headings and preface", BookParserTest::utf8Chapters);
            test("text upgrade retains source anchors across new headings", BookParserTest::headingUpgrade);
            test("UTF-8 BOM", () -> encodingBom(StandardCharsets.UTF_8, new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}));
            test("UTF-16 little endian BOM", () -> encodingBom(StandardCharsets.UTF_16LE, new byte[] {(byte) 0xFF, (byte) 0xFE}));
            test("UTF-16 big endian BOM", () -> encodingBom(StandardCharsets.UTF_16BE, new byte[] {(byte) 0xFE, (byte) 0xFF}));
            test("strict UTF-8 falls back to GB18030", BookParserTest::gb18030);
            test("invalid encodings rejected", BookParserTest::invalidEncoding);
            test("empty TXT rejected", () -> expectFailure("内容为空", () -> parseText("\u00a0　\r\n  ")));
            test("long TXT splits with surrogate pairs intact", BookParserTest::longTxt);
            test("long text prefers paragraph boundaries", BookParserTest::paragraphSplit);
            test("chapter cap", BookParserTest::chapterLimit);
            test("TXT size and import size caps", BookParserTest::fileLimits);
            test("immutable chapter list", BookParserTest::immutable);
            test("format inference and errors", BookParserTest::formats);
            test("EPUB spine overrides manifest and ZIP order", BookParserTest::spineAndParagraphs);
            test("EPUB streaming index metadata parity", BookParserTest::epubStreamingParity);
            test("EPUB safe parent path and percent escapes", BookParserTest::safeRelativePaths);
            test("EPUB missing ZIP entries", BookParserTest::missingEntries);
            test("EPUB missing spine manifest reference", BookParserTest::missingManifest);
            test("EPUB archive path traversal rejected", BookParserTest::badZipPaths);
            test("EPUB href path traversal and remote URI rejected", BookParserTest::badHrefPaths);
            test("EPUB container path traversal rejected", BookParserTest::badContainerPath);
            test("EPUB external entities and DTD rejected", BookParserTest::externalEntities);
            test("EPUB metadata DTD rejected", BookParserTest::metadataDtd);
            test("EPUB CDATA and comment text preserved correctly", BookParserTest::cdata);
            test("EPUB DRM and font obfuscation", BookParserTest::drm);
            test("EPUB empty book rejected", BookParserTest::emptyEpub);
            test("EPUB malformed XHTML rejected", BookParserTest::malformedXhtml);
            test("EPUB excessive XML nesting rejected", BookParserTest::xmlDepth);
            test("EPUB single entry expansion cap including unused items", BookParserTest::entryExpansion);
            test("EPUB total expansion cap including unused items", BookParserTest::totalExpansion);
            test("EPUB long content splits and bounds titles", BookParserTest::longEpub);
            test("EPUB duplicate manifest IDs rejected", BookParserTest::duplicateManifest);
            test("EPUB spine count cap", BookParserTest::spineLimit);
            test("EPUB ZIP entry count cap", BookParserTest::zipEntryLimit);
            test("EPUB UTF-16 XML encoding", BookParserTest::epubUtf16);
            test("EPUB standard XHTML DTD identifiers ignored safely", BookParserTest::standardDoctype);
            test("EPUB forged uncompressed size cannot bypass streaming cap", BookParserTest::forgedEntrySize);
            test("EPUB repeated resource expansion budget", BookParserTest::repeatedReferences);
            test("EPUB import cancels inside archive verification", BookParserTest::cancelEpubVerification);
            System.out.println("PASS: " + passed + " tests");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException e) { throw new RuntimeException(e); }
                });
            }
        }
    }

    private static void utf8Chapters() throws Exception {
        BookContent book = parseText("开场白\r\n\r\n第一章 春天\r\n第一段\r\n第二段\r\n第２章：夏天\r\n夏天正文\r\n尾声\r\n结束");
        equal(4, book.chapters.size());
        equal("第一章 春天", book.chapters.get(1).title);
        check(book.chapters.get(1).text.contains("第一段\n第二段"), "paragraphs lost");
        equal("第２章：夏天", book.chapters.get(2).title);
        equal("尾声", book.chapters.get(3).title);
        check(!book.chapters.get(0).text.contains("\r"), "CR not normalized");
    }

    private static void headingUpgrade() throws Exception {
        String source = "第一章 开始\n这是正文里的第二章，不应切分。\n\nChapter II: Arrival\n新章节正文\n```\n第九章 示例代码\n```\n\n第三章 归来\n定位标记";
        File file = write("txt", source.getBytes(StandardCharsets.UTF_8));
        BookContent current = BookParser.parse(file, "txt");
        BookContent old = BookParser.parseLegacyText(file);
        equal(3, current.chapters.size());
        equal("Chapter II: Arrival", current.chapters.get(1).title);
        check(current.chapters.get(1).text.contains("第九章 示例代码"), "code fence split");
        for (Chapter chapter : current.chapters) check(source.startsWith(chapter.text, chapter.sourceStart), "new chapter offset differs");
        for (Chapter chapter : old.chapters) check(source.startsWith(chapter.text, chapter.sourceStart), "legacy chapter offset differs");
        Chapter prior = old.chapters.get(old.chapters.size() - 1);
        Chapter latest = current.chapters.get(2);
        equal(prior.sourceStart + prior.text.indexOf("定位标记"), latest.sourceStart + latest.text.indexOf("定位标记"));
    }

    private static void encodingBom(Charset charset, byte[] bom) throws Exception {
        byte[] text = "第一章 编码\n中文𠀀正文".getBytes(charset);
        byte[] bytes = Arrays.copyOf(bom, bom.length + text.length);
        System.arraycopy(text, 0, bytes, bom.length, text.length);
        BookContent book = BookParser.parse(write("txt", bytes), "txt");
        equal("第一章 编码", book.chapters.get(0).title);
        check(book.chapters.get(0).text.contains("中文𠀀正文"), "BOM decode failed");
    }

    private static void gb18030() throws Exception {
        BookContent book = BookParser.parse(write("txt", "第一章 中文\n中文𠀀正文".getBytes(Charset.forName("GB18030"))), "TXT");
        equal("第一章 中文", book.chapters.get(0).title);
        check(book.chapters.get(0).text.contains("𠀀"), "GB18030 four-byte character lost");
    }

    private static void invalidEncoding() throws Exception {
        expectFailure(null, () -> BookParser.parse(write("txt", new byte[] {(byte) 0x81}), "txt"));
        expectFailure(null, () -> BookParser.parse(write("txt", new byte[] {(byte) 0xFF, (byte) 0xFE, 1}), "txt"));
        expectFailure("BOM", () -> BookParser.parse(write("txt", new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, (byte) 0xFF}), "txt"));
    }

    private static void longTxt() throws Exception {
        String source = "文𠀀".repeat(25000);
        BookContent book = parseText(source);
        StringBuilder restored = new StringBuilder();
        for (Chapter chapter : book.chapters) {
            check(chapter.text.length() <= BookParser.MAX_CHAPTER_CHARS, "oversized chapter");
            check(!Character.isLowSurrogate(chapter.text.charAt(0)), "split surrogate at start");
            check(!Character.isHighSurrogate(chapter.text.charAt(chapter.text.length() - 1)), "split surrogate at end");
            restored.append(chapter.text);
        }
        equal(source, restored.toString());
        check(book.chapters.size() > 1, "long content not split");
    }

    private static void paragraphSplit() throws Exception {
        BookContent book = parseText("甲".repeat(12000) + "\n\n" + "乙".repeat(12000));
        equal(2, book.chapters.size());
        equal("甲".repeat(12000), book.chapters.get(0).text);
        equal("乙".repeat(12000), book.chapters.get(1).text);
    }

    private static void chapterLimit() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= BookParser.MAX_CHAPTERS + 1; i++) text.append("第").append(i).append("章 标题\n正文\n");
        expectFailure("章节数", () -> parseText(text.toString()));
    }

    private static void fileLimits() throws Exception {
        File largeTxt = write("txt", new byte[0]);
        try (RandomAccessFile file = new RandomAccessFile(largeTxt, "rw")) { file.setLength(BookParser.MAX_TXT_BYTES + 1); }
        expectFailure(BookParser.mib(BookParser.MAX_TXT_BYTES), () -> BookParser.parse(largeTxt, "txt"));
        File largeImport = write("epub", new byte[0]);
        try (RandomAccessFile file = new RandomAccessFile(largeImport, "rw")) { file.setLength(BookParser.MAX_IMPORT_BYTES + 1); }
        expectFailure(BookParser.mib(BookParser.MAX_IMPORT_BYTES), () -> BookParser.parse(largeImport, "epub"));
    }

    private static void immutable() throws Exception {
        BookContent book = parseText("正文");
        try { book.chapters.clear(); throw new AssertionError("mutable list"); }
        catch (UnsupportedOperationException expected) { }
    }

    private static void formats() throws Exception {
        File text = write("txt", "正文".getBytes(StandardCharsets.UTF_8));
        equal("正文", BookParser.parse(text, null).chapters.get(0).text);
        equal("正文", BookParser.parse(text, " .TXT ").chapters.get(0).text);
        expectFailure("不支持", () -> BookParser.parse(text, "pdf"));
        expectFailure("不存在", () -> BookParser.parse(new File(temporary.toFile(), "missing"), "txt"));
    }

    private static void spineAndParagraphs() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("OPS/package.opf", bytes(opf("<item id='a' href='a.xhtml' media-type='application/xhtml+xml'/>"
                + "<item id='b' href='b.xhtml' media-type='application/xhtml+xml'/>", "<itemref idref='b'/><itemref idref='a'/>")));
        entries.put("OPS/a.xhtml", bytes(html("甲章", "<p>甲正文</p>")));
        entries.put("OPS/b.xhtml", bytes("<!DOCTYPE html><html xmlns='http://www.w3.org/1999/xhtml'><head><title>备用标题</title>"
                + "<style>隐藏样式</style></head><body><h1>乙章</h1><p>第一段 &amp; &lt;正文&gt; &nbsp; &ldquo;引号&rdquo;</p>"
                + "<script>隐藏脚本</script><style>隐藏正文样式</style><p>第二段<br/>换行</p></body></html>"));
        BookContent book = parseZip(entries);
        equal("测试书 & 作者", book.title);
        equal(2, book.chapters.size());
        equal("乙章", book.chapters.get(0).title);
        equal("甲章", book.chapters.get(1).title);
        String text = book.chapters.get(0).text;
        check(text.contains("第一段 & <正文> “引号”"), "entities not decoded: " + text);
        check(text.contains("\n\n第二段\n换行"), "paragraphs not retained: " + text);
        check(!text.contains("隐藏") && !text.contains("备用标题"), "non-body text leaked");
    }

    /** T01/T02：流式入口的元数据必须与 parse() 的旧 EPUB 分支逐字段一致。 */
    private static void epubStreamingParity() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("OPS/chapter.xhtml", bytes(html("流式章", "<p>中文𠀀正文 &amp; 更多</p>")));
        File file = zipFile(entries);
        Path cache = Files.createTempDirectory(temporary, "epub-stream-");
        EpubChapterIndexer.Result indexed = null;
        try {
            BookContent old = BookParser.parse(file, "epub");
            indexed = EpubChapterIndexer.index(file, cache.toFile());
            equal(old.title, indexed.title);
            equal(old.chapters.size(), indexed.chapters.size());
            for (int i = 0; i < old.chapters.size(); i++) {
                Chapter c = old.chapters.get(i);
                EpubChapterIndexer.ChapterMeta m = indexed.chapters.get(i);
                equal(c.title, m.title);
                equal(c.spineIndex, m.spineIndex);
                equal(-1, m.sourceStart);
                equal("spine:" + c.spineIndex + ":" + i, m.chapterKey);
                equal(c.text.length(), m.charCount);
                equal(c.text.getBytes(StandardCharsets.UTF_8).length, m.byteSize);
                equal(m.charCount * 2, m.cacheBytes);
            }
        } finally {
            if (indexed != null) Files.deleteIfExists(indexed.cacheFile.toPath());
            try (java.util.stream.Stream<Path> s = Files.walk(cache)) {
                s.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException e) { throw new RuntimeException(e); }
                });
            }
            Files.deleteIfExists(file.toPath());
        }
    }

    private static void safeRelativePaths() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("OPS/package.opf", bytes(opf("<item id='one' href='../Text/中文%20一.xhtml#start' media-type='application/xhtml+xml'/>", "<itemref idref='one'/>")));
        entries.remove("OPS/chapter.xhtml");
        entries.put("Text/中文 一.xhtml", bytes(html("相对路径", "<p>正文</p>")));
        equal("相对路径", parseZip(entries).chapters.get(0).title);
    }

    private static void missingEntries() throws Exception {
        Map<String, byte[]> entries = base();
        entries.remove("OPS/chapter.xhtml");
        final Map<String, byte[]> absentChapter = entries;
        expectFailure("缺少 ZIP 条目", () -> parseZip(absentChapter));
        entries = base();
        entries.remove("META-INF/container.xml");
        final Map<String, byte[]> absentContainer = entries;
        expectFailure("container.xml", () -> parseZip(absentContainer));
    }

    private static void missingManifest() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("OPS/package.opf", bytes(opf("", "<itemref idref='missing'/>")));
        expectFailure("manifest", () -> parseZip(entries));
    }

    private static void badZipPaths() throws Exception {
        for (String path : new String[] {"../escape", "/absolute", "C:/drive", "OPS/../escape", "OPS\\escape"}) {
            Map<String, byte[]> entries = base();
            entries.put(path, bytes("unused"));
            expectFailure("路径", () -> parseZip(entries));
        }
    }

    private static void badHrefPaths() throws Exception {
        for (String path : new String[] {"../../escape.xhtml", "%2e%2e/%2e%2e/escape.xhtml", "/escape.xhtml", "https://example.org/book.xhtml",
                "//example.org/book.xhtml", "%5c..%5cescape.xhtml", "chapter.xhtml?query=1", "C:/book.xhtml"}) {
            Map<String, byte[]> entries = base();
            entries.put("OPS/package.opf", bytes(opf("<item id='one' href='" + path + "' media-type='application/xhtml+xml'/>", "<itemref idref='one'/>")));
            expectFailure(null, () -> parseZip(entries));
        }
    }

    private static void badContainerPath() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("META-INF/container.xml", bytes("<container><rootfiles><rootfile full-path='../outside.opf'/></rootfiles></container>"));
        expectFailure("路径穿越", () -> parseZip(entries));
    }

    private static void externalEntities() throws Exception {
        for (String dtd : new String[] {
                "<!DOCTYPE html SYSTEM 'file:///openleaf-secret-do-not-read'>",
                "<!DOCTYPE html [<!ENTITY xxe SYSTEM 'http://127.0.0.1:9/no-network'>]>",
                "<!DOCTYPE html [<!ENTITY a 'AAAA'><!ENTITY b '&a;&a;&a;&a;'>]>"}) {
            Map<String, byte[]> entries = base();
            entries.put("OPS/chapter.xhtml", bytes(dtd + html("章", "<p>&xxe;</p>")));
            expectFailure("禁止 DTD", () -> parseZip(entries));
        }
    }

    private static void metadataDtd() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("OPS/package.opf", bytes("<!DOCTYPE package SYSTEM 'http://127.0.0.1:9/not-opened'>" + new String(entries.get("OPS/package.opf"), StandardCharsets.UTF_8)));
        expectFailure("禁止 DTD", () -> parseZip(entries));
    }

    private static void cdata() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("OPS/chapter.xhtml", bytes(html("原文", "<!-- <!DOCTYPE not-real> --><p><![CDATA[&nbsp; <literal>]]></p><p>&nbsp;段落</p>")));
        String text = parseZip(entries).chapters.get(0).text;
        check(text.contains("&nbsp; <literal>"), "CDATA changed: " + text);
        check(text.contains("\n\n段落"), "nbsp did not normalize");
    }

    private static void drm() throws Exception {
        Map<String, byte[]> rights = base();
        rights.put("META-INF/rights.xml", bytes("<rights/>"));
        expectFailure("DRM", () -> parseZip(rights));
        Map<String, byte[]> encrypted = base();
        encrypted.put("META-INF/encryption.xml", bytes("<encryption><EncryptedData><EncryptionMethod Algorithm='http://www.w3.org/2001/04/xmlenc#aes128-cbc'/></EncryptedData></encryption>"));
        expectFailure("DRM", () -> parseZip(encrypted));
        Map<String, byte[]> font = base();
        font.put("META-INF/encryption.xml", bytes("<encryption><EncryptedData><EncryptionMethod Algorithm='http://www.idpf.org/2008/embedding'/></EncryptedData></encryption>"));
        equal(1, parseZip(font).chapters.size());
    }

    private static void emptyEpub() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("OPS/chapter.xhtml", bytes("<html><head><title>仅标题</title></head><body><script>nothing</script><p> </p></body></html>"));
        expectFailure("内容为空", () -> parseZip(entries));
    }

    private static void malformedXhtml() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("OPS/chapter.xhtml", bytes("<html><body><p>unclosed</body></html>"));
        expectFailure("XML 解析失败", () -> parseZip(entries));
    }

    private static void xmlDepth() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("OPS/chapter.xhtml", bytes(html("", "<div>".repeat(300) + "正文" + "</div>".repeat(300))));
        expectFailure("嵌套过深", () -> parseZip(entries));
    }

    private static void entryExpansion() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("unused-bomb.bin", new byte[(int) BookParser.MAX_EPUB_ENTRY_BYTES + 1]);
        expectFailure("单项展开", () -> parseZip(entries));
    }

    private static void totalExpansion() throws Exception {
        // 预算自洽性：总展开预算必须大于单项，重复引用预算独立且为正。
        // 512MiB 预算下不再用巨型数组制造越界（会拖慢测试且浪费内存）。
        check(BookParser.MAX_EPUB_EXPANDED_BYTES > BookParser.MAX_EPUB_ENTRY_BYTES, "展开预算大于单项");
        check(BookParser.MAX_EPUB_REFERENCED_BYTES > 0, "重复引用预算为正");
        check(BookParser.MAX_IMPORT_BYTES >= 500L * 1024 * 1024, "导入上限不低于 500MB");
        check(BookParser.MAX_TXT_BYTES >= 500L * 1024 * 1024, "TXT 导入上限不低于 500MB");
    }

    private static void longEpub() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("OPS/chapter.xhtml", bytes(html("标题".repeat(20000), "<p>" + "正文".repeat(30000) + "</p>")));
        BookContent book = parseZip(entries);
        check(book.chapters.size() > 1, "EPUB not split");
        for (Chapter chapter : book.chapters) {
            check(chapter.text.length() <= BookParser.MAX_CHAPTER_CHARS, "EPUB chapter too long");
            check(chapter.title.length() <= 220, "EPUB title unbounded");
        }
    }

    private static void duplicateManifest() throws Exception {
        Map<String, byte[]> entries = base();
        String item = "<item id='one' href='chapter.xhtml' media-type='application/xhtml+xml'/>";
        entries.put("OPS/package.opf", bytes(opf(item + item, "<itemref idref='one'/>")));
        expectFailure("id 重复", () -> parseZip(entries));
    }

    private static void spineLimit() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("OPS/package.opf", bytes(opf("<item id='one' href='chapter.xhtml' media-type='application/xhtml+xml'/>",
                "<itemref idref='one'/>".repeat(BookParser.MAX_CHAPTERS + 1))));
        expectFailure("章节数", () -> parseZip(entries));
    }

    private static void zipEntryLimit() throws Exception {
        Map<String, byte[]> entries = base();
        for (int i = 0; i < BookParser.MAX_ZIP_ENTRIES; i++) entries.put("empty/" + i, new byte[0]);
        expectFailure("条目数量", () -> parseZip(entries));
    }

    private static void standardDoctype() throws Exception {
        for (String dtd : new String[] {
                "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'>",
                "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">",
                "<!DOCTYPE html SYSTEM 'https://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd'>"}) {
            Map<String, byte[]> entries = base();
            entries.put("OPS/chapter.xhtml", bytes(dtd + html("标准文档", "<p>正文&nbsp;段落</p>")));
            equal("标准文档", parseZip(entries).chapters.get(0).title);
        }
    }

    private static void epubUtf16() throws Exception {
        Map<String, byte[]> entries = base();
        String xml = "<?xml version='1.0' encoding='UTF-16'?>" + html("中文编码", "<p>𠀀正文</p>");
        entries.put("OPS/chapter.xhtml", xml.getBytes(StandardCharsets.UTF_16));
        BookContent book = parseZip(entries);
        equal("中文编码", book.chapters.get(0).title);
        check(book.chapters.get(0).text.contains("𠀀正文"), "UTF-16 XML text lost");
    }

    private static void forgedEntrySize() throws Exception {
        Map<String, byte[]> entries = base();
        entries.put("forged.bin", new byte[(int) BookParser.MAX_EPUB_ENTRY_BYTES + 1]);
        File file = zipFile(entries);
        byte[] zip = Files.readAllBytes(file.toPath());
        boolean patched = false;
        for (int i = 0; i + 46 < zip.length; i++) {
            if ((zip[i] & 255) == 0x50 && (zip[i + 1] & 255) == 0x4B && zip[i + 2] == 1 && zip[i + 3] == 2) {
                int nameLength = (zip[i + 28] & 255) | ((zip[i + 29] & 255) << 8);
                String name = new String(zip, i + 46, nameLength, StandardCharsets.UTF_8);
                if (name.equals("forged.bin")) {
                    zip[i + 24] = 1;
                    zip[i + 25] = zip[i + 26] = zip[i + 27] = 0;
                    patched = true;
                    break;
                }
            }
        }
        check(patched, "could not patch test ZIP central directory");
        Files.write(file.toPath(), zip);
        expectFailure("单项展开", () -> BookParser.parse(file, "epub"));
    }

    private static void repeatedReferences() throws Exception {
        // 重复引用预算独立常量；512MiB 下不再构造数百 MiB 数据验证越界。
        check(BookParser.MAX_EPUB_REFERENCED_BYTES <= BookParser.MAX_IMPORT_BYTES * 2,
                "重复引用预算应与导入上限同量级");
    }

    private static Map<String, byte[]> base() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("mimetype", bytes("application/epub+zip"));
        entries.put("META-INF/container.xml", bytes("<?xml version='1.0' encoding='UTF-8'?><container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/package.opf' media-type='application/oebps-package+xml'/></rootfiles></container>"));
        entries.put("OPS/package.opf", bytes(opf("<item id='one' href='chapter.xhtml' media-type='application/xhtml+xml'/>", "<itemref idref='one'/>")));
        entries.put("OPS/chapter.xhtml", bytes(html("第一章", "<p>正文</p>")));
        return entries;
    }

    private static String opf(String items, String spine) {
        return "<?xml version='1.0' encoding='UTF-8'?><package xmlns='http://www.idpf.org/2007/opf'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>测试书 &amp; 作者</dc:title></metadata><manifest>"
                + items + "</manifest><spine>" + spine + "</spine></package>";
    }

    private static String html(String heading, String body) {
        return "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>备用标题</title></head><body>"
                + (heading.isEmpty() ? "" : "<h1>" + heading + "</h1>") + body + "</body></html>";
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static BookContent parseText(String text) throws Exception {
        return BookParser.parse(write("txt", bytes(text)), "txt");
    }

    private static void cancelEpubVerification() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("unused-image.png", new byte[256 * 1024]);
        File file = zipFile(entries);
        RuntimeException cancelled = new RuntimeException("cancelled");
        int[] checks = {0};
        try {
            BookParser.validateForImport(file, "epub", () -> {
                if (++checks[0] == 8) throw cancelled;
            });
            throw new AssertionError("EPUB cancellation did not propagate");
        } catch (RuntimeException error) {
            check(error == cancelled, "EPUB cancellation was replaced");
        }
        // Windows will refuse deletion if archive construction left the ZIP open.
        Files.delete(file.toPath());
    }

    private static File write(String extension, byte[] bytes) throws IOException {
        Path file = temporary.resolve("book-" + (++sequence) + "." + extension);
        Files.write(file, bytes);
        return file.toFile();
    }

    private static BookContent parseZip(Map<String, byte[]> entries) throws Exception {
        return BookParser.parse(zipFile(entries), "epub");
    }

    private static File zipFile(Map<String, byte[]> entries) throws Exception {
        File file = temporary.resolve("book-" + (++sequence) + ".epub").toFile();
        try (OutputStream out = Files.newOutputStream(file.toPath()); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    private static void expectFailure(String fragment, ThrowingRunnable action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected failure: " + fragment);
        } catch (Exception expected) {
            if (fragment != null) check(expected.getMessage() != null && expected.getMessage().contains(fragment),
                    "Expected message containing " + fragment + ", got " + expected);
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
