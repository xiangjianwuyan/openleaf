package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TTS 听书文本预处理：把整章正文裁剪成「可朗读正文」，切成带全局字符锚点的句子，
 * 供朗读高亮、自动翻页和进度保存共用同一个锚点。
 *
 * 设计借鉴 Shelf（MIT）的 front/back matter 正则裁剪管线与 Passage(section,offset) 锚点，
 * 由 OpenLeaf 自行实现，纯 JVM 可测，不执行任何脚本、不依赖网络。
 */
public final class SpeechText {
    private SpeechText() { }

    /** 句子锚点：正文 section 序号 + 段内字符偏移 + 句子文本，贯穿朗读四环节。 */
    public static final class Passage {
        public final int section;
        public final int offset;
        public final String text;
        Passage(int section, int offset, String text) {
            this.section = section; this.offset = offset; this.text = text;
        }
        @Override public String toString() { return section + ":" + offset + " " + text; }
    }

    /** 段落：section 序号 + 文本 + 是否标题（标题可作为 front/back 边界）。 */
    public static final class Block {
        public final int section;
        public final String text;
        public final boolean heading;
        public Block(int section, String text, boolean heading) {
            this.section = section; this.text = text; this.heading = heading;
        }
    }

    private static final Pattern FRONT_TITLE = Pattern.compile(
            "(?i)^\\s*(?:cover|title\\s*page|half\\s*title|table\\s*of\\s*contents|contents|"
            + "copyright|impressum|license|dedication|preface|foreword|list\\s*of\\s*illustrations|"
            + "about\\s*this\\s*book|project\\s*gutenberg|序|序言|前言|目录|版权|扉页|献词|插图目录)"
            + "(?:[\\s:：.．、\\-—].*)?$");
    private static final Pattern BACK_TITLE = Pattern.compile(
            "(?i)^\\s*(?:acknowledg\\w*|notes?|footnotes?|bibliography|glossary|index|"
            + "about\\s*the\\s*author|excerpt|credits|后记|尾声|注释|参考文献|索引|作者简介|附录|番外|题记)"
            + "(?:[\\s:：.．、\\-—].*)?$");
    private static final Pattern BODY_TITLE = Pattern.compile(
            "(?i)^\\s*(prologue|epilogue|introduction|chapter|kapitel|chapitre|第.{1,12}[章节回卷部篇集]|序章|楔子).*$");
    private static final Pattern FOOTNOTE = Pattern.compile("\\[\\d+\\]|\\[Illustration\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUTENBERG_START = Pattern.compile(
            "(?i)\\*{2,}\\s*START OF (?:THE|THIS) PROJECT GUTENBERG EBOOK.*?\\*{2,}");
    private static final Pattern GUTENBERG_END = Pattern.compile(
            "(?i)\\*{2,}\\s*END OF (?:THE|THIS) PROJECT GUTENBERG EBOOK.*?\\*{2,}");
    private static final Pattern NOISE = Pattern.compile("(?m)^\\s*(?:\\*{3,}|THE END|全剧终|完)\\s*$");
    private static final Pattern ABBREVIATION = Pattern.compile(
            "(?i)\\b(?:mr|mrs|ms|dr|prof|sr|jr|st|vs|etc|e\\.g|i\\.e|no)\\.$");

    /**
     * 句子切分：空白 + 大写/引号起始；缩写由下面的 ABBREVIATION 合并。
     *
     * <p>这里原先写的是 {@code (?<=句末标点[引号括号])?\s+(?=大写开头)}：JVM 的正则允许给
     * 环视加 {@code ?}，Android 的 ICU 引擎不允许，真机加载本类时直接抛
     * {@code PatternSyntaxException → ExceptionInInitializerError}，进程当场被杀。
     * 那个环视本来就是可选的（零宽 + 可选 = 对匹配集没有贡献），去掉它语义不变，
     * 同时消除这颗「只在真机、只在真去听书时才爆」的雷。
     */
    private static final Pattern SENTENCE_BREAK = Pattern.compile(
            "\\s+(?=[A-Z\"“'‘（(\\[])");
    public static final int MAX_SPEECH_CHARACTERS = 3500;

