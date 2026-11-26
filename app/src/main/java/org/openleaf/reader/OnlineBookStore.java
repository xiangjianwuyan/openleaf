package org.openleaf.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.openleaf.reader.core.OnlineLibrary;

import java.util.ArrayList;
import java.util.List;

/**
 * DB v14 online_books / chapter_cache 的读写仓储。
 * 稳定标识 sourceId+remoteKey；规则版本失配可提示重新解析；
 * 章节缓存区分用户保留(retained)与自动预取（可 LRU 清理）。
 */
public final class OnlineBookStore {
    private final LibraryDatabase database;

    public OnlineBookStore(Context context) { database = LibraryDatabase.get(context); }

    /** 在线书绑定记录。 */
    public static final class OnlineBook {
        public final String bookId, sourceId, remoteKey, ruleVersion, remoteMeta;
        public final long updatedAt;
        OnlineBook(String bookId, String sourceId, String remoteKey, String ruleVersion,
                   String remoteMeta, long updatedAt) {
            this.bookId = bookId; this.sourceId = sourceId; this.remoteKey = remoteKey;
            this.ruleVersion = ruleVersion; this.remoteMeta = remoteMeta; this.updatedAt = updatedAt;
        }
    }

    /** 按本地 bookId 查绑定；无记录返回 null。 */
    public OnlineBook findByBookId(String bookId) {
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor c = db.query("online_books", null, "book_id=?", new String[]{bookId}, null, null, null);
        try {
            if (c.moveToFirst()) return new OnlineBook(
                    c.getString(c.getColumnIndexOrThrow("book_id")),
                    c.getString(c.getColumnIndexOrThrow("source_id")),
                    c.getString(c.getColumnIndexOrThrow("remote_key")),
                    c.getString(c.getColumnIndexOrThrow("rule_version")),
                    c.getString(c.getColumnIndexOrThrow("remote_meta")),
                    c.getLong(c.getColumnIndexOrThrow("updated_at")));
            return null;
        } finally { c.close(); }
    }

    /** 列出全部在线书绑定（供"检查书源更新"批量扫描）。 */
    public List<OnlineBook> allBindings() {
        List<OnlineBook> result = new ArrayList<>();
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor c = db.query("online_books", null, null, null, null, null, "updated_at DESC");
        try {
            while (c.moveToNext()) result.add(new OnlineBook(
                    c.getString(c.getColumnIndexOrThrow("book_id")),
                    c.getString(c.getColumnIndexOrThrow("source_id")),
                    c.getString(c.getColumnIndexOrThrow("remote_key")),
                    c.getString(c.getColumnIndexOrThrow("rule_version")),
                    c.getString(c.getColumnIndexOrThrow("remote_meta")),
                    c.getLong(c.getColumnIndexOrThrow("updated_at"))));
        } finally { c.close(); }
        return result;
    }

    /** 按 source+remoteKey 查绑定；无记录返回 null。 */
    public OnlineBook find(String sourceId, String remoteKey) {
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor c = db.query("online_books", null, "source_id=? AND remote_key=?",
                new String[]{sourceId, remoteKey}, null, null, null);
        try {
            if (c.moveToFirst()) return new OnlineBook(
                    c.getString(c.getColumnIndexOrThrow("book_id")),
                    c.getString(c.getColumnIndexOrThrow("source_id")),
                    c.getString(c.getColumnIndexOrThrow("remote_key")),
                    c.getString(c.getColumnIndexOrThrow("rule_version")),
                    c.getString(c.getColumnIndexOrThrow("remote_meta")),
                    c.getLong(c.getColumnIndexOrThrow("updated_at")));
            return null;
        } finally { c.close(); }
    }

