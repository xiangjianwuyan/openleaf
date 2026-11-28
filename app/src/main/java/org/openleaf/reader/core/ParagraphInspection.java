package org.openleaf.reader;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Read-only, bounded inspection of already-decoded text. Never retains the text in its output. */
final class ParagraphInspection {
    static final int MAX_INPUT_UNITS = 256 * 1024;
    static final int MAX_EXAMPLES_PER_KIND = 12;
    static final int LONG_PARAGRAPH_POINTS = 500;
    private static final int MIN_WRAP_LINE_POINTS = 24;
    private static final int MAX_WRAP_LINE_POINTS = 160;
    private static final int CHECK_MASK = 1023;

    private ParagraphInspection() { }

    enum Kind { LONG_PARAGRAPH, CONSECUTIVE_BLANKS, SUSPECTED_HARD_WRAP }

    /** 1-based inclusive line ranges and 0-based UTF-16 [start, end) offsets into the exact input.
     * Magnitude: trimmed code points for a long paragraph (no line separators), blank lines for a
     * blank run, or trimmed code points in the first line for a suspected wrap. The range excludes
     * the last line's terminator; a physically empty last line can therefore have zero width.
     */
    record Finding(Kind kind, int firstLine, int lastLine, int startOffset, int endOffset, int magnitude) { }

    /** Counts describe the entire input even when the immutable example list is capped per kind. */
    record Report(int lines, int longParagraphs, int consecutiveBlankRuns, int suspectedHardWraps,
                  List<Finding> examples) {
        Report { examples = java.util.Collections.unmodifiableList(new ArrayList<>(examples)); }
    }

    static Report inspect(String text) throws InterruptedException {
        return inspect(text, () -> false);
    }

