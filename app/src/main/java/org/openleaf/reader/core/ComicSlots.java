package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 漫画双页槽位换算（纯 JVM，无 Android 依赖）。
 *
 * 设计借鉴 Breeze（MPL-2.0）的「统一条目流 + 双页槽位 + 槽位↔页码闭环换算」思想，
 * 由 OpenLeaf 自行用 Java 重写，未复制任何源码。核心是把图片页与章节过渡卡片
 * 统一为条目流，再把单页/双页/RTL 全部映射到同一套槽位，避免多套队列。
 */
public final class ComicSlots {
    private ComicSlots() { }

    /** 条目类型：图片页 / 章节过渡卡片。 */
    public enum EntryType { IMAGE, TRANSITION }

    /** 排版三态：单页 / 双页封面独占 / 全双页（含封面）。 */
    public enum CoverMode { ALL_SINGLE, DOUBLE_NO_COVER, ALL_DOUBLE }

    /** 统一条目流中的一项。 */
    public static final class Entry {
        public final EntryType type;
        public final String key; // 图片稳定键，或过渡卡片的章节键
        public Entry(EntryType type, String key) {
            if (type == null || key == null) throw new IllegalArgumentException();
            this.type = type; this.key = key;
        }
        public static Entry image(String key) { return new Entry(EntryType.IMAGE, key); }
        public static Entry transition(String key) { return new Entry(EntryType.TRANSITION, key); }
    }

    /** 槽位内容：空白占位 / 单图 / 双图（左,右）。 */
    public static final class Slot {
        public final String left, right;
        private Slot(String left, String right) { this.left = left; this.right = right; }
        public static Slot blank() { return new Slot(null, null); }
        public static Slot single(String key) { return new Slot(key, null); }
        public static Slot pair(String left, String right) { return new Slot(left, right); }
        public boolean isBlank() { return left == null && right == null; }
        public boolean isPair() { return left != null && right != null; }
        @Override public String toString() { return "[" + left + " | " + right + "]"; }
    }

    /**
     * 构建双页槽位：过渡卡片独占一槽；连续图片段两两成组；
     * insertLeadingBlank=true 时在每个连续图片段开头插入空白占位（首页放右侧，左空）。
     * @param rtl 从右到左阅读：图片入槽顺序反转（右页先放）
     */
    public static List<Slot> buildSlots(List<Entry> entries, boolean insertLeadingBlank, boolean rtl) {
        List<Slot> slots = new ArrayList<>();
        if (entries == null || entries.isEmpty()) return slots;
        int i = 0;
        while (i < entries.size()) {
            Entry entry = entries.get(i);
            if (entry.type == EntryType.TRANSITION) {
                slots.add(Slot.single(entry.key)); // 过渡卡片独占
                i++;
                continue;
            }
            // 收集一个连续图片段
            List<String> segment = new ArrayList<>();
            while (i < entries.size() && entries.get(i).type == EntryType.IMAGE) segment.add(entries.get(i++).key);
            if (insertLeadingBlank && !segment.isEmpty()) slots.add(Slot.blank());
            int p = 0;
            while (p < segment.size()) {
                String first = segment.get(p);
                String second = p + 1 < segment.size() ? segment.get(p + 1) : null;
                if (second == null) {
                    slots.add(Slot.single(first));
                } else if (rtl) {
                    slots.add(Slot.pair(second, first)); // RTL：视觉右=第一张(先读)
                } else {
                    slots.add(Slot.pair(first, second));
                }
                p += 2;
            }
        }
        return Collections.unmodifiableList(slots);
    }

    /** 单页槽位数 = 条目数。 */
    public static int singleSlotCount(List<Entry> entries) { return entries == null ? 0 : entries.size(); }

    /**
     * 双页槽位数：图片两两成组，过渡卡片独占。
     * insertLeadingBlank 时每个连续图片段额外 +1。
     */
    public static int doubleSlotCount(List<Entry> entries, boolean insertLeadingBlank) {
        if (entries == null || entries.isEmpty()) return 0;
        int count = 0;
        int i = 0;
        while (i < entries.size()) {
            if (entries.get(i).type == EntryType.TRANSITION) { count++; i++; continue; }
            int images = 0;
            while (i < entries.size() && entries.get(i).type == EntryType.IMAGE) { images++; i++; }
            count += (images + 1) / 2;
            if (insertLeadingBlank && images > 0) count++;
        }
        return count;
    }

    /**
     * slot 序号（0-based）→ 存储的历史页码（0-based，按图片计数）。
     * 过渡卡片与空白占位不推进图片页码。返回 -1 表示该槽无图片。
     */
    public static int storedPageOfSlot(List<Entry> entries, List<Slot> slots, int slotIndex) {
        if (slots == null || slotIndex < 0 || slotIndex >= slots.size()) return -1;
        int imageCount = 0;
        for (int i = 0; i < slotIndex; i++) {
            Slot slot = slots.get(i);
            if (!slot.isBlank()) {
                if (slot.left != null) imageCount++;
                if (slot.right != null) imageCount++;
            }
        }
        Slot target = slots.get(slotIndex);
        if (target.isBlank()) return -1;
        // 过渡卡片不占图片页码：其 key 不在图片条目中
        if (target.left != null && !isImageKey(entries, target.left) && target.right == null) return imageCount;
        return imageCount;
    }

