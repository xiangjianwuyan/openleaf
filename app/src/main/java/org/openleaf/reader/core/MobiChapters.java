package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MOBI rawml / 纯文本 → 章节列表。纯 JVM，无 Android 类型，无第三方依赖。
 *
 * <p>章节切分采用「宁可不切，也不要切错」的标题启发式：只有明确命中标题信号的行才开新章；
 * 推断不出任何标题时整篇作为单章返回（与 TXT 平铺目录口径一致）。标题行保留在正文首行，
 * 便于 {@code ChapterSurvey.head()} / {@code LazyChapterBook} 零改动复用。</p>
 *
 * <p>本类不做 DRM 判断、不解析二进制 MOBI，只处理已经解码好的文本；上限由调用方传入，
 * 典型值与 {@code BookParser} 对齐：最多 2000 章、每章最多 20000 个 UTF-16 代码单元。</p>
 */
public final class MobiChapters {

    /** 章节标题的最大代码单元数，超出截断（与 BookParser 的 200 上限对齐）。 */
    public static final int MAX_TITLE_CHARS = 200;

    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+.+");
    private static final Pattern NUMBERED = Pattern.compile(
            "^(?:第?[零〇一二三四五六七八九十百千万兩0-9]+[卷部篇集章节節回话話幕期])"
                    + "|(?:^(?:volume|part|book|chapter|chap\\.|ch\\.)\\s+\\S)");
    private static final Pattern SPECIAL = Pattern.compile(
            "^(?:序章|序言|序幕|楔子|引子|前言|引言|开篇|開篇|后记|後記|尾声|尾聲|终章|終章|"
                    + "番外(?:篇)?|附录|附錄|后序|後序|跋|结语|結語|大结局|大結局)");

    private MobiChapters() { }

    public static final class Chapter {
        public final String title;
        public final String text;

        public Chapter(String title, String text) {
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
        }
    }

    /**
     * 按标题启发式切章，再按 {@code maxCharsPerChapter} 细分超长章，最后按 {@code maxChapters} 截断。
     * 任何非正上限或空输入返回不可变空列表；不抛异常。
     */
    public static List<Chapter> split(String rawText, int maxChapters, int maxCharsPerChapter) {
        if (rawText == null || rawText.trim().isEmpty()
                || maxChapters <= 0 || maxCharsPerChapter <= 0) {
            return Collections.emptyList();
        }

        // 1) 逐行扫描，按标题切成「原始章」（标题行保留在正文首行）。
        List<String[]> raw = new ArrayList<>(); // [title, body]
        StringBuilder body = null;
        String pendingTitle = "";
        boolean seenHeading = false;
        String[] lines = rawText.split("\\r\\n|\\r|\\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            String heading = headingOf(trimmed);
            if (heading != null) {
                if (body != null) raw.add(new String[] { pendingTitle, body.toString() });
                body = new StringBuilder();
                pendingTitle = heading;
                seenHeading = true;
            } else if (body == null) {
                body = new StringBuilder(); // 首个标题之前的文字 → 独立章，标题为空
            }
            if (body.length() > 0) body.append('\n');
            body.append(line);
        }
        if (body != null && body.length() > 0) raw.add(new String[] { pendingTitle, body.toString() });

        // 完全没有标题信号：整篇一章，标题留空。
        if (!seenHeading && raw.size() == 1 && raw.get(0)[0].isEmpty()) {
            // 保持原样（标题为空的单章）。
        } else if (raw.isEmpty()) {
            return Collections.emptyList();
        }

        // 2) 每章按字符上限细分，切割优先落在换行处且不拆代理对。
        List<Chapter> chapters = new ArrayList<>();
        for (String[] rc : raw) {
            List<String> pieces = carve(rc[1], maxCharsPerChapter);
            for (int i = 0; i < pieces.size(); i++) {
                String title = pieces.size() == 1 ? rc[0] : suffix(rc[0], i + 1, pieces.size());
                chapters.add(new Chapter(title, pieces.get(i)));
            }
        }

        // 3) 章数上限（截断，不报错；与 §6「章节超过 2000，仅展示前 2000 章」一致）。
        if (chapters.size() > maxChapters) return new ArrayList<>(chapters.subList(0, maxChapters));
        return chapters;
    }

    /** 返回标题文本；非标题行返回 null。 */
    private static String headingOf(String trimmed) {
        if (trimmed.isEmpty()) return null;
        String t = trimmed;
        if (MD_HEADING.matcher(t).matches()) {
            t = t.replaceFirst("^#{1,6}\\s+", "").trim();
        } else if (!NUMBERED.matcher(t).find() && !SPECIAL.matcher(t).find()) {
            return null;
        }
        if (t.isEmpty()) return null;
        return t.length() <= MAX_TITLE_CHARS ? t : t.substring(0, MAX_TITLE_CHARS) + "…";
    }

    /** 把长文本按上限切成多段，优先在换行 / 空格处收尾，且不拆 UTF-16 代理对。 */
    private static List<String> carve(String text, int limit) {
        if (text.length() <= limit) return Collections.singletonList(text);
        List<String> out = new ArrayList<>();
        int start = 0;
        int n = text.length();
        while (start < n) {
            int end = Math.min(start + limit, n);
            if (end < n) {
                int cut = cutBefore(text, start, end);
                if (cut > start) end = cut;
            }
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }

    /** 在 (start, end] 区间找合适的切割点：换行 > 空格 > 安全字符边界。 */
    private static int cutBefore(String text, int start, int end) {
        int nl = Math.max(text.lastIndexOf('\n', end - 1), text.lastIndexOf('\r', end - 1));
        if (nl > start) return nl + 1;
        int sp = text.lastIndexOf(' ', end - 1);
        if (sp > start) return sp + 1;
        // 避免把高代理留在上一段末尾。
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            return end - 1;
        }
        return end;
    }

    private static String suffix(String title, int index, int total) {
        String tag = " (" + index + "/" + total + ")";
        int room = MAX_TITLE_CHARS - tag.length();
        String base = title.length() <= room ? title : title.substring(0, Math.max(0, room));
        return base + tag;
    }
}
