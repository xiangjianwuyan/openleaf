import org.openleaf.reader.core.Direction;
import org.openleaf.reader.core.ThemePalette;
import org.openleaf.reader.core.UiTokens;
import org.openleaf.reader.core.UiTokens.Radius;
import org.openleaf.reader.core.UiTokens.Role;
import org.openleaf.reader.core.UiTokens.Spacing;
import org.openleaf.reader.core.UiTokens.Status;
import org.openleaf.reader.core.UiTokens.Typography;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * OpenLeaf v4 令牌门禁：纯 JVM、无 JUnit、断言始终启用。
 *
 * <p>把架构 §3.2/§3.3 的三方向逐项 ARGB、对比度矩阵与方向门禁钉成可执行断言：
 * 尤其 {@code B/muted·tint=4.47 FAIL}、{@code A/soft 放行}、{@code C warn/danger 深底≥4.5}、
 * {@code ON_DANGER 方向化}。runner 按文件名自动纳入，本类保留原名。
 */
public final class UiTokensTest {
    private static int passed;

    public static void main(String[] args) {
        test("12 角色 × 3 方向逐项照抄 v4 原型，含 clamp 边界与不透明校验",
                UiTokensTest::rolesAcrossDirections);
        test("架构 §3.3 三方向完整对比度矩阵（ink/muted/on/accent/danger/warn ...）",
                UiTokensTest::contrastMatrix);
        test("ON_DANGER 方向化：A/B 白、C 用 on（对 danger 6.63/6.63/7.65）",
                UiTokensTest::dangerAndOnDanger);
        test("次级文字方向门禁：B/tint=4.47 拦截、A/soft 放行、旧单参等价 A",
                UiTokensTest::secondaryPlacementGate);
        test("STATUS_BG 三方向底令牌化：fg/bg≥4.5，C 深底，绝不照抄 #f1e8cf 浅底",
                UiTokensTest::statusBackgrounds);
        test("Direction.fromInt clamp / value / count / label 单一真源",
                UiTokensTest::directionEnum);
        test("UiPrefs SP/key 常量（纯字符串断言，Android 层不进纯 JVM 编译集）",
                UiTokensTest::uiPrefsKeys);
        test("divider 对 surface 1.50/1.47/2.10，只作装饰、不得作唯一控件边界",
                UiTokensTest::dividerIsDecorativeOnly);
        test("paper/paperInk 委托 ThemePalette.resolve：4 档 + 自定义 + clamp + 夜读对比度",
                UiTokensTest::paperDelegation);
        test("字级 / 字体族 / 圆角 / 间距取值",
                UiTokensTest::typographyRadiusSpacing);
        test("热力 alpha 单调递增（0.18<0.36<0.60<0.90）且 RGB 恒等于 A 方向 ACCENT",
                UiTokensTest::heatArgbLevels);
        System.out.println("UiTokensTest: " + passed + " checks passed");
    }

    private static void rolesAcrossDirections() {
        // 顺序严格对齐 Role ordinal：BACKGROUND,SURFACE,SOFT,PRIMARY,SECONDARY,ACCENT,
        // ACCENT_SOFT,ON_ACCENT,DIVIDER,DANGER,WARN,ON_DANGER
        int[] a = {
                0xFFF6F5EF, 0xFFFFFEF8, 0xFFEAECE3, 0xFF243D31, 0xFF5B6B60, 0xFF285840,
                0xFFE0E9DB, 0xFFFFFEF8, 0xFFCED4C9, 0xFF9C3F30, 0xFF77551E, 0xFFFFFFFF
        };
        int[] b = {
                0xFFF3F5F6, 0xFFFCFDFE, 0xFFE7EDF1, 0xFF293E50, 0xFF596B7B, 0xFF315E80,
                0xFFDEE9F1, 0xFFFCFDFE, 0xFFCBD4DC, 0xFF9C3F30, 0xFF77551E, 0xFFFFFFFF
        };
        int[] c = {
                0xFF222923, 0xFF29322B, 0xFF353F35, 0xFFF0EDDF, 0xFFC0C6B7, 0xFFDDC497,
                0xFF3E493B, 0xFF283127, 0xFF586354, 0xFFF2B5A4, 0xFFEED29C, 0xFF283127
        };
        int[][] expected = {a, b, c};
        String[] names = {"A/STUDY", "B/TOOL", "C/GALLERY"};
        Role[] roles = Role.values();
        equal(12, roles.length, "角色必须是 12 个（v3 11 + WARN）");
        equal(2, Status.values().length, "状态底枚举必须是 WARN/DANGER 两项");
        for (int d = 0; d < Direction.count(); d++) {
            for (int i = 0; i < roles.length; i++) {
                int color = UiTokens.color(d, roles[i]);
                equal(expected[d][i], color, names[d] + " role=" + roles[i]);
                equal(0xFF, color >>> 24, "chrome 必须不透明：" + names[d] + " " + roles[i]);
            }
        }
        // direction 入口一律 clamp via Direction.fromInt：非法值回落 A/STUDY。
        for (Role role : roles) {
            for (int bad : new int[]{-99, -1, 3, 99, Integer.MAX_VALUE}) {
                equal(UiTokens.color(0, role), UiTokens.color(bad, role),
                        "clamp(" + bad + ")→A " + role);
            }
            equal(UiTokens.color(role), UiTokens.color(0, role), "color(role) 等价 A/STUDY");
        }
        equal(0xFFEAE9E2, UiTokens.DESK, "DESK 仅评审台背景，不进 chrome 表");
        for (Role role : roles) {
            for (int d = 0; d < Direction.count(); d++) {
                check(UiTokens.color(d, role) != UiTokens.DESK, "DESK 不得作为 chrome 角色：" + d + " " + role);
            }
        }
    }

