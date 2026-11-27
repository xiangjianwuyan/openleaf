package org.openleaf.reader;

import org.openleaf.reader.core.ReaderPresets;

/**
 * 0.57.0 P1-2 排版草稿：面板内的纯数据暂存，点「应用」才写回 ReaderStyle 与 prefs。
 *
 * <p>零 SharedPreferences、零 Android View 依赖。本类只持有 10 个标量字段，不读/写任何
 * 持久化键、不构造任何 View；只借助 ReaderStyle 的档位常量与 ReaderPresets 的纯数据
 * 映射做范围收敛，因此可被 core JVM javac 探针直接编译验证。</p>
 *
 * <p>生命周期与排版对话框完全绑定：打开面板时 {@link #from(ReaderStyle, boolean, int)}，
 * 取消/关闭时由调用方清引用；只有点「应用」才 {@link #copyInto(ReaderStyle)} 真正写回
 * 持久化对象。</p>
 */
final class StyleDraft {
    /** 与 settings() 既有的字号入口保持同一 clamp 区间（14–32，步进 2）。 */
    static final int MIN_FONT_SIZE = 14;
    static final int MAX_FONT_SIZE = 32;

    private int preset;
    private int lineSpacing;
    private int paraSpacing;
    private int letterSpacing;
    private int sideMargin;
    private int align;
    private int indent;
    private int convert;
    private boolean paragraphReflow;
    private int fontSize;

    private StyleDraft() { }

    /**
     * 从当前已持久化状态拍一份快照。
     *
     * @param style           当前排版（只读）；null 时全部档位取安全默认 0
     * @param paragraphReflow 当前段落重排开关（不在 ReaderStyle 内）
     * @param fontSize        当前字号（不在 ReaderStyle 内）
     */
    static StyleDraft from(ReaderStyle style, boolean paragraphReflow, int fontSize) {
        StyleDraft draft = new StyleDraft();
        if (style != null) {
            draft.preset = clampPreset(style.stylePreset);
            draft.lineSpacing = clamp(style.lineSpacing, ReaderStyle.LINE_SPACING.length);
            draft.paraSpacing = clamp(style.paraSpacing, ReaderStyle.PARA_SPACING.length);
            draft.letterSpacing = clamp(style.letterSpacing, ReaderStyle.LETTER_SPACING.length);
            draft.sideMargin = clamp(style.sideMargin, ReaderStyle.SIDE_MARGIN.length);
            draft.align = clamp(style.align, 4);
            draft.indent = clamp(style.indent, ReaderStyle.INDENT.length);
            draft.convert = clamp(style.convert, 3);
        } else {
            draft.preset = ReaderPresets.CUSTOM;
        }
        draft.paragraphReflow = paragraphReflow;
        draft.fontSize = clampFontSize(fontSize);
        return draft;
    }

    /**
     * 把草稿的 8 个排版档位写回 ReaderStyle（仅内存，不落盘；落盘由调用方 style.save() 完成）。
     * fontSize / paragraphReflow 不属于 ReaderStyle，由调用方另行写 prefs。
     */
    void copyInto(ReaderStyle style) {
        if (style == null) return;
        style.lineSpacing = lineSpacing;
        style.paraSpacing = paraSpacing;
        style.letterSpacing = letterSpacing;
        style.sideMargin = sideMargin;
        style.align = align;
        style.indent = indent;
        style.convert = convert;
        style.stylePreset = preset;
    }

    /** 从 core 的纯数据映射套用预设：只改 7 项索引与 preset 字段，不读 prefs。 */
    void applyPreset(int presetId) {
        int effective = ReaderPresets.isValid(presetId) ? presetId : ReaderPresets.STANDARD;
        int[] values = ReaderPresets.valuesFor(effective);
        lineSpacing = clamp(values[0], ReaderStyle.LINE_SPACING.length);
        paraSpacing = clamp(values[1], ReaderStyle.PARA_SPACING.length);
        letterSpacing = clamp(values[2], ReaderStyle.LETTER_SPACING.length);
        sideMargin = clamp(values[3], ReaderStyle.SIDE_MARGIN.length);
        align = clamp(values[4], 4);
        indent = clamp(values[5], ReaderStyle.INDENT.length);
        convert = clamp(values[6], 3);
        preset = effective;
    }

    /**
     * 逐项微调后的预设态重算：7 项若恰好回到某个预设则显示该预设，否则显示「自定义」。
     * 与 ReaderPresets.detectPreset 的既有语义一致，不引入新判定口径。
     */
    void markCustom() {
        preset = detectedPreset();
    }

    /** 按 7 项实际数值重算档位标题（与 0.56.0 面板的 selectedPreset 口径一致）。 */
    int detectedPreset() {
        return ReaderPresets.detectPreset(current7());
    }

    /** 供 ReaderPresets.detectPreset 使用的 7 项索引副本，顺序 [line,para,letter,margin,align,indent,convert]。 */
    int[] current7() {
        return new int[]{lineSpacing, paraSpacing, letterSpacing, sideMargin, align, indent, convert};
    }

    int preset() { return preset; }
    void setPreset(int value) { preset = clampPreset(value); }

    int lineSpacing() { return lineSpacing; }
    void setLineSpacing(int value) { lineSpacing = clamp(value, ReaderStyle.LINE_SPACING.length); }

    int paraSpacing() { return paraSpacing; }
    void setParaSpacing(int value) { paraSpacing = clamp(value, ReaderStyle.PARA_SPACING.length); }

    int letterSpacing() { return letterSpacing; }
    void setLetterSpacing(int value) { letterSpacing = clamp(value, ReaderStyle.LETTER_SPACING.length); }

    int sideMargin() { return sideMargin; }
    void setSideMargin(int value) { sideMargin = clamp(value, ReaderStyle.SIDE_MARGIN.length); }

    int align() { return align; }
    void setAlign(int value) { align = clamp(value, 4); }

    int indent() { return indent; }
    void setIndent(int value) { indent = clamp(value, ReaderStyle.INDENT.length); }

    int convert() { return convert; }
    void setConvert(int value) { convert = clamp(value, 3); }

    boolean paragraphReflow() { return paragraphReflow; }
    void setParagraphReflow(boolean value) { paragraphReflow = value; }

    int fontSize() { return fontSize; }
    void setFontSize(int value) { fontSize = clampFontSize(value); }

    /** 10 项全等才相等，供「草稿是否改动」与探针比对使用。 */
    boolean equals(StyleDraft other) {
        if (other == null) return false;
        return preset == other.preset
                && lineSpacing == other.lineSpacing
                && paraSpacing == other.paraSpacing
                && letterSpacing == other.letterSpacing
                && sideMargin == other.sideMargin
                && align == other.align
                && indent == other.indent
                && convert == other.convert
                && paragraphReflow == other.paragraphReflow
                && fontSize == other.fontSize;
    }

    private static int clamp(int value, int length) {
        return value < 0 || value >= length ? 0 : value;
    }

    private static int clampPreset(int value) {
        return ReaderPresets.isValid(value) ? value : ReaderPresets.CUSTOM;
    }

    private static int clampFontSize(int value) {
        return value < MIN_FONT_SIZE || value > MAX_FONT_SIZE ? 20 : value;
    }
}
