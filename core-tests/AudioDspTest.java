import org.openleaf.reader.core.AudioDsp;
import java.util.*;

/** 音频增强 core 回归：交叉淡入、渐变、频谱、diff、缓存键、路径安全。 */
public final class AudioDspTest {
    private static int passed;

    public static void main(String[] args) {
        crossfade();
        gainRamp();
        spectrum();
        spectrumBands();
        chainAndClip();
        lyricBest();
        scanDiff();
        cacheKeyAndPath();
        System.out.println("PASS: " + passed + " audio dsp checks");
    }

    private static void crossfade() {
        AudioDsp.GainPair start = AudioDsp.crossfadeGains(0);
        AudioDsp.GainPair end = AudioDsp.crossfadeGains(1);
        approx(1f, start.out); approx(0f, start.in);
        approx(0f, end.out); approx(1f, end.in);
        AudioDsp.GainPair mid = AudioDsp.crossfadeGains(0.5f);
        approx(1f, mid.out + mid.in, 0.0001f); // 增益和恒为 1
        // 相同信号混合输出恒定
        float mixed = AudioDsp.mix(0.6f, 0.6f, 0.3f);
        approx(0.6f, mixed, 0.02f);
        float[][] tables = AudioDsp.gainTables(64);
        eq(64, tables[0].length);
        passed++;
    }

    private static void gainRamp() {
        AudioDsp.GainRamp ramp = new AudioDsp.GainRamp(100, 1000); // 100 帧
        ramp.setTarget(0f);
        check(ramp.next() <= 1f, "渐变起点不超过1");
        for (int i = 0; i < 200; i++) ramp.next();
        approx(0f, ramp.current());
        // 连续指令：以当前值为新起点，不跳变
        ramp.setTarget(1f);
        float first = ramp.next();
        check(first < 0.1f, "新渐变从当前值起步，不跳变: " + first);
        passed++;
    }

    private static void spectrum() {
        AudioDsp.SpectrumAnalyzer analyzer = new AudioDsp.SpectrumAnalyzer(256, 64); // 旧 2 参构造器保持 48000Hz 兼容
        // 喂入 440Hz 近似正弦
        for (int i = 0; i < 512; i++) {
            float sample = (float) Math.sin(2 * Math.PI * 440 * i / 48000);
            analyzer.push(sample);
        }
        long now = System.nanoTime();
        float[] bands = analyzer.analyze(now, 30, 180);
        eq(64, bands.length);
        for (float band : bands) check(band >= 0f && band <= 1f, "频带归一化 0..1");
        float[] quiet = analyzer.analyze(now + 16_000_000L, 30, 180);
        eq(64, quiet.length);
        passed++;
    }

    /** 64 段真对数分带的边界性质：非递减、每段非空、不越界、不同采样率下均合法。 */
    private static void spectrumBands() {
        final int bands = 64, fft = 2048, nyquist = fft / 2;
        for (int sr : new int[]{44100, 48000}) {
            AudioDsp.SpectrumAnalyzer a = new AudioDsp.SpectrumAnalyzer(fft, bands, sr);
            double prevStart = -1, prevEnd = -1, prevCenter = -1;
            for (int b = 0; b < bands; b++) {
                int start = a.bandStart(b), end = a.bandEnd(b);
                check(start >= 1 && start <= nyquist, "bandStart 合法 sr=" + sr + " b=" + b + " v=" + start);
                check(end > start && end <= nyquist, "bandEnd 合法 sr=" + sr + " b=" + b + " v=" + end);
                // 低频多个 bin 会重合，只允许非递减，不要求严格递增
                check(start >= prevStart, "bandStart 非递减 sr=" + sr + " b=" + b);
                check(end >= prevEnd, "bandEnd 非递减 sr=" + sr + " b=" + b);
                double centerHz = (start + end) * 0.5 * sr / fft;
                check(centerHz >= prevCenter, "中心频率非递减 sr=" + sr + " b=" + b);
                prevStart = start; prevEnd = end; prevCenter = centerHz;
            }
            check(a.bandEnd(bands - 1) <= nyquist, "末段 bandEnd<=fftSize/2 sr=" + sr);
            check(a.bandStart(bands) == -1 && a.bandEnd(bands) == -1, "越界索引返回 -1 sr=" + sr);
            check(a.bandStart(-1) == -1 && a.bandEnd(-1) == -1, "负索引返回 -1 sr=" + sr);
        }
        // 非法采样率回退 44100：边界与显式 44100 完全一致
        AudioDsp.SpectrumAnalyzer at44100 = new AudioDsp.SpectrumAnalyzer(fft, bands, 44100);
        AudioDsp.SpectrumAnalyzer fallback = new AudioDsp.SpectrumAnalyzer(fft, bands, 1000);
        for (int b = 0; b < bands; b++) {
            eq(at44100.bandStart(b), fallback.bandStart(b));
            eq(at44100.bandEnd(b), fallback.bandEnd(b));
        }
        passed++;
    }

