package org.openleaf.reader.core;

/**
 * 阶段 2-B DB v16→v17 迁移的 DDL 定义（纯 JVM，无 Android 依赖）。
 *
 * <p>把迁移 SQL 集中到这里，好处有二：①{@code LibraryDatabase} 的 onCreate/onUpgrade
 * 共用同一份 DDL，新库与升级库 schema 逐字一致；②core 单测可直接校验 DDL 文本，
 * 在没有 sqlite-jdbc 的前提下仍能锁住"默认 'book'、可空漫画列、索引"等关键语义，
 * 真正的 SQLite 行为由 QA 用 sqlite3 在 v16 老库上执行验证。</p>
 */
public final class ComicMigration {
    private ComicMigration() { }

    /** 目标库版本。 */
    public static final int VERSION = 17;
    /** 漫画在 media_type 中的取值；文字书恒为 'book'。 */
    public static final String MEDIA_BOOK = "book";
    public static final String MEDIA_COMIC = "comic";

    /** v16→v17 的完整 DDL（顺序固定）。 */
    public static final String[] DDL = {
            "ALTER TABLE books ADD COLUMN media_type TEXT NOT NULL DEFAULT 'book'",
            "ALTER TABLE books ADD COLUMN page_count INTEGER DEFAULT NULL",
            "ALTER TABLE books ADD COLUMN cover_entry TEXT DEFAULT NULL",
            "CREATE INDEX IF NOT EXISTS books_media_type_idx ON books(media_type)"
    };

    /** 老行是否保持文字书语义（默认 'book'、漫画列可空）。 */
    public static boolean keepsLegacyRowAsBook(String mediaType, Object pageCount, Object coverEntry) {
        return MEDIA_BOOK.equals(mediaType) && pageCount == null && coverEntry == null;
    }

    /** 媒体类型是否合法值。 */
    public static boolean validMediaType(String value) {
        return MEDIA_BOOK.equals(value) || MEDIA_COMIC.equals(value);
    }
}
