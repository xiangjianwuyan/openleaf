package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落重排纯函数：合并硬换行、折叠多余空行、智能缩进判定（只判定不插字符）。
 *
 * <p>纯 JVM，无 Android 依赖。核心产物是 {displayText, int[] map}：map 按 UTF-16 char
 * 索引，map.length == displayText.length()，map[i] 是显示第 i 个字符在原字符串中的偏移。</p>
 *
 * <p>硬契约（M1–M7）：
 * M1 长度相等；M2 值域 [0,n-1] 且单调不减；M3 保留字符逐字溯源；
 * M4 三开关全关恒等；M5 简繁转换 1:1 长度不变（调用方转换后 map 仍有效）；
 * M6 mapSpan lower_bound 且整段被删时不塌缩为 0 长；M7 缩进只产 boolean。</p>
 */
public final class ParagraphReflow {
    private ParagraphReflow() { }

    /** 不可变结果；map 在构造时复制，调用方应把它当只读数组。 */
    public static final class Result {
        public final String displayText;
        public final int[] map;

        public Result(String displayText, int[] map) {
            this.displayText = displayText == null ? "" : displayText;
            this.map = map == null ? new int[0] : map.clone();
        }

        /** 防御性副本，供需要修改的调用方使用。 */
        public int[] map() { return map.clone(); }
    }

    /**
     * 重排原文。三个开关由 UI 统一传（本轮都随 paragraph_reflow 一起开/关）。
     * detectIndent=true 时，段首已有全角空格/制表符的诗歌行不参与硬换行合并。
     */
    public static Result reflow(String original, boolean mergeHardBreaks,
                                boolean collapseBlankLines, boolean detectIndent) {
        String source = original == null ? "" : original;
        int length = source.length();
        if (length == 0) return new Result(source, new int[0]);
        // M4：不改变文本的两个开关全关 → 恒等快速路径（detectIndent 只判定，不改文本）
        if (!mergeHardBreaks && !collapseBlankLines) {
            int[] identity = new int[length];
            for (int i = 0; i < length; i++) identity[i] = i;
            return new Result(source, identity);
        }

        LineInfo[] lines = splitLines(source);
        int lineCount = lines.length;
        markFences(lines);
        boolean[] deleteNewline = new boolean[lineCount];

        int index = 0;
        while (index < lineCount) {
            LineInfo current = lines[index];
            if (current.newlineLen == 0) { index++; continue; }
            // 空行是上一段 newline run 的组成部分，不单独处理
            if (current.blank && index > 0) { index++; continue; }

            // 从当前非空行起的连续换行 token：后续每多一个空行，run 长度 +1
            int runLength = 1;
            while (index + runLength < lineCount
                    && lines[index + runLength].blank
                    && lines[index + runLength].newlineLen > 0) runLength++;

            if (collapseBlankLines && runLength >= 2) {
                // 保留前两个换行 = 一个段间空行，其余空行的换行全部删除
                for (int k = 2; k < runLength; k++) deleteNewline[index + k] = true;
            } else if (runLength == 1 && mergeHardBreaks && index + 1 < lineCount
                    && canMerge(lines[index], lines[index + 1], detectIndent)) {
                // 合并被切断段落：只删行尾换行，不插空格（避免污染逐字溯源）
                deleteNewline[index] = true;
            }
            index += runLength;
        }

        // 单次正向扫描：每个保留字符都 append 一个 map 项（M1/M2/M3 一次成型）
        StringBuilder display = new StringBuilder(length);
        int[] map = new int[length];
        int kept = 0;
        for (LineInfo line : lines) {
            for (int i = line.start; i < line.contentEnd; i++) {
                display.append(source.charAt(i));
                map[kept++] = i;
            }
            if (line.newlineLen == 0 || deleteNewline[line.ordinal]) continue;
            for (int i = line.contentEnd; i < line.contentEnd + line.newlineLen; i++) {
                display.append(source.charAt(i));
                map[kept++] = i;
            }
        }
        int[] exact = new int[kept];
        System.arraycopy(map, 0, exact, 0, kept);
        return new Result(display.toString(), exact);
    }

    /**
     * M6：把原文 [sourceStart,sourceEnd) 映射到显示坐标 [dStart,dEnd)。
     * lower_bound：dStart = 第一个 map[i] >= sourceStart 的 i；dEndRaw 同理取 sourceEnd。
     * 若整段被合并删除导致 dEndRaw <= dStart 且尚未到末尾，则强制至少 1 个字符，避免高亮塌缩。
     */
    public static int[] mapSpan(int[] map, int sourceStart, int sourceEnd, int displayLen) {
        int limit = Math.max(0, Math.min(displayLen, map == null ? 0 : map.length));
        int dStart = lowerBound(map, sourceStart, limit);
        int dEndRaw = lowerBound(map, sourceEnd, limit);
        int dEnd = (dEndRaw <= dStart && dStart < limit) ? dStart + 1 : dEndRaw;
        if (dStart < 0) dStart = 0;
        if (dStart > limit) dStart = limit;
        if (dEnd < dStart) dEnd = dStart;
        if (dEnd > limit) dEnd = limit;
        return new int[]{dStart, dEnd};
    }

    /**
     * M7：每段（按 \n 切分）一个 boolean：段首已有全角空格 / U+3000 / 制表符 / 空格 → true，
     * 表示 UI 不要再叠加 LeadingMarginSpan。只判定，不改文本。
     */
    public static boolean[] indentDecisions(String displayText) {
        String text = displayText == null ? "" : displayText;
        if (text.isEmpty()) return new boolean[0];
        int n = text.length();
        int lineCount = 1;
        for (int i = 0; i < n; i++) if (text.charAt(i) == '\n') lineCount++;
        boolean[] decisions = new boolean[lineCount];
        int cursor = 0;
        int line = 0;
        while (cursor <= n) {
            int end = cursor;
            while (end < n && text.charAt(end) != '\n') end++;
            if (cursor < end) {
                char first = text.charAt(cursor);
                decisions[line] = first == '\u3000' || first == '\t' || first == '\u00A0' || first == ' ';
            }
            line++;
            if (end >= n) break;
            cursor = end + 1;
        }
        return decisions;
    }

