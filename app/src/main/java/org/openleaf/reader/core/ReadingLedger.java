package org.openleaf.reader.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 阅读时长分列与稳定事件键（纯 JVM，无 Android 依赖）。
 *
 * 目的：
 * 1. 文本/漫画/音视频时长分列统计，不再混在一个总数里；
 * 2. 每个计时分段有稳定事件键，同一分段重复提交不会重复计入（防 onPause/onStop 双触发）；
 * 3. "剩余时间"标注为估算，且只在有稳定进度时给出，不伪装成精确值。
 */
public final class ReadingLedger {
    private ReadingLedger() { }

    public enum Kind {
        TEXT, COMIC, AUDIO;
        public String key() { return name().toLowerCase(Locale.ROOT); }
        public String label() {
            switch (this) { case TEXT: return "文字阅读"; case COMIC: return "漫画阅读"; case AUDIO: return "听书/音频"; default: return "其他"; }
        }
    }

    /** 一条计时分段。 */
    public static final class Segment {
        public final String eventKey, bookId, day;
        public final Kind kind;
        public final long seconds;
        public Segment(String eventKey, String bookId, String day, Kind kind, long seconds) {
            this.eventKey = eventKey; this.bookId = bookId; this.day = day; this.kind = kind; this.seconds = seconds;
        }
    }

    /**
     * 稳定事件键：bookId + kind + day + 分段起始毫秒。
     * 同一分段（同一 segmentStartMs）在 onPause/onStop/切章重复提交时得到相同键 → 去重。
     */
    public static String eventKey(String bookId, Kind kind, String day, long segmentStartMs) {
        String base = (bookId == null ? "" : bookId) + "|" + kind.key() + "|"
                + (day == null ? "" : day) + "|" + Math.max(0, segmentStartMs);
        return ContentContracts.hashHex(base.getBytes(StandardCharsets.UTF_8));
    }

    /** 按 kind 聚合时长；未知/null 跳过。 */
    public static Map<Kind, Long> totalsByKind(List<Segment> segments) {
        Map<Kind, Long> result = new LinkedHashMap<>();
        for (Kind k : Kind.values()) result.put(k, 0L);
        if (segments == null) return result;
        for (Segment s : segments) {
            if (s == null || s.kind == null || s.seconds <= 0) continue;
            result.put(s.kind, result.get(s.kind) + s.seconds);
        }
        return result;
    }

    /** 去重：按 eventKey 保留首条，返回去重后的分段（保持输入顺序）。 */
    public static List<Segment> dedupe(List<Segment> segments) {
        List<Segment> result = new ArrayList<>();
        if (segments == null) return result;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Segment s : segments) {
            if (s == null || s.eventKey == null || s.eventKey.isEmpty()) continue;
            if (seen.add(s.eventKey)) result.add(s);
        }
        return Collections.unmodifiableList(result);
    }

    /** 总时长（所有 kind 之和）。 */
    public static long sumAll(List<Segment> segments) {
        long total = 0;
        for (Long value : totalsByKind(segments).values()) total += value;
        return total;
    }

    /**
     * 剩余时间估算：已读比例 + 已用秒数 → 剩余秒数。
     * 返回 -1 表示"不可估算"（比例非法/已用为 0），调用方必须标注"估算"。
     */
    public static long estimateRemainingSeconds(long usedSeconds, float progress) {
        if (usedSeconds <= 0 || progress <= 0f || progress >= 1f || Float.isNaN(progress)) return -1;
        double remaining = usedSeconds * (1.0 - progress) / progress;
        if (remaining < 0 || remaining > 100L * 365 * 24 * 3600) return -1; // 超过 100 年视为不可估算
        return (long) Math.round(remaining);
    }

    /** 带"估算"标注的时长文案（调用方直接用，避免伪装精确）。 */
    public static String estimatedLabel(long seconds) {
        if (seconds < 0) return "剩余时间：暂无足够数据估算";
        if (seconds < 60) return "预计剩余 约" + seconds + " 秒（估算）";
        long minutes = seconds / 60;
        if (minutes < 60) return "预计剩余 约" + minutes + " 分钟（估算）";
        long hours = minutes / 60;
        return "预计剩余 约" + hours + " 小时 " + (minutes % 60) + " 分（估算）";
    }
}
