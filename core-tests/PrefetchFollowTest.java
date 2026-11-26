import org.openleaf.reader.core.PrefetchFollow;
import java.util.*;

/** 有界预取计划回归（生产在用的 clamp / key / plan）。 */
public final class PrefetchFollowTest {
    private static int passed;

    public static void main(String[] args) {
        clampAndKey();
        boundedPlan();
        System.out.println("PASS: " + passed + " prefetch checks");
    }

    private static void clampAndKey() {
        eq(2, PrefetchFollow.clampCount(0, true));
        eq(10, PrefetchFollow.clampCount(99, true));
        eq(5, PrefetchFollow.clampCount(5, true));
        eq(1, PrefetchFollow.clampCount(0, false));
        eq(3, PrefetchFollow.clampCount(99, false));
        eq("src|comic|ch|path", PrefetchFollow.prefetchKey("src", "comic", "ch", "path"));
        passed++;
    }

    private static void boundedPlan() {
        List<String> candidates = Arrays.asList("a", "b", "b", "c", "d");
        Set<String> done = new HashSet<>(Collections.singleton("a"));
        Set<String> inFlight = new HashSet<>(Collections.singleton("c"));
        List<String> plan = PrefetchFollow.plan(candidates, s -> s, done, inFlight, 10, true);
        // clamp 到 10，a 已done跳过、b重复去重、c进行中跳过 → b实际被去重? 见下：b第一次加入、第二次seen重复
        eq(Arrays.asList("b", "d"), plan);
        passed++;
    }

    private static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
