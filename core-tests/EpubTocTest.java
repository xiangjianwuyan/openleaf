import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.EpubChapterIndexer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** EPUB TOC labels must never reorder or multiply spine entries. */
public final class EpubTocTest {
    private static final String NAV = "<item id='nav' href='Navigation/nav.xhtml' media-type='application/xhtml+xml' properties='scripted nav'/>";
    private static final String NCX = "<item id='ncx' href='Navigation/toc.ncx' media-type='application/x-dtbncx+xml'/>";
    private static Path cacheDir;

    public static void main(String[] args) throws Exception {
        cacheDir = Files.createTempDirectory("openleaf-epub-toc-index-");
        try {
            navAndNcx();
            malformedNavFallsBack();
            splitsAndEmptySpineEntriesKeepIdentity();
            System.out.println("PASS: 3 EPUB TOC checks");
        } finally {
            try (java.util.stream.Stream<Path> s = Files.walk(cacheDir)) {
                for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
            }
        }
    }

    private static void navAndNcx() throws Exception {
        Map<String, String> files = entries(NAV + NCX, " toc='ncx'");
        files.put("OPS/Navigation/nav.xhtml", "<html xmlns:epub='http://www.idpf.org/2007/ops'><body>"
            + "<nav epub:type='landmarks'><ol><li><a href='../Chapters/one%20space.xhtml'>Not a chapter label</a></li></ol></nav>"
            + "<nav epub:type='toc'><ol><li><a href='../Chapters/two.xhtml#x'><span>Second</span> label</a></li>"
            + "<li><a href='../Chapters/one%20space.xhtml#first'>First label</a></li>"
            + "<li><a href='../Chapters/one%20space.xhtml#duplicate'>Another anchor</a></li>"
            + "<li><a href='https://example.invalid/three.xhtml'>External</a></li>"
            + "<li><a href='../../../OPS/Chapters/three.xhtml'>Traversal</a></li></ol></nav></body></html>");
        files.put("OPS/Navigation/toc.ncx", ncx("<navPoint><navLabel><text>NCX second</text></navLabel><content src='../Chapters/two.xhtml'/>"
            + "<navPoint><navLabel><text>Third label</text></navLabel><content src='../Chapters/three.xhtml'/></navPoint></navPoint>"));
        verify(files, "First label", "Second label", "Third label");
    }

    private static void malformedNavFallsBack() throws Exception {
        Map<String, String> files = entries(NAV + NCX, " toc='ncx'");
        files.put("OPS/Navigation/nav.xhtml", "<html><body><nav role='doc-toc'><a href='../Chapters/one%20space.xhtml'>Broken");
        files.put("OPS/Navigation/toc.ncx", ncx("<navPoint><navLabel><text>NCX title</text></navLabel><content src='../Chapters/one%20space.xhtml'/></navPoint>"));
        verify(files, "NCX title", "XHTML two", "第3章");
    }

    private static void splitsAndEmptySpineEntriesKeepIdentity() throws Exception {
        Map<String, String> files = entries("", "");
        files.put("OPS/Chapters/two.xhtml", "<html><body>  </body></html>");
        StringBuilder longText = new StringBuilder("<html><body><h1>Long</h1><p>");
        for (int i = 0; i < BookParser.MAX_CHAPTER_CHARS + 50; i++) longText.append('a');
        files.put("OPS/Chapters/one space.xhtml", longText.append("</p></body></html>").toString());
        File epub = epub(files);
        try {
            BookParser.BookContent plain = BookParser.parse(epub, "epub");
            require(plain.chapters.size() == 3, "unexpected plain chapter count");
            require(plain.chapters.get(0).spineIndex == 0 && plain.chapters.get(1).spineIndex == 0
                    && plain.chapters.get(2).spineIndex == 2, "split or empty spine shifted mapping");
            try (BookParser.IllustratedBook rich = BookParser.openIllustrated(epub)) {
                require(rich.size() == 3 && rich.title(2).equals(plain.chapters.get(2).title), "rich spine no longer aligned");
            }
            EpubChapterIndexer.Result indexed = EpubChapterIndexer.index(epub, cacheDir.toFile());
            try {
                require(indexed.chapters.size() == 3, "streaming split/empty mapping changed");
                require(indexed.chapters.get(0).spineIndex == 0 && indexed.chapters.get(1).spineIndex == 0
                        && indexed.chapters.get(2).spineIndex == 2, "streaming spineIndex shifted");
                for (int i = 0; i < 3; i++) {
                    require(indexed.chapters.get(i).title.equals(plain.chapters.get(i).title),
                            "streaming title differs at " + i);
                    require(indexed.chapters.get(i).sourceStart == -1, "streaming sourceStart not -1");
                }
            } finally { Files.deleteIfExists(indexed.cacheFile.toPath()); }
        } finally { Files.deleteIfExists(epub.toPath()); }
        require(new BookParser.Chapter("test", "test").spineIndex == -1, "legacy constructor has spine index");
    }

