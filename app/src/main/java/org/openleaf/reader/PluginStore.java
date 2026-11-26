package org.openleaf.reader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Separate app-private settings; not part of the reader preferences or library DB. */
final class PluginStore {
    static final int MAX_PLUGINS = 20;
    private static final String KEY = "installed_v1";
    private final SharedPreferences prefs;

    enum Source {
        BUILTIN("内置目录"), LOCAL("本地 JSON");
        final String label;
        Source(String label) { this.label = label; }
    }

    static final class Installed {
        final PluginManifest manifest;
        final Source source;
        final boolean enabled;
        Installed(PluginManifest manifest, Source source, boolean enabled) {
            this.manifest = manifest;
            this.source = source;
            this.enabled = enabled;
        }
    }

    PluginStore(Context context) {
        prefs = context.getSharedPreferences("openleaf_plugins", Context.MODE_PRIVATE);
    }

    List<Installed> list() throws IOException {
        try {
            JSONArray data = new JSONArray(prefs.getString(KEY, "[]"));
            if (data.length() > MAX_PLUGINS) throw new IOException("已安装插件记录超出上限");
            List<Installed> result = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject entry = data.getJSONObject(i);
                PluginManifest manifest = PluginManifest.parse(entry.getString("manifest"));
                Source source = Source.valueOf(entry.getString("source"));
                if (indexOf(result, manifest.id) >= 0 || (source == Source.BUILTIN) != PluginManifest.BUILTIN_ID.equals(manifest.id)) {
                    throw new IOException("已安装插件的来源或标识无效");
                }
                result.add(new Installed(manifest, source, entry.getBoolean("enabled")));
            }
            return result;
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("已安装插件记录无法读取", e);
        }
    }

    Installed get(String id) throws IOException {
        List<Installed> entries = list();
        int index = indexOf(entries, id);
        return index < 0 ? null : entries.get(index);
    }

    void install(PluginManifest manifest, Source source) throws IOException {
        if (source == Source.LOCAL && manifest.id.startsWith("openleaf.")) throw new IOException("openleaf.* 仅用于内置插件");
        if (source == Source.BUILTIN && !PluginManifest.BUILTIN_ID.equals(manifest.id)) throw new IOException("不是可用的内置插件");
        List<Installed> entries = list();
        if (indexOf(entries, manifest.id) >= 0) throw new IOException("此 ID 已安装。请先移除旧清单，再重新导入。");
        if (entries.size() >= MAX_PLUGINS) throw new IOException("最多可安装 " + MAX_PLUGINS + " 个插件");
        entries.add(new Installed(manifest, source, false));
        save(entries);
    }

    void setEnabled(String id, boolean enabled) throws IOException {
        List<Installed> entries = list();
        int index = indexOf(entries, id);
        if (index < 0) throw new IOException("插件已移除");
        Installed current = entries.get(index);
        entries.set(index, new Installed(current.manifest, current.source, enabled));
        save(entries);
    }

    void remove(String id) throws IOException {
        List<Installed> entries = list();
        int index = indexOf(entries, id);
        if (index >= 0) {
            entries.remove(index);
            save(entries);
        }
    }

    private static int indexOf(List<Installed> entries, String id) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).manifest.id.equals(id)) return i;
        return -1;
    }

    private void save(List<Installed> entries) throws IOException {
        JSONArray data = new JSONArray();
        try {
            for (Installed entry : entries) {
                data.put(new JSONObject().put("manifest", entry.manifest.encode())
                        .put("source", entry.source.name()).put("enabled", entry.enabled));
            }
        } catch (JSONException e) {
            throw new IOException("无法生成插件设置", e);
        }
        prefs.edit().putString(KEY, data.toString()).apply();
    }
}
