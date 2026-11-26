package org.openleaf.reader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openleaf.reader.core.SourceRuleVersion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Legado 书源的共享仓储（application 级），供 LegadoActivity 与 ReaderActivity 共同访问。
 *
 * 背景：书源原先存在 LegadoActivity.getPreferences(0)（Activity 私有），阅读页无法读取，
 * 导致"规则版本失配自动提示"无法实现。本类把书源迁到应用级 SharedPreferences，
 * 首次读取时自动从旧的 Activity 私有 prefs 迁移，避免用户已有配置丢失。
 *
 * 同时提供规则版本计算：对影响抓取的字段白名单做规范化（JSON 递归键排序 + 长度前缀），
 * 与 SourceRuleVersion core 一致，保证入库与检查用同一算法。
 */
public final class SourceStore {
    private static final String PREFS = "legado_sources";
    private static final String KEY = "sources";
    private static final String LEGACY_PREFS = "LegadoActivity"; // getPreferences(0) 的实际文件名

    private final Context context;

    public SourceStore(Context context) { this.context = context.getApplicationContext(); }

    private SharedPreferences prefs() { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** 读取全部书源（url → Source），首次自动迁移旧配置。 */
    public Map<String, LegadoClient.Source> load() {
        SharedPreferences current = prefs();
        String encoded = current.getString(KEY, null);
        if (encoded == null) {
            // 迁移旧版 Activity 私有 prefs
            String legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).getString(KEY, null);
            if (legacy != null && !legacy.isEmpty()) {
                encoded = legacy;
                current.edit().putString(KEY, encoded).apply();
                // 立即清理旧键，避免重复迁移；新 prefs 已是唯一事实源
                context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                        .edit().remove(KEY).apply();
            }
        }
        if (encoded == null) encoded = "[]";
        LinkedHashMap<String, LegadoClient.Source> result = new LinkedHashMap<>();
        try {
            for (LegadoClient.Source item : LegadoClient.parseSources(encoded)) result.put(item.url, item);
        } catch (Exception ignored) { /* 损坏配置按空处理，不阻塞应用 */ }
        return result;
    }

    /** 保存全部书源（与旧 save() 行为一致：序列化后写入应用级 prefs）。 */
    public void save(Map<String, LegadoClient.Source> sources) throws Exception {
        String encoded = LegadoClient.exportSources(new ArrayList<>(sources.values()));
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 512 * 1024)
            throw new java.io.IOException("书源配置总大小不能超过 512 KiB");
        prefs().edit().putString(KEY, encoded).apply();
        // 清理旧 prefs，避免下次误迁移旧配置
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    /** 按 sourceId（即书源 url）找当前书源；书源已删除返回 null。 */
    public LegadoClient.Source find(String sourceId) {
        if (sourceId == null) return null;
        return load().get(sourceId);
    }

    /**
     * 计算书源规则版本：只纳入影响抓取的字段白名单，JSON 递归键排序规范化。
     * 与入库时 SourceRuleVersion.compute 使用同一算法；改书源名等展示字段不会误报。
     */
    public static String ruleVersion(LegadoClient.Source source) {
        if (source == null) return "";
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("searchUrl", nullToEmpty(source.searchUrl));
        fields.put("search", canonical(source.search));
        fields.put("info", canonical(source.info));
        fields.put("toc", canonical(source.toc));
        fields.put("content", canonical(source.content));
        return SourceRuleVersion.compute(fields);
    }

    /** 对 Source 的规则 JSON 计算版本（用于重新解析后写出新版本）。 */
    public static String ruleVersion(String sourceJson) {
        if (sourceJson == null || sourceJson.trim().isEmpty()) return "";
        try {
            JSONObject raw = new JSONObject(sourceJson);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("searchUrl", nullToEmpty(raw.optString("searchUrl", "")));
            fields.put("search", canonicalRule(raw.opt("ruleSearch")));
            fields.put("info", canonicalRule(raw.opt("ruleBookInfo")));
            fields.put("toc", canonicalRule(raw.opt("ruleToc")));
            fields.put("content", canonicalRule(raw.opt("ruleContent")));
            return SourceRuleVersion.compute(fields);
        } catch (Exception ignored) { return ""; }
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    /** JSONObject/Normal 值递归按键排序规范化；非 JSONObject 原样。 */
    private static String canonical(JSONObject object) {
        if (object == null) return "{}";
        return canonicalValue(object);
    }

    /** 规则字段可能是 JSONObject 或 JSON 字符串，统一规范化。 */
    private static String canonicalRule(Object value) {
        if (value == null || value == JSONObject.NULL) return "{}";
        if (value instanceof JSONObject) return canonicalValue((JSONObject) value);
        if (value instanceof String) {
            try { return canonicalValue(new JSONObject((String) value)); }
            catch (Exception ignored) { return nullToEmpty((String) value); }
        }
        return String.valueOf(value);
    }

    private static String canonicalValue(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            TreeMap<String, String> sorted = new TreeMap<>();
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                sorted.put(key, canonicalValue(object.opt(key)));
            }
            StringBuilder builder = new StringBuilder("{");
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                if (builder.length() > 1) builder.append(',');
                builder.append(quote(entry.getKey())).append(':').append(entry.getValue());
            }
            return builder.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) builder.append(',');
                builder.append(canonicalValue(array.opt(i)));
            }
            return builder.append(']').toString();
        }
        if (value instanceof String) return quote((String) value);
        if (value == JSONObject.NULL || value == null) return "null";
        return String.valueOf(value);
    }

    private static String quote(String value) {
        return "\"" + nullToEmpty(value).replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
