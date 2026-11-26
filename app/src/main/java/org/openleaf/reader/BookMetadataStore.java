package org.openleaf.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Editable library metadata only. Never modifies a book document, source grant or reading position. */
final class BookMetadataStore {
    private static final Pattern COVER_NAME = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg");
    private final Context app;
    private final LibraryDatabase helper;
    private final BookStore books;

    static final class Info {
        final BookStore.Book book;
        final String author, description, coverText, coverName, review;
        final boolean favorite;
        final List<String> tags, folderIds;

        Info(BookStore.Book book, String author, String description, String coverText, String coverName,
                boolean favorite, List<String> tags, List<String> folderIds, String review) {
            this.book = book;
            this.review = review;
            this.author = author;
            this.description = description;
            this.coverText = coverText;
            this.coverName = coverName;
            this.favorite = favorite;
            this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
            this.folderIds = Collections.unmodifiableList(new ArrayList<>(folderIds));
        }
    }

    static final class Card {
        final String author, coverText, coverName;
        final boolean favorite;
        Card(String author, String coverText, String coverName, boolean favorite) {
            this.author = author;
            this.coverText = coverText;
            this.coverName = coverName;
            this.favorite = favorite;
        }
    }

    BookMetadataStore(Context context) {
        Context application = context.getApplicationContext();
        this.app = application == null ? context : application;
        helper = LibraryDatabase.get(app);
        books = new BookStore(app);
    }

    void setCover(String id, String coverName) throws IOException {
        if (coverName == null || !COVER_NAME.matcher(coverName).matches())
            throw new IOException("封面文件名无效");
        SQLiteDatabase db = helper.getWritableDatabase();
        requireBook(db, id);
        ContentValues row = new ContentValues();
        row.put("cover_name", coverName);
        if (db.update("book_metadata", row, "book_id=?", new String[]{id}) == 0) {
            row.put("book_id", id);
            db.insert("book_metadata", null, row);
        }
    }

