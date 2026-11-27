import org.openleaf.reader.core.ThemePalette;

/** 0.30.0 #2 配色与 #5 对比度回归；纯 JVM、无 JUnit、断言始终启用。 */
public final class ThemePaletteTest {
    private static int passed;

    public static void main(String[] args) {
        test("三套预设与 0.29.0 PAPER/INK 逐字一致", ThemePaletteTest::presetsUnchanged);
        test("自定义色覆盖与 0=未设置回退、preset clamp", ThemePaletteTest::resolveContract);
        test("WCAG 对比度边界：同色 1、黑白 21、对称", ThemePaletteTest::ratioBoundaries);
        test("4.5 阈值与深色判定", ThemePaletteTest::thresholdAndDarkness);
        test("AMOLED 纯黑主题档（liseur/baca 对标）", ThemePaletteTest::amoledPreset);
        test("samePalette：字段初值 0 不得误判为无变化", ThemePaletteTest::samePaletteGuardsInit);
        test("C2：presetName 主题名单一真源与 clamp", ThemePaletteTest::presetNameContract);
        test("C1：按主题语义色角色对比度走查（4 档 × accent/onAccent/selectedContainer）",
                ThemePaletteTest::semanticRoleContrast);
        test("clamp 保留 AMOLED 档：clamp(3)=3 / clamp(100)=3 / clamp(-1)=0",
                ThemePaletteTest::clampPreservesAmoled);
        test("A11Y-1：MUTED 次级文字在面板底色上达 WCAG AA 4.5:1",
                ThemePaletteTest::mutedContrastOnPanelSurfaces);
        System.out.println("ThemePaletteTest: " + passed + " checks passed");
    }

    private static void presetsUnchanged() {
        int[] paper = {0xFFF7F5EF, 0xFFF0E7D2, 0xFF18221F, 0xFF000000};
        int[] ink = {0xFF24302A, 0xFF473D2C, 0xFFD3DBD4, 0xFFE8E6E1};
        for (int i = 0; i < ThemePalette.PRESET_COUNT; i++) {
            equal(paper[i], ThemePalette.presetPaper(i));
            equal(ink[i], ThemePalette.presetInk(i));
            int[] resolved = ThemePalette.resolve(i, 0, 0);
            equal(paper[i], resolved[0]);
            equal(ink[i], resolved[1]);
        }
    }

    private static void resolveContract() {
        int customBg = 0xFFE8F0E3;
        int customInk = 0xFF1F3A2A;
        int[] both = ThemePalette.resolve(0, customBg, customInk);
        equal(customBg, both[0]);
        equal(customInk, both[1]);
        int[] bgOnly = ThemePalette.resolve(1, customBg, 0);
        equal(customBg, bgOnly[0]);
        equal(0xFF473D2C, bgOnly[1]);
        int[] inkOnly = ThemePalette.resolve(2, 0, customInk);
        equal(0xFF18221F, inkOnly[0]);
        equal(customInk, inkOnly[1]);
        equal(0xFFF7F5EF, ThemePalette.resolve(-9, 0, 0)[0], "负 preset clamp 到 0");
        equal(0xFF000000, ThemePalette.resolve(99, 0, 0)[0], "过大 preset clamp 到 3 (AMOLED)");
        equal(3, ThemePalette.clamp(99));
        equal(0, ThemePalette.clamp(-1));
    }

    private static void clampPreservesAmoled() {
        equal(3, ThemePalette.clamp(3), "theme=3 (AMOLED) 不得被压成夜间 2");
        equal(3, ThemePalette.clamp(100));
        equal(0, ThemePalette.clamp(-1));
    }

    private static void ratioBoundaries() {
        approx(1.0, ThemePalette.ratio(0xFF123456, 0xFF123456), 1e-9);
        approx(21.0, ThemePalette.ratio(0xFF000000, 0xFFFFFFFF), 1e-9);
        approx(21.0, ThemePalette.ratio(0xFFFFFFFF, 0xFF000000), 1e-9);
        approx(ThemePalette.ratio(0xFF24302A, 0xFFF7F5EF),
                ThemePalette.ratio(0xFFF7F5EF, 0xFF24302A), 1e-9);
        approx(1.0, ThemePalette.ratio(0x00000000, 0xFF000000), 1e-9, "alpha 不计入亮度");
    }

