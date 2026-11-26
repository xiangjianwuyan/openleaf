package org.openleaf.reader.core;

/**
 * 状态栏/导航栏图标策略（纯 JVM，无 Android 依赖，可单测）。
 *
 * <p>浅色背景 → 深色图标（LIGHT appearance）；深色背景 → 浅色图标。
 * 阈值用相对亮度（WCAG 感知亮度），与 ThemePalette.isDark 同口径但独立实现，
 * 便于 core 单测锁住边界。</p>
 */
public final class SystemBarsPolicy {
    private SystemBarsPolicy() { }

    /** 图标是否应为深色（true = LIGHT status/navigation bar appearance）。 */
    public static boolean darkIcons(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        double luminance = 0.2126 * relative(r) + 0.7152 * relative(g) + 0.0722 * relative(b);
        return luminance > 0.35;
    }

    private static double relative(int channel) {
        double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
