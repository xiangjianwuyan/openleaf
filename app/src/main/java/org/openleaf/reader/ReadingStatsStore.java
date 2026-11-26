package org.openleaf.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.openleaf.reader.core.MediaAttribution;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阅读时长记录与统计。借鉴 KOReader statistics 插件的「有操作才计时 + 单页封顶」思想、
 * LightRead readingStats 的连续天数/热力图算法、Anx Reader reading_time 的同书同日累加模型。
 * 由 OpenLeaf 自行实现，SQLite 存储。
 */
public final class ReadingStatsStore {
    static final long MIN_SECONDS = 3;      // 低于 3 秒的停留不计（过滤误触/翻页抖动）
    static final long MAX_SECONDS = 300;    // 单页最多计 300 秒（防止离开设备虚增）
    static final long ACTIVE_DAY_SECONDS = 60; // 当日读满 60 秒才算一个阅读日

    private final LibraryDatabase database;

    ReadingStatsStore(Context context) {
        database = LibraryDatabase.get(context);
    }

    /**
     * 记录一段阅读时长，同时写合计表（reading_time）与分列分段表（reading_sessions）。
     * @param segmentStartMs 分段起始毫秒；>=0 时生成稳定事件键，重复提交只计一次。
     */
    void addSeconds(String bookId, long seconds,
                    org.openleaf.reader.core.ReadingLedger.Kind kind, long segmentStartMs) {
        if (bookId == null || seconds < MIN_SECONDS || kind == null) return;
        long capped = Math.min(seconds, MAX_SECONDS);
        String day = LocalDate.now().toString();
        SQLiteDatabase db = database.getWritableDatabase();
        // reading_time.book_id 上有 REFERENCES books(id)：传进来的若不是真实书 id，
        // 这条 INSERT 会抛 SQLiteConstraintException 并把调用方线程带崩（听书页曾拿曲目 URI 当 book_id）。
        // 统计只认书库里的书，非书 id 一律不入库，把契约收紧在这里而不是要求每个调用方自己判断。
        if (!isKnownBook(db, bookId)) return;
        db.execSQL("INSERT INTO reading_time (book_id,day,seconds,created_at) VALUES (?,?,?,?) "
                        + "ON CONFLICT(book_id,day) DO UPDATE SET seconds=seconds+excluded.seconds",
                new Object[]{bookId, day, capped, System.currentTimeMillis()});
        // 分列分段：仅当有稳定分段起点时写入（事件键去重）
        if (segmentStartMs >= 0) {
            String eventKey = org.openleaf.reader.core.ReadingLedger.eventKey(bookId, kind, day, segmentStartMs);
            db.execSQL("INSERT OR IGNORE INTO reading_sessions "
                    + "(event_key,book_id,day,kind,seconds,created_at) VALUES (?,?,?,?,?,?)",
                    new Object[]{eventKey, bookId, day, kind.key(), capped, System.currentTimeMillis()});
        }
    }

    /**
     * 记录一段「按文件直接打开」的媒体时长（漫画 / 本地音频）。
     *
     * <p>这些媒体没有书库 id，不能写 {@code reading_time}（外键会拒绝）。写入无外键的
     * {@code media_time}，并用确定性 id 同时落一条分列分段。id 跨设备稳定，所以备份恢复
     * 不需要像 books.id 那样重映射。
     *
     * @param mediaKey 该媒体的稳定键（漫画用进度指纹，音频用 URI/路径指纹）
     * @param segmentStartMs 分段起点；>=0 时生成稳定事件键，重复提交只计一次
     */
    void addMediaSeconds(org.openleaf.reader.core.ReadingLedger.Kind kind, String mediaKey,
                         String title, long seconds, long segmentStartMs) {
        if (kind == null || seconds < MIN_SECONDS) return;
        String sourceId = MediaAttribution.id(kind, mediaKey);
        if (sourceId == null) return;
        long capped = Math.min(seconds, MAX_SECONDS);
        String day = LocalDate.now().toString();
        long now = System.currentTimeMillis();
        SQLiteDatabase db = database.getWritableDatabase();
        // 分列会话按 event_key 去重；合计表若仍无条件 += 就会在重复提交时翻倍，
        // 变成「分列 90 / 合计 180」——0.20.0 修过的矛盾不能在新表上重演。
        String eventKey = segmentStartMs >= 0
                ? org.openleaf.reader.core.ReadingLedger.eventKey(sourceId, kind, day, segmentStartMs) : null;
        if (eventKey != null) {
            Cursor duplicate = db.query("reading_sessions", new String[]{"1"},
                    "event_key=?", new String[]{eventKey}, null, null, null, "1");
            try { if (duplicate.moveToFirst()) return; } finally { duplicate.close(); }
        }
        db.execSQL("INSERT INTO media_sources (id,kind,title,created_at) VALUES (?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET title=excluded.title",
                new Object[]{sourceId, kind.key(), title == null ? "" : title, now});
        db.execSQL("INSERT INTO media_time (source_id,day,seconds,created_at) VALUES (?,?,?,?) "
                        + "ON CONFLICT(source_id,day) DO UPDATE SET seconds=seconds+excluded.seconds",
                new Object[]{sourceId, day, capped, now});
        if (eventKey != null) {
            db.execSQL("INSERT OR IGNORE INTO reading_sessions "
                            + "(event_key,book_id,day,kind,seconds,created_at) VALUES (?,?,?,?,?,?)",
                    new Object[]{eventKey, sourceId, day, kind.key(), capped, now});
        }
    }

