package org.openleaf.reader.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 文本编码手动兜底：模式枚举、prefs 索引→Charset、UTF-16 BOM 试探与严格解码。
 *
 * <p>纯 JVM 类，无 Android 依赖。AUTO 返回 null 表示"走 TextChapterIndexer 原自动检测链"，
 * 保证 0.27.0 行为逐字不变。Big5 / GB18030 都是 JDK 标准字符集，零新依赖。</p>
 */
public final class TextEncoding {
    /** 5 档：自动 / UTF-8 / GB18030 / UTF-16 / Big5，索引与 prefs 存储值一致。 */
    public enum Mode { AUTO, UTF_8, GB18030, UTF_16, BIG5 }

    private TextEncoding() { }

    /** prefs 索引→Mode；非法值 clamp 到 AUTO。 */
    public static Mode fromIndex(int index) {
        Mode[] modes = Mode.values();
        return modes[index >= 0 && index < modes.length ? index : Mode.AUTO.ordinal()];
    }

    /**
     * Mode→Charset。AUTO 返回 null（调用方据此跳过 detectCharset）；
     * UTF-16 默认 UTF_16LE，UI 应再用 resolveUtf16 按文件实际 BOM 校正 LE/BE。
     */
    public static Charset charsetOf(Mode mode) {
        if (mode == null) return null;
        switch (mode) {
            case UTF_8: return StandardCharsets.UTF_8;
            case GB18030: return Charset.forName("GB18030");
            case UTF_16: return StandardCharsets.UTF_16LE;
            case BIG5: return Charset.forName("Big5");
            case AUTO:
            default: return null;
        }
    }

    /**
     * UTF-16 字节序试探：FF FE → LE，FE FF → BE；无 BOM 先按 LE（启发式，仍乱码可让用户另存为带 BOM）。
     */
    public static Charset resolveUtf16(byte[] prefix) {
        if (prefix != null && prefix.length >= 2) {
            int first = prefix[0] & 0xFF;
            int second = prefix[1] & 0xFF;
            if (first == 0xFF && second == 0xFE) return StandardCharsets.UTF_16LE;
            if (first == 0xFE && second == 0xFF) return StandardCharsets.UTF_16BE;
        }
        return StandardCharsets.UTF_16LE;
    }

    /**
     * 严格解码：malformed / unmappable 一律 REPORT，供 core 单测验证四编码命中预期文字。
     * offset 会 clamp 到 [0, bytes.length]；charset 为 null 时防御性回退 UTF-8。
     */
    public static String decode(byte[] bytes, int offset, Charset charset) throws CharacterCodingException {
        if (bytes == null) return "";
        int start = Math.max(0, Math.min(offset, bytes.length));
        Charset effective = charset == null ? StandardCharsets.UTF_8 : charset;
        return effective.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.length - start))
                .toString();
    }
}