    /** 绑定在线书（幂等：同一 source+remoteKey 只更新）。 */
    public void bind(String bookId, String sourceId, String remoteKey, String ruleVersion, String remoteMeta) {
        ContentValues values = new ContentValues();
        values.put("book_id", bookId);
        values.put("source_id", sourceId);
        values.put("remote_key", remoteKey);
        values.put("rule_version", ruleVersion == null ? "" : ruleVersion);
        values.put("remote_meta", remoteMeta == null ? "" : remoteMeta);
        values.put("updated_at", System.currentTimeMillis());
        database.getWritableDatabase().insertWithOnConflict("online_books", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 规则版本是否失配（无记录视为不失配）。 */
    public boolean ruleChanged(String sourceId, String remoteKey, String currentVersion) {
        OnlineBook book = find(sourceId, remoteKey);
        return book != null && OnlineLibrary.ruleChanged(book.ruleVersion, currentVersion);
    }

    // ---------- 章节缓存 ----------

    /** 缓存条目状态。 */
    public static final class CacheRecord {
        public final String bookId, chapterKey, state, cachePath, sha256;
        public final long size;
        public final boolean retained;
        CacheRecord(String bookId, String chapterKey, String state, String cachePath,
                    String sha256, long size, boolean retained) {
            this.bookId = bookId; this.chapterKey = chapterKey; this.state = state;
            this.cachePath = cachePath; this.sha256 = sha256; this.size = size; this.retained = retained;
        }
    }

    public CacheRecord chapter(String bookId, String chapterKey) {
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor c = db.query("chapter_cache", null, "book_id=? AND chapter_key=?",
                new String[]{bookId, chapterKey}, null, null, null);
        try {
            if (c.moveToFirst()) return new CacheRecord(
                    c.getString(c.getColumnIndexOrThrow("book_id")),
                    c.getString(c.getColumnIndexOrThrow("chapter_key")),
                    c.getString(c.getColumnIndexOrThrow("state")),
                    c.getString(c.getColumnIndexOrThrow("cache_path")),
                    c.getString(c.getColumnIndexOrThrow("sha256")),
                    c.getLong(c.getColumnIndexOrThrow("size")),
                    c.getInt(c.getColumnIndexOrThrow("retained")) == 1);
            return null;
        } finally { c.close(); }
    }

    /** 章节是否已缓存且文件存在。 */
    public boolean isCached(String bookId, String chapterKey) {
        CacheRecord record = chapter(bookId, chapterKey);
        return record != null && "CACHED".equals(record.state)
                && record.cachePath != null && !record.cachePath.isEmpty()
                && new java.io.File(record.cachePath).isFile();
    }

    /** 标记章节缓存（下载成功后调用）。 */
    public void cacheChapter(String bookId, String chapterKey, String cachePath, String sha256, long size, boolean retained) {
        ContentValues values = new ContentValues();
        values.put("book_id", bookId);
        values.put("chapter_key", chapterKey);
        values.put("state", "CACHED");
        values.put("cache_path", cachePath == null ? "" : cachePath);
        values.put("sha256", sha256 == null ? "" : sha256);
        values.put("size", size);
        values.put("retained", retained ? 1 : 0);
        values.put("updated_at", System.currentTimeMillis());
        database.getWritableDatabase().insertWithOnConflict("chapter_cache", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 标记章节失败或待处理。 */
    public void markChapter(String bookId, String chapterKey, OnlineLibrary.CacheState state) {
        ContentValues values = new ContentValues();
        values.put("state", state.name());
        values.put("updated_at", System.currentTimeMillis());
        database.getWritableDatabase().update("chapter_cache", values,
                "book_id=? AND chapter_key=?", new String[]{bookId, chapterKey});
    }

    /** 用户主动保存：标记 retained，避免被 LRU 清理。 */
    public void retain(String bookId, String chapterKey, boolean retained) {
        ContentValues values = new ContentValues();
        values.put("retained", retained ? 1 : 0);
        values.put("updated_at", System.currentTimeMillis());
        database.getWritableDatabase().update("chapter_cache", values,
                "book_id=? AND chapter_key=?", new String[]{bookId, chapterKey});
    }

    /** 某书所有已缓存章节（供 LRU 淘汰计算）。 */
    public List<OnlineLibrary.CacheEntry> cacheEntries(String bookId) {
        List<OnlineLibrary.CacheEntry> result = new ArrayList<>();
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor c = db.query("chapter_cache", null, "book_id=?", new String[]{bookId},
                null, null, "updated_at ASC");
        try {
            while (c.moveToNext()) {
                OnlineLibrary.CacheState state;
                try { state = OnlineLibrary.CacheState.valueOf(c.getString(c.getColumnIndexOrThrow("state"))); }
                catch (RuntimeException ignored) { state = OnlineLibrary.CacheState.PENDING; }
                result.add(new OnlineLibrary.CacheEntry(
                        c.getString(c.getColumnIndexOrThrow("chapter_key")), state,
                        c.getLong(c.getColumnIndexOrThrow("size")),
                        c.getInt(c.getColumnIndexOrThrow("retained")) == 1,
                        c.getLong(c.getColumnIndexOrThrow("updated_at"))));
            }
        } finally { c.close(); }
        return result;
    }

    /** 预取计划：从起始章取后 N 章未缓存的 chapterKey（用 PrefetchFollow 的有界策略）。 */
    public List<String> prefetch(String bookId, List<String> allChapterKeys, String startKey, int count) {
        java.util.Map<String, OnlineLibrary.CacheEntry> existing = new java.util.HashMap<>();
        for (OnlineLibrary.CacheEntry entry : cacheEntries(bookId)) existing.put(entry.chapterKey, entry);
        return org.openleaf.reader.core.PrefetchFollow.plan(allChapterKeys,
                key -> key, existing.keySet(), new java.util.HashSet<>(), count, false);
    }

    /** 配额自检 + LRU 淘汰：超过 quota 时按 OnlineLibrary 策略清理（retained 永不淘汰）。 */
    public List<String> enforceQuota(String bookId, long quotaBytes) {
        List<OnlineLibrary.CacheEntry> entries = cacheEntries(bookId);
        List<String> victims = OnlineLibrary.evictCandidates(entries, quotaBytes);
        if (!victims.isEmpty()) evict(bookId, victims);
        return victims;
    }

    /** 执行 LRU 淘汰：删除被选中的缓存文件与记录。 */
    public void evict(String bookId, List<String> chapterKeys) {
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            for (String key : chapterKeys) {
                CacheRecord record = chapter(bookId, key);
                if (record != null && record.cachePath != null && !record.cachePath.isEmpty())
                    new java.io.File(record.cachePath).delete();
                db.delete("chapter_cache", "book_id=? AND chapter_key=?", new String[]{bookId, key});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
