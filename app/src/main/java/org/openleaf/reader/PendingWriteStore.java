package org.openleaf.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.openleaf.reader.core.ContentContracts;
import org.openleaf.reader.core.PendingWriteReconciler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 持久写入意图账本：写前 PREPARED，成功后 COMMITTED，确认后 ACK，失败 FAILED。 */
public final class PendingWriteStore {
    static final String PREPARED = "PREPARED", COMMITTED = "COMMITTED",
            ACKNOWLEDGED = "ACKNOWLEDGED", FAILED = "FAILED", CONFLICT = "CONFLICT";

    private final LibraryDatabase database;

    public PendingWriteStore(Context context) { database = LibraryDatabase.get(context); }

    public static final class Record {
        final String id, targetUri, documentKey, expectedOldSha, intendedSha, state, error;
        final long size;
        final boolean replaceOriginal;
        final String bookId;

        public Record(String id, String targetUri, String documentKey, String expectedOldSha, String intendedSha,
               long size, boolean replaceOriginal, String bookId, String state, String error) {
            this.id = id; this.targetUri = targetUri; this.documentKey = documentKey;
            this.expectedOldSha = expectedOldSha; this.intendedSha = intendedSha; this.size = size;
            this.replaceOriginal = replaceOriginal; this.bookId = bookId; this.state = state; this.error = error;
        }

        PendingWriteReconciler.PendingWrite toPending() {
            return new PendingWriteReconciler.PendingWrite(targetUri, expectedOldSha, intendedSha, size, bookId);
        }
    }

    public String prepare(String targetUri, String documentKey, byte[] oldHash, byte[] intendedHash,
                   long size, boolean replaceOriginal, String bookId) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        ContentValues row = new ContentValues();
        row.put("id", id);
        row.put("target_uri", targetUri == null ? "" : targetUri);
        row.put("document_key", documentKey == null ? "" : documentKey);
        row.put("expected_old_sha", oldHash == null ? "" : ContentContracts.toHex(oldHash));
        row.put("intended_sha", intendedHash == null ? "" : ContentContracts.toHex(intendedHash));
        row.put("size", size);
        row.put("replace_original", replaceOriginal ? 1 : 0);
        row.put("book_id", bookId);
        row.put("state", PREPARED);
        row.put("created_at", now); row.put("updated_at", now);
        database.getWritableDatabase().insert("pending_writes", null, row);
        return id;
    }

    public void updateState(String id, String state, String error) {
        if (id == null) return;
        ContentValues values = new ContentValues();
        values.put("state", state);
        values.put("error", error);
        values.put("updated_at", System.currentTimeMillis());
        database.getWritableDatabase().update("pending_writes", values, "id=?", new String[]{id});
    }

    public void acknowledge(String id) {
        if (id == null) return;
        database.getWritableDatabase().delete("pending_writes", "id=?", new String[]{id});
    }

    public List<Record> unfinished() {
        List<Record> result = new ArrayList<>();
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.query("pending_writes", null, "state IN (?,?)",
                new String[]{PREPARED, COMMITTED}, null, null, "updated_at DESC");
        try {
            while (cursor.moveToNext()) result.add(fromCursor(cursor));
        } finally { cursor.close(); }
        return result;
    }

    List<Record> all() {
        List<Record> result = new ArrayList<>();
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.query("pending_writes", null, null, null, null, null, "updated_at DESC");
        try {
            while (cursor.moveToNext()) result.add(fromCursor(cursor));
        } finally { cursor.close(); }
        return result;
    }

    private static Record fromCursor(Cursor c) {
        return new Record(
                c.getString(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("target_uri")),
                c.getString(c.getColumnIndexOrThrow("document_key")),
                c.getString(c.getColumnIndexOrThrow("expected_old_sha")),
                c.getString(c.getColumnIndexOrThrow("intended_sha")),
                c.getLong(c.getColumnIndexOrThrow("size")),
                c.getInt(c.getColumnIndexOrThrow("replace_original")) == 1,
                c.getString(c.getColumnIndexOrThrow("book_id")),
                c.getString(c.getColumnIndexOrThrow("state")),
                c.getString(c.getColumnIndexOrThrow("error")));
    }
}
