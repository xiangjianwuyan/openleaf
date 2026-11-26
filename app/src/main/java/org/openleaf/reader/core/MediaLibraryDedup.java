package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 阶段 3-B Y-3（逻辑去重）：统一媒体库与 books 兼容列的一致性校验（纯 JVM，可单测）。
 *
 * <p>设计 Y 终态下 media_items 是文件级字段的唯一真相，books.title 仅作兼容列保留
 * （物理删列需重建表、动 7 处 SQL，风险过高，留独立评审）。本类给出"双表是否一致"的
 * 纯判定，供 Y-2 观察期与 QA 核对使用：不一致时按 media_items 为准，books 列视为待淘汰的兼容副本。</p>
 */
public final class MediaLibraryDedup {
    private MediaLibraryDedup() { }

    /** 一条对照：books 兼容列 vs media_items 权威列。 */
    public static final class Pair {
        public final String id;
        public final String booksTitle, mediaTitle;
        public final String booksFormat, mediaKind;
        public Pair(String id, String booksTitle, String mediaTitle, String booksFormat, String mediaKind) {
            this.id = id == null ? "" : id;
            this.booksTitle = booksTitle == null ? "" : booksTitle;
            this.mediaTitle = mediaTitle == null ? "" : mediaTitle;
            this.booksFormat = booksFormat == null ? "" : booksFormat;
            this.mediaKind = mediaKind == null ? "" : mediaKind;
        }
    }

    /** 不一致项描述。 */
    public static final class Conflict {
        public final String id, field, compatValue, authoritativeValue;
        Conflict(String id, String field, String compat, String authoritative) {
            this.id = id; this.field = field; this.compatValue = compat; this.authoritativeValue = authoritative;
        }
        @Override public String toString() {
            return id + "." + field + ": books=" + compatValue + " media_items=" + authoritativeValue;
        }
    }

    /**
     * 找出所有不一致。口径：
     * - mediaItems 无对应行（title 空）→ 老库/未双写，不算冲突（Y-2 回退 books.title）
     * - title 不一致 → 冲突，权威值 = mediaTitle
     * - kind 与 format 不一致：仅当 mediaKind 为 comic 且 format 非 comic（或反之）才算冲突；
     *   book/audio/video 与 format 的自由取值不强求一致（format 可能是 mobi/epub 等具体格式）
     */
    public static List<Conflict> conflicts(List<Pair> pairs) {
        List<Conflict> out = new ArrayList<>();
        if (pairs == null) return out;
        for (Pair p : pairs) {
            if (p == null) continue;
            if (!p.mediaTitle.isEmpty() && !p.mediaTitle.equals(p.booksTitle))
                out.add(new Conflict(p.id, "title", p.booksTitle, p.mediaTitle));
            // books.format 是具体格式（cbz/mobi/txt），media_items.kind 是媒体大类（comic/book）；
            // 先按同一函数归一化再比，避免把 "cbz" vs "comic" 误判为冲突。
            if (p.mediaKind.isEmpty()) continue; // 老库未双写：无权威 kind，跳过判定
            String normalized = kindFromFormat(p.booksFormat, null); // 只从 format 推导，避免用 mediaKind 自证
            if (!normalized.equalsIgnoreCase(p.mediaKind))
                out.add(new Conflict(p.id, "kind", p.booksFormat, p.mediaKind));
        }
        return Collections.unmodifiableList(out);
    }

    /** 从 books 的 format 推导应写入 media_items.kind 的值（与双写口径一致）。 */
    public static String kindFromFormat(String format, String mediaType) {
        if (mediaType != null && !mediaType.isEmpty()) return mediaType;
        if (format == null) return "book";
        String f = format.toLowerCase(java.util.Locale.ROOT);
        if ("cbz".equals(f) || "zip".equals(f) || "cbr".equals(f) || "rar".equals(f) || "comic".equals(f)) return "comic";
        return "book";
    }
}