    private static String ncx(String points) {
        return "<!DOCTYPE ncx PUBLIC '-//NISO//DTD ncx 2005-1//EN' 'http://www.daisy.org/z3986/2005/ncx-2005-1.dtd'>"
            + "<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap>" + points + "</navMap></ncx>";
    }

    private static Map<String, String> entries(String extra, String spineAttributes) {
        Map<String, String> files = new LinkedHashMap<String, String>();
        files.put("META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>");
        files.put("OPS/book.opf", "<package><metadata><title>Sample</title></metadata><manifest>"
            + "<item id='a' href='Chapters/one%20space.xhtml' media-type='application/xhtml+xml'/>"
            + "<item id='b' href='Chapters/two.xhtml' media-type='application/xhtml+xml'/>"
            + "<item id='c' href='Chapters/three.xhtml' media-type='application/xhtml+xml'/>" + extra
            + "</manifest><spine" + spineAttributes + "><itemref idref='a'/><itemref idref='b'/><itemref idref='c'/></spine></package>");
        files.put("OPS/Chapters/one space.xhtml", "<html><body><h1>XHTML one</h1><p>Body one</p></body></html>");
        files.put("OPS/Chapters/two.xhtml", "<html><head><title>XHTML two</title></head><body><p>Body two</p></body></html>");
        files.put("OPS/Chapters/three.xhtml", "<html><body><p>Body three</p></body></html>");
        return files;
    }

    private static void verify(Map<String, String> files, String... labels) throws Exception {
        File epub = epub(files);
        try (BookParser.IllustratedBook rich = BookParser.openIllustrated(epub)) {
            BookParser.BookContent plain = BookParser.parse(epub, "epub");
            require(plain.chapters.size() == labels.length && rich.size() == labels.length, "TOC changed spine size");
            for (int i = 0; i < labels.length; i++) {
                BookParser.Chapter chapter = plain.chapters.get(i);
                require(labels[i].equals(chapter.title), "wrong plain label: " + chapter.title);
                require(labels[i].equals(rich.title(i)), "wrong rich label: " + rich.title(i));
                require(chapter.spineIndex == i, "wrong spine index");
            }
            EpubChapterIndexer.Result indexed = EpubChapterIndexer.index(epub, cacheDir.toFile());
            try {
                require(indexed.chapters.size() == labels.length, "streaming changed spine size");
                for (int i = 0; i < labels.length; i++) {
                    EpubChapterIndexer.ChapterMeta meta = indexed.chapters.get(i);
                    require(labels[i].equals(meta.title), "wrong streaming label: " + meta.title);
                    require(meta.spineIndex == i, "wrong streaming spine index");
                    require(meta.sourceStart == -1, "wrong streaming sourceStart");
                    require(meta.charCount == plain.chapters.get(i).text.length(), "wrong streaming charCount");
                }
            } finally { Files.deleteIfExists(indexed.cacheFile.toPath()); }
        } finally { Files.deleteIfExists(epub.toPath()); }
    }

    private static File epub(Map<String, String> files) throws Exception {
        File file = File.createTempFile("openleaf-toc-", ".epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
