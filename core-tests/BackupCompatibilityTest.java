import org.openleaf.reader.core.BackupManifest;
import org.openleaf.reader.core.BackupRestoreGuard;
import org.openleaf.reader.core.ContentContracts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DATA-1 静态兼容验证：消费 BackupFixtureGenerator 产出的<b>合成</b>备份样本。
 *
 * <p>覆盖 validateStructure / verifyPayload、三类损坏样本、版本矩阵与纯函数恢复辅助逻辑。
 * 明确不覆盖 LibraryRestore.commit() 落盘/事务边界、BackupActivity.doImport() 取消，
 * 也不代表当前版本真实 SAF 回环或 ol037 历史本体兼容（无设备、无历史样本本体）。</p>
 */
public final class BackupCompatibilityTest {
    private static final String BOOK_ID = "book-fixture-1";
    private static final String BOOK_FILE = "files/book-fixture-1.txt";
    private static final String[] SAMPLES = {
            "current-v6-valid.zip", "corrupt-bad-hash.zip", "corrupt-bad-json.zip",
            "corrupt-truncated.zip", "historical-v4-synthetic.zip", "historical-v5-synthetic.zip"
    };
    private static int passed = 0;

    public static void main(String[] args) throws IOException {
        // 先确认样本齐备，避免"找不到文件"被误读成校验通过。
        for (String sample : SAMPLES) check(Files.isRegularFile(fixture(sample)), "缺少样本：" + sample);

        validV6Sample();
        corruptSamples();
        versionMatrix();
        pureFunctionGuards();

        System.out.println("PASS BackupCompatibilityTest (" + passed + " checks)");
        System.out.println("SCOPE: synthetic fixtures only; SAF roundtrip / ol037 body / commit / cancel NOT verified");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        passed++;
    }

    private static Path fixture(String name) {
        Path relative = Paths.get("validation", "fixtures", "backup", name);
        if (Files.isRegularFile(relative)) return relative;
        String override = System.getProperty("openleaf.backup.fixtures");
        if (override != null) {
            Path fromProperty = Paths.get(override, name);
            if (Files.isRegularFile(fromProperty)) return fromProperty;
        }
        throw new AssertionError("找不到夹具 " + name + "（cwd=" + Paths.get("").toAbsolutePath() + "）");
    }

    /** 合成 v6 包：结构、哈希、数据段与真实 LibraryBackup 字段形状一致。 */
    private static void validV6Sample() throws IOException {
        Map<String, byte[]> zip = readZip(fixture("current-v6-valid.zip"));
        check(zip.containsKey("manifest.json"), "合法包应含 manifest.json");
        check(zip.containsKey(BOOK_FILE), "合法包应含书文件条目");

        Set<String> expected = new HashSet<>(Arrays.asList(BackupManifest.DATA_SECTIONS));
        Set<String> actual = new HashSet<>();
        for (String path : zip.keySet()) {
            if (path.startsWith("data/") && path.endsWith(".json"))
                actual.add(path.substring("data/".length(), path.length() - ".json".length()));
        }
        check(expected.equals(actual), "DATA 段必须与 BackupManifest.DATA_SECTIONS 一致：" + actual);
        check(actual.size() == 16, "DATA 段应为 16 项，实际 " + actual.size());

        Map<String, Object> manifest = manifestOf(zip);
        int version = (int) Json.num(manifest.get("schemaVersion"));
        check(version == BackupManifest.SCHEMA_VERSION, "合成包版本应为当前 SCHEMA_VERSION");
        check(Json.str(manifest.get("app")).contains("synthetic"), "样本必须自标合成，不得冒充真实导出");

        List<BackupManifest.Entry> entries = entriesOf(manifest);
        check(entries.size() == BackupManifest.DATA_SECTIONS.length + 1,
                "清单条目应为 16 DATA + 1 FILE，实际 " + entries.size());
        List<BackupManifest.Issue> structural = BackupManifest.validateStructure(version, entries);
        check(structural.isEmpty(), "合法合成包结构校验应通过：" + structural);

        for (BackupManifest.Entry entry : entries) {
            byte[] payload = zip.get(entry.path);
            check(payload != null, "清单声明的条目必须落盘：" + entry.path);
            BackupManifest.Issue bad = BackupManifest.verifyPayload(entry,
                    ContentContracts.hashHex(payload), payload.length);
            check(bad == null, "合法合成包载荷哈希应通过：" + bad);
        }

        // 每个 DATA 段都必须是可解析 JSON；表段形状与 LibraryBackup.exportTable 一致。
        for (String section : expected) {
            Object parsed = Json.parse(new String(zip.get("data/" + section + ".json"), StandardCharsets.UTF_8));
            Map<String, Object> object = Json.obj(parsed);
            if (!"reader_prefs".equals(section))
                check(section.equals(Json.str(object.get("table"))), "表段 table 字段应匹配路径：" + section);
            if (object.get("rows") == null) throw new AssertionError("DATA 段缺少 rows：" + section);
            passed++;
        }

        Map<String, Object> books = Json.obj(Json.parse(
                new String(zip.get("data/books.json"), StandardCharsets.UTF_8)));
        List<Object> bookRows = Json.arr(books.get("rows"));
        check(bookRows.size() >= 1, "合法包至少含 1 本书");
        Map<String, Object> book = Json.obj(bookRows.get(0));
        check(BOOK_ID.equals(Json.str(book.get("id"))), "合成书 id 应匹配书文件条目");
        check(Json.str(book.get("sha256")).equals(ContentContracts.hashHex(zip.get(BOOK_FILE))),
                "books.sha256 应与书文件载荷一致");
        check(book.get("file") == null, "books.file 是设备内文件名，导出侧应剥离");

        Map<String, Object> time = Json.obj(Json.parse(
                new String(zip.get("data/reading_time.json"), StandardCharsets.UTF_8)));
        check(Json.arr(time.get("rows")).size() >= 1, "合法包应含 reading_time 行");
        Map<String, Object> prefs = Json.obj(Json.parse(
                new String(zip.get("data/reader_prefs.json"), StandardCharsets.UTF_8)));
        check(Json.arr(prefs.get("rows")).size() == 2, "合法包应含两个阅读锚点");

        System.out.println("V6_SYNTHETIC: 16 DATA sections, " + entries.size()
                + " manifest entries, 1 book, 1 reading_time row, 2 prefs anchors (all hashes verified)");
    }

