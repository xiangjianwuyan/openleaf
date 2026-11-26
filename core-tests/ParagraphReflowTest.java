import org.openleaf.reader.core.ParagraphReflow;
import org.openleaf.reader.core.ParagraphReflow.Result;

import java.util.Arrays;
import java.util.Locale;

/** 段落重排与 map M1–M7 契约回归；纯 JVM，无 JUnit、无第三方依赖。 */
public final class ParagraphReflowTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        test("M4 三开关全关恒等", ParagraphReflowTest::identity);
        test("硬换行合并：被切断段落合并、段尾标点不合并", ParagraphReflowTest::mergeHardBreaks);
        test("诗歌/对话/fence 不误合并", ParagraphReflowTest::protectedLines);
        test("空行折叠：多个连续空行变一个", ParagraphReflowTest::collapseBlankLines);
        test("M7 智能缩进判定只产 boolean", ParagraphReflowTest::indentDecisions);
        test("M1/M2/M3 全部样本单调、不越界、逐字溯源", ParagraphReflowTest::mapContracts);
        test("M6 mapSpan lower_bound 与整段删除不塌缩", ParagraphReflowTest::mapSpanContract);
        test("标注/搜索 span 映射落点正确", ParagraphReflowTest::spanMapping);
        test("M5 简繁 1:1 转换后 map 仍有效", ParagraphReflowTest::oneToOneConversion);
        System.out.println("ParagraphReflowTest: " + passed + " checks passed");
    }

    private static void identity() {
        String original = "第一段，\n被切断。\n\n\n第二段。\n";
        Result off = ParagraphReflow.reflow(original, false, false, false);
        equal(original, off.displayText);
        equal(original.length(), off.map.length);
        for (int i = 0; i < off.map.length; i++) equal(i, off.map[i]);
        // detectIndent 单独打开也不改文本
        Result detectOnly = ParagraphReflow.reflow(original, false, false, true);
        equal(original, detectOnly.displayText);
        equalArray(off.map, detectOnly.map);
    }

    private static void mergeHardBreaks() {
        String cut = "这是第一段，\n被切断的下一行。\n真正第二段。";
        Result result = ParagraphReflow.reflow(cut, true, false, true);
        equal("这是第一段，被切断的下一行。\n真正第二段。", result.displayText);
        int newlineCount = 0;
        for (int i = 0; i < result.displayText.length(); i++)
            if (result.displayText.charAt(i) == '\n') newlineCount++;
        check(newlineCount == 1, "只应保留真正的段间换行，实际 " + newlineCount);
        // 段尾已有句号 → 真实段落结束，不合并
        String ended = "第一段已经结束。\n第二段开始，\n尚未结束。";
        Result endedResult = ParagraphReflow.reflow(ended, true, false, true);
        equal("第一段已经结束。\n第二段开始，尚未结束。", endedResult.displayText);
    }

    private static void protectedLines() {
        String poem = "\u3000床前明月光，\n\u3000疑是地上霜。\n";
        equal(poem, ParagraphReflow.reflow(poem, true, true, true).displayText);
        String dialogue = "他继续说道，\n「你什么时候到？」\n";
        equal(dialogue, ParagraphReflow.reflow(dialogue, true, true, true).displayText);
        String asciiQuote = "他继续说道，\n\"你什么时候到？\"\n";
        equal(asciiQuote, ParagraphReflow.reflow(asciiQuote, true, true, true).displayText);
        String fence = "前文未完，\n```\n代码行\n```\n后文继续。";
        equal(fence, ParagraphReflow.reflow(fence, true, true, true).displayText);
        String tilde = "前文未完，\n~~~\n另一段代码\n~~~\n后文继续。";
        equal(tilde, ParagraphReflow.reflow(tilde, true, true, true).displayText);
        // 文件开头的单个空行也不应被合并
        equal("\n正文。", ParagraphReflow.reflow("\n正文。", true, false, true).displayText);
    }

    private static void collapseBlankLines() {
        String many = "第一段。\n\n\n\n第二段。";
        Result result = ParagraphReflow.reflow(many, false, true, false);
        equal("第一段。\n\n第二段。", result.displayText);
        equal("第一段。\n\n第二段。",
                ParagraphReflow.reflow(many, true, true, true).displayText);
        // 单个段间空行保持不变
        equal("A。\n\nB。", ParagraphReflow.reflow("A。\n\nB。", false, true, false).displayText);
    }

    private static void indentDecisions() {
        boolean[] decisions = ParagraphReflow.indentDecisions("\u3000诗行\n普通行\n\tTab 行\n 空格行");
        equalArray(new boolean[]{true, false, true, true}, decisions);
        equal(0, ParagraphReflow.indentDecisions("").length);
        equal(2, ParagraphReflow.indentDecisions("甲\n乙").length);
    }

    private static void mapContracts() {
        String[] samples = {
                "第一段，\n被切断。\n第二段。",
                "第一段。\n\n\n\n第二段，\n继续。",
                "\u3000床前明月光，\n\u3000疑是地上霜。\n",
                "前文，\n```\n代码\n```\n后文。",
                "A\nB\nC",
                "纯单行无换行"
        };
        for (String original : samples) {
            Result reflowed = ParagraphReflow.reflow(original, true, true, true);
            checkM1ToM3(original, reflowed);
        }
    }

    private static void checkM1ToM3(String original, Result result) {
        equal(original.length() >= result.map.length, true);
        equal(result.displayText.length(), result.map.length); // M1
        int previous = -1;
        for (int i = 0; i < result.map.length; i++) {
            int source = result.map[i];
            if (original.isEmpty()) break;
            check(source >= 0 && source <= original.length() - 1,
                    "M2 越界: map[" + i + "]=" + source + " len=" + original.length());
            check(source >= previous, "M2 非单调: map[" + i + "]=" + source + " prev=" + previous);
            equal(String.valueOf(original.charAt(source)), String.valueOf(result.displayText.charAt(i)));
            previous = source;
        }
    }

    private static void mapSpanContract() {
        // "abc\ndef" 的换行被合并：map = [0,1,2,4,5,6]
        String original = "abc\ndef";
        Result result = ParagraphReflow.reflow(original, true, false, true);
        equal("abcdef", result.displayText);
        int[] map = result.map;
        equalArray(new int[]{0, 1, 2, 4, 5, 6}, map);
        int len = result.displayText.length();

        equalArray(new int[]{0, 3}, ParagraphReflow.mapSpan(map, 0, 3, len));
        // 跨过被删除的换行 \n（原文偏移 3）：lower_bound 落到 3，且不塌缩为 0 长
        equalArray(new int[]{3, 4}, ParagraphReflow.mapSpan(map, 3, 4, len));
        equalArray(new int[]{3, 5}, ParagraphReflow.mapSpan(map, 4, 6, len));
        equalArray(new int[]{len, len}, ParagraphReflow.mapSpan(map, 99, 100, len));
        equalArray(new int[]{0, 1}, ParagraphReflow.mapSpan(map, -5, 0, len));
        // 末尾显示长度小于 map 长度时按显示长度 clamp，不越界
        equalArray(new int[]{0, 2}, ParagraphReflow.mapSpan(map, 0, 2, 2));
    }

    private static void spanMapping() {
        String original = "这是第一段，\n被切断的下一行。\n真正第二段。";
        Result result = ParagraphReflow.reflow(original, true, false, true);
        String display = result.displayText;
        int len = display.length();
        // 模拟标注锚点：覆盖原文 [0,6) = "这是第一段，"（换行前的 6 个 char）
        int[] annotation = ParagraphReflow.mapSpan(result.map, 0, 6, len);
        equal("这是第一段，", display.substring(annotation[0], annotation[1]));
        // 模拟搜索 hit：覆盖原文 [7,13) = "被切断的下一行。"（跨过被删换行）
        int[] hit = ParagraphReflow.mapSpan(result.map, 7, 15, len);
        check(hit[0] < hit[1], "搜索 span 不应塌缩为 0 长");
        equal("被切断的下一行。", display.substring(hit[0], hit[1]));
        // 第二个 hit 仍在真实段内
        int[] second = ParagraphReflow.mapSpan(result.map, 16, 22, len);
        equal("真正第二段。", display.substring(second[0], second[1]));
    }

    private static void oneToOneConversion() {
        String original = "这是第一段，\n被切断的下一行。\n真正第二段。";
        Result result = ParagraphReflow.reflow(original, true, false, true);
        // 模拟 ChineseConverter：逐 char 替换且长度不变（这里用大写化模拟等长映射）
        String converted = result.displayText.toUpperCase(Locale.ROOT);
        equal(result.displayText.length(), converted.length());
        for (int i = 0; i < converted.length(); i++) {
            check(result.map[i] >= 0 && result.map[i] < original.length(),
                    "转换后 map 必须仍然有效: i=" + i);
        }
    }

    private static void equalArray(int[] expected, int[] actual) {
        if (!Arrays.equals(expected, actual))
            throw new AssertionError("expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual));
    }

    private static void equalArray(boolean[] expected, boolean[] actual) {
        if (!Arrays.equals(expected, actual))
            throw new AssertionError("expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual));
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