    private static void contrastMatrix() {
        // 每行：组合名、A、B、C、是否期望可读（ink/acc 例外，仅装饰）。
        Object[][] rows = {
                {"ink/bg",      10.76, 10.11, 12.69, true},
                {"ink/surface", 11.63, 10.86, 11.28, true},
                {"ink/soft",     9.85,  9.36,  9.34, true},
                {"ink/tint",     9.43,  8.97,  8.05, true},
                {"muted/bg",     5.17,  5.03,  8.52, true},
                {"muted/sf",     5.59,  5.41,  7.57, true},
                {"muted/soft",   4.74,  4.66,  6.27, true},
                {"muted/tint",   4.53,  4.47,  5.40, true}, // B 近失由门禁单独拦截
                {"on/accent",    8.12,  6.78,  7.97, true},
                {"accent/bg",    7.51,  6.31,  8.81, true},
                {"accent/sf",    8.12,  6.78,  7.83, true},
                {"danger/bg",    6.07,  6.06,  8.46, true},
                {"danger/sf",    6.56,  6.51,  7.52, true},
                {"warn/bg",      6.20,  6.19, 10.17, true},
                {"warn/sf",      6.70,  6.65,  9.04, true},
                {"ink/accent",   1.43,  1.60,  1.44, false}
        };
        Role[][] pairs = {
                {Role.PRIMARY, Role.BACKGROUND}, {Role.PRIMARY, Role.SURFACE},
                {Role.PRIMARY, Role.SOFT}, {Role.PRIMARY, Role.ACCENT_SOFT},
                {Role.SECONDARY, Role.BACKGROUND}, {Role.SECONDARY, Role.SURFACE},
                {Role.SECONDARY, Role.SOFT}, {Role.SECONDARY, Role.ACCENT_SOFT},
                {Role.ON_ACCENT, Role.ACCENT}, {Role.ACCENT, Role.BACKGROUND},
                {Role.ACCENT, Role.SURFACE}, {Role.DANGER, Role.BACKGROUND},
                {Role.DANGER, Role.SURFACE}, {Role.WARN, Role.BACKGROUND},
                {Role.WARN, Role.SURFACE}, {Role.PRIMARY, Role.ACCENT}
        };
        for (int i = 0; i < rows.length; i++) {
            String name = (String) rows[i][0];
            for (int d = 0; d < Direction.count(); d++) {
                double v = ratio(d, pairs[i][0], pairs[i][1]);
                approx((Double) rows[i][1 + d], v, 0.01, name + " direction=" + d);
            }
        }
        // 可读性断言：除 ink/accent 外全部达 AA；唯一例外是 B/muted·tint=4.47 近失。
        for (int i = 0; i < rows.length - 1; i++) {
            for (int d = 0; d < Direction.count(); d++) {
                if (i == 7 && d == 1) {
                    check(!UiTokens.contrastOk(d, pairs[i][0], pairs[i][1]),
                            "B muted/tint 4.47 原始对比度必须 FAIL（由落位门禁拦截）");
                } else {
                    check(UiTokens.contrastOk(d, pairs[i][0], pairs[i][1]),
                            nameOf(d) + " " + rows[i][0] + " 必须 PASS");
                }
            }
        }
        for (int d = 0; d < Direction.count(); d++) {
            check(!UiTokens.contrastOk(d, Role.PRIMARY, Role.ACCENT),
                    nameOf(d) + " ink/accent 不可放主文字，强调底只用 on");
        }
        // 旧双参签名等价 A/STUDY。
        check(UiTokens.contrastOk(Role.PRIMARY, Role.BACKGROUND), "旧签名 contrastOk 等价 A");
        check(UiTokens.contrastOk(Role.SECONDARY, Role.SOFT), "A/soft 4.74 达标，旧签名放行");
        System.out.printf("matrix A/B/C: ink/bg=%.2f/%.2f/%.2f muted/tint=%.2f/%.2f/%.2f%n",
                ratio(0, Role.PRIMARY, Role.BACKGROUND), ratio(1, Role.PRIMARY, Role.BACKGROUND),
                ratio(2, Role.PRIMARY, Role.BACKGROUND),
                ratio(0, Role.SECONDARY, Role.ACCENT_SOFT), ratio(1, Role.SECONDARY, Role.ACCENT_SOFT),
                ratio(2, Role.SECONDARY, Role.ACCENT_SOFT));
    }

