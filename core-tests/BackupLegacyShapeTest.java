import org.openleaf.reader.core.BackupManifest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 历史备份「结构层」契约测试：只消费 BackupManifest.validateStructure / 字段分类纯函数，
 * 不读取任何历史 zip 本体，不触碰 LibraryRestore.commit，也不代表旧备份能恢复成功。
 *
 * <p>输出中的 SCOPE 声明是硬约束：本测试只说明 0.62 结构校验对 v1..v6 与旧形状（缺
 * reader_prefs）的接受行为，绝不是 ol037 backup-v6.zip 本体兼容的证据。</p>
 */
public final class BackupLegacyShapeTest {
    private static final String SHA = "a".repeat(64);
    private static int passed = 0;

    public static void main(String[] args) {
        System.out.println("SCOPE: structural contract only; ol037 backup-v6.zip body absent");
        versionAcceptance();
        versionRejection();
        malformedEntries();
        legacyShapeAcceptance();
        deviceFieldGuards();
        System.out.println("PASS BackupLegacyShapeTest (" + passed + " checks)");
        System.out.println("SCOPE: structural contract only; ol037 backup-v6.zip body absent");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        passed++;
    }

    private static BackupManifest.Entry entry(String path) {
        return new BackupManifest.Entry(path, BackupManifest.EntryKind.DATA, SHA, 2, "LIBRARY");
    }

    private static void expectNoFatal(List<BackupManifest.Issue> issues, String label) {
        for (BackupManifest.Issue issue : issues)
            if (issue.fatal) throw new AssertionError(label + " 不应有致命问题：" + issues);
        passed++;
    }

    private static void expectFatal(List<BackupManifest.Issue> issues,
                                    String messageFragment, String expectedPath) {
        for (BackupManifest.Issue issue : issues) {
            if (issue.fatal && issue.message.contains(messageFragment)
                    && (expectedPath == null || expectedPath.equals(issue.path))) {
                passed++;
                return;
            }
        }
        throw new AssertionError("应含致命问题「" + messageFragment + "」path=" + expectedPath
                + "，实际 " + issues);
    }

    /** 1..6 都不得因为「版本号本身」被拒；结构合法时结果为空。 */
    private static void versionAcceptance() {
        List<BackupManifest.Entry> valid = Collections.singletonList(entry("data/books.json"));
        for (int version = 1; version <= BackupManifest.SCHEMA_VERSION; version++) {
            List<BackupManifest.Issue> issues = BackupManifest.validateStructure(version, valid);
            expectNoFatal(issues, "v" + version);
            check(issues.isEmpty(), "v" + version + " 的合法条目应零问题，实际 " + issues);
        }
        System.out.println("VERSION_ACCEPT: 1.." + BackupManifest.SCHEMA_VERSION
                + " accepted structurally (no version-only fatal)");
    }

    /** 0 / 7 / -1 必须因版本本身致命拒绝。 */
    private static void versionRejection() {
        List<BackupManifest.Entry> valid = Collections.singletonList(entry("data/books.json"));
        for (int version : new int[]{0, 7, -1}) {
            List<BackupManifest.Issue> issues = BackupManifest.validateStructure(version, valid);
            expectFatal(issues, "不支持的清单版本", "manifest");
        }
        System.out.println("VERSION_REJECT: v0 / v7 / v-1 fatal at structure layer");
    }

