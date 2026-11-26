package org.openleaf.reader.core;

/**
 * 音效能力探测的降级分类与统一文案（纯 JVM，无 Android 依赖）。
 *
 * <p>本类不探测设备硬件音效能力：工程里并不存在这类 API，音频链是否旁路只有三种
 * 事实——用户三开关（eqOn/clipOn/spectrumOn）全关不挂链、非 PCM 编码在
 * {@code onConfigure} 返回 NOT_SET 被硬旁路、以及无链状态下改配置只存待下次播放生效。
 * 这里只做「用户请求 vs 实际生效」的判定与文案，供音效面板常驻说明复用。</p>
 *
 * <p>{@code audibleActually} 的判定口径：eqOn 且 eqGains 至少一段不为 0 dB，或 clipOn，
 * 或 spectrumOn（频谱虽不改变音质，但属于用户可见音效）。EQ 开而五段全 0 dB 时位透明，
 * 视为「未实际处理」，不得误报为「音效处理中」。</p>
 */
public final class AudioCapability {
    /** 能力等级：SUPPORTED 位透明静默；DEGRADED 需告知用户；BYPASSED 用户主动全关静默。 */
    public enum Level { SUPPORTED, DEGRADED, BYPASSED }

    private AudioCapability() { }

    /**
     * 按格式与音效事实分类：未请求音效即正常直通；请求了但格式不支持、或请求了却未真正
     * 处理（增益全 0 dB）则为降级；仅当格式 PCM 且确有可听处理才是 SUPPORTED。
     */
    public static Level classify(boolean formatPcm, boolean audibleRequested, boolean audibleActually) {
        if (!audibleRequested) return Level.BYPASSED;
        if (!formatPcm) return Level.DEGRADED;
        return audibleActually ? Level.SUPPORTED : Level.DEGRADED;
    }

    /** 音效面板常驻说明：SUPPORTED/BYPASSED 返回空串（不打扰），DEGRADED 给出明确原因。 */
    public static String message(Level level) {
        if (level == Level.DEGRADED)
            return "当前音频未应用音效处理（格式不支持或 EQ 未调整），按原始音质播放";
        return "";
    }

    /** 是否需要一次性提示：仅降级态需要；SUPPORTED 与 BYPASSED 均静默。 */
    public static boolean shouldNotifyOnce(Level level) {
        return level == Level.DEGRADED;
    }
}