    private static void thresholdAndDarkness() {
        check(ThemePalette.lowContrast(0xFF777777, 0xFFFFFFFF), "#777 on white 约 4.48 < 4.5");
        check(!ThemePalette.lowContrast(0xFF767676, 0xFFFFFFFF), "#767 on white 约 4.54 > 4.5");
        check(!ThemePalette.lowContrast(0xFF24302A, 0xFFF7F5EF), "纸白预设必须达标");
        check(!ThemePalette.lowContrast(0xFFD3DBD4, 0xFF18221F), "夜间预设必须达标");
        check(ThemePalette.isDark(0xFF18221F), "夜间预设判为深色");
        check(!ThemePalette.isDark(0xFFF7F5EF), "纸白预设判为浅色");
        check(ThemePalette.isDark(0xFF000000) && !ThemePalette.isDark(0xFFFFFFFF), "黑白边界");
    }

    private static void amoledPreset() {
        equal(4, ThemePalette.PRESET_COUNT);
        equal(0xFF000000, ThemePalette.presetPaper(3)); // 纯黑背景
        equal(0xFFE8E6E1, ThemePalette.presetInk(3));   // 浅灰字，非纯白降低 OLED 光晕
        int[] resolved = ThemePalette.resolve(3, 0, 0);
        equal(0xFF000000, resolved[0]);
        equal(0xFFE8E6E1, resolved[1]);
        equal(0xFF000000, ThemePalette.presetPaper(99)); // 越界 clamp 到 AMOLED
        check(ThemePalette.isDark(ThemePalette.presetPaper(3)), "AMOLED 应判为深色");
        check(!ThemePalette.lowContrast(ThemePalette.presetPaper(3), ThemePalette.presetInk(3)),
                "AMOLED 对比度应达 AA");
    }

    private static void samePaletteGuardsInit() {
        int paper = ThemePalette.presetPaper(0);
        int ink = ThemePalette.presetInk(0);
        check(ThemePalette.samePalette(0, paper, ink, 0, paper, ink), "完全一致应为 true");
        check(!ThemePalette.samePalette(0, paper, ink, 0, 0, 0),
                "字段初值 0 必须判为不同，否则 onResume 会提前调 colors() 导致 NPE");
        check(!ThemePalette.samePalette(1, paper, ink, 0, paper, ink), "主题不同应为 false");
        check(!ThemePalette.samePalette(0, 0xFFE8F0E3, ink, 0, paper, ink), "背景不同应为 false");
        check(!ThemePalette.samePalette(0, paper, 0xFF555555, 0, paper, ink), "字色不同应为 false");
        check(ThemePalette.samePalette(2, 0xFF18221F, 0xFFD3DBD4, 2, 0xFF18221F, 0xFFD3DBD4),
                "夜间预设一致应为 true");
    }

    private static void presetNameContract() {
        equal("纸白", ThemePalette.presetName(0));
        equal("暖纸", ThemePalette.presetName(1));
        equal("夜间", ThemePalette.presetName(2));
        equal("AMOLED 纯黑", ThemePalette.presetName(3));
        equal("纸白", ThemePalette.presetName(-8), "负数 clamp 到纸白");
        equal("AMOLED 纯黑", ThemePalette.presetName(99), "大数 clamp 到 AMOLED 纯黑");
        equal(4, ThemePalette.PRESET_COUNT);
        // 名单长度必须与档位数一致，否则 clamp 后索引越界。
        equal(ThemePalette.presetName(ThemePalette.PRESET_COUNT - 1),
                ThemePalette.presetName(999));
    }

