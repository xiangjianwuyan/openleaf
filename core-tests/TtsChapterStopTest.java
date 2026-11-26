import org.openleaf.reader.core.SpeechText;
import java.util.Arrays;
import java.util.List;

/**
 * T3 回归：TTS 听书「本章结束」睡眠判定的分章语义。
 *
 * 修前失败点：TtsActivity 曾用 index == 0 当「章末」，导致第一句讲完就停、其余位置永不触发。
 * 这里覆盖 chapterStartOffsets 的章标题识别（中文章节/楔子/序章、英文 Chapter/PROLOGUE、
 * markdown 标题、正文夹带不误判、空文本）与 chapterStops/isChapterEnd/shouldStopAtChapterEnd
 * 的组合语义（三章文本：第 1 章末句讲完为 true、第 1 章中间句为 false）。
 */
public final class TtsChapterStopTest {
    private static int passed;

    public static void main(String[] args) {
        emptyAndBlank();
        cjkHeadings();
        englishHeadings();
        markdownHeading();
        inlineMention();
        singleChapterFallback();
        threeChapterStop();
        noisyTextAlignment();
        System.out.println("PASS: " + passed + " tts chapter-stop checks");
    }

    private static void emptyAndBlank() {
        eqInts(new int[]{0}, SpeechText.chapterStartOffsets(null));
        eqInts(new int[]{0}, SpeechText.chapterStartOffsets(""));
        eqInts(new int[]{0}, SpeechText.chapterStartOffsets("   \n\t\n  \r\n"));
        passed++;
    }

    private static void cjkHeadings() {
        String text = "第一章 开端\n正文一。\n\n第2章 发展\n正文二。\n\n楔子\n正文三。\n\n序章\n正文四。\n";
        int[] expected = {
                0,
                text.indexOf("第2章 发展"),
                text.indexOf("楔子"),
                text.indexOf("序章"),
        };
        eqInts(expected, SpeechText.chapterStartOffsets(text));
        passed++;
    }

    private static void englishHeadings() {
        String text = "Chapter 1 The Beginning\nBody one.\n\nPROLOGUE\nBody two.\n";
        eqInts(new int[]{0, text.indexOf("PROLOGUE")}, SpeechText.chapterStartOffsets(text));

        // 只有 Chapter 而没有前置空行也要认（前缀规则不要求 isolated）
        String dense = "Chapter 1\nBody.\nChapter 2\nBody.\n";
        eqInts(new int[]{0, dense.indexOf("Chapter 2")}, SpeechText.chapterStartOffsets(dense));
        passed++;
    }

    private static void markdownHeading() {
        String text = "前言一段。\n\n## 标题一\n正文。\n\n### 小节\n正文。\n";
        eqInts(new int[]{0, text.indexOf("## 标题一"), text.indexOf("### 小节")},
                SpeechText.chapterStartOffsets(text));
        passed++;
    }

    private static void inlineMention() {
        // 正文里出现「第一章」但不在行首 → 不误判
        String text = "开头一段。\n\n他读到第一章时笑了。\n\n第一章讲的是开端这个故事。\n";
        eqInts(new int[]{0}, SpeechText.chapterStartOffsets(text));
        passed++;
    }

    private static void singleChapterFallback() {
        String text = "只是一段话。第二句。\n\n再一段。\n";
        eqInts(new int[]{0}, SpeechText.chapterStartOffsets(text));
        List<SpeechText.Passage> passages = passagesOf(text);
        // 未识别到章标题 → 边界退化为 [passages.size()]（读完全文才停）
        eqInts(new int[]{passages.size()}, SpeechText.chapterStops(passages, spokenOf(text)));
        passed++;
    }

