package org.openleaf.reader.core;

/**
 * OpenLeaf v3 全域 UI 令牌单一真源（纯 JVM，零 Android import、零新依赖）。
 *
 * <p>色板逐项照抄 v3 原型 {@code OpenLeaf-页面设计优化-v3-交互原型.html} 的 {@code :root}
 * （规格 §2.1）：{@code --bg/surface/soft/ink/muted/accent/accent-soft/on-accent/line}。
 * 本轮 4 档主题（纸白/暖纸/夜间/AMOLED）的 chrome 角色返回同一组浅色值；夜间/AMOLED 只改
 * 正文纸与墨，且二者委托 {@link ThemePalette#resolve(int, int, int)}，不在本类复制任何正文色。
 * 原型第 72 行的全局深色探索态仅记录，不进入默认主题。
 *
 * <p>无障碍口径：SECONDARY 对 SURFACE 4.88:1 达标，对 SOFT 4.25:1 不达标；
 * 对 BACKGROUND 实测 4.499:1（AA 4.5 的近失）。{@link #canPlaceSecondaryOn(Role)} 因此采用
 * 严格 {@link ThemePalette#MIN_CONTRAST} 门禁，凡不达标的底一律返回 false，让下游无法误用。
 */
public final class UiTokens {

    /** 角色（11）：原型 8 角色 + SOFT + DANGER/ON_DANGER。 */
    public enum Role {
        BACKGROUND, SURFACE, SOFT, PRIMARY, SECONDARY, ACCENT,
        ACCENT_SOFT, ON_ACCENT, DIVIDER, DANGER, ON_DANGER
    }

    /** 字级（v3 §5）：页面标题 24sp / 分组 18sp / 正文 16sp / 辅助 14sp / tab 标签 12sp 例外。 */
    public enum Typography { PAGE_TITLE, GROUP_TITLE, BODY, AUX, TAB_LABEL }

    /** 圆角（原型 CSS 实际值优先，见规格 §2.3）。 */
    public enum Radius {
        BUTTON, INPUT, CARD, CONTINUE_CARD, SEGMENT_CONTAINER, SEGMENT_ITEM,
        PANEL, DIALOG, PILL, TAB_SELECTED, COVER_LEFT, COVER_RIGHT
    }

    /** 间距栅格（v3 §5）：4/8/12/16/24/32dp，页边 16dp。 */
    public enum Spacing { XS, SM, MD, LG, XL, XXL, PAGE_EDGE, GROUP_GAP }

    /** 原型评审台背景，仅用于原型评审台；Android 页面根容器必须用 BACKGROUND。 */
    public static final int DESK = 0xFFEAE9E3;

    // 原型 :root 逐项照抄（顺序与 Role 枚举一致）。
    private static final int C_BACKGROUND = 0xFFF5F5F0; // --bg
    private static final int C_SURFACE    = 0xFFFFFEFA; // --surface
    private static final int C_SOFT       = 0xFFEEEFE8; // --soft
    private static final int C_PRIMARY    = 0xFF27382F; // --ink
    private static final int C_SECONDARY  = 0xFF65746C; // --muted
    private static final int C_ACCENT     = 0xFF245E4A; // --accent
    private static final int C_ACCENT_SOFT= 0xFFE4EEE6; // --accent-soft
    private static final int C_ON_ACCENT  = 0xFFFFFFFF; // --on-accent
    private static final int C_DIVIDER    = 0xFFD6DCD4; // --line
    private static final int C_DANGER     = 0xFF876020; // v3 .pill.warn #876020（负责人拍板）
    private static final int C_ON_DANGER  = 0xFFFFFFFF; // 白对 DANGER 5.64:1

    private static final int[] ROLE_COLORS = {
            C_BACKGROUND, C_SURFACE, C_SOFT, C_PRIMARY, C_SECONDARY,
            C_ACCENT, C_ACCENT_SOFT, C_ON_ACCENT, C_DIVIDER, C_DANGER, C_ON_DANGER
    };

    /** 11 角色 × 4 主题查表；本轮每档返回同一组浅色 chrome 值。 */
    private static final int[][] CHROME = new int[ThemePalette.PRESET_COUNT][Role.values().length];
    static {
        for (int theme = 0; theme < ThemePalette.PRESET_COUNT; theme++) {
            System.arraycopy(ROLE_COLORS, 0, CHROME[theme], 0, ROLE_COLORS.length);
        }
    }

