package org.openleaf.reader.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词多格式解析（0.30.0 #9，纯 JVM，无 Android 依赖）。
 *
 * <p>支持四种输入：标准 LRC、增强 LRC（一行多时间标签展开）、SRT、无时间戳 TXT。
 * 编码按 UTF-8 严格解码，失败回退 GB18030；两种解码都失败时按 GB18030 宽松解码，
 * 解析器本身不抛异常。
 *
 * <p>容量上限（超限策略在此明示）：
 * <ul>
 *   <li>原始字节 &gt; {@link #MAX_BYTES}（1 MiB）：整份拒绝，返回空文档；</li>
 *   <li>输出行数 &gt; {@link #MAX_LINES}（5000）：截断，保留前 5000 行；</li>
 *   <li>单行文本 &gt; {@link #MAX_LINE_CHARS}（500 字符）：截断到 500 字符。</li>
 * </ul>
 */
public final class Lyrics {

    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_LINES = 5000;
    public static final int MAX_LINE_CHARS = 500;

    /** 静态歌词（TXT）的时间戳哨兵。 */
    public static final int STATIC_TIME = -1;

    private static final Pattern LRC_TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{2}(?:\\.\\d{1,3})?)\\]");
    private static final Pattern SRT_TIME =
            Pattern.compile("(\\d{1,3}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->");
    private static final Pattern SRT_INDEX = Pattern.compile("^\\d{1,6}$");

    private Lyrics() {
        // 纯工具类，禁止实例化。
    }

    /** 一行歌词：timeMs = -1 表示无时间戳的静态文本。 */
    public static final class Line {
        public final int timeMs;
        public final String text;

        public Line(int timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text == null ? "" : text;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Line)) return false;
            Line that = (Line) other;
            return timeMs == that.timeMs && text.equals(that.text);
        }

        @Override
        public int hashCode() {
            return 31 * timeMs + text.hashCode();
        }

        @Override
        public String toString() {
            return "Line{" + timeMs + ", " + text + "}";
        }
    }

    /** 解析结果；lines 为不可修改列表，format ∈ lrc / srt / txt / empty。 */
    public static final class Doc {
        public final List<Line> lines;
        public final String format;

        public Doc(List<Line> lines, String format) {
            this.lines = lines == null
                    ? Collections.<Line>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(lines));
            this.format = format == null ? "" : format;
        }

        public boolean isEmpty() {
            return lines.isEmpty();
        }

        /** 带时间戳行数占比，用于 {@link #best(List)} 置信度。 */
        public double timedRatio() {
            if (lines.isEmpty()) return 0.0;
            int timed = 0;
            for (Line line : lines) if (line.timeMs >= 0) timed++;
            return timed / (double) lines.size();
        }
    }

    /**
     * 内容嗅探 + 解析。null / 空字节 / 超过 1 MiB 均返回空文档（format = empty），永不抛异常。
     */
    public static Doc parse(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > MAX_BYTES) {
            return new Doc(Collections.<Line>emptyList(), "empty");
        }
        String text = decode(raw);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.trim().isEmpty()) return new Doc(Collections.<Line>emptyList(), "empty");

        // 先判 SRT：时间轴形如 00:00:01,000 -->，避免被 LRC 嗅探误判。
        if (SRT_TIME.matcher(text).find()) return new Doc(parseSrt(text), "srt");
        if (LRC_TAG.matcher(text).find()) return new Doc(parseLrc(text), "lrc");
        return new Doc(parseTxt(text), "txt");
    }

    /**
     * 择优：带时间戳行数占比高者优先；同分取行数少者；空/无效文档跳过。
     * 返回值可能为 null（入参 null 或全部为空）。
     */
    public static Doc best(List<Doc> docs) {
        if (docs == null || docs.isEmpty()) return null;
        Doc winner = null;
        double winnerScore = -1.0;
        for (Doc doc : docs) {
            if (doc == null || doc.isEmpty()) continue;
            double score = doc.timedRatio();
            if (winner == null
                    || score > winnerScore + 1e-9
                    || (Math.abs(score - winnerScore) <= 1e-9
                            && doc.lines.size() < winner.lines.size())) {
                winner = doc;
                winnerScore = score;
            }
        }
        return winner;
    }

    /** 标准 LRC / 增强 LRC：行首连续时间标签，一行多标签按时间升序展开。 */
    private static List<Line> parseLrc(String text) {
        List<Line> result = new ArrayList<>();
        String[] lines = splitLines(text);
        for (String line : lines) {
            if (line == null || line.isEmpty() || result.size() >= MAX_LINES) continue;
            Matcher matcher = LRC_TAG.matcher(line);
            List<Integer> times = new ArrayList<>();
            int cursor = 0;
            while (matcher.find() && matcher.start() == cursor) {
                long minutes = Long.parseLong(matcher.group(1));
                double seconds = Double.parseDouble(matcher.group(2));
                long millis = minutes * 60_000L + (long) (seconds * 1000);
                times.add((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, millis)));
                cursor = matcher.end();
            }
            if (times.isEmpty()) {
                // 兼容旧 parseLrc：时间标签不在行首时，取首个标签、文本取其后的内容。
                Matcher fallback = LRC_TAG.matcher(line);
                if (fallback.find()) {
                    long minutes = Long.parseLong(fallback.group(1));
                    double seconds = Double.parseDouble(fallback.group(2));
                    long millis = minutes * 60_000L + (long) (seconds * 1000);
                    result.add(new Line((int) Math.max(Integer.MIN_VALUE,
                            Math.min(Integer.MAX_VALUE, millis)),
                            truncate(line.substring(fallback.end()))));
                }
                continue; // 元数据行 / 无时间戳行：标准 LRC 语义即忽略。
            }
            String content = truncate(line.substring(cursor));
            for (int time : times) {
                if (result.size() >= MAX_LINES) break;
                result.add(new Line(time, content));
            }
        }
        // 稳定排序：同一时间的多行保持文件出现顺序（与旧 parseLrc 的 Collections.sort 一致）。
        result.sort((a, b) -> Integer.compare(a.timeMs, b.timeMs));
        return result;
    }

    /** SRT：忽略序号行与空行，时间轴后到下一个空行之前的文本合并为一条。 */
    private static List<Line> parseSrt(String text) {
        List<Line> result = new ArrayList<>();
        String[] lines = splitLines(text);
        for (int i = 0; i < lines.length && result.size() < MAX_LINES; i++) {
            String line = lines[i];
            if (line == null) continue;
            Matcher matcher = SRT_TIME.matcher(line);
            if (!matcher.find()) continue;
            long hours = Long.parseLong(matcher.group(1));
            long minutes = Long.parseLong(matcher.group(2));
            long seconds = Long.parseLong(matcher.group(3));
            long fraction = Long.parseLong(matcher.group(4));
            // 毫秒位数按实际长度归一：1 位 = 100ms，2 位 = 10ms，3 位 = 1ms。
            long millis = fraction;
            for (int pad = matcher.group(4).length(); pad < 3; pad++) millis *= 10L;
            long time = hours * 3_600_000L + minutes * 60_000L + seconds * 1000L + millis;
            StringBuilder content = new StringBuilder();
            int cursor = i + 1;
            while (cursor < lines.length) {
                String body = lines[cursor];
                if (body == null || body.trim().isEmpty()) break;
                if (SRT_INDEX.matcher(body.trim()).matches() && content.length() == 0) {
                    cursor++;
                    continue;
                }
                if (content.length() > 0) content.append('\n');
                content.append(body);
                cursor++;
            }
            if (content.length() == 0) continue;
            result.add(new Line((int) Math.max(Integer.MIN_VALUE,
                    Math.min(Integer.MAX_VALUE, time)), truncate(content.toString())));
            i = cursor;
        }
        return result;
    }

    /** TXT：无时间戳，逐非空行生成静态歌词。 */
    private static List<Line> parseTxt(String text) {
        List<Line> result = new ArrayList<>();
        for (String line : splitLines(text)) {
            if (result.size() >= MAX_LINES) break;
            if (line == null || line.trim().isEmpty()) continue;
            result.add(new Line(STATIC_TIME, truncate(line)));
        }
        return result;
    }

    private static String[] splitLines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() > MAX_LINE_CHARS ? value.substring(0, MAX_LINE_CHARS) : value;
    }

    /** UTF-8 严格解码 → GB18030；两者都异常时按 GB18030 宽松解码，绝不抛异常。 */
    private static String decode(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException notUtf8) {
            try {
                return new String(raw, Charset.forName("GB18030"));
            } catch (RuntimeException unavailable) {
                return new String(raw, StandardCharsets.ISO_8859_1);
            }
        }
    }
}
