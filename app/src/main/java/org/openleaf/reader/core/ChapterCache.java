package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 阶段 2-B 跨章缓存生命周期策略（纯 JVM，无文件 IO，可单测）。
 *
 * <p>路线图头号坑：旧实现在 loadComic 开头删光所有 {@code comic-*} 目录，
 * 打开第二章会把第一章已解压页删掉。本类给出"保留当前 ±1 章、淘汰更远章节"的纯判定：
 * Android 层据此决定哪些目录保留、哪些删除。</p>
 */
public final class ChapterCache {
    private ChapterCache() { }

    /** 缓存目录前缀，与 ComicActivity 的目录命名保持一致。 */
    public static final String DIR_PREFIX = "comic-";

    /**
     * 当前章索引（0-based）下应保留的章节索引集合：当前章及相邻各一章。
     * 返回不可变集合；越界索引自动忽略。
     */
    public static Set<Integer> keepIndices(int currentIndex, int totalChapters) {
        Set<Integer> keep = new HashSet<>();
        if (totalChapters <= 0 || currentIndex < 0 || currentIndex >= totalChapters) return keep;
        for (int d = -1; d <= 1; d++) {
            int i = currentIndex + d;
            if (i >= 0 && i < totalChapters) keep.add(i);
        }
        return Collections.unmodifiableSet(keep);
    }

    /**
     * 给定实际存在的缓存目录名集合（形如 "comic-<id>"）与应保留的章节 ID 集合，
     * 返回应删除的目录名（不在保留集内的 comic- 目录）。
     */
    public static List<String> evictable(Set<String> existingDirNames, Set<String> keepChapterIds) {
        List<String> out = new ArrayList<>();
        if (existingDirNames == null) return out;
        for (String name : existingDirNames) {
            if (name == null || !name.startsWith(DIR_PREFIX)) continue;
            String id = name.substring(DIR_PREFIX.length());
            if (keepChapterIds == null || !keepChapterIds.contains(id)) out.add(name);
        }
        Collections.sort(out);
        return Collections.unmodifiableList(out);
    }

    /** 缓存目录名：前缀 + 稳定章节 ID（低频碰撞可接受，仅作目录标识）。 */
    public static String dirName(String chapterEid) {
        return DIR_PREFIX + ComicChapters.stableId(chapterEid);
    }
}
