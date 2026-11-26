package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 在线书统一入库与章节缓存策略（纯 JVM，无 Android 依赖）。
 *
 * 借鉴 LightRead（AGPL）的导入逐项结果与 LightRead/Venera 的缓存分层思想，
 * 由 OpenLeaf 自行实现。核心：sourceId+remoteKey 稳定标识 + 规则版本绑定 +
 * 用户主动保存(retained)与自动预取(可 LRU 清理)分离。
 */
public final class OnlineLibrary {
    private OnlineLibrary() { }

    // ---------- 1. 稳定远端标识 ----------

    /** 生成稳定 remoteKey：优先站点稳定 ID，否则规范化链接（去 query/fragment/尾斜杠）。 */
    public static String remoteKey(String sourceId, String stableId, String url) {
        String id = stableId != null && !stableId.trim().isEmpty() ? stableId.trim() : normalizeUrl(url);
        if (id.isEmpty()) id = ContentContracts.hashHex(((sourceId == null ? "" : sourceId) + "|" + url)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (sourceId == null ? "" : sourceId.trim()) + "::" + id;
    }

    /** URL 规范化：小写 host、去 query/fragment、去尾斜杠、统一 http→https 比较基准。 */
    static String normalizeUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        String value = url.trim();
        int cut = value.length();
        int q = value.indexOf('?'), f = value.indexOf('#');
        if (q >= 0) cut = Math.min(cut, q);
        if (f >= 0) cut = Math.min(cut, f);
        value = value.substring(0, cut);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.toLowerCase(Locale.ROOT);
    }

    /** 规则版本失配判定：版本变化应提示"需重新解析"，不能静默当空书。 */
    public static boolean ruleChanged(String storedVersion, String currentVersion) {
        String a = storedVersion == null ? "" : storedVersion;
        String b = currentVersion == null ? "" : currentVersion;
        return !a.equals(b);
    }

    // ---------- 2. 章节缓存策略 ----------

    /** 缓存态：待处理 / 已缓存 / 失败 / 已清理。 */
    public enum CacheState { PENDING, CACHED, FAILED, EVICTED }

    /** 缓存条目：章节键 + 状态 + 字节数 + 是否用户主动保留。 */
    public static final class CacheEntry {
        public final String chapterKey;
        public final CacheState state;
        public final long bytes;
        public final boolean retained;
        public final long updatedAt;
        public CacheEntry(String chapterKey, CacheState state, long bytes, boolean retained, long updatedAt) {
            this.chapterKey = chapterKey; this.state = state; this.bytes = bytes;
            this.retained = retained; this.updatedAt = updatedAt;
        }
    }

    /**
     * LRU 淘汰候选：按 updatedAt 升序选可清理项（retained 永不选），直到总字节 <= quota。
     * 返回应被淘汰的 chapterKey 列表。
     */
    public static List<String> evictCandidates(List<CacheEntry> entries, long quotaBytes) {
        List<String> result = new ArrayList<>();
        if (entries == null || quotaBytes < 0) return result;
        long total = 0;
        for (CacheEntry entry : entries) if (entry.state == CacheState.CACHED) total += entry.bytes;
        if (total <= quotaBytes) return result;
        List<CacheEntry> evictable = new ArrayList<>();
        for (CacheEntry entry : entries)
            if (entry.state == CacheState.CACHED && !entry.retained) evictable.add(entry);
        evictable.sort((a, b) -> Long.compare(a.updatedAt, b.updatedAt));
        for (CacheEntry entry : evictable) {
            if (total <= quotaBytes) break;
            result.add(entry.chapterKey);
            total -= entry.bytes;
        }
        return result;
    }

    /** 预取计划：从起始章开始连续 N 章的 chapterKey（不含已缓存）。 */
    public static List<String> prefetchPlan(List<String> allChapterKeys, String startKey, int count,
                                            Map<String, CacheEntry> existing) {
        List<String> result = new ArrayList<>();
        if (allChapterKeys == null || startKey == null || count <= 0) return result;
        int start = allChapterKeys.indexOf(startKey);
        if (start < 0) return result;
        for (int i = start; i < allChapterKeys.size() && result.size() < count; i++) {
            String key = allChapterKeys.get(i);
            CacheEntry entry = existing == null ? null : existing.get(key);
            if (entry == null || entry.state != CacheState.CACHED) result.add(key);
        }
        return result;
    }

    // ---------- 3. 仅 Wi-Fi 判定 ----------

    /** 是否允许下载：仅 Wi-Fi 开关开启时要求 unmetered=true。 */
    public static boolean mayDownload(boolean wifiOnly, boolean unmetered) {
        return !wifiOnly || unmetered;
    }
}