    /**
     * 验收铁律：令牌必须「角色 → 按 theme 取值」。4 个主题档逐一走查每条前景/背景组合，
     * 任何一档低于 WCAG AA 4.5:1 即失败——特别是 AMOLED (#000000) 档。
     */
    private static void semanticRoleContrast() {
        String[] names = {"纸白", "暖纸", "夜间", "AMOLED 纯黑"};
        // 控制台输出用 ASCII 标签，避免非 UTF-8 终端出现乱码。
        String[] labels = {"paper", "warm", "ink", "amoled"};
        for (int t = 0; t < ThemePalette.PRESET_COUNT; t++) {
            int surface = ThemePalette.surface(t);
            int accent = ThemePalette.accent(t);
            int onAccent = ThemePalette.onAccent(t);
            int wide = ThemePalette.selectedContainer(t, true);
            int filled = ThemePalette.selectedContainer(t, false);

            equal(ThemePalette.presetPaper(t), surface, "surface 必须等于主题底色");
            equal(accent, filled, "非 wide 选中态容器 = accent 填充");

            // 1) 强调色在主题底色上可读（进度条、选中指示线）
            double rSurface = ThemePalette.ratio(accent, surface);
            check(!ThemePalette.lowContrast(accent, surface),
                    names[t] + " accent/surface 低对比 " + rSurface);
            check(rSurface >= ThemePalette.MIN_CONTRAST,
                    names[t] + " accent/surface 未达 AA " + rSurface);

            // 2) onAccent 在强调色填充上可读
            double rAccent = ThemePalette.ratio(onAccent, accent);
            check(rAccent >= ThemePalette.MIN_CONTRAST,
                    names[t] + " onAccent/accent 未达 AA " + rAccent);

            // 3) onAccent 在选中态容器上可读（非 wide 容器即 accent 填充）
            double rFilled = ThemePalette.ratio(onAccent, filled);
            check(rFilled >= ThemePalette.MIN_CONTRAST,
                    names[t] + " onAccent/selectedContainer(false) 未达 AA " + rFilled);

            // 4) 宽屏左栏中性容器上承载 accent 文字，同样需达 AA
            double rWide = ThemePalette.ratio(accent, wide);
            check(rWide >= ThemePalette.MIN_CONTRAST,
                    names[t] + " accent/selectedContainer(true) 未达 AA " + rWide);

            System.out.printf("ThemePalette[%d %s] accent/surface=%.2f onAccent/accent=%.2f "
                            + "onAccent/filled=%.2f accent/wide=%.2f%n",
                    t, labels[t], rSurface, rAccent, rFilled, rWide);
        }

        // 越界值与边界档一致（所有角色方法均内部 clamp）。
        equal(ThemePalette.accent(-5), ThemePalette.accent(0));
        equal(ThemePalette.accent(99), ThemePalette.accent(3));
        equal(ThemePalette.onAccent(-5), ThemePalette.onAccent(0));
        equal(ThemePalette.onAccent(99), ThemePalette.onAccent(3));
        equal(ThemePalette.selectedContainer(-5, true), ThemePalette.selectedContainer(0, true));
        equal(ThemePalette.selectedContainer(99, true), ThemePalette.selectedContainer(3, true));
        equal(ThemePalette.selectedContainer(-5, false), ThemePalette.selectedContainer(0, false));
    }

    /**
     * A11Y-1：次级说明文字（Ui.MUTED）在设置面板两种底色上的对比度必须达 WCAG AA 4.5:1。
     *
     * <p>Ui 类在 app 包且依赖 android.Context，core runner 不引入 android.jar；按 README 的
     * 「固定色值复制进测试」范式在此内联同一 ARGB 常量，并附 Ui.java 的源行号便于同步。
     * 面板底色 0xFFF7F3EA 来自 ReaderActivity.showReaderOptions() 的
     * Color.rgb(247, 243, 234)。阈值断言留 0.1 余量（要求 >=4.6）以防后续微调回退到 4.5 边缘。
     */
    private static void mutedContrastOnPanelSurfaces() {
        final int muted = 0xFF706A65;              // Ui.MUTED（Ui.java）
        final int panel = 0xFFF2F1EE;              // Ui.PANEL（Ui.java）
        final int optionsPanel = 0xFFF7F3EA;       // 阅读设置面板底色（showReaderOptions）
        final double minWithMargin = 4.6;

        double onPanel = ThemePalette.ratio(muted, panel);
        double onOptions = ThemePalette.ratio(muted, optionsPanel);
        check(!ThemePalette.lowContrast(muted, panel), "MUTED/PANEL 低于 4.5：" + onPanel);
        check(!ThemePalette.lowContrast(muted, optionsPanel), "MUTED/面板底色 低于 4.5：" + onOptions);
        check(onPanel >= minWithMargin, "MUTED/PANEL 未留余量达 4.6：" + onPanel);
        check(onOptions >= minWithMargin, "MUTED/面板底色 未留余量达 4.6：" + onOptions);
        // 亮度单调性守门：面板底色更亮，故其对比度必须不低于 PANEL 上的对比度。
        check(ThemePalette.luminance(optionsPanel) > ThemePalette.luminance(panel)
                && onOptions > onPanel, "面板底色应比 PANEL 亮且对比度更高");
        System.out.printf("A11Y-1 muted contrast: on PANEL=%.4f on OPTIONS_PANEL=%.4f%n", onPanel, onOptions);
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
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException("expected=" + expected + " actual=" + actual);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(message + "：expected=" + expected + " actual=" + actual);
        }
    }

    private static void approx(double expected, double actual, double tolerance) {
        approx(expected, actual, tolerance, "数值不接近");
    }

    private static void approx(double expected, double actual, double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new IllegalStateException(message + "：expected=" + expected + " actual=" + actual);
        }
    }
}