    private static boolean isImageKey(List<Entry> entries, String key) {
        if (entries == null) return false;
        for (Entry entry : entries) if (entry.type == EntryType.IMAGE && entry.key.equals(key)) return true;
        return false;
    }

    /**
     * 存储历史页码（0-based，按图片计数）→ slot 序号（0-based）。
     * 遍历槽位累计图片数，返回首次达到或超过 page 的槽位。
     */
    public static int slotOfStoredPage(List<Slot> slots, int storedPage) {
        if (slots == null || slots.isEmpty() || storedPage < 0) return -1;
        int imageCount = 0;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.isBlank()) continue;
            int before = imageCount;
            if (slot.left != null) imageCount++;
            if (slot.right != null) imageCount++;
            if (imageCount > storedPage) return i;
            if (imageCount == storedPage && before < imageCount) return i;
        }
        return slots.size() - 1;
    }

    /** 单双页切换时的粗定位：单页→双页 slot≈page/2；双页→单页 page≈slot*2。 */
    public static int mapSlotForToggle(int index, boolean toDouble) {
        if (index < 0) return 0;
        return toDouble ? index / 2 : index * 2;
    }

    /**
     * 计算本屏显示的真实图片索引（1 或 2 个，0-based）。
     * ALL_SINGLE 只显示 firstIndex；ALL_DOUBLE 把 firstIndex 与下一张配对；
     * DOUBLE_NO_COVER 的第 0 张（封面）独占一屏，其后与 ALL_DOUBLE 相同。
     * 入参越界（firstIndex<0、>=total 或 total<=0）返回空数组。
     */
    public static int[] screenImages(int firstIndex, int total, CoverMode mode) {
        if (total <= 0 || firstIndex < 0 || firstIndex >= total) return new int[0];
        if (mode == null || mode == CoverMode.ALL_SINGLE) return new int[]{firstIndex};
        if (mode == CoverMode.DOUBLE_NO_COVER && firstIndex == 0) return new int[]{0};
        if (firstIndex + 1 < total) return new int[]{firstIndex, firstIndex + 1};
        return new int[]{firstIndex};
    }

    /**
     * 计算下一屏/上一屏的 firstIndex；越界返回 -1。
     * 步长：ALL_SINGLE=1；ALL_DOUBLE=2；DOUBLE_NO_COVER 从封面 0 前进到 1、
     * 从 1 后退到 0 时 step=1，其余 step=2（屏幕序列 0,1,3,5…）。
     */
    public static int advanceFirst(int firstIndex, int total, CoverMode mode, boolean forward) {
        if (total <= 0 || firstIndex < 0 || firstIndex >= total) return -1;
        int step;
        if (mode == null || mode == CoverMode.ALL_SINGLE) {
            step = 1;
        } else if (mode == CoverMode.ALL_DOUBLE) {
            step = 2;
        } else { // DOUBLE_NO_COVER
            step = (forward ? firstIndex == 0 : firstIndex == 1) ? 1 : 2;
        }
        int next = forward ? firstIndex + step : firstIndex - step;
        return (next < 0 || next >= total) ? -1 : next;
    }

    /**
     * 切换排版档位时把当前页对齐到新模式的合法 firstIndex，保证正在看的页仍在本屏。
     * ALL_SINGLE 任意页均合法；ALL_DOUBLE 要求偶数起点（奇数回退到前一页）；
     * DOUBLE_NO_COVER 要求封面 0 或奇数起点（大于 0 的偶数回退到前一页）。
     * 越界先 clamp 到 [0, total-1]；total<=0（空列表）安全返回 0。
     */
    public static int alignFirstIndex(int currentPage, CoverMode mode, int total) {
        if (total <= 0) return 0;
        int page = currentPage;
        if (page < 0) page = 0;
        if (page >= total) page = total - 1;
        if (mode == null || mode == CoverMode.ALL_SINGLE) return page;
        if (mode == CoverMode.ALL_DOUBLE) return (page % 2 == 1) ? page - 1 : page;
        // DOUBLE_NO_COVER：封面 0 独占，其后起点为 1,3,5…
        return (page > 0 && page % 2 == 0) ? page - 1 : page;
    }

    /** 稳定尺寸缓存键：避免跨章同 localPageIndex 串味。 */
    public static int stableSizeCacheIndex(int chapterOrder, int localPageIndex) {
        int hash = chapterOrder * 31 + localPageIndex;
        return 100000 + (hash & 0x3FFFFFFF);
    }
}
