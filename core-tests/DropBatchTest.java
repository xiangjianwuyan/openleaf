import org.openleaf.reader.core.DropBatch;
import org.openleaf.reader.core.DropBatch.Batch;
import org.openleaf.reader.core.DropBatch.Kind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 拖拽批量分类回归（设计 §3 / §14）：扩展名优先/MIME 兜底、混合扩展名分流、空输入、
 * 重复项、20 上限、三种 ZIP 签名与三态文案。纯 JVM，无文件 I/O、无 Android Uri。
 */
public final class DropBatchTest {
    private static int passed;

    public static void main(String[] args) {
        test("混合 txt/epub/pdf/md/cbz/zip/未知 扩展名分类", DropBatchTest::mixedExtensions);
        test("MIME 兜底且扩展名优先", DropBatchTest::mimeFallback);
        test("空输入返回全空", DropBatchTest::emptyInput);
        test("重复项被跳过并计入 duplicates", DropBatchTest::duplicates);
        test("20 个上限与 overflow 计数", DropBatchTest::cap);
        test("isZipSignature 三种签名/非签名/短数组", DropBatchTest::zipSignatures);
        test("三态文案逐字匹配", DropBatchTest::wording);
        System.out.println("PASS: " + passed + " DropBatch checks");
    }

    private static void mixedExtensions() {
        List<String> names = Arrays.asList("a.txt", "b.epub", "c.pdf", "d.md",
                "e.cbz", "f.zip", "g.docx", "h");
        Batch b = DropBatch.split(names, null);
        equal(Arrays.asList("a.txt", "b.epub", "c.pdf", "d.md"), b.books);
        equal(Arrays.asList("e.cbz", "f.zip"), b.comics);
        equal(2, b.rejected.size());
        equal("g.docx", b.rejected.get(0).name);
        equal("h", b.rejected.get(1).name);
        equal(0, b.overflow);
        equal(0, b.duplicates.size());
        System.out.println("  [mixed] books=" + b.books + " comics=" + b.comics
                + " rejected=" + b.rejected.size());
    }

    private static void mimeFallback() {
        equal(Kind.BOOK, DropBatch.classify("noext", "application/epub+zip"));
        equal(Kind.BOOK, DropBatch.classify("noext", "text/plain; charset=utf-8"));
        equal(Kind.BOOK, DropBatch.classify("noext", "application/pdf"));
        equal(Kind.COMIC, DropBatch.classify("noext", "application/zip"));
        equal(Kind.COMIC, DropBatch.classify("noext", "application/x-cbz"));
        equal(Kind.UNSUPPORTED, DropBatch.classify("noext", "application/octet-stream"));
        equal(Kind.UNSUPPORTED, DropBatch.classify(null, null));
        equal(Kind.COMIC, DropBatch.classify("x.cbz", "text/plain"), "扩展名应优先于 MIME");
        equal(Kind.BOOK, DropBatch.classify("x.TXT", null), "扩展名大小写不敏感");
    }

    private static void emptyInput() {
        Batch a = DropBatch.split(null, null);
        equal(0, a.books.size());
        equal(0, a.comics.size());
        equal(0, a.rejected.size());
        equal(0, a.overflow);
        Batch b = DropBatch.split(Collections.<String>emptyList(), null);
        equal(0, b.books.size());
        equal(0, b.comics.size());
    }

    private static void duplicates() {
        Batch b = DropBatch.split(Arrays.asList("a.txt", "a.txt", "A.TXT", "b.pdf"), null);
        equal(Arrays.asList("a.txt", "b.pdf"), b.books);
        equal(2, b.duplicates.size());
        equal("a.txt", b.duplicates.get(0));
        equal("A.TXT", b.duplicates.get(1));
        equal(0, b.overflow);
    }

    private static void cap() {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < 25; i++) names.add("f" + i + ".txt");
        Batch b = DropBatch.split(names, null);
        equal(DropBatch.MAX_DROPPED_URIS, b.books.size());
        equal(5, b.overflow);
        equal(0, b.rejected.size());
    }

    private static void zipSignatures() {
        check(DropBatch.isZipSignature(new byte[]{'P', 'K', 3, 4}), "PK0304 应识别");
        check(DropBatch.isZipSignature(new byte[]{'P', 'K', 5, 6}), "PK0506 应识别");
        check(DropBatch.isZipSignature(new byte[]{'P', 'K', 7, 8}), "PK0708 应识别");
        check(!DropBatch.isZipSignature(new byte[]{'P', 'K', 3, 5}), "非签名不应识别");
        check(!DropBatch.isZipSignature(new byte[]{'P', 'K', 3}), "短数组不应识别");
        check(!DropBatch.isZipSignature(null), "null 不应识别");
        check(!DropBatch.isZipSignature(new byte[0]), "空数组不应识别");
    }

    private static void wording() {
        equal("", DropBatch.reason(Kind.BOOK, "a.txt"));
        equal("e.cbz：未入库，可在漫画阅读器中逐个打开", DropBatch.reason(Kind.COMIC, "e.cbz"));
        equal("g.docx：仅支持 TXT/MD、EPUB、PDF；漫画请拖 CBZ/ZIP",
                DropBatch.reason(Kind.UNSUPPORTED, "g.docx"));
        check(DropBatch.reasonInvalidZip("e.cbz").contains("不是有效的 ZIP/CBZ 文件"), "签名失败文案");
        check(DropBatch.reasonPermission("a.txt").contains("文件访问授权已失效"), "授权失效文案");
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + "; got " + actual);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + "; got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void test(String name, ThrowingRunnable action) {
        try { action.run(); passed++; System.out.println("OK " + name); }
        catch (Throwable failure) { throw new AssertionError("FAILED: " + name, failure); }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run(); }
}
