package org.openleaf.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.CancellationSignal;

import org.openleaf.reader.core.ChapterDiff;
import org.openleaf.reader.core.OnlineChapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 在线书追更检查：真的抓一次远端目录，比较章节 URL 集合。
 *
 * <p>与 {@link SourceUpdateChecker} 的分工：那边只比较「书源规则版本」，回答的是
 * 「规则变没变」；这里回答「作者有没有加新章」。规则没变也可能追更，规则变了也可能
 * 目录没变，两者不能互相替代。网络抓取由调用方放在后台线程执行。
 */
public final class ChapterUpdateChecker {

    public enum Status {
        NEW_CHAPTERS,   // 远端出现新章节
        UP_TO_DATE,     // 目录一致
        RULE_CHANGED,   // 规则已变，需先重新解析才能可靠比对
        SOURCE_MISSING,
        NO_META,
        ERROR
    }

    public static final class Result {
        public final String bookId, title, detail;
        public final Status status;
        public final int localCount, remoteCount, added;
        Result(String bookId, String title, Status status, String detail,
               int localCount, int remoteCount, int added) {
            this.bookId = bookId;
            this.title = title;
            this.status = status;
            this.detail = detail;
            this.localCount = localCount;
            this.remoteCount = remoteCount;
            this.added = added;
        }
        public boolean canReparse() { return status == Status.NEW_CHAPTERS || status == Status.RULE_CHANGED; }
    }

    private static final String PREFS = "reader";
    private static final String PREFIX_ADDED = "online_added_";
    private static final String PREFIX_REMOTE = "online_remote_count_";

    private ChapterUpdateChecker() { }

    /** 抓远端目录并与本地清单比对；会写入待更新的角标计数。 */
    public static Result check(Context context, OnlineBookStore.OnlineBook binding,
                               CancellationSignal signal) {
        if (binding == null) return new Result("", "", Status.NO_META, "未找到在线书绑定", 0, 0, 0);
        String title = binding.bookId;
        BookStore store = new BookStore(context);
        BookStore.Book record = null;
        Uri sourceUri = null;
        try {
            record = store.find(binding.bookId);
            if (record != null) sourceUri = store.sourceUri(record);
        } catch (java.io.IOException failed) {
            return new Result(binding.bookId, title, Status.ERROR, "读取书籍信息失败", 0, 0, 0);
        }
        if (record != null && record.title != null) title = record.title;

        SourceUpdateChecker.CheckResult rule = new SourceUpdateChecker(context).check(binding);
        if (rule.status == SourceUpdateChecker.Status.SOURCE_MISSING)
            return store(context, binding.bookId, title, Status.SOURCE_MISSING, "书源已移除", 0, 0, 0);
        if (rule.status == SourceUpdateChecker.Status.NO_META)
            return store(context, binding.bookId, title, Status.NO_META, "旧版在线书无规则记录", 0, 0, 0);
        if (rule.status == SourceUpdateChecker.Status.RULE_CHANGED)
            return store(context, binding.bookId, title, Status.RULE_CHANGED,
                    "书源规则已变化，需先重新解析", 0, 0, 0);

        if (sourceUri == null || sourceUri.toString().isEmpty())
            return store(context, binding.bookId, title, Status.ERROR, "缺少原书链接，无法抓取目录", 0, 0, 0);

        LegadoClient.Source source = new SourceStore(context).find(binding.sourceId);
        if (source == null)
            return store(context, binding.bookId, title, Status.SOURCE_MISSING, "书源不存在", 0, 0, 0);

        try {
            if (signal != null) signal.throwIfCanceled();
            LegadoClient.Book remoteBook = new LegadoClient.Book(
                    title, "", sourceUri.toString(), "", "", sourceUri.toString());
            List<LegadoClient.Chapter> remote =
                    new LegadoClient().toc(source, remoteBook, signal);
            List<OnlineChapter.Entry> local =
                    new OnlineChapterStore(context).chapters(binding.remoteMeta);
            List<String> localUrls = new ArrayList<>();
            for (OnlineChapter.Entry entry : local) localUrls.add(entry.url);
            List<String> remoteUrls = new ArrayList<>();
            for (LegadoClient.Chapter chapter : remote) remoteUrls.add(chapter.url);
            int added = ChapterDiff.addedCount(localUrls, remoteUrls);
            Status status = added > 0 ? Status.NEW_CHAPTERS : Status.UP_TO_DATE;
            String detail = added > 0
                    ? "远端目录新增 " + added + " 章（" + localUrls.size() + " → " + remoteUrls.size() + "）"
                    : "目录一致（共 " + remoteUrls.size() + " 章）";
            return store(context, binding.bookId, title, status, detail,
                    localUrls.size(), remoteUrls.size(), added);
        } catch (android.os.OperationCanceledException canceled) {
            return new Result(binding.bookId, title, Status.ERROR, "检查已取消", 0, 0, 0);
        } catch (Exception failed) {
            String message = failed.getMessage();
            return new Result(binding.bookId, title, Status.ERROR,
                    "抓取目录失败" + (message == null || message.isEmpty() ? "" : "：" + message), 0, 0, 0);
        }
    }

    private static Result store(Context context, String bookId, String title, Status status, String detail,
                                int local, int remote, int added) {
        SharedPreferences prefs = prefs(context);
        if (prefs != null) {
            prefs.edit().putInt(PREFIX_ADDED + bookId, added)
                    .putInt(PREFIX_REMOTE + bookId, remote).apply();
        }
        return new Result(bookId, title, status, detail, local, remote, added);
    }

    /** 全部在线书逐本检查（调用方需保证在后台线程）。 */
    public static List<Result> checkAll(Context context, CancellationSignal signal) {
        List<Result> result = new ArrayList<>();
        for (OnlineBookStore.OnlineBook binding : new OnlineBookStore(context).allBindings()) {
            if (signal != null && signal.isCanceled()) break;
            result.add(check(context, binding, signal));
        }
        return result;
    }

    /** 书架角标：上一次检查发现的新章数。 */
    public static int pendingCount(Context context, String bookId) {
        SharedPreferences prefs = prefs(context);
        return prefs == null || bookId == null ? 0 : prefs.getInt(PREFIX_ADDED + bookId, 0);
    }

    /** 重新解析后角标清零，避免「已经追完了还一直显示有更新」。 */
    public static void clearPending(Context context, String bookId) {
        SharedPreferences prefs = prefs(context);
        if (prefs != null && bookId != null) prefs.edit().putInt(PREFIX_ADDED + bookId, 0).apply();
    }

    private static SharedPreferences prefs(Context context) {
        if (context == null) return null;
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String describe(Result result) {
        if (result == null) return "未找到在线书绑定";
        return result.title + " · " + result.detail;
    }
}
