package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** UTF-16 literal/regex/word/near search; matching offsets are never rewritten. */
public final class BookSearch {
    public static final int MAX_QUERY_LENGTH = 256;
    public static final int MAX_REGEX_LENGTH = 128;
    public static final int MAX_NEAR_TERMS = 4;
    public static final int DEFAULT_NEAR_WINDOW = 64;
    public static final int MAX_NEAR_WINDOW = 512;
    public static final int LITERAL = 0, REGEX = 1, WORD = 2, NEAR = 3;

    private BookSearch() { }

    public static final class Hit {
        public final int chapter, start, end, snippetStart;
        public final String snippet;
        public final int mode;
        public final int[] matchStarts, matchEnds;
        /** 作品级 UTF-16 字符偏移；-1 表示未知（旧构造器 / 无源偏移来源）。 */
        public final long sourceStart, sourceEnd;
        Hit(int chapter, int start, int end, int snippetStart, String snippet) {
            this(chapter, start, end, snippetStart, snippet, LITERAL, new int[]{start}, new int[]{end});
        }
        Hit(int chapter, int start, int end, int snippetStart, String snippet,
            int mode, int[] matchStarts, int[] matchEnds) {
            this(chapter, start, end, snippetStart, snippet, mode, matchStarts, matchEnds, -1L);
        }
        /** chapterSourceStart = Σ 前序章字符数；未知传 -1，作品级偏移同样落 -1。 */
        Hit(int chapter, int start, int end, int snippetStart, String snippet,
            int mode, int[] matchStarts, int[] matchEnds, long chapterSourceStart) {
            this.chapter = chapter;
            this.start = start;
            this.end = end;
            this.snippetStart = snippetStart;
            this.snippet = snippet;
            this.mode = mode;
            this.matchStarts = matchStarts == null ? new int[]{start} : matchStarts.clone();
            this.matchEnds = matchEnds == null ? new int[]{end} : matchEnds.clone();
            this.sourceStart = chapterSourceStart < 0 ? -1L : chapterSourceStart + start;
            this.sourceEnd = chapterSourceStart < 0 ? -1L : chapterSourceStart + end;
        }
    }

    public static final class Result {
        public final List<Hit> hits;
        public final boolean truncated;
        Result(List<Hit> hits, boolean truncated) {
            this.hits = Collections.unmodifiableList(new ArrayList<>(hits));
            this.truncated = truncated;
        }
    }

    private interface Chapters {
        int size();
        String load(int index);
        void release(int index);
        /** 该章的作品级起点（Σ 前序章字符数）；未知返回 -1。 */
        long sourceStart(int index);
    }

    public static Result find(BookParser.BookContent book, String query, boolean caseSensitive,
                              int chapterScope, int limit, BooleanSupplier cancelled) {
        return find(book, query, caseSensitive, chapterScope, limit, cancelled, LITERAL, DEFAULT_NEAR_WINDOW);
    }

    public static Result find(BookParser.BookContent book, String query, boolean caseSensitive,
                              int chapterScope, int limit, BooleanSupplier cancelled,
                              int mode, int nearWindow) {
        final List<BookParser.Chapter> chapters = book == null ? null : book.chapters;
        return search(new Chapters() {
            public int size() { return chapters == null ? 0 : chapters.size(); }
            public String load(int index) { return chapters.get(index).text; }
            public void release(int index) { }
            public long sourceStart(int index) {
                long sum = 0;
                for (int i = 0; i < index; i++) {
                    String text = chapters.get(i).text;
                    sum += text == null ? 0 : text.length();
                }
                return sum;
            }
        }, query, caseSensitive, chapterScope, limit, cancelled, mode, nearWindow);
    }

    public static Result find(LazyChapterBook book, String query, boolean caseSensitive,
                              int chapterScope, int limit, BooleanSupplier cancelled,
                              int mode, int nearWindow) {
        return search(new Chapters() {
            public int size() { return book == null ? 0 : book.size(); }
            public String load(int index) { try { return book.load(index); } catch (Exception e) { throw new IllegalStateException(e); } }
            public void release(int index) { book.release(index); }
            public long sourceStart(int index) { return SourceOffset.cumulative(book, index); }
        }, query, caseSensitive, chapterScope, limit, cancelled, mode, nearWindow);
    }