    Info get(String id) throws IOException {
        // BookStore owns the one-time legacy JSON migration; do not bypass it for existing users.
        books.find(id);
        SQLiteDatabase db = helper.getReadableDatabase();
        db.beginTransaction();
        try {
            BookStore.Book book = requireBook(db, id);
            String author = "", description = "", coverText = "", coverName = "";
            boolean favorite = false;
            try (Cursor row = db.query("book_metadata",
                    new String[]{"author", "description", "cover_text", "cover_name", "favorite"},
                    "book_id=?", new String[]{id}, null, null, null, "1")) {
                if (row.moveToFirst()) {
                    author = row.getString(0); description = row.getString(1);
                    coverText = row.getString(2); coverName = row.getString(3);
                    favorite = row.getInt(4) != 0;
                }
            }
            List<String> tags = new ArrayList<>(), folderIds = new ArrayList<>();
            try (Cursor rows = db.query("book_tags", new String[]{"name"}, "book_id=?",
                    new String[]{id}, null, null, "tag_key")) {
                while (rows.moveToNext()) tags.add(rows.getString(0));
            }
            try (Cursor rows = db.query("book_folders", new String[]{"folder_id"}, "book_id=?",
                    new String[]{id}, null, null, "folder_id")) {
                while (rows.moveToNext()) folderIds.add(rows.getString(0));
            }
            Info result = new Info(book, author, description, coverText, coverName, favorite, tags, folderIds, readReview(db, id));
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }

    /** Exactly one metadata query; absent IDs mean empty metadata on the caller's existing book. */
    Map<String, Card> cards() throws IOException {
        Map<String, Card> result = new HashMap<>();
        try (Cursor rows = helper.getReadableDatabase().query("book_metadata",
                new String[]{"book_id", "author", "cover_text", "cover_name", "favorite"},
                null, null, null, null, null)) {
            while (rows.moveToNext()) result.put(rows.getString(0), new Card(rows.getString(1),
                    rows.getString(2), rows.getString(3), rows.getInt(4) != 0));
        }
        return result;
    }

    void save(String id, String title, String author, String description, String coverText, String coverName,
            boolean favorite, List<String> tags, boolean changeFolder, String folderId) throws IOException {
        save(id, title, author, description, coverText, coverName, favorite, tags, changeFolder, folderId, get(id).review);
    }

    void save(String id, String title, String author, String description, String coverText, String coverName,
            boolean favorite, List<String> tags, boolean changeFolder, String folderId, String review) throws IOException {
        String checkedReview = BookReview.validate(review);
        String checkedTitle = text(title, "书名", 256, true, false);
        String checkedAuthor = text(author, "作者", 128, false, false);
        String checkedDescription = text(description, "简介", 8000, false, true);
        String checkedCoverText = text(coverText, "封面文字", 80, false, false);
        String checkedCoverName = checkCover(coverName);
        LinkedHashMap<String, String> checkedTags = tags(tags);
        books.find(id);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            requireBook(db, id);
            boolean actualFolder = changeFolder && folderId != null && !BookStore.UNCLASSIFIED.equals(folderId);
            if (actualFolder) requireFolder(db, folderId);

            ContentValues titleRow = new ContentValues(); titleRow.put("title", checkedTitle);
            if (db.update("books", titleRow, "id=?", new String[]{id}) != 1)
                throw new IOException("书籍已被移除，请返回书架刷新");

            ContentValues metadata = new ContentValues();
            metadata.put("book_id", id); metadata.put("author", checkedAuthor);
            metadata.put("description", checkedDescription); metadata.put("cover_text", checkedCoverText);
            metadata.put("cover_name", checkedCoverName); metadata.put("favorite", favorite ? 1 : 0);
            if (db.insertWithOnConflict("book_metadata", null, metadata, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                throw new IOException("无法保存书籍信息，请稍后重试");

            saveReview(db, id, checkedReview);
            db.delete("book_tags", "book_id=?", new String[]{id});
            for (Map.Entry<String, String> tag : checkedTags.entrySet()) {
                ContentValues row = new ContentValues(); row.put("book_id", id);
                row.put("tag_key", tag.getKey()); row.put("name", tag.getValue());
                db.insertOrThrow("book_tags", null, row);
            }

            if (changeFolder) {
                db.delete("book_folders", "book_id=?", new String[]{id});
                if (actualFolder) {
                    ContentValues row = new ContentValues(); row.put("book_id", id); row.put("folder_id", folderId);
                    db.insertOrThrow("book_folders", null, row);
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    static String readReview(SQLiteDatabase db, String id) {
        try (Cursor row = db.query("book_reviews", new String[]{"review"}, "book_id=?", new String[]{id}, null, null, null, "1")) {
            return row.moveToFirst() ? row.getString(0) : "";
        }
    }

    static void saveReview(SQLiteDatabase db, String id, String review) throws IOException {
        if (review.isEmpty()) { db.delete("book_reviews", "book_id=?", new String[]{id}); return; }
        ContentValues row = new ContentValues(); row.put("book_id", id); row.put("review", review);
        if (db.insertWithOnConflict("book_reviews", null, row, SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new IOException("无法保存书评，请稍后重试");
    }

    private static BookStore.Book requireBook(SQLiteDatabase db, String id) throws IOException {
        if (id == null || id.isEmpty()) throw new IOException("书籍标识无效");
        try (Cursor row = db.query("books", new String[]{"id", "title", "format", "file", "added"},
                "id=?", new String[]{id}, null, null, null, "1")) {
            if (row.moveToFirst()) return LibraryDatabase.toBook(db, row);
        }
        throw new IOException("书籍已移除，请返回书架刷新");
    }

    private static void requireFolder(SQLiteDatabase db, String id) throws IOException {
        try (Cursor row = db.query("folders", new String[]{"id"}, "id=?", new String[]{id},
                null, null, null, "1")) {
            if (!row.moveToFirst()) throw new IOException("所选分类已不存在，请重新选择");
        }
    }

    private String checkCover(String name) throws IOException {
        String value = name == null ? "" : name;
        if (value.isEmpty()) return "";
        if (!COVER_NAME.matcher(value).matches()) throw new IOException("封面文件名无效，请重新选择封面");
        File directory = new File(app.getFilesDir(), "covers");
        File file = new File(directory, value);
        if (!directory.getCanonicalPath().startsWith(app.getFilesDir().getCanonicalPath() + File.separator)
                || !file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator) || !file.isFile())
            throw new IOException("封面文件不存在，请重新选择封面");
        return value;
    }

    private static String text(String input, String label, int maximum, boolean required, boolean multiline)
            throws IOException {
        String result = input == null ? "" : input.trim();
        if (required && result.isEmpty()) throw new IOException(label + "不能为空");
        if (result.codePointCount(0, result.length()) > maximum)
            throw new IOException(label + "最多 " + maximum + " 个字符");
        for (int offset = 0; offset < result.length();) {
            int point = result.codePointAt(offset);
            if (Character.getType(point) == Character.SURROGATE
                    || Character.isISOControl(point) && !(multiline && (point == '\n' || point == '\r' || point == '\t'))
                    || !multiline && (Character.getType(point) == Character.LINE_SEPARATOR
                    || Character.getType(point) == Character.PARAGRAPH_SEPARATOR))
                throw new IOException(label + "包含不支持的控制字符");
            offset += Character.charCount(point);
        }
        return result;
    }

    private static LinkedHashMap<String, String> tags(List<String> labels) throws IOException {
        if (labels == null) throw new IOException("标签列表无效");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String name : labels) {
            if (name == null) throw new IOException("标签不能为空");
            String label = text(Normalizer.normalize(name, Normalizer.Form.NFKC), "标签", 32, true, false);
            String key = label.toLowerCase(Locale.ROOT);
            if (!result.containsKey(key)) result.put(key, label);
            if (result.size() > 24) throw new IOException("每本书最多可添加 24 个标签");
        }
        return result;
    }
}
