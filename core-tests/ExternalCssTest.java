import org.openleaf.reader.core.BookParser;

import java.util.Map;

/**
 * 外部 CSS 白名单回归（设计 §9 / §14）：parseCss() 走与书内 CSS 相同的 addCss/safeCss
 * 白名单；合法 color/background-color/font-family/border 保留，恶意向量与布局属性被拒。
 * 纯 JVM，无 JUnit、无第三方依赖；断言始终启用。
 */
public final class ExternalCssTest {
    private static int passed;

    public static void main(String[] args) {
        test("合法四项白名单属性保留", ExternalCssTest::legalFourProperties);
        test("命名色/透明色/继承值保留", ExternalCssTest::namedColors);
        test("url(javascript:) 被拒并打印断言", ExternalCssTest::rejectJavascriptUrl);
        test("@import 被拒并打印断言", ExternalCssTest::rejectImport);
        test("expression() 被拒并打印断言", ExternalCssTest::rejectExpression);
        test("布局属性/!important/伪类被拒", ExternalCssTest::rejectLayoutAndPseudo);
        test("超长 CSS 与超长声明被截断/拒绝", ExternalCssTest::rejectOversize);
        test("既有属性行为不退化", ExternalCssTest::existingPropertiesUnchanged);
        test("返回 Map 不可修改", ExternalCssTest::immutableResult);
        test("extraRules 在原书样式关闭时独立生效/旧路径不变", ExternalCssTest::htmlThreeWayRender);
        System.out.println("PASS: " + passed + " ExternalCss checks");
    }

    private static void legalFourProperties() {
        String css = "p { color: #1a2b3c; background-color: rgb(255, 128, 0); "
                + "font-family: serif, sans-serif; border: 1px solid #abc; }";
        Map<String, String> rules = BookParser.parseCss(css);
        String rule = rules.get("p");
        check(rule != null, "合法 CSS 应生成 p 规则, rules=" + rules);
        check(rule.contains("color:#1a2b3c;"), "color 应保留: " + rule);
        check(rule.contains("background-color:rgb(255, 128, 0);"), "background-color 应保留: " + rule);
        check(rule.contains("font-family:serif, sans-serif;"), "font-family 应保留: " + rule);
        check(rule.contains("border:1px solid #abc;"), "border 应保留: " + rule);
        System.out.println("  [keep] p -> " + rule);
    }

    private static void namedColors() {
        Map<String, String> rules = BookParser.parseCss(
                "a { color: red; background-color: transparent; } b { color: currentcolor; }");
        check(rules.get("a").contains("color:red;"), "命名色 red 应保留");
        check(rules.get("a").contains("background-color:transparent;"), "transparent 应保留");
        check(rules.get("b").contains("color:currentcolor;"), "currentcolor 应保留");
    }

    private static void rejectJavascriptUrl() {
        String css = "p { color: url(javascript:alert(1)); }";
        Map<String, String> rules = BookParser.parseCss(css);
        String dump = dump(rules);
        check(!dump.contains("javascript:"), "javascript: 不应出现在 rules: " + dump);
        check(rules.get("p") == null || !rules.get("p").contains("url("), "url() 不应被保留: " + dump);
        check(rules.isEmpty(), "整条声明非法时不应产出 p 规则: " + dump);
        System.out.println("  [reject] url(javascript:alert(1)) -> rules=" + dump
                + " containsJavaScript=" + dump.contains("javascript:"));
    }

    private static void rejectImport() {
        String css = "@import url(javascript:alert(1)); p { color: red; }";
        Map<String, String> rules = BookParser.parseCss(css);
        String dump = dump(rules);
        check(!dump.contains("@import"), "@import 不应出现在 rules: " + dump);
        check(!dump.contains("javascript:"), "@import 携带的 javascript: 不应出现: " + dump);
        System.out.println("  [reject] @import url(javascript:alert(1)) -> rules=" + dump
                + " containsImport=" + dump.contains("@import"));
    }

    private static void rejectExpression() {
        String css = "p { color: expression(alert(1)); text-align: center; }";
        Map<String, String> rules = BookParser.parseCss(css);
        String rule = rules.get("p");
        check(rule != null, "合法的 text-align 仍应产出规则: " + rules);
        check(!rule.contains("expression"), "expression() 不应出现在 rules: " + rule);
        check(rule.contains("text-align:center;"), "同块内合法声明应保留: " + rule);
        System.out.println("  [reject] expression(alert(1)) -> p=" + rule
                + " containsExpression=" + rule.contains("expression"));
    }

    private static void rejectLayoutAndPseudo() {
        String css = "p { position: fixed; z-index: 9999; width: 100px; "
                + "color: blue !important; } a:hover { color: red; }";
        Map<String, String> rules = BookParser.parseCss(css);
        String dump = dump(rules);
        check(rules.isEmpty(), "布局属性/!important/伪类应全部被拒: " + dump);
        check(!dump.contains("position"), "position 不应保留: " + dump);
        check(!dump.contains("z-index"), "z-index 不应保留: " + dump);
        check(!dump.contains("width"), "width 不应保留: " + dump);
        check(!dump.contains("important"), "!important 不应保留: " + dump);
        check(!dump.contains("a:hover"), "伪类选择器不应保留: " + dump);
        System.out.println("  [reject] position/z-index/width/!important/a:hover -> rules=" + dump);
    }

