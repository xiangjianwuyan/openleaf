package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节标题 → 两级大纲（depth 0/1）与折叠可见性投影。纯 JVM，无 Android 类型。
 * 判定遵循「宁可不分组，也不要分错」：推断不出层级时一律平铺（全 depth=0）。
 */
public final class ChapterOutline {
    public static final int MAX_DEPTH = 1;  // P0 只允许两级；渲染层按 depth*16dp 缩进

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6}) ");
    private static final Pattern VOLUME = Pattern.compile(
            "^(第?[零〇一二三四五六七八九十百千万兩0-9]+[卷部篇集])|^(volume|part|book)\\s");
    private static final Pattern CHAPTER = Pattern.compile(
            "^(第?[零〇一二三四五六七八九十百千万兩0-9]+[章节節回话話幕期])|^(chapter|chap\\.|ch\\.)\\s");
    private static final Pattern SPECIAL = Pattern.compile(
            "^(?:序章|序言|序幕|楔子|引子|前言|引言|开篇|開篇|后记|後記|尾声|尾聲|终章|終章|"
                    + "番外(?:篇)?|附录|附錄|后序|後序|跋|结语|結語|大结局|大結局)");

    private ChapterOutline() { }

    public static final class Node {
        public final int index;
        public final String title;
        public final int depth;    // 0 或 1
        public final boolean parent; // true = 后继存在更深节点（渲染 ▾/▸）

        Node(int index, String title, int depth, boolean parent) {
            this.index = index;
            this.title = title;
            this.depth = depth;
            this.parent = parent;
        }
    }

    /** 任何输入都返回可直接渲染的列表；推断不出层级时全 depth=0（= 今天的平铺目录）。 */
    public static List<Node> build(List<String> titles) {
        if (titles == null || titles.isEmpty()) return new ArrayList<>();
        int n = titles.size();
        int[] depth = classify(titles);
        // parent：从后往前线性扫描「后继最大 depth」，单次 O(n)，避免逐节点向后找的 O(n²)。
        int[] suffixMax = new int[n + 1];
        suffixMax[n] = -1;
        for (int i = n - 1; i >= 0; i--) suffixMax[i] = Math.max(suffixMax[i + 1], depth[i]);
        List<Node> nodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            nodes.add(new Node(i, safe(titles.get(i)), depth[i], suffixMax[i + 1] > depth[i]));
        }
        return nodes;
    }

    /** 判定顺序：空 → Markdown 精确信号 → 卷/章两级 → 否则全 0。 */
    private static int[] classify(List<String> titles) {
        int n = titles.size();
        int[] depth = new int[n]; // 默认全 0

        // 2. Markdown 精确信号：≥2 个 #{1,6} 标题且存在 ≥2 种级别
        int mdCount = 0;
        int[] levels = new int[n];
        Set<Integer> distinct = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Matcher m = MD_HEADING.matcher(safe(titles.get(i)));
            if (m.find()) {
                int level = m.group(1).length();
                levels[i] = level;
                mdCount++;
                distinct.add(level);
            }
        }
        if (mdCount >= 2 && distinct.size() >= 2) {
            for (int i = 0; i < n; i++) {
                if (levels[i] > 0) depth[i] = Math.min(levels[i] - 1, MAX_DEPTH);
            }
            return depth;
        }

        // 3. 卷/章两级：卷级 ≥2、章级 ≥2、且卷数 < 章数才分组
        boolean[] isVolume = new boolean[n];
        boolean[] isChapter = new boolean[n];
        int volumeCount = 0, chapterCount = 0, firstVolume = -1;
        for (int i = 0; i < n; i++) {
            String t = safe(titles.get(i));
            isVolume[i] = VOLUME.matcher(t).find();
            isChapter[i] = CHAPTER.matcher(t).find();
            if (isVolume[i]) {
                volumeCount++;
                if (firstVolume < 0) firstVolume = i;
            }
            if (isChapter[i]) chapterCount++;
        }
        if (volumeCount >= 2 && chapterCount >= 2 && volumeCount < chapterCount) {
            for (int i = 0; i < n; i++) {
                String t = safe(titles.get(i));
                boolean special = SPECIAL.matcher(t).find();
                if (!special && isVolume[i]) depth[i] = 0;
                else if (!special && isChapter[i] && firstVolume >= 0 && i > firstVolume) depth[i] = 1;
                else depth[i] = 0; // 第一卷之前的章、特殊标题一律平铺
            }
        }
        return depth;
    }

    /** 折叠投影：返回可见行（父节点折叠时整段跳过其子项）。线性时间。 */
    public static int[] visible(List<Node> nodes, boolean[] collapsed) {
        if (nodes == null || nodes.isEmpty()) return new int[0];
        int n = nodes.size();
        int[] out = new int[n];
        int count = 0;
        int hideBelow = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            Node node = nodes.get(i);
            if (node.depth > hideBelow) continue;   // 被折叠父节点吞掉的子项
            hideBelow = Integer.MAX_VALUE;          // 遇到同级/更浅节点，折叠段结束
            out[count++] = node.index;
            if (node.parent && collapsed != null && i < collapsed.length && collapsed[i]) {
                hideBelow = node.depth;
            }
        }
        return Arrays.copyOf(out, count);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