    // ---------------------------------------------------------------- 内部实现

    private static int lowerBound(int[] map, int target, int limit) {
        if (map == null) return limit;
        int low = 0, high = limit;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (map[middle] < target) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static LineInfo[] splitLines(String source) {
        List<LineInfo> lines = new ArrayList<>();
        int n = source.length();
        int start = 0;
        while (start <= n) {
            int contentEnd = start;
            while (contentEnd < n && source.charAt(contentEnd) != '\n' && source.charAt(contentEnd) != '\r')
                contentEnd++;
            int newlineLen = 0;
            if (contentEnd < n) {
                if (source.charAt(contentEnd) == '\r' && contentEnd + 1 < n
                        && source.charAt(contentEnd + 1) == '\n') newlineLen = 2;
                else newlineLen = 1;
            }
            lines.add(new LineInfo(source, lines.size(), start, contentEnd, newlineLen));
            if (newlineLen == 0) break;
            start = contentEnd + newlineLen;
        }
        return lines.toArray(new LineInfo[0]);
    }

    /** 与 ChapterWriter 同款 fence 状态：``` / ~~~ 连续 >=3 开合，内部行一律不合并。 */
    private static void markFences(LineInfo[] lines) {
        boolean inside = false;
        char marker = 0;
        int width = 0;
        for (LineInfo line : lines) {
            line.fenced = inside;
            int first = firstNonWhitespace(line);
            if (first < 0) continue;
            char candidate = line.source.charAt(first);
            if (candidate != '`' && candidate != '~') continue;
            int run = 0;
            while (first + run < line.contentEnd && line.source.charAt(first + run) == candidate) run++;
            if (run < 3) continue;
            if (!inside) {
                inside = true; marker = candidate; width = run;
            } else if (candidate == marker && run >= width && first + run == line.contentEnd) {
                inside = false; marker = 0; width = 0;
            }
        }
    }

    private static boolean canMerge(LineInfo before, LineInfo after, boolean detectIndent) {
        // 空行不是"被切断的段落"：文件开头的单个空行也不参与硬换行合并
        if (before.blank || after.blank) return false;
        // 诗歌：段首已有缩进字符 → 独立诗行，不合并（与 D6 避免双重缩进同口径）
        if (detectIndent && (hasIndent(before) || hasIndent(after))) return false;
        // 对话：行首引号 → 独立台词，不合并
        if (startsWithQuote(before) || startsWithQuote(after)) return false;
        // fence 标记行与代码块内部行都不合并
        if (before.fenced || after.fenced || isFenceLine(before) || isFenceLine(after)) return false;
        // 段尾已有句末标点 → 真实段落结束，不是被硬换行切断
        return !endsWithTerminal(before);
    }

    private static int firstNonWhitespace(LineInfo line) {
        for (int i = line.start; i < line.contentEnd; i++) {
            char c = line.source.charAt(i);
            if (!Character.isWhitespace(c) && c != '\u00A0' && c != '\uFEFF') return i;
        }
        return -1;
    }

    private static boolean hasIndent(LineInfo line) {
        if (line.start >= line.contentEnd) return false;
        char c = line.source.charAt(line.start);
        return c == '\u3000' || c == '\t' || c == '\u00A0' || c == ' ';
    }

    private static boolean startsWithQuote(LineInfo line) {
        int first = firstNonWhitespace(line);
        if (first < 0) return false;
        char c = line.source.charAt(first);
        return c == '「' || c == '『' || c == '“' || c == '"' || c == '‘' || c == '\'';
    }

    private static boolean isFenceLine(LineInfo line) {
        int first = firstNonWhitespace(line);
        if (first < 0) return false;
        char c = line.source.charAt(first);
        if (c != '`' && c != '~') return false;
        int run = 0;
        while (first + run < line.contentEnd && line.source.charAt(first + run) == c) run++;
        return run >= 3;
    }

    private static boolean endsWithTerminal(LineInfo line) {
        for (int i = line.contentEnd - 1; i >= line.start; i--) {
            char c = line.source.charAt(i);
            if (Character.isWhitespace(c) || c == '\u00A0' || c == '\uFEFF') continue;
            return c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '；' || c == ';'
                    || c == '：' || c == ':' || c == '…' || c == '”' || c == '』' || c == '」'
                    || c == '’' || c == '）' || c == ')' || c == '】' || c == '》' || c == '}';
        }
        return false;
    }

    /** 单行模型：ordinal 是行号，start/contentEnd 描述内容区间（不含换行 token）。 */
    private static final class LineInfo {
        final String source;
        final int ordinal;
        final int start;
        final int contentEnd;
        final int newlineLen;
        final boolean blank;
        boolean fenced;

        LineInfo(String source, int ordinal, int start, int contentEnd, int newlineLen) {
            this.source = source;
            this.ordinal = ordinal;
            this.start = start;
            this.contentEnd = contentEnd;
            this.newlineLen = newlineLen;
            boolean empty = true;
            for (int i = start; i < contentEnd; i++) {
                char c = source.charAt(i);
                if (!Character.isWhitespace(c) && c != '\u00A0' && c != '\uFEFF') { empty = false; break; }
            }
            this.blank = empty;
        }
    }
}