    /** 裁剪结果：可朗读的 Block 列表（已去 front/back matter 与噪声）。 */
    public static List<Block> spokenBlocks(List<Block> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        List<Block> blocks = new ArrayList<>();
        for (Block block : source) {
            String text = tidy(block.text);
            if (text.isEmpty()) continue;
            blocks.add(new Block(block.section, text, block.heading));
        }
        blocks = cutMarkers(blocks);
        blocks = withoutRunningHeaders(blocks);
        return trimFrontBack(blocks);
    }

    private static String tidy(String text) {
        if (text == null) return "";
        String value = text.replace("\u00ad", ""); // 软连字符
        value = FOOTNOTE.matcher(value).replaceAll("");
        value = NOISE.matcher(value).replaceAll("");
        value = value.replaceAll("[ \\t]+", " ").trim();
        return value;
    }

    /** 去 Gutenberg 起止标记：只在文本前半/后半命中才生效，避免误裁正文。 */
    private static List<Block> cutMarkers(List<Block> blocks) {
        List<Block> result = new ArrayList<>(blocks);
        int total = 0;
        for (Block block : result) total += block.text.length();
        for (int i = 0; i < result.size(); i++) {
            Block block = result.get(i);
            Matcher start = GUTENBERG_START.matcher(block.text);
            if (start.find() && start.start() < total / 2) {
                result.set(i, new Block(block.section, block.text.substring(start.end()).trim(), block.heading));
            }
            Matcher end = GUTENBERG_END.matcher(block.text);
            if (end.find() && end.start() > total / 2) {
                result.set(i, new Block(block.section, block.text.substring(0, end.start()).trim(), block.heading));
            }
        }
        return result;
    }

    /** 重复页眉页脚去除：短行文本数字归一化为 # 作 key，出现 >=5 次的删除（含句末标点保留）。 */
    private static List<Block> withoutRunningHeaders(List<Block> blocks) {
        List<Block> result = new ArrayList<>();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (Block block : blocks) {
            if (block.heading || block.text.length() > 60) continue;
            String key = block.text.replaceAll("\\d", "#");
            counts.put(key, (counts.containsKey(key) ? counts.get(key) : 0) + 1);
        }
        for (Block block : blocks) {
            if (!block.heading && block.text.length() <= 60) {
                String key = block.text.replaceAll("\\d", "#");
                boolean sentence = block.text.matches(".*[.!?。！？]$");
                if (counts.get(key) >= 5 && !sentence) continue;
            }
            result.add(block);
        }
        return result;
    }

    /** 裁剪：第一个 BODY 标题为正文起点；书末附录（后半部才出现的 Front/Back）也排除。 */
    private static List<Block> trimFrontBack(List<Block> blocks) {
        int first = -1, last = blocks.size();
        for (int i = 0; i < blocks.size(); i++) {
            if (isBody(blocks.get(i))) { first = i; break; }
        }
        if (first < 0) first = 0; // 没有显式章节标题则整篇朗读
        int total = 0;
        for (Block block : blocks) total += block.text.length();
        int midpoint = 0;
        for (int i = 0; i < blocks.size(); i++) {
            midpoint += blocks.get(i).text.length();
            if (i >= first && (isFront(blocks.get(i)) || isBack(blocks.get(i))) && midpoint > total / 2) {
                last = i; break;
            }
        }
        if (last <= first) return Collections.emptyList();
        List<Block> result = new ArrayList<>();
        for (int i = first; i < last; i++) result.add(blocks.get(i));
        return result;
    }

    private static boolean isFront(Block block) { return block.heading && FRONT_TITLE.matcher(block.text).matches(); }
    private static boolean isBack(Block block) { return block.heading && BACK_TITLE.matcher(block.text).matches(); }
    private static boolean isBody(Block block) { return block.heading && BODY_TITLE.matcher(block.text).matches(); }