    /** 三类损坏样本必须被拒：坏哈希（载荷校验）、坏 JSON（解析）、截断（zip 读取）。 */
    private static void corruptSamples() throws IOException {
        Map<String, byte[]> badHashZip = readZip(fixture("corrupt-bad-hash.zip"));
        Map<String, Object> badHashManifest = manifestOf(badHashZip);
        List<BackupManifest.Entry> badHashEntries = entriesOf(badHashManifest);
        check(BackupManifest.validateStructure(
                (int) Json.num(badHashManifest.get("schemaVersion")), badHashEntries).isEmpty(),
                "坏哈希样本结构仍应合法，以便单独验证 verifyPayload");
        BackupManifest.Issue found = null;
        for (BackupManifest.Entry entry : badHashEntries) {
            byte[] payload = badHashZip.get(entry.path);
            if (payload == null) continue;
            BackupManifest.Issue bad = BackupManifest.verifyPayload(entry,
                    ContentContracts.hashHex(payload), payload.length);
            if (bad != null) { found = bad; break; }
        }
        check(found != null && found.fatal, "坏哈希样本必须被 verifyPayload 致命拒绝");
        check("data/books.json".equals(found.path), "被拒条目应为 data/books.json");

        byte[] badJson = readZip(fixture("corrupt-bad-json.zip")).get("manifest.json");
        try {
            Json.parse(new String(badJson, StandardCharsets.UTF_8));
            throw new AssertionError("坏 JSON 样本不应解析成功");
        } catch (JsonParseException expected) {
            passed++;
        }

        try {
            // readZip 会打开中央目录并读出全部条目；截断包必须在其中一步失败。
            readZip(fixture("corrupt-truncated.zip"));
            throw new AssertionError("截断 zip 不应被完整读出");
        } catch (IOException expected) {
            passed++;
        }
        System.out.println("CORRUPT_SAMPLES: bad-hash rejected by verifyPayload; bad-json rejected by parse; truncated rejected by zip read");
    }

    /** 版本兼容矩阵：记录 validateStructure 对合成 v4/v5/v6 的实际结果，不外推到运行时恢复。 */
    private static void versionMatrix() throws IOException {
        StringBuilder actual = new StringBuilder();
        int[] versions = {4, 5, 6};
        String[] names = {"historical-v4-synthetic.zip", "historical-v5-synthetic.zip", "current-v6-valid.zip"};
        Map<String, Object> v6Manifest = null;
        for (int i = 0; i < versions.length; i++) {
            Map<String, Object> manifest = manifestOf(readZip(fixture(names[i])));
            int version = (int) Json.num(manifest.get("schemaVersion"));
            check(version == versions[i], "样本清单版本应为 " + versions[i]);
            List<BackupManifest.Issue> issues = BackupManifest.validateStructure(version, entriesOf(manifest));
            check(issues.isEmpty(), "validateStructure(v" + version + ") 实际结果：" + issues);
            actual.append('v').append(version).append("=accepted ");
            if (version == 6) v6Manifest = manifest;
        }
        check(v6Manifest != null, "应读到 v6 清单");
        List<BackupManifest.Issue> tooNew = BackupManifest.validateStructure(7, entriesOf(v6Manifest));
        check(!tooNew.isEmpty() && tooNew.get(0).fatal, "未知高版本 7 必须被致命拒绝");
        actual.append("v7=rejected(structure only)");
        System.out.println("VERSION_MATRIX: " + actual);
        System.out.println("VERSION_MATRIX_SCOPE: v4/v5 samples are synthetic structural variants; ol037 backup-v6.zip body absent");
    }

