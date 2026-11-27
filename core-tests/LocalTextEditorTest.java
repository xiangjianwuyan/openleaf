package org.openleaf.reader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.openleaf.reader.core.TextEncoding;

/** Standalone JVM regressions for editor encoding and reading anchor mapping. */
public final class LocalTextEditorTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        byte[] crlfBody = "第一章\r\n第二行\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[3 + crlfBody.length];
        withBom[0] = (byte) 0xef; withBom[1] = (byte) 0xbb; withBom[2] = (byte) 0xbf;
        System.arraycopy(crlfBody, 0, withBom, 3, crlfBody.length);
        LocalTextDocument utf8 = LocalTextDocument.decode(withBom, TextEncoding.Mode.UTF_8);
        check(utf8.text.equals("第一章\n第二行\n"), "CRLF normalized to reader offset space");
        check(Arrays.equals(withBom, utf8.encode(utf8.text)), "UTF-8 BOM + CRLF exact round trip");
        check(Arrays.equals(withBom, LocalTextDocument.decode(withBom).encode(utf8.text)), "auto BOM parity");

        byte[] utf16Raw = "清晨\n日光".getBytes(StandardCharsets.UTF_16LE);
        LocalTextDocument forced16 = LocalTextDocument.decode(utf16Raw, TextEncoding.Mode.UTF_16);
        check(forced16.text.equals("清晨\n日光"), "forced UTF-16LE without BOM");
        check(Arrays.equals(utf16Raw, forced16.encode(forced16.text)), "no invented BOM");
        rejects(() -> LocalTextDocument.decode(withBom, TextEncoding.Mode.BIG5));

        Charset big5 = Charset.forName("Big5");
        byte[] big5Raw = "繁體文字\r行".getBytes(big5);
        LocalTextDocument forcedBig5 = LocalTextDocument.decode(big5Raw, TextEncoding.Mode.BIG5);
        check(forcedBig5.text.equals("繁體文字\n行"), "Big5 override, CR normalized");
        check(Arrays.equals(big5Raw, forcedBig5.encode(forcedBig5.text)), "Big5 CR exact round trip");
        rejects(() -> forcedBig5.encode("還有 😀"));
        rejects(() -> LocalTextDocument.readBytes(new ByteArrayInputStream(new byte[LocalTextDocument.MAX_BYTES + 1])));
        rejects(() -> utf8.encode("甲".repeat(LocalTextDocument.MAX_BYTES / 2)));
        rejects(() -> utf8.encode("\ud800"));

        check(LocalTextDocument.cursorAt("a😀b", 2) == 1, "cursor cannot split surrogate");
        check(LocalTextDocument.cursorAt("a😀b", Long.MAX_VALUE) == 4, "large offsets clamp safely");
        check(LocalTextDocument.remapOffset("abc", "XYabc", 1) == 3, "insertion before current text");
        check(LocalTextDocument.remapOffset("XYabc", "abc", 3) == 1, "deletion before current text");
        check(LocalTextDocument.remapOffset("abc", "abcXY", 1) == 1, "insertion after current text");
        check(LocalTextDocument.remapOffset("abc", "x", 3) == 1, "EOF moves with final edit");
        String prose = "此处是仅出现一次的稳定正文锚点，这段内容不因首尾修改而移动到错误的行。";
        String before = "原开头---".repeat(12) + prose + "---原结尾".repeat(12);
        String after = "改短开头---".repeat(7) + prose + "---另一个结尾".repeat(9);
        int old = before.indexOf(prose) + 12;
        check(LocalTextDocument.remapOffset(before, after, old) == after.indexOf(prose) + 12,
                "unique context preserves anchor across separated edits");
        System.out.println("PASS: " + checks + " local text editor checks");
    }

    interface IOAction { void run() throws IOException; }
    private static void rejects(IOAction action) throws Exception {
        try { action.run(); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError("expected safe rejection");
    }
    private static void check(boolean ok, String title) {
        if (!ok) throw new AssertionError(title);
        checks++;
    }
}
