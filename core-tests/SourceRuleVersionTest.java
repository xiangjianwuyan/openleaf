import org.openleaf.reader.core.SourceRuleVersion;
import java.util.LinkedHashMap;
import java.util.Map;

/** 书源规则版本稳定性测试（纯 JVM）。 */
public class SourceRuleVersionTest {
    static int passed = 0;

    public static void main(String[] args) {
        canonicalOrder();
        noAmbiguity();
        emptyDeterministic();
        whitelistSensitivity();
        changedLogic();
        shortVersion();
        System.out.println("PASS SourceRuleVersionTest (" + passed + " checks)");
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        passed++;
    }

    static Map<String, String> fields(String searchUrl, String search, String info, String toc, String content) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("searchUrl", searchUrl);
        map.put("search", search);
        map.put("info", info);
        map.put("toc", toc);
        map.put("content", content);
        return map;
    }

    static void canonicalOrder() {
        String a = SourceRuleVersion.compute(fields("https://a/s?q={{key}}", "{\"a\":1,\"b\":2}", "{}", "{\"l\":1}", "{\"c\":1}"));
        // 插入顺序不同但内容一致 → 版本必须相同
        Map<String, String> reordered = new LinkedHashMap<>();
        reordered.put("content", "{\"c\":1}");
        reordered.put("toc", "{\"l\":1}");
        reordered.put("info", "{}");
        reordered.put("search", "{\"a\":1,\"b\":2}");
        reordered.put("searchUrl", "https://a/s?q={{key}}");
        String b = SourceRuleVersion.compute(reordered);
        check(a.equals(b), "字段插入顺序不同应得到相同版本");
        check(a.length() == 64, "版本应为 SHA-256 hex(64)");
    }

    static void noAmbiguity() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("x", "a"); one.put("y", "bc");
        Map<String, String> two = new LinkedHashMap<>();
        two.put("x", "ab"); two.put("y", "c");
        check(!SourceRuleVersion.compute(one).equals(SourceRuleVersion.compute(two)), "长度前缀应消除拼接歧义");
    }

    static void emptyDeterministic() {
        String a = SourceRuleVersion.compute(null);
        String b = SourceRuleVersion.compute(new LinkedHashMap<String, String>());
        check(a.equals(b), "空字段集合应得到确定版本");
    }

    static void whitelistSensitivity() {
        String base = SourceRuleVersion.compute(fields("u", "s", "i", "t", "c"));
        // 只改 searchUrl（旧算法会漏掉这种变更）
        String changed = SourceRuleVersion.compute(fields("u2", "s", "i", "t", "c"));
        check(!base.equals(changed), "searchUrl 变化应触发版本变化");
        // 只改正文规则
        String contentChanged = SourceRuleVersion.compute(fields("u", "s", "i", "t", "c2"));
        check(!base.equals(contentChanged), "content 变化应触发版本变化");
    }

    static void changedLogic() {
        check(!SourceRuleVersion.changed("abc", "abc"), "相同版本不失配");
        check(SourceRuleVersion.changed("abc", "def"), "不同版本失配");
        check(!SourceRuleVersion.changed(null, null), "双空不失配");
        check(SourceRuleVersion.changed(null, "abc"), "存储缺失视为失配");
    }

    static void shortVersion() {
        check(SourceRuleVersion.shortVersion(null).contains("无版本"), "空版本摘要");
        String shortOne = SourceRuleVersion.shortVersion("0123456789abcdef0123");
        check(shortOne.endsWith("…") && shortOne.length() <= 13, "长版本应截断并加省略号");
        check("abcd".equals(SourceRuleVersion.shortVersion("abcd")), "短版本原样返回");
    }
}
