package org.openleaf.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The only v1 plugin capability. Input and output are both plain text. */
final class PluginTextReport {
    static final int MAX_TEXT_BYTES = 1024 * 1024;
    static final int MAX_EXAMPLES = 20;
    private PluginTextReport() { }

    static String readUtf8(InputStream stream, int limit) throws IOException {
        if (stream == null) throw new IOException("无法打开所选文件");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] bytes = new byte[8192];
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("操作已取消");
            int available = limit - buffer.size();
            int n = stream.read(bytes, 0, Math.min(available + 1, bytes.length));
            if (n < 0) break;
            if (n == 0) throw new IOException("所选文件读取中断");
            if (n > available) throw new IOException("文件超出大小限制（" + (limit / 1024) + " KiB）");
            buffer.write(bytes, 0, n);
        }
        byte[] raw = buffer.toByteArray();
        int offset = raw.length >= 3 && raw[0] == (byte) 0xef && raw[1] == (byte) 0xbb && raw[2] == (byte) 0xbf ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, offset, raw.length - offset)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("仅支持有效的 UTF-8 文件", e);
        }
    }

    record Finding(int lineNumber, String text) { }
    record Report(int lines, int blankLines, int codePoints, int headings, List<Finding> examples) { }

    static Report analyze(String text, List<String> prefixes) throws IOException {
        int lines = 0, blank = 0, headings = 0, points = 0;
        ArrayList<Finding> examples = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("操作已取消");
            int end = start;
            while (end < text.length()) {
                char ch = text.charAt(end);
                if (ch == '\r' || ch == '\n') break;
                if (ch < 32 && ch != '\t') throw new IOException("文件含二进制控制字符，无法作为 TXT / MD 分析");
                end++;
            }
            lines++;
            points += text.codePointCount(start, end);
            int content = start;
            while (content < end && Character.isWhitespace(text.charAt(content))) content++;
            if (content == end) blank++;
            else for (String prefix : prefixes) {
                if (prefix.length() <= end - content && text.startsWith(prefix, content)) {
                    headings++;
                    if (examples.size() < MAX_EXAMPLES) {
                        int stop = Math.min(end, content + 80);
                        if (stop < end && Character.isHighSurrogate(text.charAt(stop - 1))) stop--;
                        String safe = displayText(text.substring(content, stop));
                        examples.add(new Finding(lines, safe + (stop < end ? "…" : "")));
                    }
                    break;
                }
            }
            start = end + 1;
            if (end + 1 < text.length() && text.charAt(end) == '\r' && text.charAt(end + 1) == '\n') start++;
        }
        return new Report(lines, blank, points, headings, Collections.unmodifiableList(examples));
    }

    private static String displayText(String text) {
        StringBuilder output = new StringBuilder(text.length());
        text.codePoints().filter(cp -> Character.getType(cp) != Character.FORMAT
                && !Character.isISOControl(cp) && cp != 0x2028 && cp != 0x2029).forEach(output::appendCodePoint);
        return output.toString();
    }
}
