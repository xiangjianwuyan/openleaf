package org.openleaf.reader.core;

/**
 * 播放队列「下一首播放」插入位与倍速档位的纯 JVM 规则（无 Android 依赖）。
 *
 * 0.43.0 音频两项功能的可测内核：本类只回答「插到哪里、下一个倍速档是哪一个、
 * 这个倍速是否合法、非法倍速回退到多少」，不持有队列、不触碰播放器。Activity
 * 负责按 {@link #insertNextIndex(int, int)} 的结果在自身 List 上做 add(pos, uri)，
 * 并按 {@link #SPEEDS} / {@link #nextSpeedIndex(int)} 推进 UI 文案与 ExoPlayer 倍速。
 */
public final class PlaybackQueue {
    private PlaybackQueue() { }

    /** 可选倍速档位（与 TTS 独立，不共享 prefs）：0.75 / 1 / 1.25 / 1.5 / 2 倍。 */
    public static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 2f};

    /**
     * 计算「下一首播放」的插入下标：插在当前曲目之后。
     * 队列空或无当前曲目（currentIndex &lt; 0）返回 0（队首）；currentIndex 越界
     * （&ge; size）返回 size（尾部追加）；否则返回 currentIndex + 1。
     */
    public static int insertNextIndex(int currentIndex, int size) {
        if (size <= 0) return 0;
        if (currentIndex < 0) return 0;
        if (currentIndex >= size) return size;
        return currentIndex + 1;
    }

    /**
     * 下一个倍速档位下标，在 {@link #SPEEDS} 的 0..length-1 之间循环（末档回绕到 0）。
     * 负数先归一再推进（index = -1 与末档等价，同样回绕到 0），保证任意整数入参安全。
     */
    public static int nextSpeedIndex(int index) {
        int n = SPEEDS.length;
        return (((index + 1) % n) + n) % n;
    }

    /** 浮点容差：倍速比较以 1e-3 为界，避免 0.75f 等二进制近似误判。 */
    private static final float EPS = 1e-3f;

    /** speed 是否落在某个已知档位的 1e-3 容差内；NaN / Inf 一律 false。 */
    public static boolean isKnownSpeed(float speed) {
        if (!Float.isFinite(speed)) return false;
        for (float s : SPEEDS) if (Math.abs(s - speed) <= EPS) return true;
        return false;
    }

    /**
     * 把持久化/外部传入的倍速收敛到安全区间 [0.5, 3]；非有限值或越界回退 1f。
     * 区间内但非档位的值（如 1.1）原样保留，交由调用方决定是否吸附到档位。
     */
    public static float clampSpeed(float speed) {
        if (!Float.isFinite(speed) || speed < 0.5f || speed > 3f) return 1f;
        return speed;
    }
}
