package org.openleaf.reader;

import org.openleaf.reader.core.BookSearch;
import org.openleaf.reader.core.ParagraphReflow;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.UnderlineSpan;

import java.util.List;

/**
 * P1-4 显示层编排薄类：把"原文 → reflow → 简繁转换(1:1) → mapSpan 映射 → 设 span → setText"
 * 集中到一处，便于 QA 验证顺序。仍由 ReaderActivity.applySearchHighlights() 唯一调用。
 *
 * <p>关闭态（reflowEnabled=false）完全走 0.27.0 原路径：displayText=原 String、恒等坐标，
 * 因此渲染逐字一致。所有偏移映射只改显示坐标，绝不回写 Anchor / Hit / DB / 缓存。</p>
 */
final class ReflowUi {
    private static final int HIT_ACTIVE_BG = 0xFFFFB347;
    private static final int HIT_BG = 0xFFFFE6A3;
    private static final int HIT_ACTIVE_FG = 0xFF672500;
    private static final int HIT_FG = 0xFF502E12;

    private ReflowUi() { }

    /**
     * 渲染当前章文本。indentPx 由调用方按 style.indentChars()*fontSize 换算为像素；
     * 传 0 表示不叠加首行缩进。records / hits 可为空列表，按 chapter 过滤。
     */
    static SpannableString renderWithReflow(String original, boolean reflowEnabled,
                                            int convertMode, int indentPx,
                                            List<AnnotationStore.Record> records,
                                            List<BookSearch.Hit> hits,
                                            int chapter, int activeMatch) {
        String source = original == null ? "" : original;
        String display = source;
        int[] map = null;
        if (reflowEnabled) {
            ParagraphReflow.Result reflowed = ParagraphReflow.reflow(source, true, true, true);
            display = reflowed.displayText;
            map = reflowed.map;
        }

        // M5：简繁转换逐 char 替换、长度不变，故 map 在转换后仍然有效
        String converted = ChineseConverter.convert(display, convertMode);
        if (converted == null) converted = display;
        boolean mappingValid = map != null && converted.length() == display.length();
        if (!mappingValid) map = null; // 防御：长度异常时退化为恒等坐标，绝不越界
        int displayLen = converted.length();

        SpannableString marked = new SpannableString(converted);

        // M7：缩进仍走 LeadingMarginSpan；重排开启时，段首已有缩进字符的段不叠加
        if (indentPx > 0) {
            boolean[] decisions = mappingValid ? ParagraphReflow.indentDecisions(converted) : null;
            int paragraphStart = 0;
            int line = 0;
            for (int i = 0; i <= displayLen; i++) {
                if (i == displayLen || converted.charAt(i) == '\n') {
                    if (i > paragraphStart && converted.charAt(paragraphStart) != '\n') {
                        boolean alreadyIndented = decisions != null && line < decisions.length && decisions[line];
                        if (!alreadyIndented) {
                            marked.setSpan(new LeadingMarginSpan.Standard(indentPx), paragraphStart,
                                    paragraphStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                    }
                    paragraphStart = i + 1;
                    line++;
                }
            }
        }

        // 标注 span：原文锚点 → 显示坐标（关闭态恒等，仅做 0.27.0 同款 clamp）
        if (records != null) {
            for (AnnotationStore.Record record : records) {
                if (record == null || record.anchor == null || record.anchor.chapter != chapter) continue;
                int[] span = mapped(map, record.anchor.start, record.anchor.end, displayLen);
                int start = span[0], end = span[1];
                if (end <= start) continue;
                if (AnnotationStore.STYLE_UNDERLINE.equals(record.style))
                    marked.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                else if (AnnotationStore.STYLE_STRIKE.equals(record.style))
                    marked.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                else
                    marked.setSpan(new BackgroundColorSpan(record.color), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        // 搜索 span：Hit.matchStarts/matchEnds 也是原文偏移，统一走 mapSpan
        if (hits != null) {
            for (int i = 0; i < hits.size(); i++) {
                BookSearch.Hit hit = hits.get(i);
                if (hit == null || hit.chapter != chapter) continue;
                boolean active = i == activeMatch;
                for (int k = 0; k < hit.matchStarts.length; k++) {
                    int[] span = mapped(map, hit.matchStarts[k], hit.matchEnds[k], displayLen);
                    int start = span[0], end = span[1];
                    marked.setSpan(new BackgroundColorSpan(active ? HIT_ACTIVE_BG : HIT_BG),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    marked.setSpan(new ForegroundColorSpan(active ? HIT_ACTIVE_FG : HIT_FG),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        return marked;
    }

    private static int[] mapped(int[] map, int sourceStart, int sourceEnd, int displayLen) {
        if (map != null) return ParagraphReflow.mapSpan(map, sourceStart, sourceEnd, displayLen);
        // 关闭态：与 0.27.0 applySearchHighlights 的 clamp 逐字一致
        int start = Math.max(0, Math.min(sourceStart, displayLen));
        int end = Math.max(start, Math.min(sourceEnd, displayLen));
        return new int[]{start, end};
    }
}
