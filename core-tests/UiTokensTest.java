import org.openleaf.reader.core.ThemePalette;
import org.openleaf.reader.core.UiTokens;
import org.openleaf.reader.core.UiTokens.Radius;
import org.openleaf.reader.core.UiTokens.Role;
import org.openleaf.reader.core.UiTokens.Spacing;
import org.openleaf.reader.core.UiTokens.Typography;

/**
 * OpenLeaf v3 令牌门禁：纯 JVM、无 JUnit、断言始终启用。
 *
 * <p>把规格 §2.1 的全部对比度组合钉成可执行断言，尤其是
 * {@code muted/soft 4.25 FAIL} 与 {@code canPlaceSecondaryOn(SOFT)==false}，
 * 防止下游把次级文字放到 soft 底上。
 */
public final class UiTokensTest {
    private static int passed;

    public static void main(String[] args) {
        test("11 角色 × 4 主题逐项照抄原型 :root，含 clamp 边界与不透明校验",
                UiTokensTest::rolesAcrossThemes);
        test("规格 §2.1 完整对比度矩阵（ink/bg、ink/surface、muted/surface、accent...）",
                UiTokensTest::contrastMatrix);
        test("danger / on-danger 三组对比度（5.59 / 5.16 / 5.64）",
                UiTokensTest::dangerContrast);
        test("次级文字底门禁：SURFACE=true、SOFT=false、BACKGROUND=4.499 近失=false",
                UiTokensTest::secondaryPlacementGate);
        test("divider 对 surface 仅 1.38，只作装饰、不得作唯一控件边界",
                UiTokensTest::dividerIsDecorativeOnly);
        test("paper/paperInk 委托 ThemePalette.resolve：4 档 + 自定义 + clamp + 夜读对比度",
                UiTokensTest::paperDelegation);
        test("字级 / 字体族 / 圆角 / 间距取值",
                UiTokensTest::typographyRadiusSpacing);
        test("热力 alpha 单调递增（0.18<0.36<0.60<0.90）且 RGB 恒等于 ACCENT",
                UiTokensTest::heatArgbLevels);
        System.out.println("UiTokensTest: " + passed + " checks passed");
    }

    private static void rolesAcrossThemes() {
        int[] expected = {
                0xFFF5F5F0, // BACKGROUND --bg
                0xFFFFFEFA, // SURFACE    --surface
                0xFFEEEFE8, // SOFT       --soft
                0xFF27382F, // PRIMARY    --ink
                0xFF65746C, // SECONDARY  --muted
                0xFF245E4A, // ACCENT     --accent
                0xFFE4EEE6, // ACCENT_SOFT --accent-soft
                0xFFFFFFFF, // ON_ACCENT  --on-accent
                0xFFD6DCD4, // DIVIDER    --line
                0xFF876020, // DANGER     .pill.warn #876020（负责人拍板）
                0xFFFFFFFF  // ON_DANGER
        };
        Role[] roles = Role.values();
        equal(11, roles.length, "角色必须是 11 个（含独立 SOFT 与 DANGER/ON_DANGER）");
        for (int theme = 0; theme < ThemePalette.PRESET_COUNT; theme++) {
            for (int i = 0; i < roles.length; i++) {
                int color = UiTokens.color(theme, roles[i]);
                equal(expected[i], color, "theme=" + theme + " role=" + roles[i]);
                equal(0xFF, color >>> 24, "chrome 必须不透明：" + roles[i]);
            }
        }
        // theme 入口一律 clamp：负数→纸白，越界→AMOLED；本轮 4 档同值，故首尾档也必须相等。
        for (Role role : roles) {
            equal(UiTokens.color(0, role), UiTokens.color(-1, role), "clamp(-1) " + role);
            equal(UiTokens.color(0, role), UiTokens.color(-99, role), "clamp(-99) " + role);
            equal(UiTokens.color(3, role), UiTokens.color(99, role), "clamp(99) " + role);
            equal(UiTokens.color(role), UiTokens.color(0, role), "color(role) 等价 color(0,role)");
        }
        equal(0xFFEAE9E3, UiTokens.DESK, "DESK 仅评审台背景，不进 chrome 表");
        for (Role role : roles) {
            check(UiTokens.color(role) != UiTokens.DESK, "DESK 不得作为任何 chrome 角色：" + role);
        }
    }

