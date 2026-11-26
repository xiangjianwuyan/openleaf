package org.openleaf.reader;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.openleaf.reader.core.ContentContracts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 漫画阅读进度（0.13.0 缺口 5）。
 *
 * ComicActivity 没有可用 bookId，按「显示名|页数|首页名」文件指纹做键（Q6 / §5.2）。
 * 独立 SP 文件 "comicProgress"，不污染 "reader" 的删除清理清单；keys 列表 LRU 上限 50 自清。
 */
final class ComicProgressStore {
    static final String SP = "comicProgress";
    static final String KEY_PAGE = "page_";
    static final String KEY_TOTAL = "total_";
    static final String KEY_NAME = "name_";
    static final String KEY_KEYS = "keys";
    static final int COMIC_PROGRESS_MAX_KEYS = 50;

    private final android.content.SharedPreferences prefs;

    ComicProgressStore(Context c) { prefs = c.getSharedPreferences(SP, 0); }

    /** 无 bookId 时的稳定指纹：SHA-256 前 16 位 hex，URI 变化不影响。 */
    String keyFor(String displayName, int pageCount, String firstPageName) {
        String fallback = displayName == null ? "" : displayName;
        String first = firstPageName == null ? "" : firstPageName;
        String fingerprint = fallback + "|" + pageCount + "|" + first;
        return ContentContracts.hashHex(fingerprint.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /** SAF 显示名；查询失败回退 URI 末段。 */
    static String displayNameFor(Context c, Uri uri) {
        if (uri == null) return "未命名漫画";
        try (android.database.Cursor cursor = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (RuntimeException ignored) { /* 回退到 URI 末段 */ }
        String last = uri.getLastPathSegment();
        return last == null ? "未命名漫画" : last;
    }

    /** 写入页码，并把 key 提到 LRU 最前；超出 50 个时删除最旧的三键。 */
    void save(String key, int page, int total, String name) {
        if (key == null || key.isEmpty()) return;
        List<String> keys = loadKeys();
        keys.remove(key);
        keys.add(0, key);
        android.content.SharedPreferences.Editor editor = prefs.edit()
                .putInt(KEY_PAGE + key, page)
                .putInt(KEY_TOTAL + key, total)
                .putString(KEY_NAME + key, name == null ? "" : name);
        while (keys.size() > COMIC_PROGRESS_MAX_KEYS) {
            String oldest = keys.remove(keys.size() - 1);
            editor.remove(KEY_PAGE + oldest).remove(KEY_TOTAL + oldest).remove(KEY_NAME + oldest);
        }
        editor.putString(KEY_KEYS, join(keys)).apply();
    }

    /** -1 表示无进度或总页数已变（换版本）；其余为 0 起页码。 */
    int page(String key) {
        if (key == null || key.isEmpty() || !prefs.contains(KEY_PAGE + key)) return -1;
        int page = prefs.getInt(KEY_PAGE + key, -1);
        return page < 0 ? -1 : page;
    }

    /** 显式校验总页数，供「换版本从 0 起」使用；与 key 中的 pageCount 双保险。 */
    int page(String key, int expectedTotal) {
        if (key == null || key.isEmpty() || !prefs.contains(KEY_PAGE + key)) return -1;
        if (prefs.getInt(KEY_TOTAL + key, Integer.MIN_VALUE) != expectedTotal) return -1;
        int page = prefs.getInt(KEY_PAGE + key, -1);
        return page < 0 ? -1 : page;
    }

    /** 供「清除漫画阅读记录」使用：删除全部指纹三键与 LRU 列表。 */
    void clearAll() {
        android.content.SharedPreferences.Editor editor = prefs.edit();
        for (String key : loadKeys()) {
            editor.remove(KEY_PAGE + key).remove(KEY_TOTAL + key).remove(KEY_NAME + key);
        }
        editor.remove(KEY_KEYS).apply();
    }

    private List<String> loadKeys() {
        String raw = prefs.getString(KEY_KEYS, "");
        if (raw == null || raw.isEmpty()) return new ArrayList<String>();
        return new ArrayList<String>(Arrays.asList(raw.split(",")));
    }

    private static String join(List<String> keys) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) out.append(',');
            out.append(keys.get(i));
        }
        return out.toString();
    }
}
