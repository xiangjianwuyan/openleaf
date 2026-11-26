import org.openleaf.reader.core.Lyrics;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 0.30.0 #9 歌词四格式回归；纯 JVM、无 JUnit、断言始终启用。 */
public final class LyricsTest {
    private static int passed;

    public static void main(String[] args) {
        test("标准 LRC 解析与旧 parseLrc 逐字等价", LyricsTest::lrcParity);
        test("增强 LRC 一行多标签展开并按时间稳定排序", LyricsTest::enhancedLrc);
        test("SRT 序号行/空行处理与毫秒归一", LyricsTest::srtParsing);
        test("TXT 无时间戳静态歌词", LyricsTest::txtParsing);
        test("UTF-8 严格解码与 GB18030 兜底", LyricsTest::encodingFallback);
        test("容量上限：1MiB / 5000 行 / 500 字符", LyricsTest::limits);
        test("best 按时间戳占比与行数择优", LyricsTest::bestSelection);
        test("空/垃圾/畸形输入不抛异常", LyricsTest::robustness);
        System.out.println("LyricsTest: " + passed + " checks passed");
    }

    private static final String LRC_SAMPLE =
            "[ti:测试曲目]\n"
                    + "[ar:测试歌手]\n"
                    + "[00:01.00]第一行\n"
                    + "[00:12.34]第二行\n"
                    + "[00:05.5]中间行\n"
                    + "[01:00.000]最后一行\n";

    private static void lrcParity() {
        Lyrics.Doc doc = Lyrics.parse(LRC_SAMPLE.getBytes(StandardCharsets.UTF_8));
        equal("lrc", doc.format);
        List<OldLine> old = oldParseLrc(LRC_SAMPLE);
        equal(old.size(), doc.lines.size());
        for (int i = 0; i < old.size(); i++) {
            equal((int) old.get(i).timeMs, doc.lines.get(i).timeMs, "第 " + i + " 行时间");
            equal(old.get(i).text, doc.lines.get(i).text, "第 " + i + " 行文本");
        }
        equal(1000, doc.lines.get(0).timeMs);
        equal("第一行", doc.lines.get(0).text);
        equal(5500, doc.lines.get(1).timeMs);
        equal(12340, doc.lines.get(2).timeMs);
        equal(60000, doc.lines.get(3).timeMs);

        // 非行首标签：旧 parseLrc 用 find() 仍能解析，新实现必须保持逐字等价。
        String odd = "前缀[00:02.00]文本\n";
        Lyrics.Doc oddDoc = Lyrics.parse(odd.getBytes(StandardCharsets.UTF_8));
        List<OldLine> oddOld = oldParseLrc(odd);
        equal(oddOld.size(), oddDoc.lines.size());
        equal((int) oddOld.get(0).timeMs, oddDoc.lines.get(0).timeMs);
        equal(oddOld.get(0).text, oddDoc.lines.get(0).text, "非行首标签文本必须与旧实现一致");
    }

    private static void enhancedLrc() {
        String sample = "[00:15.00][00:01.00]副歌\n[00:05.00]主歌\n";
        Lyrics.Doc doc = Lyrics.parse(sample.getBytes(StandardCharsets.UTF_8));
        equal(3, doc.lines.size());
        equal(1000, doc.lines.get(0).timeMs);
        equal("副歌", doc.lines.get(0).text);
        equal(5000, doc.lines.get(1).timeMs);
        equal("主歌", doc.lines.get(1).text);
        equal(15000, doc.lines.get(2).timeMs);
        equal("副歌", doc.lines.get(2).text);
    }

    private static void srtParsing() {
        String sample = "1\n00:00:01,000 --> 00:00:04,000\n第一句\n第二句\n\n"
                + "2\n00:00:04.50 --> 00:00:06,000\n第三句\n";
        Lyrics.Doc doc = Lyrics.parse(sample.getBytes(StandardCharsets.UTF_8));
        equal("srt", doc.format);
        equal(2, doc.lines.size());
        equal(1000, doc.lines.get(0).timeMs);
        equal("第一句\n第二句", doc.lines.get(0).text);
        equal(4500, doc.lines.get(1).timeMs);
        equal("第三句", doc.lines.get(1).text);
    }

    private static void txtParsing() {
        Lyrics.Doc doc = Lyrics.parse("第一句\n\n第二句\n第三句\n".getBytes(StandardCharsets.UTF_8));
        equal("txt", doc.format);
        equal(3, doc.lines.size());
        for (Lyrics.Line line : doc.lines) equal(Lyrics.STATIC_TIME, line.timeMs);
        equal("第一句", doc.lines.get(0).text);
        equal("第三句", doc.lines.get(2).text);
    }

