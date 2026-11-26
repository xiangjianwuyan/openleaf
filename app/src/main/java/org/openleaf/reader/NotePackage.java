package org.openleaf.reader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openleaf.reader.core.DigestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Versioned JSON exchange for a book's notes, highlights and collected ideas. */
final class NotePackage {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_ANNOTATIONS = 500;
    private static final int MAX_TEXT = 10000;

    private NotePackage() { }

    static String exportJson(Context context, String bookId, String title) throws Exception {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("bookTitle", title == null ? "" : title);
        BookMetadataStore.Info info = new BookMetadataStore(context).get(bookId);
        root.put("review", info.review == null ? "" : info.review);
        JSONArray tags = new JSONArray();
        for (String tag : new BookStore(context).tags(bookId)) tags.put(tag);
        root.put("tags", tags);
        JSONArray annotations = new JSONArray();
        for (AnnotationStore.Record record : new AnnotationStore(context).list(bookId)) {
            JSONObject item = new JSONObject();
            item.put("chapter", record.anchor.chapter);
            item.put("start", record.anchor.start);
            item.put("end", record.anchor.end);
            item.put("spineIndex", record.anchor.spineIndex);
            item.put("sourceStart", record.anchor.sourceStart);
            item.put("quote", record.anchor.quote);
            item.put("type", record.type);
            item.put("color", record.color);
            item.put("note", record.note);
            annotations.put(item);
        }
        root.put("annotations", annotations);
        JSONArray ideas = new JSONArray();
        for (IdeaStore.Idea idea : new IdeaStore(context).list()) {
            if (!bookId.equals(idea.bookId)) continue;
            JSONObject item = new JSONObject();
            item.put("sharer", idea.sharer);
            item.put("bookTitle", idea.bookTitle);
            item.put("review", idea.review);
            ideas.put(item);
        }
        root.put("ideas", ideas);
        return root.toString(2);
    }