    private static void dangerAndOnDanger() {
        equal(0xFFFFFFFF, UiTokens.color(0, Role.ON_DANGER), "A ON_DANGER 白");
        equal(0xFFFFFFFF, UiTokens.color(1, Role.ON_DANGER), "B ON_DANGER 白");
        equal(0xFF283127, UiTokens.color(2, Role.ON_DANGER), "C ON_DANGER = on(#283127)");
        double[] expected = {6.63, 6.63, 7.65};
        for (int d = 0; d < Direction.count(); d++) {
            equal(UiTokens.color(d, Role.ON_DANGER), UiTokens.onStatus(d), "onStatus 等价 ON_DANGER " + d);
            double onDanger = ratio(d, Role.ON_DANGER, Role.DANGER);
            approx(expected[d], onDanger, 0.01, "on-danger/danger direction=" + d);
            check(UiTokens.contrastOk(d, Role.ON_DANGER, Role.DANGER),
                    nameOf(d) + " on-danger/danger 必须 PASS");
            check(UiTokens.contrastOk(d, Role.DANGER, Role.SURFACE), nameOf(d) + " danger/surface PASS");
            check(UiTokens.contrastOk(d, Role.DANGER, Role.BACKGROUND), nameOf(d) + " danger/bg PASS");
            check(UiTokens.contrastOk(d, Role.WARN, Role.SURFACE), nameOf(d) + " warn/surface PASS");
            check(UiTokens.contrastOk(d, Role.WARN, Role.BACKGROUND), nameOf(d) + " warn/bg PASS");
        }
        System.out.printf("on-danger/danger: %.2f/%.2f/%.2f%n",
                ratio(0, Role.ON_DANGER, Role.DANGER), ratio(1, Role.ON_DANGER, Role.DANGER),
                ratio(2, Role.ON_DANGER, Role.DANGER));
    }

    private static void secondaryPlacementGate() {
        // A 方向：soft 4.74 / tint 4.53 / bg 5.17 全部放行。
        check(UiTokens.canPlaceSecondaryOn(0, Role.SURFACE), "A/surface 放行");
        check(UiTokens.canPlaceSecondaryOn(0, Role.SOFT), "A/soft 4.74 放行");
        check(UiTokens.canPlaceSecondaryOn(0, Role.ACCENT_SOFT), "A/tint 4.53 放行");
        check(UiTokens.canPlaceSecondaryOn(0, Role.BACKGROUND), "A/bg 5.17 放行");
        // B 方向 tint 近失必须拦截，即使 A 达标也不得全局放行。
        double bTint = ratio(1, Role.SECONDARY, Role.ACCENT_SOFT);
        approx(4.47, bTint, 0.01, "B muted/tint 近失");
        check(!UiTokens.canPlaceSecondaryOn(1, Role.ACCENT_SOFT),
                "B/tint 4.47 < 4.5 必须拦截；调用方改 PRIMARY 文字或 SURFACE 底");
        check(UiTokens.canPlaceSecondaryOn(1, Role.SOFT), "B/soft 4.66 放行");
        check(UiTokens.canPlaceSecondaryOn(1, Role.SURFACE), "B/surface 放行");
        // C 方向全部放行。
        check(UiTokens.canPlaceSecondaryOn(2, Role.SOFT), "C/soft 6.27 放行");
        check(UiTokens.canPlaceSecondaryOn(2, Role.ACCENT_SOFT), "C/tint 5.40 放行");
        check(UiTokens.canPlaceSecondaryOn(2, Role.SURFACE), "C/surface 放行");
        // 旧单参签名等价 A/STUDY（v3 的 A/accent-soft、A/bg 因 v4 改值已由 FAIL 转 PASS）。
        check(UiTokens.canPlaceSecondaryOn(Role.SOFT) == UiTokens.canPlaceSecondaryOn(0, Role.SOFT),
                "旧单参 SOFT 等价 A");
        check(UiTokens.canPlaceSecondaryOn(Role.ACCENT_SOFT) == UiTokens.canPlaceSecondaryOn(0, Role.ACCENT_SOFT),
                "旧单参 ACCENT_SOFT 等价 A");
        check(!UiTokens.canPlaceSecondaryOn(null), "null 底色必须拦截");
        System.out.printf("secondary gate: B/tint=%.4f(false) A/soft=true C/tint=true%n", bTint);
    }