    private static void threeChapterStop() {
        String text = "Chapter 1\n"
                + "The cat sat. It slept.\n\n"
                + "Chapter 2\n"
                + "A dog ran. It barked.\n\n"
                + "Chapter 3\n"
                + "A bird flew. It sang.\n";
        List<SpeechText.Passage> passages = passagesOf(text);
        int[] stops = SpeechText.chapterStops(passages, spokenOf(text));
        check(stops.length == 4, "三章 → 三个章首 + 末尾，实际 " + Arrays.toString(stops));
        check(stops[0] == 0, "首章从第 0 句开始");
        check(stops[3] == passages.size(), "末尾是 passages.size()");
        check(stops[0] < stops[1] && stops[1] < stops[2] && stops[2] < stops[3], "边界升序");

        SpeechText.SleepTimer timer = new SpeechText.SleepTimer();
        timer.setChapterEnd();
        int firstChapterLast = stops[1] - 1;
        check(SpeechText.isChapterEnd(stops, firstChapterLast), "第 1 章末句是章末");
        check(timer.shouldStopAtChapterEnd(
                SpeechText.isChapterEnd(stops, firstChapterLast)), "第 1 章末句讲完 → 停");
        check(!SpeechText.isChapterEnd(stops, 1), "第 1 章中间句不是章末");
        check(!timer.shouldStopAtChapterEnd(SpeechText.isChapterEnd(stops, 1)), "第 1 章中间句 → 不停");
        check(timer.shouldStopAtChapterEnd(
                SpeechText.isChapterEnd(stops, passages.size() - 1)), "全文末句讲完 → 停");

        timer.setMinutes(1000, 30);
        check(!timer.shouldStopAtChapterEnd(SpeechText.isChapterEnd(stops, firstChapterLast)),
                "分钟模式不受章末影响");
        passed++;
    }

    /**
     * 坐标系对齐（本轮的回归重点）：原始文本里带脚注标记 / 连续空格 / 噪声行时，
     * 章边界必须与 passages 落在同一坐标系，否则偏差逐处累积、章末判错句子。
     * 「修前」= 用原始文本算边界（旧实现），「修后」= 用 spoken 文本算边界。
     */
    private static void noisyTextAlignment() {
        String raw = "Chapter 1\n"
                + "Alpha one. Beta two[1]  with  extra spaces. Gamma three.\n"
                + "本章完\n\n"
                + "Chapter 2\n"
                + "Delta four. Epsilon five[2]  again  extra. Zeta six.\n"
                + "本章完\n\n"
                + "Chapter 3\n"
                + "Eta seven. Theta eight. Iota nine.\n"
                + "本章完\n";
        List<SpeechText.Block> source = sourceOf(raw);
        List<SpeechText.Block> spoken = SpeechText.spokenBlocks(source);
        List<SpeechText.Passage> passages = SpeechText.sentences(spoken);

        int[] fixedStops = SpeechText.chapterStops(passages, spoken);
        int[] oldStops = SpeechText.chapterStops(passages, source); // 旧实现：原始文本坐标
        System.out.println("[noisy] passages=" + passages.size()
                + " 修前stops=" + Arrays.toString(oldStops)
                + " 修后stops=" + Arrays.toString(fixedStops));
        for (int i = 0; i < passages.size(); i++) {
            System.out.println("[noisy] 句" + i + " [" + passages.get(i).text.replace("\n", "\\n") + "]"
                    + " 修前章末=" + SpeechText.isChapterEnd(oldStops, i)
                    + " 修后章末=" + SpeechText.isChapterEnd(fixedStops, i));
        }

        check(!Arrays.equals(oldStops, fixedStops), "本用例前提：两种坐标系的边界确实不同");
        check(fixedStops.length == 4, "三章 → 4 个边界，实际 " + Arrays.toString(fixedStops));
        SpeechText.SleepTimer timer = new SpeechText.SleepTimer();
        timer.setChapterEnd();
        for (int chapter = 0; chapter < 3; chapter++) {
            int last = fixedStops[chapter + 1] - 1;
            check(SpeechText.isChapterEnd(fixedStops, last), "第 " + (chapter + 1) + " 章末句是章末");
            check(timer.shouldStopAtChapterEnd(SpeechText.isChapterEnd(fixedStops, last)),
                    "第 " + (chapter + 1) + " 章末句讲完 → 停");
        }
        check(!SpeechText.isChapterEnd(fixedStops, 1), "第 1 章中间句不是章末");
        passed++;
    }

    private static List<SpeechText.Block> sourceOf(String text) {
        return Arrays.asList(new SpeechText.Block(0, text, false));
    }

    /** passages 所对应的 spoken（tidy 后）Block 列表——章边界的正确坐标系。 */
    private static List<SpeechText.Block> spokenOf(String text) {
        return SpeechText.spokenBlocks(sourceOf(text));
    }

    private static List<SpeechText.Passage> passagesOf(String text) {
        return SpeechText.sentences(spokenOf(text));
    }

    private static void eqInts(int[] expected, int[] actual) {
        if (!Arrays.equals(expected, actual))
            throw new AssertionError("expected " + Arrays.toString(expected) + " but got " + Arrays.toString(actual));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
