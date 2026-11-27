package org.openleaf.reader.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本索引身份（纯 JVM，无 Android 依赖）。
 *
 * <p>同一份正文用同一套有效编码与分章规则解码/分章，得到的 {@link #specId()} 必须相同；
 * 任一字段变化（尤其是 EDIT-1 改写正文后 {@code books.sha256} 变化）就会得到新的 specId，
 * 旧搜索结果据此判过期。spec 是派生值，不持久化。
 *
 * <p>规范化口径仿 {@link SourceRuleVersion#compute(Map)}：键排序 + 「长度:值;」前缀拼接 +
 * SHA-256，消除拼接歧义与字段顺序差异。版本指纹取文件的 SHA-256 而非位置修订号，
 * 因为 {@code PositionSync.newRevision()} 每念一句都会变，不是内容身份。
 */
public final class TextIndexSpec {
    /** 作品 ID。 */
    public final String bookId;
    /** 正文 SHA-256；null 表示旧书「未提供」，参与规范化时按空串处理，绝不抛 NPE。 */
    public final String contentSha;
    /** 有效编码档位（0=AUTO .. 4=Big5）。 */
    public final int encoding;
    /** 有效分章规则档位（0=保守 .. 3=自定义）。 */
    public final int ruleMode;
    /** 自定义正则文本，永不为 null。 */
    public final String ruleCustom;

    private TextIndexSpec(String bookId, String contentSha, int encoding, int ruleMode, String ruleCustom) {
        this.bookId = bookId == null ? "" : bookId;
        this.contentSha = contentSha;
        this.encoding = encoding < 0 || encoding > 4 ? 0 : encoding;
        this.ruleMode = ruleMode < 0 || ruleMode > 3 ? 0 : ruleMode;
        this.ruleCustom = ruleCustom == null ? "" : ruleCustom;
    }

    /** 非法 encoding/ruleMode clamp 到 0，ruleCustom null 归一为 ""。 */
    public static TextIndexSpec of(String bookId, String contentSha, int encoding, int ruleMode, String ruleCustom) {
        return new TextIndexSpec(bookId, contentSha, encoding, ruleMode, ruleCustom);
    }

    /** 规范化字节串的 SHA-256（小写 hex）。 */
    public String fingerprint() {
        return ContentContracts.hashHex(canonical(this.bookId, this.contentSha,
                this.encoding, this.ruleMode, this.ruleCustom).getBytes(StandardCharsets.UTF_8));
    }

    /** 单一身份 id；与 {@link #fingerprint()} 同值，供 intent extra / 过期比对使用。 */
    public String specId() { return fingerprint(); }

    /** 旧书是否没有内容 SHA（"未提供"状态，按异于任何空串 SHA 处理时不抛异常）。 */
    public boolean hasContentSha() { return contentSha != null && !contentSha.isEmpty(); }

    /** 字段化规范化：与 {@link #canonical(Map)} 同一口径，便于按名构造后比对。 */
    public static String canonical(String bookId, String contentSha, int encoding, int ruleMode, String ruleCustom) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("bookId", bookId);
        fields.put("contentSha", contentSha);
        fields.put("encoding", Integer.toString(encoding));
        fields.put("ruleMode", Integer.toString(ruleMode));
        fields.put("ruleCustom", ruleCustom);
        return canonical(fields);
    }

    /**
     * 键排序后逐项写「长度:值;」，顺序无关且能区分 "a|bc" 与 "ab|c"。
     * 传入 map 的插入顺序不影响结果；null 值按空串处理。
     */
    public static String canonical(Map<String, String> fields) {
        List<String> keys = new ArrayList<String>();
        if (fields != null) keys.addAll(fields.keySet());
        Collections.sort(keys);
        StringBuilder builder = new StringBuilder();
        for (String key : keys) {
            appendPart(builder, key);
            String value = fields.get(key);
            appendPart(builder, value == null ? "" : value);
        }
        return builder.toString();
    }

    private static void appendPart(StringBuilder builder, String value) {
        builder.append(value.length()).append(':').append(value).append(';');
    }

    /** null 安全；bookId 不同或 specId 不同即不匹配。 */
    public boolean matches(TextIndexSpec other) {
        if (other == null) return false;
        if (!this.bookId.equals(other.bookId)) return false;
        return specId().equals(other.specId());
    }

    /** 当前 spec 缺失或与本 spec 不符即过期。 */
    public boolean stale(TextIndexSpec current) {
        return current == null || !matches(current);
    }

    @Override public boolean equals(Object other) {
        return other instanceof TextIndexSpec && matches((TextIndexSpec) other);
    }

    @Override public int hashCode() { return specId().hashCode(); }

    @Override public String toString() {
        return "TextIndexSpec{bookId=" + bookId + ", sha=" + (hasContentSha() ? contentSha : "<未提供>")
                + ", encoding=" + encoding + ", ruleMode=" + ruleMode + ", specId=" + specId() + "}";
    }
}