    private static void rejectOversize() {
        StringBuilder big = new StringBuilder("p { color: red; }");
        for (int i = 0; i < 600 * 1024; i++) big.append(' ');
        check(BookParser.parseCss(big.toString()).isEmpty(), "超过 512 KiB 的 CSS 应整体拒绝");

        StringBuilder longValue = new StringBuilder("p { color: ");
        for (int i = 0; i < 20000; i++) longValue.append('a');
        longValue.append("; }");
        check(BookParser.parseCss(longValue.toString()).isEmpty(), "超过 16 KiB 的声明块应被拒绝");
        System.out.println("  [reject] oversize: >512KiB CSS 与 >16KiB 声明块均被拒");
    }

    private static void existingPropertiesUnchanged() {
        Map<String, String> rules = BookParser.parseCss(
                "div { text-align: center; font-weight: bold; margin-top: 1em; line-height: 1.5; }");
        String rule = rules.get("div");
        check(rule != null, "既有属性规则应存在");
        check(rule.contains("text-align:center;"), "text-align 不受影响");
        check(rule.contains("font-weight:bold;"), "font-weight 不受影响");
        check(rule.contains("margin-top:1em;"), "margin-top 不受影响");
        check(rule.contains("line-height:1.5;"), "line-height 不受影响");
    }

    private static void immutableResult() {
        Map<String, String> rules = BookParser.parseCss("p { color: red; }");
        boolean threw = false;
        try { rules.put("x", "y"); } catch (UnsupportedOperationException expected) { threw = true; }
        check(threw, "parseCss 返回的 Map 应不可修改");
        check(BookParser.parseCss(null).isEmpty(), "null 输入应返回空 Map");
    }

    /**
     * L3 阻断 1：extraRules 必须独立于 applyBookCss 生效。旧路径三态逐一锁定：
     * html(0,true,empty) 与 html(0) 逐字节一致；html(0,false,empty) 无 style；
     * html(0,false,extraRules) 含用户声明且不带入书内 CSS / align / inline style。
     */
    private static void htmlThreeWayRender() {
        try { htmlThreeWayRenderBody(); }
        catch (Exception error) { throw new AssertionError("EPUB 夹具构造失败", error); }
    }

    private static void htmlThreeWayRenderBody() throws Exception {
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<String, String>();
        entries.put("mimetype", "application/epub+zip");
        entries.put("META-INF/container.xml", "<?xml version='1.0'?><container xmlns='urn:oasis:names:tc:opendocument:xmlns:container' version='1.0'><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>");
        entries.put("OPS/book.opf", "<?xml version='1.0'?><package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='id'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>CSS Gate</dc:title></metadata><manifest><item id='c1' href='c1.xhtml' media-type='application/xhtml+xml'/><item id='st' href='style.css' media-type='text/css'/></manifest><spine><itemref idref='c1'/></spine></package>");
        entries.put("OPS/style.css", "p {text-indent:2em} h1 {text-align:center;color:red}");
        entries.put("OPS/c1.xhtml", "<?xml version='1.0'?><html xmlns='http://www.w3.org/1999/xhtml'><head><title>x</title></head><body>"
                + "<h1>标题</h1><p>段落一</p><p id='m' align='center' style='color: blue;'>段落二</p></body></html>");
        java.io.File file = java.io.File.createTempFile("openleaf-css-gate-", ".epub");
        try {
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                    new java.io.FileOutputStream(file))) {
                for (java.util.Map.Entry<String, String> entry : entries.entrySet()) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            BookParser.IllustratedBook book = BookParser.openIllustrated(file);
            try {
                java.util.Map<String, String> extra = BookParser.parseCss("p { color: #123456; }");
                String offWithExtra = book.html(0, false, extra);
                String onEmpty = book.html(0, true, java.util.Collections.<String, String>emptyMap());
                String legacy = book.html(0);
                String offEmpty = book.html(0, false, java.util.Collections.<String, String>emptyMap());

                check(onEmpty.equals(legacy), "html(0,true,emptyMap) 应与 html(0) 逐字节一致");
                check(onEmpty.contains("text-indent:2em"), "旧路径仍应含书内 CSS");
                check(onEmpty.contains("text-align:center"), "旧路径仍应含书内 align 规则");
                check(onEmpty.contains("color:blue"), "旧路径仍应含内联 style: " + onEmpty);

                check(!offEmpty.contains(" style="), "html(0,false,emptyMap) 不应输出 style 属性: " + offEmpty);

                check(offWithExtra.contains("color:#123456"),
                        "html(0,false,extraRules) 应含用户声明: " + offWithExtra);
                check(!offWithExtra.contains("text-indent:2em"), "用户规则不应带入书内 CSS");
                check(!offWithExtra.contains("text-align:center"), "关闭原书样式时书内 align 不应生效");
                check(!offWithExtra.contains("color:blue"), "关闭原书样式时内联 style 不应生效");
                System.out.println("  [gate] false+extra=" + offWithExtra);
            } finally { book.close(); }
        } finally { if (!file.delete()) file.deleteOnExit(); }
    }

    private static String dump(Map<String, String> rules) {
        return rules == null ? "" : rules.toString();
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + "; got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void test(String name, ThrowingRunnable action) {
        try { action.run(); passed++; System.out.println("OK " + name); }
        catch (Throwable failure) { throw new AssertionError("FAILED: " + name, failure); }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run(); }
}
