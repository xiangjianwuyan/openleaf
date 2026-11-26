package org.openleaf.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.openleaf.reader.core.ContentContracts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 内容版本登记：把"身份（book_id）—位置（asset_key）—版本（sha256）"分开记录。
 * 编辑覆盖后登记新版本并同步更新 books.sha256，修复"重载因哈希失配失败"的链路断点。
 */
public final class RevisionStore {
    private final LibraryDatabase database;

    public RevisionStore(Context context) { database = LibraryDatabase.get(context); }

    /** 登记一次已验证版本；同时更新 books.sha256 与 book_sources 的引用。 */
    public void register(String bookId, String assetKey, ContentContracts.Revision revision, String sourceUri) {
        if (bookId == null || revision == null) return;
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues row = new ContentValues();
            row.put("id", UUID.randomUUID().toString());
            row.put("book_id", bookId);
            row.put("asset_key", assetKey == null ? "primary" : assetKey);
            row.put("sha256", revision.sha256);
            row.put("size", revision.size);
            row.put("source_uri", sourceUri == null ? "" : sourceUri);
            row.put("reason", "edit");
            row.put("created_at", System.currentTimeMillis());
            db.insert("asset_revisions", null, row);
            // 关键：让 books.sha256 反映当前资产内容，否则 BookStore.open() 的校验必然失败
            ContentValues book = new ContentValues();
            book.put("sha256", revision.sha256);
            db.update("books", book, "id=?", new String[]{bookId});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** 仅登记书包导入时的初始版本（不标 edit）。 */
    public void registerImported(String bookId, String assetKey, ContentContracts.Revision revision) {
        if (bookId == null || revision == null) return;
        ContentValues row = new ContentValues();
        row.put("id", UUID.randomUUID().toString());
        row.put("book_id", bookId);
        row.put("asset_key", assetKey == null ? "primary" : assetKey);
        row.put("sha256", revision.sha256);
        row.put("size", revision.size);
        row.put("reason", "import");
        row.put("created_at", System.currentTimeMillis());
        database.getWritableDatabase().insert("asset_revisions", null, row);
    }

    public List<ContentContracts.Revision> history(String bookId) {
        List<ContentContracts.Revision> result = new ArrayList<>();
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.query("asset_revisions", new String[]{"asset_key", "sha256", "size", "created_at"},
                "book_id=?", new String[]{bookId}, null, null, "created_at DESC");
        try {
            while (cursor.moveToNext())
                result.add(new ContentContracts.Revision(cursor.getString(0), cursor.getString(1),
                        cursor.getLong(2), cursor.getLong(3)));
        } finally { cursor.close(); }
        return result;
    }

    /** 当前指纹是否与最近一次登记版本一致（重扫校验用）。 */
    public boolean matchesCurrent(String bookId, String sha256) {
        List<ContentContracts.Revision> history = history(bookId);
        return !history.isEmpty() && history.get(0).sha256.equals(sha256);
    }
}
