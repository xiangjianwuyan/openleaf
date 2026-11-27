import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * OpenLeaf DATA-1 备份夹具生成器（纯 JDK 17，无 Android / org.json 依赖）。
 *
 * <p>本生成器只产出<b>合成样本</b>：既有样本由固定算法生成，无时间戳、无随机数、无网络、
 * 无用户数据；ZIP 条目一律 STORED + 固定 DOS 时间，重复运行逐字节一致。样本用途仅限
 * core 层静态验证（BackupManifest.validateStructure / verifyPayload 与版本矩阵），
 * <b>不是</b>当前版本真实 SAF 导出，也<b>不是</b> ol037 backup-v6.zip 历史本体。</p>
 *
 * <p>用法：
 * <pre>
 *   javac -encoding UTF-8 --release 17 -d &lt;tmp&gt; validation/fixtures/backup/BackupFixtureGenerator.java
 *   java -cp &lt;tmp&gt; BackupFixtureGenerator validation/fixtures/backup
 * </pre>
 * 生成后从该目录运行 {@code sha256sum -c MANIFEST.sha256} 校验。
 */
public final class BackupFixtureGenerator {

    /** 与 app/src/main/java/org/openleaf/reader/core/BackupManifest.java:34-39 的 DATA_SECTIONS 一致。 */
    private static final String[] DATA_SECTIONS = {
            "books", "book_metadata", "book_tags", "book_reviews", "book_folders",
            "folders", "annotations", "ideas", "reading_time", "reading_sessions",
            "media_sources", "media_time", "media_items", "asset_revisions", "online_books",
            "reader_prefs"
    };

    /** 本目录由 DATA-1 生成并纳入 MANIFEST 的 6 个样本；QA-1 的 corrupt-backup.zip 不在其中。 */
    private static final String[] SAMPLE_FILES = {
            "current-v6-valid.zip", "corrupt-bad-hash.zip", "corrupt-bad-json.zip",
            "corrupt-truncated.zip", "historical-v4-synthetic.zip", "historical-v5-synthetic.zip"
    };

    /** 1980-01-01T00:00:00Z（ZIP DOS 时间下限），避免本地时间进入哈希。 */
    private static final long FIXED_ZIP_TIME_MILLIS = 315532800000L;

    /** 清单里的固定创建时间：合成样本不得伪装成真实导出时间。 */
    private static final long FIXED_CREATED_AT = 0L;

    /** 合成 app 标识：测试可据此断言样本不是真实导出。 */
    private static final String SYNTHETIC_APP = "OpenLeaf/synthetic-fixture";

    private static final String BOOK_ID = "book-fixture-1";
    private static final String BOOK_FILE = "files/book-fixture-1.txt";
    private static final byte[] BOOK_BYTES =
            "合成备份样书：仅用于 DATA-1 core 静态校验，不含真实用户内容。\n".getBytes(StandardCharsets.UTF_8);

    private BackupFixtureGenerator() { }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Files.createDirectories(root);

        LinkedHashMap<String, byte[]> validEntries = buildValidEntries(BackupManifestVersion.V6);
        List<Spec> specs = entrySpecs(validEntries);

        writeStoredZip(root.resolve("current-v6-valid.zip"), validEntries);
        writeBadHashZip(root.resolve("corrupt-bad-hash.zip"), validEntries, specs);
        writeBadJsonZip(root.resolve("corrupt-bad-json.zip"), validEntries);
        writeTruncatedZip(root.resolve("corrupt-truncated.zip"), validEntries);

        // 版本矩阵样本：结构与当前包一致，仅 schemaVersion 改为 4 / 5。
        // 它们是合成结构变体，不是 ol037 历史本体，不能证明旧样本兼容。
        writeStoredZip(root.resolve("historical-v4-synthetic.zip"),
                buildValidEntries(BackupManifestVersion.V4));
        writeStoredZip(root.resolve("historical-v5-synthetic.zip"),
                buildValidEntries(BackupManifestVersion.V5));

