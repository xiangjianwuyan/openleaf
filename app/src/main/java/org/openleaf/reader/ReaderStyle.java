package org.openleaf.reader;

import org.openleaf.reader.core.ReaderPresets;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 阅读排版设置。借鉴 Anx Reader (MIT) BookStyle 模型与 KOReader 档位化设计，
 * 由 OpenLeaf 自行实现。所有参数经 SharedPreferences 持久化，字号沿用阅读器既有键。
 */
final class ReaderStyle {
    // 行距倍数档位（对应 KOReader 70%–200% 区间；1.25 为 OpenLeaf 原默认值）
    static final float[] LINE_SPACING = {1.0f, 1.15f, 1.25f, 1.5f, 1.75f, 2.0f};
    // 段距（sp）
    static final int[] PARA_SPACING = {0, 4, 8, 12, 20};
    // 字距（em 的千分比，Paint.setLetterSpacing 使用 px = em*值）
    static final float[] LETTER_SPACING = {0f, 0.03f, 0.06f, 0.1f, 0.15f};
    // 左右页边距（dp）
    static final int[] SIDE_MARGIN = {12, 20, 28, 36, 48, 64};
    // 对齐：0 左 / 1 两端 / 2 居中 / 3 右
    static final int ALIGN_LEFT = 0, ALIGN_JUSTIFY = 1, ALIGN_CENTER = 2, ALIGN_RIGHT = 3;
    // 首行缩进字符数
    static final int[] INDENT = {0, 1, 2};
    // 简繁转换：0 原 / 1 简转繁 / 2 繁转简
    static final int CONVERT_OFF = 0, CONVERT_S2T = 1, CONVERT_T2S = 2;

    final SharedPreferences prefs;
    int lineSpacing, paraSpacing, letterSpacing, sideMargin, align, indent, convert;
    /** 当前套用的预设（0 自定义 / 1 紧凑 / 2 标准 / 3 宽松 / 4 小说 / 5 漫画），读取即 clamp。 */
    int stylePreset;

    private ReaderStyle(SharedPreferences prefs) {
        this.prefs = prefs;
        stylePreset = clamp(prefs.getInt("style_preset", 0), 6);
        lineSpacing = clamp(prefs.getInt("style_line", 2), LINE_SPACING.length);
        paraSpacing = clamp(prefs.getInt("style_para", 1), PARA_SPACING.length);
        letterSpacing = clamp(prefs.getInt("style_letter", 0), LETTER_SPACING.length);
        sideMargin = clamp(prefs.getInt("style_margin", 2), SIDE_MARGIN.length);
        align = clamp(prefs.getInt("style_align", ALIGN_LEFT), 4);
        indent = clamp(prefs.getInt("style_indent", 0), INDENT.length);
        convert = clamp(prefs.getInt("style_convert", CONVERT_OFF), 3);
    }

    static ReaderStyle get(Context context) {
        return new ReaderStyle(context.getSharedPreferences("reader", 0));
    }

    private static int clamp(int value, int length) {
        return value < 0 || value >= length ? 0 : value;
    }

    void save() {
        prefs.edit()
                .putInt("style_line", lineSpacing)
                .putInt("style_para", paraSpacing)
                .putInt("style_letter", letterSpacing)
                .putInt("style_margin", sideMargin)
                .putInt("style_align", align)
                .putInt("style_indent", indent)
                .putInt("style_convert", convert)
                .putInt("style_preset", stylePreset)
                .apply();
    }

    /**
     * P1-1：套用预设，只把 core 映射出的 7 个索引写回现有字段；
     * 不改任何档位数组、不改 7 个 style_* 键的语义。落盘由调用方 save() 完成。
     */
    boolean applyPreset(int presetId) {
        int effective = ReaderPresets.isValid(presetId) ? presetId : ReaderPresets.STANDARD;
        int[] values = ReaderPresets.valuesFor(effective);
        lineSpacing = values[0];
        paraSpacing = values[1];
        letterSpacing = values[2];
        sideMargin = values[3];
        align = values[4];
        indent = values[5];
        convert = values[6];
        stylePreset = effective;
        return true;
    }

    float lineSpacingMultiplier() { return LINE_SPACING[lineSpacing]; }
    int paraSpacingSp() { return PARA_SPACING[paraSpacing]; }
    float letterSpacingEm() { return LETTER_SPACING[letterSpacing]; }
    int sideMarginDp() { return SIDE_MARGIN[sideMargin]; }
    int indentChars() { return INDENT[indent]; }
}