    /**
     * 句子切分：每段按句末标点切分，缩写合并回前句，超长句再按 MAX_SPEECH_CHARACTERS 分块。
     * Passage 的 offset 是句子在该 section 内的字符偏移，与原段落文本严格对应。
     */
    public static List<Passage> sentences(List<Block> blocks) {
        List<Passage> result = new ArrayList<>();
        for (Block block : blocks) {
            if (block.text.isEmpty()) continue;
            String[] parts = SENTENCE_BREAK.split(block.text);
            StringBuilder pending = new StringBuilder();
            int pendingStart = -1;
            int cursor = 0;
            for (String raw : parts) {
                String sentence = raw.trim();
                if (sentence.isEmpty()) { cursor += raw.length() + 1; continue; }
                int index = block.text.indexOf(sentence, cursor);
                int offset = index >= 0 ? index : Math.max(0, cursor);
                // 缩写结尾（如 "Mr."）应合并到下一句
                if (pending.length() > 0 && ABBREVIATION.matcher(sentence).find()) {
                    pending.append(' ').append(sentence);
                    cursor = offset + sentence.length();
                    continue;
                }
                if (pending.length() > 0) { pending.append(' ').append(sentence); sentence = pending.toString(); offset = pendingStart; pending.setLength(0); pendingStart = -1; }
                else if (ABBREVIATION.matcher(sentence).matches()) { pending.append(sentence); pendingStart = offset; cursor = offset + sentence.length(); continue; }
                // 超长句按字符上限分块，锚点随之偏移
                if (sentence.length() > MAX_SPEECH_CHARACTERS) {
                    for (int from = 0; from < sentence.length(); from += MAX_SPEECH_CHARACTERS) {
                        int end = Math.min(sentence.length(), from + MAX_SPEECH_CHARACTERS);
                        result.add(new Passage(block.section, offset + from, sentence.substring(from, end)));
                    }
                } else {
                    result.add(new Passage(block.section, offset, sentence));
                }
                cursor = Math.max(cursor, offset + raw.length());
            }
            if (pending.length() > 0) result.add(new Passage(block.section, pendingStart, pending.toString()));
        }
        return Collections.unmodifiableList(result);
    }

    /** 定位：第一个锚点 >= 给定 section/offset 的句子（用于从进度恢复朗读）。 */
    public static int locate(List<Passage> passages, int section, int offset) {
        for (int i = 0; i < passages.size(); i++) {
            Passage passage = passages.get(i);
            if (passage.section > section || (passage.section == section && passage.offset >= offset)) return i;
        }
        return passages.isEmpty() ? -1 : passages.size() - 1;
    }

