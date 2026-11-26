import org.openleaf.reader.core.FontLibrary;
import org.openleaf.reader.core.FontLibrary.Entry;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.29.0 P2-3 预览契约：原创示例文本 + 损坏降级纯函数判定；纯 JVM、无 JUnit。
 * 竞品闭源样本的具体字符串绝不进入本仓库，这里只断言不含任何竞品标识/资源后缀。
 */
public final class FontPreviewContractTest {
    private static int passed;

    public static void main(String[] args) {
        test("SAMPLE_TEXT 非空且含中文/数字/拉丁", FontPreviewContractTest::sampleShape);
        test("SAMPLE_TEXT 与闭源样本标识无交集", FontPreviewContractTest::noCompetitorStrings);
        test("损坏条目不可选，预览降级判定纯函数", FontPreviewContractTest::previewDecision);
        test("控制字符与换行不破坏序列化往返", FontPreviewContractTest::controlCharRoundtrip);
        System.out.println("FontPreviewContractTest: " + passed + " checks passed");
    }

    private static void sampleShape() {
        String sample = FontLibrary.SAMPLE_TEXT;
        check(sample != null && !sample.isEmpty(), "示例文本必须非空");
        check(sample.contains("墨香伴长夜"), "示例文本必须含原创中文短句");
        check(sample.contains("0123456789"), "示例文本必须含数字");
        check(sample.contains("abcXYZ"), "示例文本必须含拉丁字母");
        check(sample.length() <= 120, "示例文本应足够短，避免卡片多行布局");
    }

    private static void noCompetitorStrings() {
        String sample = FontLibrary.SAMPLE_TEXT;
        String[] forbidden = {"静读天下", "多看阅读", "Moon+", "Duokan", "Calluna", ".ttf", ".otf", "DkKernel"};
        for (String marker : forbidden) {
            check(!sample.contains(marker), "示例文本不得含第三方标识/资源名: " + marker);
        }
    }

    private static void previewDecision() {
        Entry valid = new Entry("aaaaaaaaaaaaaaaa", "可用字体", "ttf", true);
        Entry broken = new Entry("bbbbbbbbbbbbbbbb", "损坏字体", "otf", false);
        equal("aaaaaaaaaaaaaaaa", previewFontId(valid));
        equal("", previewFontId(broken));
        equal("", previewFontId(null));
        check(!FontLibrary.selectable(broken), "损坏字体卡片必须灰化禁用");
    }

    /** 与 UI 一致的预览降级判定：不可选条目返回空 id，UI 用系统默认字形占位。 */
    private static String previewFontId(Entry entry) {
        return FontLibrary.selectable(entry) ? entry.id : "";
    }

    private static void controlCharRoundtrip() {
        Entry tricky = new Entry("cccccccccccccccc", "第一行\n第二行\r\t结束\u001F\u001E😀", "ttf", true);
        List<Entry> one = new ArrayList<>();
        one.add(tricky);
        List<Entry> decoded = FontLibrary.deserialize(FontLibrary.serialize(one));
        equal(1, decoded.size());
        equal(FontLibrary.normalizeDisplayName(tricky.name), decoded.get(0).name);
        check(!decoded.get(0).name.contains("\n") && !decoded.get(0).name.contains("\u001F"),
                "控制字符必须被归一化剔除，不得破坏分隔符格式");
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
}
