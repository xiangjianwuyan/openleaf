package org.openleaf.reader.core;

/** Byte-weighted global progress plus structural-anchor resolution. */
public final class ProgressModel {
    private final LazyChapterBook book;
    private final long[] prefix;
    private final long totalBytes;

    public ProgressModel(LazyChapterBook book) {
        this.book = book;
        prefix = new long[book.size() + 1];
        long sum = 0;
        for (int i = 0; i < book.size(); i++) {
            prefix[i] = sum;
            sum += Math.max(0, book.meta(i).byteSize);
        }
        prefix[book.size()] = sum;
        totalBytes = sum;
    }

    public int percent(int chapter, int charOffset, boolean atEnd) {
        if (chapter < 0 || chapter >= book.size()) return 0;
        LazyChapterBook.Section section = book.meta(chapter);
        long within = atEnd ? section.byteSize
                : section.byteSize * Math.max(0, Math.min(charOffset, section.charCount))
                        / Math.max(1, section.charCount);
        long done = prefix[chapter] + within;
        return totalBytes <= 0 ? 0 : (int) Math.min(100, Math.round(100.0 * done / totalBytes));
    }

    /** Returns {chapterIndex, chapterCharOffset}. */
    public int[] fromPercent(int percent) {
        int value = Math.max(0, Math.min(100, percent));
        if (totalBytes <= 0 || book.size() == 0) return new int[]{0, 0};
        long target = (long) Math.ceil(totalBytes * value / 100.0);
        int chapter = 0;
        while (chapter + 1 < book.size() && prefix[chapter + 1] <= target) chapter++;
        LazyChapterBook.Section section = book.meta(chapter);
        long within = Math.max(0, target - prefix[chapter]);
        int offset = within >= section.byteSize ? section.charCount
                : (int) (section.charCount * within / Math.max(1, section.byteSize));
        return new int[]{chapter, Math.max(0, Math.min(offset, section.charCount))};
    }

    /** Resolve an anchor to a chapter index and update the in-chapter offset. */
    public int resolve(ReadingAnchor anchor, int[] offsetOut) {
        if (anchor == null || book.size() == 0) {
            offsetOut[0] = 0;
            return 0;
        }
        int found = -1;
        long source = anchor.sourceCharOffset;
        if (source >= 0) {
            for (int i = 0; i < book.size(); i++) {
                LazyChapterBook.Section section = book.meta(i);
                if (section.sourceStart >= 0 && source >= section.sourceStart
                        && source < section.sourceStart + section.charCount) {
                    found = i;
                    offsetOut[0] = (int) (source - section.sourceStart);
                    break;
                }
            }
        }
        if (found < 0 && anchor.chapterKey != null && !anchor.chapterKey.isEmpty()) {
            for (int i = 0; i < book.size(); i++) {
                if (anchor.chapterKey.equals(book.meta(i).chapterKey)) { found = i; break; }
            }
        }
        if (found < 0 && anchor.spineIndex >= 0) {
            for (int i = 0; i < book.size(); i++) {
                if (book.meta(i).spineIndex == anchor.spineIndex) { found = i; break; }
            }
        }
        if (found < 0) found = Math.max(0, Math.min(anchor.chapterOrdinal, book.size() - 1));
        if (offsetOut[0] < 0) offsetOut[0] = anchor.chapterCharOffset;
        LazyChapterBook.Section section = book.meta(found);
        offsetOut[0] = Math.max(0, Math.min(offsetOut[0], section.charCount));
        return found;
    }
}
