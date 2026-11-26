package org.openleaf.reader.core;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 封面/元数据推断（纯 JVM，无 android.* 依赖，0.13.0 缺口 8）。
 *
 * 只产出「候选」，不写库：调用方（BookDetailActivity）把候选填进 draft，
 * 用户点保存才由既有 original/draft/baseline 机制落库（设计 §8.2 / Q9）。
 */
public final class CoverInfer {
    private CoverInfer() { }

    private static final int MAX_TEXT = 80;
    private static final int MAX_PDF_VALUE = 256;

    private static final Pattern[] AUTHOR_TAILS = {
            Pattern.compile("\\s*\\[([^\\[\\]]{1,40})\\]\\s*$"),
            Pattern.compile("\\s*\\u3010([^\\u3010\\u3011]{1,40})\\u3011\\s*$"),
            Pattern.compile("\\s*\\(([^()]{1,40})\\)\\s*$"),
            Pattern.compile("\\s*[-—－]\\s*([^\\-—－]{1,40})\\s*$")
    };

    /** 文件名推断结果；title / author 均可能为 null（未命中）。 */
    public static final class Meta {
        public final String title;
        public final String author;
        public Meta(String title, String author) { this.title = title; this.author = author; }
    }

    /**
     * 去扩展名、全角空格归一，再剥掉末尾的 [作者] / 【作者】 / (作者) / -作者。
     * 剥不掉或剥完为空时对应字段返回 null，不抛异常。
     */
    public static Meta fromFileName(String name) {
        if (name == null || name.isEmpty()) return new Meta(null, null);
        String s = name.trim().replace('\u3000', ' ');
        int dot = s.lastIndexOf('.');
        if (dot > 0 && dot < s.length() - 1) s = s.substring(0, dot);
        s = s.trim();
        String author = null;
        for (Pattern pattern : AUTHOR_TAILS) {
            Matcher matcher = pattern.matcher(s);
            if (!matcher.find()) continue;
            for (int group = 1; group <= matcher.groupCount(); group++) {
                String found = matcher.group(group);
                if (found == null || found.trim().isEmpty()) continue;
                author = found.trim();
                s = s.substring(0, matcher.start()).trim();
                break;
            }
            if (author != null) break;
        }
        return new Meta(s.isEmpty() ? null : s, author);
    }

