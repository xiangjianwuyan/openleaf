import org.openleaf.reader.core.BackupBookFiles;

/**
 * DATA-1 SAF 书文件扩展名口径验证（纯 JVM，不依赖 Android）。
 *
 * <p>只验证 {@link BackupBookFiles#extensionFor} 的纯函数规则；真实 SAF 字节读取与
 * LibraryBackup 导出回路属于 Android 层，由真机取证覆盖。</p>
 */
public final class BackupBookFilesTest {
    private static int passed = 0;

    public static void main(String[] args) {
        priority();
        caseNormalization();
        invalidExtensions();
        nullAndEmptySafety();
        noCrossContamination();

        System.out.println("PASS BackupBookFilesTest (" + passed + " checks)");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        passed++;
    }

    private static void ext(String expected, String stored, String format, String display, String message) {
        String actual = BackupBookFiles.extensionFor(stored, format, display);
        check(expected.equals(actual), message + "：期望 " + expected + "，实际 " + actual);
    }

    /** 优先级：storedFile > displayName > format > .bin。 */
    private static void priority() {
        ext(".epub", "book.epub", "txt", "book.txt", "库内文件名优先于 format 与展示名");
        ext(".pdf", "a.pdf", "epub", "a.txt", "库内文件名优先于展示名");
        ext(".cbz", "", "txt", "comic.cbz", "库内文件名为空时展示名优先于 format");
        ext(".pdf", "book", "epub", "book.pdf", "库内文件名无扩展名时回退展示名");
        ext(".epub", "", "epub", "Book", "展示名无扩展名时回退 format");
        ext(".txt", "book", "txt", "", "库内与展示名都无扩展名时回退 format");
        ext(".bin", "book", "", "Book", "三者都无有效扩展名时兜底 .bin");
    }

    /** 大小写归一：storedFile / displayName / format 均归一为小写。 */
    private static void caseNormalization() {
        ext(".txt", "Book.TXT", null, null, "库内文件名大小写归一");
        ext(".pdf", null, null, "Book.PdF", "展示名大小写归一");
        ext(".mobi", null, "MOBI", "Book", "format 大小写归一");
        ext(".azw3", "book.AZW3", null, null, "含数字的扩展名大小写归一");
    }

    /** 超长 / 非法扩展名必须被拒绝并降级到下一优先级。 */
    private static void invalidExtensions() {
        ext(".bin", "book.123456789", null, null, "库内文件名 9 位扩展名拒绝");
        ext(".bin", null, null, "book.abcdefghi", "展示名 9 位扩展名拒绝");
        ext(".bin", "book.a_b", null, null, "库内文件名含下划线拒绝");
        ext(".bin", null, null, "book.a-b", "展示名含连字符拒绝");
        ext(".txt", "book.tar.gz!", "txt", null, "非法库内扩展名降级到 format");
        ext(".bin", ".epub", null, null, "整个文件名都是扩展名（隐藏文件）拒绝");
        ext(".txt", "book.", "txt", null, "空扩展名拒绝并降级到 format");
        ext(".bin", null, "abcdefghi", null, "format 超长拒绝");
        ext(".bin", null, "a_b", null, "format 含下划线拒绝");
        ext(".bin", "book.txt ", null, null, "带尾随空格的扩展名拒绝");
        ext(".txt", "book.v2.txt", null, null, "多点文件名取最后一段");
    }

    /** null / 空串一律安全返回 .bin。 */
    private static void nullAndEmptySafety() {
        ext(".bin", null, null, null, "全 null 安全兜底");
        ext(".bin", "", "", "", "全空串安全兜底");
        ext(".bin", null, "", null, "空 format 安全兜底");
        ext(".bin", null, "  ", null, "纯空白 format 安全兜底");
    }

    /** 展示名相同但扩展名不同的多本书，各自独立取源，不互相串用。 */
    private static void noCrossContamination() {
        ext(".txt", "same.txt", "epub", "same.epub", "A 书保留自身 .txt");
        ext(".epub", "same.epub", "txt", "same.txt", "B 书保留自身 .epub");
        ext(".txt", "", "epub", "same.txt", "C 书按展示名取 .txt 而非 format 的 .epub");
        ext(".epub", "", "txt", "same.epub", "D 书按展示名取 .epub 而非 format 的 .txt");
    }
}
