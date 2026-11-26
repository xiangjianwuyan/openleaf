package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 在线书逐章缓存的纯 JVM 数据契约（无 Android 依赖）。
 *
 * 在线书入库时不再抓整本正文，而是保存章节清单（title + remoteUrl）与
 * source 规则；阅读时按章缓存正文，未缓存的章按需下载。
 * JSON 序列化/反序列化由 Android 层（OnlineBookStore）用 org.json 处理。
 */
public final class OnlineChapter {
    private OnlineChapter() { }

    /** 章节清单项：序号 + 标题 + 远端 URL（稳定标识）。 */
    public static final class Entry {
        public final int order;
        public final String title;
        public final String url;
        public Entry(int order, String title, String url) {
            if (order < 0) throw new IllegalArgumentException("章节序号无效");
            this.order = order;
            this.title = title == null ? "第" + (order + 1) + "章" : title;
            this.url = url == null ? "" : url;
        }
        /** 缓存键：稳定用 remoteUrl 规范化，避免目录序号变化。 */
        public String cacheKey(String sourceId) {
            return OnlineLibrary.remoteKey(sourceId, null, url.isEmpty() ? title : url);
        }
    }

    public static final String PLACEHOLDER = "（本章尚未下载，联网后自动获取）";

    /** 骨架章节文本：让 LazyChapterBook 得到正确章节数/标题，正文标记为待下载。 */
    public static String skeletonText(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        if (entries == null || entries.isEmpty()) return "（无章节）\n";
        for (Entry entry : entries) {
            sb.append(entry.title).append('\n');
            sb.append(PLACEHOLDER).append("\n\n");
        }
        return sb.toString();
    }

    /** 预取计划：从当前章往后 N 章的序号列表（有界，不重复）。 */
    public static List<Integer> prefetchOrders(List<Entry> entries, int currentOrder, int count) {
        List<Integer> result = new ArrayList<>();
        if (entries == null || currentOrder < 0 || count <= 0) return result;
        for (int i = currentOrder + 1; i < entries.size() && result.size() < count; i++) result.add(i);
        return Collections.unmodifiableList(result);
    }

    /**
     * 重新解析后的进度映射：按章节 URL（稳定标识）把旧序号映射到新序号。
     * URL 仍存在的章节返回新序号；被移除的章节返回 -1，调用方据此回退到最近保留章节。
     * 这样即使书源规则更新导致目录顺序变化/插入章节，进度也不会错位。
     */
    public static int remapOrder(List<Entry> oldEntries, List<Entry> newEntries, int oldOrder) {
        if (oldEntries == null || newEntries == null || oldOrder < 0 || oldOrder >= oldEntries.size()) return -1;
        String url = oldEntries.get(oldOrder).url;
        if (url == null || url.isEmpty()) {
            // 无稳定 URL 时退化为标题匹配
            String title = oldEntries.get(oldOrder).title;
            for (int i = 0; i < newEntries.size(); i++)
                if (title.equals(newEntries.get(i).title)) return i;
            return -1;
        }
        for (int i = 0; i < newEntries.size(); i++)
            if (url.equals(newEntries.get(i).url)) return i;
        return -1;
    }

    /**
     * 缓存可达性：新清单里哪些章节 URL 与旧清单一致（缓存可复用）。
     * 返回新序号集合——这些章节的 chapter_cache 无需重下。
     */
    public static java.util.Set<Integer> reusableCacheOrders(List<Entry> oldEntries, List<Entry> newEntries) {
        java.util.Set<Integer> result = new java.util.HashSet<>();
        if (oldEntries == null || newEntries == null) return result;
        java.util.Set<String> oldUrls = new java.util.HashSet<>();
        for (Entry e : oldEntries) if (e.url != null && !e.url.isEmpty()) oldUrls.add(e.url);
        for (int i = 0; i < newEntries.size(); i++) {
            String url = newEntries.get(i).url;
            if (url != null && !url.isEmpty() && oldUrls.contains(url)) result.add(i);
        }
        return result;
    }
}
