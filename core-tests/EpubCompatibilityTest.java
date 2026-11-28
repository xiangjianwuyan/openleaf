import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.EpubChapterIndexer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Synthetic, dependency-free regressions for EPUB body recovery and local IRI compatibility. */
public final class EpubCompatibilityTest {
    private static final String CONTAINER = "META-INF/container.xml";
    private static final String OPF = "书 籍/主 文档.opf";
    private static final String CHAPTER = "书 籍/正文/第 一章.xhtml";
    private static final String IMAGE = "书 籍/图 片/插 图.png";
    private static final String CSS = "书 籍/样 式/原 样式.css";
    private static final String NCX = "书 籍/目录/目 录.ncx";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aL1sAAAAASUVORK5CYII=");
    private static Path temporary;
    private static int sequence, passed;
    private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        temporary = Files.createTempDirectory("openleaf-epub-compat-");
        try {
            test("crossed and missing body end tags recover without duplicate text", EpubCompatibilityTest::crossedTags);
            test("optional paragraph list and table endings stay balanced", EpubCompatibilityTest::optionalEnds);
            test("raw spaces Chinese paths CSS images and cover share one resolver", EpubCompatibilityTest::localResources);
            test("HTML void tags quoted greater-than and unquoted attributes", EpubCompatibilityTest::htmlSyntax);
            test("image-only recovered chapters import through streaming", EpubCompatibilityTest::imageOnly);
            test("prefixed XHTML retains namespace and raw script boundaries", EpubCompatibilityTest::prefixedXhtml);
            test("parse streaming and illustrated errors identify the inner resource", EpubCompatibilityTest::resourceContext);
            test("unknown entities and malformed declarations stay rejected", EpubCompatibilityTest::entities);
            test("container and OPF never use HTML recovery", EpubCompatibilityTest::strictMetadata);
            test("broken NCX stays optional strict XML", EpubCompatibilityTest::strictNcx);
            test("raw-space compatibility never enables external or escaping paths", EpubCompatibilityTest::unsafeReferences);
            test("unsafe optional images and styles stay unavailable", EpubCompatibilityTest::unsafeOptionalResources);
            test("CDATA comments and Unicode survive body recovery", EpubCompatibilityTest::literalContent);
            test("recovered script and event content cannot enter illustrated output", EpubCompatibilityTest::sanitization);
            test("depth entry-size and illustrated output budgets remain enforced", EpubCompatibilityTest::limits);
            System.out.println("PASS: " + passed + " EPUB compatibility checks");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException error) { throw new RuntimeException(error); }
                });
            }
        }
    }

    private static void crossedTags() throws Exception {
        Map<String, byte[]> files = entries("<h1>修复章</h1><p>开头<strong>加粗</p>尾部</strong><p>第二段𠀀");
        // Also omit the body/html end tags, which common HTML producers leave implicit.
        put(files, CHAPTER, new String(files.get(CHAPTER), StandardCharsets.UTF_8).replace("</body></html>", ""));
        File file = epub(files);
        BookParser.BookContent parsed = parity(file);
        equal("修复章", parsed.chapters.get(0).title);
        equal("修复章\n\n开头加粗\n\n尾部\n\n第二段𠀀", parsed.chapters.get(0).text);
        try (BookParser.IllustratedBook book = BookParser.openIllustrated(file)) {
            String html = book.html(0);
            require(html.contains("加粗</strong></p>尾部"), "crossed tags did not balance: " + html);
            equal(html, book.html(0));
            require(count(html, "开头") == 1, "retry duplicated partial SAX output");
            balanced(html);
        }
    }

    private static void optionalEnds() throws Exception {
        File file = epub(entries("<h1>标题</h1><p>第一段<p>第二段<ul><li>甲<li>乙<ul><li>子项</ul></ul>"
                + "<table><tr><td>一<td>二<tr><td>三</table>"));
        BookParser.BookContent parsed = parity(file);
        require(parsed.chapters.get(0).text.contains("第一段\n\n第二段"), "implicit paragraph endings merged");
        try (BookParser.IllustratedBook book = BookParser.openIllustrated(file)) {
            String html = book.html(0);
            require(count(html, "<li ") == 3 && count(html, "<td ") == 3, "list/cell lost");
            require(html.contains("甲</li><li "), "sibling list items nested");
            balanced(html);
        }
    }

    private static void localResources() throws Exception {
        File file = epub(entries("<h1>资源章</h1><p class='note'>中文资源</p><img src='../图 片/插%20图.png'/>") );
        equal("兼容测试", BookParser.validateForImport(file, "epub", null));
        parity(file);
        try (BookParser.IllustratedBook book = BookParser.openIllustrated(file)) {
            String html = book.html(0);
            require(html.contains("text-align:center;") && html.contains("image/0"), "raw-space CSS or image unavailable");
            require(Arrays.equals(PNG, book.image("0").bytes), "raw/escaped mixed image reference failed");
            require(!html.contains("图 片"), "archive path leaked instead of token");
        }
        BookParser.CoverData cover = BookParser.discoverCover(file);
        require(cover != null && Arrays.equals(PNG, cover.bytes), "raw-space cover manifest href failed");
        Map<String, byte[]> encoded = entries("<p>转义路径</p>");
        put(encoded, CONTAINER, text(encoded, CONTAINER).replace(OPF, "书%20籍/主%20文档.opf"));
        put(encoded, OPF, text(encoded, OPF).replace("正文/第 一章.xhtml", "正文/第%20一章.xhtml#锚 点"));
        equal("转义路径", parity(epub(encoded)).chapters.get(0).text);
    }

    private static void htmlSyntax() throws Exception {
        Map<String, byte[]> files = entries("<H1>HTML 正文</H1><P class=note title='甲 > 乙'>甲 & 乙<BR>换行"
                + "<IMG src='../图 片/插 图.png' alt='甲 > 乙'><INPUT disabled><WBR>结尾");
        put(files, OPF, text(files, OPF).replace("application/xhtml+xml", "text/html"));
        File file = epub(files);
        String plain = parity(file).chapters.get(0).text;
        require(plain.contains("甲 & 乙\n换行") && plain.contains("结尾"), "HTML text or break lost: " + plain);
        try (BookParser.IllustratedBook book = BookParser.openIllustrated(file)) {
            String html = book.html(0);
            require(html.contains("alt=\"甲 &gt; 乙\"") && html.contains("image/0"), "quoted > damaged image tag");
            require(html.contains("text-align:center;") && !html.contains("<input"), "unquoted class or sanitizer failed");
            balanced(html);
        }
    }

    private static void imageOnly() throws Exception {
        BookParser.BookContent parsed = parity(epub(entries("<p><img src='../图 片/插 图.png'>")));
        require(parsed.chapters.size() == 1 && parsed.chapters.get(0).text.contains("图文章节"), "image-only import failed");
    }

    private static void prefixedXhtml() throws Exception {
        Map<String, byte[]> files = entries("");
        put(files, CHAPTER, "<x:html xmlns:x='http://www.w3.org/1999/xhtml'><x:head><x:title>前缀章</x:title></x:head>"
                + "<x:body><x:p>前文<x:script>if (a < b) bad(); </x:p> stillHidden()</x:script>"
                + "<x:strong>加粗</x:p><x:img src='../图 片/插 图.png'></x:body></x:html>");
        File file = epub(files);
        String plain = parity(file).chapters.get(0).text;
        require(plain.contains("前文加粗") && !plain.contains("stillHidden"), "prefixed script boundary escaped");
        try (BookParser.IllustratedBook book = BookParser.openIllustrated(file)) {
            String html = book.html(0);
            require(html.contains("image/0") && html.contains("<strong") && !html.contains("bad()")
                    && !html.contains("stillHidden"), "prefixed XHTML recovery failed: " + html);
            balanced(html);
        }
    }

    private static void resourceContext() throws Exception {
        File file = epub(entries("<p><strong>正文</p>&unknown;"));
        rejectReading(file, CHAPTER);
        Map<String, byte[]> files = entries("<p>正文</p>");
        put(files, OPF, text(files, OPF).replace("</manifest>", ""));
        rejectReading(epub(files), OPF);
        files = entries("<p>正文</p>");
        put(files, CONTAINER, text(files, CONTAINER).replace("</rootfiles>", ""));
        rejectReading(epub(files), CONTAINER);
    }

    private static void entities() throws Exception {
        for (String declaration : new String[] {
                "<!DOCTYPE html SYSTEM 'file:///do-not-open'>",
                "<!DOCTYPE html [<!ENTITY xxe SYSTEM 'http://127.0.0.1:9/no-network'>]>",
                "<!DOCTYPE html [<!ENTITY a 'AAAA'><!ENTITY b '&a;&a;&a;'>]>",
                "<!ENTITY secret 'no'>"}) {
            Map<String, byte[]> files = entries("<p><b>正文</p>&xxe;");
            put(files, CHAPTER, declaration + text(files, CHAPTER));
            rejectReading(epub(files), "禁止 DTD");
        }
        for (String entity : new String[] { "&unknown;", "&#0;", "&#xD800;", "&#x110000;" }) {
            rejectReading(epub(entries("<p><b>正文</p>" + entity)), null);
        }
        Map<String, byte[]> files = entries("<p><b>&nbsp;中文&mdash;&copy;&#x20000;</p>");
        put(files, CHAPTER, "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'>"
                + text(files, CHAPTER));
        require(parity(epub(files)).chapters.get(0).text.contains("中文—©𠀀"), "safe standard entities or DTD lost");
    }

    private static void strictMetadata() throws Exception {
        for (String path : new String[] { CONTAINER, OPF }) {
            Map<String, byte[]> files = entries("<p><b>正文</p>");
            String original = text(files, path);
            put(files, path, original.replace(path.equals(CONTAINER) ? "</rootfiles>" : "</manifest>", ""));
            File file = epub(files);
            expectFailure(() -> BookParser.validateForImport(file, "epub", null), "XML 解析失败");
            rejectReading(file, "XML 解析失败");
        }
        Map<String, byte[]> files = entries("<p>正文</p>");
        put(files, OPF, text(files, OPF).replace("<title>兼容测试</title>", "<title>兼容&nbsp;测试</title>"));
        rejectReading(epub(files), "XML 解析失败");
        files = entries("<p>正文</p>");
        put(files, OPF, text(files, OPF).replace("<metadata>", "<metadata><br>"));
        rejectReading(epub(files), "XML 解析失败");
        files = entries("<p>正文</p>");
        put(files, CHAPTER, "<package><p>并非 HTML 文档</package>");
        rejectReading(epub(files), null);
    }

    private static void strictNcx() throws Exception {
        Map<String, byte[]> files = entries("<h1>正文标题</h1><p>正文</p>");
        put(files, NCX, "<ncx><navMap><navPoint><navLabel><text>不应恢复的目录名</text></navLabel>"
                + "<content src='../正文/第 一章.xhtml'/></navMap></ncx>");
        equal("正文标题", parity(epub(files)).chapters.get(0).title);
        put(files, NCX, "<!DOCTYPE ncx PUBLIC '-//NISO//DTD ncx 2005-1//EN' 'http://www.daisy.org/z3986/2005/ncx-2005-1.dtd'>"
                + "<ncx><navMap><navPoint><navLabel><text>正确目录名</text></navLabel>"
                + "<content src='../正文/第 一章.xhtml'/></navPoint></navMap></ncx>");
        equal("正确目录名", parity(epub(files)).chapters.get(0).title);
    }

    private static void unsafeReferences() throws Exception {
        for (String reference : new String[] { "../../逃 逸.xhtml", "%2e%2e/%2e%2e/逃 逸.xhtml",
                "%2F逃 逸.xhtml", "//example.test/中 文.xhtml", "https://example.test/中 文.xhtml",
                "file:///中 文.xhtml", "C:/中 文.xhtml", "正文/第 一章.xhtml?x=1", "正文/%5c逃 逸.xhtml",
                "正文/%00逃 逸.xhtml", "正文/bad%escape.xhtml" }) {
            Map<String, byte[]> files = entries("<p>正文</p>");
            put(files, OPF, text(files, OPF).replace("href='正文/第 一章.xhtml'", "href='" + reference + "'"));
            rejectReading(epub(files), null);
        }
        Map<String, byte[]> files = entries("<p>正文</p>");
        put(files, CONTAINER, text(files, CONTAINER).replace(OPF, "../逃 逸.opf"));
        File file = epub(files);
        expectFailure(() -> BookParser.validateForImport(file, "epub", null), "路径穿越");
        rejectReading(file, "路径穿越");
        for (String path : new String[] { "../逃 逸.bin", "书 籍/../逃 逸.bin", "C:/逃 逸.bin", "书 籍\\逃 逸.bin" }) {
            files = entries("<p>正文</p>");
            put(files, path, "unused");
            rejectReading(epub(files), "路径");
        }
    }

    private static void unsafeOptionalResources() throws Exception {
        Map<String, byte[]> files = entries("<p>正文</p><img src='https://example.test/中 文.png'>"
                + "<img src='../../../逃 逸.png'><img src='%2f逃 逸.png'>");
        put(files, OPF, text(files, OPF).replace("href='图 片/插 图.png'", "href='https://example.test/中 文.png'")
                .replace("href='样 式/原 样式.css'", "href='https://example.test/中 文.css'"));
        File file = epub(files);
        parity(file);
        try (BookParser.IllustratedBook book = BookParser.openIllustrated(file)) {
            String html = book.html(0);
            require(!html.contains("image/0") && !html.contains("https:") && !html.contains("text-align:center"), "unsafe resource loaded");
            require(book.image("0") == null, "unsafe image got a token");
        }
    }

    private static void literalContent() throws Exception {
        File file = epub(entries("<!-- <!DOCTYPE fake> --><p><![CDATA[&nbsp; <字>]]><b>𠀀中文</p><p>后文</p>"));
        String plain = parity(file).chapters.get(0).text;
        require(plain.contains("&nbsp; <字>𠀀中文") && plain.endsWith("后文"), "CDATA or Unicode changed: " + plain);
        try (BookParser.IllustratedBook book = BookParser.openIllustrated(file)) {
            String html = book.html(0);
            require(html.contains("&amp;nbsp; &lt;字&gt;") && html.contains("𠀀中文"), "literal text turned into markup: " + html);
        }
    }

    private static void sanitization() throws Exception {
        File file = epub(entries("<script>if (a < b && c) bad()</script><p onclick=bad()>安全<strong>粗体</p>"
                + "<a href='javascript:bad()'>链接</a><iframe src='https://example.test'>隐藏</iframe>"
                + "<style>.note{background:url(https://example.test/x)}</style>"));
        String plain = parity(file).chapters.get(0).text;
        require(!plain.contains("bad()"), "script leaked to plain text");
        try (BookParser.IllustratedBook book = BookParser.openIllustrated(file)) {
            String html = book.html(0);
            for (String unsafe : new String[] { "<script", "onclick", "bad()", "javascript:", "https:", "<iframe", "隐藏", "<style" })
                require(!html.contains(unsafe), "unsafe rendered content: " + unsafe);
            require(html.contains("安全") && html.contains("链接"), "safe body disappeared");
            balanced(html);
        }
    }

    private static void limits() throws Exception {
        rejectReading(epub(entries("<div>".repeat(300) + "正文</p>")), "嵌套过深");
        Map<String, byte[]> files = entries("<p>正文</p>");
        files.put("unused.bin", new byte[(int) BookParser.MAX_EPUB_ENTRY_BYTES + 1]);
        rejectReading(epub(files), "单项展开");
        File file = epub(entries("<p><b>" + "字".repeat(4 * 1024 * 1024 + 1) + "</p>"));
        try (BookParser.IllustratedBook book = BookParser.openIllustrated(file)) {
            expectFailure(() -> {
                try { book.html(0); }
                catch (IOException error) {
                    require(error.getMessage().contains(CHAPTER), "render error omitted resource path");
                    throw error;
                }
            }, "图文章节过大");
        }
        File amplified = epub(entries("<script>" + "\"".repeat(9 * 1024 * 1024) + "</script><p><b>正文</p>"));
        expectFailure(() -> BookParser.parse(amplified, "epub"), "正文修复结果过大");
    }

    private static BookParser.BookContent parity(File file) throws Exception {
        BookParser.BookContent parsed = BookParser.parse(file, "epub");
        List<String> chunks = new ArrayList<String>();
        List<String> titles = new ArrayList<String>();
        List<Integer> indexes = new ArrayList<Integer>();
        equal(parsed.title, BookParser.indexEpub(file, (title, text, spine) -> {
            titles.add(title); chunks.add(text); indexes.add(spine);
        }));
        equal(parsed.chapters.size(), chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            equal(parsed.chapters.get(i).title, titles.get(i));
            equal(parsed.chapters.get(i).text, chunks.get(i));
            equal(parsed.chapters.get(i).spineIndex, indexes.get(i));
        }
        EpubChapterIndexer.Result indexed = EpubChapterIndexer.index(file, temporary.toFile());
        equal(parsed.title, indexed.title);
        equal(parsed.chapters.size(), indexed.chapters.size());
        StringBuilder restored = new StringBuilder();
        for (BookParser.Chapter chapter : parsed.chapters) restored.append(chapter.text);
        equal(restored.toString(), new String(Files.readAllBytes(indexed.cacheFile.toPath()), StandardCharsets.UTF_16LE));
        return parsed;
    }

    private static void rejectReading(File file, String message) throws Exception {
        expectFailure(() -> BookParser.parse(file, "epub"), message);
        expectFailure(() -> BookParser.indexEpub(file, (title, text, spine) -> { }), message);
        expectFailure(() -> { try (BookParser.IllustratedBook book = BookParser.openIllustrated(file)) { book.html(0); } }, message);
    }

    private static void expectFailure(Check test, String message) throws Exception {
        try { test.run(); }
        catch (IOException expected) {
            require(message == null || expected.getMessage().contains(message), "unexpected failure: " + expected);
            return;
        }
        throw new AssertionError("expected rejection: " + message);
    }

    private static Map<String, byte[]> entries(String body) {
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        put(files, CONTAINER, "<container><rootfiles><rootfile full-path='" + OPF
                + "' media-type='application/oebps-package+xml'/></rootfiles></container>");
        put(files, OPF, "<package><metadata><title>兼容测试</title></metadata><manifest>"
                + "<item id='c' href='正文/第 一章.xhtml' media-type='application/xhtml+xml'/>"
                + "<item id='img' href='图 片/插 图.png' media-type='image/png' properties='cover-image'/>"
                + "<item id='css' href='样 式/原 样式.css' media-type='text/css'/>"
                + "<item id='ncx' href='目录/目 录.ncx' media-type='application/x-dtbncx+xml'/>"
                + "</manifest><spine toc='ncx'><itemref idref='c'/></spine></package>");
        put(files, CHAPTER, "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>备用标题</title></head><body>"
                + body + "</body></html>");
        put(files, CSS, ".note{text-align:center;}");
        put(files, NCX, "<ncx><navMap/></ncx>");
        files.put(IMAGE, PNG);
        return files;
    }

    private static File epub(Map<String, byte[]> files) throws Exception {
        Path file = temporary.resolve("sample-" + sequence++ + ".epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file.toFile();
    }

    private static void balanced(String html) throws Exception {
        // The renderer intentionally emits HTML br/hr void tags. Canonicalize only those
        // already-sanitized tags so XML parsing can verify all surrounding element balance.
        html = html.replaceAll("<(br|hr)(\\s[^>]*)?>", "<$1$2/>");
        javax.xml.parsers.SAXParserFactory factory = javax.xml.parsers.SAXParserFactory.newInstance();
        factory.newSAXParser().parse(new org.xml.sax.InputSource(new java.io.StringReader("<root>" + html + "</root>")),
                new org.xml.sax.helpers.DefaultHandler());
    }

    private static int count(String text, String part) { return (text.length() - text.replace(part, "").length()) / part.length(); }
    private static String text(Map<String, byte[]> files, String path) { return new String(files.get(path), StandardCharsets.UTF_8); }
    private static void put(Map<String, byte[]> files, String path, String text) { files.put(path, text.getBytes(StandardCharsets.UTF_8)); }
    private static void equal(Object expected, Object actual) { require(expected.equals(actual), "expected " + expected + ", got " + actual); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void test(String name, Check check) throws Exception { check.run(); passed++; System.out.println("PASS " + name); }
}
