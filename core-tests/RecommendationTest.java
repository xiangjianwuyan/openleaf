import org.openleaf.reader.core.Recommendation;
import org.openleaf.reader.core.Recommendation.Candidate;
import org.openleaf.reader.core.Recommendation.Signal;
import org.openleaf.reader.core.Recommendation.Suggestion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地推荐算法回归（设计 §2 / §14）：空历史、冷热门标签权重、输入顺序无关、条数上限、
 * 已读降权、排除集与逐字文案。纯 JVM，无 Android / DB / SP / 网络；断言始终启用。
 */
public final class RecommendationTest {
    private static int passed;

    public static void main(String[] args) {
        test("空历史与不足 1 分钟均返回空", RecommendationTest::emptyHistory);
        test("热门标签权重高于冷门标签", RecommendationTest::hotVsCold);
        test("输入顺序无关（信号与候选均重排）", RecommendationTest::orderIndependent);
        test("结果条数上限 limit", RecommendationTest::limitCap);
        test("已读降权 0.35 且可 exclude", RecommendationTest::readPenaltyAndExclude);
        test("reason 使用信号书标题且无协同过滤措辞", RecommendationTest::reasonWording);
        test("同分按标题稳定排序", RecommendationTest::stableTitleOrder);
        System.out.println("PASS: " + passed + " Recommendation checks");
    }

    private static void emptyHistory() {
        List<Candidate> candidates = baseCandidates();
        equal(0, Recommendation.rank(Collections.<Signal>emptyList(), candidates, null, 5).size());
        long now = System.currentTimeMillis();
        List<Signal> tooShort = Arrays.asList(new Signal("s1", Arrays.asList("科幻"), 10, now));
        equal(0, Recommendation.rank(tooShort, candidates, null, 5).size());
        List<Signal> noOpen = Arrays.asList(new Signal("s1", Arrays.asList("科幻"), 5000, 0));
        equal(0, Recommendation.rank(noOpen, candidates, null, 5).size());
        equal(0, Recommendation.rank(null, candidates, null, 5).size());
        equal(0, Recommendation.rank(baseSignals(now), null, null, 5).size());
    }

    private static void hotVsCold() {
        long now = System.currentTimeMillis();
        List<Signal> signals = Arrays.asList(
                new Signal("read-hot", Arrays.asList("热门"), 3600, now),
                new Signal("read-cold", Arrays.asList("冷门"), 90, now));
        List<Candidate> candidates = Arrays.asList(
                new Candidate("c-hot", "热门候选", Arrays.asList("热门")),
                new Candidate("c-cold", "冷门候选", Arrays.asList("冷门")));
        List<Suggestion> ranked = Recommendation.rank(signals, candidates, null, 5);
        equal(2, ranked.size());
        equal("c-hot", ranked.get(0).bookId);
        check(ranked.get(0).score > ranked.get(1).score,
                "热门标签权重应高于冷门: " + ranked.get(0).score + " vs " + ranked.get(1).score);
        System.out.println("  [weight] hot=" + ranked.get(0).score + " cold=" + ranked.get(1).score);
    }

    private static void orderIndependent() {
        long now = System.currentTimeMillis();
        List<Signal> s1 = Arrays.asList(
                new Signal("s-a", Arrays.asList("科幻", "冒险"), 3600, now - 86_400_000L),
                new Signal("s-b", Arrays.asList("科幻", "历史"), 1800, now),
                new Signal("s-c", Arrays.asList("冒险"), 600, now - 2 * 86_400_000L));
        List<Signal> s2 = new ArrayList<Signal>(s1);
        Collections.reverse(s2);
        List<Candidate> c1 = Arrays.asList(
                new Candidate("c1", "星海", Arrays.asList("科幻")),
                new Candidate("c2", "远征", Arrays.asList("冒险")),
                new Candidate("c3", "长河", Arrays.asList("历史")),
                new Candidate("c4", "无人区", Arrays.asList("科幻", "冒险")));
        List<Candidate> c2 = new ArrayList<Candidate>(c1);
        Collections.reverse(c2);

        List<Suggestion> a = Recommendation.rank(s1, c1, null, 10);
        List<Suggestion> b = Recommendation.rank(s2, c2, null, 10);
        equal(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            equal(a.get(i).bookId, b.get(i).bookId);
            equal(a.get(i).title, b.get(i).title);
            equal(a.get(i).reason, b.get(i).reason);
            equal(Float.floatToIntBits(a.get(i).score), Float.floatToIntBits(b.get(i).score));
        }
        System.out.println("  [order] " + ids(a) + " == " + ids(b));
    }

