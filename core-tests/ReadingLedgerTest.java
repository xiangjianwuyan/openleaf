import org.openleaf.reader.core.ReadingLedger;
import java.util.*;

/** 阅读时长分列与稳定事件键测试。 */
public class ReadingLedgerTest {
    static int passed = 0;
    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); passed++; }

    public static void main(String[] args) {
        eventKeyStable();
        eventKeyDistinct();
        dedupe();
        byKind();
        estimate();
        labels();
        System.out.println("PASS ReadingLedgerTest (" + passed + " checks)");
    }

    static void eventKeyStable() {
        String a = ReadingLedger.eventKey("b1", ReadingLedger.Kind.TEXT, "2026-11-22", 12345L);
        String b = ReadingLedger.eventKey("b1", ReadingLedger.Kind.TEXT, "2026-11-22", 12345L);
        check(a.equals(b), "同一分段应得到相同事件键");
        check(a.length() == 64, "事件键应为 SHA-256 hex");
    }

    static void eventKeyDistinct() {
        String base = ReadingLedger.eventKey("b1", ReadingLedger.Kind.TEXT, "2026-11-22", 1L);
        check(!base.equals(ReadingLedger.eventKey("b1", ReadingLedger.Kind.TEXT, "2026-11-22", 2L)), "不同分段应不同键");
        check(!base.equals(ReadingLedger.eventKey("b1", ReadingLedger.Kind.AUDIO, "2026-11-22", 1L)), "不同kind应不同键");
        check(!base.equals(ReadingLedger.eventKey("b2", ReadingLedger.Kind.TEXT, "2026-11-22", 1L)), "不同书应不同键");
        check(!base.equals(ReadingLedger.eventKey("b1", ReadingLedger.Kind.TEXT, "2026-11-23", 1L)), "不同天应不同键");
    }

    static void dedupe() {
        String k1 = ReadingLedger.eventKey("b1", ReadingLedger.Kind.TEXT, "2026-11-22", 1L);
        String k2 = ReadingLedger.eventKey("b1", ReadingLedger.Kind.AUDIO, "2026-11-22", 2L);
        List<ReadingLedger.Segment> segments = Arrays.asList(
                new ReadingLedger.Segment(k1, "b1", "2026-11-22", ReadingLedger.Kind.TEXT, 10),
                new ReadingLedger.Segment(k1, "b1", "2026-11-22", ReadingLedger.Kind.TEXT, 10),
                new ReadingLedger.Segment(k2, "b1", "2026-11-22", ReadingLedger.Kind.AUDIO, 20));
        check(ReadingLedger.dedupe(segments).size() == 2, "重复事件键应去重");
        check(ReadingLedger.sumAll(ReadingLedger.dedupe(segments)) == 30, "去重后时长应正确");
        check(ReadingLedger.sumAll(segments) == 40, "未去重时重复计入（对比用）");
    }

    static void byKind() {
        String k = ReadingLedger.eventKey("b", ReadingLedger.Kind.TEXT, "d", 1L);
        List<ReadingLedger.Segment> segments = Arrays.asList(
                new ReadingLedger.Segment(k, "b", "d", ReadingLedger.Kind.TEXT, 60),
                new ReadingLedger.Segment(k, "b", "d", ReadingLedger.Kind.COMIC, 30),
                new ReadingLedger.Segment(k, "b", "d", ReadingLedger.Kind.AUDIO, 120));
        Map<ReadingLedger.Kind, Long> totals = ReadingLedger.totalsByKind(segments);
        check(totals.get(ReadingLedger.Kind.TEXT) == 60, "文本时长应分列");
        check(totals.get(ReadingLedger.Kind.COMIC) == 30, "漫画时长应分列");
        check(totals.get(ReadingLedger.Kind.AUDIO) == 120, "音频时长应分列");
        check("漫画阅读".equals(ReadingLedger.Kind.COMIC.label()), "标签应正确");
    }

    static void estimate() {
        check(ReadingLedger.estimateRemainingSeconds(600, 0.5f) == 600, "读一半用600秒应剩600秒");
        check(ReadingLedger.estimateRemainingSeconds(0, 0.5f) < 0, "无已用时长不可估算");
        check(ReadingLedger.estimateRemainingSeconds(600, 0f) < 0, "进度0不可估算");
        check(ReadingLedger.estimateRemainingSeconds(600, 1f) < 0, "读完不可估算");
        check(ReadingLedger.estimateRemainingSeconds(600, Float.NaN) < 0, "NaN不可估算");
    }

    static void labels() {
        check(ReadingLedger.estimatedLabel(-1).contains("暂无"), "不可估算应提示");
        check(ReadingLedger.estimatedLabel(30).contains("估算"), "秒级应标估算");
        check(ReadingLedger.estimatedLabel(600).contains("估算"), "分钟级应标估算");
        check(ReadingLedger.estimatedLabel(7200).contains("估算"), "小时级应标估算");
    }
}