    /** Oversize input fails without inspecting a prefix. Cancellation never returns partial results
     * and does not clear the thread's interrupt status. The callback must be cheap and non-blocking.
     */
    static Report inspect(String text, BooleanSupplier cancelled) throws InterruptedException {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(cancelled, "cancelled");
        checkCancelled(cancelled);
        if (text.length() > MAX_INPUT_UNITS) throw new IllegalArgumentException("Paragraph inspection input exceeds limit");
        Collector out = new Collector();
        Range paragraph = new Range(), blanks = new Range();
        Line previous = null;
        char fence = 0;
        int fenceSize = 0;
        int number = 0;
        for (int offset = 0; offset < text.length();) {
            checkCancelled(cancelled);
            Line line = readLine(text, offset, ++number, cancelled);
            offset = line.next;
            int marker = fenceLength(text, line, cancelled);
            char token = marker == 0 ? 0 : text.charAt(line.contentStart);
            if (fence != 0) {
                if (token == fence && marker >= fenceSize && line.contentStart + marker == line.contentEnd) fence = 0;
                continue; // Do not diagnose Markdown code as prose, even if the fence is unclosed.
            }
            if (marker >= 3) {
                fence = token;
                fenceSize = marker;
            }
            if (line.points == 0) {
                paragraph.finish(out, Kind.LONG_PARAGRAPH, LONG_PARAGRAPH_POINTS + 1);
                blanks.add(line, 1);
                previous = null;
                continue;
            }
            blanks.finish(out, Kind.CONSECUTIVE_BLANKS, 2);
            if (marker >= 3 || isStructural(text, line)) {
                paragraph.finish(out, Kind.LONG_PARAGRAPH, LONG_PARAGRAPH_POINTS + 1);
                previous = null;
                continue;
            }
            paragraph.add(line, line.points);
            if (previous != null && suspectedWrap(text, previous, line)) {
                out.add(Kind.SUSPECTED_HARD_WRAP, previous.number, line.number,
                        previous.start, line.end, previous.points);
            }
            previous = line;
        }
        paragraph.finish(out, Kind.LONG_PARAGRAPH, LONG_PARAGRAPH_POINTS + 1);
        blanks.finish(out, Kind.CONSECUTIVE_BLANKS, 2);
        out.examples.sort(java.util.Comparator.comparingInt(Finding::startOffset)
                .thenComparing(Finding::kind));
        checkCancelled(cancelled);
        return new Report(number, out.counts[Kind.LONG_PARAGRAPH.ordinal()],
                out.counts[Kind.CONSECUTIVE_BLANKS.ordinal()],
                out.counts[Kind.SUSPECTED_HARD_WRAP.ordinal()], out.examples);
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new InterruptedException("Paragraph inspection cancelled");
        }
    }

    /** One pass per line. CR, LF and CRLF are line breaks; a trailing separator alone adds no line. */
    private static Line readLine(String text, int start, int number, BooleanSupplier cancelled)
            throws InterruptedException {
        int first = start, last = start, points = 0, spaces = 0;
        int nextCheck = start + CHECK_MASK + 1;
        boolean seen = false;
        int i = start;
        for (; i < text.length();) {
            if (i >= nextCheck) {
                checkCancelled(cancelled);
                nextCheck = i + CHECK_MASK + 1;
            }
            int cp = text.codePointAt(i);
            if (cp == '\r' || cp == '\n') break;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (seen) spaces++;
            } else {
                if (!seen) first = i;
                seen = true;
                points += 1 + spaces;
                spaces = 0;
                last = i + Character.charCount(cp);
            }
            i += Character.charCount(cp);
        }
        int next = i;
        if (i < text.length()) {
            next++;
            if (text.charAt(i) == '\r' && next < text.length() && text.charAt(next) == '\n') next++;
        }
        return new Line(number, start, i, next, first, last, points);
    }

    private static int fenceLength(String text, Line line, BooleanSupplier cancelled) throws InterruptedException {
        if (line.points < 3 || line.contentStart - line.start > 3) return 0;
        char marker = text.charAt(line.contentStart);
        if (marker != '`' && marker != '~') return 0;
        int size = 0;
        while (line.contentStart + size < line.contentEnd && text.charAt(line.contentStart + size) == marker) {
            size++;
            if ((size & CHECK_MASK) == 0) checkCancelled(cancelled);
        }
        return size >= 3 ? size : 0;
    }

    private static boolean isStructural(String text, Line line) {
        if (line.contentStart - line.start >= 4 || text.charAt(line.start) == '\t') return true;
        char first = text.charAt(line.contentStart);
        if ("#>|`~".indexOf(first) >= 0) return true;
        int next = line.contentStart + 1;
        if (next < line.contentEnd && "-+*".indexOf(first) >= 0) {
            char second = text.charAt(next);
            if (Character.isWhitespace(second) || second == first) return true;
        }
        if (first >= '0' && first <= '9') {
            while (next < line.contentEnd && next - line.contentStart < 10 && text.charAt(next) >= '0'
                    && text.charAt(next) <= '9') next++;
            if (next + 1 < line.contentEnd && ".)．、".indexOf(text.charAt(next)) >= 0
                    && Character.isWhitespace(text.charAt(next + 1))) return true;
        }
        return false;
    }

    /** Cautious signal only: two prose-sized unindented lines, a continuation character, and no
     * sentence-ending character on the previous line. Explicit Markdown hard breaks are excluded.
     */
    private static boolean suspectedWrap(String text, Line previous, Line current) {
        if (previous.points < MIN_WRAP_LINE_POINTS || previous.points > MAX_WRAP_LINE_POINTS
                || current.points < MIN_WRAP_LINE_POINTS || current.points > MAX_WRAP_LINE_POINTS
                || current.contentStart != current.start || previous.contentStart != previous.start) return false;
        if (previous.end - previous.contentEnd >= 2 && text.charAt(previous.end - 1) == ' '
                && text.charAt(previous.end - 2) == ' ') return false;
        int before = text.codePointBefore(previous.contentEnd);
        int after = text.codePointAt(current.contentStart);
        boolean endsLikeWord = Character.isLetter(before) || before == ',' || before == '，';
        // UnicodeScript avoids using scripts such as kana as a Latin continuation accidentally.
        Character.UnicodeScript script = Character.UnicodeScript.of(after);
        return endsLikeWord && (script == Character.UnicodeScript.HAN
                || (script == Character.UnicodeScript.LATIN && Character.isLowerCase(after)));
    }

    private record Line(int number, int start, int end, int next, int contentStart, int contentEnd, int points) { }

    private static final class Range {
        private Line first, last;
        private int amount;

        void add(Line line, int magnitude) {
            if (first == null) first = line;
            last = line;
            amount += magnitude;
        }

        void finish(Collector out, Kind kind, int threshold) {
            if (first != null && amount >= threshold) {
                out.add(kind, first.number, last.number, first.start, last.end, amount);
            }
            first = last = null;
            amount = 0;
        }
    }

    private static final class Collector {
        final int[] counts = new int[Kind.values().length];
        final List<Finding> examples = new ArrayList<>();

        void add(Kind kind, int first, int last, int start, int end, int magnitude) {
            if (++counts[kind.ordinal()] <= MAX_EXAMPLES_PER_KIND) {
                examples.add(new Finding(kind, first, last, start, end, magnitude));
            }
        }
    }
}
