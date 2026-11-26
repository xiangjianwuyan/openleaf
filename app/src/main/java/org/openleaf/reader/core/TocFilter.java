package org.openleaf.reader.core;

import java.util.List;
import java.util.Locale;

/**
 * 目录标题筛选纯函数：大小写不敏感子串包含，返回原索引数组。
 *
 * <p>不查正文、不重建索引、不改 ChapterRule / ChapterOutline 的任何既有行为。
 * filterVisible 与 ChapterOutline.visible() 的折叠投影结果求交，只过滤可见项。</p>
 */
public final class TocFilter {
    private TocFilter() { }

    /**
     * 在标题列表中做大小写不敏感子串匹配，返回升序的原索引。
     * query 为 null 或空串时返回全部原索引 [0..n-1]；无匹配返回空数组。
     */
    public static int[] filter(List<String> titles, String query) {
        int n = titles == null ? 0 : titles.size();
        if (isEmpty(query)) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = i;
            return all;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        int[] found = new int[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            String title = titles.get(i);
            String safe = title == null ? "" : title;
            if (safe.toLowerCase(Locale.ROOT).contains(needle)) found[count++] = i;
        }
        return trim(found, count);
    }

    /**
     * 只在 visibleIndices 指定的可见集合内过滤；visibleIndices 存的是 nodes 的下标，
     * 返回值是 node.index（原章节下标）。脏下标直接跳过，保证与 visible() 组合不越界。
     */
    public static int[] filterVisible(List<ChapterOutline.Node> nodes, int[] visibleIndices, String query) {
        if (nodes == null || nodes.isEmpty() || visibleIndices == null || visibleIndices.length == 0)
            return new int[0];
        String needle = isEmpty(query) ? null : query.toLowerCase(Locale.ROOT);
        int[] found = new int[visibleIndices.length];
        int count = 0;
        for (int visible : visibleIndices) {
            if (visible < 0 || visible >= nodes.size()) continue;
            ChapterOutline.Node node = nodes.get(visible);
            if (node == null) continue;
            if (needle != null) {
                String title = node.title == null ? "" : node.title;
                if (!title.toLowerCase(Locale.ROOT).contains(needle)) continue;
            }
            found[count++] = node.index;
        }
        return trim(found, count);
    }

    /** 当前章在过滤结果中的 0-based 行位置；不在结果中返回 -1。 */
    public static int positionOf(int[] filteredIndices, int currentIndex) {
        if (filteredIndices == null) return -1;
        for (int i = 0; i < filteredIndices.length; i++) {
            if (filteredIndices[i] == currentIndex) return i;
        }
        return -1;
    }

    private static boolean isEmpty(String value) { return value == null || value.isEmpty(); }

    private static int[] trim(int[] source, int count) {
        if (count == source.length) return source;
        int[] exact = new int[count];
        System.arraycopy(source, 0, exact, 0, count);
        return exact;
    }
}
