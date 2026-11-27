package org.openleaf.reader;

import java.util.Random;

/**
 * READ-1 纯 JVM 覆盖：持久锚点是字符/行位置，页码只是当前布局的派生结果。
 * 同一「含该字符的行」在不同 bottoms 布局下始终落到包含该行的页。
 */
public final class ReaderPagingAnchorTest {
    public static void main(String[] args) {
        firstAndLastPageBoundaries();
        characterAnchorFollowsItsLineAcrossLayouts();
        randomGeometryNoGapNoOverlap();
        System.out.println("PASS: READ-1 paging anchor boundaries, char-to-page coverage and 300 random layouts");
    }

    /** 首/末页边界与空文本：任何偏移都归到实际含该字符的页。 */
    private static void firstAndLastPageBoundaries() {
        int[] bottoms = {12, 24, 36, 48, 60};
        ReaderPaging pages = ReaderPaging.fromLineBottoms(bottoms, 24);
        expect(pages.count() >= 2);
        expect(pages.firstLine(0) == 0);
        expect(pages.endLine(pages.count() - 1) == bottoms.length);
        expect(pages.pageForLine(0) == 0);
        expect(pages.pageForLine(bottoms.length - 1) == pages.count() - 1);

        ReaderPaging blank = ReaderPaging.fromLineBottoms(new int[0], 10);
        expect(blank.count() == 1 && blank.firstLine(0) == 0 && blank.endLine(0) == 0);
        expect(blank.pageForLine(0) == 0 && blank.pageForLine(999) == 0);
    }

    /** 字符锚点固定在其所在行；布局改变页码可变，但页区间必须始终包含该行。 */
    private static void characterAnchorFollowsItsLineAcrossLayouts() {
        int lineCount = 41;
        int[] dense = new int[lineCount];
        int[] sparse = new int[lineCount];
        for (int i = 0; i < lineCount; i++) {
            dense[i] = i * 12;
            sparse[i] = i * 30;
        }
        ReaderPaging tallLine = ReaderPaging.fromLineBottoms(new int[]{12, 24, 900, 912, 924}, 60);
        ReaderPaging[] layouts = {
                ReaderPaging.fromLineBottoms(dense, 60),
                ReaderPaging.fromLineBottoms(sparse, 30),
                tallLine
        };
        for (ReaderPaging pages : layouts) {
            int lines = pages.endLine(pages.count() - 1);
            for (int line = 0; line < lines; line++) {
                int page = pages.pageForLine(line);
                expect(page >= 0 && page < pages.count());
                expect(pages.firstLine(page) <= line && line < pages.endLine(page));
            }
        }
        // 同一行在稀疏布局里页码更大：证明页码是布局派生的，不是锚点本身。
        expect(ReaderPaging.fromLineBottoms(sparse, 30).pageForLine(20)
                > ReaderPaging.fromLineBottoms(dense, 60).pageForLine(20));
    }

    /** 随机几何下每页连续、无漏行无重叠、pageForLine 全覆盖。超高行仍独占一页。 */
    private static void randomGeometryNoGapNoOverlap() {
        Random random = new Random(1771);
        for (int fixture = 0; fixture < 300; fixture++) {
            int n = 1 + random.nextInt(120);
            int available = 1 + random.nextInt(160);
            int[] bottoms = new int[n];
            for (int i = 0; i < n; i++) {
                bottoms[i] = (i > 0 ? bottoms[i - 1] : 0) + 1 + random.nextInt(90);
            }
            ReaderPaging pages = ReaderPaging.fromLineBottoms(bottoms, available);
            int next = 0;
            for (int p = 0; p < pages.count(); p++) {
                int start = pages.firstLine(p), end = pages.endLine(p);
                expect(start == next && end > start);
                int top = start == 0 ? 0 : bottoms[start - 1];
                expect(end == start + 1 || bottoms[end - 1] - top <= available);
                expect(end == n || bottoms[end] - top > available);
                for (int line = start; line < end; line++) expect(pages.pageForLine(line) == p);
                next = end;
            }
            expect(next == n);
        }
    }

    private static void expect(boolean condition) {
        if (!condition) throw new AssertionError("unexpected paging anchor geometry");
    }
}
