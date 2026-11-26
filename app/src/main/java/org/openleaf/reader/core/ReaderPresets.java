package org.openleaf.reader.core;

/**
 * 智能排版预设：把预设 id 映射为 ReaderStyle 现有 7 项参数的索引值。
 *
 * <p>纯 JVM 类，无 Android 依赖。只产出 7 个 int 索引，不碰 ReaderStyle 的档位值数组，
 * 也不定义新的 style_* 键。"标准"预设锁死为 0.27.0 默认值 {2,1,0,2,0,0,0}，
 * 保证升级后未主动套用预设的用户渲染逐字不变。</p>
 */
public final class ReaderPresets {
    public static final int CUSTOM = 0;
    public static final int COMPACT = 1;
    public static final int STANDARD = 2;
    public static final int LOOSE = 3;
    public static final int NOVEL = 4;
    public static final int COMIC = 5;
    public static final int STYLE_COUNT = 7;

    // 顺序：[line, para, letter, margin, align, indent, convert]；全部由 OpenLeaf 自写
    private static final int[] COMPACT_VALUES = {0, 0, 0, 0, 0, 0, 0};
    private static final int[] STANDARD_VALUES = {2, 1, 0, 2, 0, 0, 0};
    private static final int[] LOOSE_VALUES = {4, 3, 1, 4, 1, 0, 0};
    private static final int[] NOVEL_VALUES = {2, 1, 0, 2, 0, 1, 0};
    private static final int[] COMIC_VALUES = {1, 1, 0, 5, 2, 0, 0};

    private ReaderPresets() { }

    /**
     * 返回预设对应的 7 项索引副本。非法 id（&lt;0 或 &gt;5）与"自定义"态都安全降级为"标准"，
     * 绝不越界、不抛异常。
     */
    public static int[] valuesFor(int presetId) {
        int[] source = sourceOf(presetId);
        int[] copy = new int[STYLE_COUNT];
        System.arraycopy(source, 0, copy, 0, STYLE_COUNT);
        return copy;
    }

    /**
     * 当前 7 项与某个预设全等则返回该 id；否则返回 CUSTOM(0)。
     * 供"任一项微调后高亮切到自定义"使用，微调后的数值本身照常生效。
     */
    public static int detectPreset(int[] current7) {
        if (current7 == null || current7.length != STYLE_COUNT) return CUSTOM;
        for (int id = COMPACT; id <= COMIC; id++) {
            int[] values = sourceOf(id);
            boolean same = true;
            for (int i = 0; i < STYLE_COUNT; i++) {
                if (current7[i] != values[i]) { same = false; break; }
            }
            if (same) return id;
        }
        return CUSTOM;
    }

    /** id 在 [0,5] 内为合法（0 表示未套用/自定义）。 */
    public static boolean isValid(int id) {
        return id >= CUSTOM && id <= COMIC;
    }

    private static int[] sourceOf(int presetId) {
        switch (presetId) {
            case COMPACT: return COMPACT_VALUES;
            case LOOSE: return LOOSE_VALUES;
            case NOVEL: return NOVEL_VALUES;
            case COMIC: return COMIC_VALUES;
            case STANDARD:
            case CUSTOM:
            default: return STANDARD_VALUES;
        }
    }
}
