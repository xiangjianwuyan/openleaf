package org.openleaf.reader.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 提要拼装：章节首句提取 + 首屏行 + 空标题占位。纯 JVM，无 Android 类型。 */
public final class DigestBuilder {
    public static final int DEFAULT_LEAD_CHARS = 60;
    public static final int DEFAULT_MAX_LINES  = 50;   // Q5=B：前 50 章 + 「显示全部」

    private static final Pattern MD_LINE = Pattern.compile("^#{1,6} ");

    private DigestBuilder() { }

    public static final class Line {
        public final int chapter;
        public final String title;
        public final String lead;

        Line(int chapter, String title, String lead) {
            this.chapter = chapter;
            this.title = title;
            this.lead = lead;
        }
    }

    public static final class Digest {
        public final String bookTitle;
        public final List<String> tags;
        public final List<Line> lines;
        public final int totalChapters;
        public final boolean truncated;   // lines 少于此值说明还有「未展示」的章

        Digest(String bookTitle, List<String> tags, List<Line> lines,
               int totalChapters, boolean truncated) {
            this.bookTitle = bookTitle;
            this.tags = tags;
            this.lines = lines;
            this.totalChapters = totalChapters;
            this.truncated = truncated;
        }
    }

    public static String placeholderTitle(int index) {            // "第 N 章"（index 从 0 起 → N=index+1）
        return "第" + (index + 1) + "章";
    }

    /**
     * 首句提取：head 空 → ""；按 \n 切行 trim；跳过空行、与章节标题重复行、#{1,6} 前缀行；
     * 取第一行有效文本，按码点截断到 maxChars 加 …（绝不切开代理对）；整章只有标题 → ""。
     */
    public static String lead(String chapterTitle, String head, int maxChars) {
        if (head == null || head.isEmpty() || maxChars <= 0) return "";
        String foldedTitle = fold(chapterTitle);
        for (String raw : head.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) continue;                              // 跳过空行
            if (MD_LINE.matcher(line).find()) continue;                // 跳过 MD 标题重复
            if (!foldedTitle.isEmpty() && fold(line).equals(foldedTitle)) continue; // 跳过章节标题重复
            return clip(line, maxChars);                               // 第一行有效文本
        }
        return "";
    }

    public static Digest build(String bookTitle, List<String> tags, ChapterSurvey survey, int maxLines) {
        List<String> safeTags = tags == null ? Collections.emptyList() : new ArrayList<>(tags);
        int limit = Math.min(Math.max(0, maxLines), survey.heads.size());
        List<Line> lines = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            String raw = i < survey.titles.length ? survey.titles[i] : null;
            String title = (raw == null || raw.trim().isEmpty()) ? placeholderTitle(i) : raw;
            String first = lead(raw == null ? "" : raw, survey.heads.get(i).text, DEFAULT_LEAD_CHARS);
            lines.add(new Line(i, title, first));
        }
        return new Digest(bookTitle, safeTags, lines, survey.total, survey.total > lines.size());
    }

    /** 按码点截断并追加 …，绝不切开代理对。 */
    private static String clip(String line, int maxChars) {
        if (line.codePointCount(0, line.length()) <= maxChars) return line;
        int end = line.offsetByCodePoints(0, maxChars);
        return line.substring(0, end) + "…";
    }

    /** NFKC + 去空白 + 忽略大小写。 */
    private static String fold(String s) {
        if (s == null || s.isEmpty()) return "";
        String nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length();) {
            int cp = nfkc.codePointAt(i);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) out.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
