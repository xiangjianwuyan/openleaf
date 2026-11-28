package org.openleaf.reader;

import java.util.concurrent.atomic.AtomicInteger;

import static org.openleaf.reader.ParagraphInspection.Kind;

/** Standalone JVM tests; no Android runtime needed. */
public final class ParagraphInspectionTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        var empty = ParagraphInspection.inspect("");
        check(empty.lines() == 0 && empty.examples().isEmpty(), "empty input");
        check(ParagraphInspection.inspect("a\n").lines() == 1, "no phantom trailing line");
        check(ParagraphInspection.inspect("\n\n").consecutiveBlankRuns() == 1, "real empty lines are counted");
        String blankFixture = "ab\r\n \r\t\u3000\nend";
        var blank = ParagraphInspection.inspect(blankFixture);
        var gap = blank.examples().get(0);
        check(blank.lines() == 4 && blank.consecutiveBlankRuns() == 1, "mixed newline and Unicode blanks");
        check(gap.kind() == Kind.CONSECUTIVE_BLANKS && gap.firstLine() == 2 && gap.lastLine() == 3
                && gap.magnitude() == 2 && blankFixture.substring(gap.startOffset(), gap.endOffset()).equals(" \r\t\u3000"), "blank offset origin");
        check(ParagraphInspection.inspect("a\n\nb").consecutiveBlankRuns() == 0, "single blank line is allowed");

        String supplementary = "\ud83d\udcda";
        check(ParagraphInspection.inspect(supplementary.repeat(500)).longParagraphs() == 0, "threshold is in code points");
        String longText = "  " + supplementary.repeat(300) + "\n" + supplementary.repeat(201) + " \n";
        var longParagraph = ParagraphInspection.inspect(longText).examples().get(0);
        check(longParagraph.kind() == Kind.LONG_PARAGRAPH && longParagraph.magnitude() == 501
                && longParagraph.firstLine() == 1 && longParagraph.lastLine() == 2, "long paragraph spans adjoining prose lines");
        check(longParagraph.startOffset() == 0 && longParagraph.endOffset() == longText.length() - 1,
                "UTF-16 range includes line padding, excludes last separator");
        check(ParagraphInspection.inspect("a".repeat(300) + "\n# Section\n" + "a".repeat(300)).longParagraphs() == 0,
                "heading breaks paragraph");

        String prose = "the harbor is quiet in the late afternoon";
        var wrap = ParagraphInspection.inspect(prose + "\r\n" + prose);
        var wrapped = wrap.examples().get(0);
        check(wrap.suspectedHardWraps() == 1 && wrapped.kind() == Kind.SUSPECTED_HARD_WRAP
                && wrapped.firstLine() == 1 && wrapped.lastLine() == 2, "lowercase continuation is a suspect");
        check(wrapped.endOffset() == prose.length() * 2 + 2, "wrap offset refers to raw CRLF");
        check(ParagraphInspection.inspect("湖".repeat(30) + "，\n" + "风".repeat(30)).suspectedHardWraps() == 1, "Han continuation");
        check(ParagraphInspection.inspect(prose + ".\n" + prose).suspectedHardWraps() == 0, "complete sentence is not suspected");
        check(ParagraphInspection.inspect(prose + "\n" + "A" + prose).suspectedHardWraps() == 0, "uppercase continuation excluded");
        check(ParagraphInspection.inspect(prose + "\n  " + prose).suspectedHardWraps() == 0, "new indentation excluded");
        check(ParagraphInspection.inspect(prose + "  \n" + prose).suspectedHardWraps() == 0, "explicit Markdown break excluded");
        check(ParagraphInspection.inspect(prose + "\n11. " + prose).suspectedHardWraps() == 0, "numbered list excluded");
        check(ParagraphInspection.inspect("- " + prose + "\n" + prose).suspectedHardWraps() == 0, "list marker excluded");
        check(ParagraphInspection.inspect(prose + "\n" + "short").suspectedHardWraps() == 0, "short line excluded");
        var fence = ParagraphInspection.inspect("````txt\n```\n" + "a".repeat(600) + "\n\n\n"
                + prose + "\n" + prose + "\n````\n" + prose + "\n" + prose);
        check(fence.suspectedHardWraps() == 1 && fence.longParagraphs() == 0 && fence.consecutiveBlankRuns() == 0
                && fence.examples().get(0).firstLine() == 9 && fence.examples().get(0).lastLine() == 10, "fenced code ignored; shorter fence not a close");
        check(ParagraphInspection.inspect("~~~\n" + "a".repeat(600)).longParagraphs() == 0, "unclosed fence ignored conservatively");

        String many = ("a".repeat(501) + "\n\n\n" + prose + "\n" + prose + "\n\n\n").repeat(30);
        var full = ParagraphInspection.inspect(many);
        check(full.longParagraphs() == 30 && full.suspectedHardWraps() == 30 && full.consecutiveBlankRuns() == 60,
                "counts continue after cap");
        for (Kind kind : Kind.values()) {
            check(full.examples().stream().filter(f -> f.kind() == kind).count() == ParagraphInspection.MAX_EXAMPLES_PER_KIND,
                    "examples capped separately for " + kind);
        }
        int previousOffset = -1;
        for (var example : full.examples()) {
            if (example.startOffset() < previousOffset) throw new AssertionError("examples out of source order");
            previousOffset = example.startOffset();
        }
        checks++;
        expect(UnsupportedOperationException.class, () -> full.examples().clear(), "immutable list");
        check(ParagraphInspection.inspect("a".repeat(ParagraphInspection.MAX_INPUT_UNITS)).longParagraphs() == 1,
                "exact limit accepted");
        expect(IllegalArgumentException.class, () -> ParagraphInspection.inspect("a".repeat(ParagraphInspection.MAX_INPUT_UNITS + 1)),
                "over-limit rejected without truncating");
        expect(InterruptedException.class, () -> ParagraphInspection.inspect("", () -> true), "explicit cancellation at entry");
        AtomicInteger checksInLine = new AtomicInteger();
        expect(InterruptedException.class, () -> ParagraphInspection.inspect(supplementary.repeat(20_000),
                () -> checksInLine.incrementAndGet() == 4), "cancellation inside a single long line");
        check(checksInLine.get() == 4, "cancellation stops at first signal");
        Thread.currentThread().interrupt();
        try {
            expect(InterruptedException.class, () -> ParagraphInspection.inspect("ordinary input"), "thread interruption");
            check(Thread.currentThread().isInterrupted(), "interrupt flag preserved");
        } finally {
            Thread.interrupted();
        }
        System.out.println("PASS: " + checks + " paragraph inspection checks");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void expect(Class<? extends Exception> type, Action test, String label) throws Exception {
        try {
            test.run();
        } catch (Exception exception) {
            check(type.isInstance(exception), label + ": " + exception);
            return;
        }
        throw new AssertionError(label + ": expected " + type.getSimpleName());
    }
}
