import org.openleaf.reader.core.BackupManifest;
import org.openleaf.reader.core.ContentContracts;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.openleaf.reader.core.BackupManifest.Issue;

/** 可迁移书库备份清单核心测试。 */
public class BackupManifestTest {
    static int passed = 0;
    static String sha(String s) { return ContentContracts.hashHex(s.getBytes(StandardCharsets.UTF_8)); }

    public static void main(String[] args) {
        structureValid();
        rejectBadVersion();
        rejectTraversal();
        rejectDuplicateAndBadHash();
        verifyPayload();
        remapIds();
        deviceFieldExclusion();
        fingerprintIgnoresFormatting();
        fingerprintDetectsChange();
        System.out.println("PASS BackupManifestTest (" + passed + " checks)");
    }

    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); passed++; }

    static BackupManifest.Entry entry(String path, String content, String part) {
        return new BackupManifest.Entry(path, BackupManifest.EntryKind.DATA, sha(content), content.getBytes(StandardCharsets.UTF_8).length, part);
    }

    static void structureValid() {
        List<BackupManifest.Entry> entries = Arrays.asList(
                entry("data/books.json", "[]", "LIBRARY"),
                new BackupManifest.Entry("files/a1.txt", BackupManifest.EntryKind.FILE, sha("abc"), 3, "ARCHIVE"));
        check(BackupManifest.validateStructure(1, entries).isEmpty(), "合法清单应无问题");
    }

    static void rejectBadVersion() {
        List<Issue> issues = BackupManifest.validateStructure(999, new ArrayList<BackupManifest.Entry>());
        check(!issues.isEmpty() && issues.get(0).fatal, "未知高版本应致命拒绝");
        check(BackupManifest.validateStructure(0, new ArrayList<BackupManifest.Entry>()).get(0).fatal, "版本0应拒绝");
    }

    static void rejectTraversal() {
        List<BackupManifest.Entry> entries = Arrays.asList(
                new BackupManifest.Entry("../evil.txt", BackupManifest.EntryKind.FILE, sha("x"), 1, "ARCHIVE"));
        List<Issue> issues = BackupManifest.validateStructure(1, entries);
        boolean found = false;
        for (Issue i : issues) if (i.path.contains("evil") && i.fatal) found = true;
        check(found, "路径穿越应致命拒绝");
        check(!BackupManifest.safePath("/abs/path"), "绝对路径不安全");
        check(!BackupManifest.safePath("C:/windows"), "盘符路径不安全");
        check(BackupManifest.safePath("files/book.txt"), "正常相对路径安全");
    }

    static void rejectDuplicateAndBadHash() {
        BackupManifest.Entry e = entry("data/x.json", "a", "LIBRARY");
        List<BackupManifest.Entry> entries = Arrays.asList(e, e);
        List<Issue> issues = BackupManifest.validateStructure(1, entries);
        boolean dup = false;
        for (Issue i : issues) if (i.message.contains("重复")) dup = true;
        check(dup, "重复路径应拒绝");
        List<BackupManifest.Entry> bad = Arrays.asList(
                new BackupManifest.Entry("data/y.json", BackupManifest.EntryKind.DATA, "nothex", 1, "LIBRARY"));
        check(!BackupManifest.validateStructure(1, bad).isEmpty(), "非法哈希应拒绝");
    }

    static void verifyPayload() {
        BackupManifest.Entry e = entry("files/z.txt", "hello", "ARCHIVE");
        check(BackupManifest.verifyPayload(e, sha("hello"), 5) == null, "一致载荷应通过");
        check(BackupManifest.verifyPayload(e, sha("world"), 5) != null, "哈希不符应报错");
        Issue size = BackupManifest.verifyPayload(e, sha("hello"), 4);
        check(size != null && size.fatal && size.message.contains("字节数"), "字节数不符应致命");
    }

    static void remapIds() {
        Map<String, String> existing = new HashMap<>();
        existing.put(sha("content-a"), "target-a");
        Map<String, String> sourceHash = new HashMap<>();
        sourceHash.put("src-1", sha("content-a"));
        sourceHash.put("src-2", sha("content-b"));
        final int[] counter = {0};
        Map<String, String> mapping = BackupManifest.remapBookIds(
                Arrays.asList("src-1", "src-2", "src-3"), existing, sourceHash,
                () -> "new-" + (++counter[0]));
        check("target-a".equals(mapping.get("src-1")), "sha去重命中应复用目标id");
        check("new-1".equals(mapping.get("src-2")), "未命中应生成新id");
        check("new-2".equals(mapping.get("src-3")), "无hash应生成新id");
    }

    static void deviceFieldExclusion() {
        check(BackupManifest.isDeviceOnly("book_references.source_uri"), "SAF URI应排除");
        check(BackupManifest.isDeviceOnly("folders.source_tree"), "目录URI应排除");
        check(BackupManifest.isDeviceOnly("chapter_cache.cache_path"), "缓存绝对路径应排除");
        check(!BackupManifest.isDeviceOnly("annotations.anchor"), "标注锚点是内容派生，应保留");
        check(BackupManifest.isDeviceOnlyTable("pending_writes"), "待写事务表应整表排除");
        check(BackupManifest.isDeviceOnlyTable("jobs"), "任务表应整表排除");
        check(!BackupManifest.isDeviceOnlyTable("books"), "书表应保留");
    }

    static void fingerprintIgnoresFormatting() {
        String a = "第一行  \r\n第二行\r\n";
        String b = "第一行\n第二行\n";
        check(BackupManifest.contentFingerprint(a).equals(BackupManifest.contentFingerprint(b)),
                "换行风格和行尾空白不应影响指纹");
    }

    static void fingerprintDetectsChange() {
        String a = "第一行\n第二行\n";
        String b = "第一行\n第二行改了\n";
        check(!BackupManifest.contentFingerprint(a).equals(BackupManifest.contentFingerprint(b)),
                "语义变化应改变指纹");
    }
}
