import org.openleaf.reader.core.TtsPlaybackPlan;

/**
 * 听书位置演算的边界回归。
 *
 * <p>覆盖「讲完一句之后停哪儿」的全部岔路：章内推进、章末跨章、全书结束、空章跳过、
 * 首章回退、占位下标落地。这几条分支在 Activity/Service 里跑不起来（需要 TextToSpeech
 * 与前台服务），所以放在纯逻辑层被测。
 */
public final class TtsPlaybackPlanTest {
    private static int passed;

    public static void main(String[] args) {
        withinChapter();
        chapterBoundary();
        endOfBook();
        emptyChapter();
        nextChapterEdges();
        previousChapterEdges();
        resolveIndex();
        degenerateInputs();
        System.out.println("PASS: " + passed + " tts playback plan checks");
    }

    /** 章内还有下一句：只推句，不换章。 */
    private static void withinChapter() {
        TtsPlaybackPlan.Step step = TtsPlaybackPlan.after(3, 0, 0, 5);
        require(step != null, "章内应有下一句");
        eq(0, step.chapter, "章内推进不应换章");
        eq(1, step.index, "章内推进应到下一句");
        require(!step.chapterChanged, "章内推进不应标记换章");

        TtsPlaybackPlan.Step last = TtsPlaybackPlan.after(3, 1, 3, 5);
        eq(1, last.chapter, "章末前一句仍在本章");
        eq(4, last.index, "章末前一句应推到本章最后一句");
        passed++;
    }

    /** 章末最后一句讲完 → 下一章首句，且必须标记换章（服务要重新载入正文）。 */
    private static void chapterBoundary() {
        TtsPlaybackPlan.Step step = TtsPlaybackPlan.after(3, 0, 4, 5);
        require(step != null, "非末章章末应能跨章");
        eq(1, step.chapter, "章末应进入下一章");
        eq(0, step.index, "跨章应停在下一章首句");
        require(step.chapterChanged, "跨章必须标记 chapterChanged，否则服务不会重新载入正文");
        passed++;
    }

    /** 全书最后一句讲完 → null（服务据此结束播放，而不是越界后静默停住）。 */
    private static void endOfBook() {
        require(TtsPlaybackPlan.after(3, 2, 4, 5) == null, "最后一章最后一句之后应为 null");
        require(TtsPlaybackPlan.after(1, 0, 0, 1) == null, "只有一句的书讲完应为 null");
        // 末章但还没到最后一句时，仍要能正常推进
        require(TtsPlaybackPlan.after(3, 2, 0, 5) != null, "末章中间句之后仍应有下一句");
        passed++;
    }

    /** 空章没有句子可讲，必须跳到下一章，否则停在原地会死循环。 */
    private static void emptyChapter() {
        TtsPlaybackPlan.Step step = TtsPlaybackPlan.after(3, 0, 0, 0);
        require(step != null, "空章应跳到下一章而不是结束");
        eq(1, step.chapter, "空章应跳到下一章");
        require(step.chapterChanged, "空章跳转应标记换章");
        require(TtsPlaybackPlan.after(1, 0, 0, 0) == null, "末章为空时应结束，不能返回自身");
        passed++;
    }

    private static void nextChapterEdges() {
        TtsPlaybackPlan.Step step = TtsPlaybackPlan.nextChapter(3, 1);
        eq(2, step.chapter, "nextChapter 应进入下一章");
        eq(0, step.index, "nextChapter 应落在首句");
        require(step.chapterChanged, "nextChapter 必须标记换章");
        require(TtsPlaybackPlan.nextChapter(3, 2) == null, "末章没有下一章");
        require(TtsPlaybackPlan.nextChapter(0, 0) == null, "没有章节时没有下一章");
        passed++;
    }

    private static void previousChapterEdges() {
        TtsPlaybackPlan.Step step = TtsPlaybackPlan.previousChapter(3, 2);
        eq(1, step.chapter, "previousChapter 应回到上一章");
        eq(TtsPlaybackPlan.LAST_PASSAGE, step.index, "上一章句数未知，应先用末句占位");
        require(step.chapterChanged, "previousChapter 必须标记换章");
        require(TtsPlaybackPlan.previousChapter(3, 0) == null, "首章没有上一章");
        passed++;
    }

    /** 占位下标必须能被安全落到真实下标上。 */
    private static void resolveIndex() {
        eq(4, TtsPlaybackPlan.resolve(TtsPlaybackPlan.LAST_PASSAGE, 5), "末句占位应解析为最后一句的下标");
        eq(9, TtsPlaybackPlan.resolve(99, 10), "越界下标应夹到最后一句");
        eq(0, TtsPlaybackPlan.resolve(-5, 10), "负数（非末句占位）应夹到首句");
        eq(-1, TtsPlaybackPlan.resolve(0, 0), "空章解析结果应为 -1");
        eq(-1, TtsPlaybackPlan.resolve(TtsPlaybackPlan.LAST_PASSAGE, 0), "空章的末句占位仍为 -1");
        passed++;
    }

    /** 退化入参不得抛异常，只返回 null。 */
    private static void degenerateInputs() {
        require(TtsPlaybackPlan.after(0, 0, 0, 5) == null, "没有章节应为 null");
        require(TtsPlaybackPlan.after(-1, 0, 0, 5) == null, "负章数应为 null");
        require(TtsPlaybackPlan.after(3, -1, 0, 5) == null, "负章下标应为 null");
        require(TtsPlaybackPlan.after(3, 3, 0, 5) == null, "章下标越界应为 null");
        require(TtsPlaybackPlan.previousChapter(0, 0) == null, "没有章节时没有上一章");
        passed++;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void eq(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
        }
    }
}
