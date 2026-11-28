package org.openleaf.reader.core;

/**
 * OpenLeaf v4 全域 UI 令牌单一真源（纯 JVM，零 Android import、零新依赖）。
 *
 * <p>v4 把 chrome 取色轴由「4 档阅读主题」换成「3 个方向」（{@link Direction}）：
 * {@code CHROME[direction][role]} 三方向逐项 ARGB，色值照抄 v4 原型
 * {@code :root} / {@code [data-theme=tool]} / {@code [data-theme=gallery]}。
 * 阅读纸 {@link #paper}/{@link #paperInk} 继续委托 {@link ThemePalette#resolve(int, int, int)}，
 * 4 档正文纸与方向正交；切 C 深 chrome 不强制正文深底。
 *
 * <p>无障碍口径（规格 §3.3 已逐项复算）：三方向 ink/bg、muted/surface 均达 AA；
 * B 方向 muted/tint = 4.47:1 近失，{@link #canPlaceSecondaryOn(int, Role)} 按方向返回 false；
 * warn/danger 的底也按方向令牌化（{@link #statusBackground(int, Status)}），
 * C 方向用深底配浅前景，原型 {@code #f1e8cf} 硬编码浅底绝不照抄。
 */
public final class UiTokens {

    /** 角色（12）：v3 11 角色 + WARN；顺序即 ordinal，索引进 CHROME 第二维。 */
    public enum Role {
        BACKGROUND, SURFACE, SOFT, PRIMARY, SECONDARY, ACCENT,
        ACCENT_SOFT, ON_ACCENT, DIVIDER, DANGER, WARN, ON_DANGER
    }

    /** 状态底令牌（非 Role，保持 Role=12）：WARN / DANGER。 */
    public enum Status { WARN, DANGER }

    /** 字级（v3 §5）：页面标题 24sp / 分组 18sp / 正文 16sp / 辅助 14sp / tab 标签 12sp 例外。 */
    public enum Typography { PAGE_TITLE, GROUP_TITLE, BODY, AUX, TAB_LABEL }

    /** 圆角（原型 CSS 实际值优先，见规格 §2.3）。 */
    public enum Radius {
        BUTTON, INPUT, CARD, CONTINUE_CARD, SEGMENT_CONTAINER, SEGMENT_ITEM,
        PANEL, DIALOG, PILL, TAB_SELECTED, COVER_LEFT, COVER_RIGHT
    }

    /** 间距栅格（v3 §5）：4/8/12/16/24/32dp，页边 16dp。 */
    public enum Spacing { XS, SM, MD, LG, XL, XXL, PAGE_EDGE, GROUP_GAP }

    /** 原型评审台背景，非产品角色，Android 页面根容器必须用 BACKGROUND。 */
    public static final int DESK = 0xFFEAE9E2;

    /**
     * 三方向 × 12 角色 chrome 表（顺序与 {@link Role} 严格对齐）。
     * A/B 的 danger/warn 继承 A 值；C 的 ON_DANGER 取 C 的 on 色。
     */
    private static final int[][] CHROME = {
            { // A · STUDY · :root
                    0xFFF6F5EF, // BACKGROUND --bg
                    0xFFFFFEF8, // SURFACE    --surface
                    0xFFEAECE3, // SOFT       --soft
                    0xFF243D31, // PRIMARY    --ink
                    0xFF5B6B60, // SECONDARY  --muted
                    0xFF285840, // ACCENT     --accent
                    0xFFE0E9DB, // ACCENT_SOFT --tint
                    0xFFFFFEF8, // ON_ACCENT  --on
                    0xFFCED4C9, // DIVIDER    --line
                    0xFF9C3F30, // DANGER     --danger
                    0xFF77551E, // WARN       --warn
                    0xFFFFFFFF  // ON_DANGER  A/B 白字
            },
            { // B · TOOL · [data-theme=tool]
                    0xFFF3F5F6, // BACKGROUND --bg
                    0xFFFCFDFE, // SURFACE    --surface
                    0xFFE7EDF1, // SOFT       --soft
                    0xFF293E50, // PRIMARY    --ink
                    0xFF596B7B, // SECONDARY  --muted
                    0xFF315E80, // ACCENT     --accent
                    0xFFDEE9F1, // ACCENT_SOFT --tint
                    0xFFFCFDFE, // ON_ACCENT  --on
                    0xFFCBD4DC, // DIVIDER    --line
                    0xFF9C3F30, // DANGER     继承 A
                    0xFF77551E, // WARN       继承 A
                    0xFFFFFFFF  // ON_DANGER  A/B 白字
            },
            { // C · GALLERY · [data-theme=gallery]
                    0xFF222923, // BACKGROUND --bg
                    0xFF29322B, // SURFACE    --surface
                    0xFF353F35, // SOFT       --soft
                    0xFFF0EDDF, // PRIMARY    --ink
                    0xFFC0C6B7, // SECONDARY  --muted
                    0xFFDDC497, // ACCENT     --accent
                    0xFF3E493B, // ACCENT_SOFT --tint
                    0xFF283127, // ON_ACCENT  --on
                    0xFF586354, // DIVIDER    --line
                    0xFFF2B5A4, // DANGER     --danger
                    0xFFEED29C, // WARN       --warn
                    0xFF283127  // ON_DANGER  = C 的 on
            }
    };