    private static void contrastMatrix() {
        double inkBg = ratio(Role.PRIMARY, Role.BACKGROUND);
        double inkSurface = ratio(Role.PRIMARY, Role.SURFACE);
        double mutedSurface = ratio(Role.SECONDARY, Role.SURFACE);
        double mutedSoft = ratio(Role.SECONDARY, Role.SOFT);
        double accentSurface = ratio(Role.ACCENT, Role.SURFACE);
        double onAccentAccent = ratio(Role.ON_ACCENT, Role.ACCENT);
        double accentAccentSoft = ratio(Role.ACCENT, Role.ACCENT_SOFT);
        double lineSurface = ratio(Role.DIVIDER, Role.SURFACE);

        approx(11.34, inkBg, 0.01, "ink/bg");
        approx(12.29, inkSurface, 0.01, "ink/surface");
        approx(4.88, mutedSurface, 0.01, "muted/surface");
        approx(4.25, mutedSoft, 0.01, "muted/soft");
        approx(7.50, accentSurface, 0.01, "accent/surface");
        approx(7.57, onAccentAccent, 0.01, "on-accent/accent");
        approx(6.37, accentAccentSoft, 0.01, "accent/accent-soft");
        approx(1.38, lineSurface, 0.01, "line/surface");

        check(UiTokens.contrastOk(Role.PRIMARY, Role.BACKGROUND), "ink/bg 必须 PASS");
        check(UiTokens.contrastOk(Role.PRIMARY, Role.SURFACE), "ink/surface 必须 PASS");
        check(UiTokens.contrastOk(Role.SECONDARY, Role.SURFACE), "muted/surface 必须 PASS");
        check(!UiTokens.contrastOk(Role.SECONDARY, Role.SOFT), "muted/soft 必须 FAIL");
        check(UiTokens.contrastOk(Role.ACCENT, Role.SURFACE), "accent/surface 必须 PASS");
        check(UiTokens.contrastOk(Role.ON_ACCENT, Role.ACCENT), "on-accent/accent 必须 PASS");
        check(UiTokens.contrastOk(Role.ACCENT, Role.ACCENT_SOFT), "accent/accent-soft 必须 PASS");

        System.out.printf("contrast: ink/bg=%.2f ink/surface=%.2f muted/surface=%.2f "
                        + "muted/soft=%.2f(FAIL) accent/surface=%.2f on-accent/accent=%.2f "
                        + "accent/accent-soft=%.2f line/surface=%.2f(decor)%n",
                inkBg, inkSurface, mutedSurface, mutedSoft, accentSurface,
                onAccentAccent, accentAccentSoft, lineSurface);
    }

    private static void dangerContrast() {
        double onSurface = ratio(Role.DANGER, Role.SURFACE);
        double onBg = ratio(Role.DANGER, Role.BACKGROUND);
        double whiteOnDanger = ratio(Role.ON_DANGER, Role.DANGER);
        approx(5.59, onSurface, 0.01, "danger/surface");
        approx(5.16, onBg, 0.01, "danger/bg");
        approx(5.64, whiteOnDanger, 0.01, "on-danger/danger");
        check(UiTokens.contrastOk(Role.DANGER, Role.SURFACE), "danger/surface 必须 PASS");
        check(UiTokens.contrastOk(Role.DANGER, Role.BACKGROUND), "danger/bg 必须 PASS");
        check(UiTokens.contrastOk(Role.ON_DANGER, Role.DANGER), "on-danger/danger 必须 PASS");
        System.out.printf("danger: /surface=%.2f /bg=%.2f on-danger/danger=%.2f%n",
                onSurface, onBg, whiteOnDanger);
    }