        String manifestHash = writeManifest(root);
        String generatorHash = sha256Hex(Files.readAllBytes(
                root.resolve("BackupFixtureGenerator.java")));
        writeReadme(root, generatorHash, manifestHash, specs.size());

        System.out.println("backup fixtures root : " + root);
        System.out.println("samples              : " + SAMPLE_FILES.length);
        System.out.println("manifest entries     : " + specs.size());
        System.out.println("generator sha256     : " + generatorHash);
        System.out.println("manifest sha256      : " + manifestHash);
    }

    private enum BackupManifestVersion {
        V4(4), V5(5), V6(6);

        final int value;

        BackupManifestVersion(int value) {
            this.value = value;
        }
    }

    /** 构造合法 v6（或指定版本）包：manifest.json + 16 个 DATA 段 + 1 个书文件。 */
    private static LinkedHashMap<String, byte[]> buildValidEntries(BackupManifestVersion version) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        for (String section : DATA_SECTIONS) {
            entries.put("data/" + section + ".json", sectionPayload(section));
        }
        entries.put(BOOK_FILE, BOOK_BYTES);
        entries.put("manifest.json", manifestJson(version.value, entrySpecs(entries)).getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    /**
     * 各数据段载荷格式与 LibraryBackup.exportTable / exportReaderPrefs 一致：
     * 表段为 {"table":...,"columns":[...],"rows":[...]}，reader_prefs 为 {"rows":[...]}。
     */
    private static byte[] sectionPayload(String section) {
        String payload;
        if ("books".equals(section)) {
            // books.file 是 LibraryBackup.REGENERATE_FIELDS，导出侧已剥离，故行内不含 file。
            payload = sectionObject(section,
                    "[\"id\",\"title\",\"format\",\"added\",\"sha256\",\"order_index\"]",
                    "[{\"id\":\"" + BOOK_ID + "\",\"title\":\"合成备份样书\","
                            + "\"format\":\"txt\",\"added\":1704067200000,"
                            + "\"sha256\":\"" + sha256Hex(BOOK_BYTES) + "\",\"order_index\":0}]");
        } else if ("reading_time".equals(section)) {
            payload = sectionObject(section,
                    "[\"book_id\",\"day\",\"seconds\",\"created_at\"]",
                    "[{\"book_id\":\"" + BOOK_ID + "\",\"day\":\"2024-01-01\","
                            + "\"seconds\":120,\"created_at\":1704067200000}]");
        } else if ("reader_prefs".equals(section)) {
            // 两个阅读锚点：LibraryBackup.exportReaderPrefs 的 {book_id,suffix,type,value} 行格式。
            payload = "{\"rows\":["
                    + "{\"book_id\":\"" + BOOK_ID + "\",\"suffix\":\"anchor_\","
                    + "\"type\":\"string\",\"value\":\"synthetic-anchor-1\"},"
                    + "{\"book_id\":\"" + BOOK_ID + "\",\"suffix\":\"chapter_\","
                    + "\"type\":\"int\",\"value\":\"3\"}"
                    + "]}";
        } else {
            payload = sectionObject(section, "[]", "[]");
        }
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    private static String sectionObject(String table, String columns, String rows) {
        return "{\"table\":" + quote(table) + ",\"columns\":" + columns + ",\"rows\":" + rows + "}";
    }

    /** 清单条目：与 LibraryBackup.export 写入的键名和顺序一致。 */
    private static List<Spec> entrySpecs(Map<String, byte[]> entries) {
        List<Spec> specs = new ArrayList<>();
        for (Map.Entry<String, byte[]> item : entries.entrySet()) {
            String path = item.getKey();
            if ("manifest.json".equals(path)) continue;
            boolean data = path.startsWith("data/");
            String part = data && path.endsWith("online_books.json") ? "CATALOG"
                    : data ? "LIBRARY" : "ARCHIVE";
            specs.add(new Spec(path, data ? "DATA" : "FILE", sha256Hex(item.getValue()),
                    item.getValue().length, part));
        }
        return specs;
    }

    private static String manifestJson(int version, List<Spec> specs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schemaVersion\":").append(version)
                .append(",\"createdAt\":").append(FIXED_CREATED_AT)
                .append(",\"app\":").append(quote(SYNTHETIC_APP))
                .append(",\"entries\":[");
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"path\":").append(quote(s.path))
                    .append(",\"kind\":").append(quote(s.kind))
                    .append(",\"sha256\":").append(quote(s.sha256))
                    .append(",\"size\":").append(s.size)
                    .append(",\"part\":").append(quote(s.part)).append('}');
        }
        return sb.append("]}").toString();
    }

    /** 坏哈希样本：结构合法、载荷齐全，但 books 段声明的哈希全 0。 */
    private static void writeBadHashZip(Path target, Map<String, byte[]> valid, List<Spec> specs)
            throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(valid);
        List<Spec> broken = new ArrayList<>();
        for (Spec s : specs) {
            String hash = "data/books.json".equals(s.path) ? repeat('0', 64) : s.sha256;
            broken.add(new Spec(s.path, s.kind, hash, s.size, s.part));
        }
        entries.put("manifest.json",
                manifestJson(BackupManifestVersion.V6.value, broken).getBytes(StandardCharsets.UTF_8));
        writeStoredZip(target, entries);
    }

    /** 坏 JSON 样本：manifest.json 被截断为不完整的 JSON 文本。 */
    private static void writeBadJsonZip(Path target, Map<String, byte[]> valid) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(valid);
        entries.put("manifest.json",
                "{\"schemaVersion\":6,\"createdAt\":0,\"entries\":[{\"path\":\"data/books.json\""
                        .getBytes(StandardCharsets.UTF_8));
        writeStoredZip(target, entries);
    }

    /** 截断样本：合法包尾部（中央目录）被切掉，zip 读取阶段必须失败。 */
    private static void writeTruncatedZip(Path target, Map<String, byte[]> valid) throws IOException {
        Path complete = target.resolveSibling("current-v6-valid.zip");
        byte[] bytes = Files.readAllBytes(complete);
        Files.createDirectories(target.getParent());
        Files.write(target, Arrays.copyOf(bytes, bytes.length / 2));
    }

    /** STORED ZIP：固定 CRC / 长度 / DOS 时间，逐字节可复现。 */
    private static void writeStoredZip(Path target, LinkedHashMap<String, byte[]> entries)
            throws IOException {
        Files.createDirectories(target.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target),
                StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                byte[] bytes = item.getValue();
                CRC32 crc = new CRC32();
                crc.update(bytes);
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(bytes.length);
                entry.setCompressedSize(bytes.length);
                entry.setCrc(crc.getValue());
                entry.setTime(FIXED_ZIP_TIME_MILLIS);
                zip.putNextEntry(entry);
                zip.write(bytes);
                zip.closeEntry();
            }
        }
    }

    /** 标准 sha256sum 格式，路径相对 backup 目录；与 QA-1 顶层 MANIFEST 互不覆盖。 */
    private static String writeManifest(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String sample : SAMPLE_FILES) {
            Path path = root.resolve(sample);
            if (!Files.isRegularFile(path)) {
                throw new IOException("缺少 DATA-1 样本：" + path);
            }
            files.add(path);
        }
        TreeMap<String, String> digests = new TreeMap<>();
        for (Path file : files) {
            digests.put(root.relativize(file).toString().replace(java.io.File.separatorChar, '/'),
                    sha256Hex(Files.readAllBytes(file)));
        }
        StringBuilder manifest = new StringBuilder();
        for (Map.Entry<String, String> item : digests.entrySet()) {
            manifest.append(item.getValue()).append("  ").append(item.getKey()).append('\n');
        }
        Files.write(root.resolve("MANIFEST.sha256"),
                manifest.toString().getBytes(StandardCharsets.UTF_8));
        return sha256Hex(manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeReadme(Path root, String generatorHash, String manifestHash,
                                    int entriesPerSample) throws IOException {
        String[] lines = {
                "# OpenLeaf DATA-1 合成备份样本（静态验证用）",
                "",
                "**语义声明：本目录全部 zip 均为合成样本，不是当前版本真实 SAF 导出，**",
                "**也不是 ol037 backup-v6.zip 历史本体。** 仅用于 core 层静态校验，",
                "不得据此声称端到端回环、旧样本兼容或恢复后不污染库。",
                "",
                "## 样本清单",
                "",
                "- `current-v6-valid.zip`：合成 schema v6 合法包；"
                        + DATA_SECTIONS.length + " 个 DATA 段（覆盖 BackupManifest.DATA_SECTIONS）"
                        + " + 1 个书文件条目，清单 entries 共 " + entriesPerSample + " 条，"
                        + "zip 另含 manifest.json 共 " + (entriesPerSample + 1) + " 个文件；",
                "  含 1 本书（`book-fixture-1`）、reading_time 1 行、reader_prefs 2 个阅读锚点。",
                "  `createdAt=0`、`app=OpenLeaf/synthetic-fixture`，明确区别于真实导出。",
                "- `corrupt-bad-hash.zip`：结构合法但 `data/books.json` 声明 SHA-256 全 0；",
                "  用于断言 `BackupManifest.verifyPayload` 拒绝，不代表真实损坏备份本体。",
                "- `corrupt-bad-json.zip`：`manifest.json` 被截断为非法 JSON；断言解析阶段拒绝。",
                "- `corrupt-truncated.zip`：合法包尾部截断（中央目录缺失）；断言 zip 读取失败。",
                "- `historical-v4-synthetic.zip` / `historical-v5-synthetic.zip`：用当前结构合成、",
                "  `schemaVersion=4/5` 的版本矩阵变体；只反映 `validateStructure` 对 1..6 的接受行为，",
                "  **不能**证明 ol037 旧备份可恢复或运行时迁移兼容。",
                "",
                "## 重建与校验",
                "",
                "```powershell",
                "$root = '<repo-root>'",
                "$tmp  = '<repo-root>\\.tools\\tmp-build\\backup-fixture-gen'",
                "New-Item -ItemType Directory -Force -Path $tmp | Out-Null",
                "javac -encoding UTF-8 --release 17 -d $tmp (Join-Path $root 'validation\\fixtures\\backup\\BackupFixtureGenerator.java')",
                "java -cp $tmp BackupFixtureGenerator (Join-Path $root 'validation\\fixtures\\backup')",
                "```",
                "",
                "从 `validation/fixtures/backup` 执行 `sha256sum -c MANIFEST.sha256`；",
                "MANIFEST 只覆盖本目录 6 个 zip 样本，不覆盖 README.md、MANIFEST.sha256、",
                "生成器本体，也不覆盖 QA-1 遗留的 `corrupt-backup.zip`。",
                "QA-1 顶层 `validation/fixtures/MANIFEST.sha256` 保持不变，仍只覆盖其原有 12 个夹具。",
                "本目录继承上级 `.gitattributes` 的 `* -text`，避免检出换行改写哈希。",
                "",
                "## 完整性记录",
                "",
                "- 生成器本体 SHA-256：`" + generatorHash + "`",
                "- MANIFEST.sha256 自身 SHA-256：`" + manifestHash + "`",
                "- ZIP 使用 STORED + DOS 时间 1980-01-01，无时间戳、随机数或网络依赖，重复运行逐字节一致。",
                "",
                "## 验证边界（未验，不伪装为通过）",
                "",
                "- 当前版本真实 SAF 导出回环：无目标 adb 设备，待验。",
                "- ol037 backup-v6.zip 历史本体兼容：样本本体不存在，待验。",
                "- `LibraryRestore.commit()` 文件落盘 / 事务边界、`BackupActivity.doImport()` 取消：",
                "  本批不改运行时、不提供设备证据。",
                ""
        };
        Files.write(root.resolve("README.md"),
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    private static final class Spec {
        final String path, kind, sha256, part;
        final long size;

        Spec(String path, String kind, String sha256, long size, String part) {
            this.path = path;
            this.kind = kind;
            this.sha256 = sha256;
            this.size = size;
            this.part = part;
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
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
}