    /** 按 kind 汇总分列时长（秒）。 */
    Map<org.openleaf.reader.core.ReadingLedger.Kind, Long> totalsByKind() {
        Map<org.openleaf.reader.core.ReadingLedger.Kind, Long> result =
                new java.util.LinkedHashMap<org.openleaf.reader.core.ReadingLedger.Kind, Long>();
        for (org.openleaf.reader.core.ReadingLedger.Kind k : org.openleaf.reader.core.ReadingLedger.Kind.values())
            result.put(k, 0L);
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor c = db.query("reading_sessions", new String[]{"kind", "SUM(seconds)"}, null, null, "kind", null, null);
        try {
            while (c.moveToNext()) {
                org.openleaf.reader.core.ReadingLedger.Kind kind = parseKind(c.getString(0));
                if (kind != null) result.put(kind, c.getLong(1));
            }
        } finally { c.close(); }
        return result;
    }

    /** 该书 id 是否真实存在于 books 表（reading_time 的外键目标）。 */
    private static boolean isKnownBook(SQLiteDatabase db, String bookId) {
        Cursor cursor = db.rawQuery("SELECT 1 FROM books WHERE id=? LIMIT 1", new String[]{bookId});
        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    private static org.openleaf.reader.core.ReadingLedger.Kind parseKind(String value) {
        if (value == null) return null;
        try { return org.openleaf.reader.core.ReadingLedger.Kind.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (Exception ignored) { return null; }
    }

    /** 按天汇总（含零阅读的日期返回 0），最多返回 days 天，从 days-1 天前到今天。 */
    Map<String, Long> dailyTotals(int days) {
        Map<String, Long> raw = new LinkedHashMap<>();
        SQLiteDatabase db = database.getReadableDatabase();
        // 总时长必须同时含书与非书媒体，否则「分列合计 > 总时长」——0.20.0 修过的矛盾会重现
        Cursor cursor = db.rawQuery(
                "SELECT day, seconds FROM reading_time "
                        + "UNION ALL SELECT day, seconds FROM media_time", null);
        try {
            while (cursor.moveToNext()) {
                String day = cursor.getString(0);
                raw.put(day, (raw.containsKey(day) ? raw.get(day) : 0L) + cursor.getLong(1));
            }
        } finally {
            cursor.close();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            String day = today.minusDays(i).toString();
            result.put(day, raw.containsKey(day) ? raw.get(day) : 0L);
        }
        return result;
    }

    long totalSeconds() {
        return sum("SELECT COALESCE(SUM(seconds),0) FROM (SELECT seconds FROM reading_time "
                + "UNION ALL SELECT seconds FROM media_time)", null);
    }

    long todaySeconds() {
        String today = LocalDate.now().toString();
        return sum("SELECT COALESCE(SUM(seconds),0) FROM (SELECT seconds FROM reading_time WHERE day=? "
                + "UNION ALL SELECT seconds FROM media_time WHERE day=?)", new String[]{today, today});
    }

    private long sum(String sql, String[] args) {
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.rawQuery(sql, args);
        try {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        } finally {
            cursor.close();
        }
    }

    /** 连续阅读天数：今天读满则从今天往前数，否则从昨天往前数（今天未读不算中断）。 */
    int streakDays() {
        Map<String, Long> all = dailyTotals(400);
        LocalDate today = LocalDate.now();
        int start = 0;
        Long todayValue = all.get(today.toString());
        if (todayValue == null || todayValue < ACTIVE_DAY_SECONDS) start = 1;
        int streak = 0;
        for (int i = start; i < 400; i++) {
            String day = today.minusDays(i).toString();
            Long value = all.get(day);
            if (value != null && value >= ACTIVE_DAY_SECONDS) streak++;
            else break;
        }
        return streak;
    }

    int activeDays() {
        int count = 0;
        for (long seconds : dailyTotals(400).values()) if (seconds >= ACTIVE_DAY_SECONDS) count++;
        return count;
    }

    /** 清空全部统计：合计表（reading_time）与分列分段表（reading_sessions）必须一起清，否则分列恒留残值。 */
    void clearAll() {
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("reading_sessions", null, null);
            db.delete("reading_time", null, null);
            db.delete("media_time", null, null);
            db.delete("media_sources", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 每本书的累计时长，按降序返回（供统计页书籍榜使用）。 */
    List<BookTime> bookRanking(int limit) {
        List<BookTime> result = new ArrayList<>();
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT rt.book_id, COALESCE(SUM(rt.seconds),0) AS total, "
                + "COALESCE(b.title, rt.book_id) AS title FROM reading_time rt "
                + "LEFT JOIN books b ON b.id=rt.book_id GROUP BY rt.book_id ORDER BY total DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (cursor.moveToNext())
                result.add(new BookTime(cursor.getString(0), cursor.getString(2), cursor.getLong(1)));
        } finally {
            cursor.close();
        }
        return result;
    }

    static final class BookTime {
        final String bookId, title;
        final long seconds;
        BookTime(String bookId, String title, long seconds) {
            this.bookId = bookId;
            this.title = title;
            this.seconds = seconds;
        }
    }
}
