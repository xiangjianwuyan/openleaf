package org.openleaf.reader.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * 漫画核心（纯 JVM，无 Android 依赖）：CBZ/ZIP 安全页索引 + 跨章连读分段全局索引。
 *
 * 设计借鉴 Venera-Next（GPL-3.0）的「分段 + 全局扁平索引 + 双向预取 + 锚点恢复」
 * 与 XY-Music/Venera 的路径安全边界思想，由 OpenLeaf 自行用 Java 重写。
 */
public final class ComicFlow {
    private ComicFlow() { }

    // ---------- 1. ZIP 条目安全判定 ----------

    public static final int MAX_ENTRIES = 30000;
    public static final long MAX_UNCOMPRESSED = 512L * 1024 * 1024;
    public static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    private static final Pattern IMAGE_EXT = Pattern.compile("(?i)\\.(png|jpe?g|gif|webp|bmp)$");

    /** ZIP 路径安全检查：拒绝绝对路径、反斜杠、盘符、.. 越界。 */
    public static boolean safeEntry(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.startsWith("/") || name.contains("\\") || name.indexOf(':') >= 0) return false;
        for (String part : name.split("/")) {
            if (part.equals("..") || part.equals(".")) return false;
            if (part.isEmpty()) continue;
        }
        return true;
    }

    /** 是否图片条目（按扩展名）。 */
    public static boolean isImage(String name) { return name != null && IMAGE_EXT.matcher(name).find(); }

    /** 自然排序：多位数字按数值比较（2 在 10 前），前导零不影响。 */
    public static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ia = i, ib = j;
                while (ia < a.length() && Character.isDigit(a.charAt(ia))) ia++;
                while (ib < b.length() && Character.isDigit(b.charAt(ib))) ib++;
                // 跳过前导零（保留至少一位），再比较有效位数与逐位数值
                int sa = i, sb = j;
                while (sa < ia - 1 && a.charAt(sa) == '0') sa++;
                while (sb < ib - 1 && b.charAt(sb) == '0') sb++;
                int lenA = ia - sa, lenB = ib - sb;
                if (lenA != lenB) return Integer.compare(lenA, lenB);
                for (int k = 0; k < lenA; k++) {
                    int da = a.charAt(sa + k) - '0', db = b.charAt(sb + k) - '0';
                    if (da != db) return Integer.compare(da, db);
                }
                i = ia; j = ib;
            } else {
                if (ca != cb) return Character.compare(ca, cb);
                i++; j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** 漫画入库元数据：页数 + 封面条目名（阶段 2-B）。 */
    public static final class ComicMeta {
        public final int pageCount;
        public final String coverEntry;
        public ComicMeta(int pageCount, String coverEntry) {
            this.pageCount = pageCount;
            this.coverEntry = coverEntry;
        }
    }

    /** 从条目名列表生成入库元数据（页数/封面），与 imagePages 同口径的安全过滤与自然排序。 */
    public static ComicMeta metaFrom(List<String> entryNames) throws IOException {
        List<String> pages = imagePages(entryNames);
        return new ComicMeta(pages.size(), pages.isEmpty() ? null : pages.get(0));
    }

    /** 从 ZIP 条目列表提取安全图片页，按自然顺序返回相对路径。 */
    public static List<String> imagePages(List<String> entryNames) throws IOException {
        if (entryNames == null) throw new IOException("条目列表为空");
        if (entryNames.size() > MAX_ENTRIES) throw new IOException("压缩包条目数超过 " + MAX_ENTRIES);
        List<String> pages = new ArrayList<>();
        long total = 0;
        for (String name : entryNames) {
            if (!safeEntry(name)) continue;
            if (!isImage(name)) continue;
            total++;
            if (total > MAX_ENTRIES) throw new IOException("图片条目数超过 " + MAX_ENTRIES);
            pages.add(name);
        }
        Collections.sort(pages, ComicFlow::naturalCompare);
        if (pages.isEmpty()) throw new IOException("未找到可用图片页");
        if (total > MAX_ENTRIES) throw new IOException("总展开超过 " + BookParser.mib(MAX_UNCOMPRESSED));
        return Collections.unmodifiableList(pages);
    }

    // ---------- 2. 分段 + 全局扁平索引 ----------

    /** 章节分段：稳定章节 ID + 图片页路径列表。 */
    public static final class Segment {
        public final int chapter;
        public final String eid;
        public final List<String> images;

        public Segment(int chapter, String eid, List<String> images) {
            if (chapter < 1) throw new IllegalArgumentException("章节序号从 1 起");
            if (eid == null || eid.isEmpty()) throw new IllegalArgumentException("章节需要稳定 ID");
            if (images == null || images.isEmpty()) throw new IllegalArgumentException("章节需要至少一页");
            this.chapter = chapter;
            this.eid = eid;
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
        }
    }

    /** 图片引用：全局 1-based 索引 → 章节/页序号/图片 key。 */
    public static final class ImageRef {
        public final Segment segment;
        public final int pageIndex; // 0-based within segment
        public final int globalIndex; // 1-based
        public final String key;

        ImageRef(Segment segment, int pageIndex, int globalIndex) {
            this.segment = segment;
            this.pageIndex = pageIndex;
            this.globalIndex = globalIndex;
            this.key = segment.eid + ":" + pageIndex;
        }
    }

    /** 跨章连读的全局索引流：多章图片拼成一个连续流，提供双向映射。 */
    public static final class FlatFlow {
        private final List<Segment> segments = new ArrayList<>();
        private final List<ImageRef> refs = new ArrayList<>();

        public FlatFlow(List<Segment> source) {
            if (source == null) return;
            int global = 1;
            for (Segment segment : source) {
                segments.add(segment);
                for (int i = 0; i < segment.images.size(); i++) refs.add(new ImageRef(segment, i, global++));
            }
        }

        public int size() { return refs.size(); }
        public int chapterCount() { return segments.size(); }

        /** 全局索引（1-based）→ 图片引用，越界返回 null。 */
        public ImageRef at(int globalIndex) {
            if (globalIndex < 1 || globalIndex > refs.size()) return null;
            return refs.get(globalIndex - 1);
        }

        /** 章节号 + 页序号 → 全局索引；找不到返回 -1。 */
        public int indexOf(int chapter, int pageIndex) {
            for (ImageRef ref : refs)
                if (ref.segment.chapter == chapter && ref.pageIndex == pageIndex) return ref.globalIndex;
            return -1;
        }

        /** 当前页所在章之后是否还有章节（用于触发下一章预取）。 */
        private boolean hasChapterAfter(int chapter) {
            for (Segment segment : segments) if (segment.chapter > chapter) return true;
            return false;
        }

        /** 当前页所在章之前是否还有章节（用于触发上一章预取）。 */
        private boolean hasChapterBefore(int chapter) {
            for (Segment segment : segments) if (segment.chapter < chapter) return true;
            return false;
        }

        /** 接近当前章末尾（剩余图片数小于阈值）且后面还有章节时，应预取下一章。 */
        public boolean shouldLoadAfter(int globalIndex, int threshold) {
            if (segments.isEmpty() || globalIndex < 1 || globalIndex > refs.size()) return false;
            int chapter = refs.get(globalIndex - 1).segment.chapter;
            if (!hasChapterAfter(chapter)) return false;
            // 本章剩余页数
            int remaining = 0;
            for (int i = globalIndex; i < refs.size() && refs.get(i).segment.chapter == chapter; i++) remaining++;
            return remaining <= threshold;
        }

        /** 接近当前章开头（前面剩余页数小于阈值）且前面还有章节时，应预取上一章。 */
        public boolean shouldLoadBefore(int globalIndex, int threshold) {
            if (segments.isEmpty() || globalIndex < 1 || globalIndex > refs.size()) return false;
            int chapter = refs.get(globalIndex - 1).segment.chapter;
            if (!hasChapterBefore(chapter)) return false;
            int before = 0;
            for (int i = globalIndex - 1; i >= 0 && refs.get(i).segment.chapter == chapter; i--) before++;
            return before <= threshold;
        }

        /** 在前方插入一章后，锚点索引应如何平移（保证视觉内容不跳动）。 */
        public int anchorAfterPrepend(int anchorGlobalIndex, int insertedImageCount) {
            return Math.max(1, anchorGlobalIndex + insertedImageCount);
        }

        /**
         * 当前页码落在哪一章（按可见图片反推）。返回 chapter 序号，找不到返回 -1。
         */
        public int chapterAt(int globalIndex) {
            ImageRef ref = at(globalIndex);
            return ref == null ? -1 : ref.segment.chapter;
        }
    }

    // ---------- 3. 导入意图记录与恢复 ----------

    /** 导入意图摘要：先写意图，再复制，再校验，最后注册。 */
    public static final class ImportIntent {
        public final String sourceUri;
        public final String targetPath;
        public final String expectedSha256;
        public final long expectedSize;
        public final long createdAt;

        public ImportIntent(String sourceUri, String targetPath, String expectedSha256,
                            long expectedSize, long createdAt) {
            this.sourceUri = sourceUri == null ? "" : sourceUri;
            this.targetPath = targetPath == null ? "" : targetPath;
            this.expectedSha256 = expectedSha256 == null ? "" : expectedSha256;
            this.expectedSize = expectedSize;
            this.createdAt = createdAt;
        }
    }

    /** 恢复判定：只有文件存在、大小一致、哈希一致时才允许完成注册。 */
    public static boolean canResume(ImportIntent intent, long actualSize, String actualSha256) {
        if (intent == null || intent.targetPath.isEmpty()) return false;
        if (intent.expectedSize >= 0 && actualSize != intent.expectedSize) return false;
        if (intent.expectedSha256 != null && !intent.expectedSha256.isEmpty()
                && !intent.expectedSha256.equalsIgnoreCase(actualSha256)) return false;
        return true;
    }
}