    /** 纯函数断言：悬挂 FK 过滤、ID 重映射、reader_prefs 键变换口径（不触碰 Android SharedPreferences）。 */
    private static void pureFunctionGuards() throws IOException {
        BackupRestoreGuard.Result filtered = BackupRestoreGuard.filterStats(
                Arrays.asList(BOOK_ID, "ghost-id"), null,
                BackupRestoreGuard.bookIds(Collections.singletonList(BOOK_ID)));
        check(filtered.kept.equals(Collections.singletonList(BOOK_ID)), "悬挂 FK 过滤应保留有效 book_id");
        check(filtered.skipped.equals(Collections.singletonList("ghost-id")), "悬挂 FK 过滤应剔除幽灵 id");

        byte[] bookBytes = readZip(fixture("current-v6-valid.zip")).get(BOOK_FILE);
        Map<String, String> existingByHash = new LinkedHashMap<>();
        existingByHash.put(ContentContracts.hashHex(bookBytes), "target-book");
        Map<String, String> sourceHash = new LinkedHashMap<>();
        sourceHash.put(BOOK_ID, ContentContracts.hashHex(bookBytes));
        Map<String, String> mapping = BackupManifest.remapBookIds(
                Collections.singletonList(BOOK_ID), existingByHash, sourceHash, () -> "generated-id");
        check("target-book".equals(mapping.get(BOOK_ID)), "sha 去重命中应复用目标 id");

        // 复刻 LibraryRestore.restoreReaderPrefs:407-409 的键推导，只验证纯变换，不验证落盘。
        Map<String, Object> prefs = Json.obj(Json.parse(new String(
                readZip(fixture("current-v6-valid.zip")).get("data/reader_prefs.json"),
                StandardCharsets.UTF_8)));
        Set<String> targetKeys = new HashSet<>();
        for (Object item : Json.arr(prefs.get("rows"))) {
            Map<String, Object> row = Json.obj(item);
            String source = Json.str(row.get("book_id"));
            String target = mapping.get(source);
            if (target == null) target = source;
            targetKeys.add(Json.str(row.get("suffix")) + target);
        }
        check(targetKeys.contains("anchor_target-book"), "anchor_ 锚点应按重映射后的 id 生成目标键");
        check(targetKeys.contains("chapter_target-book"), "chapter_ 锚点应按重映射后的 id 生成目标键");
        check(!targetKeys.contains("anchor_" + BOOK_ID), "目标键不得残留未重映射的源 book id");

        check(BackupManifest.isRegenerateField("books.file"), "books.file 应是需要重新生成的设备内文件名");
        check(BackupManifest.isRemapColumn("book_id"), "book_id 应是需要重映射的外键列");
    }

    // ------------------------------------------------------------------
    // zip / 清单读取
    // ------------------------------------------------------------------