    private static void secondaryPlacementGate() {
        check(UiTokens.canPlaceSecondaryOn(Role.SURFACE), "muted/surface 4.88 必须放行");
        check(!UiTokens.canPlaceSecondaryOn(Role.SOFT), "muted/soft 4.25 必须拦截");
        check(!UiTokens.canPlaceSecondaryOn(Role.ACCENT_SOFT), "muted/accent-soft 4.14 必须拦截");
        double onBackground = ratio(Role.SECONDARY, Role.BACKGROUND);
        approx(4.499, onBackground, 0.01, "muted/bg 是 AA 4.5 的近失，不得放行");
        check(!UiTokens.canPlaceSecondaryOn(Role.BACKGROUND),
                "muted/bg 实测 4.499 < 4.5，严格门禁必须拦截；次级文字改放 SURFACE 或用 PRIMARY");
        System.out.printf("secondary gate: SURFACE=true SOFT=false BACKGROUND=false(%.4f)%n",
                onBackground);
    }

    private static void dividerIsDecorativeOnly() {
        double r = ratio(Role.DIVIDER, Role.SURFACE);
        approx(1.38, r, 0.01, "line/surface");
        check(r < ThemePalette.MIN_CONTRAST, "divider 对 surface 不达 AA，只能是装饰分隔");
        check(!UiTokens.contrastOk(Role.DIVIDER, Role.SURFACE), "divider 不得被当作可读文本色");
    }

    private static void paperDelegation() {
        for (int theme = 0; theme < ThemePalette.PRESET_COUNT; theme++) {
            equal(ThemePalette.presetPaper(theme), UiTokens.paper(theme, 0, 0),
                    "paper 必须委托 ThemePalette preset " + theme);
            equal(ThemePalette.presetInk(theme), UiTokens.paperInk(theme, 0, 0),
                    "paperInk 必须委托 ThemePalette preset " + theme);
            int[] resolved = ThemePalette.resolve(theme, 0, 0);
            equal(resolved[0], UiTokens.paper(theme, 0, 0));
            equal(resolved[1], UiTokens.paperInk(theme, 0, 0));
            check(ThemePalette.ratio(resolved[1], resolved[0]) >= ThemePalette.MIN_CONTRAST,
                    "夜读/AMOLED 预设正文必须达 AA：theme=" + theme);
            System.out.printf("paper[theme=%d] ink/paper=%.2f%n", theme,
                    ThemePalette.ratio(resolved[1], resolved[0]));
        }
        equal(UiTokens.paper(0, 0, 0), UiTokens.paper(-1, 0, 0), "paper clamp(-1)");
        equal(UiTokens.paper(3, 0, 0), UiTokens.paper(99, 0, 0), "paper clamp(99)");
        equal(UiTokens.paperInk(0, 0, 0), UiTokens.paperInk(-1, 0, 0), "paperInk clamp(-1)");
        equal(UiTokens.paperInk(3, 0, 0), UiTokens.paperInk(99, 0, 0), "paperInk clamp(99)");

        // 自定义纸/墨覆盖；0 表示未设置、两路独立。
        int customBg = 0xFFE8F0E3;
        int customInk = 0xFF1F3A2A;
        equal(customBg, UiTokens.paper(0, customBg, customInk));
        equal(customInk, UiTokens.paperInk(0, customBg, customInk));
        equal(customBg, UiTokens.paper(1, customBg, 0));
        equal(ThemePalette.presetInk(1), UiTokens.paperInk(1, customBg, 0));

        // 规格 §2.1 的纸墨三档数值来自原型纸色（仅作对比度核算，不写入实现常量），
        // 通过自定义纸/墨入口复算，验证组合确实达到规格标注的比值。
        approx(9.45, paperRatio(0xFFF4EDDF, 0xFF3F3C34), 0.01, "paper-ink/paper");
        approx(10.96, paperRatio(0xFFFAF9F3, 0xFF373A34), 0.01, "paper-ink/浅白");
        approx(10.03, paperRatio(0xFF242923, 0xFFD6D5C7), 0.01, "paper-ink/夜读");
    }

    private static double paperRatio(int paper, int ink) {
        return ThemePalette.ratio(UiTokens.paperInk(0, paper, ink), UiTokens.paper(0, paper, ink));
    }

