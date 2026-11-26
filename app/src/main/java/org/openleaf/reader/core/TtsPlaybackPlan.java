package org.openleaf.reader.core;

/**
 * 听书播放的位置演算：讲完一句之后该讲哪一句、章末该不该跨章、全书到没到头。
 *
 * <p>做成无状态的纯函数而不是有状态的游标，是因为「当前章有多少句」必须等该章正文
 * 载入之后才知道；把句数当入参传进来，服务就只在真正需要时载入一章，不必把整本书
 * 切句进内存，这类分支也能被 core-tests 直接覆盖（Activity/Service 在宿主侧跑不了）。
 */
public final class TtsPlaybackPlan {

    private TtsPlaybackPlan() { }

    /** 一次位置移动的结果：目标章 + 目标句。 */
    public static final class Step {
        /** 目标章下标。 */
        public final int chapter;
        /** 目标句下标；{@link #LAST_PASSAGE} 表示「载入该章后停在最后一句」。 */
        public final int index;
        /** 是否换了章（换章意味着需要重新载入正文与切句）。 */
        public final boolean chapterChanged;

        Step(int chapter, int index, boolean chapterChanged) {
            this.chapter = chapter;
            this.index = index;
            this.chapterChanged = chapterChanged;
        }
    }

    /** 载入后停在末句的占位下标。 */
    public static final int LAST_PASSAGE = -1;

    /**
     * 讲完 {@code (chapter, index)} 之后的位置。
     *
     * @param chapters     全书章数
     * @param chapter      当前章
     * @param index        当前句
     * @param inChapter    当前章的句数（0 表示空章）
     * @return 下一句；全书结束返回 {@code null}
     */
    public static Step after(int chapters, int chapter, int index, int inChapter) {
        if (chapters <= 0) return null;
        if (chapter < 0 || chapter >= chapters) return null;
        // 空章直接跳到下一章首句：没有任何句子可讲，停在原地会死循环
        if (inChapter <= 0) return nextChapter(chapters, chapter);
        if (index + 1 < inChapter) return new Step(chapter, index + 1, false);
        return nextChapter(chapters, chapter);
    }

    /** 下一章首句；已是最后一章返回 {@code null}。 */
    public static Step nextChapter(int chapters, int chapter) {
        if (chapters <= 0) return null;
        int next = chapter + 1;
        if (chapter < 0 || next >= chapters) return null;
        return new Step(next, 0, true);
    }

    /**
     * 上一章，载入后停在末句；已是第一章返回 {@code null}。
     * <p>不能直接给出句下标——上一章有多少句要等载入后才知道，所以用 {@link #LAST_PASSAGE} 占位。
     */
    public static Step previousChapter(int chapters, int chapter) {
        if (chapters <= 0) return null;
        int previous = chapter - 1;
        if (chapter <= 0 || previous < 0) return null;
        return new Step(previous, LAST_PASSAGE, true);
    }

    /**
     * 把 {@link #LAST_PASSAGE} 这类占位下标落到真实下标上，并夹在合法范围内。
     * @return 合法句下标；空章返回 -1
     */
    public static int resolve(int index, int inChapter) {
        if (inChapter <= 0) return -1;
        if (index == LAST_PASSAGE) return inChapter - 1;
        if (index < 0) return 0;
        return Math.min(index, inChapter - 1);
    }
}