    /**
     * 章节起始字符偏移：按行扫描文本，把命中 {@link TextChapterHeadings#isHeading} 的行首字符偏移
     * 升序收集，首项恒为 0（正文开头本身算一章起点）。
     *
     * isolated 判定与 {@link TextChapterIndexer} 的流式扫描一致：前一行（或文件头）为空行、
     * 且后一行（或文件尾）为空行，才算独立行——避免把正文里夹带的「第一章」误判成章标题。
     * 纯 JVM 实现，不依赖 Android。null / 空文本返回 {@code new int[]{0}}。
     *
     * 注意：返回的是**入参文本**的字符坐标。要和 {@link Passage#offset} 对齐，入参必须是
     * {@link #spokenBlocks} 的输出文本（见 {@link #chapterStops}），不能传原始全文。
     */
    public static int[] chapterStartOffsets(String text) {
        if (text == null || text.isEmpty()) return new int[]{0};
        List<String> lines = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        int length = text.length();
        int cursor = 0;
        while (true) {
            int lineStart = cursor;
            int scan = cursor;
            while (scan < length && text.charAt(scan) != '\n' && text.charAt(scan) != '\r') scan++;
            lines.add(text.substring(lineStart, scan));
            starts.add(lineStart);
            if (scan >= length) break;
            cursor = scan + 1;
            if (text.charAt(scan) == '\r' && cursor < length && text.charAt(cursor) == '\n') cursor++;
        }
        int count = lines.size();
        boolean[] blank = new boolean[count];
        for (int i = 0; i < count; i++) blank[i] = blankLine(lines.get(i));
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < count; i++) {
            if (blank[i]) continue;
            boolean isolated = (i == 0 || blank[i - 1]) && (i == count - 1 || blank[i + 1]);
            if (!TextChapterHeadings.isHeading(lines.get(i).trim(), isolated)) continue;
            int offset = starts.get(i);
            if (offset > offsets.get(offsets.size() - 1)) offsets.add(offset); // 升序且去重
        }
        int[] result = new int[offsets.size()];
        for (int i = 0; i < result.length; i++) result[i] = offsets.get(i);
        return result;
    }

    /** 空行判定：与 TextChapterIndexer 的 isWs 一致（含 NBSP 与 BOM）。 */
    private static boolean blankLine(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!Character.isWhitespace(c) && c != '\u00a0' && c != '\ufeff') return false;
        }
        return true;
    }

    /**
     * 章末边界（passage 下标）：把每章起点映射成「该章第一句」的 passage 下标，升序排列，
     * 末尾追加 {@code passages.size()}（全文结束）。
     *
     * <b>入参必须与 passages 同一坐标系</b>：{@code spoken} 是 {@link #spokenBlocks} 的返回值，
     * 即 passages 由 {@code sentences(spoken)} 生成的那一份。传原始全文会让偏移在前移后错位
     * （tidy 删过脚注标记/噪声行、折叠过连续空格，偏差会逐处累积）。
     *
     * 降级：整篇没识别出章标题（chapterStartOffsets 只返回 [0]）时返回 {@code [passages.size()]}，
     * 「本章结束」等同于读完全文停止——可预期，不会静默不生效。
     */
    public static int[] chapterStops(List<Passage> passages, List<Block> spoken) {
        int size = passages == null ? 0 : passages.size();
        if (size == 0 || spoken == null || spoken.isEmpty()) return new int[]{0};
        List<Integer> stops = new ArrayList<>();
        int previous = 0;
        for (Block block : spoken) {
            if (block == null || block.text == null || block.text.isEmpty()) continue;
            // 每个 block 的段落文本就是 Passage.offset 的坐标系（section 相同）
            for (int offset : chapterStartOffsets(block.text)) {
                int at = locate(passages, block.section, offset);
                if (at < 0) at = 0;
                if (at < previous) at = previous; // 保证升序，边界绝不回退
                previous = at;
                if (stops.isEmpty() || at > stops.get(stops.size() - 1)) stops.add(at); // 去重（升序）
            }
        }
        if (stops.size() <= 1) return new int[]{size}; // 只有「0」= 没识别到章标题，降级
        stops.add(size);
        int[] result = new int[stops.size()];
        for (int i = 0; i < result.length; i++) result[i] = stops.get(i);
        return result;
    }

    /**
     * 句下标 {@code index} 是否为所在章的最后一句：{@code index + 1} 恰好落在某个章边界上。
     * stops 取自 {@link #chapterStops}（升序，末尾含 passages.size()）。
     */
    public static boolean isChapterEnd(int[] stops, int index) {
        if (stops == null) return false;
        int next = index + 1;
        for (int stop : stops) {
            if (stop < next) continue;
            return stop == next;
        }
        return false;
    }

    /** 睡眠定时器状态机：用 elapsedRealtime 的截止时刻，支持分钟后/章节末两种结束条件。 */
    public static final class SleepTimer {
        public enum Kind { OFF, MINUTES, CHAPTER_END }
        private Kind kind = Kind.OFF;
        private long deadlineRealtime;

        public void setMinutes(long nowRealtime, int minutes) {
            if (minutes <= 0) { off(); return; }
            this.kind = Kind.MINUTES;
            this.deadlineRealtime = nowRealtime + minutes * 60_000L;
        }

        public void setChapterEnd() { this.kind = Kind.CHAPTER_END; this.deadlineRealtime = 0; }
        public void off() { this.kind = Kind.OFF; this.deadlineRealtime = 0; }
        public Kind kind() { return kind; }

        /** 剩余秒数；OFF/CHAPTER_END 返回 -1。 */
        public long remainingSeconds(long nowRealtime) {
            if (kind != Kind.MINUTES) return -1;
            return Math.max(0, (deadlineRealtime - nowRealtime) / 1000);
        }

        /** 是否到点。CHAPTER_END 由调用方在句末显式调用 shouldStopAtChapterEnd。 */
        public boolean expired(long nowRealtime) {
            return kind == Kind.MINUTES && nowRealtime >= deadlineRealtime;
        }

        /**
         * 本句讲完后是否该停：仅 CHAPTER_END 模式、且 atChapterEnd 为 true（当前句是本章最后一句）时停。
         * atChapterEnd 由调用方按 {@link SpeechText#chapterStops} 给出的章节边界判定，避免读一句就停。
         */
        public boolean shouldStopAtChapterEnd(boolean atChapterEnd) {
            return kind == Kind.CHAPTER_END && atChapterEnd;
        }
    }

    /** 稳定的朗读队列世代令牌：pause/seek/改速都递增，回调只接受同世代事件，防串音。 */
    public static final class Generation {
        private long value;
        public long next() { return ++value; }
        public long current() { return value; }
    }
}
