package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 音频增强核心算法（纯 JVM，无 Android 依赖）。
 *
 * 设计借鉴 XY-Music（AGPL-3.0）的 DSP 链、常数功率交叉淡入、歌词多解析器打分、
 * 增量扫描三元组、频谱平滑等思想，由 OpenLeaf 自行用 Java 重写，未复制任何源码。
 */
public final class AudioDsp {
    private AudioDsp() { }

    // ---------- 1. 常数功率交叉淡入 ----------

    /** 增益对：out=cos(p·π/2)/(cos+sin)，in=sin(p·π/2)/(cos+sin)，归一化后增益和恒为 1。 */
    public static final class GainPair {
        public final float out, in;
        GainPair(float out, float in) { this.out = out; this.in = in; }
    }

    public static GainPair crossfadeGains(float progress) {
        float p = Math.max(0f, Math.min(1f, progress));
        double angle = p * Math.PI / 2;
        double c = Math.cos(angle), s = Math.sin(angle);
        double sum = c + s;
        float out = (float) (c / sum), in = (float) (s / sum);
        if (!Float.isFinite(out) || !Float.isFinite(in)) return new GainPair(1f, 0f);
        return new GainPair(out, in);
    }

    /** 预计算增益表，热路径直接查表。 */
    public static float[][] gainTables(int overlapFrames) {
        if (overlapFrames <= 0) throw new IllegalArgumentException("overlapFrames 必须为正");
        float[][] tables = new float[2][overlapFrames];
        for (int i = 0; i < overlapFrames; i++) {
            GainPair pair = crossfadeGains((float) i / (overlapFrames - 1));
            tables[0][i] = pair.out;
            tables[1][i] = pair.in;
        }
        return tables;
    }

    /** 常数功率混合：相同信号混合输出恒定，无音量凹陷；NaN 安全回退当前样本。 */
    public static float mix(float current, float next, float progress) {
        GainPair pair = crossfadeGains(progress);
        float result = current * pair.out + next * pair.in;
        return Float.isFinite(result) ? result : current;
    }

    // ---------- 2. 响度渐变 ramp（逐帧线性，同帧所有声道共用增益） ----------

    /** 增益渐变状态机：目标变化时以当前值为新起点，避免连续指令产生跳变。 */
    public static final class GainRamp {
        private final int totalFrames;
        private int elapsed;
        private float from, to, current;

        public GainRamp(int durationMs, int sampleRate) {
            this.totalFrames = Math.max(1, durationMs * sampleRate / 1000);
            this.current = 1f; this.from = 1f; this.to = 1f;
            this.elapsed = totalFrames;
        }

        public void setTarget(float target) {
            if (Float.isNaN(target)) return;
            this.from = this.current;
            this.to = Math.max(0f, Math.min(1f, target));
            this.elapsed = 0;
        }

        /** 每帧调用一次（所有声道共用返回值）。 */
        public float next() {
            if (elapsed >= totalFrames) { current = to; return current; }
            current = from + (to - from) * ((float) elapsed / totalFrames);
            elapsed++;
            return current;
        }

        public void reset() { current = to; elapsed = totalFrames; }
        public float current() { return current; }
    }

    // ---------- 3. 频谱分析器（环形缓冲 + attack/release 平滑） ----------

    /** 频谱分析：下混单声道 → Hann 窗 → 基2 FFT → 频带峰值 → 动态压缩 → 时间平滑。 */
    public static final class SpectrumAnalyzer {
        /** 对数分带的频率范围：40Hz 起点、16kHz 终点。 */
        public static final float FMIN = 40f, FMAX = 16000f;

        private final int fftSize;
        private final float[] ring;
        private int writePos;
        private final float[] re, im;
        private final float[] hann;
        private final float[] bands;
        private final float[] smoothed;
        private final int[] bandStart, bandEnd;
        private long lastTimeNanos;

        /** 兼容旧调用：按 48000Hz 采样率构造。 */
        public SpectrumAnalyzer(int fftSize, int bandCount) {
            this(fftSize, bandCount, 48000);
        }

