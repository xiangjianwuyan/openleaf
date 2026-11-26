package org.openleaf.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 持久标注：结构锚点定位 + 多色多样式。
 * 借鉴 Anx Reader (MIT) 的 5 色 2 样式模型、KOReader 的统一 annotation 模型（样式/笔记/时间），
 * 由 OpenLeaf 自行实现。
 */
public final class AnnotationStore {
    static final String TYPE_HIGHLIGHT = "highlight";
    static final String TYPE_BOOKMARK = "bookmark";

    // 样式借鉴 Anx Reader：高亮 / 下划线 / 删除线；书签沿用 type=bookmark
    static final String STYLE_HIGHLIGHT = "highlight";
    static final String STYLE_UNDERLINE = "underline";
    static final String STYLE_STRIKE = "strikethrough";
    static final String[] STYLES = {STYLE_HIGHLIGHT, STYLE_UNDERLINE, STYLE_STRIKE};

    // 5 色预设（Anx Reader 蓝红绿紫金 + OpenLeaf 原生暖黄高亮）
    static final int[] COLORS = {
            0xFF66CCFF, 0xFFFF6B6B, 0xFF55C98A, 0xFFB57BEA, 0xFFE8B33A
    };
    static final String[] COLOR_NAMES = {"蓝", "红", "绿", "紫", "金"};

    static final int SORT_CHAPTER = 0, SORT_TIME = 1;

    static final class Anchor {
        final int chapter;
        final int start;
        final int end;
        final int spineIndex;
        final int sourceStart;
        final String quote;

        Anchor(int chapter, int start, int end, int spineIndex, int sourceStart, String quote) {
            this.chapter = chapter;
            this.start = start;
            this.end = end;
            this.spineIndex = spineIndex;
            this.sourceStart = sourceStart;
            this.quote = quote == null ? "" : quote;
        }

        String serialize() {
            return chapter + "|" + start + "|" + end + "|" + spineIndex + "|" + sourceStart + "|"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(quote.getBytes(StandardCharsets.UTF_8));
        }

        static Anchor parse(String raw) {
            if (raw == null) return null;
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 6) return null;
            try {
                String quote = new String(Base64.getUrlDecoder().decode(parts[5]), StandardCharsets.UTF_8);
                return new Anchor(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]), quote);
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }

    static final class Record {
        final String id;
        final String type;
        final int color;
        final String note;
        final String style;
        final long createdAt;
        final long updatedAt;
        final Anchor anchor;

        Record(String id, String type, int color, String note, String style,
               long createdAt, long updatedAt, Anchor anchor) {
            this.id = id;
            this.type = type;
            this.color = color;
            this.note = note;
            this.style = style == null || style.isEmpty() ? STYLE_HIGHLIGHT : style;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.anchor = anchor;
        }
    }

    private final LibraryDatabase database;

    AnnotationStore(Context context) { database = LibraryDatabase.get(context); }

    /** sortBy: SORT_CHAPTER 按章节+位置；SORT_TIME 按修改时间。colorFilter<0 表示不过滤。 */
    List<Record> list(String bookId, int sortBy, int colorFilter) {
        SQLiteDatabase db = database.getReadableDatabase();
        ArrayList<Record> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id,type,color,note,style,created_at,updated_at,anchor "
                + "FROM annotations WHERE book_id=?");
        List<String> args = new ArrayList<>();
        args.add(bookId);
        if (colorFilter >= 0) {
            sql.append(" AND color=?");
            args.add(String.valueOf(colorFilter));
        }
        sql.append(sortBy == SORT_TIME ? " ORDER BY updated_at DESC" : " ORDER BY created_at DESC");
        Cursor cursor = db.rawQuery(sql.toString(), args.toArray(new String[0]));
        try {
            while (cursor.moveToNext()) {
                Anchor anchor = Anchor.parse(cursor.getString(7));
                if (anchor == null) continue;
                result.add(new Record(cursor.getString(0), cursor.getString(1), cursor.getInt(2),
                        cursor.getString(3), cursor.getString(4), cursor.getLong(5), cursor.getLong(6), anchor));
            }
        } finally {
            cursor.close();
        }
        if (sortBy == SORT_CHAPTER) {
            result.sort((a, b) -> {
                int c = Integer.compare(a.anchor.chapter, b.anchor.chapter);
                return c != 0 ? c : Integer.compare(a.anchor.start, b.anchor.start);
            });
        }
        return result;
    }

    List<Record> list(String bookId) {
        return list(bookId, SORT_TIME, -1);
    }

    /** 写入一条标注；成功返回新 id，写库失败（insert 返回 -1）返回 null，由调用方如实提示。 */
    String insert(String bookId, Anchor anchor, String type, int color, String note, String style) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("book_id", bookId);
        values.put("anchor", anchor.serialize());
        values.put("type", type);
        values.put("color", color);
        values.put("note", note == null ? "" : note);
        values.put("style", style == null ? STYLE_HIGHLIGHT : style);
        values.put("created_at", now);
        values.put("updated_at", now);
        long row = database.getWritableDatabase().insert("annotations", null, values);
        return row == -1L ? null : id;
    }

    /** 兼容旧调用（无样式参数）。 */
    String insert(String bookId, Anchor anchor, String type, int color, String note) {
        return insert(bookId, anchor, type, color, note, STYLE_HIGHLIGHT);
    }

    /** @return true 表示确有行被更新；false 表示标注已不存在或写库失败。 */
    boolean update(String annotationId, String note, Integer color, String style) {
        ContentValues values = new ContentValues();
        if (note != null) values.put("note", note);
        if (color != null) values.put("color", color);
        if (style != null) values.put("style", style);
        values.put("updated_at", System.currentTimeMillis());
        return database.getWritableDatabase()
                .update("annotations", values, "id=?", new String[]{annotationId}) > 0;
    }

    /** @return true 表示确有行被删除；false 表示标注已不存在或删库失败。 */
    boolean delete(String annotationId) {
        return database.getWritableDatabase()
                .delete("annotations", "id=?", new String[]{annotationId}) > 0;
    }
}