    private static void chainAndClip() {
        AudioDsp.ChainConfig config = new AudioDsp.ChainConfig();
        check(!config.hasAudibleProcessing(), "默认硬旁路");
        config.equalizerEnabled = true;
        check(config.hasAudibleProcessing(), "开启 EQ 后需处理");
        config.volume = 0.5f;
        check(config.copy().hasAudibleProcessing(), "copy 保留状态");
        // 限幅：输入远超阈值时饱和到接近 1，但不会超过 1
        check(AudioDsp.softClip(2f) >= 0.95f && AudioDsp.softClip(2f) <= 1f, "正限幅");
        check(AudioDsp.softClip(-2f) <= -0.95f && AudioDsp.softClip(-2f) >= -1f, "负限幅");
        check(AudioDsp.softClip(0.96f) > 0.95f && AudioDsp.softClip(0.96f) < 1f, "阈值附近连续");
        approx(0.5f, AudioDsp.softClip(0.5f));
        eq(0f, AudioDsp.softClip(Float.NaN));
        passed++;
    }

    private static void lyricBest() {
        List<AudioDsp.LyricCandidate<String>> candidates = new ArrayList<>();
        candidates.add(new AudioDsp.LyricCandidate<>(Arrays.asList("a", "b"), 0.6f, "LRC"));
        candidates.add(new AudioDsp.LyricCandidate<>(Arrays.asList("x", "y", "z"), 0.95f, "QRC"));
        AudioDsp.LyricCandidate<String> best = AudioDsp.best(candidates);
        eq("QRC", best.format);
        eq(null, AudioDsp.best(Collections.<AudioDsp.LyricCandidate<String>>emptyList()));
        passed++;
    }

    private static void scanDiff() {
        Map<String, AudioDsp.FileFingerprint> db = new LinkedHashMap<>();
        db.put("/a.mp3", new AudioDsp.FileFingerprint("/a.mp3", 100, 1000));
        db.put("/b.mp3", new AudioDsp.FileFingerprint("/b.mp3", 200, 2000));
        Map<String, AudioDsp.FileFingerprint> disk = new LinkedHashMap<>();
        disk.put("/a.mp3", new AudioDsp.FileFingerprint("/a.mp3", 100, 1000)); // unchanged
        disk.put("/b.mp3", new AudioDsp.FileFingerprint("/b.mp3", 201, 2000)); // modified
        disk.put("/c.mp3", new AudioDsp.FileFingerprint("/c.mp3", 300, 3000)); // added
        AudioDsp.ScanDiff diff = AudioDsp.diff(db, disk);
        eq(Collections.singletonList("/c.mp3"), diff.added);
        eq(Collections.singletonList("/b.mp3"), diff.modified);
        eq(Collections.emptyList(), diff.removed);
        // 目录不可读时 disk 为空 → removed 有值，但调用方可选择不执行
        eq(2, AudioDsp.diff(db, Collections.<String, AudioDsp.FileFingerprint>emptyMap()).removed.size());
        passed++;
    }

    private static void cacheKeyAndPath() {
        String key1 = AudioDsp.cacheKey("https://example.com/a.mp3", "etag1");
        String key2 = AudioDsp.cacheKey("https://example.com/a.mp3", "etag2");
        check(!key1.equals(key2), "不同 etag 不同缓存键");
        eq(64, key1.length());
        check(key1.matches("[0-9a-f]+"), "hex 小写");
        String root = "C:/app/files";
        check(AudioDsp.isPathSafe(root, "C:/app/files/a.mp3"), "根内路径安全");
        check(!AudioDsp.isPathSafe(root, "C:/app/files/../secret"), "路径穿越拒绝");
        passed++;
    }

    private static void approx(float expected, float actual) { approx(expected, actual, 0.0001f); }
    private static void approx(float expected, float actual, float tolerance) {
        if (Math.abs(expected - actual) > tolerance)
            throw new AssertionError("expected " + expected + " but got " + actual);
    }
    private static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