    private static void limitCap() {
        long now = System.currentTimeMillis();
        List<Signal> signals = Arrays.asList(new Signal("s", Arrays.asList("科幻"), 3600, now));
        List<Candidate> many = new ArrayList<Candidate>();
        for (int i = 0; i < 10; i++) {
            many.add(new Candidate("c" + i, "候选" + i, Arrays.asList("科幻")));
        }
        equal(3, Recommendation.rank(signals, many, null, 3).size());
        equal(5, Recommendation.rank(signals, many, null, Recommendation.DEFAULT_LIMIT).size());
        equal(0, Recommendation.rank(signals, many, null, 0).size());
    }

    private static void readPenaltyAndExclude() {
        long now = System.currentTimeMillis();
        List<Signal> signals = Arrays.asList(new Signal("b1", Arrays.asList("t"), 3600, now));
        List<Candidate> candidates = Arrays.asList(
                new Candidate("b1", "已读", Arrays.asList("t")),
                new Candidate("b2", "未读", Arrays.asList("t")));
        List<Suggestion> ranked = Recommendation.rank(signals, candidates, null, 5);
        equal(2, ranked.size());
        equal("b2", ranked.get(0).bookId);
        check(ranked.get(1).score < ranked.get(0).score, "已读书应降权");
        Set<String> exclude = new HashSet<String>(Arrays.asList("b2"));
        List<Suggestion> excluded = Recommendation.rank(signals, candidates, exclude, 5);
        equal(1, excluded.size());
        equal("b1", excluded.get(0).bookId);
    }

    private static void reasonWording() {
        long now = System.currentTimeMillis();
        List<Signal> signals = Arrays.asList(new Signal("b1", Arrays.asList("t"), 3600, now));
        List<Candidate> candidates = Arrays.asList(
                new Candidate("b1", "我已读的书", Arrays.asList("t")),
                new Candidate("b2", "推荐书", Arrays.asList("t")));
        Suggestion top = Recommendation.rank(signals, candidates, null, 5).get(0);
        equal("因为你读过《我已读的书》· 同标签「t」", top.reason);
        check(!top.reason.contains("其他"), "reason 不得出现协同过滤措辞: " + top.reason);
        check(!top.reason.contains("读过这本书的人"), "reason 不得出现他人语义: " + top.reason);
        System.out.println("  [reason] " + top.reason);
    }

    private static void stableTitleOrder() {
        long now = System.currentTimeMillis();
        List<Signal> signals = Arrays.asList(new Signal("s", Arrays.asList("t"), 3600, now));
        List<Candidate> candidates = Arrays.asList(
                new Candidate("z", "B书", Arrays.asList("t")),
                new Candidate("a", "A书", Arrays.asList("t")));
        List<Suggestion> ranked = Recommendation.rank(signals, candidates, null, 5);
        equal("A书", ranked.get(0).title);
        equal("B书", ranked.get(1).title);
        equal(Float.floatToIntBits(ranked.get(0).score), Float.floatToIntBits(ranked.get(1).score));
    }

    private static List<Signal> baseSignals(long now) {
        return Arrays.asList(new Signal("s1", Arrays.asList("科幻"), 3600, now));
    }

    private static List<Candidate> baseCandidates() {
        return Arrays.asList(new Candidate("c1", "候选一", Arrays.asList("科幻")));
    }

    private static String ids(List<Suggestion> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i).bookId);
        }
        return sb.append("]").toString();
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + "; got " + actual);
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
