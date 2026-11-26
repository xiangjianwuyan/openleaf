package org.openleaf.reader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openleaf.reader.core.ContentContracts;
import org.openleaf.reader.core.OnlineChapter;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线书逐章缓存的存储层。
 * - 入库时只写骨架 txt（章节标题+占位），把 source 规则与章节 URL 清单存入 remote_meta
 * - 逐章正文下载后写入私有缓存文件并登记 chapter_cache
 * - 阅读时缓存命中直接读文件，未命中返回 null（触发下载）
 */
public final class OnlineChapterStore {
    private final Context context;
    private final OnlineBookStore books;

    public OnlineChapterStore(Context context) {
        this.context = context.getApplicationContext();
        this.books = new OnlineBookStore(this.context);
    }

    /** 清单 JSON：{"source":<规则JSON>,"chapters":[{"t":title,"u":url}]} */
    public static String manifest(String sourceJson, List<OnlineChapter.Entry> entries) {
        try {
            JSONObject root = new JSONObject();
            root.put("source", sourceJson == null ? "" : sourceJson);
            JSONArray array = new JSONArray();
            for (OnlineChapter.Entry e : entries) {
                JSONObject item = new JSONObject();
                item.put("t", e.title); item.put("u", e.url);
                array.put(item);
            }
            root.put("chapters", array);
            return root.toString();
        } catch (Exception e) { throw new IllegalStateException("生成章节清单失败", e); }
    }

    public List<OnlineChapter.Entry> chapters(String manifest) {
        List<OnlineChapter.Entry> result = new ArrayList<>();
        if (manifest == null || manifest.isEmpty()) return result;
        try {
            JSONArray array = new JSONObject(manifest).optJSONArray("chapters");
            if (array == null) return result;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                result.add(new OnlineChapter.Entry(i, item.optString("t", ""), item.optString("u", "")));
            }
        } catch (Exception ignored) { }
        return result;
    }

    public String sourceJson(String manifest) {
        if (manifest == null || manifest.isEmpty()) return "";
        try { return new JSONObject(manifest).optString("source", ""); }
        catch (Exception ignored) { return ""; }
    }

    /** 章节缓存目录（应用私有，持久化）。 */
    private File chapterDir(String bookId) {
        File dir = new File(context.getFilesDir(), "online-chapters/" + bookId);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("无法创建章节缓存目录");
        return dir;
    }

    /** 读取已缓存章节正文；未缓存返回 null。 */
    public String cachedText(String bookId, String chapterKey) {
        OnlineBookStore.CacheRecord record = books.chapter(bookId, chapterKey);
        if (record == null || !"CACHED".equals(record.state)) return null;
        if (record.cachePath == null || record.cachePath.isEmpty()) return null;
        File file = new File(record.cachePath);
        if (!file.isFile()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return null; }
    }

    /** 写入章节缓存（下载成功后调用）：正文写文件 + 登记 chapter_cache。 */
    public void cache(String bookId, String chapterKey, String text, boolean retained) {
        if (text == null) return;
        File file = new File(chapterDir(bookId), safeFileName(chapterKey) + ".txt");
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            out.write(bytes);
            String sha = ContentContracts.hashHex(bytes);
            books.cacheChapter(bookId, chapterKey, file.getAbsolutePath(), sha, bytes.length, retained);
        } catch (Exception ignored) { /* 写失败不登记，允许重试 */ }
    }

    /** 缓存文件名安全化：只允许字母数字和少数符号，杜绝路径穿越。 */
    static String safeFileName(String key) {
        if (key == null || key.isEmpty()) return "chapter";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length() && sb.length() < 96; i++) {
            char c = key.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') sb.append(c);
            else sb.append('_');
        }
        return sb.length() == 0 ? "chapter" : sb.toString();
    }

    /** 配额自检（LRU 淘汰），委托 OnlineBookStore。 */
    public List<String> enforceQuota(String bookId, long quotaBytes) {
        return books.enforceQuota(bookId, quotaBytes);
    }

    public OnlineBookStore store() { return books; }
}