    private static void statusBackgrounds() {
        int[][] warnBg = {{0xFFF1E8CF}, {0xFFF0E7D4}, {0xFF4A4030}};
        int[][] dangerBg = {{0xFFF6E3DE}, {0xFFF5E2DD}, {0xFF4A312B}};
        double[] warnRatio = {5.54, 5.51, 6.93};
        double[] dangerRatio = {5.36, 5.31, 6.75};
        for (int d = 0; d < Direction.count(); d++) {
            equal(warnBg[d][0], UiTokens.statusBackground(d, Status.WARN), nameOf(d) + " warn bg");
            equal(dangerBg[d][0], UiTokens.statusBackground(d, Status.DANGER), nameOf(d) + " danger bg");
            equal(UiTokens.color(d, Role.WARN), UiTokens.statusForeground(d, Status.WARN),
                    nameOf(d) + " warn fg = Role.WARN");
            equal(UiTokens.color(d, Role.DANGER), UiTokens.statusForeground(d, Status.DANGER),
                    nameOf(d) + " danger fg = Role.DANGER");
            approx(warnRatio[d], ThemePalette.ratio(
                    UiTokens.statusForeground(d, Status.WARN), UiTokens.statusBackground(d, Status.WARN)),
                    0.01, nameOf(d) + " warn fg/bg");
            approx(dangerRatio[d], ThemePalette.ratio(
                    UiTokens.statusForeground(d, Status.DANGER), UiTokens.statusBackground(d, Status.DANGER)),
                    0.01, nameOf(d) + " danger fg/bg");
            check(UiTokens.canPlaceStatusOn(d, Status.WARN), nameOf(d) + " warn fg/bg ≥4.5");
            check(UiTokens.canPlaceStatusOn(d, Status.DANGER), nameOf(d) + " danger fg/bg ≥4.5");
        }
        // C 方向必须深底，不得照抄原型 A 的 #f1e8cf 浅底（在 C 上仅 1.20:1）。
        check(UiTokens.statusBackground(2, Status.WARN) != UiTokens.statusBackground(0, Status.WARN),
                "C warn 底必须方向化，不等于 A 浅底");
        check(UiTokens.statusBackground(2, Status.DANGER) != UiTokens.statusBackground(1, Status.DANGER),
                "C danger 底必须方向化，不等于 B 浅底");
        check(ThemePalette.isDark(UiTokens.statusBackground(2, Status.WARN)), "C warn 底必须为深色");
        check(ThemePalette.isDark(UiTokens.statusBackground(2, Status.DANGER)), "C danger 底必须为深色");
        // 若把 A 浅底配 C warn 前景，实测 1.20:1 不可读——钉死该坑。
        approx(1.20, ThemePalette.ratio(UiTokens.color(2, Role.WARN), 0xFFF1E8CF), 0.01,
                "C warn 前景落在原型浅底 #f1e8cf 上 = 1.20:1 不可读");
        System.out.printf("status bg fg/bg: warn=%.2f/%.2f/%.2f danger=%.2f/%.2f/%.2f%n",
                warnRatio[0], warnRatio[1], warnRatio[2],
                dangerRatio[0], dangerRatio[1], dangerRatio[2]);
    }