    /** 逐项畸形断言：空 paths、null 列表、null 条目、重复路径、穿越、绝对、SHA/size/part。 */
    private static void malformedEntries() {
        List<BackupManifest.Issue> nullList = BackupManifest.validateStructure(6, null);
        expectFatal(nullList, "条目列表为空", "manifest");

        List<BackupManifest.Issue> nullEntry = BackupManifest.validateStructure(
                6, Arrays.asList(entry("data/books.json"), null));
        expectFatal(nullEntry, "存在空条目", "manifest");

        List<BackupManifest.Issue> emptyPath = BackupManifest.validateStructure(
                6, Collections.singletonList(entry("  ")));
        expectFatal(emptyPath, "条目路径为空", "  ");

        List<BackupManifest.Issue> duplicate = BackupManifest.validateStructure(
                6, Arrays.asList(entry("data/books.json"), entry("data/books.json")));
        expectFatal(duplicate, "条目路径重复", "data/books.json");

        List<BackupManifest.Issue> traversal = BackupManifest.validateStructure(
                6, Collections.singletonList(entry("../outside.json")));
        expectFatal(traversal, "路径含穿越或绝对路径", "../outside.json");

        List<BackupManifest.Issue> absolute = BackupManifest.validateStructure(
                6, Collections.singletonList(entry("/abs/outside.json")));
        expectFatal(absolute, "路径含穿越或绝对路径", "/abs/outside.json");

        List<BackupManifest.Issue> badSha = BackupManifest.validateStructure(6,
                Collections.singletonList(new BackupManifest.Entry(
                        "data/books.json", BackupManifest.EntryKind.DATA, "not-a-sha", 2, "LIBRARY")));
        expectFatal(badSha, "SHA-256 格式非法", "data/books.json");

        List<BackupManifest.Issue> negativeSize = BackupManifest.validateStructure(6,
                Collections.singletonList(new BackupManifest.Entry(
                        "data/books.json", BackupManifest.EntryKind.DATA, SHA, -1, "LIBRARY")));
        expectFatal(negativeSize, "字节数为负", "data/books.json");

        List<BackupManifest.Issue> badPart = BackupManifest.validateStructure(6,
                Collections.singletonList(new BackupManifest.Entry(
                        "data/books.json", BackupManifest.EntryKind.DATA, SHA, 2, "INVALID")));
        expectFatal(badPart, "能力分类非法", "data/books.json");

        System.out.println("MALFORMED: null list / null element / empty path / duplicate / traversal"
                + " / absolute / bad SHA / negative size / bad part all fatal");
    }

    /**
     * 旧包缺 data/reader_prefs.json 段时结构层不致命拒绝：旧备份允许没有阅读锚点。
     * 这仅是结构层接受（structural contract only），不是恢复成功或进度迁移的保证。
     */
    private static void legacyShapeAcceptance() {
        List<BackupManifest.Entry> oldShape = Arrays.asList(
                entry("data/books.json"), entry("data/annotations.json"),
                new BackupManifest.Entry("files/legacy-book.txt", BackupManifest.EntryKind.FILE,
                        SHA, 16, "ARCHIVE"));
        boolean hasPrefs = false;
        for (BackupManifest.Entry e : oldShape) if (e.path.contains("reader_prefs")) hasPrefs = true;
        check(!hasPrefs, "旧形状夹具应不含 data/reader_prefs.json");
        List<BackupManifest.Issue> issues = BackupManifest.validateStructure(4, oldShape);
        expectNoFatal(issues, "旧包缺 reader_prefs");
        check(issues.isEmpty(), "旧形状结构校验应零问题，实际 " + issues);
        System.out.println("LEGACY_SHAPE: missing data/reader_prefs.json accepted structurally"
                + " (structural acceptance only, NOT a restore guarantee)");
    }

    /** 防止未来把 SAF URI 或设备内文件名误写进可迁移备份。 */
    private static void deviceFieldGuards() {
        check(BackupManifest.isDeviceOnly("book_references.source_uri"),
                "book_references.source_uri 必须是设备字段");
        check(BackupManifest.isRegenerateField("books.file"),
                "books.file 必须是重新生成的设备内文件名");
        check(BackupManifest.isDeviceOnlyTable("book_references"),
                "book_references 必须是整表排除的设备表");

        String[] sections = BackupManifest.DATA_SECTIONS;
        boolean hasPrefs = false;
        for (String section : sections) if ("reader_prefs".equals(section)) hasPrefs = true;
        check(hasPrefs, "DATA_SECTIONS 必须包含 reader_prefs");
        check(16 == sections.length, "DATA_SECTIONS 应为 16 段，实际 " + sections.length);
        System.out.println("DEVICE_GUARDS: SAF URI / books.file / book_references excluded;"
                + " DATA_SECTIONS=16 with reader_prefs");
    }
}