    private static Result search(Chapters chapters, String query, boolean caseSensitive,
                                 int chapterScope, int limit, BooleanSupplier cancelled,
                                 int mode, int nearWindow) {
        if (chapters == null || chapters.size() == 0) throw new IllegalArgumentException("书籍为空");
        if (chapterScope < -1 || chapterScope >= chapters.size()) throw new IllegalArgumentException("章节范围无效");
        if (query == null) query = "";
        if (query.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException("关键词最多256个字符");
        if (limit < 1 || limit > 10000) throw new IllegalArgumentException("结果上限需在1至10000之间");
        if (mode < LITERAL || mode > NEAR) throw new IllegalArgumentException("搜索模式无效");
        if (mode == REGEX && query.length() > MAX_REGEX_LENGTH) throw new IllegalArgumentException("正则最多128个字符");
        if (nearWindow < 8 || nearWindow > MAX_NEAR_WINDOW) nearWindow = DEFAULT_NEAR_WINDOW;
        check(cancelled);
        ArrayList<Hit> hits = new ArrayList<>();
        final boolean[] truncated = new boolean[1];
        if (query.isEmpty()) return new Result(hits, false);
        String[] nearTerms = mode == NEAR ? splitTerms(query) : new String[0];
        if (mode == NEAR && nearTerms.length > MAX_NEAR_TERMS) throw new IllegalArgumentException("附近词最多4个");
        if (mode == NEAR && nearTerms.length == 0) return new Result(hits, false);
        if (mode == NEAR && nearTerms.length == 1) mode = LITERAL;
        Pattern regex = null;
        if (mode == REGEX) {
            validateRegex(query);
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            try { regex = Pattern.compile(query, flags); }
            catch (PatternSyntaxException bad) { throw new IllegalArgumentException("正则表达式无效：" + bad.getDescription()); }
        }
        int first = chapterScope < 0 ? 0 : chapterScope;
        int last = chapterScope < 0 ? chapters.size() : chapterScope + 1;
        for (int chapter = first; chapter < last; chapter++) {
            check(cancelled);
            String text = chapters.load(chapter);
            long chapterSourceStart = chapters.sourceStart(chapter);
            try {
                if (mode == REGEX) searchRegex(text, regex, chapter, chapterSourceStart, mode, limit, hits, truncated, cancelled);
                else if (mode == NEAR) searchNear(text, nearTerms, nearWindow, caseSensitive, chapter, chapterSourceStart, mode, limit, hits, truncated, cancelled);
                else searchLiteral(text, query, caseSensitive, mode == WORD, chapter, chapterSourceStart, mode, limit, hits, truncated, cancelled);
            } finally {
                chapters.release(chapter);
            }
            if (truncated[0]) break;
        }
        check(cancelled);
        return new Result(hits, truncated[0]);
    }

    private static void searchLiteral(String text, String query, boolean caseSensitive, boolean wholeWord,
                                      int chapter, long chapterSourceStart, int mode, int limit, List<Hit> hits, boolean[] truncated,
                                      BooleanSupplier cancelled) {
        int last = text.length() - query.length();
        for (int start = 0; start <= last; start++) {
            if ((start & 255) == 0) check(cancelled);
            if (!text.regionMatches(!caseSensitive, start, query, 0, query.length())) continue;
            int end = start + query.length();
            if (wholeWord && !isWordMatch(text, start, end, query)) continue;
            if (hits.size() >= limit) { truncated[0] = true; return; }
            hits.add(makeHit(chapter, start, end, mode, new int[]{start}, new int[]{end}, text, chapterSourceStart));
        }
    }

    private static boolean isWordMatch(String text, int start, int end, String query) {
        boolean cjkQuery = false;
        for (int i = 0; i < query.length(); ) {
            int cp = query.codePointAt(i);
            if (isCjk(cp)) { cjkQuery = true; break; }
            i += Character.charCount(cp);
        }
        int before = start == 0 ? -1 : text.codePointBefore(start);
        int after = end >= text.length() ? -1 : text.codePointAt(end);
        if (cjkQuery) return !isCjk(before) && !isCjk(after);
        return !isWordPart(before) && !isWordPart(after);
    }

    private static boolean isWordPart(int cp) {
        if (cp < 0) return false;
        return isCjk(cp) || cp == '_' || Character.isLetterOrDigit(cp);
    }

    private static boolean isCjk(int cp) {
        return cp >= 0x3400 && cp <= 0x9FFF || cp >= 0xF900 && cp <= 0xFAFF || cp >= 0x20000;
    }

    private static void searchRegex(String text, Pattern pattern, int chapter, long chapterSourceStart, int mode, int limit,
                                    List<Hit> hits, boolean[] truncated, BooleanSupplier cancelled) {
        Matcher matcher = pattern.matcher(text);
        int from = 0;
        int guard = 0;
        while (from <= text.length() && hits.size() < limit) {
            if ((guard++ & 255) == 0) check(cancelled);
            if (!matcher.find(from)) break;
            int start = matcher.start(), end = matcher.end();
            if (hits.size() >= limit) { truncated[0] = true; return; }
            hits.add(makeHit(chapter, start, end, mode, new int[]{start}, new int[]{end}, text, chapterSourceStart));
            from = end == start ? start + 1 : start + 1;
            if (from > text.length()) break;
        }
    }

    private static void searchNear(String text, String[] terms, int window, boolean caseSensitive,
                                   int chapter, long chapterSourceStart, int mode, int limit, List<Hit> hits, boolean[] truncated,
                                   BooleanSupplier cancelled) {
        ArrayList<int[]>[] occurrences = new ArrayList[terms.length];
        for (int t = 0; t < terms.length; t++) {
            occurrences[t] = new ArrayList<int[]>();
            String term = terms[t];
            int last = text.length() - term.length();
            for (int i = 0; i <= last; i++) {
                if ((i & 255) == 0) check(cancelled);
                if (text.regionMatches(!caseSensitive, i, term, 0, term.length()))
                    occurrences[t].add(new int[]{i, i + term.length()});
            }
        }
        if (occurrences[0].isEmpty()) return;
        HashSetLike seen = new HashSetLike();
        for (int[] left : occurrences[0]) {
            check(cancelled);
            int[] starts = new int[terms.length];
            int[] ends = new int[terms.length];
            Arrays.fill(starts, -1);
            starts[0] = left[0]; ends[0] = left[1];
            int maxEnd = left[1];
            for (int t = 1; t < terms.length; t++) {
                int[] best = null;
                for (int[] candidate : occurrences[t]) {
                    if (candidate[0] < left[0]) continue;
                    if (text.codePointCount(left[0], candidate[1]) > window) break;
                    best = candidate;
                    break;
                }
                if (best == null) break;
                starts[t] = best[0]; ends[t] = best[1];
                maxEnd = Math.max(maxEnd, best[1]);
            }
            boolean complete = true;
            for (int v : starts) if (v < 0) { complete = false; break; }
            if (!complete || text.codePointCount(left[0], maxEnd) > window) continue;
            if (!seen.add(left[0], maxEnd)) continue;
            if (hits.size() >= limit) { truncated[0] = true; return; }
            hits.add(makeHit(chapter, left[0], maxEnd, mode, starts, ends, text, chapterSourceStart));
        }
    }

    private static final class HashSetLike {
        private final List<Long> keys = new ArrayList<Long>();
        boolean add(int start, int end) {
            long key = ((long) start << 32) | (end & 0xFFFFFFFFL);
            if (keys.contains(key)) return false;
            keys.add(key);
            return true;
        }
    }

    private static Hit makeHit(int chapter, int start, int end, int mode,
                               int[] starts, int[] ends, String text, long chapterSourceStart) {
        int left = backCodePoints(text, start, 45), right = forwardCodePoints(text, end, 45);
        if (left > 0 && left < text.length() && Character.isLowSurrogate(text.charAt(left))
                && Character.isHighSurrogate(text.charAt(left - 1))) left--;
        if (right > 0 && right < text.length() && Character.isHighSurrogate(text.charAt(right - 1))
                && Character.isLowSurrogate(text.charAt(right))) right++;
        return new Hit(chapter, start, end, left, text.substring(left, right), mode, starts, ends, chapterSourceStart);
    }

    private static String[] splitTerms(String query) {
        String[] raw = query.split("[\\s,，、;；]+");
        ArrayList<String> result = new ArrayList<String>();
        for (String term : raw) if (!term.isEmpty()) result.add(term);
        return result.toArray(new String[0]);
    }

    private static void validateRegex(String pattern) {
        if (pattern.matches("(?s).*(?<!\\\\) \\\\[1-9].*")) throw new IllegalArgumentException("暂不支持正则反向引用");
        if (Pattern.compile("\\([^()]*[+*][^()]*\\)[+*{]").matcher(pattern).find())
            throw new IllegalArgumentException("暂不支持嵌套量词，可能造成搜索卡死");
    }

    private static int backCodePoints(String text, int at, int count) {
        int result = at;
        for (int i = 0; i < count && result > 0; i++) result = text.offsetByCodePoints(result, -1);
        return result;
    }

    private static int forwardCodePoints(String text, int at, int count) {
        int result = at;
        for (int i = 0; i < count && result < text.length(); i++) result = text.offsetByCodePoints(result, 1);
        return result;
    }

    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean())
            throw new CancellationException("查找已取消");
    }
}
