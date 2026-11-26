package org.openleaf.reader;

import java.util.Arrays;

/** Immutable page boundaries in Android Layout line coordinates; independent of Android classes. */
final class ReaderPaging {
    private final int[] first;
    private final int lineCount;

    static ReaderPaging fromLineBottoms(int[] bottoms, int availableHeight) {
        int count = bottoms.length;
        if (count == 0) return new ReaderPaging(new int[]{0}, 0);
        long height = Math.max(1, availableHeight);
        int[] starts = new int[count];
        int pages = 0;
        for (int start = 0; start < count;) {
            starts[pages++] = start;
            long limit = (start == 0 ? 0 : bottoms[start - 1]) + height;
            int end = start + 1; // An unusually tall line still occupies exactly one page.
            while (end < count && bottoms[end] <= limit) end++;
            start = end;
        }
        return new ReaderPaging(Arrays.copyOf(starts, pages), count);
    }

    private ReaderPaging(int[] first, int lineCount) {
        this.first = first;
        this.lineCount = lineCount;
    }

    int count() { return first.length; }
    int firstLine(int page) { return first[page]; }
    int endLine(int page) { return page + 1 < first.length ? first[page + 1] : lineCount; }

    int pageForLine(int line) {
        if (lineCount == 0) return 0;
        int match = Arrays.binarySearch(first, Math.max(0, Math.min(lineCount - 1, line)));
        return match >= 0 ? match : -match - 2;
    }
}
