package org.openleaf.reader.core;

/**
 * 阅读速度估算（纯 JVM，无 Android 依赖）。
 *
 * 用于阅读页页脚：按本章剩余字数与阅读速度，估算读完本章还需多久。
 * 速度取中文阅读经验中位值（每分钟 300-500 字，默认 400）；
 * 结果一律标注"约/预计"，不伪装成精确值。
 */
public final class ReadingPace {
    private ReadingPace() { }

    /** 默认阅读速度：每分钟 400 字（中文 300-500 中位）。 */
    public static final int DEFAULT_CHARS_PER_MINUTE = 400;

    /**
     * 估算读完剩余字数所需秒数。
     * remainingChars &lt;= 0 返回 0；charsPerMinute &lt;= 0 用默认速度；
     * 结果向上取整到秒，全程用 long 计算以避免 int 溢出。
     */
    public static long estimateSeconds(int remainingChars, int charsPerMinute) {
        if (remainingChars <= 0) return 0;
        long r = charsPerMinute > 0 ? charsPerMinute : DEFAULT_CHARS_PER_MINUTE;
        long chars = remainingChars;
        return (chars * 60L + r - 1) / r;
    }

    /**
     * 本章剩余信息页脚文案："剩余约 M 字 · 预计 K 分钟"。
     * remainingChars &lt;= 0 返回"本章已读完"；分钟数向上取整且至少为 1。
     */
    public static String chapterFooterLabel(int remainingChars, int charsPerMinute) {
        if (remainingChars <= 0) return "本章已读完";
        long r = charsPerMinute > 0 ? charsPerMinute : DEFAULT_CHARS_PER_MINUTE;
        long chars = remainingChars;
        long minutes = (chars + r - 1) / r;
        if (minutes < 1) minutes = 1;
        return "剩余约 " + remainingChars + " 字 · 预计 " + minutes + " 分钟";
    }
}