    /**
     * 取首个非空、非目录行作为封面文字候选，最多 80 字；无命中返回 null。
     * BOM 与全角空格会先归一化，超长时截断并加省略号。
     */
    public static String fromFirstLine(String text) {
        if (text == null || text.isEmpty()) return null;
        for (String raw : text.split("\\r\\n|\\n|\\r", -1)) {
            String line = raw.trim().replace('\u3000', ' ').trim();
            if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1).trim();
            if (line.isEmpty() || isTocLine(line)) continue;
            return bounded(line, MAX_TEXT);
        }
        return null;
    }

    /**
     * 在文件首尾字节里扫 PDF 字典键（如 "/Title" / "/Author"）的值。
     * 支持 PDF 字面串 (…)（含转义）与十六进制串 &lt;FEFF…&gt;（UTF-16BE/LE BOM）。
     * 未命中返回 null；不解析 xref / 对象流，不引入 PDF 依赖（设计 §8.2）。
     */
    public static String pdfInfoValue(byte[] headTail, String key) {
        if (headTail == null || headTail.length == 0 || key == null || key.isEmpty()) return null;
        String name = key.startsWith("/") ? key : "/" + key;
        byte[] needle = name.getBytes(StandardCharsets.US_ASCII);
        int from = 0;
        while (from <= headTail.length - needle.length) {
            int at = indexOf(headTail, needle, from);
            if (at < 0) return null;
            int p = at + needle.length;
            while (p < headTail.length && isWhite(headTail[p])) p++;
            if (p < headTail.length && headTail[p] == '(') {
                String literal = readLiteral(headTail, p);
                if (literal != null && !literal.trim().isEmpty()) return bounded(literal.trim(), MAX_PDF_VALUE);
            } else if (p < headTail.length && headTail[p] == '<') {
                String decoded = decodePdfHex(readHex(headTail, p));
                if (decoded != null && !decoded.trim().isEmpty()) return bounded(decoded.trim(), MAX_PDF_VALUE);
            }
            from = at + needle.length;
        }
        return null;
    }

    /** JPEG / PNG / GIF / WebP 签名判定，供漫画首页封面复用（与 BookParser.imageSignature 同口径）。 */
    public static boolean usableImageSignature(byte[] head) {
        if (head == null) return false;
        if (head.length >= 8 && prefix(head, 137, 80, 78, 71, 13, 10, 26, 10)) return true;
        if (head.length >= 3 && prefix(head, 255, 216, 255)) return true;
        if (head.length >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F'
                && head[3] == '8' && (head[4] == '7' || head[4] == '9') && head[5] == 'a') return true;
        if (head.length >= 12 && "RIFF".equals(new String(head, 0, 4, StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(head, 8, 4, StandardCharsets.US_ASCII))) return true;
        return false;
    }

    // ---------- 内部工具 ----------

    private static boolean isTocLine(String line) {
        String compact = line.replaceAll("[\\s\\u3000]", "");
        if (compact.equalsIgnoreCase("目录") || compact.equalsIgnoreCase("目次")
                || compact.equalsIgnoreCase("contents") || compact.equalsIgnoreCase("tableofcontents")) return true;
        // 「第一章 绪论 ...... 12」这类点线加页码的目录行
        return compact.matches(".{0,40}[.．…]{3,}[0-9]{1,4}")
                || compact.matches("[0-9]{1,4}[.．]{1,3}.{0,40}");
    }

    private static String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "\u2026";
    }

    private static boolean isWhite(byte b) {
        return b == 0 || b == 9 || b == 10 || b == 12 || b == 13 || b == 32;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static boolean prefix(byte[] bytes, int... expected) {
        for (int i = 0; i < expected.length; i++) if ((bytes[i] & 0xff) != expected[i]) return false;
        return true;
    }

    /** 读取 PDF 字面串，处理反斜杠转义与 \n \r \t \b \f 及八进制转义。 */
    private static String readLiteral(byte[] bytes, int open) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = open + 1; i < bytes.length && out.size() < MAX_PDF_VALUE * 4; i++) {
            byte b = bytes[i];
            if (b == ')') return decodePdfBytes(out.toByteArray());
            if (b == '\\' && i + 1 < bytes.length) {
                byte next = bytes[++i];
                if (next == 'n') out.write('\n');
                else if (next == 'r') out.write('\r');
                else if (next == 't') out.write('\t');
                else if (next == 'b') out.write('\b');
                else if (next == 'f') out.write('\f');
                else if (next >= '0' && next <= '7') {
                    int octal = next - '0';
                    for (int k = 0; k < 2 && i + 1 < bytes.length && bytes[i + 1] >= '0' && bytes[i + 1] <= '7'; k++)
                        octal = octal * 8 + (bytes[++i] - '0');
                    out.write(octal);
                } else if (next == '\r' || next == '\n') {
                    if (next == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') i++;
                } else out.write(next & 0xff);
            } else out.write(b & 0xff);
        }
        return null; // 未闭合，放弃该候选
    }

    private static String readHex(byte[] bytes, int open) {
        StringBuilder hex = new StringBuilder();
        for (int i = open + 1; i < bytes.length && hex.length() <= MAX_PDF_VALUE * 4; i++) {
            byte b = bytes[i];
            if (b == '>') break;
            if (isWhite(b)) continue;
            if ((b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F')) hex.append((char) b);
            else return null; // 非法十六进制字符
        }
        return hex.length() >= 4 && hex.length() % 2 == 0 ? hex.toString() : null;
    }

    private static String decodePdfHex(String hex) {
        if (hex == null) return null;
        byte[] raw = new byte[hex.length() / 2];
        for (int i = 0; i < raw.length; i++)
            raw[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                    | Character.digit(hex.charAt(i * 2 + 1), 16));
        return decodePdfBytes(raw);
    }

    /** 按字节级 BOM 选择 UTF-16BE / UTF-16LE；无 BOM 时严格 UTF-8，失败退回 ISO-8859-1。 */
    private static String decodePdfBytes(byte[] raw) {
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xFE && (raw[1] & 0xff) == 0xFF)
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE);
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xFF && (raw[1] & 0xff) == 0xFE)
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (java.nio.charset.CharacterCodingException notUtf8) {
            return new String(raw, StandardCharsets.ISO_8859_1);
        }
    }
}
