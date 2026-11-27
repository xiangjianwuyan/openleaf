package org.openleaf.reader;

import java.util.Random;

/** Pure JVM coverage of line height geometry and anchor mapping, no Android SDK needed. */
public final class ReaderPagingTest {
    public static void main(String[] args) {
        ReaderPaging blank = ReaderPaging.fromLineBottoms(new int[]{}, 10);
        expect(blank.count() == 1 && blank.pageForLine(4) == 0 && blank.endLine(0) == 0);
        ReaderPaging pages = ReaderPaging.fromLineBottoms(new int[]{10, 20, 33, 38, 62, 63, 70}, 20);
        expect(pages.count() == 4);
        expect(pages.firstLine(0) == 0 && pages.endLine(0) == 2);
        expect(pages.firstLine(1) == 2 && pages.endLine(1) == 4);
        expect(pages.firstLine(2) == 4 && pages.endLine(2) == 5);
        expect(pages.firstLine(3) == 5 && pages.endLine(3) == 7);
        expect(pages.pageForLine(-1) == 0 && pages.pageForLine(7) == 3 && pages.pageForLine(4) == 2);
        Random random = new Random(71);
        for (int fixture = 0; fixture < 200; fixture++) {
            int n = 1 + random.nextInt(100);
            int available = 1 + random.nextInt(200);
            int[] bottoms = new int[n];
            for (int i = 0; i < n; i++) bottoms[i] = (i > 0 ? bottoms[i - 1] : 0) + 1 + random.nextInt(80);
            ReaderPaging map = ReaderPaging.fromLineBottoms(bottoms, available);
            int nextStart = 0;
            for (int p = 0; p < map.count(); p++) {
                int start = map.firstLine(p), end = map.endLine(p);
                expect(nextStart == start && end > start);
                int top = start == 0 ? 0 : bottoms[start - 1];
                expect(end == start + 1 || bottoms[end - 1] - top <= available);
                expect(end == n || bottoms[end] - top > available);
                for (int line = start; line < end; line++) expect(map.pageForLine(line) == p);
                nextStart = end;
            }
            expect(nextStart == n);
        }
        System.out.println("PASS: horizontal pagination line coverage, variable height and viewport anchors (200 fixtures)");
    }

    private static void expect(boolean condition) {
        if (!condition) throw new AssertionError("unexpected page geometry");
    }
}
