import org.openleaf.reader.core.EpubCfi;
import org.openleaf.reader.core.ReadingAnchor;
import org.openleaf.reader.core.ResolvedContinueReading;
import org.openleaf.reader.core.ResolvedContinueReading.Format;

/**
 * 续读主卡闸门 ② 的纯 JVM 门禁（BASELINE-V3.md §7 / 续读研究 §2.2）。
 * 钉死 0%、坏锚点、越界页、非法 CFI、漫画 page<=0 等边界；无 JUnit，main() 自写断言。
 */
public final class ResolvedContinueReadingTest {
    private static int passed;

    public static void main(String[] args) {
        test("TXT：progress>0 + 可解析锚点才算可靠，0%/坏锚点一律不计",
                ResolvedContinueReadingTest::txtGate);
        test("EPUB：spine>0 或 CFI 可解析；坏 CFI 不得凭空可靠",
                ResolvedContinueReadingTest::epubGate);
        test("PDF：严格 0<=page<count，越界与 0 页总数不计",
                ResolvedContinueReadingTest::pdfGate);
        test("COMIC：page>0 才可靠；-1（无记录/换版本）不计",
                ResolvedContinueReadingTest::comicGate);
        test("位置文案：不显示 0%/估算值，格式与真实值一致",
                ResolvedContinueReadingTest::positionText);
        test("值对象：空文案不可展示，空字段归零，null 格式降级 TEXT",
                ResolvedContinueReadingTest::valueObject);
        System.out.println("ResolvedContinueReadingTest: " + passed + " checks passed");
    }

    private static void txtGate() {
        String good = ReadingAnchor.text("ch1", 0, -1, 0, 0, false, 0.5f).serialize();
        check(ResolvedContinueReading.isReliable(Format.TEXT, 1, good, -1, "", -1, 0, 0),
                "TXT progress=1 + 有效锚点应可靠");
        check(ResolvedContinueReading.isReliable(Format.TEXT, 100, good, -1, "", -1, 0, 0),
                "TXT progress=100 仍可靠");
        check(!ResolvedContinueReading.isReliable(Format.TEXT, 0, good, -1, "", -1, 0, 0),
                "TXT progress==0（只是点开过）必须不可靠");
        check(!ResolvedContinueReading.isReliable(Format.TEXT, -1, good, -1, "", -1, 0, 0),
                "TXT progress 缺省 -1 不可靠");
        check(!ResolvedContinueReading.isReliable(Format.TEXT, 101, good, -1, "", -1, 0, 0),
                "TXT progress>100 越界不可靠");
        check(!ResolvedContinueReading.isReliable(Format.TEXT, 5, null, -1, "", -1, 0, 0),
                "TXT 无锚点不可靠");
        check(!ResolvedContinueReading.isReliable(Format.TEXT, 5, "1|TEXT|bad", -1, "", -1, 0, 0),
                "TXT 段数不对的坏锚点不可靠");
        String meaningless = ReadingAnchor.text("", -1, -1, -1, 0, false, 0f).serialize();
        check(!ResolvedContinueReading.isReliable(Format.TEXT, 5, meaningless, -1, "", -1, 0, 0),
                "TXT 锚点无任何可定位字段不可靠");
    }

    private static void epubGate() {
        String cfi = EpubCfi.createSimple(3, 12);
        check(ResolvedContinueReading.isReliable(Format.EPUB, 0, null, 1, "", -1, 0, 0),
                "EPUB spine>0 可靠");
        check(ResolvedContinueReading.isReliable(Format.EPUB, 0, null, 0, cfi, -1, 0, 0),
                "EPUB spine==0 但 CFI 可解析可靠");
        check(!ResolvedContinueReading.isReliable(Format.EPUB, 0, null, 0, "", -1, 0, 0),
                "EPUB 无 spine 且无 CFI 不可靠");
        check(!ResolvedContinueReading.isReliable(Format.EPUB, 0, null, -1, "garbage", -1, 0, 0),
                "EPUB 坏 CFI 不可靠");
        check(!ResolvedContinueReading.isReliable(Format.EPUB, 0, null, -1,
                "ocfcfi:v1:s-1/0/0", -1, 0, 0), "EPUB CFI spine 负数不可靠");
    }

