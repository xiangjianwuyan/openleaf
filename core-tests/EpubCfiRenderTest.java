import org.openleaf.reader.core.BookParser;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;

/** 原书 CSS 开关 + data-cfi 结构锚点测试。 */
public class EpubCfiRenderTest {
    static int passed = 0;
    static void check(boolean ok, String msg) { if(!ok) throw new AssertionError(msg); passed++; }

    static Map<String,String> entries(String body) {
        Map<String,String> e = new LinkedHashMap<>();
        e.put("mimetype", "application/epub+zip");
        e.put("META-INF/container.xml", "<?xml version='1.0'?><container xmlns='urn:oasis:names:tc:opendocument:xmlns:container' version='1.0'><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>");
        e.put("OPS/book.opf", "<?xml version='1.0'?><package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='id'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>CFI Test</dc:title></metadata><manifest><item id='c1' href='c1.xhtml' media-type='application/xhtml+xml'/><item id='st' href='style.css' media-type='text/css'/></manifest><spine><itemref idref='c1'/></spine></package>");
        e.put("OPS/style.css", "p {text-indent:2em} h1 {text-align:center;color:red}");
        e.put("OPS/c1.xhtml", "<?xml version='1.0'?><html xmlns='http://www.w3.org/1999/xhtml'><head><title>x</title></head><body>" + body + "</body></html>");
        return e;
    }

    static BookParser.IllustratedBook open(Map<String,String> e) throws Exception {
        File f = File.createTempFile("openleaf-cfi-", ".epub");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            for (Map.Entry<String,String> en : e.entrySet()) {
                z.putNextEntry(new ZipEntry(en.getKey()));
                z.write(en.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return BookParser.openIllustrated(f);
    }

    public static void main(String[] a) throws Exception {
        BookParser.IllustratedBook book = open(entries("<h1>标题</h1><p>段落一</p><p id='m'>段落二</p>"));
        try {
            String withCss = book.html(0, true);
            String plain = book.html(0, false);
            // data-cfi 锚点
            check(withCss.contains("data-cfi="), "应输出 data-cfi 锚点");
            check(plain.contains("data-cfi="), "关闭CSS时仍应输出锚点");
            // 锚点唯一性：h1/p/p 应有不同 cfi
            int cfiCount = countOccurrences(withCss, "data-cfi=");
            check(cfiCount >= 4, "应至少 body + 3 元素各一个锚点，实际 " + cfiCount);
            // CSS 开关
            check(withCss.contains("text-indent:2em"), "开CSS应含原书样式");
            check(!plain.contains("text-indent:2em"), "关CSS不应含原书样式");
            check(withCss.contains("text-align:center"), "开CSS应含标题居中");
            check(!plain.contains("text-align:center"), "关CSS不应含标题居中");
            // CSS 白名单：color 现为允许属性（§9.2）
            check(withCss.contains("color:red"), "白名单 color 应被保留");
            // 默认 html(index) 与开CSS一致
            check(book.html(0).contains("text-indent:2em"), "默认应套用原书CSS");
            System.out.println("PASS EpubCfiRenderTest (" + passed + " checks)");
        } finally { book.close(); }
    }

    static int countOccurrences(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
    }
}
