import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.LazyChapterBook;
import org.openleaf.reader.core.MobiChapterIndexer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * MOBI 受限堆 OOM 对照探针（仅测试代码，不依赖 JUnit / Android / native so）。
 *
 * 解码由注入的 {@link MobiParserBridgeTest.Fake} 模拟（不读 MOBI 二进制）：
 * - old 分支：BookParser.parse(mobi) 经 Fake.read() 聚合全本章节，再逐章 getBytes(UTF_16LE) 常驻，
 *   复刻 0.17.0 LazyChapterBook.open() 的全本累积峰值；
 * - new 分支：MobiChapterIndexer.index() → LazyChapterBook.open(mobi)/load 逐章，走 Fake.stream()
 *   真流式（逐段生成、直接 sink.accept、不保留已产出章节）。
 * 两分支读同一份样本、同一 JVM 参数，只切换路径。
 *
 * 用法（堆档由 JVM -Xmx 设定，探针打印实测 maxMemory 作为证据）：
 *   java -Xmx512m ... MobiStreamingOomProbe gen s2 <out.mobi>
 *   java -Xmx512m ... MobiStreamingOomProbe gen s4 <out.mobi>
 *   java -Xmx512m ... MobiStreamingOomProbe gen over <out.mobi>
 *   java -Xmx64m  ... MobiStreamingOomProbe old <sample.mobi> -Xmx64m   # 期望 OOM
 *   java -Xmx64m  ... MobiStreamingOomProbe new <sample.mobi> -Xmx64m   # 期望正常完成
 *
 * 诚实边界：本对照只证明 Java 侧「全本章节列表聚合峰值」被消除。真实 Bridge 的 native
 * ensure_parsed() 一次性 strdup 全部 segments（native 堆 ≤64MiB）与单段 jstring（≤16MiB
 * → Java String ≤32MiB）的瞬态峰值仍在；改它要动 mobi_bridge.c 并重编 so，属二期。
 */
public final class MobiStreamingOomProbe {
    private static final int MIB = 1024 * 1024;

