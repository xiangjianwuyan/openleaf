package org.openleaf.reader;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.openleaf.reader.core.ContentContracts;
import org.openleaf.reader.core.EditBudget;
import org.openleaf.reader.core.TextEncoding;

/** EDIT-1 pure-JVM regressions for typed edit outcomes, byte budget and encoding anchors. */
public final class EditContractTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        typedOutcomeContract();
        byteLimitFixture();
        encodingRoundTripsAndAnchors();
        budgetMatchesSaveGate();
        surrogateAnchors();
        System.out.println("PASS: " + checks + " EDIT-1 edit contract checks");
    }

    /** The enum the editor now uses for failure/conflict paths is the one the reader handles. */
    private static void typedOutcomeContract() {
        check(ContentContracts.EditKind.valueOf("COVERED") == ContentContracts.EditKind.COVERED,
                "covered kind exists");
        check(ContentContracts.EditKind.valueOf("SAVED_COPY") == ContentContracts.EditKind.SAVED_COPY,
                "saved-copy kind exists");
        check(ContentContracts.EditKind.CONFLICT == ContentContracts.EditOutcome.conflict("外部修改").kind,
                "conflict outcome maps to CONFLICT");
        check(ContentContracts.EditKind.FAILED == ContentContracts.EditOutcome.failed("写入失败").kind,
                "failed outcome maps to FAILED");
        check(!ContentContracts.EditOutcome.conflict("外部修改").ok()
                && !ContentContracts.EditOutcome.failed("写入失败").ok(),
                "conflict and failure are not reported as success");
    }

    /** QA-1 boundary fixture: 128 KiB exactly passes, one byte more is rejected by both gates. */
    private static void byteLimitFixture() throws Exception {
        byte[] fixture = Files.readAllBytes(Paths.get("validation/fixtures/txt/boundary-128kib.txt"));
        check(fixture.length == LocalTextDocument.MAX_BYTES, "fixture is exactly 128 KiB");
        LocalTextDocument doc = LocalTextDocument.decode(fixture, TextEncoding.Mode.UTF_8);
        check(Arrays.equals(fixture, doc.encode(doc.text)), "128 KiB fixture round trips");
        check(EditBudget.check(doc.text, doc.charset, doc.newline, doc.bom(),
                LocalTextDocument.MAX_BYTES) == EditBudget.OK, "input budget accepts exactly 128 KiB");
        check(EditBudget.check(doc.text + "x", doc.charset, doc.newline, doc.bom(),
                LocalTextDocument.MAX_BYTES) == EditBudget.TOO_LARGE, "input budget rejects 128 KiB + 1 byte");
        rejects(() -> doc.encode(doc.text + "x"));
    }

    /** BOM/CRLF/UTF-16/GB18030/Big5 fixtures keep byte-exact round trips and safe anchors. */
    private static void encodingRoundTripsAndAnchors() throws Exception {
        roundTripAndAnchor("validation/fixtures/txt/three-chapters-bom-crlf.txt", TextEncoding.Mode.AUTO,
                "\r\n", "UTF-8 BOM + CRLF");
        roundTripAndAnchor("validation/fixtures/txt/utf16-bom.txt", TextEncoding.Mode.AUTO,
                "\n", "UTF-16 BOM");
        roundTripAndAnchor("validation/fixtures/txt/manual-gb18030.txt", TextEncoding.Mode.GB18030,
                "\n", "GB18030");
        roundTripAndAnchor("validation/fixtures/txt/manual-big5.txt", TextEncoding.Mode.BIG5,
                "\n", "Big5");
    }

    private static void roundTripAndAnchor(String path, TextEncoding.Mode mode, String newline, String label)
            throws Exception {
        byte[] raw = Files.readAllBytes(Paths.get(path));
        LocalTextDocument doc = LocalTextDocument.decode(raw, mode);
        check(doc.newline.equals(newline), label + " newline detected");
        check(Arrays.equals(raw, doc.encode(doc.text)), label + " exact round trip");
        int anchor = LocalTextDocument.cursorAt(doc.text, doc.text.length() / 2);
        String shifted = "【开头】" + doc.text;
        check(LocalTextDocument.remapOffset(doc.text, shifted, anchor) == anchor + 4,
                label + " anchor survives prefix insertion");
        check(LocalTextDocument.cursorAt(doc.text, doc.text.length()) == doc.text.length(),
                label + " EOF cursor stays valid");
    }

    /** The input filter and the save gate must accept/reject the exact same candidates. */
    private static void budgetMatchesSaveGate() throws Exception {
        byte[] utf8Bom = new byte[]{ (byte) 0xef, (byte) 0xbb, (byte) 0xbf };
        byte[] prefixedUtf8 = Arrays.copyOf(utf8Bom, utf8Bom.length + 5);
        System.arraycopy("seed\n".getBytes(StandardCharsets.UTF_8), 0, prefixedUtf8, 3, 5);
        byte[] prefixedUtf16 = new byte[]{ (byte) 0xfe, (byte) 0xff };
        byte[] utf16Body = "seed".getBytes(StandardCharsets.UTF_16BE);
        prefixedUtf16 = Arrays.copyOf(prefixedUtf16, 2 + utf16Body.length);
        System.arraycopy(utf16Body, 0, prefixedUtf16, 2, utf16Body.length);

        LocalTextDocument[] docs = {
                LocalTextDocument.decode("seed\r\n".getBytes(StandardCharsets.UTF_8), TextEncoding.Mode.UTF_8),
                LocalTextDocument.decode(prefixedUtf8, TextEncoding.Mode.UTF_8),
                LocalTextDocument.decode(prefixedUtf16),
                LocalTextDocument.decode("种子\n".getBytes(Charset.forName("GB18030")), TextEncoding.Mode.GB18030),
                LocalTextDocument.decode("種子\n".getBytes(Charset.forName("Big5")), TextEncoding.Mode.BIG5)
        };
        String[] samples = {"", "abc", "中文", "😀", "a😀b", "line1\nline2", "\tok",
                "甲".repeat(100), "😀".repeat(100)};
        for (LocalTextDocument doc : docs) {
            String label = doc.charset.name() + (doc.bom().length == 0 ? "" : "+BOM");
            for (String sample : samples) {
                int verdict = EditBudget.check(sample, doc.charset, doc.newline, doc.bom(),
                        LocalTextDocument.MAX_BYTES);
                boolean saveAccepted;
                try { doc.encode(sample); saveAccepted = true; }
                catch (IOException refused) { saveAccepted = false; }
                check((verdict == EditBudget.OK) == saveAccepted, label + " budget matches save gate");
            }

            int unit = EditBudget.encode("a", doc.charset, "\n", doc.bom(),
                    LocalTextDocument.MAX_BYTES).length - doc.bom().length;
            int units = (LocalTextDocument.MAX_BYTES - doc.bom().length) / unit;
            String exact = "a".repeat(units);
            String over = "a".repeat(units + 1);
            check(EditBudget.check(exact, doc.charset, doc.newline, doc.bom(),
                    LocalTextDocument.MAX_BYTES) == EditBudget.OK, label + " exact byte budget fits");
            check(EditBudget.check(over, doc.charset, doc.newline, doc.bom(),
                    LocalTextDocument.MAX_BYTES) == EditBudget.TOO_LARGE, label + " over budget reported");
            try { doc.encode(exact); }
            catch (IOException refused) { throw new AssertionError(label + " exact budget saved"); }
            rejects(() -> doc.encode(over));
        }
    }

    /** UTF-16 surrogate pairs must never be split by cursor placement or anchor remapping. */
    private static void surrogateAnchors() {
        String pair = "a😀b";
        check(LocalTextDocument.cursorAt(pair, 2) == 1, "cursor moves before surrogate pair");
        check(LocalTextDocument.cursorAt(pair, 3) == 3, "cursor after low surrogate stays");
        check(LocalTextDocument.cursorAt(pair, Long.MAX_VALUE) == 4, "large surrogate offset clamps");
        String inserted = "aX😀b";
        int shifted = LocalTextDocument.remapOffset(pair, inserted, 3);
        check(shifted == 4, "anchor behind inserted character follows the emoji");
        check(LocalTextDocument.remapOffset(pair, "ab", 0) == 0, "anchor before deleted emoji stays");
        check(!insideSurrogate(inserted, LocalTextDocument.remapOffset(pair, inserted, 2)),
                "remapped anchor never splits a surrogate pair");
    }

    private static boolean insideSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
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
