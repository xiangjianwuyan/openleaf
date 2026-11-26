import org.openleaf.reader.core.MobiChapters;
import org.openleaf.reader.core.MobiChapters.Chapter;

import java.util.Collections;
import java.util.List;

/** MOBI 纯文本章节切分回归；无 JUnit、无第三方依赖，断言始终启用。 */
public final class MobiChaptersTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        test("空 / null / 非正上限 → 空列表", MobiChaptersTest::empty);
        test("无标题信号 → 整篇单章，标题留空", MobiChaptersTest::noHeading);
        test("中文卷/章标题 → 正确切章并提取标题", MobiChaptersTest::chineseHeadings);
        test("Markdown 标题 → 正确切章", MobiChaptersTest::markdownHeadings);
        test("首个标题前的文字 → 独立成章", MobiChaptersTest::preamble);
        test("maxChapters 截断 → 只保留前 N 章", MobiChaptersTest::chapterCap);
        test("超长章按字符上限细分并加序号", MobiChaptersTest::charCap);
        test("超长标题截断到 MAX_TITLE_CHARS", MobiChaptersTest::titleCap);
        test("代理对不被切断", MobiChaptersTest::surrogateSafe);
        System.out.println("PASS: " + passed + " MobiChapters checks");
    }

    private static void empty() {
        equal(0, MobiChapters.split(null, 2000, 20000).size());
        equal(0, MobiChapters.split("", 2000, 20000).size());
        equal(0, MobiChapters.split("   \n\t ", 2000, 20000).size());
        equal(0, MobiChapters.split("正文", 0, 20000).size());
        equal(0, MobiChapters.split("正文", 2000, -1).size());
        check(MobiChapters.split(null, 2000, 20000).isEmpty(), "空输入应返回不可变空列表");
    }

    private static void noHeading() {
        List<Chapter> chapters = MobiChapters.split("第一段。\n第二段。\n第三段。", 2000, 20000);
        equal(1, chapters.size());
        equal("", chapters.get(0).title);
        check(chapters.get(0).text.contains("第一段"), "正文应保留");
        check(chapters.get(0).text.contains("第三段"), "正文应完整");
    }

    private static void chineseHeadings() {
        String raw = "第一卷 风起\n卷首语。\n第一章 初遇\n相遇了。\n第二章 相逢\n又相见。\n"
                + "第二卷 云涌\n第三章 别离\n再见。";
        List<Chapter> cs = MobiChapters.split(raw, 2000, 20000);
        equal(5, cs.size());
        equal("第一卷 风起", cs.get(0).title);
        equal("第一章 初遇", cs.get(1).title);
        equal("第二章 相逢", cs.get(2).title);
        equal("第二卷 云涌", cs.get(3).title);
        equal("第三章 别离", cs.get(4).title);
        check(cs.get(1).text.startsWith("第一章 初遇"), "标题应保留在正文首行");
        check(cs.get(1).text.contains("相遇了"), "章正文应保留");
    }

    private static void markdownHeadings() {
        List<Chapter> cs = MobiChapters.split("# 卷一\n内容一\n## 第一章\n内容二\n# 卷二\n内容三", 2000, 20000);
        equal(3, cs.size());
        equal("卷一", cs.get(0).title);
        equal("第一章", cs.get(1).title);
        equal("卷二", cs.get(2).title);
    }

    private static void preamble() {
        List<Chapter> cs = MobiChapters.split("这是前言。\n第一章 开始\n正文。", 2000, 20000);
        equal(2, cs.size());
        equal("", cs.get(0).title);
        check(cs.get(0).text.contains("这是前言"), "前言应独立成章");
        equal("第一章 开始", cs.get(1).title);
    }

    private static void chapterCap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) sb.append("第").append(i).append("章 标题").append(i).append("\n正文").append(i).append('\n');
        equal(3, MobiChapters.split(sb.toString(), 3, 20000).size());
        List<Chapter> all = MobiChapters.split(sb.toString(), 2000, 20000);
        equal(10, all.size());
        equal("第1章 标题1", all.get(0).title);
    }

    private static void charCap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("字"); // 100 个中文字符，无换行/空格
        List<Chapter> cs = MobiChapters.split(sb.toString(), 10, 30);
        equal(4, cs.size()); // ceil(100/30)
        int total = 0;
        for (Chapter c : cs) {
            check(c.text.length() <= 30, "每章不应超过 30 字符: " + c.text.length());
            total += c.text.length();
        }
        equal(100, total);
        check(cs.get(0).title.contains("(1/4)"), "细分章标题应带序号: " + cs.get(0).title);
    }

    private static void titleCap() {
        StringBuilder sb = new StringBuilder("第1章 ");
        for (int i = 0; i < 400; i++) sb.append("长");
        sb.append("\n正文。");
        List<Chapter> cs = MobiChapters.split(sb.toString(), 10, 20000);
        equal(1, cs.size());
        check(cs.get(0).title.length() <= MobiChapters.MAX_TITLE_CHARS + 1,
                "标题应截断: " + cs.get(0).title.length());
        check(cs.get(0).title.endsWith("…"), "截断标题应以省略号结尾");
    }

    private static void surrogateSafe() {
        // 每 29 个普通字符夹一个 emoji（代理对），上限 30 → 切割点不应拆开代理对。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            for (int k = 0; k < 29; k++) sb.append('a');
            sb.append(new String(Character.toChars(0x1F600)));
        }
        List<Chapter> cs = MobiChapters.split(sb.toString(), 20, 30);
        check(cs.size() >= 12, "应切成多章: " + cs.size());
        for (Chapter c : cs) {
            for (int i = 0; i < c.text.length(); i++) {
                char ch = c.text.charAt(i);
                check(!Character.isHighSurrogate(ch) || (i + 1 < c.text.length() && Character.isLowSurrogate(c.text.charAt(i + 1))),
                        "高代理后必须跟低代理");
            }
        }
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