    /** 热力 alpha 级别：0.18 / 0.36 / 0.60 / 0.90，由 ACCENT 拆 RGB 合成。 */
    private static final float[] HEAT_ALPHA_LEVELS = {0.18f, 0.36f, 0.60f, 0.90f};
    private static final int[] HEAT_ALPHA = heatAlphaBytes();

    private UiTokens() {
        // 纯工具类，禁止实例化。
    }

    private static int[] heatAlphaBytes() {
        int[] bytes = new int[HEAT_ALPHA_LEVELS.length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = Math.round(HEAT_ALPHA_LEVELS[i] * 255f);
        }
        return bytes;
    }

    /** 按主题档取 chrome 角色色；theme 一律先经 {@link ThemePalette#clamp(int)}。 */
    public static int color(int theme, Role role) {
        return CHROME[ThemePalette.clamp(theme)][role.ordinal()];
    }

    /** 默认主题（纸白）下的 chrome 角色色。 */
    public static int color(Role role) {
        return color(0, role);
    }

    /** 正文纸：委托 ThemePalette.resolve，customBg==0 表示未设置、回退预设。 */
    public static int paper(int theme, int customBg, int customInk) {
        return ThemePalette.resolve(theme, customBg, customInk)[0];
    }

    /** 正文墨：委托 ThemePalette.resolve，customInk==0 表示未设置、回退预设。 */
    public static int paperInk(int theme, int customBg, int customInk) {
        return ThemePalette.resolve(theme, customBg, customInk)[1];
    }

    /** 字级（sp）；遵循系统字体倍率，由调用方 TextView 承担。 */
    public static int textSizeSp(Typography t) {
        switch (t) {
            case PAGE_TITLE: return 24;
            case GROUP_TITLE: return 18;
            case BODY: return 16;
            case AUX: return 14;
            case TAB_LABEL: return 12;
            default: throw new IllegalArgumentException("Typography: " + t);
        }
    }

    /** 字体族：标题级 medium，其余常规 sans。 */
    public static String fontFamily(Typography t) {
        return (t == Typography.PAGE_TITLE || t == Typography.GROUP_TITLE)
                ? "sans-serif-medium" : "sans-serif";
    }

    /** 圆角（dp）。 */
    public static int radiusDp(Radius r) {
        switch (r) {
            case BUTTON: return 14;
            case INPUT: return 15;
            case CARD: return 18;
            case CONTINUE_CARD: return 20;
            case SEGMENT_CONTAINER: return 14;
            case SEGMENT_ITEM: return 11;
            case PANEL: return 16;
            case DIALOG: return 25;
            case PILL: return 8;
            case TAB_SELECTED: return 14;
            case COVER_LEFT: return 6;
            case COVER_RIGHT: return 12;
            default: throw new IllegalArgumentException("Radius: " + r);
        }
    }

    /** 间距（dp）。 */
    public static int spacingDp(Spacing s) {
        switch (s) {
            case XS: return 4;
            case SM: return 8;
            case MD: return 12;
            case LG: return 16;
            case XL: return 24;
            case XXL: return 32;
            case PAGE_EDGE: return 16;
            case GROUP_GAP: return 16;
            default: throw new IllegalArgumentException("Spacing: " + s);
        }
    }

    /** 热力色阶：level 0..3 越界钳制，返回 ACCENT 的 RGB + 对应 alpha 的 ARGB。 */
    public static int heatArgb(int level) {
        int clamped = level < 0 ? 0 : (level >= HEAT_ALPHA.length ? HEAT_ALPHA.length - 1 : level);
        return (HEAT_ALPHA[clamped] << 24) | (C_ACCENT & 0xFFFFFF);
    }

    /** 两角色 chrome 色是否达 WCAG AA（阈值走 ThemePalette.MIN_CONTRAST）。 */
    public static boolean contrastOk(Role fg, Role bg) {
        return ThemePalette.ratio(color(fg), color(bg)) >= ThemePalette.MIN_CONTRAST;
    }

    /**
     * 次级文字能否放在该底色上：严格按 WCAG AA 4.5:1 判定。
     *
     * <p>SURFACE 4.88 达标；SOFT 4.25 不达标；BACKGROUND 实测 4.499 亦不达标。
     * 返回 false 时调用方必须改用 PRIMARY 文字或 SURFACE 底。
     */
    public static boolean canPlaceSecondaryOn(Role bg) {
        return bg != null
                && ThemePalette.ratio(color(Role.SECONDARY), color(bg)) >= ThemePalette.MIN_CONTRAST;
    }
}
