import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * OpenLeaf QA-1 合成夹具生成器（纯 JDK 17，无第三方依赖）。
 *
 * <p>所有夹具内容由固定算法生成：无时间戳、无随机数、无网络、无用户数据。
 * 同一 JDK 下重复运行会逐字节覆盖输出并产出完全相同的 MANIFEST.sha256。
 * ZIP 条目使用 STORED + 固定 DOS 时间，避免压缩实现差异与本地时间影响。
 *
 * <p>用法：
 * <pre>
 *   javac -encoding UTF-8 --release 17 -d &lt;tmp&gt;/fixture-gen validation/fixtures/FixtureGenerator.java
 *   java -Djava.io.tmpdir=&lt;tmp&gt; -cp &lt;tmp&gt;/fixture-gen FixtureGenerator validation/fixtures
 * </pre>
 */
public final class FixtureGenerator {

    /** 1980-01-01T00:00:00Z，ZIP DOS 时间下限，避免时间戳进入哈希。 */
    private static final long FIXED_ZIP_TIME_MILLIS = 315532800000L;

    /** 128 KiB 边界样本的目标字节数。 */
    private static final int BOUNDARY_BYTES = 128 * 1024;

    private FixtureGenerator() { }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Files.createDirectories(root);

        writeTextFixtures(root);
        writeMarkdownFixture(root);
        writeCollisionFixtures(root);
        writeCorruptBackup(root);
        writeComicFixture(root);
        writeMediaFixtures(root);

        String manifestHash = writeManifest(root);
        String generatorHash = sha256Hex(Files.readAllBytes(root.resolve("FixtureGenerator.java")));
        writeReadme(root, generatorHash, manifestHash);