    private static void directionEnum() {
        equal(3, Direction.values().length, "方向枚举 3 项");
        equal(3, Direction.count(), "count()=3");
        equal(Direction.STUDY, Direction.fromInt(0), "fromInt(0)=STUDY");
        equal(Direction.TOOL, Direction.fromInt(1), "fromInt(1)=TOOL");
        equal(Direction.GALLERY, Direction.fromInt(2), "fromInt(2)=GALLERY");
        for (int bad : new int[]{-99, -1, 3, 99, Integer.MAX_VALUE}) {
            equal(Direction.STUDY, Direction.fromInt(bad), "clamp " + bad + "→STUDY");
        }
        equal(0, Direction.STUDY.value(), "STUDY.value=0");
        equal(1, Direction.TOOL.value(), "TOOL.value=1");
        equal(2, Direction.GALLERY.value(), "GALLERY.value=2");
        equal("安静书房", Direction.STUDY.label(), "STUDY label");
        equal("效率工具", Direction.TOOL.label(), "TOOL label");
        equal("内容画廊", Direction.GALLERY.label(), "GALLERY label");
        for (Role role : Role.values()) {
            equal(UiTokens.color(2, role), UiTokens.color(Direction.GALLERY.value(), role), "value 索引一致");
        }
    }

    private static void uiPrefsKeys() {
        String[] candidates = {
                "app/src/main/java/org/openleaf/reader/UiPrefs.java",
                "../app/src/main/java/org/openleaf/reader/UiPrefs.java",
                "../openleaf-qa-c0b6e3/app/src/main/java/org/openleaf/reader/UiPrefs.java"
        };
        String src = null;
        for (String path : candidates) {
            try {
                src = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
                break;
            } catch (Exception ignored) {
                // 尝试下一个候选路径。
            }
        }
        check(src != null, "无法定位 UiPrefs.java 源文件以做字符串断言");
        check(src.contains("SP = \"openleaf_ui\"") || src.contains("SP=\"openleaf_ui\""),
                "UiPrefs SP 必须是 openleaf_ui");
        check(src.contains("KEY_DIRECTION = \"direction\"") || src.contains("KEY_DIRECTION=\"direction\""),
                "UiPrefs KEY_DIRECTION 必须是 direction");
        check(src.contains("sCachedDirection"), "UiPrefs 必须有进程内缓存 sCachedDirection");
        check(!src.contains("getSharedPreferences(\"reader\""), "UiPrefs 不得读写 reader 阅读主题 SP");
        check(!src.contains("getSharedPreferences(\"shelf\""), "UiPrefs 不得读写 shelf SP");
    }

    private static void dividerIsDecorativeOnly() {
        double[] expected = {1.50, 1.47, 2.10};
        for (int d = 0; d < Direction.count(); d++) {
            double r = ratio(d, Role.DIVIDER, Role.SURFACE);
            approx(expected[d], r, 0.01, "line/surface direction=" + d);
            check(r < ThemePalette.MIN_CONTRAST, nameOf(d) + " divider 不达 AA，只能装饰");
            check(!UiTokens.contrastOk(d, Role.DIVIDER, Role.SURFACE),
                    nameOf(d) + " divider 不得作可读文本色");
        }
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

        int customBg = 0xFFE8F0E3;
        int customInk = 0xFF1F3A2A;
        equal(customBg, UiTokens.paper(0, customBg, customInk));
        equal(customInk, UiTokens.paperInk(0, customBg, customInk));
        equal(customBg, UiTokens.paper(1, customBg, 0));
        equal(ThemePalette.presetInk(1), UiTokens.paperInk(1, customBg, 0));

        approx(9.45, paperPairRatio(0xFFF4EDDF, 0xFF3F3C34), 0.01, "主 paper-ink/主 paper");
        approx(10.96, paperPairRatio(0xFFFAF9F3, 0xFF373A34), 0.01, "浅白 ink/浅白 paper");
        approx(10.03, paperPairRatio(0xFF242923, 0xFFD6D5C7), 0.01, "夜读 ink/夜读 paper");
    }

    /** 用传入的纸色与其"自己的墨色"配对复算对比度，不混用主 paper-ink。 */
    private static double paperPairRatio(int paper, int ownInk) {
        return ThemePalette.ratio(UiTokens.paperInk(0, paper, ownInk), UiTokens.paper(0, paper, ownInk));
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
            equal(0xFF285840 & 0xFFFFFF, argb & 0xFFFFFF, "热力色 RGB 必须恒等于 A 方向 ACCENT " + level);
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

    private static String nameOf(int d) {
        return d == 0 ? "A" : (d == 1 ? "B" : "C");
    }

    private static double ratio(int d, Role fg, Role bg) {
        return ThemePalette.ratio(UiTokens.color(d, fg), UiTokens.color(d, bg));
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
