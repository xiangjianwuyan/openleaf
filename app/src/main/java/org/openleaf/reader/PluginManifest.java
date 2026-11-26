package org.openleaf.reader;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict, data-only contract: no generic actions, URLs, regular expressions or display markup. */
final class PluginManifest {
    static final int MAX_BYTES = 16 * 1024;
    static final String CAPABILITY = "txt-report";
    static final String BUILTIN_ID = "openleaf.text-audit";
    static final String BUILTIN = "{\"schema\":1,\"id\":\"" + BUILTIN_ID + "\",\"name\":\"文本体检\","
            + "\"description\":\"统计所选文档的行数、空行与字符数，按常见章标题开头列出示例。\","
            + "\"capability\":\"txt-report\",\"prefixes\":[\"# \",\"## \",\"第\",\"Chapter \"]}";

    final String id, name, description;
    final List<String> prefixes;

    private PluginManifest(String id, String name, String description, List<String> prefixes) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.prefixes = Collections.unmodifiableList(new ArrayList<>(prefixes));
    }

    static PluginManifest parse(String data) throws IOException {
        if (data == null || data.length() > MAX_BYTES) throw invalid("清单为空或过长");
        try (JsonReader reader = new JsonReader(new StringReader(data))) {
            reader.setLenient(false);
            reader.beginObject();
            Set<String> seen = new HashSet<>();
            String id = null, name = null, description = null;
            List<String> prefixes = null;
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!seen.add(key)) throw invalid("字段重复");
                switch (key) {
                    case "schema":
                        if (reader.peek() != JsonToken.NUMBER || !"1".equals(reader.nextString())) throw invalid("不支持此清单版本");
                        break;
                    case "id":
                        id = string(reader);
                        if (!id.matches("[a-z][a-z0-9-]{0,31}(?:\\.[a-z][a-z0-9-]{0,31}){1,3}") || id.length() > 80) {
                            throw invalid("插件 ID 必须是小写点分标识");
                        }
                        break;
                    case "name": name = display(string(reader), 48, false); break;
                    case "description": description = display(string(reader), 180, false); break;
                    case "capability":
                        if (!CAPABILITY.equals(string(reader))) throw invalid("不支持此插件能力");
                        break;
                    case "prefixes":
                        prefixes = new ArrayList<>();
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (prefixes.size() == 8) throw invalid("最多 8 个标题前缀");
                            String prefix = display(string(reader), 20, true);
                            if (prefixes.contains(prefix)) throw invalid("标题前缀重复");
                            prefixes.add(prefix);
                        }
                        reader.endArray();
                        if (prefixes.isEmpty()) throw invalid("至少需要 1 个标题前缀");
                        break;
                    default: throw invalid("清单包含不支持的字段");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || seen.size() != 6) throw invalid("缺少必填字段或存在额外内容");
            return new PluginManifest(id, name, description, prefixes);
        } catch (IllegalStateException e) {
            throw invalid("字段类型不正确");
        } catch (android.util.MalformedJsonException e) {
            throw invalid("JSON 格式无效");
        }
    }

    String encode() throws JSONException {
        return new JSONObject().put("schema", 1).put("id", id).put("name", name)
                .put("description", description).put("capability", CAPABILITY)
                .put("prefixes", new JSONArray(prefixes)).toString();
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid("字段必须为字符串");
        return reader.nextString();
    }

    private static String display(String value, int max, boolean prefix) throws IOException {
        final int length = value.codePointCount(0, value.length());
        if (length == 0 || length > max || value.trim().isEmpty()) throw invalid("文本字段长度不合规");
        if (prefix && Character.isWhitespace(value.codePointAt(0))) throw invalid("标题前缀不能以空白开头");
        if (value.codePoints().anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                || cp == 0x2028 || cp == 0x2029 || (cp >= 0xd800 && cp <= 0xdfff))) throw invalid("文本字段包含不可见控制字符");
        return prefix ? value : value.trim();
    }

    private static IOException invalid(String message) { return new IOException(message); }
}
