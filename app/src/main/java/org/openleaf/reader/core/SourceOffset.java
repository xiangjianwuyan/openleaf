package org.openleaf.reader.core;

/**
 * 作品级 UTF-16 字符偏移换算（纯 JVM，无 Android 依赖）。
 *
 * <p>唯一口径：作品级源偏移 = Σ 前序章 {@code meta(i).charCount} + 章内偏移。
 * 与 {@code ReaderActivity.cumulativeSourceOffset()/anchorSourceOffset()/resolveAnchor()}
 * 逐字同口径；对同一份解码文本跨编码、跨分章规则都不变。
 *
 * <p><b>禁止使用 {@code TextChapterIndexer.sourceStart}</b>：它是「上一章长度」而非累加值，
 * 直接拿来定位会 resolve 到靠前的章。所有越界/负偏移一律返回 -1 或 {-1,-1}，不抛异常打断搜索。
 */
public final class SourceOffset {
    private SourceOffset() { }

    /** Σ_{k<chapter} max(0, meta(k).charCount)；book 为 null 或 chapter 越界返回 -1。 */
    public static long cumulative(LazyChapterBook book, int chapter) {
        if (book == null || chapter < 0 || chapter >= book.size()) return -1;
        long sum = 0;
        for (int i = 0; i < chapter; i++) {
            LazyChapterBook.Section meta = book.meta(i);
            sum += Math.max(0, meta == null ? 0 : meta.charCount);
        }
        return sum;
    }

    /** cumulative(book, chapter) + max(0, inChapter)；章无效返回 -1。 */
    public static long of(LazyChapterBook book, int chapter, int inChapter) {
        long base = cumulative(book, chapter);
        return base < 0 ? -1 : base + Math.max(0, inChapter);
    }

    /**
     * 反解作品级偏移 → {chapter, inChapter}。落在章边界取后一章 0 偏移；
     * 负偏移、空书、超出全书总长返回 {-1,-1}（调用方据此提示"请重新查找"，不猜跳）。
     */
    public static int[] resolve(LazyChapterBook book, long sourceOffset) {
        if (book == null || sourceOffset < 0) return new int[]{-1, -1};
        long cum = 0;
        for (int i = 0; i < book.size(); i++) {
            LazyChapterBook.Section meta = book.meta(i);
            long count = Math.max(0, meta == null ? 0 : meta.charCount);
            if (sourceOffset >= cum && sourceOffset < cum + count) {
                return new int[]{i, (int) (sourceOffset - cum)};
            }
            cum += count;
        }
        return new int[]{-1, -1};
    }
}