    private static void encodingFallback() {
        String text = "[00:02.00]中文歌词";
        byte[] gb = text.getBytes(Charset.forName("GB18030"));
        Lyrics.Doc doc = Lyrics.parse(gb);
        equal("lrc", doc.format);
        equal(1, doc.lines.size());
        equal(2000, doc.lines.get(0).timeMs);
        equal("中文歌词", doc.lines.get(0).text);
        // UTF-8 样本走严格解码路径。
        Lyrics.Doc utf8 = Lyrics.parse("[00:03.00]中文歌词".getBytes(StandardCharsets.UTF_8));
        equal("中文歌词", utf8.lines.get(0).text);
    }

    private static void limits() {
        check(Lyrics.parse(new byte[Lyrics.MAX_BYTES + 1]).isEmpty(), "超过 1 MiB 整份拒绝");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < Lyrics.MAX_LINES + 1000; i++) many.append("行").append(i).append('\n');
        Lyrics.Doc capped = Lyrics.parse(many.toString().getBytes(StandardCharsets.UTF_8));
        equal(Lyrics.MAX_LINES, capped.lines.size(), "输出行数截断到 5000");
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < Lyrics.MAX_LINE_CHARS + 200; i++) longLine.append('字');
        Lyrics.Doc truncated = Lyrics.parse(longLine.toString().getBytes(StandardCharsets.UTF_8));
        equal(Lyrics.MAX_LINE_CHARS, truncated.lines.get(0).text.length(), "单行截断到 500 字符");
    }

    private static void bestSelection() {
        List<Lyrics.Line> staticLines = new ArrayList<>();
        for (int i = 0; i < 10; i++) staticLines.add(new Lyrics.Line(Lyrics.STATIC_TIME, "静态" + i));
        Lyrics.Doc txt = new Lyrics.Doc(staticLines, "txt");
        Lyrics.Doc lrc = new Lyrics.Doc(Arrays.asList(
                new Lyrics.Line(0, "a"), new Lyrics.Line(1000, "b")), "lrc");
        equal(lrc, Lyrics.best(Arrays.asList(txt, lrc)), "时间戳占比高者优先");
        Lyrics.Doc shorter = new Lyrics.Doc(Collections.singletonList(new Lyrics.Line(0, "a")), "lrc");
        Lyrics.Doc longer = new Lyrics.Doc(Arrays.asList(
                new Lyrics.Line(0, "a"), new Lyrics.Line(1000, "b")), "lrc");
        equal(shorter, Lyrics.best(Arrays.asList(longer, shorter)), "同占比取行数少者");
        check(Lyrics.best(null) == null, "null 列表返回 null");
        check(Lyrics.best(new ArrayList<Lyrics.Doc>()) == null, "空列表返回 null");
        check(Lyrics.best(Arrays.asList(null, new Lyrics.Doc(null, "empty"))) == null, "全空返回 null");
    }

    private static void robustness() {
        check(Lyrics.parse(null).isEmpty(), "null 返回空文档");
        check(Lyrics.parse(new byte[0]).isEmpty(), "空字节返回空文档");
        check(Lyrics.parse("普通文本".getBytes(StandardCharsets.UTF_8)).format.equals("txt"), "垃圾按 TXT");
        Lyrics.Doc brokenSrt = Lyrics.parse("1\n00:00:01,000 --> \n".getBytes(StandardCharsets.UTF_8));
        check(brokenSrt.isEmpty(), "畸形 SRT 不抛异常且无行");
        Lyrics.Doc brokenLrc = Lyrics.parse("[99:99.999]".getBytes(StandardCharsets.UTF_8));
        equal(1, brokenLrc.lines.size(), "边界时间戳仍可解析");
    }

    // ---------- 旧 AudioPlayerActivity.parseLrc 的等价参照实现 ----------

    private static final class OldLine {
        final long timeMs;
        final String text;
        OldLine(long timeMs, String text) { this.timeMs = timeMs; this.text = text; }
    }

    private static List<OldLine> oldParseLrc(String text) {
        List<OldLine> result = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\[(\\d{1,3}):(\\d{2}(?:\\.\\d{1,3})?)\\](.*)");
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            java.util.regex.Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                long minutes = Long.parseLong(matcher.group(1));
                double seconds = Double.parseDouble(matcher.group(2));
                long millis = minutes * 60_000L + (long) (seconds * 1000);
                result.add(new OldLine(millis, matcher.group(3)));
            }
        }
        Collections.sort(result, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return result;
    }

    private interface Case {
        void run();
    }

    private static void test(String name, Case body) {
        try {
            body.run();
            passed++;
        } catch (Throwable failure) {
            throw new IllegalStateException("FAIL " + name + ": " + failure, failure);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void equal(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException("expected=" + expected + " actual=" + actual);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(message + "：expected=" + expected + " actual=" + actual);
        }
    }
}
