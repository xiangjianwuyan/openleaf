package org.openleaf.reader.core;

/**
 * 书库「继续阅读」主卡的可靠记录判定与位置文案（纯 JVM，零 Android import）。
 *
 * <p>口径来自 {@code validation/BASELINE-V3.md} §7 与 {@code deliverables/v3-续读数据源-研究.md} §2。
 * 权威源是 SharedPreferences {@code reader} 中按 book.id 存的一组键；本类只承载**已读出的键**与判定，
 * 不碰 Context/SP/DB。三条闸门中本类负责第 ② 条「该格式进度键严格有效」，第 ① 条 opened_ 由
 * Activity 选 latest 时保证，第 ③ 条文件可读由 BookStore.open() 在 worker 线程保证。
 *
 * <p>总原则：宁可不显示，也不显示「点开报错」的卡；位置文案只展示可核对的真实值，不用 0%/估算填空。
 */
public final class ResolvedContinueReading {

    /** 纳入续读的格式；本地音频无逐文件位置不纳入，TTS 按 TEXT 口径。 */
    public enum Format { TEXT, EPUB, PDF, COMIC }

    public final String bookId;
    public final String title;
    public final Format format;
    public final String positionText;

    public ResolvedContinueReading(String bookId, String title, Format format, String positionText) {
        this.bookId = bookId == null ? "" : bookId;
        this.title = title == null ? "" : title;
        this.format = format == null ? Format.TEXT : format;
        this.positionText = positionText == null ? "" : positionText;
    }

    /** 值对象自身可用：必须有可核对的位置文案，空文案一律不展示。 */
    public boolean isReliable() {
        return !positionText.isEmpty();
    }

    /**
     * 闸门 ②：给定已读出的进度键，判断该格式是否有严格有效的进度。
     * 阈值：TEXT progress>0 且锚点可解析；EPUB spine>0 或 CFI 可解析；
     * PDF 0<=page<count；COMIC page>0。
     */
    public static boolean isReliable(Format f, int progress, String anchorRaw,
                                     int spine, String cfi, int pdfPage, int pdfCount,
                                     int comicPage) {
        if (f == null) return false;
        switch (f) {
            case TEXT:
                if (progress <= 0 || progress > 100) return false;
                ReadingAnchor anchor = ReadingAnchor.parse(anchorRaw);
                return anchor != null && (anchor.chapterOrdinal >= 0
                        || anchor.spineIndex >= 0 || anchor.sourceCharOffset >= 0);
            case EPUB:
                if (spine > 0) return true;
                EpubCfi.Location location = EpubCfi.parse(cfi);
                return location != null && location.spineIndex >= 0;
            case PDF:
                return pdfCount > 0 && pdfPage >= 0 && pdfPage < pdfCount;
            case COMIC:
                return comicPage > 0;
            default:
                return false;
        }
    }

    /**
     * 由已读出的键生成位置文案；不可靠或无可核对真值时返回 ""（不显示 0%/估算值）。
     * 与 MainActivity.savedPosition() 的取值口径一致：PDF 页码、EPUB spine 序号、TXT 进度百分比；
     * 漫画位置来自独立的 comicProgress SP（按内容指纹），是本类唯一会给出页码的格式。
     */
    public static String positionText(Format f, int progress, int spine, String cfi,
                                      int pdfPage, int pdfCount, int comicPage) {
        if (f == null) return "";
        switch (f) {
            case TEXT:
                return progress > 0 && progress <= 100 ? "进度 " + progress + "%" : "";
            case EPUB:
                if (spine >= 0) return "阅读位置 " + ((long) spine + 1);
                return EpubCfi.parse(cfi) == null ? "" : EpubCfi.shortText(cfi);
            case PDF:
                return pdfCount > 0 && pdfPage >= 0 && pdfPage < pdfCount
                        ? "第 " + (pdfPage + 1) + " / " + pdfCount + " 页" : "";
            case COMIC:
                return comicPage > 0 ? "第 " + (comicPage + 1) + " 页" : "";
            default:
                return "";
        }
    }
}