    /** 三方向 × 2 状态底：A/B 浅底、C 深底（避免原型浅底在 C 上 1.20:1 不可读）。 */
    private static final int[][] STATUS_BG = {
            {0xFFF1E8CF, 0xFFF6E3DE}, // A · WARN / DANGER
            {0xFFF0E7D4, 0xFFF5E2DD}, // B · WARN / DANGER
            {0xFF4A4030, 0xFF4A312B}  // C · WARN / DANGER（深底）
    };

    /** 热力 alpha 级别：0.18 / 0.36 / 0.60 / 0.90，由 A 方向 ACCENT 拆 RGB 合成。 */
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

    /** 按方向取 chrome 角色色；direction 一律先经 {@link Direction#fromInt(int)} clamp。 */
    public static int color(int direction, Role role) {
        return CHROME[Direction.fromInt(direction).value()][role.ordinal()];
    }

    /** 默认 A/STUDY 方向下的 chrome 角色色（保留旧单参签名）。 */
    public static int color(Role role) {
        return color(Direction.STUDY.value(), role);
    }

    /** 状态底：WARN/DANGER 按方向取色，禁止在页面硬编码浅底。 */
    public static int statusBackground(int direction, Status s) {
        return STATUS_BG[Direction.fromInt(direction).value()][s.ordinal()];
    }

    /** 状态前景：WARN→Role.WARN、DANGER→Role.DANGER。 */
    public static int statusForeground(int direction, Status s) {
        return color(direction, s == Status.WARN ? Role.WARN : Role.DANGER);
    }

    /** 危险实心按钮前景：A/B 白、C 深（等价 color(direction, ON_DANGER)）。 */
    public static int onStatus(int direction) {
        return color(direction, Role.ON_DANGER);
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

    /** 热力色阶：level 0..3 越界钳制，返回 A 方向 ACCENT 的 RGB + 对应 alpha 的 ARGB。 */
    public static int heatArgb(int level) {
        int clamped = level < 0 ? 0 : (level >= HEAT_ALPHA.length ? HEAT_ALPHA.length - 1 : level);
        return (HEAT_ALPHA[clamped] << 24) | (CHROME[Direction.STUDY.value()][Role.ACCENT.ordinal()] & 0xFFFFFF);
    }

    /** 按方向的两角色 chrome 色是否达 WCAG AA（阈值走 ThemePalette.MIN_CONTRAST）。 */
    public static boolean contrastOk(int direction, Role fg, Role bg) {
        return ThemePalette.ratio(color(direction, fg), color(direction, bg)) >= ThemePalette.MIN_CONTRAST;
    }

    /** 默认 A/STUDY 方向的两角色对比度（保留旧签名）。 */
    public static boolean contrastOk(Role fg, Role bg) {
        return contrastOk(Direction.STUDY.value(), fg, bg);
    }

    /**
     * 次级文字能否放在该方向的某底色上：严格按 WCAG AA 4.5:1 判定。
     * B 方向 ACCENT_SOFT(tint) 实测 4.47:1 近失，返回 false；A 方向 SOFT 4.74 放行。
     * 返回 false 时调用方必须改用 PRIMARY 文字或 SURFACE 底。
     */
    public static boolean canPlaceSecondaryOn(int direction, Role bg) {
        return bg != null && ThemePalette.ratio(color(direction, Role.SECONDARY), color(direction, bg))
                >= ThemePalette.MIN_CONTRAST;
    }

    /** 默认 A/STUDY 方向的次级文字落位门禁（保留旧签名）。 */
    public static boolean canPlaceSecondaryOn(Role bg) {
        return canPlaceSecondaryOn(Direction.STUDY.value(), bg);
    }

    /** 状态前景/状态底是否达 WCAG AA；三方向均应为 true。 */
    public static boolean canPlaceStatusOn(int direction, Status s) {
        return ThemePalette.ratio(statusForeground(direction, s), statusBackground(direction, s))
                >= ThemePalette.MIN_CONTRAST;
    }
}