        System.out.println("fixtures root : " + root);
        System.out.println("generator sha : " + generatorHash);
        System.out.println("manifest sha  : " + manifestHash);
    }

    // ------------------------------------------------------------------
    // 文本夹具
    // ------------------------------------------------------------------

    private static void writeTextFixtures(Path root) throws IOException {
        // 1) 三章长 TXT：UTF-8 BOM + CRLF；唯一行标记；超长章切分共享 src:1 锚点。
        byte[] utf8Body = bomPrefixed(
                encode(threeChapterText().replace("\n", "\r\n"), StandardCharsets.UTF_8),
                new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        writeBytes(root, "txt/three-chapters-bom-crlf.txt", utf8Body);

        // 2) 手动指定编码的样书。
        writeBytes(root, "txt/manual-gb18030.txt",
                encode("手动编码样书：GB18030。\n第一章 简体中文与标点。\n第二章 备份夹具不得改写用户原件。\n",
                        Charset.forName("GB18030")));
        writeBytes(root, "txt/manual-big5.txt",
                encode("手動編碼樣書：Big5。\n第一章 繁體中文與標點。\n第二章 備份夾具不得改寫使用者原件。\n",
                        Charset.forName("Big5")));
        writeBytes(root, "txt/utf16-bom.txt",
                bomPrefixed(encode("UTF-16 BOM 样书：含字节顺序标记。\n第一章 编码识别回归。\n",
                        StandardCharsets.UTF_16BE), new byte[]{(byte) 0xFE, (byte) 0xFF}));

        // 3) 128 KiB 上限边界样本：正好 131072 字节。
        byte[] boundary = boundarySample().getBytes(StandardCharsets.US_ASCII);
        if (boundary.length != BOUNDARY_BYTES) {
            throw new IllegalStateException("边界样本字节数错误：" + boundary.length);
        }
        writeBytes(root, "txt/boundary-128kib.txt", boundary);
    }

    private static String threeChapterText() {
        StringBuilder sb = new StringBuilder();
        sb.append("[BOOK] 合成三章长文本：UTF-8 BOM + CRLF，用于分页/锚点/搜索回归。\n");
        appendChapter(sb, 1, "序章", "[MARK-1-A]", 260);
        appendChapter(sb, 2, "长章与共享锚点", "[MARK-2-B]", 620);
        appendChapter(sb, 3, "终章", "[MARK-3-C]", 260);
        sb.append("[EOF] 合成夹具结束；不含任何真实用户文档。\n");
        return sb.toString();
    }

    private static void appendChapter(StringBuilder sb, int number, String title,
                                      String marker, int bodyLines) {
        sb.append("第").append(number).append("章 ").append(title).append("\n");
        sb.append(marker).append(" 唯一行标记：第").append(number).append("章起点\n");
        if (number == 2) {
            sb.append("[MARK-2-LONG] 本章为超长章，分页切分后的所有页共享同一原文锚点 src:1。\n");
            sb.append("[MARK-2-SPLIT-1] src:1 共享 key，切分页一。\n");
            sb.append("[MARK-2-SPLIT-2] src:1 共享 key，切分页二。\n");
            sb.append("[MARK-2-SPLIT-3] src:1 共享 key，切分页三。\n");
            sb.append("第2章 长章与共享锚点\n"); // 重复长章 key 场景，不应产生第二个独立锚点。
        }
        for (int i = 1; i <= bodyLines; i++) {
            sb.append("第").append(number).append("章 第").append(i)
                    .append("行：合成正文内容，用于分页、锚点与搜索回归。\n");
        }
    }

    private static String boundarySample() {
        StringBuilder sb = new StringBuilder(BOUNDARY_BYTES);
        String alphabet = "0123456789ABCDEF";
        for (int line = 0; line < BOUNDARY_BYTES / 128; line++) {
            StringBuilder row = new StringBuilder(128);
            row.append(String.format(Locale.ROOT, "%04d ", line));
            while (row.length() < 127) {
                row.append(alphabet.charAt((line + row.length()) % alphabet.length()));
            }
            sb.append(row).append('\n');
        }
        return sb.toString();
    }

    private static void writeMarkdownFixture(Path root) throws IOException {
        String[] lines = {
                "# 围栏样本",
                "",
                "正文段落，用于 Markdown 围栏、诗歌与对白混排回归。",
                "",
                "```text",
                "[POEM-START]",
                "静夜思",
                "床前明月光，",
                "疑是地上霜。",
                "举头望明月，",
                "低头思故乡。",
                "[POEM-END]",
                "```",
                "",
                "对白段落：",
                "",
                "> 甲：「这是第一段对白，引号与标点应保持原样。」",
                "> 乙：「这是第二段对白，用于朗读与队列回归。」",
                "",
                "```",
                "第二处围栏：三反引号内的诗歌与对白不应被当作正文标题。",
                "```",
                ""
        };
        writeBytes(root, "markdown/fenced-sample.md",
                encode(String.join("\n", lines), StandardCharsets.UTF_8));
    }

    private static void writeCollisionFixtures(Path root) throws IOException {
        writeBytes(root, "collision/a/same-name.txt",
                encode("A 目录的同名文件：内容甲，仅供导入去重回归。\n", StandardCharsets.UTF_8));
        writeBytes(root, "collision/b/same-name.txt",
                encode("B 目录的同名文件：内容乙，与 A 目录同名但内容不同。\n", StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 备份 / 漫画 / 媒体夹具
    // ------------------------------------------------------------------

    private static void writeCorruptBackup(Path root) throws IOException {
        String declaredHash = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        String manifest = "{\"schemaVersion\":6,\"entries\":["
                + "{\"path\":\"data/books.json\",\"kind\":\"DATA\",\"sha256\":\""
                + declaredHash + "\",\"size\":" + payload.length + ",\"part\":\"LIBRARY\"}"
                + "]}\n";
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        entries.put("data/books.json", payload);
        writeStoredZip(root.resolve("backup/corrupt-backup.zip"), entries);
    }

    private static void writeComicFixture(Path root) throws IOException {
        LinkedHashMap<String, byte[]> pages = new LinkedHashMap<>();
        int[] shades = {0, 51, 102, 153, 204};
        for (int i = 0; i < shades.length; i++) {
            pages.put(String.format(Locale.ROOT, "page-%03d.png", i + 1), minimalPng(shades[i]));
        }
        writeStoredZip(root.resolve("comic/five-pages.cbz"), pages);
    }

    private static void writeMediaFixtures(Path root) throws IOException {
        // 短音频：合法 0.5 秒 8kHz/16bit/单声道 PCM WAV，可被标准解码器解码。
        writeBytes(root, "media/short-audio.wav", shortWav());

        // 短视频：纯 JDK 无编码器，明确占位，不伪造可解码性。
        String placeholder = String.join("\n", new String[]{
                "# 短视频夹具占位说明",
                "",
                "环境无可解码编码器，占位。",
                "",
                "本仓库不提供声称可解码的短视频夹具：生成链路为纯 JDK 17 Java，",
                "不依赖 ffmpeg / MediaCodec，无法合法合成可解码的视频码流。",
                "为避免伪造可解码性，本文件仅为说明占位，扩展名为 .md。",
                "测试脚本必须先断言「非可解码占位」再跳过，或显式标记为未验。",
                "需要真实视频时请用外部编码器另行生成并记录哈希，不要修改本占位文件。",
                ""
        });
        writeBytes(root, "media/short-video.PLACEHOLDER.md",
                encode(placeholder, StandardCharsets.UTF_8));
    }

    private static byte[] shortWav() {
        int sampleRate = 8000;
        int sampleCount = 4000; // 0.5 秒
        int dataLength = sampleCount * 2;
        ByteArrayOutputStream wav = new ByteArrayOutputStream(44 + dataLength);
        writeAscii(wav, "RIFF");
        putIntLE(wav, 36 + dataLength);
        writeAscii(wav, "WAVE");
        writeAscii(wav, "fmt ");
        putIntLE(wav, 16);              // PCM 格式块长度
        putShortLE(wav, 1);             // PCM
        putShortLE(wav, 1);             // 单声道
        putIntLE(wav, sampleRate);
        putIntLE(wav, sampleRate * 2);  // 字节率
        putShortLE(wav, 2);             // 块对齐
        putShortLE(wav, 16);            // 位深
        writeAscii(wav, "data");
        putIntLE(wav, dataLength);
        for (int i = 0; i < sampleCount; i++) {
            int sample = (i / 100) % 2 == 0 ? 1000 : -1000; // 固定低频方波，非静音
            putShortLE(wav, sample);
        }
        byte[] result = wav.toByteArray();
        if (result.length != 44 + dataLength) {
            throw new IllegalStateException("WAV 长度错误：" + result.length);
        }
        return result;
    }

    /** 1x1 灰度 PNG，CRC 由 java.util.zip.CRC32 计算，保证是合法可枚举图片。 */
    private static byte[] minimalPng(int gray) {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        byte[] signature = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        png.write(signature, 0, signature.length);

        byte[] ihdr = new byte[13];
        putIntBE(ihdr, 0, 1);   // width
        putIntBE(ihdr, 4, 1);   // height
        ihdr[8] = 8;            // bit depth
        ihdr[9] = 0;            // color type: grayscale
        ihdr[10] = 0;           // compression
        ihdr[11] = 0;           // filter
        ihdr[12] = 0;           // interlace
        writePngChunk(png, "IHDR", ihdr);

        byte[] raw = new byte[]{0, (byte) gray}; // filter=0 + 单像素灰度
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            if (count > 0) compressed.write(buffer, 0, count);
        }
        deflater.end();
        writePngChunk(png, "IDAT", compressed.toByteArray());
        writePngChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void writePngChunk(ByteArrayOutputStream out, String type, byte[] data) {
        putIntBE(out, data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes, 0, typeBytes.length);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        putIntBE(out, (int) crc.getValue());
    }

    /** STORED ZIP：固定 CRC/长度/时间，逐字节可复现。 */
    private static void writeStoredZip(Path target, LinkedHashMap<String, byte[]> entries)
            throws IOException {
        Files.createDirectories(target.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target),
                StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                byte[] bytes = entry.getValue();
                CRC32 crc = new CRC32();
                crc.update(bytes);
                ZipEntry item = new ZipEntry(entry.getKey());
                item.setMethod(ZipEntry.STORED);
                item.setSize(bytes.length);
                item.setCompressedSize(bytes.length);
                item.setCrc(crc.getValue());
                item.setTime(FIXED_ZIP_TIME_MILLIS);
                zip.putNextEntry(item);
                zip.write(bytes);
                zip.closeEntry();
            }
        }
    }

    // ------------------------------------------------------------------
    // 清单与说明
    // ------------------------------------------------------------------

    private static String writeManifest(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("MANIFEST.sha256"))
                    .filter(path -> !path.getFileName().toString().equals("README.md"))
                    .filter(path -> !path.getFileName().toString().equals("FixtureGenerator.java"))
                    .filter(path -> !path.getFileName().toString().equals(".gitattributes"))
                    .collect(Collectors.toList());
        }
        TreeMap<String, String> digests = new TreeMap<>();
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
            digests.put(relative, sha256Hex(Files.readAllBytes(file)));
        }
        StringBuilder manifest = new StringBuilder();
        for (Map.Entry<String, String> entry : digests.entrySet()) {
            manifest.append(entry.getValue()).append("  ").append(entry.getKey()).append('\n');
        }
        Files.write(root.resolve("MANIFEST.sha256"),
                manifest.toString().getBytes(StandardCharsets.UTF_8));
        return sha256Hex(manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeReadme(Path root, String generatorHash, String manifestHash)
            throws IOException {
        String[] lines = {
                "# OpenLeaf QA-1 合成夹具",
                "",
                "全部夹具由 `FixtureGenerator.java` 合成，不含任何真实用户文档；",
                "内容固定、无时间戳与随机数，重复运行逐字节一致。",
                "",
                "## 用途",
                "",
                "- `txt/three-chapters-bom-crlf.txt`：三章长文本，UTF-8 BOM + CRLF；",
                "  `[MARK-1-A]` / `[MARK-2-B]` / `[MARK-3-C]` 为唯一行标记；",
                "  第 2 章（超长、含重复章标题）切分页共享 `src:1` 锚点 key。",
                "- `txt/manual-gb18030.txt` / `txt/manual-big5.txt` / `txt/utf16-bom.txt`：",
                "  手动指定编码样书，分别验证 GB18030、Big5 与 UTF-16 BOM 识别。",
                "- `txt/boundary-128kib.txt`：正好 131072 字节（128 KiB）的正文上限边界样本。",
                "- `markdown/fenced-sample.md`：三反引号围栏 + 诗歌 + 对白混排。",
                "- `collision/a/same-name.txt` 与 `collision/b/same-name.txt`：同名不同内容，",
                "  用于导入去重与 SAF 可写/只读提供器回归（文件相同，授权不同）。",
                "- `backup/corrupt-backup.zip`：`manifest.json` 声明的 SHA-256 与 `data/books.json`",
                "  实际内容不符，用于恢复前校验与失败不污染目标库验证。",
                "- `comic/five-pages.cbz`：5 张合法 1x1 PNG（STORED ZIP），可枚举页数为 5。",
                "- `media/short-audio.wav`：合法 0.5 秒 8kHz/16bit/单声道 PCM WAV。",
                "- `media/short-video.PLACEHOLDER.md`：环境无可解码编码器，占位；",
                "  **不声称可解码**，测试须显式标注未验。",
                "",
                "## 重建命令",
                "",
                "```powershell",
                "$root = '<repo-root>'",
                "$tmp  = 'C:\\Users\\Public\\openleaf-fixture-gen'",
                "New-Item -ItemType Directory -Force -Path $tmp | Out-Null",
                "javac -encoding UTF-8 --release 17 -d $tmp (Join-Path $root 'validation\\fixtures\\FixtureGenerator.java')",
                "java -Djava.io.tmpdir=$tmp -cp $tmp FixtureGenerator (Join-Path $root 'validation\\fixtures')",
                "```",
                "",
                "从 `validation/fixtures` 目录执行 `sha256sum -c MANIFEST.sha256` 校验。",
                "本目录 `.gitattributes` 声明 `* -text`，避免 `core.autocrlf` 在检出时改写换行、",
                "导致清单哈希失效。",
                "",
                "## 完整性记录",
                "",
                "- 生成器本体 SHA-256：`" + generatorHash + "`",
                "- 本次 MANIFEST.sha256 自身 SHA-256：`" + manifestHash + "`",
                "- MANIFEST.sha256 覆盖 " + (countFixtures(root)) + " 个夹具文件；",
                "  不覆盖 `README.md`、`MANIFEST.sha256`、`.gitattributes` 与生成器本体（其哈希见上）。",
                "- ZIP 使用 STORED + DOS 时间 1980-01-01，避免压缩器与本地时间影响哈希。",
                ""
        };
        Files.write(root.resolve("README.md"),
                encode(String.join("\n", lines), StandardCharsets.UTF_8));
    }

    private static int countFixtures(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("MANIFEST.sha256"))
                    .filter(path -> !path.getFileName().toString().equals("README.md"))
                    .filter(path -> !path.getFileName().toString().equals("FixtureGenerator.java"))
                    .filter(path -> !path.getFileName().toString().equals(".gitattributes"))
                    .count();
        }
    }

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    private static void writeBytes(Path root, String relative, byte[] bytes) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static byte[] encode(String text, Charset charset) throws IOException {
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer buffer = encoder.encode(CharBuffer.wrap(text));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static byte[] bomPrefixed(byte[] body, byte[] bom) {
        byte[] result = Arrays.copyOf(bom, bom.length + body.length);
        System.arraycopy(body, 0, result, bom.length, body.length);
        return result;
    }

    private static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JDK 缺少 SHA-256", error);
        }
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            sb.append(Character.forDigit((value >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(value & 0xF, 16));
        }
        return sb.toString();
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    private static void putIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void putShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void putIntBE(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 24) & 0xFF);
        target[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }

    private static void putIntBE(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
