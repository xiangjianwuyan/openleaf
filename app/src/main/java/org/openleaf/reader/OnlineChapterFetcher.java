package org.openleaf.reader;

import android.content.Context;
import android.os.CancellationSignal;

import org.openleaf.reader.core.OnlineChapter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在线书逐章下载器：从 remote_meta 的 source 规则 + 章节清单重建 LegadoClient，
 * 按章抓取正文写入 OnlineChapterStore 缓存。后台执行，回调主线程。
 */
public final class OnlineChapterFetcher {
    private final Context context;
    private final OnlineChapterStore store;
    private final OnlineBookStore onlineBooks;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public OnlineChapterFetcher(Context context) {
        this.context = context.getApplicationContext();
        this.store = new OnlineChapterStore(this.context);
        this.onlineBooks = new OnlineBookStore(this.context);
    }

    public interface Callback {
        void done(int order, boolean success, String message);
    }

    /** 下载单章（order 为章节序号），成功写入 chapter_cache。 */
    public void fetch(String bookId, int order, Callback callback) {
        worker.execute(() -> {
            boolean success = false;
            String message;
            try {
                OnlineBookStore.OnlineBook binding = onlineBooks.findByBookId(bookId);
                if (binding == null) throw new java.io.IOException("本书不是在线书");
                List<OnlineChapter.Entry> entries = store.chapters(binding.remoteMeta);
                if (order < 0 || order >= entries.size()) throw new java.io.IOException("章节序号无效");
                OnlineChapter.Entry entry = entries.get(order);

                // 重建书源（只取声明式子集，LegadoClient 已拒绝脚本/登录）
                String sourceJson = store.sourceJson(binding.remoteMeta);
                List<LegadoClient.Source> sources = LegadoClient.parseSources(sourceJson);
                if (sources.isEmpty()) throw new java.io.IOException("书源规则失效，请重新导入书源");
                LegadoClient.Source source = sources.get(0);

                // 抓取正文
                LegadoClient.Chapter remoteChapter = new LegadoClient.Chapter(entry.title, entry.url);
                LegadoClient.Content content = new LegadoClient().content(source, remoteChapter, new CancellationSignal());
                String text = content.text == null ? "" : content.text;
                if (text.trim().isEmpty()) throw new java.io.IOException("本章正文为空");
                String full = entry.title + "\n\n" + text;
                store.cache(bookId, entry.cacheKey(binding.sourceId), full, false);
                store.enforceQuota(bookId, 256L * 1024 * 1024); // 256MiB 配额，LRU 清理
                success = true;
                message = "已下载";
            } catch (Exception error) {
                message = error.getMessage() == null ? "下载失败" : error.getMessage();
            }
            final boolean ok = success;
            final String result = message;
            new android.os.Handler(context.getMainLooper()).post(() -> {
                if (callback != null) callback.done(order, ok, result);
            });
        });
    }

    /** 预取从 startOrder 起后 count 章（未缓存的），有界。
     *  网络门控：设置开启「仅 Wi-Fi 预取」时，计费网络（蜂窝/热点）不预取，只按需下载当前章。 */
    public void prefetch(String bookId, int startOrder, int count) {
        if (!prefetchAllowed()) return;
        OnlineBookStore.OnlineBook binding = onlineBooks.findByBookId(bookId);
        if (binding == null) return;
        List<OnlineChapter.Entry> entries = store.chapters(binding.remoteMeta);
        List<Integer> orders = OnlineChapter.prefetchOrders(entries, startOrder, Math.min(count, 3));
        for (int order : orders) {
            OnlineChapter.Entry entry = entries.get(order);
            if (store.cachedText(bookId, entry.cacheKey(binding.sourceId)) == null)
                fetch(bookId, order, null); // 有界：最多 3 章
        }
    }

    /**
     * 是否允许自动预取：读「仅 Wi-Fi 预取」开关（默认开），再按当前网络是否计费判定。
     * 判定逻辑在 core（OnlineLibrary.mayDownload），此处只取系统状态。
     */
    private boolean prefetchAllowed() {
        boolean wifiOnly = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
                .getBoolean("prefetchWifiOnly", true);
        if (!wifiOnly) return true;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true; // 拿不到状态时不阻断阅读
            boolean unmetered = !cm.isActiveNetworkMetered();
            return org.openleaf.reader.core.OnlineLibrary.mayDownload(true, unmetered);
        } catch (Exception ignored) {
            return true; // 权限/异常情况下不阻断，避免误伤
        }
    }

    public void shutdown() { worker.shutdownNow(); }
}
