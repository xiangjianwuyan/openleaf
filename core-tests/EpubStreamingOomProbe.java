import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.LazyChapterBook;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 受限堆 OOM 对照探针（仅测试代码，不依赖 JUnit / Android）。
 *
 * 用法（堆档通过 JVM -Xmx 设定，探针自身打印实测 maxMemory 作为证据）：
 *   java -Xmx512m ... EpubStreamingOomProbe gen s2 <out.epub>
 *   java -Xmx512m ... EpubStreamingOomProbe gen s4 <out.epub>
 *   java -Xmx512m ... EpubStreamingOomProbe gen s4over <out.epub>
 *   java -Xmx512m ... EpubStreamingOomProbe gen s6 <out.epub>
 *   java -Xmx64m  ... EpubStreamingOomProbe old <sample.epub>   # 期望 OOM
 *   java -Xmx64m  ... EpubStreamingOomProbe new <sample.epub>   # 期望正常完成
 *
 * old 分支复刻 LazyChapterBook.open() 旧路径的峰值：BookParser.parse() 全量章节 +
 * 每章 getBytes(UTF_16LE) 常驻；new 分支走 EpubChapterIndexer + LazyChapterBook.open()/load()。
 * 两分支读同一份样本、同一 JVM 参数，只切换路径，保证对照有效。
 *
 * 诚实边界：本对照只证明「全本累积峰值」被消除；单个 23MiB 条目的 XML 解码/预处理
 * 瞬态峰值（约 3~4 倍）仍在，需 -Xmx192m 档验证；-Xmx64m 下极限单条目仍可能 OOM。
 */
public final class EpubStreamingOomProbe {
    private static final long MIB = 1024L * 1024L;
    private static final String UNIT =
            "<p>这是一段重复的中文正文内容，用于构造可解压的大样本，验证流式索引与全量解析的内存峰值差异。</p>";
    /** S2 专用于放大 UTF-16 驻留：ASCII 主体让 1MiB 展开≈1M 码元，旧路径必然突破 64m。 */
    private static final String S2_UNIT = "<p>" + "a".repeat(240) + "中文段落</p>";