    private static Map<String, byte[]> readZip(Path path) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    result.put(entry.getName(), out.toByteArray());
                }
            }
        }
        return result;
    }

    private static Map<String, Object> manifestOf(Map<String, byte[]> zip) {
        byte[] bytes = zip.get("manifest.json");
        if (bytes == null) throw new AssertionError("样本缺少 manifest.json");
        return Json.obj(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
    }

    private static List<BackupManifest.Entry> entriesOf(Map<String, Object> manifest) {
        List<BackupManifest.Entry> entries = new ArrayList<>();
        for (Object item : Json.arr(manifest.get("entries"))) {
            Map<String, Object> row = Json.obj(item);
            BackupManifest.EntryKind kind;
            try {
                kind = BackupManifest.EntryKind.valueOf(Json.str(row.get("kind")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new AssertionError("非法条目类型：" + row.get("kind"));
            }
            entries.add(new BackupManifest.Entry(Json.str(row.get("path")), kind,
                    Json.str(row.get("sha256")), Json.num(row.get("size")), Json.str(row.get("part"))));
        }
        return entries;
    }

    // ------------------------------------------------------------------
    // 纯 JDK 最小 JSON 解析器（严格：拒绝截断文本、非法转义与尾随字符）
    // ------------------------------------------------------------------

    /** 解析失败信号；仅用于断言损坏样本被拒。 */
    static final class JsonParseException extends RuntimeException {
        JsonParseException(String message) { super(message); }
    }

    static final class Json {
        private static final Object NULL = new Object();

        private final String text;
        private int pos;

        private Json(String text) { this.text = text; }

        static Object parse(String text) {
            Json parser = new Json(text);
            Object result = parser.value();
            parser.whitespace();
            if (parser.pos != parser.text.length())
                throw new JsonParseException("JSON 尾随内容，位置 " + parser.pos);
            return result;
        }

        static Map<String, Object> obj(Object value) {
            if (!(value instanceof Map)) throw new AssertionError("期望 JSON object，实际 " + value);
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) value;
            return cast;
        }

        static List<Object> arr(Object value) {
            if (!(value instanceof List)) throw new AssertionError("期望 JSON array，实际 " + value);
            @SuppressWarnings("unchecked")
            List<Object> cast = (List<Object>) value;
            return cast;
        }

        static String str(Object value) {
            if (!(value instanceof String)) throw new AssertionError("期望 JSON string，实际 " + value);
            return (String) value;
        }

        static long num(Object value) {
            if (!(value instanceof Number)) throw new AssertionError("期望 JSON number，实际 " + value);
            return ((Number) value).longValue();
        }

        private Object value() {
            whitespace();
            if (pos >= text.length()) throw new JsonParseException("JSON 意外结束");
            char c = text.charAt(pos);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') return literal("true", Boolean.TRUE);
            if (c == 'f') return literal("false", Boolean.FALSE);
            if (c == 'n') return literal("null", NULL);
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw new JsonParseException("非法 JSON 字符 '" + c + "'，位置 " + pos);
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (peek('}')) { pos++; return result; }
            while (true) {
                whitespace();
                String key = string();
                whitespace();
                expect(':');
                result.put(key, value());
                whitespace();
                if (peek(',')) { pos++; continue; }
                if (peek('}')) { pos++; return result; }
                throw new JsonParseException("object 缺少 ',' 或 '}'，位置 " + pos);
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> result = new ArrayList<>();
            whitespace();
            if (peek(']')) { pos++; return result; }
            while (true) {
                result.add(value());
                whitespace();
                if (peek(',')) { pos++; continue; }
                if (peek(']')) { pos++; return result; }
                throw new JsonParseException("array 缺少 ',' 或 ']'，位置 " + pos);
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw new JsonParseException("字符串含控制字符");
                if (c != '\\') { out.append(c); continue; }
                if (pos >= text.length()) throw new JsonParseException("转义序列被截断");
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (pos + 4 > text.length()) throw new JsonParseException("Unicode 转义被截断");
                        try {
                            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException error) {
                            throw new JsonParseException("非法 Unicode 转义");
                        }
                        pos += 4;
                        break;
                    default: throw new JsonParseException("非法转义 '\\" + escape + "'");
                }
            }
            throw new JsonParseException("字符串未闭合");
        }

        private Number number() {
            int start = pos;
            if (peek('-')) pos++;
            if (pos >= text.length()) throw new JsonParseException("数字被截断");
            if (text.charAt(pos) == '0') pos++;
            else if (text.charAt(pos) >= '1' && text.charAt(pos) <= '9') {
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            } else throw new JsonParseException("非法数字，位置 " + pos);
            boolean floating = false;
            if (pos < text.length() && text.charAt(pos) == '.') {
                floating = true;
                pos++;
                if (pos >= text.length() || !Character.isDigit(text.charAt(pos)))
                    throw new JsonParseException("小数部分非法");
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                floating = true;
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                if (pos >= text.length() || !Character.isDigit(text.charAt(pos)))
                    throw new JsonParseException("指数部分非法");
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
            String token = text.substring(start, pos);
            try {
                return floating ? Double.valueOf(token) : Long.valueOf(token);
            } catch (NumberFormatException error) {
                throw new JsonParseException("数字超出范围：" + token);
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) throw new JsonParseException("非法字面量，位置 " + pos);
            pos += word.length();
            return value;
        }

        private boolean peek(char expected) {
            return pos < text.length() && text.charAt(pos) == expected;
        }

        private void expect(char expected) {
            if (!peek(expected)) throw new JsonParseException("期望 '" + expected + "'，位置 " + pos);
            pos++;
        }

        private void whitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
                else break;
            }
        }
    }
}
