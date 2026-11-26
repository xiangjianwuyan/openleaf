package org.openleaf.reader.core;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * TXT 章节识别规则（纯 JVM，无 Android 依赖，可进入 core-tests）。
 *
 * <p>档位从保守到宽松依次放大命中面：保守档完全委托 {@link TextChapterHeadings#isHeading}，
 * 与 0.26.0 逐字一致；标准档与宽松档在保守判定之上叠加 OpenLeaf 自行编写的紧凑编号正则；
 * 自定义档在保守判定之上叠加用户输入正则（非法正则自动忽略，不抛异常）。
 *
 * <p>唯一判定入口是 {@link #isHeading(String, boolean)}，供 {@link TextChapterIndexer}
 * 重建索引时注入，不改变分章、缓存与锚点的外围语义。
 */
public final class ChapterRule {

    /** 档位，序号与 reader prefs 的 chapter_rule_mode 一一对应。 */
    public enum Mode {
        CONSERVATIVE, // 0 保守（默认）
        STANDARD,     // 1 标准
        LOOSE,        // 2 宽松
        CUSTOM        // 3 自定义正则
    }

    /** 参与附加正则判定的单行最大长度（码点），防止超长行误判与卡顿。 */
    private static final int MAX_LINE_CODEPOINTS = 200;
    /** 自定义正则文本长度上限，防止误粘贴整篇文章。 */
    private static final int MAX_REGEX_CHARS = 512;

    // 以下正则全部由 OpenLeaf 自行编写，只使用 java.util.regex，不取自任何第三方样本。

    /** 标准档：带明确编号与分隔符的短大纲行，如「1. 起航」「第三章：归来」。 */
    private static final Pattern STANDARD_NUMBERED = Pattern.compile(
            "^[0-9]{1,4}[.．、:：)]\\s*\\p{L}[^\\p{Cntrl}]{0,40}$");

    /** 标准档：紧凑中文编号，如「第42章星海」「第7回 重逢」。 */
    private static final Pattern STANDARD_COMPACT_CJK = Pattern.compile(
            "^第[0-9０-９零〇一二三四五六七八九十百千万两]{1,12}[章节回卷部篇集幕话話期]"
                    + "[.．、:：\\-—　 ]{0,3}[\\p{L}\\p{N}][^\\p{Cntrl}]{0,40}$");

    /** 标准档：紧凑英文编号，如「Chapter 12 Awakening」「Vol.3 终章」。 */
    private static final Pattern STANDARD_COMPACT_EN = Pattern.compile(
            "^(?:chapter|chap|ch|part|vol|volume|book|episode)[. ]{1,3}#?"
                    + "[0-9]{1,5}[.．、:：\\-— ]{0,3}[\\p{L}\\p{N}][^\\p{Cntrl}]{0,40}$",
            Pattern.CASE_INSENSITIVE);

    /** 宽松档：方括号/书名号包裹的短标题，如「[5] 暗流」「【尾声】」。 */
    private static final Pattern LOOSE_BRACKET = Pattern.compile(
            "^[\\[【（(][^\\]】）)]{1,40}[\\]】）)][.．、:：\\-—　 ]{0,3}[^\\p{Cntrl}]{0,40}$");

    /** 宽松档：卷/集/话/期标记，如「卷九 长夜」「话12」。 */
    private static final Pattern LOOSE_VOLUME = Pattern.compile(
            "^(?:卷|集|话|話|期|部|篇)[0-9０-９零〇一二三四五六七八九十百千万两]{1,12}"
                    + "[.．、:：\\-—　 ]{0,3}[^\\p{Cntrl}]{0,40}$");

    /** 宽松档：纯数字编号行，如「12」「007」。 */
    private static final Pattern LOOSE_DIGIT_ONLY = Pattern.compile("^[0-9]{1,6}$");

    /** 各档共用排除词：命中这些词的短行几乎都是正文提示或站点水印，不做标题。 */
    private static final Pattern EXCLUDED = Pattern.compile(
            "广告|重要提示|温馨提示|免责声明|作者有话说|求收藏|求推荐|求订阅|最新网址|网址|"
                    + "本章完|未完待续|目录|请收藏|手机阅读");

    private final Mode mode;
    private final String customRegex;
    private final Pattern compiled;

    private ChapterRule(Mode mode, String customRegex, Pattern compiled) {
        this.mode = mode;
        this.customRegex = customRegex;
        this.compiled = compiled;
    }

    /** 默认保守档：判定完全委托现有实现，保证老用户零回归。 */
    public static ChapterRule conservative() {
        return new ChapterRule(Mode.CONSERVATIVE, "", null);
    }

    /**
     * 按档位构造规则。非法正则被静默忽略（降级为仅保守判定），调用方应先用
     * {@link #validateRegex(String)} 校验后再落库。
     */
    public static ChapterRule of(Mode mode, String customRegex) {
        Mode resolved = mode == null ? Mode.CONSERVATIVE : mode;
        String regex = customRegex == null ? "" : customRegex;
        Pattern pattern = null;
        if (resolved == Mode.CUSTOM && !regex.trim().isEmpty()) {
            try {
                pattern = Pattern.compile(regex);
            } catch (RuntimeException invalid) {
                pattern = null; // 绝不把非法正则传给判定路径（含 PatternSyntaxException）
            }
        }
        return new ChapterRule(resolved, regex, pattern);
    }

    public Mode mode() { return mode; }
    public String customRegex() { return customRegex; }
    public boolean isCustom() { return mode == Mode.CUSTOM; }

    /**
     * 校验用户正则。返回 {@code null} 表示合法，否则返回可直接展示的中文错误文案。
     */
    public String validateRegex(String regex) {
        if (regex == null || regex.trim().isEmpty()) return "正则表达式不能为空";
        if (regex.length() > MAX_REGEX_CHARS) return "正则表达式过长（最多 " + MAX_REGEX_CHARS + " 字符）";
        try {
            Pattern.compile(regex);
            return null;
        } catch (PatternSyntaxException invalid) {
            String reason = invalid.getDescription();
            return "正则表达式不合法：" + (reason == null || reason.isEmpty() ? "语法错误" : reason);
        }
    }

    /**
     * 唯一判定入口。保守判定先执行且所有档位共用，附加模式只做增量命中。
     *
     * @param line 一行原始正文（未 trim），允许为 null
     * @param isolated 该行前后均为空行（或处于书边界）时为 true，与索引器口径一致
     */
    public boolean isHeading(String line, boolean isolated) {
        if (line == null || line.isEmpty()) return false;
        // 保守档与 0.26.0 逐字一致：原样委托，不做任何预处理。
        if (TextChapterHeadings.isHeading(line, isolated)) return true;
        if (mode == Mode.CONSERVATIVE) return false;

        String value = trim(Normalizer.normalize(line, Normalizer.Form.NFKC));
        if (value.isEmpty() || value.codePointCount(0, value.length()) > MAX_LINE_CODEPOINTS) return false;
        if (EXCLUDED.matcher(value).find()) return false;

        if (mode == Mode.CUSTOM) {
            return compiled != null && compiled.matcher(value).find();
        }
        if (mode == Mode.STANDARD) {
            return STANDARD_NUMBERED.matcher(value).matches()
                    || STANDARD_COMPACT_CJK.matcher(value).matches()
                    || STANDARD_COMPACT_EN.matcher(value).matches();
        }
        return LOOSE_BRACKET.matcher(value).matches()
                || LOOSE_VOLUME.matcher(value).matches()
                || LOOSE_DIGIT_ONLY.matcher(value).matches()
                || STANDARD_NUMBERED.matcher(value).matches()
                || STANDARD_COMPACT_CJK.matcher(value).matches()
                || STANDARD_COMPACT_EN.matcher(value).matches();
    }

    /**
     * 纯函数预览：按行切分样本文本，用与索引器一致的「前后空行」孤立口径套用同一判定入口，
     * 返回命中行数。maxLines <= 0 表示不限制行数。
     */
    public int previewMatchCount(String sampleText, int maxLines) {
        if (sampleText == null || sampleText.isEmpty()) return 0;
        String normalized = sampleText.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int limit = maxLines <= 0 ? lines.length : Math.min(maxLines, lines.length);
        int matches = 0;
        for (int i = 0; i < limit; i++) {
            boolean previousBlank = i == 0 || isBlank(lines[i - 1]);
            boolean nextBlank = i + 1 >= lines.length || isBlank(lines[i + 1]);
            if (isHeading(lines[i], previousBlank && nextBlank)) matches++;
        }
        return matches;
    }

    private static boolean isBlank(String line) {
        if (line == null) return true;
        for (int i = 0; i < line.length();) {
            int cp = line.codePointAt(i);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && cp != '\uFEFF') return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static String trim(String value) {
        int start = 0, end = value.length();
        while (start < end && Character.isWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && Character.isWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }
}
