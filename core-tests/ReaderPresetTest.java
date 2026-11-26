import org.openleaf.reader.core.ReaderPresets;

/** 智能排版预设映射回归；纯 JVM，无 JUnit、无第三方依赖。 */
public final class ReaderPresetTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        test("五档预设映射合法且长度 7", ReaderPresetTest::allPresetsInRange);
        test("标准预设锁死 0.27.0 默认值", ReaderPresetTest::standardIsDefault);
        test("非法 id 降级标准、不抛异常", ReaderPresetTest::illegalIdFallback);
        test("逐字全等可反查、微调后为自定义", ReaderPresetTest::detectPreset);
        test("valuesFor 返回副本、改返回值不污染预设", ReaderPresetTest::defensiveCopy);
        System.out.println("ReaderPresetTest: " + passed + " checks passed");
    }

    private static void allPresetsInRange() {
        int[] expectedStandard = {2, 1, 0, 2, 0, 0, 0};
        int[][][] cases = {
                {{ReaderPresets.COMPACT}, {0, 0, 0, 0, 0, 0, 0}},
                {{ReaderPresets.STANDARD}, expectedStandard},
                {{ReaderPresets.LOOSE}, {4, 3, 1, 4, 1, 0, 0}},
                {{ReaderPresets.NOVEL}, {2, 1, 0, 2, 0, 1, 0}},
                {{ReaderPresets.COMIC}, {1, 1, 0, 5, 2, 0, 0}}
        };
        int[] upper = {6, 5, 5, 6, 4, 3, 3}; // 对应 ReaderStyle 七个档位数组长度
        for (int[][] oneCase : cases) {
            int id = oneCase[0][0];
            int[] values = ReaderPresets.valuesFor(id);
            equal(ReaderPresets.STYLE_COUNT, values.length);
            for (int i = 0; i < values.length; i++) {
                check(values[i] >= 0 && values[i] < upper[i],
                        "预设 " + id + " 第 " + i + " 项越界: " + values[i]);
                equal(oneCase[1][i], values[i]);
            }
            equal(id, ReaderPresets.detectPreset(values));
            check(ReaderPresets.isValid(id), "合法 id 应通过 isValid");
        }
        check(!ReaderPresets.isValid(-1) && !ReaderPresets.isValid(6), "越界 id 不应合法");
    }

    private static void standardIsDefault() {
        int[] standard = ReaderPresets.valuesFor(ReaderPresets.STANDARD);
        // ReaderStyle.java 构造默认：line=2, para=1, letter=0, margin=2, align=0, indent=0, convert=0
        int[] defaults = {2, 1, 0, 2, 0, 0, 0};
        for (int i = 0; i < defaults.length; i++) equal(defaults[i], standard[i]);
        equal(ReaderPresets.STANDARD, ReaderPresets.detectPreset(defaults));
    }

    private static void illegalIdFallback() {
        int[] standard = ReaderPresets.valuesFor(ReaderPresets.STANDARD);
        for (int id : new int[]{-7, -1, 6, 99, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            int[] values = ReaderPresets.valuesFor(id);
            for (int i = 0; i < standard.length; i++) equal(standard[i], values[i]);
        }
    }

    private static void detectPreset() {
        int[] novel = ReaderPresets.valuesFor(ReaderPresets.NOVEL);
        equal(ReaderPresets.NOVEL, ReaderPresets.detectPreset(novel));
        novel[0] = novel[0] == 0 ? 1 : novel[0] - 1; // 模拟用户微调一行距
        equal(ReaderPresets.CUSTOM, ReaderPresets.detectPreset(novel));
        equal(ReaderPresets.CUSTOM, ReaderPresets.detectPreset(null));
        equal(ReaderPresets.CUSTOM, ReaderPresets.detectPreset(new int[]{2, 1}));
    }

    private static void defensiveCopy() {
        int[] first = ReaderPresets.valuesFor(ReaderPresets.COMIC);
        first[0] = 99;
        int[] second = ReaderPresets.valuesFor(ReaderPresets.COMIC);
        check(second[0] != 99, "valuesFor 应返回副本，不能被外部修改污染");
        equal(ReaderPresets.COMIC, ReaderPresets.detectPreset(second));
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + "; got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void test(String name, Runnable action) {
        try { action.run(); passed++; System.out.println("OK " + name); }
        catch (Throwable failure) { throw new AssertionError("FAILED: " + name, failure); }
    }
}
