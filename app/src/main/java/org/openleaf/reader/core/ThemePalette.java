package org.openleaf.reader.core;

/**
 * 阅读页配色解析与对比度判定（0.30.0 #2 / #5，纯 JVM，无 Android 依赖）。
 *
 * <p>三套内置预设原样搬自 0.29.0 的 {@code ReaderActivity.PAPER/INK} 与
 * {@code EpubActivity.PAPER/INK}；调用方未设置自定义色（ARGB == 0）时返回预设值，
 * 保证默认渲染与 0.29.0 逐字一致。
 *
 * <p>对比度按 WCAG 2.x 相对亮度公式计算：ratio = (L1 + 0.05) / (L2 + 0.05)，
 * 正文可读性阈值取 4.5:1（WCAG AA 普通文本）。
 */
public final class ThemePalette {

    /**
     * 内置预设数量（纸白 / 暖纸 / 夜间 / AMOLED 纯黑），与 reader prefs 的 theme 0..3 对应。
     * 第 4 档 AMOLED 由 liseur 的 "OLED Black" 与 baca. 的 "AMOLED Dark for book content"
     * 两批对标共同指向，只借鉴事实性设计，色值由 OpenLeaf 自定。
     */
    public static final int PRESET_COUNT = 4;
    /** WCAG AA 普通文本最小对比度。 */
    public static final double MIN_CONTRAST = 4.5;

    private static final int[] PRESET_PAPER = {0xFFF7F5EF, 0xFFF0E7D2, 0xFF18221F, 0xFF000000};
    private static final int[] PRESET_INK = {0xFF24302A, 0xFF473D2C, 0xFFD3DBD4, 0xFFE8E6E1};

    /** C2 主题档位名单一真源：索引与 PRESET_PAPER / PRESET_INK 严格对齐。 */
    private static final String[] PRESET_NAMES = {"纸白", "暖纸", "夜间", "AMOLED 纯黑"};

    /**
     * C1 语义色角色令牌（按主题档取值，而非固定 ARGB 常量）。
     *
     * <p>强调色若在暗档仍沿用亮档暖棕，切到 AMOLED 纯黑 (#000000) 后对比度不足而不可读，
     * 故所有角色令牌都必须随 preset 分档。亮档强调色与 {@code Ui.GREEN} 同源——core 层
     * 禁止依赖 android.Ui，故在此内联同一 ARGB 常量；暗档取高对比暖浅灰。
     */
    private static final int ACCENT_LIGHT = 0xFF44403C; // 与 Ui.GREEN 同源
    private static final int ACCENT_DARK = 0xFFC9C4B8;   // 暖浅灰，在 #18221F / #000000 上达 AA
    private static final int ON_ACCENT_LIGHT = 0xFFFFFFFF;
    private static final int ON_ACCENT_DARK = 0xFF1A1A1A;
    private static final int SELECTED_WIDE_LIGHT = 0xFFE3E1DC;
    private static final int SELECTED_WIDE_DARK = 0xFF3A3A36;

    private ThemePalette() {
        // 纯工具类，禁止实例化。
    }

    /** 预设主题 clamp 到 [0,2]；与 ThemeResolver 语义一致。 */
    public static int clamp(int presetTheme) {
        return presetTheme < 0 ? 0 : (presetTheme >= PRESET_COUNT ? PRESET_COUNT - 1 : presetTheme);
    }

    /** 预设背景色（ARGB）；preset 会 clamp，默认路径逐字等于 0.29.0。 */
    public static int presetPaper(int presetTheme) {
        return PRESET_PAPER[clamp(presetTheme)];
    }

    /** 预设字色（ARGB）；preset 会 clamp。 */
    public static int presetInk(int presetTheme) {
        return PRESET_INK[clamp(presetTheme)];
    }

    /** C2：主题档位名单一真源；preset 会 clamp（负数→纸白，越界→AMOLED 纯黑）。 */
    public static String presetName(int presetTheme) {
        return PRESET_NAMES[clamp(presetTheme)];
    }

