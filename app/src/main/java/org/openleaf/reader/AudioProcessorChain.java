package org.openleaf.reader;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import org.openleaf.reader.core.AudioDsp;
import org.openleaf.reader.core.AudioEq;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 阶段 2-A：挂在 DefaultAudioSink 上的音频处理器（5 段 EQ + softClip 限幅 + 频谱旁路采样）。
 *
 * <p>只处理 16-bit PCM 与 float PCM；其他编码通过 {@link #onConfigure} 返回 NOT_SET 让
 * Media3 硬旁路。EQ 状态由 {@link #setState} 原子换入并标脏，下次 {@link #queueInput} 时
 * 按当前采样率重建滤波器组（每声道独立的 5 段 Biquad，避免声道间状态串扰）。
 * 频谱旁路采样来自输入端第 0 声道，不走输出缓冲；UI 线程用 {@link #refreshSpectrum}
 * 取分析快照，音频线程不分配对象。</p>
 */
@UnstableApi
final class AudioProcessorChain extends BaseAudioProcessor {
    private static final int BAND_COUNT = 64;
    private static final int FFT_SIZE = 2048;

    private static final class State {
        final boolean eqEnabled, clipEnabled, spectrumEnabled;
        final float[] gains;
        State(boolean eq, boolean clip, boolean spectrum, float[] gains) {
            this.eqEnabled = eq; this.clipEnabled = clip; this.spectrumEnabled = spectrum;
            this.gains = AudioEq.clampGains(gains);
        }
    }

    private final Object lock = new Object();
    private volatile State state = new State(false, false, false, new float[AudioEq.BANDS.length]);
    private volatile boolean dirty = true;
    /** 只读旁路事实：最近一次 onConfigure 是否识别为 PCM；未配置时按 PCM 处理。 */
    private volatile boolean lastFormatPcm = true;
    private volatile float[] spectrumSnapshot = new float[BAND_COUNT];

    // 以下字段只在音频线程访问
    private int sampleRate = 44100, channelCount = 1, encoding = C.ENCODING_PCM_16BIT;
    /** [声道][频段的 Biquad]。 */
    private AudioEq.Biquad[][] filtersByChannel;
    private AudioDsp.SpectrumAnalyzer analyzer;
    private long frameCounter;

    /** 原子换入状态；标脏以便下次队列输入时重建滤波器。 */
    void setState(boolean eqEnabled, boolean clipEnabled, boolean spectrumEnabled, float[] gainsDb) {
        synchronized (lock) {
            state = new State(eqEnabled, clipEnabled, spectrumEnabled, gainsDb);
            dirty = true;
        }
    }

    /** 频谱快照副本（UI 线程调用），值 0..1。 */
    float[] drainSpectrum() {
        float[] s = spectrumSnapshot;
        return s == null ? new float[BAND_COUNT] : s.clone();
    }

    /** 已处理的帧数（QA 验证处理器真在链上）。 */
    long processedFrames() { return frameCounter; }

    /** 只读：最近一次 onConfigure 是否识别为 PCM（音效面板文案用，不改变任何音频处理）。 */
    public boolean lastFormatPcm() { return lastFormatPcm; }

    @Override protected AudioFormat onConfigure(AudioFormat input) throws UnhandledAudioFormatException {
        if (input.encoding != C.ENCODING_PCM_16BIT && input.encoding != C.ENCODING_PCM_FLOAT) {
            this.lastFormatPcm = false;
            return AudioFormat.NOT_SET; // 非 PCM 硬旁路
        }
        this.lastFormatPcm = true;
        this.encoding = input.encoding;
        this.channelCount = Math.max(1, input.channelCount);
        this.sampleRate = input.sampleRate > 0 && input.sampleRate <= 384000 ? input.sampleRate : 44100;
        this.analyzer = new AudioDsp.SpectrumAnalyzer(FFT_SIZE, BAND_COUNT, sampleRate);
        synchronized (lock) { dirty = true; }
        return input; // 同格式：只做增益/限幅，不重采样、不换声道
    }

    @Override public boolean isActive() {
        State s = state;
        // 频谱也要采样，故单独开频谱时同样激活（即使 EQ/限幅都关）
        return super.isActive() || s.eqEnabled || s.clipEnabled || s.spectrumEnabled;
    }

    private void ensureFilters() {
        if (!dirty && filtersByChannel != null) return;
        State s = state;
        AudioEq.Coeff[] bands = AudioEq.coefficients(sampleRate, s.gains);
        AudioEq.Biquad[][] built = new AudioEq.Biquad[channelCount][bands.length];
        for (int ch = 0; ch < channelCount; ch++)
            for (int b = 0; b < bands.length; b++) built[ch][b] = new AudioEq.Biquad(bands[b]);
        filtersByChannel = built;
        dirty = false;
    }

    @Override public void queueInput(ByteBuffer input) {
        if (!input.hasRemaining()) return;
        // Media3 PCM 一律小端；显式设定避免上游 ByteBuffer 默认 BIG_ENDIAN 导致样本读反
        input.order(ByteOrder.LITTLE_ENDIAN);
        ensureFilters();
        if (encoding == C.ENCODING_PCM_FLOAT) processFloat(input);
        else processPcm16(input);
        // QA 可观测：每约 1 秒一条，证明确有音频帧流经本处理器（非被 offload 绕过）
        if (frameCounter - lastLoggedFrames >= sampleRate) {
            lastLoggedFrames = frameCounter;
            android.util.Log.d("OL-AudioChain", "frames=" + frameCounter + " active=" + isActive());
        }
    }
    private long lastLoggedFrames;

    private void processPcm16(ByteBuffer input) {
        int bytesPerFrame = 2 * channelCount;
        int frames = input.remaining() / bytesPerFrame;
        if (frames <= 0) { input.position(input.limit()); return; }
        ByteBuffer out = replaceOutputBuffer(frames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN);
        State s = state;
        boolean eq = s.eqEnabled, clip = s.clipEnabled;
        AudioEq.Biquad[][] fs = filtersByChannel;
        for (int i = 0; i < frames; i++) {
            float mono = 0f;
            for (int ch = 0; ch < channelCount; ch++) {
                float sample = input.getShort() / 32768f;
                sample = apply(sample, eq, clip, fs, ch);
                if (ch == 0) mono = sample;
                int v = Math.round(sample * 32767f);
                out.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v)));
            }
            if (analyzer != null) analyzer.push(mono);
        }
        frameCounter += frames;
        input.position(input.limit());
        out.flip();
    }

    private void processFloat(ByteBuffer input) {
        int bytesPerFrame = 4 * channelCount;
        int frames = input.remaining() / bytesPerFrame;
        if (frames <= 0) { input.position(input.limit()); return; }
        ByteBuffer out = replaceOutputBuffer(frames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN);
        State s = state;
        boolean eq = s.eqEnabled, clip = s.clipEnabled;
        AudioEq.Biquad[][] fs = filtersByChannel;
        for (int i = 0; i < frames; i++) {
            float mono = 0f;
            for (int ch = 0; ch < channelCount; ch++) {
                float sample = apply(input.getFloat(), eq, clip, fs, ch);
                if (ch == 0) mono = sample;
                out.putFloat(sample);
            }
            if (analyzer != null) analyzer.push(mono);
        }
        frameCounter += frames;
        input.position(input.limit());
        out.flip();
    }

    private float apply(float sample, boolean eq, boolean clip, AudioEq.Biquad[][] fs, int ch) {
        if (!Float.isFinite(sample)) sample = 0f;
        if (eq && fs != null && ch < fs.length) {
            AudioEq.Biquad[] bands = fs[ch];
            for (AudioEq.Biquad band : bands) {
                if (band.coeff().isIdentity()) continue;
                sample = band.process(sample);
            }
        }
        if (clip) sample = AudioDsp.softClip(sample);
        return Float.isFinite(sample) ? sample : 0f;
    }

    /** UI 线程调用：刷新频谱分析快照（attack 60ms / release 240ms）。 */
    void refreshSpectrum() {
        AudioDsp.SpectrumAnalyzer a = analyzer;
        if (a != null) spectrumSnapshot = a.analyze(System.nanoTime(), 60f, 240f);
    }

    @Override protected void onFlush() {
        resetFilters();
        frameCounter = 0;
    }

    @Override protected void onReset() {
        resetFilters();
        filtersByChannel = null;
        analyzer = null;
        frameCounter = 0;
        spectrumSnapshot = new float[BAND_COUNT];
    }

    private void resetFilters() {
        if (filtersByChannel != null)
            for (AudioEq.Biquad[] bands : filtersByChannel)
                for (AudioEq.Biquad band : bands) band.reset();
    }
}