    private MobiStreamingOomProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: gen <s2|s4|over> <out.mobi> | old|new <sample.mobi> [label]");
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
            runOld(new File(args[1]), label);
        } else if ("new".equals(mode)) {
            runNew(new File(args[1]), label);
        } else {
            throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    /** 样本规格写入 sidecar；文件本体只是 BookParser 存在性检查的占位（内容由 Fake 生成）。 */
    private static void generate(String kind, File out) throws Exception {
        int segments;
        int chars;
        if ("s2".equals(kind)) {
            segments = 48; chars = MIB;            // 48 段 × 1MiB 中文码元 ≈ 96MiB String + 96MiB UTF-16LE
        } else if ("s4".equals(kind)) {
            segments = 8; chars = 256 * 1024;      // 边界样本：约 8MiB String + 8MiB UTF-16LE
        } else if ("over".equals(kind)) {
            segments = 1; chars = 1;               // 超限样本：用覆写 length() 的 File 模拟 512MiB+
        } else {
            throw new IllegalArgumentException("unknown sample kind: " + kind);
        }
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建样本目录");
        Files.write(out.toPath(), new byte[0]);
        Path cfg = new File(out.getAbsolutePath() + ".cfg").toPath();
        Files.write(cfg, (kind + " " + segments + " " + chars).getBytes(StandardCharsets.UTF_8));
        System.out.println("GEN " + kind + " path=" + out.getAbsolutePath()
                + " segments=" + segments + " charsPerSegment=" + chars + " fileBytes=" + out.length());
    }

    /** 旧路径：全量 parse + 逐章 UTF-16LE 编码常驻。 */
    private static void runOld(File sample, String label) throws Exception {
        Spec spec = readSpec(sample);
        File file = spec.over ? overLimitFile(sample) : sample;
        MobiParserBridgeTest.Fake fake = new MobiParserBridgeTest.Fake();
        fake.segments = spec.segments;
        fake.charsPerSegment = spec.chars;
        fake.streamAggregate = true; // 覆写 stream 也走 read 聚合，与 default 回退同峰值
        BookParser.setMobiDecoder(fake);
        try {
            BookParser.BookContent book = BookParser.parse(file, "mobi");
            List<byte[]> retained = new ArrayList<byte[]>(book.chapters.size());
            long bytes = 0;
            for (BookParser.Chapter chapter : book.chapters) {
                byte[] encoded = chapter.text.getBytes(StandardCharsets.UTF_16LE);
                retained.add(encoded);
                bytes += encoded.length;
            }
            if (retained.isEmpty()) throw new AssertionError("old: no chapters");
            System.out.println("OLD PASS " + label + " chapters=" + book.chapters.size()
                    + " utf16Bytes=" + bytes + " retained=" + retained.size());
        } catch (OutOfMemoryError oom) {
            System.out.println("OLD OOM (real OutOfMemoryError) " + label + ": " + oom);
        } finally {
            BookParser.setMobiDecoder(null);
        }
    }

    /** 新路径：流式索引 + 逐章 load，堆内只有单章瞬态与元数据。 */
    private static void runNew(File sample, String label) throws Exception {
        Spec spec = readSpec(sample);
        File file = spec.over ? overLimitFile(sample) : sample;
        MobiParserBridgeTest.Fake fake = new MobiParserBridgeTest.Fake();
        fake.segments = spec.segments;
        fake.charsPerSegment = spec.chars;
        fake.streamAggregate = false; // 真流式
        BookParser.setMobiDecoder(fake);
        Path cache = Files.createTempDirectory("openleaf-oom-mobi-");
        try {
            if (spec.over) {
                // 负向：512MiB+ 上限拒绝，且不产生缓存残留。
                try {
                    MobiChapterIndexer.index(file, cache.toFile());
                    throw new AssertionError("超限样本应被拒绝");
                } catch (IOException rejected) {
                    System.out.println("NEW REJECT (expected) " + label + ": " + rejected.getMessage());
                }
            } else {
                MobiChapterIndexer.Result indexed = MobiChapterIndexer.index(file, cache.toFile());
                try (LazyChapterBook book = LazyChapterBook.open(file, "mobi", cache.toFile())) {
                    long chars = 0;
                    for (int i = 0; i < book.size(); i++) {
                        String text = book.load(i);
                        if (text.isEmpty()) throw new AssertionError("new: empty chapter " + i);
                        chars += text.length();
                    }
                    System.out.println("NEW PASS " + label + " chapters=" + book.size()
                            + " chars=" + chars + " indexedChapters=" + indexed.chapters.size());
                }
                if (indexed.cacheFile.exists() && !indexed.cacheFile.delete())
                    System.out.println("WARN: index cache left: " + indexed.cacheFile);
            }
        } catch (OutOfMemoryError oom) {
            System.out.println("NEW OOM (UNEXPECTED) " + label + ": " + oom);
            System.exit(1);
        } finally {
            BookParser.setMobiDecoder(null);
            deleteRecursively(cache);
        }
    }

    private static final class Spec {
        final boolean over;
        final int segments;
        final int chars;
        Spec(boolean over, int segments, int chars) {
            this.over = over; this.segments = segments; this.chars = chars;
        }
    }

    private static Spec readSpec(File sample) throws Exception {
        File cfg = new File(sample.getAbsolutePath() + ".cfg");
        List<String> lines = Files.readAllLines(cfg.toPath(), StandardCharsets.UTF_8);
        String[] parts = lines.get(0).trim().split("\\s+");
        return new Spec("over".equals(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    /**
     * 用覆写 length() 的 File 模拟超过 MAX_IMPORT_BYTES 的文件：被测代码只调用
     * isFile()/canRead()/length()/getAbsolutePath()，不读取文件正文，故无需真实分配 513MiB 磁盘。
     */
    private static File overLimitFile(File sample) {
        return new File(sample.getAbsolutePath()) {
            @Override public long length() { return BookParser.MAX_IMPORT_BYTES + 1; }
        };
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
