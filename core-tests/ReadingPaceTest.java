import org.openleaf.reader.core.ReadingPace;

/** 阅读页章剩余字数与预计剩余时间测试。 */
public class ReadingPaceTest {
    static int passed = 0;
    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); passed++; }
    static void eq(long actual, long expected, String msg) {
        check(actual == expected, msg + " (expected " + expected + ", got " + actual + ")");
    }

    public static void main(String[] args) {
        estimateSeconds();
        footerLabel();
        System.out.println("PASS: " + passed + " reading pace checks");
    }

    static void estimateSeconds() {
        eq(ReadingPace.estimateSeconds(0, 400), 0, "0 字应为 0 秒");
        eq(ReadingPace.estimateSeconds(-5, 400), 0, "负数应为 0 秒");
        eq(ReadingPace.estimateSeconds(400, 400), 60, "默认速率 400 字应为 60 秒");
        eq(ReadingPace.estimateSeconds(400, 0), 60, "速率<=0 应用默认 400");
        eq(ReadingPace.estimateSeconds(400, -1), 60, "负速率应用默认 400");
        eq(ReadingPace.estimateSeconds(200, 400), 30, "半分钟向上取整");
        eq(ReadingPace.estimateSeconds(1, 400), 1, "1 字应向上取整到 1 秒");
        eq(ReadingPace.estimateSeconds(401, 400), 61, "401 字向上取整到 61 秒");
        eq(ReadingPace.estimateSeconds(200, 300), 40, "自定义速率 300 字/分");
        eq(ReadingPace.estimateSeconds(Integer.MAX_VALUE, 1), (long) Integer.MAX_VALUE * 60L,
                "大数不溢出：int 上限字数按 1 字/分仍精确");
    }

    static void footerLabel() {
        check("本章已读完".equals(ReadingPace.chapterFooterLabel(0, 400)), "0 字应提示已读完");
        check("本章已读完".equals(ReadingPace.chapterFooterLabel(-1, 400)), "负数应提示已读完");
        check("剩余约 50 字 · 预计 1 分钟".equals(ReadingPace.chapterFooterLabel(50, 400)), "几十字应至少 1 分钟");
        check("剩余约 400 字 · 预计 1 分钟".equals(ReadingPace.chapterFooterLabel(400, 400)), "整分钟");
        check("剩余约 401 字 · 预计 2 分钟".equals(ReadingPace.chapterFooterLabel(401, 400)), "刚超整分钟应进位");
        check("剩余约 23600 字 · 预计 59 分钟".equals(ReadingPace.chapterFooterLabel(23600, 400)), "59 分钟边界");
        check("剩余约 23601 字 · 预计 60 分钟".equals(ReadingPace.chapterFooterLabel(23601, 400)), "59→60 进位");
        check("剩余约 200 字 · 预计 1 分钟".equals(ReadingPace.chapterFooterLabel(200, 0)), "章脚默认速率");
        check("剩余约 3000 字 · 预计 10 分钟".equals(ReadingPace.chapterFooterLabel(3000, 300)), "章脚自定义速率");
    }
}