    private EpubStreamingOomProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: gen <s2|s4|s4over|s6> <out.epub> | old|new <sample.epub> [label]");
            System.exit(64);
            return;
        }
        String mode = args[0];
        String label = ("old".equals(mode) || "new".equals(mode)) && args.length >= 3 ? args[2] : "-Xmx?";
        System.out.println("PROBE mode=" + mode + " label=" + label
                + " heapMax=" + Runtime.getRuntime().maxMemory() + " bytes");
        if ("gen".equals(mode)) {
            generate(args[1], new File(args[2]));
        } else if ("old".equals(mode)) {
            runOld(new File(args[1]));
        } else if ("new".equals(mode)) {
            runNew(new File(args[1]));
        } else {
            throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    /** 旧路径：全量 parse + 逐章 UTF-16LE 编码常驻，复刻 open() 的全本累积峰值。 */
    private static void runOld(File epub) throws Exception {
        BookParser.BookContent book = BookParser.parse(epub, "epub");
        List<byte[]> retained = new ArrayList<byte[]>(book.chapters.size());
        long bytes = 0;
        for (BookParser.Chapter chapter : book.chapters) {
            byte[] encoded = chapter.text.getBytes(StandardCharsets.UTF_16LE);
            retained.add(encoded);
            bytes += encoded.length;
        }
        if (retained.isEmpty()) throw new AssertionError("old: no chapters");
        System.out.println("OLD PASS chapters=" + book.chapters.size()
                + " utf16Bytes=" + bytes + " retained=" + retained.size());
    }

    /** 新路径：流式索引 + 逐章 load，堆内只有单章瞬态与元数据。 */
    private static void runNew(File epub) throws Exception {
        Path cache = Files.createTempDirectory("openleaf-oom-probe-");
        try {
            try (LazyChapterBook book = LazyChapterBook.open(epub, "epub", cache.toFile())) {
                long chars = 0;
                for (int i = 0; i < book.size(); i++) {
                    String text = book.load(i);
                    if (text.isEmpty()) throw new AssertionError("new: empty chapter " + i);
                    chars += text.length();
                }
                System.out.println("NEW PASS chapters=" + book.size() + " chars=" + chars);
            }
        } finally {
            deleteRecursively(cache);
        }
    }

    /** 供 EpubChapterIndexerTest 复用同一份样本构造器，避免测试间样本漂移。 */
    public static void generate(String kind, File out) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        files.put("META-INF/container.xml", bytes("<?xml version='1.0' encoding='UTF-8'?>"
                + "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles>"
                + "<rootfile full-path='OPS/package.opf' media-type='application/oebps-package+xml'/>"
                + "</rootfiles></container>"));
        if ("s2".equals(kind)) {
            generateS2(files);
        } else if ("s4".equals(kind)) {
            generateS4(files, 23L * MIB);
        } else if ("s4over".equals(kind)) {
            generateS4(files, 24L * MIB + 4096L);
        } else if ("s6".equals(kind)) {
            generateS6(files);
        } else {
            throw new IllegalArgumentException("unknown sample kind: " + kind);
        }
        long expanded = 0;
        for (Map.Entry<String, byte[]> e : files.entrySet()) expanded += e.getValue().length;
        writeZip(files, out);
        System.out.println("GEN " + kind + " path=" + out.getAbsolutePath()
                + " expanded=" + expanded + " bytes zip=" + out.length() + " bytes");
    }

    /** S2：30 spine × 约 1MiB 展开（真实可解压内容，不用稀疏文件）。 */
    private static void generateS2(Map<String, byte[]> files) {
        StringBuilder items = new StringBuilder();
        StringBuilder spine = new StringBuilder();
        byte[] unit = S2_UNIT.getBytes(StandardCharsets.UTF_8);
        int repeats = (int) (MIB / unit.length) + 1;
        StringBuilder body = new StringBuilder(repeats * S2_UNIT.length());
        for (int i = 0; i < repeats; i++) body.append(S2_UNIT);
        byte[] chapterBase = ("<html><body><h1>第").getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 30; i++) {
            String id = "c" + i;
            items.append("<item id='").append(id).append("' href='c").append(i)
                    .append(".xhtml' media-type='application/xhtml+xml'/>");
            spine.append("<itemref idref='").append(id).append("'/>");
            String tail = (i + 1) + "章</h1>" + body + "</body></html>";
            files.put("OPS/c" + i + ".xhtml", concat(chapterBase, tail.getBytes(StandardCharsets.UTF_8)));
        }
        files.put("OPS/package.opf", opf(items.toString(), spine.toString()));
    }

    /** S4 / S4-over：单个 XHTML 条目逼近或超过 MAX_EPUB_ENTRY_BYTES。 */
    private static void generateS4(Map<String, byte[]> files, long targetBytes) {
        byte[] unit = UNIT.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream((int) targetBytes);
        while (body.size() + unit.length < targetBytes) body.write(unit, 0, unit.length);
        byte[] chapter = concat("<html><body><h1>巨章</h1>".getBytes(StandardCharsets.UTF_8),
                body.toByteArray(), "</body></html>".getBytes(StandardCharsets.UTF_8));
        files.put("OPS/package.opf", opf("<item id='one' href='big.xhtml' media-type='application/xhtml+xml'/>",
                "<itemref idref='one'/>"));
        files.put("OPS/big.xhtml", chapter);
    }

    /** S6：BOM + UTF-16LE 编码的 XHTML 条目。 */
    private static void generateS6(Map<String, byte[]> files) {
        String xml = "<html><head><title>编码探测</title></head><body><h1>UTF sixteen</h1>"
                + "<p>中文𠀀正文与 emoji 😀。</p></body></html>";
        files.put("OPS/package.opf", opf("<item id='one' href='utf16.xhtml' media-type='application/xhtml+xml'/>",
                "<itemref idref='one'/>"));
        files.put("OPS/utf16.xhtml", concat(new byte[]{(byte) 0xFF, (byte) 0xFE},
                xml.getBytes(StandardCharsets.UTF_16LE)));
    }

    private static byte[] opf(String items, String spine) {
        return bytes("<?xml version='1.0' encoding='UTF-8'?>"
                + "<package xmlns='http://www.idpf.org/2007/opf'>"
                + "<metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>探针样本</dc:title></metadata>"
                + "<manifest>" + items + "</manifest><spine>" + spine + "</spine></package>");
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int cursor = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, cursor, p.length);
            cursor += p.length;
        }
        return out;
    }

    private static void writeZip(Map<String, byte[]> files, File out) throws Exception {
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建样本目录");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out.toPath()))) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