    /**
     * 导出为 Markdown / TXT / CSV。借鉴 Anx Reader (MIT) export_notes 与 Koodo export.ts 的列设计，
     * OpenLeaf 自行实现。CSV 使用 GBK 编码以兼容 Excel（见 exportCsvBytes）。
     */
    static String exportText(Context context, String bookId, String title, String format) throws Exception {
        BookMetadataStore.Info info = new BookMetadataStore(context).get(bookId);
        String review = info.review == null ? "" : info.review;
        String tags = join(new BookStore(context).tags(bookId), "、");
        java.util.List<AnnotationStore.Record> records =
                new AnnotationStore(context).list(bookId, AnnotationStore.SORT_CHAPTER, -1);
        StringBuilder out = new StringBuilder();
        if ("md".equals(format)) {
            out.append("# ").append(title == null ? "书籍" : title).append("\n\n");
            if (!review.isEmpty()) out.append("## 书评\n\n").append(review).append("\n\n");
            if (!tags.isEmpty()) out.append("**标签**：").append(tags).append("\n\n");
            out.append("## 标注与笔记\n\n");
            int lastChapter = -1;
            for (AnnotationStore.Record r : records) {
                if (r.anchor.chapter != lastChapter) {
                    lastChapter = r.anchor.chapter;
                    out.append("\n### 第 ").append(lastChapter + 1).append(" 章\n\n");
                }
                String kind = kindName(r);
                out.append("- [").append(kind).append("] ").append(r.anchor.quote);
                if (!r.note.isEmpty()) out.append("\n  > ").append(r.note);
                out.append("\n");
            }
        } else if ("txt".equals(format)) {
            out.append("书名：").append(title == null ? "书籍" : title).append("\n");
            if (!review.isEmpty()) out.append("书评：").append(review).append("\n");
            if (!tags.isEmpty()) out.append("标签：").append(tags).append("\n");
            out.append("\n—— 标注与笔记 ——\n\n");
            for (AnnotationStore.Record r : records) {
                out.append("[").append(kindName(r)).append(" 第").append(r.anchor.chapter + 1).append("章] ")
                        .append(r.anchor.quote).append("\n");
                if (!r.note.isEmpty()) out.append("    笔记：").append(r.note).append("\n");
            }
        } else if ("csv".equals(format)) {
            out.append("书名,作者,章节,类型,样式,原文,笔记,颜色,创建时间\n");
            String author = info.author == null ? "" : info.author;
            for (AnnotationStore.Record r : records) {
                out.append(csv(title)).append(',').append(csv(author)).append(',')
                        .append(r.anchor.chapter + 1).append(',').append(csv(kindName(r))).append(',')
                        .append(csv(r.style)).append(',').append(csv(r.anchor.quote)).append(',')
                        .append(csv(r.note)).append(',')
                        .append(String.format("#%06X", 0xFFFFFF & r.color)).append(',')
                        .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
                                .format(new java.util.Date(r.createdAt))).append('\n');
            }
        } else {
            throw new IllegalArgumentException("不支持的导出格式");
        }
        return out.toString();
    }

    /** CSV 以 GBK 写出，覆盖 exportText("csv") 的 UTF-8 结果，Excel 可直接打开。 */
    static byte[] exportCsvBytes(Context context, String bookId, String title) throws Exception {
        String csv = exportText(context, bookId, title, "csv");
        return csv.getBytes(java.nio.charset.Charset.forName("GBK"));
    }

    /**
     * 把想法区「提要」导出为 Markdown（纯函数：只读 Digest，不碰 Context/DB）。
     * 规则见 §5：# 书名 → 可选标签行 → 每章一行 - **title**：lead → 截断时末尾加说明。
     * 供 IdeasBookPane 导出按钮与纯 JVM 测试复用；现有三个 export 方法不动。
     */
    public static String exportDigestMarkdown(DigestBuilder.Digest digest) {
        StringBuilder out = new StringBuilder();
        if (digest == null) return out.toString();
        out.append("# ").append(digest.bookTitle == null ? "" : digest.bookTitle).append('\n');
        if (digest.tags != null && !digest.tags.isEmpty()) {
            out.append("**标签**：").append(join(digest.tags, "、")).append('\n');
        }
        if (digest.lines != null) {
            for (DigestBuilder.Line line : digest.lines) {
                if (line == null) continue;
                out.append("- **").append(line.title == null ? "" : line.title)
                        .append("**：").append(line.lead == null ? "" : line.lead).append('\n');
            }
        }
        if (digest.truncated) {
            out.append("> 仅显示前 ").append(digest.lines == null ? 0 : digest.lines.size())
                    .append(" 条提要\n");
        }
        return out.toString();
    }

    private static String kindName(AnnotationStore.Record r) {
        if (AnnotationStore.TYPE_BOOKMARK.equals(r.type)) return "书签";
        if (AnnotationStore.STYLE_UNDERLINE.equals(r.style)) return "下划线";
        if (AnnotationStore.STYLE_STRIKE.equals(r.style)) return "删除线";
        return "高亮";
    }

    private static String join(java.util.List<String> items, String sep) {
        return items == null ? "" : String.join(sep, items);
    }

    private static String csv(String value) {
        if (value == null) return "";
        String v = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        if (v.contains(",") || v.contains("\""))
            return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    /**
     * 导入结果：
     * imported=成功写入条数；failed=写库失败条数；
     * truncated=超单次上限（MAX_ANNOTATIONS）未导入条数。
     * 格式不合法被跳过的项三者都不计。
     */
    static final class ImportResult {
        final int imported;
        final int failed;
        final int truncated;
        ImportResult(int imported, int failed, int truncated) {
            this.imported = imported;
            this.failed = failed;
            this.truncated = truncated;
        }
    }

    /** 兼容旧调用：只返回成功条数。 */
    static int importAnnotations(Context context, String bookId, String json, int chapterCount) throws Exception {
        return importAnnotationsResult(context, bookId, json, chapterCount).imported;
    }

    /** Imports only annotations. Reviews/tags/ideas in the file are never written back. */
    static ImportResult importAnnotationsResult(Context context, String bookId, String json, int chapterCount)
            throws Exception {
        if (json == null || json.isEmpty()) throw new Exception("笔记文件为空");
        JSONObject root = new JSONObject(json);
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION)
            throw new Exception("不支持的笔记文件格式");
        JSONArray array = root.optJSONArray("annotations");
        if (array == null) throw new Exception("笔记文件没有标注列表");
        final int count = Math.min(array.length(), MAX_ANNOTATIONS);
        // 上限之外的条数不静默丢弃：数量原样上报，由 UI 如实告知
        final int truncated = Math.max(0, array.length() - MAX_ANNOTATIONS);
        AnnotationStore store = new AnnotationStore(context);
        int imported = 0, failed = 0;
        for (int i = 0; i < count; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            int chapter = item.optInt("chapter", -1);
            int start = item.optInt("start", -1);
            int end = item.optInt("end", -1);
            int spine = item.optInt("spineIndex", -1);
            int source = item.optInt("sourceStart", -1);
            String quote = item.optString("quote", "");
            String type = item.optString("type", AnnotationStore.TYPE_HIGHLIGHT);
            String note = item.optString("note", "");
            int color = item.optInt("color", 0xFFFFE6A3);
            if (chapter < 0 || chapterCount > 0 && chapter >= chapterCount
                    || start < 0 || end <= start || quote.length() > MAX_TEXT || note.length() > MAX_TEXT)
                continue;
            if (!AnnotationStore.TYPE_HIGHLIGHT.equals(type) && !AnnotationStore.TYPE_BOOKMARK.equals(type))
                type = AnnotationStore.TYPE_HIGHLIGHT;
            AnnotationStore.Anchor anchor = new AnnotationStore.Anchor(chapter, start, end, spine, source, quote);
            // insert 写库失败返回 null：必须计入 failed，不能假装导入成功
            if (store.insert(bookId, anchor, type, color, note) == null) failed++;
            else imported++;
        }
        return new ImportResult(imported, failed, truncated);
    }
}
