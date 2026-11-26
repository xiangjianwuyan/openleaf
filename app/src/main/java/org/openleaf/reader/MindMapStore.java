package org.openleaf.reader;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导图编辑存储（0.14.0 T02，设计 §7）。
 *
 * <p>独立 SP 文件 {@link #SP}，不进 DB、不进三处备份清单，也不加入 BookStore 删书清理清单。
 * 只存用户编辑后的 JSON（坐标 / 标题覆盖 / 折叠态），首次默认布局按 ChapterOutline + MindMapLayout
 * 现算，不落盘。键：{@code map_<bookId>} / {@code keys}（LRU 上限 50） / {@code mode_<bookId>} / {@code schema}。</p>
 */
public final class MindMapStore {
    public static final String SP = "mindmap";
    public static final String KEY_PREFIX = "map_";
    public static final String KEY_INDEX = "keys";
    public static final String KEY_MODE_PREFIX = "mode_";
    public static final String KEY_SCHEMA = "schema";
    public static final int MAX_ENTRIES = 50;
    public static final int MAX_JSON_BYTES = 64 * 1024;

    private static final Pattern FP = Pattern.compile("\"fp\"\\s*:\\s*\"([^\"]+)\"");
    private final SharedPreferences prefs;

    public MindMapStore(Context context) {
        if (context == null) throw new NullPointerException("context");
        prefs = context.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 无数据返回 null。 */
    public String load(String bookId) {
        if (bookId == null || bookId.isEmpty()) return null;
        return prefs.getString(KEY_PREFIX + bookId, null);
    }

    /** 同步维护 LRU 索引（上限 50）；JSON 超过 64 KiB 或 bookId 空白时显式拒绝。 */
    public void save(String bookId, String json) {
        requireBookId(bookId);
        if (json == null || json.isEmpty()) throw new IllegalArgumentException("导图数据为空");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("导图数据超过 64 KiB");
        }
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_PREFIX + bookId, json);
        editor.putInt(KEY_SCHEMA, 1);
        List<String> keys = index();
        keys.remove(bookId);
        keys.add(0, bookId);
        while (keys.size() > MAX_ENTRIES) {
            String evicted = keys.remove(keys.size() - 1);
            editor.remove(KEY_PREFIX + evicted);
            editor.remove(KEY_MODE_PREFIX + evicted);
        }
        editor.putString(KEY_INDEX, String.join(",", keys));
        editor.apply();
    }

    public void remove(String bookId) {
        if (bookId == null || bookId.isEmpty()) return;
        List<String> keys = index();
        if (!keys.remove(bookId)) return;
        prefs.edit()
                .remove(KEY_PREFIX + bookId)
                .remove(KEY_MODE_PREFIX + bookId)
                .putString(KEY_INDEX, String.join(",", keys))
                .apply();
    }

    public void clearAll() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String bookId : index()) {
            editor.remove(KEY_PREFIX + bookId);
            editor.remove(KEY_MODE_PREFIX + bookId);
        }
        editor.remove(KEY_INDEX);
        editor.putInt(KEY_SCHEMA, 1);
        editor.apply();
    }

    /** fp = hash16(bookId + "|" + chapterCount)，FNV-1a 64 位，固定 16 个小写十六进制字符。 */
    public String fingerprint(String bookId, int chapterCount) {
        requireBookId(bookId);
        byte[] input = (bookId + "|" + chapterCount).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte b : input) {
            hash ^= (b & 0xFFL);
            hash *= 0x100000001b3L;
        }
        return String.format(Locale.ROOT, "%016x", hash);
    }

    /** 从已存 JSON 中取 fp；无 fp 字段返回 null（旧数据 / 手改数据走重新生成）。 */
    public String storedFingerprint(String json) {
        if (json == null) return null;
        Matcher matcher = FP.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 指纹是否匹配当前书与章节数；不匹配须按 §6 文案 26 重新生成。 */
    public boolean matchesFingerprint(String json, String bookId, int chapterCount) {
        String stored = storedFingerprint(json);
        return stored != null && stored.equals(fingerprint(bookId, chapterCount));
    }

    /** 记住该书上次所在模式（outline / map）。 */
    public void saveMode(String bookId, String mode) {
        requireBookId(bookId);
        prefs.edit().putString(KEY_MODE_PREFIX + bookId, mode).apply();
    }

    public String loadMode(String bookId) {
        if (bookId == null || bookId.isEmpty()) return "outline";
        String mode = prefs.getString(KEY_MODE_PREFIX + bookId, "outline");
        return "map".equals(mode) ? "map" : "outline";
    }

    private List<String> index() {
        String raw = prefs.getString(KEY_INDEX, "");
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String id : Arrays.asList(raw.split(",", -1))) {
            if (!id.isEmpty() && !out.contains(id)) out.add(id);
        }
        return out;
    }

    private static void requireBookId(String bookId) {
        if (bookId == null || bookId.trim().isEmpty()) throw new IllegalArgumentException("bookId 不能为空");
    }
}
