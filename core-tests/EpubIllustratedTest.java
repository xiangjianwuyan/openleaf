import org.openleaf.reader.core.BookParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline EPUB formatting/image and sanitization regressions, with synthetic fixtures only. */
public final class EpubIllustratedTest {
    private interface Check { void run() throws Exception; }
    private static int passed;
    private static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aL1sAAAAASUVORK5CYII=");
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void check(String title,Check test)throws Exception{test.run();passed++;System.out.println("PASS "+title);}
    private static Map<String,byte[]> entries(String body){
        Map<String,byte[]> files=new LinkedHashMap<>();
        put(files,"META-INF/container.xml","<container><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>");
        put(files,"OPS/book.opf","<package><metadata><title>图文测试</title></metadata><manifest>"
                +"<item id='c' href='text/ch1.xhtml' media-type='application/xhtml+xml'/>"
                +"<item id='p' href='images/one.png' media-type='image/png'/>"
                +"<item id='style' href='styles/book.css' media-type='text/css'/></manifest><spine><itemref idref='c'/></spine></package>");
        put(files,"OPS/text/ch1.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><head><title>第一章</title></head><body>"+body+"</body></html>");
        put(files,"OPS/styles/book.css","h1 {text-align:center} .center {text-align:center;} p.indent {text-indent:2em;line-height:1.8;} #mark {font-weight:bold}");
        files.put("OPS/images/one.png",PNG);return files;
    }
    private static void put(Map<String,byte[]> entries,String path,String text){entries.put(path,text.getBytes(StandardCharsets.UTF_8));}
    private static File epub(Map<String,byte[]> entries)throws Exception{
        File file=File.createTempFile("openleaf-rich-",".epub");file.deleteOnExit();
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(file.toPath()))){
            for(Map.Entry<String,byte[]> entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}
        }
        return file;
    }
    private static BookParser.IllustratedBook open(String body)throws Exception{return BookParser.openIllustrated(epub(entries(body)));}
    public static void main(String[] args)throws Exception{
        check("semantic formatting and table/ruby retention",()->{
            try(BookParser.IllustratedBook book=open("<h1>正文</h1><p><strong>粗体</strong><em>斜体</em><br/>换行</p><ol start='3'><li>列表</li></ol><blockquote>引用</blockquote><table><tr><td colspan='2'>表格</td></tr></table><ruby>字<rt>zi</rt></ruby>")){
                String html=book.html(0);require(book.size()==1&&book.bookTitle.equals("图文测试"),"metadata/spine incorrect");
                // 元素现带 data-cfi 锚点属性，标签不再以 ">" 紧跟；用属性前缀匹配
                for(String markup:new String[]{"<strong","<em","<br","<ol","<blockquote","<td","<ruby","<rt"})require(html.contains(markup),"lost formatting: "+markup);
                require(html.contains("start=\"3\"")&&html.contains("colspan=\"2\""),"numeric attributes lost");
            }
        });
        check("allowlisted stylesheet rules and inline precedence",()->{
            try(BookParser.IllustratedBook book=open("<h1>标题</h1><p class='indent center' id='mark' style='text-align:right'>内容</p>")){
                String html=book.html(0);require(html.contains("text-indent:2em;")&&html.contains("line-height:1.8;")&&html.contains("font-weight:bold;")&&html.contains("text-align:right;"),"basic CSS not applied");
            }
        });
        check("embedded style text is parsed and never executed raw",()->{
            try(BookParser.IllustratedBook book=open("<style>.note{text-align:center;color:red;}</style><p class='note'>说明</p>")){
                String html=book.html(0);require(html.contains("text-align:center")&&html.contains("color:red")&&!html.contains("<style"),"style unsafe or ignored");
            }
        });
        check("relative raster image mapped to opaque token",()->{
            try(BookParser.IllustratedBook book=open("<p>插图前</p><img src='../images/one.png' alt='一幅图'/><p>插图后</p>")){
                String html=book.html(0);require(html.contains("src=\"image/0\"")&&!html.contains("../images"),"original image path leaked");
                BookParser.EpubImage image=book.image("0");require(image!=null&&image.mime.equals("image/png")&&java.util.Arrays.equals(image.bytes,PNG),"raster not served");
                require(book.image("../OPS/images/one.png")==null&&book.image("999")==null,"non-token resource accessible");
            }
        });
        check("SVG wrapper raster image supported without SVG script",()->{
            try(BookParser.IllustratedBook book=open("<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' onload='bad()'><image xlink:href='../images/one.png'/><script>bad()</script></svg>")){
                String html=book.html(0);require(html.contains("image/0")&&!html.contains("<svg")&&!html.contains("bad()"),"SVG wrapper unsanitized");
            }
        });
        check("scripts events objects frames links and network images stripped",()->{
            try(BookParser.IllustratedBook book=open("<script>alert('unsafe')</script><p onclick='bad()' style='background:url(https://evil.test/p);position:fixed'>safe</p><iframe src='https://evil.test'></iframe><a href='javascript:bad()'>label</a><img src='https://evil.test/p.png' onerror='bad()'/><object data='file:///etc/passwd'>hidden</object>")){
                String html=book.html(0);for(String bad:new String[]{"<script","onclick","onerror","https://","javascript:","file:","<iframe","<object","bad()","hidden","position:"})require(!html.contains(bad),"unsafe output: "+bad);
                require(html.contains("safe")&&html.contains("label")&&html.contains("图片不支持或不可用"),"safe text or unavailable notice lost");
            }
        });
        check("image alt text is escaped",()->{
            try(BookParser.IllustratedBook book=open("<img src='../images/one.png' alt='&quot;&lt;script&gt;'/>")){
                String html=book.html(0);require(html.contains("&quot;&lt;script&gt;")&&!html.contains("<script>"),"alt became markup");
            }
        });
        check("image MIME signature mismatch is refused",()->{
            Map<String,byte[]> files=entries("<img src='../images/one.png'/>");put(files,"OPS/images/one.png","<svg onload='bad()'/>");
            try(BookParser.IllustratedBook book=BookParser.openIllustrated(epub(files))){book.html(0);require(book.image("0")==null,"non-raster payload served");}
        });
        check("image-only EPUB imports as explicit illustrated placeholder",()->{
            File file=epub(entries("<img src='../images/one.png'/>"));BookParser.BookContent plain=BookParser.parse(file,"epub");
            require(plain.chapters.size()==1&&plain.chapters.get(0).text.contains("图文章节"),"image-only EPUB rejected or fake prose inserted");
        });
        check("external-only images do not make an empty book readable",()->{
            boolean rejected=false;try{BookParser.parse(epub(entries("<img src='https://example.test/p.png'/>")),"epub");}catch(Exception expected){rejected=true;}
            require(rejected,"external-only empty EPUB accepted");
        });
        check("traversal image ref cannot address outside ZIP root",()->{
            try(BookParser.IllustratedBook book=open("<img src='../../../../outside.png'/><p>正文</p>")){
                require(!book.html(0).contains("image/0"),"traversal image resolved");
            }
        });
        check("optional external stylesheet and manifest image never trigger network",()->{
            Map<String,byte[]> files=entries("<p>离线正文</p><img src='https://example.test/remote.png'/>");
            String opf=new String(files.get("OPS/book.opf"),StandardCharsets.UTF_8).replace("</manifest>","<item id='remote' href='https://example.test/remote.png' media-type='image/png'/><item id='remote-style' href='https://example.test/style.css' media-type='text/css'/></manifest>");
            put(files,"OPS/book.opf",opf);
            try(BookParser.IllustratedBook book=BookParser.openIllustrated(epub(files))){String html=book.html(0);require(html.contains("离线正文")&&!html.contains("https://"),"optional remote resource broke offline text");}
        });
        check("DRM remains rejected in illustrated mode",()->{
            Map<String,byte[]> files=entries("<p>text</p>");put(files,"META-INF/rights.xml","<rights/>");boolean rejected=false;
            try(BookParser.IllustratedBook book=BookParser.openIllustrated(epub(files))){book.html(0);}catch(Exception expected){rejected=true;}
            require(rejected,"DRM illustrated path bypassed");
        });
        check("repeat viewing and closed lifetime",()->{
            BookParser.IllustratedBook book=open("<img src='../images/one.png'/><p>text</p>");
            for(int i=0;i<10;i++){require(book.html(0).contains("image/0"),"repeat token changed");require(book.image("0")!=null,"repeat access exhausted import budget");}
            book.close();book.close();boolean rejected=false;try{book.html(0);}catch(Exception expected){rejected=true;}require(rejected,"closed resource still served");
        });
        System.out.println("PASS: "+passed+" illustrated EPUB checks");
    }
}
