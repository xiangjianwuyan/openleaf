import org.openleaf.reader.core.ChapterRule;
import org.openleaf.reader.core.TextChapterHeadings;

/** Standalone core regression for the pure-JVM chapter rule engine; no JUnit or Android. */
public final class ChapterRuleTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        test("保守档逐样本与 TextChapterHeadings 完全一致", ChapterRuleTest::conservativeParity);
        test("三档命中数递增（中文/网文/方括号）", ChapterRuleTest::tieredMatches);
        test("自定义正则命中与保守兜底", ChapterRuleTest::customRegex);
        test("非法正则校验与降级不崩溃", ChapterRuleTest::invalidRegex);
        test("命中预览计数与行数上限", ChapterRuleTest::previewCount);

        System.out.println("ChapterRuleTest: " + passed + " checks passed");
    }

    /** 保守档必须逐字委托现有实现，包含 isolated 的两条分支。 */
    private static void conservativeParity() {
        String[] samples = {
                "第一章 归来", "序章", "Chapter 7 Awakening", "1. 起航", "[5] 暗流",
                "# 标题", "这是一段普通的正文，讲了一段故事。", "第12章归来", "卷三 风起",
                "3.14 是圆周率", "广告：请收藏本书", "【尾声】", "XII. Epilogue", ""
        };
        for (String sample : samples) {
            for (boolean isolated : new boolean[]{true, false}) {
                boolean expected = TextChapterHeadings.isHeading(sample, isolated);
                equal(expected, ChapterRule.conservative().isHeading(sample, isolated));
                equal(expected, ChapterRule.of(ChapterRule.Mode.CONSERVATIVE, "ignored")
                        .isHeading(sample, isolated));
            }
        }
        equal(ChapterRule.Mode.CONSERVATIVE, ChapterRule.conservative().mode());
        check(!ChapterRule.conservative().isCustom(), "保守档不应标记为自定义");
        check(ChapterRule.of(ChapterRule.Mode.CUSTOM, "^CH\\d+").isCustom(), "自定义档标记错误");
    }

    private static void tieredMatches() {
        String sample = "序章\n正文第一行\n1. 起航\n正文第二行\n[5] 暗流\n正文第三行\n";
        equal(1, ChapterRule.conservative().previewMatchCount(sample, 0));
        equal(2, ChapterRule.of(ChapterRule.Mode.STANDARD, "").previewMatchCount(sample, 0));
        equal(3, ChapterRule.of(ChapterRule.Mode.LOOSE, "").previewMatchCount(sample, 0));
        // 宽松档是标准档的超集，标准档是保守档的超集（逐行）
        String[] lines = sample.split("\n");
        for (int i = 0; i < lines.length; i++) {
            boolean isolated = (i == 0 || lines[i - 1].isEmpty())
                    && (i + 1 >= lines.length || lines[i + 1].isEmpty());
            boolean conservative = ChapterRule.conservative().isHeading(lines[i], isolated);
            boolean standard = ChapterRule.of(ChapterRule.Mode.STANDARD, "").isHeading(lines[i], isolated);
            boolean loose = ChapterRule.of(ChapterRule.Mode.LOOSE, "").isHeading(lines[i], isolated);
            check(!conservative || standard, "标准档必须是保守档超集：" + lines[i]);
            check(!standard || loose, "宽松档必须是标准档超集：" + lines[i]);
        }
        // 常见误判：小数行、纯正文、广告行不应因为切档被识别成标题
        ChapterRule loose = ChapterRule.of(ChapterRule.Mode.LOOSE, "");
        check(!loose.isHeading("3.14", true), "纯小数行不应命中宽松档");
        check(!loose.isHeading("这是一段普通的正文，讲了一段故事。", true), "普通正文不应命中宽松档");
        check(!loose.isHeading("广告：最新网址 www.example.com", true), "广告行不应命中宽松档");
    }

    private static void customRegex() {
        ChapterRule custom = ChapterRule.of(ChapterRule.Mode.CUSTOM, "^CH\\d+");
        check(custom.isHeading("CH07 星火", false), "自定义正则应命中 CH07");
        check(!custom.isHeading("CH 星火", false), "自定义正则不应命中不匹配行");
        // D2：自定义档叠加保守兜底，避免用户规则漏识别造成目录断裂
        check(custom.isHeading("序章", false), "自定义档应保留保守兜底");
        check(ChapterRule.of(ChapterRule.Mode.CUSTOM, "^第\\d+章").isHeading("第12章 归来", false),
                "自定义中文正则应命中");
        String sample = "CH07 星火\n正文\n第12章 归来\n正文\n";
        equal(2, custom.previewMatchCount(sample, 0));
    }

    private static void invalidRegex() {
        ChapterRule plain = ChapterRule.conservative();
        check(plain.validateRegex("([") != null, "非法正则必须被拒绝");
        check(plain.validateRegex("([").contains("不合法"), "非法正则应给出中文错误");
        check(plain.validateRegex("") != null, "空正则必须被拒绝");
        check(plain.validateRegex(null) != null, "null 正则必须被拒绝");
        check(plain.validateRegex("^第\\d+章") == null, "合法正则不应报错");

        // 非法正则不得抛异常、不得进入判定路径，且仍保留保守兜底
        ChapterRule degraded = ChapterRule.of(ChapterRule.Mode.CUSTOM, "([");
        check(!degraded.isHeading("CH07 星火", false), "非法正则不应命中任意自定义行");
        check(degraded.isHeading("序章", false), "非法正则降级后仍需保守兜底");
        ChapterRule nullRule = ChapterRule.of(ChapterRule.Mode.CUSTOM, null);
        check(nullRule.isHeading("序章", true), "null 正则降级后仍需保守兜底");
    }

    private static void previewCount() {
        equal(0, ChapterRule.conservative().previewMatchCount(null, 0));
        equal(0, ChapterRule.conservative().previewMatchCount("", 10));
        String sample = "序章\n第一章 归来\n";
        equal(2, ChapterRule.conservative().previewMatchCount(sample, 0));
        equal(1, ChapterRule.conservative().previewMatchCount(sample, 1));
        equal(2, ChapterRule.conservative().previewMatchCount(sample.replace("\n", "\r\n"), 0));
        // 采样上限只影响扫描行数，不改变判定语义
        equal(1, ChapterRule.of(ChapterRule.Mode.STANDARD, "")
                .previewMatchCount("1. 起航\n2. 远行\n", 1));
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + "; got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void test(String name, ThrowingRunnable action) throws Exception {
        try { action.run(); passed++; System.out.println("OK " + name); }
        catch (Throwable failure) { throw new AssertionError("FAILED: " + name, failure); }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
