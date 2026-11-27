package org.openleaf.reader.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

/**
 * Pure-JVM edit budget shared by the editor input filter and the save gate.
 *
 * <p>The editor previously limited UTF-16 characters while the save layer limited encoded bytes;
 * those two measures disagree for UTF-16, GB18030, Big5, BOMs and surrogate pairs. Both sides now
 * call this class so the UI never accepts text that {@code encode()} will later refuse.</p>
 */
public final class EditBudget {
    /** The candidate can be saved as-is. */
    public static final int OK = 0;
    /** The document charset cannot represent the candidate (same rejection as the save gate). */
    public static final int UNENCODABLE = 1;
    /** The candidate fits the charset but needs more bytes than the allowed limit. */
    public static final int TOO_LARGE = 2;

    private EditBudget() { }

    /**
     * Encodes exactly as the save path does: LF display newline, document newline on output,
     * original BOM, strict charset mapping and the supplied byte limit.
     *
     * @param text display text using LF newlines
     * @param charset document charset
     * @param newline newline sequence preserved by the document
     * @param bom original BOM bytes, or null
     * @param limit maximum total byte count including the BOM
     * @return the bytes that would be written
     * @throws IOException if the text has unsupported control characters, cannot be represented
     *                     in the charset, or exceeds {@code limit}
     */
    public static byte[] encode(String text, Charset charset, String newline, byte[] bom, int limit)
            throws IOException {
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
        } catch (CharacterCodingException invalid) {
            throw new IOException("编辑内容不能用原编码保存", invalid);
        }
        byte[] prefix = bom == null ? new byte[0] : bom;
        if (prefix.length + encoded.remaining() > limit)
            throw new IOException("保存后的文本超过 " + (limit / 1024) + " KiB");
        byte[] result = Arrays.copyOf(prefix, prefix.length + encoded.remaining());
        encoded.get(result, prefix.length, encoded.remaining());
        return result;
    }

    /**
     * Classifies a candidate for the input filter without duplicating the encoding rules.
     * Encoding once with an unlimited limit keeps the common case to a single pass.
     */
    public static int check(String text, Charset charset, String newline, byte[] bom, int limit) {
        try {
            return encode(text, charset, newline, bom, Integer.MAX_VALUE).length > limit
                    ? TOO_LARGE : OK;
        } catch (IOException unencodable) {
            return UNENCODABLE;
        }
    }
}
