package org.openleaf.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.openleaf.reader.core.TextEncoding;

/** Size-bounded text for the SAF editor. Display newlines use LF; output keeps uniform source newlines. */
final class LocalTextDocument {
    static final int MAX_BYTES = 128 * 1024;
    private static final byte[] NO_BOM = new byte[0];
    final String text;
    final Charset charset;
    private final byte[] bom;
    final String newline;
    final boolean mixedNewlines;
    final byte[] fingerprint;

    private LocalTextDocument(String text, Charset charset, byte[] bom, String newline, boolean mixed, byte[] fingerprint) {
        this.text = text; this.charset = charset; this.bom = bom;
        this.newline = newline; this.mixedNewlines = mixed; this.fingerprint = fingerprint;
    }

    static byte[] readBytes(InputStream in) throws IOException {
        if (in == null) throw new IOException("无法打开文件");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer, 0, Math.min(buffer.length, MAX_BYTES - out.size() + 1))) >= 0) {
            if (count == 0) {
                int b = in.read();
                if (b < 0) break;
                buffer[0] = (byte) b;
                count = 1;
            }
            if (out.size() + count > MAX_BYTES) throw new IOException("文本编辑上限为 128 KiB");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    static LocalTextDocument decode(byte[] bytes) throws IOException {
        return decode(bytes, TextEncoding.Mode.AUTO);
    }

    static LocalTextDocument decode(byte[] bytes, TextEncoding.Mode mode) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("文本编辑上限为 128 KiB");
        Charset charset = StandardCharsets.UTF_8;
        int prefix = 0;
        if (starts(bytes, 0xef, 0xbb, 0xbf)) prefix = 3;
        else if (starts(bytes, 0xff, 0xfe)) { prefix = 2; charset = StandardCharsets.UTF_16LE; }
        else if (starts(bytes, 0xfe, 0xff)) { prefix = 2; charset = StandardCharsets.UTF_16BE; }
        Charset forced = TextEncoding.charsetOf(mode);
        if (mode == TextEncoding.Mode.UTF_16) forced = TextEncoding.resolveUtf16(bytes);
        if (forced != null) {
            if (prefix != 0 && !charset.equals(forced))
                throw new IOException("阅读编码与文件的 BOM 不一致，请先更正阅读编码");
            charset = forced;
        }
        String raw;
        try {
            raw = decodeStrict(bytes, prefix, charset);
        } catch (CharacterCodingException invalid) {
            if (forced != null || prefix != 0) throw new IOException("文件与当前文本编码不一致", invalid);
            charset = Charset.forName("GB18030");
            try { raw = decodeStrict(bytes, 0, charset); }
            catch (CharacterCodingException unsupported) { throw new IOException("不支持此文本编码", unsupported); }
        }
        int styles = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r') {
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') { styles |= 1; i++; }
                else styles |= 2;
            } else if (c == '\n') styles |= 4;
            else if (Character.isISOControl(c) && c != '\t') throw new IOException("文件包含控制字符，不是受支持的纯文本");
        }
        return new LocalTextDocument(raw.replace("\r\n", "\n").replace('\r', '\n'), charset,
                prefix == 0 ? NO_BOM : Arrays.copyOf(bytes, prefix), styles == 1 ? "\r\n" : styles == 2 ? "\r" : "\n",
                Integer.bitCount(styles) > 1, hash(bytes));
    }

    /** Source index uses normalized LF and UTF-16 character units, just like the editor. */
    static int cursorAt(String text, long offset) {
        int pos = (int) Math.max(0L, Math.min(offset, text.length()));
        if (pos > 0 && pos < text.length() && Character.isHighSurrogate(text.charAt(pos - 1))
                && Character.isLowSurrogate(text.charAt(pos))) pos--;
        return pos;
    }

    /** Keep an unchanged reading anchor; inside a replacement, retain the nearest relative character. */
    static int remapOffset(String before, String after, long original) {
        int old = cursorAt(before, original);
        int shared = Math.min(before.length(), after.length());
        int prefix = 0;
        while (prefix < shared && before.charAt(prefix) == after.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < shared - prefix && before.charAt(before.length() - 1 - suffix)
                == after.charAt(after.length() - 1 - suffix)) suffix++;
        if (old < prefix || before.equals(after)) return cursorAt(after, old);
        int oldEnd = before.length() - suffix, newEnd = after.length() - suffix;
        if (old >= oldEnd) return cursorAt(after, newEnd + old - oldEnd);
        for (int radius : new int[]{24, 12, 6}) {
            int contextStart = Math.max(0, old - radius), contextEnd = Math.min(before.length(), old + radius);
            String context = before.substring(contextStart, contextEnd);
            int match = after.indexOf(context);
            if (!context.isEmpty() && match >= 0 && after.indexOf(context, match + 1) < 0) {
                return cursorAt(after, match + old - contextStart);
            }
        }
        return cursorAt(after, Math.min(newEnd, old));
    }

    byte[] encode(String text) throws IOException {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t')
                throw new IOException("编辑内容包含不支持的控制字符");
        }
        if (!"\n".equals(newline)) normalized = normalized.replace("\n", newline);
        ByteBuffer encoded;
        try {
            encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(normalized));
        } catch (CharacterCodingException invalid) { throw new IOException("编辑内容不能用原编码保存", invalid); }
        if (bom.length + encoded.remaining() > MAX_BYTES) throw new IOException("保存后的文本超过 128 KiB");
        byte[] result = Arrays.copyOf(bom, bom.length + encoded.remaining());
        encoded.get(result, bom.length, encoded.remaining());
        return result;
    }

    String description() {
        return charset.name() + (bom.length == 0 ? "" : "（带 BOM）")
                + (mixedNewlines ? " · 混合换行在保存编辑时统一为 LF" : "\n保留原有换行方式");
    }

    static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String decodeStrict(byte[] bytes, int start, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, start, bytes.length - start)).toString();
    }

    private static boolean starts(byte[] bytes, int... values) {
        if (bytes.length < values.length) return false;
        for (int i = 0; i < values.length; i++) if ((bytes[i] & 0xff) != values[i]) return false;
        return true;
    }
}
