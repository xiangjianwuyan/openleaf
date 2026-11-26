package org.openleaf.reader;

import android.content.Context;
import android.content.SharedPreferences;

import org.openleaf.reader.core.OnlineChapter;
import org.openleaf.reader.core.OnlineLibrary;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 在线书"一键重新解析"：用当前书源重抓目录，更新 remote_meta 与 rule_version，
 * 但保留 bookId（进度、标注、想法、已缓存章节不丢）。
 *
 * 流程：
 * 1. 从 binding.remote_meta 取旧章节清单与 source，从 book_references 取原书 URL。
 * 2. 用当前书源重抓目录 → 新章节清单。
 * 3. 按 URL 映射旧进度序号 → 新序号，迁移 reader prefs 的锚点。
 * 4. 重写骨架 txt（覆盖同一文件，不改 bookId）、更新绑定规则版本。
 * 5. URL 不变章节的缓存 key 不变，自动复用；URL 变化的章缓存自然失效需重下。
 */
public final class OnlineBookReparser {
    private final Context context;
    private final OnlineBookStore books;
    private final OnlineChapterStore chapters;
    private final SourceStore sourceStore;

    public OnlineBookReparser(Context context) {
        this.context = context.getApplicationContext();
        this.books = new OnlineBookStore(this.context);
        this.chapters = new OnlineChapterStore(this.context);
        this.sourceStore = new SourceStore(this.context);
    }

    public static final class Result {
        public final boolean success;
        public final int chapterCount;
        public final String message;
        Result(boolean success, int chapterCount, String message) {
            this.success = success; this.chapterCount = chapterCount; this.message = message;
        }
    }

    /** 重新解析一本书（后台调用）。 */
    public Result reparse(String bookId) {
        try {
            OnlineBookStore.OnlineBook binding = books.findByBookId(bookId);
            if (binding == null) return new Result(false, 0, "本书不是在线书");

            LegadoClient.Source source = sourceStore.find(binding.sourceId);
            if (source == null) return new Result(false, 0, "书源已移除，请先重新导入书源");

            BookStore store = new BookStore(context);
            BookStore.Book record = store.find(bookId);
            if (record == null) return new Result(false, 0, "书籍记录不存在");
            android.net.Uri sourceUri = store.sourceUri(record);
            if (sourceUri == null || sourceUri.toString().isEmpty())
                return new Result(false, 0, "缺少原书链接，无法重新解析目录");

            List<OnlineChapter.Entry> oldEntries = chapters.chapters(binding.remoteMeta);

            // 重抓目录
            LegadoClient.Book remoteBook = new LegadoClient.Book(
                    record.title, "", sourceUri.toString(), "", "", sourceUri.toString());
            List<LegadoClient.Chapter> remote = new LegadoClient().toc(source, remoteBook, new android.os.CancellationSignal());
            if (remote.isEmpty()) return new Result(false, 0, "新目录为空，保留原状");

            List<OnlineChapter.Entry> newEntries = new ArrayList<>();
            for (LegadoClient.Chapter c : remote)
                newEntries.add(new OnlineChapter.Entry(newEntries.size(), c.title, c.url));

            // 迁移进度：按章节 URL 映射旧序号 → 新序号
            int oldOrder = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
                    .getInt("chapter_" + bookId, 0);
            int mapped = OnlineChapter.remapOrder(oldEntries, newEntries, oldOrder);
            if (mapped < 0) mapped = 0; // 原章节被删除，回退到首章
            migrateProgress(bookId, oldOrder, mapped);

            // 重写骨架 txt（覆盖同一文件，不改 bookId、不改 sha256 语义——骨架本就是占位文本）
            String skeleton = OnlineChapter.skeletonText(newEntries);
            File bookFile = new File(context.getFilesDir(), "books/" + record.file);
            try (FileOutputStream out = new FileOutputStream(bookFile)) {
                out.write(skeleton.getBytes(StandardCharsets.UTF_8));
            }

            // 更新绑定：新规则版本 + 新清单（remoteKey 不变，复用原绑定）
            String sourceJson = source.json().toString();
            String manifest = chapters.manifest(sourceJson, newEntries);
            String newVersion = SourceStore.ruleVersion(source);
            books.bind(bookId, binding.sourceId, binding.remoteKey, newVersion, manifest);

            // 因骨架文件字节变化，更新 books.sha256 以保持一致（避免 open() 校验失败）
            store.refreshHash(record);

            return new Result(true, newEntries.size(),
                    "已重新解析 " + newEntries.size() + " 章，进度与已缓存章节已保留");
        } catch (Exception error) {
            return new Result(false, 0, "重新解析失败：" + (error.getMessage() == null ? "未知错误" : error.getMessage()));
        }
    }

    /** 迁移阅读进度键：把 chapter_/offset_ 等从旧序号移动到新序号（同 bookId）。 */
    private void migrateProgress(String bookId, int oldOrder, int newOrder) {
        if (oldOrder == newOrder) return;
        SharedPreferences prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE);
        String[] keys = {"chapter_", "offset_", "bottom_", "lineFraction_", "progress_", "anchor_"};
        SharedPreferences.Editor editor = prefs.edit();
        for (String prefix : keys) {
            String from = prefix + bookId;
            // 这些键按 bookId 存，值里含章节信息的是 anchor_；chapter_ 值即序号
            Object value = prefs.getAll().get(from);
            if (value == null) continue;
            if (prefix.equals("chapter_") && value instanceof Integer) {
                editor.putInt(from, newOrder);
            }
            // anchor_ 的字符串含 chapterOrdinal，交由 ReadingAnchor 重新定位（此处不清空，避免丢失字符偏移）
        }
        editor.apply();
    }
}