    private static void pdfGate() {
        check(ResolvedContinueReading.isReliable(Format.PDF, 0, null, -1, "", 0, 12, 0),
                "PDF 第 1 页可靠");
        check(ResolvedContinueReading.isReliable(Format.PDF, 0, null, -1, "", 11, 12, 0),
                "PDF 末页可靠");
        check(!ResolvedContinueReading.isReliable(Format.PDF, 0, null, -1, "", -1, 12, 0),
                "PDF page=-1 不可靠");
        check(!ResolvedContinueReading.isReliable(Format.PDF, 0, null, -1, "", 12, 12, 0),
                "PDF page==count 越界不可靠");
        check(!ResolvedContinueReading.isReliable(Format.PDF, 0, null, -1, "", 0, 0, 0),
                "PDF count==0 不可靠");
    }

    private static void comicGate() {
        check(ResolvedContinueReading.isReliable(Format.COMIC, 0, null, -1, "", -1, 0, 1),
                "COMIC page=1（已读第 2 页）可靠");
        check(!ResolvedContinueReading.isReliable(Format.COMIC, 0, null, -1, "", -1, 0, 0),
                "COMIC page==0（仅点开）不可靠");
        check(!ResolvedContinueReading.isReliable(Format.COMIC, 0, null, -1, "", -1, 0, -1),
                "COMIC page=-1（无记录/换版本）不可靠");
    }

    private static void positionText() {
        String good = ReadingAnchor.text("ch1", 2, -1, 0, 0, false, 0.5f).serialize();
        equal("进度 9%", ResolvedContinueReading.positionText(Format.TEXT, 9, -1, "", -1, 0, 0));
        equal("", ResolvedContinueReading.positionText(Format.TEXT, 0, -1, "", -1, 0, 0),
                "绝不显示 0%");
        equal("阅读位置 3", ResolvedContinueReading.positionText(Format.EPUB, 0, 2, "", -1, 0, 0));
        equal("第 6 / 12 页", ResolvedContinueReading.positionText(Format.PDF, 0, -1, "", 5, 12, 0));
        equal("", ResolvedContinueReading.positionText(Format.PDF, 0, -1, "", 12, 12, 0),
                "越界页不给文案");
        equal("第 4 页", ResolvedContinueReading.positionText(Format.COMIC, 0, -1, "", -1, 0, 3));
        equal("", ResolvedContinueReading.positionText(Format.COMIC, 0, -1, "", -1, 0, -1));
    }

    private static void valueObject() {
        ResolvedContinueReading r = new ResolvedContinueReading("uuid-1", "书名", Format.PDF, "第 1 / 3 页");
        equal("uuid-1", r.bookId);
        equal("书名", r.title);
        equal(Format.PDF, r.format);
        check(r.isReliable(), "有真实位置文案的卡可展示");
        check(!new ResolvedContinueReading(null, null, null, null).isReliable(), "空文案不可展示");
        equal("", new ResolvedContinueReading(null, null, null, null).bookId, "null 字段归零");
        equal(Format.TEXT, new ResolvedContinueReading("x", "x", null, "").format, "null 格式降级 TEXT");
    }

    private interface Case {
        void run();
    }

    private static void test(String name, Case body) {
        try {
            body.run();
            passed++;
        } catch (Throwable failure) {
            throw new IllegalStateException("FAIL " + name + ": " + failure, failure);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void equal(Object expected, Object actual) {
        equal(expected, actual, "值不相等");
    }

    private static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(message + "：expected=" + expected + " actual=" + actual);
        }
    }
}
