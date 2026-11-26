import org.openleaf.reader.core.AudioDsp;
import org.openleaf.reader.core.AudioEq;

/** 阶段 2-A EQ core 回归；纯 JVM、无 JUnit、断言始终启用。 */
public final class AudioEqTest {
    private static int passed;

    public static void main(String[] args) {
        identityAtZeroDb();
        gainsBoundedAndFiniteAcrossRates();
        biquadRespondsToGain();
        nanNeverPropagates();
        clampAndMalformed();
        softClipContract();
        System.out.println("AudioEqTest: " + passed + " checks passed");
    }

    private static void identityAtZeroDb() {
        float[] zero = new float[AudioEq.BANDS.length];
        AudioEq.Coeff[] cs = AudioEq.coefficients(48000f, zero);
        eq(AudioEq.BANDS.length, cs.length);
        for (AudioEq.Coeff c : cs) yes(c.isIdentity(), "0dB 段应为单位增益系数");
        AudioEq.Biquad[] filters = AudioEq.filters(44100f, null);
        float[] samples = {0.1f, -0.5f, 0.75f, -0.125f, 1f};
        for (AudioEq.Biquad f : filters)
            for (float s : samples) approx(s, f.process(s), 1e-6f, "0dB bit 透明");
        passed++;
    }

    private static void gainsBoundedAndFiniteAcrossRates() {
        int[] rates = {8000, 44100, 48000};
        float[] gains = {6f, -6f, 3f, -3f, 9f};
        for (int rate : rates) {
            AudioEq.Coeff[] cs = AudioEq.coefficients(rate, gains);
            for (AudioEq.Coeff c : cs) {
                yes(Float.isFinite(c.b0) && Float.isFinite(c.b1) && Float.isFinite(c.b2), "b 系数有限");
                yes(Float.isFinite(c.a1) && Float.isFinite(c.a2), "a 系数有限");
                yes(Math.abs(c.a1) <= 2.5f && Math.abs(c.a2) <= 1.5f, "系数在稳定区间");
            }
        }
        passed++;
    }

    private static void biquadRespondsToGain() {
        AudioEq.Coeff boost = AudioEq.peaking(48000f, 910f, 1f, 12f);
        yes(!boost.isIdentity(), "高增益段不是单位增益");
        AudioEq.Biquad f = new AudioEq.Biquad(boost);
        float max = 0f;
        // 910Hz 附近输入在 48k 下应获得可见增益；只要求响应非零且有限，不依赖听感
        for (int i = 0; i < 4096; i++) {
            float y = f.process((float) Math.sin(2 * Math.PI * 910 * i / 48000.0));
            yes(Float.isFinite(y), "输出有限");
            max = Math.max(max, Math.abs(y));
        }
        yes(max > 0.5f, "EQ 对目标频段有响应");
        passed++;
    }

    private static void nanNeverPropagates() {
        AudioEq.Biquad f = new AudioEq.Biquad(AudioEq.peaking(44100f, 230f, 1f, 6f));
        approx(0f, f.process(Float.NaN), 0f, "NaN 入返回 0");
        approx(0f, f.process(Float.POSITIVE_INFINITY), 0f, "Inf 入返回 0");
        approx(0f, f.process(Float.NEGATIVE_INFINITY), 0f, "-Inf 入返回 0");
        // 污染后仍可正常处理正常信号
        float y = f.process(0.5f);
        yes(Float.isFinite(y), "污染后恢复正常");
        passed++;
    }

    private static void clampAndMalformed() {
        float[] clamped = AudioEq.clampGains(new float[]{99f, -99f, Float.NaN, Float.POSITIVE_INFINITY, 3f});
        approx(AudioEq.MAX_DB, clamped[0], 0f);
        approx(-AudioEq.MAX_DB, clamped[1], 0f);
        approx(0f, clamped[2], 0f);
        approx(0f, clamped[3], 0f);
        approx(3f, clamped[4], 0f);
        eq(AudioEq.BANDS.length, AudioEq.clampGains(null).length);
        for (int rate : new int[]{0, -44100, Integer.MAX_VALUE}) {
            AudioEq.Coeff c = AudioEq.peaking(rate, 910f, 1f, 6f);
            yes(c.isIdentity(), "非法采样率回退单位增益");
        }
        passed++;
    }

    private static void softClipContract() {
        approx(0.5f, AudioDsp.softClip(0.5f), 0f, "阈值内逐字通过");
        approx(0.95f, AudioDsp.softClip(0.95f), 0f, "阈值点连续");
        yes(Math.abs(AudioDsp.softClip(2f)) <= 1f, "大信号不溢出 1");
        yes(AudioDsp.softClip(-2f) < 0f, "负号保持");
        approx(0f, AudioDsp.softClip(Float.NaN), 0f, "NaN 归零");
        passed++;
    }

    private interface Check { void run(); }
    private static void test(String name, Check c) { c.run(); }
    private static void yes(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
    private static void eq(Object a, Object b) { if (!a.equals(b)) throw new AssertionError("期望 " + a + " 实际 " + b); }
    private static void approx(float a, float b, float tol) { if (Math.abs(a - b) > tol) throw new AssertionError("期望 " + a + "±" + tol + " 实际 " + b); }
    private static void approx(float a, float b, float tol, String msg) { if (Math.abs(a - b) > tol) throw new AssertionError(msg + ": " + a + "±" + tol + " vs " + b); }
}
