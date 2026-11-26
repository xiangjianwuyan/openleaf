import org.openleaf.reader.core.AudioCapability;
import org.openleaf.reader.core.AudioCapability.Level;

/**
 * 能力探测降级文案回归（S3）：四象限分类、三态文案与一次性提示判定。纯 JVM 无依赖。
 * audibleActually 按「EQ 有非零增益 / 限幅开 / 频谱开」口径由调用方给出。
 */
public final class AudioCapabilityTest {
    private static int passed;

    public static void main(String[] args) {
        test("classify 四象限", AudioCapabilityTest::quadrants);
        test("message 三态", AudioCapabilityTest::wording);
        test("shouldNotifyOnce 仅 DEGRADED", AudioCapabilityTest::notifyOnce);
        System.out.println("PASS: " + passed + " audio capability checks");
    }

    private static void quadrants() {
        eq(Level.BYPASSED, AudioCapability.classify(true, false, false), "未开音效→BYPASSED");
        eq(Level.BYPASSED, AudioCapability.classify(false, false, true), "未开音效即使非 PCM 也→BYPASSED");
        eq(Level.DEGRADED, AudioCapability.classify(false, true, true), "开了非 PCM→DEGRADED");
        eq(Level.DEGRADED, AudioCapability.classify(false, true, false), "开了非 PCM 且未实际处理→DEGRADED");
        eq(Level.DEGRADED, AudioCapability.classify(true, true, false), "PCM 但 EQ 全 0 dB→DEGRADED");
        eq(Level.SUPPORTED, AudioCapability.classify(true, true, true), "PCM 且有增益→SUPPORTED");
        System.out.println("  [四象限] 未开=" + AudioCapability.classify(true, false, false)
                + " 开了非PCM=" + AudioCapability.classify(false, true, true)
                + " 开了PCM全0dB=" + AudioCapability.classify(true, true, false)
                + " 开了PCM有增益=" + AudioCapability.classify(true, true, true));
    }

    private static void wording() {
        eq("", AudioCapability.message(Level.SUPPORTED), "SUPPORTED 位透明不打扰");
        eq("", AudioCapability.message(Level.BYPASSED), "BYPASSED 默认全关不打扰");
        eq("当前音频未应用音效处理（格式不支持或 EQ 未调整），按原始音质播放",
                AudioCapability.message(Level.DEGRADED), "DEGRADED 文案逐字匹配");
        check(!AudioCapability.message(Level.DEGRADED).isEmpty(), "DEGRADED 必须有提示文案");
        check(!AudioCapability.message(Level.DEGRADED).contains("音效处理中"), "降级态不得误报已生效");
        System.out.println("  [文案] SUPPORTED='" + AudioCapability.message(Level.SUPPORTED)
                + "' BYPASSED='" + AudioCapability.message(Level.BYPASSED)
                + "' DEGRADED='" + AudioCapability.message(Level.DEGRADED) + "'");
    }

    private static void notifyOnce() {
        check(AudioCapability.shouldNotifyOnce(Level.DEGRADED), "DEGRADED 应提示一次");
        check(!AudioCapability.shouldNotifyOnce(Level.SUPPORTED), "SUPPORTED 不提示");
        check(!AudioCapability.shouldNotifyOnce(Level.BYPASSED), "BYPASSED 不提示");
    }

    private static void eq(Object expected, Object actual, String message) {
        if (!expected.equals(actual))
            throw new AssertionError(message + "：expected " + expected + "; got " + actual);
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
