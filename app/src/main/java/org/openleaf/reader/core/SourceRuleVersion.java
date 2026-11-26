package org.openleaf.reader.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线书书源规则版本（纯 JVM，无 Android / org.json 依赖）。
 *
 * 用途：入库时把"影响抓取结果"的规则字段规范化后算一个稳定版本，写入 online_books.rule_version；
 * 书源更新后重新计算并与存储版本比对，失配即可提示用户"书源规则已更新，需重新解析"。
 *
 * 稳定性设计（避免误报/漏报）：
 * 1. 取字段白名单——只纳入真正影响搜索/目录/正文抓取的字段，忽略仅展示用字段（如 name），
 *    否则用户改个书源备注也会误报"规则已更新"。
 * 2. 每项用「长度:值」前缀拼接，消除分隔符出现在值里导致的拼接歧义。
 * 3. 按键排序后拼接，屏蔽 JSON 对象键顺序差异（org.json 键序在不同版本/构造方式下不稳定）。
 * 4. 规范化值由 Android 层传入（对规则 JSON 递归按键排序），本类只做长度前缀 + 排序 + 哈希。
 */
public final class SourceRuleVersion {
    private SourceRuleVersion() { }

    /** 影响抓取的字段白名单（规范化时按键排序，故此处顺序无关紧要）。 */
    public static final String[] RULE_FIELDS = {"searchUrl", "search", "info", "toc", "content"};

    /**
     * 规范化字段集合 → 稳定版本哈希。
     * 传入的 map 应只包含白名单字段；值为该字段的规范化表示（JSON 递归键排序后的字符串，
     * 或直接是字符串字段如 searchUrl）。空 map 也返回确定值，不会抛异常。
     */
    public static String compute(Map<String, String> normalizedFields) {
        Map<String, String> sorted = new LinkedHashMap<String, String>();
        if (normalizedFields != null) {
            List<String> keys = new ArrayList<String>(normalizedFields.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                String value = normalizedFields.get(key) == null ? "" : normalizedFields.get(key);
                sorted.put(key, value);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            appendPart(builder, entry.getKey());
            appendPart(builder, entry.getValue());
        }
        return ContentContracts.hashHex(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 长度前缀写入，避免 "a|bc" 与 "ab|c" 相同。 */
    private static void appendPart(StringBuilder builder, String value) {
        builder.append(value.length()).append(':').append(value).append(';');
    }

    /** 规则版本是否失配（空视为未设置，与已有绑定比对）。 */
    public static boolean changed(String storedVersion, String currentVersion) {
        String a = storedVersion == null ? "" : storedVersion;
        String b = currentVersion == null ? "" : currentVersion;
        return !a.equals(b);
    }

    /** 摘要文案：供"检查书源更新"提示使用，不会泄露完整规则。 */
    public static String shortVersion(String version) {
        if (version == null || version.isEmpty()) return "（无版本）";
        return version.length() <= 12 ? version : version.substring(0, 12) + "…";
    }
}
