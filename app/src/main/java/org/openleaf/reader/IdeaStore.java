package org.openleaf.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared ideas collected when an imported review conflicts with a local one. */
public final class IdeaStore {
    static final class Idea {
        final String id, sharer, bookTitle, review;
        final String bookId;
        final long createdAt;

        Idea(String id, String sharer, String bookTitle, String review, String bookId, long createdAt) {
            this.id = id;
            this.sharer = sharer;
            this.bookTitle = bookTitle;
            this.review = review;
            this.bookId = bookId;
            this.createdAt = createdAt;
        }

        String label() { return sharer + " — " + bookTitle + " — " + review; }
    }

    private final LibraryDatabase database;

    IdeaStore(Context context) { database = LibraryDatabase.get(context); }

    List<Idea> list() {
        SQLiteDatabase db = database.getReadableDatabase();
        ArrayList<Idea> result = new ArrayList<Idea>();
        Cursor cursor = db.query("ideas",
                new String[]{"id", "sharer", "book_title", "review", "book_id", "created_at"},
                null, null, null, null, "created_at DESC");
        try {
            while (cursor.moveToNext()) {
                result.add(new Idea(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), cursor.getLong(5)));
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    String insert(String sharer, String bookTitle, String review, String bookId) {
        return insert(database.getWritableDatabase(), sharer, bookTitle, review, bookId);
    }

    static String insert(SQLiteDatabase db, String sharer, String bookTitle, String review, String bookId) {
        String id = UUID.randomUUID().toString();
        ContentValues row = new ContentValues();
        row.put("id", id);
        row.put("sharer", safe(sharer, "未知分享者"));
        row.put("book_title", safe(bookTitle, "未知书籍"));
        row.put("review", safe(review, ""));
        row.put("book_id", bookId);
        row.put("created_at", System.currentTimeMillis());
        db.insert("ideas", null, row);
        return id;
    }

    void delete(String id) {
        database.getWritableDatabase().delete("ideas", "id=?", new String[]{id});
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }
}
