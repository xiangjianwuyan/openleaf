import org.openleaf.reader.core.SpeechText;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** TTS 文本预处理回归：front/back 裁剪、句子锚点、定位、睡眠定时器、世代令牌。 */
public final class SpeechTextTest {
    private static int passed;

    public static void main(String[] args) {
        frontBackCut();
        runningHeaders();
        sentenceAnchors();
        abbreviation();
        locate();
        sleepTimer();
        generation();
        System.out.println("PASS: " + passed + " speech text checks");
    }

    private static SpeechText.Block b(int section, String text, boolean heading) {
        return new SpeechText.Block(section, text, heading);
    }

    private static void frontBackCut() {
        List<SpeechText.Block> source = Arrays.asList(
                b(0, "封面", true),
                b(1, "版权所有 不得翻印", false),
                b(2, "目录", true),
                b(3, "第一章 开端", true),
                b(4, "正文内容在这里。这是第一句。", false),
                b(5, "附录", true),
                b(6, "附录里的补充材料。", false));
        List<SpeechText.Block> spoken = SpeechText.spokenBlocks(source);
        check(!spoken.isEmpty(), "裁剪后非空");
        check(spoken.get(0).text.contains("第一章"), "从正文标题开始: " + spoken.get(0).text);
        boolean hasCover = false, hasAppendix = false;
        for (SpeechText.Block block : spoken) {
            if (block.text.contains("封面") || block.text.contains("版权所有")) hasCover = true;
            if (block.text.contains("附录")) hasAppendix = true;
        }
        check(!hasCover, "front matter 已去除");
        check(!hasAppendix, "back matter 已去除");
        passed++;
    }

    private static void runningHeaders() {
        // 重复的短页眉行（数字归一化后 key 相同）应被去除，含句末标点的保留
        java.util.List<SpeechText.Block> source = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) source.add(b(i, "第 1 页", false));
        source.add(b(10, "正文段落。", false));
        List<SpeechText.Block> spoken = SpeechText.spokenBlocks(source);
        int headers = 0;
        for (SpeechText.Block block : spoken) if (block.text.contains("页")) headers++;
        check(headers == 0, "重复页眉被去除，剩余 " + headers);
        passed++;
    }

    private static void sentenceAnchors() {
        java.util.List<SpeechText.Block> blocks = Arrays.asList(
                b(0, "Hello world. This is a test.", false));
        List<SpeechText.Passage> passages = SpeechText.sentences(blocks);
        check(passages.size() == 2, "两句切分: " + passages);
        eq("Hello world.", passages.get(0).text);
        eq("This is a test.", passages.get(1).text);
        check(passages.get(1).offset > passages.get(0).offset, "锚点递增");
        passed++;
    }

    private static void abbreviation() {
        java.util.List<SpeechText.Block> blocks = Arrays.asList(
                b(0, "Mr. Smith arrived. He was late.", false));
        List<SpeechText.Passage> passages = SpeechText.sentences(blocks);
        // "Mr." 不应单独成句
        boolean hasMr = false;
        for (SpeechText.Passage passage : passages) if (passage.text.equals("Mr.")) hasMr = true;
        check(!hasMr, "缩写 Mr. 不单独成句: " + passages);
        passed++;
    }

    private static void locate() {
        java.util.List<SpeechText.Block> blocks = Arrays.asList(
                b(0, "One. Two. Three.", false));
        List<SpeechText.Passage> passages = SpeechText.sentences(blocks);
        eq(0, SpeechText.locate(passages, 0, 0));
        check(SpeechText.locate(passages, 0, 8) >= 2, "定位到第三句");
        eq(-1, SpeechText.locate(Collections.<SpeechText.Passage>emptyList(), 0, 0));
        passed++;
    }

    private static void sleepTimer() {
        SpeechText.SleepTimer timer = new SpeechText.SleepTimer();
        timer.setMinutes(1000, 30);
        eq(30 * 60L, timer.remainingSeconds(1000));
        check(!timer.expired(1000 + 29 * 60_000L), "未到点");
        check(timer.expired(1000 + 30 * 60_000L), "到点");
        timer.setChapterEnd();
        check(timer.shouldStopAtChapterEnd(true), "章末讲完即停");
        check(!timer.shouldStopAtChapterEnd(false), "章中不停");
        timer.off();
        eq(-1L, timer.remainingSeconds(1000));
        passed++;
    }

    private static void generation() {
        SpeechText.Generation gen = new SpeechText.Generation();
        long tag = gen.next();
        check(tag == gen.current(), "世代令牌即当前值");
        check(gen.next() != tag, "再次递增（旧世代回调据此丢弃，防串音）");
        passed++;
    }

    private static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