        public SpectrumAnalyzer(int fftSize, int bandCount, int sampleRate) {
            if (fftSize < 64 || (fftSize & (fftSize - 1)) != 0) throw new IllegalArgumentException("fftSize 必须是2的幂且>=64");
            if (sampleRate < 8000 || sampleRate > 384000) sampleRate = 44100;
            this.fftSize = fftSize;
            this.ring = new float[fftSize];
            this.re = new float[fftSize];
            this.im = new float[fftSize];
            this.hann = new float[fftSize];
            for (int i = 0; i < fftSize; i++) hann[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * i / (fftSize - 1))));
            this.bands = new float[bandCount];
            this.smoothed = new float[bandCount];
            this.bandStart = new int[bandCount];
            this.bandEnd = new int[bandCount];
            // 40Hz-16kHz 真对数等比分带：相邻段频率比恒定，低频自然更密
            double logRatio = (double) FMAX / FMIN;
            int nyquistBins = fftSize / 2;
            for (int b = 0; b < bandCount; b++) {
                double r0 = (double) b / bandCount, r1 = (double) (b + 1) / bandCount;
                double hz0 = FMIN * Math.pow(logRatio, r0);
                double hz1 = FMIN * Math.pow(logRatio, r1);
                bandStart[b] = Math.max(1, (int) (hz0 / sampleRate * fftSize));
                bandEnd[b] = Math.min(nyquistBins, Math.max(bandStart[b] + 1, (int) (hz1 / sampleRate * fftSize)));
            }
        }

        /** 只读频段起始 bin（越界返回 -1）。 */
        public int bandStart(int b) { return b < 0 || b >= bandStart.length ? -1 : bandStart[b]; }

        /** 只读频段结束 bin（不含，越界返回 -1）。 */
        public int bandEnd(int b) { return b < 0 || b >= bandEnd.length ? -1 : bandEnd[b]; }

        /** 喂入单声道样本（外部已下混）。 */
        public void push(float sample) {
            ring[writePos] = Float.isFinite(sample) ? sample : 0f;
            writePos = (writePos + 1) % fftSize;
        }

        /** 计算结果；attackMs 上升快、releaseMs 下降慢，返回归一化到 0..1 的频带数组副本。 */
        public float[] analyze(long nowNanos, float attackMs, float releaseMs) {
            for (int i = 0; i < fftSize; i++) {
                int idx = (writePos + i) % fftSize; // 最旧的样本在前
                re[i] = ring[idx] * hann[i];
                im[i] = 0f;
            }
            fft(re, im);
            for (int b = 0; b < bands.length; b++) {
                float peak = 0f;
                for (int i = bandStart[b]; i < bandEnd[b]; i++) {
                    float magnitude = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]);
                    if (magnitude > peak) peak = magnitude;
                }
                float normalized = Math.min(1f, peak / (fftSize / 4f));
                bands[b] = (float) Math.pow(normalized, 0.55);
            }
            float dt = lastTimeNanos == 0 ? 16f : (nowNanos - lastTimeNanos) / 1_000_000f;
            lastTimeNanos = nowNanos;
            for (int b = 0; b < bands.length; b++) {
                float tau = bands[b] > smoothed[b] ? attackMs : releaseMs;
                float alpha = (float) (1 - Math.exp(-dt / Math.max(1f, tau)));
                smoothed[b] += (bands[b] - smoothed[b]) * alpha;
            }
            return smoothed.clone();
        }

        /** 基2 迭代 Cooley-Tukey FFT（原地）。 */
        private static void fft(float[] re, float[] im) {
            int n = re.length;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j) {
                    float tr = re[i]; re[i] = re[j]; re[j] = tr;
                    float ti = im[i]; im[i] = im[j]; im[j] = ti;
                }
            }
            for (int len = 2; len <= n; len <<= 1) {
                double angle = -2 * Math.PI / len;
                float wrStep = (float) Math.cos(angle), wiStep = (float) Math.sin(angle);
                for (int i = 0; i < n; i += len) {
                    float wr = 1f, wi = 0f;
                    for (int j = 0; j < len / 2; j++) {
                        int u = i + j, v = u + len / 2;
                        float ur = re[u], ui = im[u];
                        float vr = re[v] * wr - im[v] * wi;
                        float vi = re[v] * wi + im[v] * wr;
                        re[u] = ur + vr; im[u] = ui + vi;
                        re[v] = ur - vr; im[v] = ui - vi;
                        float nwr = wr * wrStep - wi * wiStep;
                        wi = wr * wiStep + wi * wrStep;
                        wr = nwr;
                    }
                }
            }
        }
    }

    // ---------- 4. DSP 链配置（硬旁路 + 原子快照语义） ----------

    /** 音效链配置：全部默认关闭；无 audible 效果时硬旁路（bit 透明、零开销）。 */
    public static final class ChainConfig {
        public boolean equalizerEnabled;
        public boolean loudnessEnabled;
        public boolean reverbEnabled;
        public float volume = 1f;

        public ChainConfig copy() {
            ChainConfig copy = new ChainConfig();
            copy.equalizerEnabled = equalizerEnabled;
            copy.loudnessEnabled = loudnessEnabled;
            copy.reverbEnabled = reverbEnabled;
            copy.volume = volume;
            return copy;
        }

        /** 是否有可听处理；无则管线应直接旁路。 */
        public boolean hasAudibleProcessing() {
            return equalizerEnabled || loudnessEnabled || reverbEnabled;
        }
    }

    /** 末端限幅：阈值 0.95 的连续指数饱和（避免 tanh 对正常信号产生谐波）。 */
    public static float softClip(float sample) {
        if (!Float.isFinite(sample)) return 0f;
        float threshold = 0.95f;
        float abs = Math.abs(sample);
        if (abs <= threshold) return sample;
        float sign = sample < 0 ? -1f : 1f;
        float excess = (abs - threshold) / (1f - threshold);
        return sign * (threshold + (1f - threshold) * (1f - (float) Math.exp(-excess)));
    }

    // ---------- 5. 歌词多解析器打分择优 ----------

    /** 解析候选：行列表 + 置信度（0..1）。 */
    public static final class LyricCandidate<T> {
        public final List<T> lines;
        public final float confidence;
        public final String format;
        public LyricCandidate(List<T> lines, float confidence, String format) {
            this.lines = lines; this.confidence = confidence; this.format = format;
        }
    }

    /** 通用择优：按 confidence 降序、行数升序（同置信度时信息更密集者优先）。 */
    public static <T> LyricCandidate<T> best(List<LyricCandidate<T>> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        List<LyricCandidate<T>> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble((LyricCandidate<T> c) -> c.confidence).reversed()
                .thenComparingInt(c -> c.lines.size()));
        return sorted.get(0);
    }

    // ---------- 6. 增量扫描三元组 diff ----------

    /** 文件身份三元组：规范化路径 + 修改时间 + 大小。 */
    public static final class FileFingerprint {
        public final String path;
        public final long mtime, size;
        public FileFingerprint(String path, long mtime, long size) {
            this.path = path == null ? "" : path; this.mtime = mtime; this.size = size;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof FileFingerprint)) return false;
            FileFingerprint f = (FileFingerprint) o;
            return path.equals(f.path) && mtime == f.mtime && size == f.size;
        }
        @Override public int hashCode() { return path.hashCode() * 31 + Long.hashCode(mtime) * 31 + Long.hashCode(size); }
    }

    /** diff 结果：新增/修改/删除的路径集合。 */
    public static final class ScanDiff {
        public final List<String> added = new ArrayList<>();
        public final List<String> modified = new ArrayList<>();
        public final List<String> removed = new ArrayList<>();
    }

    /**
     * 三元组增量判定：mtime/size 变化 → modified；新路径 → added；DB 有磁盘无 → removed。
     * 目录不可读时调用方不应执行删除（本方法只做纯计算，不含 I/O）。
     */
    public static ScanDiff diff(java.util.Map<String, FileFingerprint> dbSnapshot,
                                java.util.Map<String, FileFingerprint> diskCandidates) {
        ScanDiff result = new ScanDiff();
        for (java.util.Map.Entry<String, FileFingerprint> entry : diskCandidates.entrySet()) {
            FileFingerprint old = dbSnapshot.get(entry.getKey());
            if (old == null) result.added.add(entry.getKey());
            else if (!old.equals(entry.getValue())) result.modified.add(entry.getKey());
        }
        for (String path : dbSnapshot.keySet()) if (!diskCandidates.containsKey(path)) result.removed.add(path);
        Collections.sort(result.added);
        Collections.sort(result.modified);
        Collections.sort(result.removed);
        return result;
    }

    // ---------- 7. 安全缓存键 ----------

    /** 远端 URI + ETag 的 SHA-256 缓存文件名，杜绝用户路径进入本地文件名。 */
    public static String cacheKey(String remoteUri, String etag) {
        String input = (remoteUri == null ? "" : remoteUri) + "\n" + (etag == null ? "" : etag);
        byte[] digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 不可用", e); }
        return ContentContracts.toHex(digest);
    }

    /** 路径穿越防护：拒绝任何 .. 组件，要求规范化后仍在根目录内。 */
    public static boolean isPathSafe(String root, String candidate) {
        if (root == null || candidate == null) return false;
        if (candidate.contains("..") || candidate.indexOf('\0') >= 0) return false;
        java.io.File rootFile = new java.io.File(root);
        java.io.File candidateFile = new java.io.File(candidate);
        try {
            String rootPath = rootFile.getCanonicalPath();
            String candidatePath = candidateFile.getCanonicalPath();
            return candidatePath.equals(rootPath) || candidatePath.startsWith(rootPath + java.io.File.separator);
        } catch (java.io.IOException e) { return false; }
    }
}
