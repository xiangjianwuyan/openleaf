package org.openleaf.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Application-scoped metadata database for local books. */
public final class LibraryDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "openleaf-library.db";
    private static final int DATABASE_VERSION = 18;
    private static final Map<String, LibraryDatabase> INSTANCES = new HashMap<>();

    /** 当前库结构版本。调试自检用它比对升级结果，避免把版本号写死在别处（写死必与常量脱节）。 */
    static int schemaVersion() { return DATABASE_VERSION; }

    static LibraryDatabase get(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        File databasePath = appContext.getDatabasePath(DATABASE_NAME);
        String key = databasePath.getAbsolutePath();
        synchronized (LibraryDatabase.class) {
            LibraryDatabase result = INSTANCES.get(key);
            if (result == null) {
                result = new LibraryDatabase(appContext);
                INSTANCES.put(key, result);
            }
            return result;
        }
    }

    private LibraryDatabase(Context appContext) {
        super(appContext, DATABASE_NAME, null, DATABASE_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "title TEXT NOT NULL,"
                + "format TEXT NOT NULL,"
                + "file TEXT NOT NULL,"
                + "added INTEGER NOT NULL,"
                + "sha256 TEXT,"
                + "order_index INTEGER NOT NULL"
                + ")");
        db.execSQL("CREATE TABLE library_meta (key TEXT PRIMARY KEY NOT NULL, value INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX books_added_idx ON books(added DESC)");
        // A partial, non-unique index accelerates import de-duplication without rewriting
        // or rejecting legacy rows that happened to contain duplicate files.
        db.execSQL("CREATE INDEX books_sha256_idx ON books(sha256) WHERE sha256 IS NOT NULL");
        db.execSQL("CREATE INDEX books_order_idx ON books(order_index ASC)");
        createFolderTables(db);
        createSourceTables(db);
        createTagTables(db);
        createBookMetadataTables(db);
        createReviewTables(db);
        createAnnotationTables(db);
        createIdeasTable(db);
        createReferenceTables(db);
        createReadingTimeTable(db);
        createJobTables(db);
        createRevisionTables(db);
        createPendingWriteTable(db);
        db.execSQL("ALTER TABLE annotations ADD COLUMN style TEXT NOT NULL DEFAULT 'highlight'");
        db.execSQL("ALTER TABLE annotations ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE folders ADD COLUMN display_name TEXT DEFAULT NULL");
        createOnlineTables(db);
        createReadingSessionTable(db);
        createMediaTables(db);
        addComicColumns(db); // 阶段 2-B：新库也走同一增量，保证与 v16→v17 升级后 schema 逐字一致
        createMediaItemsTable(db); // 阶段 3-B Y-1：统一媒体库影子表
    }

    /**
     * 阶段 3-B（设计 Y 第一步）：统一媒体库影子表 media_items。
     * books 的归档/标签/进度等语义字段仍留在 books（零回归），文件级字段双写到此表，
     * 为后续 Y-2 读切换与增量扫描提供快照。onCreate/onUpgrade 共用，保证 schema 一致。
     */
    private static void createMediaItemsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS media_items ("
                + "path TEXT PRIMARY KEY NOT NULL,"
                + "mtime INTEGER NOT NULL DEFAULT 0,"
                + "size INTEGER NOT NULL DEFAULT 0,"
                + "kind TEXT NOT NULL DEFAULT 'book',"
                + "title TEXT NOT NULL DEFAULT '',"
                + "duration INTEGER NOT NULL DEFAULT 0,"
                + "cover TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX IF NOT EXISTS media_items_kind_idx ON media_items(kind)");
    }

    /**
     * 阶段 2-B：把文字书与漫画统一到 books 表（Q2 路线 1）。
     * media_type 默认 'book' → 所有老行逐字不变；两列可空给漫画专用，文字书为 NULL。
     * 用独立方法让 onCreate 与 onUpgrade 共用同一 DDL，避免两处漂移。
     */
    private static void addComicColumns(SQLiteDatabase db) {
        // DDL 单一来源在 core/ComicMigration，onCreate 与 onUpgrade 共用，避免两处漂移
        for (String sql : org.openleaf.reader.core.ComicMigration.DDL) db.execSQL(sql);
    }

    /** 分列计时分段：event_key 稳定去重，kind 区分文本/漫画/音频。 */
    private static void createReadingSessionTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE reading_sessions ("
                + "event_key TEXT PRIMARY KEY NOT NULL, book_id TEXT NOT NULL, day TEXT NOT NULL, "
                + "kind TEXT NOT NULL, seconds INTEGER NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX reading_sessions_day_idx ON reading_sessions(day DESC)");
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion<1||newVersion!=DATABASE_VERSION||oldVersion>=newVersion)
            throw new IllegalStateException("不支持的书库数据库升级："+oldVersion+" → "+newVersion);
        if(oldVersion<2)createFolderTables(db);
        if(oldVersion<3)createSourceTables(db);
        if(oldVersion<4)db.execSQL("ALTER TABLE folders ADD COLUMN display_name TEXT DEFAULT NULL");
        if(oldVersion<5)createTagTables(db);
        if(oldVersion<6)createBookMetadataTables(db);
        if(oldVersion<7)createReviewTables(db);
        if(oldVersion<8)createReferenceTables(db);
        if(oldVersion<9)createAnnotationTables(db);
        if(oldVersion<10)createIdeasTable(db);
        if(oldVersion<11){
            createReadingTimeTable(db);
            db.execSQL("ALTER TABLE annotations ADD COLUMN style TEXT NOT NULL DEFAULT 'highlight'");
            db.execSQL("ALTER TABLE annotations ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
        }
        if(oldVersion<12){ createJobTables(db); createRevisionTables(db); }
        if(oldVersion<13) createPendingWriteTable(db);
        if(oldVersion<14) createOnlineTables(db);
        if(oldVersion<15) createReadingSessionTable(db);
        if(oldVersion<16) createMediaTables(db);
        if(oldVersion<17) addComicColumns(db);
        if(oldVersion<18) createMediaItemsTable(db);
    }

    /**
     * 非书媒体（按文件直接打开的漫画/音频）的归属与合计。
     *
     * <p>不给 source_id 加外键：这些 id 是 {@code MediaAttribution.id()} 生成的确定性哈希，
     * 跨设备稳定，恢复时不需要重映射；若指向 books(id) 就会和 reading_time 一样把非书媒体挡在门外。
     */
    private static void createMediaTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE media_sources ("
                + "id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, "
                + "title TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE media_time ("
                + "source_id TEXT NOT NULL, day TEXT NOT NULL, seconds INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, PRIMARY KEY(source_id, day))");
        db.execSQL("CREATE INDEX media_time_day_idx ON media_time(day DESC)");
    }

    /** 在线书绑定与章节缓存：sourceId+remoteKey 稳定标识，规则版本失配可提示重解析。 */
    private static void createOnlineTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE online_books ("
                + "book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,"
                + "source_id TEXT NOT NULL,remote_key TEXT NOT NULL,rule_version TEXT NOT NULL DEFAULT '',"
                + "remote_meta TEXT NOT NULL DEFAULT '',updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY(source_id, remote_key))");
        db.execSQL("CREATE INDEX online_books_book_idx ON online_books(book_id)");
        db.execSQL("CREATE TABLE chapter_cache ("
                + "book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,"
                + "chapter_key TEXT NOT NULL,state TEXT NOT NULL,cache_path TEXT NOT NULL DEFAULT '',"
                + "sha256 TEXT NOT NULL DEFAULT '',size INTEGER NOT NULL DEFAULT 0,"
                + "retained INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY(book_id, chapter_key))");
        db.execSQL("CREATE INDEX chapter_cache_state_idx ON chapter_cache(book_id, state)");
    }

    /** 持久写入意图：SAF 写与 SQLite 无法原子提交，用此表检测/修复中断。 */
    private static void createPendingWriteTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pending_writes ("
                + "id TEXT PRIMARY KEY NOT NULL,target_uri TEXT NOT NULL,document_key TEXT NOT NULL,"
                + "expected_old_sha TEXT NOT NULL,intended_sha TEXT NOT NULL,size INTEGER NOT NULL,"
                + "replace_original INTEGER NOT NULL DEFAULT 0,book_id TEXT,"
                + "state TEXT NOT NULL,error TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX pending_writes_state_idx ON pending_writes(state, updated_at DESC)");
    }

    /** 可恢复导入任务：Job → JobItem，逐项检查点。 */
    private static void createJobTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE jobs ("
                + "id TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,source_tree TEXT NOT NULL,"
                + "state TEXT NOT NULL,processed INTEGER NOT NULL,added INTEGER NOT NULL,"
                + "duplicates INTEGER NOT NULL,failed INTEGER NOT NULL,"
                + "created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE job_items ("
                + "job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,"
                + "seq INTEGER NOT NULL,uri TEXT NOT NULL,relative_path TEXT NOT NULL,"
                + "format TEXT NOT NULL,size INTEGER NOT NULL,state TEXT NOT NULL,"
                + "book_id TEXT,error TEXT,PRIMARY KEY(job_id,seq))");
        db.execSQL("CREATE INDEX jobs_state_idx ON jobs(state, updated_at DESC)");
    }

    /** 已验证内容版本：一个资产可有多个版本，SHA-256 是内容指纹而非条目 ID。 */
    private static void createRevisionTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE asset_revisions ("
                + "id TEXT PRIMARY KEY NOT NULL,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,"
                + "asset_key TEXT NOT NULL,sha256 TEXT NOT NULL,size INTEGER NOT NULL,"
                + "source_uri TEXT NOT NULL DEFAULT '',reason TEXT NOT NULL DEFAULT 'import',"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX asset_revisions_book_idx ON asset_revisions(book_id, created_at DESC)");
    }

    private static void createReadingTimeTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE reading_time ("
                + "book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,"
                + "day TEXT NOT NULL,seconds INTEGER NOT NULL,created_at INTEGER NOT NULL,"
                + "PRIMARY KEY(book_id,day))");
        db.execSQL("CREATE INDEX reading_time_day_idx ON reading_time(day DESC)");
    }

    private static void createIdeasTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE ideas ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "sharer TEXT NOT NULL,book_title TEXT NOT NULL,review TEXT NOT NULL,"
                + "book_id TEXT,created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX ideas_created_idx ON ideas(created_at DESC)");
    }

    private static void createAnnotationTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE annotations ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,"
                + "anchor TEXT NOT NULL,type TEXT NOT NULL,color INTEGER NOT NULL,"
                + "note TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX annotations_book_idx ON annotations(book_id, created_at DESC)");
    }

    private static void createReferenceTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE book_references (book_id TEXT PRIMARY KEY NOT NULL REFERENCES books(id) ON DELETE CASCADE,source_uri TEXT NOT NULL)");
    }

    private static void createReviewTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE book_reviews (book_id TEXT PRIMARY KEY NOT NULL REFERENCES books(id) ON DELETE CASCADE,review TEXT NOT NULL)");
    }

    private static void createBookMetadataTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE book_metadata ("
                + "book_id TEXT PRIMARY KEY NOT NULL REFERENCES books(id) ON DELETE CASCADE,"
                + "author TEXT NOT NULL DEFAULT '',description TEXT NOT NULL DEFAULT '',"
                + "cover_text TEXT NOT NULL DEFAULT '',cover_name TEXT NOT NULL DEFAULT '',"
                + "favorite INTEGER NOT NULL DEFAULT 0 CHECK(favorite IN (0,1)))");
    }

    private static void createTagTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE book_tags (book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,"
                + "tag_key TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(book_id,tag_key))");
    }

    private static void createSourceTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE book_sources (book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,"
                +"source_key TEXT NOT NULL,source_uri TEXT NOT NULL,name TEXT NOT NULL,"
                +"PRIMARY KEY(book_id,source_key))");
        db.execSQL("CREATE INDEX book_sources_key_idx ON book_sources(source_key)");
    }

    private static void createFolderTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE folders ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "parent_id TEXT REFERENCES folders(id),"
                + "name TEXT NOT NULL,"
                + "source_tree TEXT NOT NULL,"
                + "relative_path TEXT NOT NULL,"
                + "UNIQUE(source_tree, relative_path))");
        db.execSQL("CREATE INDEX folders_parent_idx ON folders(parent_id, name COLLATE NOCASE, id)");
        db.execSQL("CREATE TABLE book_folders ("
                + "book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,"
                + "folder_id TEXT NOT NULL REFERENCES folders(id),"
                + "PRIMARY KEY(book_id, folder_id))");
        db.execSQL("CREATE INDEX book_folders_folder_idx ON book_folders(folder_id, book_id)");
    }

    static ContentValues values(String id, String title, String format, String file, long added, String sha256) {
        ContentValues row = new ContentValues();
        row.put("id", id);
        row.put("title", title);
        row.put("format", format);
        row.put("file", file);
        row.put("added", added);
        if (sha256 == null) row.putNull("sha256"); else row.put("sha256", sha256);
        return row;
    }

    /**
     * 阶段 3-B Y-1 双写：books 插入后同步写一行 media_items 影子记录。
     * 从 books 的 ContentValues 提取文件级字段；size 暂记 0（后续扫描阶段补全），
     * mtime 用 added 兜底。失败不影响 books 主写入（影子表可随时重建）。
     */
    static void writeMediaItemShadow(android.content.Context context, SQLiteDatabase db, ContentValues book) {
        if (db == null || book == null) return;
        try {
            String path = book.getAsString("file");
            String id = book.getAsString("id");
            String format = book.getAsString("format");
            // 阶段 3-B 修复：新导入/引用模式的 books.file 可能为空，派生稳定 path 保证影子表必有行。
            // 与 importContent 的 committedFile 命名（id.format）一致，引用模式用 book://<id> 兜底。
            if (path == null || path.isEmpty()) {
                String ext = format == null || format.isEmpty() ? "bin" : format;
                path = (id == null || id.isEmpty()) ? "book://unknown" : ("book://" + id + "." + ext);
            }
            String title = book.getAsString("title");
            String mediaType = book.getAsString("media_type");
            String kind = mediaType != null && !mediaType.isEmpty() ? mediaType
                    : ("comic".equals(format) ? "comic" : "book");
            Long added = book.getAsLong("added");
            long size = 0L, mtime = added == null ? 0L : added;
            // 引用模式书 file 为空/相对名：优先读实际文件补全 size/mtime（快照三元组要真实）
            if (context != null && !path.startsWith("/")) {
                java.io.File f = new java.io.File(context.getFilesDir(), "books/" + path);
                if (f.isFile()) { size = f.length(); mtime = f.lastModified(); }
            }
            ContentValues row = new ContentValues();
            row.put("path", path);
            row.put("mtime", mtime);
            row.put("size", size);
            row.put("kind", kind);
            row.put("title", title == null ? "" : title);
            row.put("duration", 0L);
            row.put("cover", "");
            db.insertWithOnConflict("media_items", null, row, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (RuntimeException ignored) { /* 影子表失败绝不影响主写入 */ }
    }

    /**
     * 阶段 3-B Y-2：books 行 → Book 对象。文件级 title 改从 media_items 读（统一媒体库权威源）；
     * media_items 无对应行（老库/异常）时回退 books.title，保证零回归。
     * 双读不一致只写日志、不抛错——Y-2 观察期用，Y-3 去重后再移除回退。
     */
    static BookStore.Book toBook(SQLiteDatabase db, Cursor cursor) {
        String id = cursor.getString(cursor.getColumnIndexOrThrow("id"));
        String bookTitle = cursor.getString(cursor.getColumnIndexOrThrow("title"));
        String title = bookTitle;
        if (db != null && id != null) {
            try (Cursor m = db.query("media_items", new String[]{"title"}, "path LIKE ?",
                    new String[]{"book://" + id + ".%"}, null, null, null, "1")) {
                if (m.moveToFirst()) {
                    String mediaTitle = m.getString(0);
                    if (mediaTitle != null && !mediaTitle.isEmpty()) {
                        title = mediaTitle;
                        if (!mediaTitle.equals(bookTitle))
                            android.util.Log.w("OL-Y2", "title 双读不一致 id=" + id);
                    }
                }
            } catch (RuntimeException ignored) { title = bookTitle; }
        }
        return new BookStore.Book(id, title,
                cursor.getString(cursor.getColumnIndexOrThrow("format")),
                cursor.getString(cursor.getColumnIndexOrThrow("file")),
                cursor.getLong(cursor.getColumnIndexOrThrow("added")));
    }
}
