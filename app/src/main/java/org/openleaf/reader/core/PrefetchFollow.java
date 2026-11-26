package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 有界预取计划（纯 JVM，无 Android 依赖）。
 *
 * <p>设计借鉴 Breeze（MPL-2.0）的两级预取（图片/章节、数量 clamp、key 去重）。
 * 原先这里的 singleflight 状态机与追更三基线模型没有任何生产调用方，0.23.0 一并删除；
 * 保留的 {@link #plan} / {@link #clampCount} / {@link #prefetchKey} 仍在实际预取路径使用。
 */
public final class PrefetchFollow {
    private PrefetchFollow() { }

    // ---------- 1. 有界预取 ----------

    public static final int IMAGE_MIN = 2, IMAGE_MAX = 10;
    public static final int CHAPTER_MIN = 1, CHAPTER_MAX = 3;

    /** 数量 clamp：图片 2..10、章节 1..3。 */
    public static int clampCount(int requested, boolean image) {
        int min = image ? IMAGE_MIN : CHAPTER_MIN;
        int max = image ? IMAGE_MAX : CHAPTER_MAX;
        return Math.max(min, Math.min(max, requested));
    }

    /** 预取去重键：来源|漫画|章节|路径，稳定且可判重。 */
    public static String prefetchKey(String from, String comicId, String chapterId, String path) {
        return (from == null ? "" : from) + "|" + (comicId == null ? "" : comicId)
                + "|" + (chapterId == null ? "" : chapterId) + "|" + (path == null ? "" : path);
    }

    /**
     * 有界预取计划：从候选列表取前 count 个未缓存项（按 key 去重），
     * 已缓存/进行中的跳过；失败时调用方移除 key 即可重试。
     */
    public static <T> List<T> plan(List<T> candidates, java.util.function.Function<T, String> keyOf,
                                   Set<String> done, Set<String> inFlight, int requested, boolean image) {
        List<T> result = new ArrayList<>();
        if (candidates == null || keyOf == null) return result;
        int limit = clampCount(requested, image);
        Set<String> seen = new HashSet<>();
        for (T candidate : candidates) {
            if (result.size() >= limit) break;
            String key = keyOf.apply(candidate);
            if (key == null || !seen.add(key)) continue;
            if (done.contains(key) || inFlight.contains(key)) continue;
            result.add(candidate);
        }
        return Collections.unmodifiableList(result);
    }

}
