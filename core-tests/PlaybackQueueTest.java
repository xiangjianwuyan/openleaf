import org.openleaf.reader.core.PlaybackQueue;

/** 0.43.0 播放队列 core 回归；纯 JVM、无 JUnit、断言始终启用（不依赖 -ea）。 */
public final class PlaybackQueueTest {
    private static int passed;

    public static void main(String[] args) {
        insertNextIndexCases();
        nextSpeedIndexCycles();
        speedKnownAndClamped();
        System.out.println("PASS: " + passed + " playback queue checks");
    }

    private static void insertNextIndexCases() {
        // 空队列：无论 currentIndex 是多少都插在 0
        eq(0, PlaybackQueue.insertNextIndex(0, 0));
        eq(0, PlaybackQueue.insertNextIndex(-1, 0));
        eq(0, PlaybackQueue.insertNextIndex(3, 0));
        // 无当前曲目（队首）
        eq(0, PlaybackQueue.insertNextIndex(-1, 5));
        eq(0, PlaybackQueue.insertNextIndex(-3, 5));
        // 队中：当前曲目之后
        eq(1, PlaybackQueue.insertNextIndex(0, 5));
        eq(3, PlaybackQueue.insertNextIndex(2, 5));
        // 队尾：当前已是最后一首
        eq(5, PlaybackQueue.insertNextIndex(4, 5));
        // 越界：currentIndex >= size 退化为尾部追加
        eq(5, PlaybackQueue.insertNextIndex(5, 5));
        eq(5, PlaybackQueue.insertNextIndex(99, 5));
        passed++;
    }

    private static void nextSpeedIndexCycles() {
        int n = PlaybackQueue.SPEEDS.length;
        eq(5, n);
        // 正向推进：每一位都到下一个，末档回绕 0
        for (int i = 0; i < n; i++) eq((i + 1) % n, PlaybackQueue.nextSpeedIndex(i));
        eq(0, PlaybackQueue.nextSpeedIndex(n - 1));
        // 负数归一：-1 等价末档回绕到 0；-2 与 3 推进结果一致
        eq(0, PlaybackQueue.nextSpeedIndex(-1));
        eq(PlaybackQueue.nextSpeedIndex(3), PlaybackQueue.nextSpeedIndex(-2));
        // 大数取模
        eq(1, PlaybackQueue.nextSpeedIndex(n + 5));
        passed++;
    }

    private static void speedKnownAndClamped() {
        for (float s : PlaybackQueue.SPEEDS) yes(PlaybackQueue.isKnownSpeed(s), "档位应 known: " + s);
        // 1e-3 容差内
        yes(PlaybackQueue.isKnownSpeed(1.0005f), "容差内应 known");
        yes(PlaybackQueue.isKnownSpeed(0.7505f), "0.75 容差内应 known");
        // 容差外 / 非法
        yes(!PlaybackQueue.isKnownSpeed(1.01f), "容差外应 unknown");
        yes(!PlaybackQueue.isKnownSpeed(0.7f), "非档位应 unknown");
        yes(!PlaybackQueue.isKnownSpeed(Float.NaN), "NaN 应 unknown");
        yes(!PlaybackQueue.isKnownSpeed(Float.POSITIVE_INFINITY), "Inf 应 unknown");
        // clamp：区间内原样保留（含非档位 1.25 之外的 1.1）
        approx(1f, PlaybackQueue.clampSpeed(1f), 0f);
        approx(0.75f, PlaybackQueue.clampSpeed(0.75f), 0f);
        approx(2f, PlaybackQueue.clampSpeed(2f), 0f);
        approx(1.1f, PlaybackQueue.clampSpeed(1.1f), 0f);
        // clamp：闭区间边界
        approx(0.5f, PlaybackQueue.clampSpeed(0.5f), 0f);
        approx(3f, PlaybackQueue.clampSpeed(3f), 0f);
        // clamp：越界 / 非法回退 1f
        approx(1f, PlaybackQueue.clampSpeed(0.49f), 0f);
        approx(1f, PlaybackQueue.clampSpeed(3.01f), 0f);
        approx(1f, PlaybackQueue.clampSpeed(Float.NaN), 0f);
        approx(1f, PlaybackQueue.clampSpeed(Float.POSITIVE_INFINITY), 0f);
        approx(1f, PlaybackQueue.clampSpeed(Float.NEGATIVE_INFINITY), 0f);
        passed++;
    }

    private static void yes(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
    private static void eq(int a, int b) { if (a != b) throw new AssertionError("期望 " + a + " 实际 " + b); }
    private static void approx(float a, float b, float tol) {
        if (Math.abs(a - b) > tol) throw new AssertionError("期望 " + a + "±" + tol + " 实际 " + b);
    }
}
