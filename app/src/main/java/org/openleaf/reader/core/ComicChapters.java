package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 阶段 2-B 下半场：把两种「章节来源」归一化为 {@link ComicFlow.Segment} 列表（纯 JVM）。
 *
 * <p>来源 A：同一目录下的多个 CBZ/ZIP 文件，按文件名自然序，每个文件一章。
 * 来源 B：单个 CBZ 内的子目录（如 {@code ch01/}、{@code 第02话/}），按目录分章；
 * 若所有图片都在根目录（无有效子目录）则退化为单章，保持旧行为。</p>
 *
 * <p>章节 eid 必须稳定（force-stop 冷启动后仍能对上）：来源 A 用文件名，
 * 来源 B 用目录名；两者都做安全化，去掉路径分隔与非法字符。</p>
 */
public final class ComicChapters {
    private ComicChapters() { }

    /** 条目数/名称上限，与 ComicFlow 一致，防止恶意归档制造海量章节。 */
    private static final int MAX_CHAPTERS = 2000;

    /**
     * 来源 A：同目录多文件 → 每文件一章。
     * @param fileNames 已按自然序排列（本方法内部仍会再排一次保证确定性）的 CBZ/ZIP 文件名
     * @param imagesPerFile 文件名 → 该文件的图片条目名列表（已经过 ComicFlow.imagePages 过滤）
     */
    public static List<ComicFlow.Segment> fromFiles(List<String> fileNames,
                                                    java.util.Map<String, List<String>> imagesPerFile) {
        if (fileNames == null || fileNames.isEmpty()) return Collections.emptyList();
        List<String> names = new ArrayList<>(fileNames);
        Collections.sort(names, ComicFlow::naturalCompare);
        List<ComicFlow.Segment> out = new ArrayList<>();
        for (String name : names) {
            if (out.size() >= MAX_CHAPTERS) break;
            List<String> images = imagesPerFile == null ? null : imagesPerFile.get(name);
            if (images == null || images.isEmpty()) continue;
            out.add(new ComicFlow.Segment(out.size() + 1, stableId(name), images));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 来源 B：单包内子目录 → 每目录一章。
     * 图片路径形如 {@code ch01/001.jpg} 时取 {@code ch01} 为章；根目录图片（无 /）归入单章 ""。
     * 若只有根目录图片或只有一个子目录，返回一个单章（保持旧「整包连读」行为）。
     */
    public static List<ComicFlow.Segment> fromEntryNames(List<String> imageNames) {
        if (imageNames == null || imageNames.isEmpty()) return Collections.emptyList();
        java.util.Map<String, List<String>> grouped = new java.util.LinkedHashMap<>();
        List<String> rootImages = new ArrayList<>();
        for (String raw : imageNames) {
            if (!ComicFlow.isImage(raw) || !ComicFlow.safeEntry(raw)) continue;
            int slash = raw.lastIndexOf('/');
            if (slash <= 0) { rootImages.add(raw); continue; }
            String dir = raw.substring(0, slash);
            grouped.computeIfAbsent(dir, k -> new ArrayList<>()).add(raw);
        }
        // 按目录名自然序排列，保证章节顺序确定
        List<String> dirs = new ArrayList<>(grouped.keySet());
        Collections.sort(dirs, ComicFlow::naturalCompare);
        List<ComicFlow.Segment> out = new ArrayList<>();
        if (!rootImages.isEmpty()) {
            Collections.sort(rootImages, ComicFlow::naturalCompare);
            out.add(new ComicFlow.Segment(1, stableId("root"), rootImages));
        }
        for (String dir : dirs) {
            if (out.size() >= MAX_CHAPTERS) break;
            List<String> imgs = grouped.get(dir);
            Collections.sort(imgs, ComicFlow::naturalCompare);
            out.add(new ComicFlow.Segment(out.size() + 1, stableId(dir), imgs));
        }
        return Collections.unmodifiableList(out);
    }

    /** 稳定章节 ID：小写化 + 非法字符替换为下划线 + 长度截断，保证跨会话一致。 */
    public static String stableId(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 80; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c >= '\u4e00' && c <= '\u9fff';
            sb.append(ok ? c : '_');
        }
        String id = sb.toString();
        return id.isEmpty() ? "chapter" : id;
    }

    /**
     * 从章节列表生成全局页 key 列表（与 FlatFlow.at(i).key 逐字一致）。
     * 供进度存储做「旧指纹 → 新全局 key」映射的对照表。
     */
    public static List<String> globalKeys(List<ComicFlow.Segment> segments) {
        List<String> keys = new ArrayList<>();
        if (segments == null) return keys;
        for (ComicFlow.Segment seg : segments)
            for (int i = 0; i < seg.images.size(); i++)
                keys.add(seg.eid + ":" + i);
        return keys;
    }
}
