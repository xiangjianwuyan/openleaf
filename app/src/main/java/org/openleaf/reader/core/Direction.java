package org.openleaf.reader.core;

/**
 * v4 三方向轴（纯 JVM，零 Android import）：A 安静书房 / B 效率工具 / C 内容画廊。
 *
 * <p>方向只服务 chrome 令牌 {@link UiTokens#CHROME}，与阅读纸主题（{@link ThemePalette}
 * 的 4 档）正交。{@link #fromInt(int)} 是 clamp 单一真源：任何非法值一律回落到
 * A/STUDY(0)，避免各 Activity 自行硬编码 0..2 边界。
 */
public enum Direction {
    STUDY(0), TOOL(1), GALLERY(2);

    private final int value;

    Direction(int value) {
        this.value = value;
    }

    /** 持久化/索引用的整数值（0/1/2）。 */
    public int value() {
        return value;
    }

    /** clamp：仅 1/2 映射到 B/C，其余（含负数、越界）一律 STUDY。 */
    public static Direction fromInt(int v) {
        return v == 1 ? TOOL : (v == 2 ? GALLERY : STUDY);
    }

    /** 方向数量（3）。 */
    public static int count() {
        return values().length;
    }

    /** 用户可见方向名。 */
    public String label() {
        switch (this) {
            case TOOL: return "效率工具";
            case GALLERY: return "内容画廊";
            case STUDY:
            default: return "安静书房";
        }
    }
}