    private static void typographyRadiusSpacing() {
        equal(24, UiTokens.textSizeSp(Typography.PAGE_TITLE));
        equal(18, UiTokens.textSizeSp(Typography.GROUP_TITLE));
        equal(16, UiTokens.textSizeSp(Typography.BODY));
        equal(14, UiTokens.textSizeSp(Typography.AUX));
        equal(12, UiTokens.textSizeSp(Typography.TAB_LABEL));
        equal("sans-serif-medium", UiTokens.fontFamily(Typography.PAGE_TITLE));
        equal("sans-serif-medium", UiTokens.fontFamily(Typography.GROUP_TITLE));
        equal("sans-serif", UiTokens.fontFamily(Typography.BODY));
        equal("sans-serif", UiTokens.fontFamily(Typography.AUX));
        equal("sans-serif", UiTokens.fontFamily(Typography.TAB_LABEL));

        equal(14, UiTokens.radiusDp(Radius.BUTTON));
        equal(15, UiTokens.radiusDp(Radius.INPUT));
        equal(18, UiTokens.radiusDp(Radius.CARD));
        equal(20, UiTokens.radiusDp(Radius.CONTINUE_CARD));
        equal(14, UiTokens.radiusDp(Radius.SEGMENT_CONTAINER));
        equal(11, UiTokens.radiusDp(Radius.SEGMENT_ITEM));
        equal(16, UiTokens.radiusDp(Radius.PANEL));
        equal(25, UiTokens.radiusDp(Radius.DIALOG));
        equal(8, UiTokens.radiusDp(Radius.PILL));
        equal(14, UiTokens.radiusDp(Radius.TAB_SELECTED));
        equal(6, UiTokens.radiusDp(Radius.COVER_LEFT));
        equal(12, UiTokens.radiusDp(Radius.COVER_RIGHT));

        equal(4, UiTokens.spacingDp(Spacing.XS));
        equal(8, UiTokens.spacingDp(Spacing.SM));
        equal(12, UiTokens.spacingDp(Spacing.MD));
        equal(16, UiTokens.spacingDp(Spacing.LG));
        equal(24, UiTokens.spacingDp(Spacing.XL));
        equal(32, UiTokens.spacingDp(Spacing.XXL));
        equal(16, UiTokens.spacingDp(Spacing.PAGE_EDGE));
        equal(16, UiTokens.spacingDp(Spacing.GROUP_GAP));
    }

    private static void heatArgbLevels() {
        int accentRgb = UiTokens.color(Role.ACCENT) & 0xFFFFFF;
        int[] alphas = new int[4];
        for (int level = 0; level < 4; level++) {
            int argb = UiTokens.heatArgb(level);
            alphas[level] = argb >>> 24;
            equal(0xFF245E4A & 0xFFFFFF, argb & 0xFFFFFF, "热力色 RGB 必须恒等于 ACCENT " + level);
            equal(accentRgb, argb & 0xFFFFFF, "RGB 必须与 color(ACCENT) 一致 " + level);
        }
        approx(0.18, alphas[0] / 255.0, 0.003, "heat level0 alpha");
        approx(0.36, alphas[1] / 255.0, 0.003, "heat level1 alpha");
        approx(0.60, alphas[2] / 255.0, 0.003, "heat level2 alpha");
        approx(0.90, alphas[3] / 255.0, 0.003, "heat level3 alpha");
        check(alphas[0] < alphas[1] && alphas[1] < alphas[2] && alphas[2] < alphas[3],
                "热力 alpha 必须单调递增");
        equal(UiTokens.heatArgb(0), UiTokens.heatArgb(-1), "heatArgb clamp 负数");
        equal(UiTokens.heatArgb(3), UiTokens.heatArgb(99), "heatArgb clamp 越界");
        System.out.printf("heat alphas: %d %d %d %d%n", alphas[0], alphas[1], alphas[2], alphas[3]);
    }

    private static double ratio(Role fg, Role bg) {
        return ThemePalette.ratio(UiTokens.color(fg), UiTokens.color(bg));
    }

    private interface Case {
        void run();
    }

    private static void test(String name, Case body) {
        try {
            body.run();
            passed++;
        } catch (Throwable failure) {
            throw new IllegalStateException("FAIL " + name + ": " + failure, failure);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void equal(Object expected, Object actual) {
        equal(expected, actual, "值不相等");
    }

    private static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(message + "：expected=" + expected + " actual=" + actual);
        }
    }

    private static void approx(double expected, double actual, double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new IllegalStateException(message + "：expected=" + expected + " actual=" + actual);
        }
    }
}
