package org.openleaf.reader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Pure JVM test: no Android stubs needed. PluginManifest itself is validated using the device UI. */
public final class PluginTextReportTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        PluginTextReport.Report mixed = PluginTextReport.analyze("# 开头\r\n\t\r\nIntro 😀\r  Chapter 2\n", List.of("# ", "Chapter "));
        check(mixed.lines() == 4 && mixed.blankLines() == 1 && mixed.headings() == 2 && mixed.codePoints() == 23, "CRLF/CR/LF/blank/code points");
        check(mixed.examples().get(0).lineNumber() == 1 && mixed.examples().get(1).lineNumber() == 4, "source line numbers");
        check(PluginTextReport.analyze("\n\n", List.of("A")).blankLines() == 2, "trailing newline does not create phantom extra line");
        check(PluginTextReport.analyze("", List.of("# ")).lines() == 0, "empty doc");
        check(PluginTextReport.analyze("Chapter 2\nchapter 3\n.", List.of("Chapter ", "Chap", ".")).headings() == 2, "literal case-sensitive and no double-count");
        PluginTextReport.Report capped = PluginTextReport.analyze("# a\n".repeat(25), List.of("# "));
        check(capped.headings() == 25 && capped.examples().size() == 20, "examples capped without reducing summary count");
        PluginTextReport.Report safe = PluginTextReport.analyze("# " + "a".repeat(77) + "😀\u202e!", List.of("# "));
        check(safe.examples().get(0).text().equals("# " + "a".repeat(77) + "…"), "safe truncation on surrogate boundary");
        check(PluginTextReport.analyze("# abc\u202edef", List.of("# ")).examples().get(0).text().equals("# abcdef"), "strip bidi control chars from previews");
        check("{}".equals(PluginTextReport.readUtf8(new ByteArrayInputStream(new byte[]{(byte)0xef, (byte)0xbb, (byte)0xbf, '{', '}'}), 5)), "UTF-8 BOM and exact size");
        check("text".equals(PluginTextReport.readUtf8(new ByteArrayInputStream("text".getBytes(StandardCharsets.UTF_8)), 4)), "exact byte limit");
        rejects(() -> PluginTextReport.readUtf8(new ByteArrayInputStream(new byte[5]), 4), "byte limit +1");
        rejects(() -> PluginTextReport.readUtf8(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28}), 10), "malformed UTF-8");
        rejects(() -> PluginTextReport.analyze("a\u0000b", Arrays.asList("a")), "binary control characters");
        Thread.currentThread().interrupt();
        try { rejects(() -> PluginTextReport.analyze("test", List.of("t")), "cooperative cancellation"); }
        finally { Thread.interrupted(); }
        System.out.println("PASS: " + passed + " OpenLeaf plugin utility checks");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
    }
    private interface Throwing { void run() throws IOException; }
    private static void rejects(Throwing action, String name) throws IOException {
        try { action.run(); }
        catch (IOException expected) { check(true, name); return; }
        throw new AssertionError(name);
    }
}
