package org.openleaf.reader.core;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative line-based discovery for text books; never sorts or changes the source text. */
public final class TextChapterHeadings {
    private static final String GAP = "[\\p{Zs}\\t]";
    private static final String CJK_NUMBER = "[0-9零〇○一二三四五六七八九十百千万萬亿億两兩壹贰貳叁參肆伍陆陸柒捌玖拾佰仟廿卅卌]{1,20}";
    private static final String ROMAN = "[IVXLCDM]{1,15}";
    private static final String NUMBER = "(?:[0-9]{1,9}(?:\\.[0-9]{1,5}){0,4}|" + ROMAN + ")";
    private static final Pattern ATX = Pattern.compile("^#{1,6}" + GAP + "+(.+)$");
    private static final Pattern ATX_CLOSING = Pattern.compile(GAP + "+#+$");
    private static final Pattern NOT_MARKDOWN_TITLE = Pattern.compile("^#+$");
    private static final String MARKER = "[章节節回卷部篇集幕话話期]";
    private static final Pattern CJK_PREFIX = Pattern.compile("^(?:第" + GAP + "*" + CJK_NUMBER + GAP + "*" + MARKER
            + "|巻" + GAP + "*" + CJK_NUMBER + "|제" + GAP + "*" + CJK_NUMBER + GAP + "*[장화회권]|"
            + MARKER + GAP + "*" + CJK_NUMBER + ")");
    private static final String ENGLISH_UNITS = "(?:one|two|three|four|five|six|seven|eight|nine)";
    private static final String ENGLISH_WORD_NUMBER = "(?:(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[ -]"
            + ENGLISH_UNITS + ")?|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|zero|" + ENGLISH_UNITS + ")";
    private static final Pattern ENGLISH_PREFIX = Pattern.compile("^(?:chapter|chap\\.|ch\\.|part|section|episode|book|volume|vol\\.|act|scene)"
            + "(?:" + GAP + "*" + "(?:#" + GAP + "*)?(" + NUMBER + ")|" + GAP + "+" + ENGLISH_WORD_NUMBER + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALID_ROMAN = Pattern.compile("M{0,4}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPACT_PROSE = Pattern.compile("^(?:中[，、: ]|里[，、: ]|中提到|中写道|的内容|的正文|讲述了|描述了|已经|仍然|读完了|看完了|将于)");
    private static final Pattern DECORATED = Pattern.compile("^【([^【】]+)】$");
    private static final Pattern NON_CHAPTER_BOX = Pattern.compile("^(?:广告|重要提示|温馨提示|免责声明|作者有话说|求收藏|求推荐|目录|本章完|未完待续)(?:$|[:：\\s])");
    private static final Pattern SPECIAL_CJK = Pattern.compile("^(?:序章|序言|序幕|楔子|引子|前言|引言|开篇|開篇|後記|后记|尾声|尾聲|终章|終章|"
            + "终曲|終曲|跋语|跋語|题记|題記|大结局|大結局|结语|結語|番外(?:篇)?|附录|附錄|卷首语|卷首語|上篇|中篇|下篇)"
            + "(?:" + GAP + "*" + CJK_NUMBER + "(?=$|[\\p{Zs}\\t:：.．、,，–—·|\\-]))?");
    private static final Pattern SPECIAL_ENGLISH = Pattern.compile("^(?:prologue|epilogue|preface|foreword|afterword|interlude|appendix|acknowledg(?:e)?ments)(?:"
            + GAP + "+(?:[A-Z]|" + NUMBER + ")(?=$|[^A-Za-z0-9]))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGIT_OUTLINE = Pattern.compile("^(?:[0-9]{1,6}(?:\\.[0-9]{1,5}){1,4}(?:[.、:：)]|" + GAP + ")|"
            + "[0-9]{1,6}[.、:：)]|0[0-9]{2,5}" + GAP + "+|[一二三四五六七八九十百]{1,8}、)(?![0-9])" + GAP + "*(.+)$");
    private static final Pattern ROMAN_OUTLINE = Pattern.compile("^(" + ROMAN + ")[.、:：)]" + GAP + "*(.+)$");

    private TextChapterHeadings() { }

    /**
     * @param line one logical source line, not a paragraph
     * @param isolated true when surrounding lines are blank or at book boundaries
     */
    public static boolean isHeading(String line, boolean isolated) {
        if (line == null || line.length() > 512 || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) return false;
        String value = strip(Normalizer.normalize(line, Normalizer.Form.NFKC));
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 120) return false;
        Matcher markdown = ATX.matcher(value);
        if (markdown.matches()) {
            String title = strip(ATX_CLOSING.matcher(markdown.group(1)).replaceFirst(""));
            return !title.isEmpty() && !NOT_MARKDOWN_TITLE.matcher(title).matches();
        }
        String candidate = unwrapLeadingBracket(value);
        if (matchesPrefix(CJK_PREFIX, candidate, isolated, true)) return true;
        if (matchesPrefix(ENGLISH_PREFIX, candidate, isolated, false)) return true;
        // Unnumbered labels are accepted alone, or with a separated subtitle on an isolated line.
        if (matchesSpecial(SPECIAL_CJK, candidate, isolated) || matchesSpecial(SPECIAL_ENGLISH, candidate, isolated)) return true;
        if (!isolated) return false;
        Matcher decorated = DECORATED.matcher(value);
        if (decorated.matches()) {
            String label = strip(decorated.group(1));
            return plausibleShortTitle(label) && !NON_CHAPTER_BOX.matcher(label).find();
        }
        Matcher outline = DIGIT_OUTLINE.matcher(candidate);
        if (outline.matches()) return plausibleShortTitle(strip(outline.group(1)));
        outline = ROMAN_OUTLINE.matcher(candidate);
        return outline.matches() && VALID_ROMAN.matcher(outline.group(1)).matches()
                && plausibleShortTitle(strip(outline.group(2)));
    }

    private static boolean matchesPrefix(Pattern prefix, String value, boolean isolated, boolean tightCjk) {
        Matcher match = prefix.matcher(value);
        if (!match.find()) return false;
        if (prefix == ENGLISH_PREFIX) {
            String number = match.group(1);
            if (number != null && !Character.isDigit(number.charAt(0)) && !VALID_ROMAN.matcher(number).matches()) return false;
        }
        int end = match.end();
        if (end == value.length()) return true;
        int next = value.codePointAt(end);
        if (isBoundary(next)) {
            String tail = strip(value.substring(end));
            while (!tail.isEmpty() && isBoundary(tail.codePointAt(0))) {
                tail = strip(tail.substring(Character.charCount(tail.codePointAt(0))));
            }
            if (tail.isEmpty()) return ".".equals(value.substring(end));
            return !endsUnfinished(tail) && !tail.endsWith(".") && !COMPACT_PROSE.matcher(tail).find();
        }
        // Compact titles like 第十二章归来 have no delimiter. Do not split an ordinary
        // paragraph beginning with a chapter reference unless the line is genuinely isolated.
        String tail = value.substring(end);
        return tightCjk && isolated && Character.isLetter(next) && plausibleShortTitle(tail)
                && !COMPACT_PROSE.matcher(tail).find();
    }

    private static boolean matchesSpecial(Pattern prefix, String value, boolean isolated) {
        Matcher match = prefix.matcher(value);
        if (!match.find()) return false;
        int end = match.end();
        if (end == value.length()) return true;
        return isolated && isBoundary(value.codePointAt(end)) && plausibleShortTitle(strip(value.substring(end)));
    }

    private static String unwrapLeadingBracket(String value) {
        char begin = value.charAt(0);
        char close = begin == '[' ? ']' : begin == '(' ? ')' : begin == '【' ? '】' : '\0';
        if (close == '\0') return value;
        int end = value.indexOf(close, 1);
        if (end < 2) return value;
        if (end == value.length() - 1) return strip(value.substring(1, end));
        int next = value.codePointAt(end + 1);
        String tail = strip(value.substring(end + 1));
        if (!isBoundary(next) && (!Character.isLetter(next) || COMPACT_PROSE.matcher(tail).find())) return value;
        return strip(value.substring(1, end)) + " " + tail;
    }

    private static boolean plausibleShortTitle(String value) {
        int size = value.codePointCount(0, value.length());
        if (size == 0 || size > 55 || endsUnfinished(value) || value.endsWith(".")) return false;
        // Exclude numeric values/versions and link-like rows that are not chapter titles.
        if (value.contains("://")) return false;
        for (int p = 0; p < value.length();) {
            int cp = value.codePointAt(p);
            if (Character.isLetter(cp)) return true;
            p += Character.charCount(cp);
        }
        return false;
    }

    private static boolean endsUnfinished(String value) {
        return "。;；,，、:：-–—".indexOf(value.codePointBefore(value.length())) >= 0;
    }

    private static boolean isBoundary(int cp) {
        return isSpace(cp) || ":：.．、,，-–—·|".indexOf(cp) >= 0;
    }

    private static boolean isSpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == '\uFEFF';
    }

    private static String strip(String value) {
        int start = 0, end = value.length();
        while (start < end && isSpace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && isSpace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }
}
