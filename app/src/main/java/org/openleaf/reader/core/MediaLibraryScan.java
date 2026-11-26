package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 3-B（设计 Y 第一步）：统一媒体库增量扫描的纯 JVM 编排。
 *
 * <p>把已就位但 0 引用的 {@link AudioDsp.FileFingerprint}/{@link AudioDsp.ScanDiff}/{@link AudioDsp.diff}
 * 包成"快照 → 磁盘 → 差异"的可单测入口。本类不碰文件 IO（磁盘条目由 Android 层传入），
 * 不自动入库/删行——只产出候选差异，符合设计 Y-1 的"books 仍是唯一真相、影子表双写"口径。</p>
 */
public final class MediaLibraryScan {
    private MediaLibraryScan() { }

    /** 媒体类型（与 books.media_type 同域，增加 audio/video）。 */
    public enum Kind {
        BOOK("book"), COMIC("comic"), AUDIO("audio"), VIDEO("video");
        public final String value;
        Kind(String value) { this.value = value; }
        public static Kind of(String value) {
            if (value == null) return BOOK;
            for (Kind k : values()) if (k.value.equalsIgnoreCase(value)) return k;
            return BOOK;
        }
    }

    /** 一条媒体库快照（对应 media_items 一行）。 */
    public static final class Item {
        public final String path, kind, title, cover;
        public final long mtime, size, duration;
        public Item(String path, long mtime, long size, Kind kind, String title,
                    long duration, String cover) {
            this.path = path == null ? "" : path;
            this.mtime = mtime; this.size = size;
            this.kind = kind == null ? Kind.BOOK.value : kind.value;
            this.title = title == null ? "" : title;
            this.duration = Math.max(0, duration);
            this.cover = cover == null ? "" : cover;
        }
        /** 转成 AudioDsp 的三元组（增量 diff 的输入）。 */
        public AudioDsp.FileFingerprint fingerprint() {
            return new AudioDsp.FileFingerprint(path, mtime, size);
        }
    }

    /** 把快照列表转成 diff 所需的 map（path → 指纹）。重复 path 后者覆盖，与 DB 主键语义一致。 */
    public static Map<String, AudioDsp.FileFingerprint> snapshot(List<Item> items) {
        Map<String, AudioDsp.FileFingerprint> out = new LinkedHashMap<>();
        if (items == null) return out;
        for (Item item : items)
            if (item != null && !item.path.isEmpty()) out.put(item.path, item.fingerprint());
        return out;
    }

    /**
     * 计算磁盘相对快照的差异。返回的三个列表路径已排序（AudioDsp.diff 保证）。
     * 不修改任何输入集合；不产生"自动入库"副作用。
     */
    public static AudioDsp.ScanDiff diff(List<Item> dbItems, List<Item> diskItems) {
        return AudioDsp.diff(snapshot(dbItems), snapshot(diskItems));
    }

    /** 扫描结论摘要（供 UI 显示"新增 X / 修改 Y / 删除 Z"）。 */
    public static String summary(AudioDsp.ScanDiff diff) {
        if (diff == null) return "无差异";
        return "新增 " + diff.added.size() + " / 修改 " + diff.modified.size()
                + " / 删除 " + diff.removed.size();
    }

    /** 路径是否可作为扫描候选（复用 isPathSafe 的 canonical 语义的字符串前置检查）。 */
    public static boolean candidatePath(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        if (path.indexOf('\0') >= 0 || path.contains("..")) return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".epub")
                || lower.endsWith(".pdf") || lower.endsWith(".mobi") || lower.endsWith(".azw")
                || lower.endsWith(".azw3") || lower.endsWith(".prc") || lower.endsWith(".cbz")
                || lower.endsWith(".zip") || lower.endsWith(".cbr") || lower.endsWith(".rar")
                || lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".flac")
                || lower.endsWith(".wav") || lower.endsWith(".mp4") || lower.endsWith(".mkv");
    }
}
