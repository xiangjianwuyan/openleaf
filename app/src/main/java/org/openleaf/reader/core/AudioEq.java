package org.openleaf.reader.core;

/**
 * 5 段 peaking EQ 的 biquad 系数与逐样本处理（纯 JVM，无 Android 依赖）。
 *
 * 阶段 2-A 音频内核：系数采用 RBJ Audio EQ Cookbook 的 peakingEQ 公式，
 * 每段中心频率 60/230/910/3600/14000 Hz、Q=1.0；增益以 dB 表示，0 dB 时
 * 系数退化为单位增益（输出逐字等于输入，bit 透明）。NaN/Inf 输入不传播。
 */
public final class AudioEq {
    private AudioEq() { }

    /** 5 段中心频率（Hz）。 */
    public static final float[] BANDS = {60f, 230f, 910f, 3600f, 14000f};
    /** 默认 Q；peaking 带宽由 Q 决定。 */
    public static final float DEFAULT_Q = 1.0f;
    /** 耳感安全的增益上下限（dB）。 */
    public static final float MAX_DB = 12f;

    /** biquad 传递函数系数（已归一 b0=1 之前的原始系数也可，这里存归一后的 b0..b2/a1..a2）。 */
    public static final class Coeff {
        public final float b0, b1, b2, a1, a2;
        public Coeff(float b0, float b1, float b2, float a1, float a2) {
            this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2;
        }
        /** 0 dB 时是否为单位增益（a 归一、b=[1,0,0]）。 */
        public boolean isIdentity() {
            return approx(b0, 1f) && approx(b1, 0f) && approx(b2, 0f)
                    && approx(a1, 0f) && approx(a2, 0f);
        }
    }

    /**
     * 计算单段 peaking EQ 的归一 biquad 系数。
     * gainDb 为 0 时返回单位增益；采样率/f0 非法时返回单位增益（不抛异常到音频线程）。
     */
    public static Coeff peaking(float sampleRate, float f0, float q, float gainDb) {
        if (!Float.isFinite(sampleRate) || !Float.isFinite(f0) || !Float.isFinite(q)
                || !Float.isFinite(gainDb) || sampleRate <= 0 || sampleRate > 384000f
                || f0 <= 0 || q <= 0)
            return identity();
        if (Math.abs(gainDb) < 1e-6f) return identity();
        double sr = sampleRate, freq = Math.min(f0, sr * 0.49);
        double omega = 2 * Math.PI * freq / sr;
        double cosW = Math.cos(omega), sinW = Math.sin(omega);
        double A = Math.pow(10, gainDb / 40.0);
        double alpha = sinW / (2.0 * q);
        double b0 = 1 + alpha * A;
        double b1 = -2 * cosW;
        double b2 = 1 - alpha * A;
        double a0 = 1 + alpha / A;
        double a1 = -2 * cosW;
        double a2 = 1 - alpha / A;
        if (a0 == 0 || !Double.isFinite(a0)) return identity();
        float nb0 = (float) (b0 / a0), nb1 = (float) (b1 / a0), nb2 = (float) (b2 / a0);
        float na1 = (float) (a1 / a0), na2 = (float) (a2 / a0);
        if (!Float.isFinite(nb0) || !Float.isFinite(nb1) || !Float.isFinite(nb2)
                || !Float.isFinite(na1) || !Float.isFinite(na2)) return identity();
        return new Coeff(nb0, nb1, nb2, na1, na2);
    }

    /** 单位增益系数。 */
    public static Coeff identity() { return new Coeff(1f, 0f, 0f, 0f, 0f); }

    /** 把 dB 数组 clamp 到 [-MAX_DB, MAX_DB]；非法值回退 0。 */
    public static float[] clampGains(float[] gainsDb) {
        int n = gainsDb == null ? 0 : Math.min(gainsDb.length, BANDS.length);
        float[] out = new float[BANDS.length];
        for (int i = 0; i < out.length; i++) {
            float g = i < n ? gainsDb[i] : 0f;
            out[i] = Float.isFinite(g) ? Math.max(-MAX_DB, Math.min(MAX_DB, g)) : 0f;
        }
        return out;
    }

    /** 五段系数；任一段 0 dB 即为单位增益（该段跳过运算）。 */
    public static Coeff[] coefficients(float sampleRate, float[] gainsDb) {
        float[] gains = clampGains(gainsDb);
        Coeff[] out = new Coeff[BANDS.length];
        for (int i = 0; i < BANDS.length; i++)
            out[i] = peaking(sampleRate, BANDS[i], DEFAULT_Q, gains[i]);
        return out;
    }

    /**
     * Direct Form II Transposed 双二阶状态；逐样本调用 {@link #process(float)} 返回滤波结果。
     */
    public static final class Biquad {
        private final Coeff c;
        private float z1, z2;
        public Biquad(Coeff c) { this.c = c == null ? identity() : c; }

        public float process(float x) {
            if (!Float.isFinite(x)) { z1 = 0f; z2 = 0f; return 0f; }
            float y = c.b0 * x + z1;
            z1 = c.b1 * x - c.a1 * y + z2;
            z2 = c.b2 * x - c.a2 * y;
            if (!Float.isFinite(y)) { z1 = 0f; z2 = 0f; return 0f; }
            if (!Float.isFinite(z1)) z1 = 0f;
            if (!Float.isFinite(z2)) z2 = 0f;
            return y;
        }

        public void reset() { z1 = 0f; z2 = 0f; }
        public Coeff coeff() { return c; }
    }

    /** 创建 5 段滤波器组；0 dB 段仍创建对象但 process 走单位系数（调用方可据 isIdentity 跳过）。 */
    public static Biquad[] filters(float sampleRate, float[] gainsDb) {
        Coeff[] coeffs = coefficients(sampleRate, gainsDb);
        Biquad[] out = new Biquad[coeffs.length];
        for (int i = 0; i < coeffs.length; i++) out[i] = new Biquad(coeffs[i]);
        return out;
    }

    private static boolean approx(float a, float b) { return Math.abs(a - b) < 1e-6f; }
}
