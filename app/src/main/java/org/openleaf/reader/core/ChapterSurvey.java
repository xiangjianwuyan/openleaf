package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.List;

/** 批量取「每章标题 + 前 N 字符」，带章数上限与时间预算、可返回部分结果。纯 JVM，无 Android 类型。 */
public final class ChapterSurvey {
    public static final int DEFAULT_HEAD_CHARS  = 240;   // 足够覆盖「标题行 + 首句」
    public static final int DEFAULT_MAX_HEADS   = 2000;  // 超过则不读正文，只留标题
    public static final long DEFAULT_BUDGET_MS  = 1500;  // 读取阶段预算（不含 open）

    private static final int BATCH = 200;

    public static final class Head {
        public final String title;   // 章节标题（可能为空 → 由 DigestBuilder 占位）
        public final String text;    // 该章前 headChars 个字符
        public final boolean cut;    // true = 还有更多正文未读

        Head(String title, String text, boolean cut) {
            this.title = title;
            this.text = text;
            this.cut = cut;
        }
    }

    public final List<Head> heads;      // size = 已读章数
    public final String[] titles;       // total 全量标题（来自 meta(i).title，零 I/O）
    public final int[] spineIndex;      // 与 titles 同长（EPUB 当前章映射用，TXT 恒 -1）
    public final int total;             // book.size()
    public final int maxHeads;
    public final boolean complete;      // heads 覆盖全部章节
    public final boolean timedOut;      // 预算用尽提前返回

    private ChapterSurvey(List<Head> heads, String[] titles, int[] spineIndex,
                          int total, int maxHeads, boolean complete, boolean timedOut) {
        this.heads = heads;
        this.titles = titles;
        this.spineIndex = spineIndex;
        this.total = total;
        this.maxHeads = maxHeads;
        this.complete = complete;
        this.timedOut = timedOut;
    }

    /** 同步执行；每读完一批（BATCH=200）调一次 progress，便于边读边渲染。 */
    public static ChapterSurvey read(LazyChapterBook book, int headChars, int maxHeads,
                                     long budgetMs, Progress progress) {
        int total = book.size();
        String[] titles = new String[total];
        int[] spine = new int[total];
        // titles/spine 无论预算是否用尽都完整填充——章节树只需要标题。
        for (int i = 0; i < total; i++) {
            LazyChapterBook.Section meta = book.meta(i);
            titles[i] = meta.title;
            spine[i] = meta.spineIndex;
        }
        int cap = Math.min(total, Math.max(0, maxHeads));
        List<Head> heads = new ArrayList<>(cap);
        boolean timedOut = budgetMs <= 0; // 预算非正：立刻超时且 heads 为空
        if (!timedOut) {
            long startMs = System.currentTimeMillis();
            int i = 0;
            while (i < cap) {
                if (System.currentTimeMillis() - startMs >= budgetMs) { // 每批开始前的预算检查点
                    timedOut = true;
                    break;
                }
                int end = Math.min(cap, i + BATCH);
                for (int j = i; j < end; j++) {
                    String text = book.head(j, headChars); // 只读前缀，禁止 load(i)
                    boolean cut = text.length() < book.meta(j).charCount;
                    heads.add(new Head(titles[j], text, cut));
                }
                if (progress != null) {
                    progress.onBatch(new ChapterSurvey(new ArrayList<>(heads), titles, spine,
                            total, maxHeads, false, false));
                }
                i = end;
            }
        }
        boolean complete = heads.size() == total;
        return new ChapterSurvey(heads, titles, spine, total, maxHeads, complete, timedOut);
    }

    public interface Progress { void onBatch(ChapterSurvey snapshot); }
}
