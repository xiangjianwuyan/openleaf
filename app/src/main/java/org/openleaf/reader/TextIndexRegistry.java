package org.openleaf.reader;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.openleaf.reader.core.TextIndexSpec;

/**
 * 文本索引身份注册表（Android 集成层）：只做派生与序列化，不新建任何 prefs / DB 列。
 *
 * <p>真源是阅读页既有的一组 {@code reader} prefs（按书覆盖标记、规则档位/自定义正则、编码）
 * 与 {@code books.sha256}。spec 是派生值，不持久化；旧书无覆盖时用全局规则 + AUTO，
 * 这正是阅读页 {@code currentRule()/currentEncodingIndex()} 的权威默认。
 * 声称携带 spec 但字段缺失或 specId 不符，一律 {@code claimedFrom()} 返回 null → 判过期。
 */
public final class TextIndexRegistry {
    private static final String PREFS = "reader";

    /** spec 的 intent / bundle extra 键，集中定义于此。 */
    public static final String EXTRA_SPEC_BOOK_ID = "spec_bookId";
    public static final String EXTRA_SPEC_SHA = "spec_sha";
    public static final String EXTRA_SPEC_ENCODING = "spec_encoding";
    public static final String EXTRA_SPEC_RULE_MODE = "spec_ruleMode";
    public static final String EXTRA_SPEC_RULE_CUSTOM = "spec_ruleCustom";
    public static final String EXTRA_SPEC_ID = "spec_id";
    /** 搜索结果回传的作品级 UTF-16 偏移。 */
    public static final String EXTRA_SOURCE_START = "sourceStart";
    public static final String EXTRA_SOURCE_END = "sourceEnd";

    private TextIndexRegistry() { }

    /** 从 reader prefs + books.sha256 派生当前 spec；sha 为 null 表示旧书「未提供」。 */
    public static TextIndexSpec current(Context context, String bookId) {
        if (context == null || bookId == null || bookId.isEmpty()) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int encoding = prefs.getInt("text_encoding_" + bookId, 0);
        if (encoding < 0 || encoding > 4) encoding = 0;
        boolean hasOverride = prefs.contains("chapter_rule_override_" + bookId);
        String modeKey = hasOverride ? "chapter_rule_mode_" + bookId : "chapter_rule_mode";
        String customKey = hasOverride ? "chapter_rule_custom_" + bookId : "chapter_rule_custom";
        int ruleMode = prefs.getInt(modeKey, 0);
        if (ruleMode < 0 || ruleMode > 3) ruleMode = 0;
        String ruleCustom = prefs.getString(customKey, "");
        if (ruleCustom == null) ruleCustom = "";
        return TextIndexSpec.of(bookId, storedShaOrNull(context, bookId), encoding, ruleMode, ruleCustom);
    }

    /** 复用既有 BookStore.storedHash（package-private，抛 IOException）；异常/空列视为「未提供」。 */
    private static String storedShaOrNull(Context context, String bookId) {
        try {
            return new BookStore(context).storedHash(bookId);
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** 用字符串 extra 序列化 spec，免 Parcelable、免新类。 */
    public static void putInto(Intent intent, TextIndexSpec spec) {
        if (intent == null || spec == null) return;
        write(new StringSink() {
            public void put(String key, String value) { intent.putExtra(key, value); }
        }, spec);
    }

    /** Bundle（onSaveInstanceState）版本，键与 Intent 完全一致。 */
    public static void putInto(Bundle bundle, TextIndexSpec spec) {
        if (bundle == null || spec == null) return;
        write(new StringSink() {
            public void put(String key, String value) { bundle.putString(key, value); }
        }, spec);
    }

    /** 缺字段 / specId 不符 / 数字非法 → null（= 无有效声称，判过期）。 */
    public static TextIndexSpec claimedFrom(Intent intent) {
        if (intent == null) return null;
        return assemble(intent.getStringExtra(EXTRA_SPEC_BOOK_ID),
                intent.getStringExtra(EXTRA_SPEC_SHA),
                intent.getStringExtra(EXTRA_SPEC_ENCODING),
                intent.getStringExtra(EXTRA_SPEC_RULE_MODE),
                intent.getStringExtra(EXTRA_SPEC_RULE_CUSTOM),
                intent.getStringExtra(EXTRA_SPEC_ID));
    }

    public static TextIndexSpec claimedFrom(Bundle bundle) {
        if (bundle == null) return null;
        return assemble(bundle.getString(EXTRA_SPEC_BOOK_ID),
                bundle.getString(EXTRA_SPEC_SHA),
                bundle.getString(EXTRA_SPEC_ENCODING),
                bundle.getString(EXTRA_SPEC_RULE_MODE),
                bundle.getString(EXTRA_SPEC_RULE_CUSTOM),
                bundle.getString(EXTRA_SPEC_ID));
    }

    /** claimed 缺失或与当前派生 spec 不符即失效。 */
    public static boolean stillValid(Context context, TextIndexSpec claimed) {
        if (claimed == null || context == null) return false;
        TextIndexSpec current = current(context, claimed.bookId);
        return current != null && !claimed.stale(current);
    }

    /** 统一过期文案（T03 再弹 UI，本轮只提供文案）。 */
    public static String staleMessage() {
        return "索引已变化（编码/分章规则/正文已改），请重新查找";
    }

    private interface StringSink { void put(String key, String value); }

    private static void write(StringSink sink, TextIndexSpec spec) {
        sink.put(EXTRA_SPEC_BOOK_ID, spec.bookId);
        sink.put(EXTRA_SPEC_SHA, spec.contentSha == null ? "" : spec.contentSha);
        sink.put(EXTRA_SPEC_ENCODING, Integer.toString(spec.encoding));
        sink.put(EXTRA_SPEC_RULE_MODE, Integer.toString(spec.ruleMode));
        sink.put(EXTRA_SPEC_RULE_CUSTOM, spec.ruleCustom);
        sink.put(EXTRA_SPEC_ID, spec.specId());
    }

    private static TextIndexSpec assemble(String bookId, String sha, String encoding,
                                          String ruleMode, String ruleCustom, String claimedId) {
        if (bookId == null || sha == null || encoding == null || ruleMode == null
                || ruleCustom == null || claimedId == null || claimedId.isEmpty()) return null;
        try {
            TextIndexSpec spec = TextIndexSpec.of(bookId, sha,
                    Integer.parseInt(encoding), Integer.parseInt(ruleMode), ruleCustom);
            return claimedId.equals(spec.specId()) ? spec : null;
        } catch (NumberFormatException malformed) {
            return null;
        }
    }
}