    /** C1 语义角色 surface = 主题底色，等同 {@link #presetPaper(int)}。 */
    public static int surface(int presetTheme) {
        return presetPaper(presetTheme);
    }

    /** C1 语义角色 accent = 强调色；亮档(0/1)暖棕=Ui.GREEN，暗档(2/3)高对比暖浅灰。 */
    public static int accent(int presetTheme) {
        return isLight(presetTheme) ? ACCENT_LIGHT : ACCENT_DARK;
    }

    /** C1 语义角色 onAccent = 强调色之上的前景色；亮档白、暗档近黑。 */
    public static int onAccent(int presetTheme) {
        return isLight(presetTheme) ? ON_ACCENT_LIGHT : ON_ACCENT_DARK;
    }

    /**
     * C1 语义角色 selectedContainer = 选中态容器底色。
     * wide=true（宽屏左栏）用中性色以承载 accent 文字；否则返回 accent 作强调色填充，
     * 其上的前景应为 {@link #onAccent(int)}。
     */
    public static int selectedContainer(int presetTheme, boolean wide) {
        if (!wide) return accent(presetTheme);
        return isLight(presetTheme) ? SELECTED_WIDE_LIGHT : SELECTED_WIDE_DARK;
    }

    /** 亮档判定：预设 0/1 为亮档，2/3 为暗档；preset 会 clamp。 */
    private static boolean isLight(int presetTheme) {
        return clamp(presetTheme) <= 1;
    }

    /**
     * 解析有效配色：返回 {@code [背景色, 字色]}。
     * customBg / customInk 为 0 表示未设置，回退到对应预设值；两者互相独立。
     */
    public static int[] resolve(int presetTheme, int customBg, int customInk) {
        int preset = clamp(presetTheme);
        int background = customBg == 0 ? PRESET_PAPER[preset] : customBg;
        int ink = customInk == 0 ? PRESET_INK[preset] : customInk;
        return new int[]{background, ink};
    }

    /** WCAG 相对亮度，忽略 alpha 通道（按不透明色计算）。 */
    public static double luminance(int color) {
        double red = channel(color >> 16 & 0xFF);
        double green = channel(color >> 8 & 0xFF);
        double blue = channel(color & 0xFF);
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    /**
     * 前景/背景对比度，结果范围 [1,21]。同色为 1，纯黑与纯白为 21。
     * 参数顺序不影响结果（内部先取较亮者）。
     */
    public static double ratio(int foreground, int background) {
        double first = luminance(foreground);
        double second = luminance(background);
        double lighter = Math.max(first, second);
        double darker = Math.min(first, second);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /** 是否低于 WCAG AA 正文阈值；UI 据此提示「对比度偏低，可能影响阅读」。 */
    public static boolean lowContrast(int foreground, int background) {
        return ratio(foreground, background) < MIN_CONTRAST;
    }

    /** 深色判定：用于状态栏/导航栏图标反色，替代硬编码 theme == 2。 */
    public static boolean isDark(int color) {
        return luminance(color) < 0.5;
    }

    /**
     * 调色板是否与当前生效值完全相同（用于 onResume 幂等提前返回）。
     *
     * <p>注意：调用方字段若尚未初始化（Java 默认 0），任何合法预设/自定义色都不会等于 0，
     * 因此本方法必然返回 false，不会把「未初始化」误判为「无变化」。这正是 0.30.0
     * 开书 NPE 的语义防线：初始化必须在 build() 之前完成，且不能依赖 0 作哨兵。
     */
    public static boolean samePalette(int nextTheme, int nextPaper, int nextInk,
                                      int currentTheme, int currentPaper, int currentInk) {
        return nextTheme == currentTheme && nextPaper == currentPaper && nextInk == currentInk;
    }

    private static double channel(int value) {
        double normalized = value / 255.0;
        return normalized <= 0.03928 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }
}
