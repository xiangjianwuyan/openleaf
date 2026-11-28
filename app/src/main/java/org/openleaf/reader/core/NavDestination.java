package org.openleaf.reader.core;

/**
 * OpenLeaf v4 四目的地单一真源（纯 JVM，零 Android import）。
 *
 * <p>v4 把旧的五导航（书库/发现/想法/记录/关于）固定为四项：
 * 书库 / 媒体 / 扩展 / 我的。本枚举同时承载三件事：
 * <ol>
 *   <li>{@link #id()}：MainActivity 内部 {@code tab} 值。刻意让旧 tab=0/1/2
 *       继续指向书库/媒体/关于(=我的)，从而「升级后旧索引不静默丢失」；</li>
 *   <li>{@link #displayOrder()}：底栏/宽屏侧栏从左到右的位置，与 id 解耦；</li>
 *   <li>文案与图标：窄屏/宽屏标签、contentDescription 全部由此派生，避免
 *       T-B3 的单行/48dp/选中态实现被改写。</li>
 * </ol>
 *
 * <p>音频/视频/漫画是「媒体」内部分类，不占一级导航；插件不得产生第五个 tab
 * （v4 §5.1 / 验收 NAV-04）。
 */
public enum NavDestination {
    /** id=0：旧 tab=0（书库）原样延续。 */
    SHELF(0, 0, "▤", "书库", "切换到书库"),
    /** id=1：旧 tab=1（发现）的内容/来源部分，承接来源、OPDS、漫画源、音乐源。 */
    MEDIA(1, 1, "◎", "媒体", "切换到媒体"),
    /** id=2：旧 tab=2（关于）延用到「我的」——这是旧索引兼容的关键锚点。 */
    MINE(2, 3, "⚙", "我的", "切换到我的"),
    /** id=3：v4 新的一级目的地，I3 建立外壳、P1-A 填真实内容。 */
    EXTENSIONS(3, 2, "✧", "扩展", "切换到扩展");

    private final int id;
    private final int displayOrder;
    private final String glyph;
    private final String label;
    private final String desc;

    NavDestination(int id, int displayOrder, String glyph, String label, String desc) {
        this.id = id;
        this.displayOrder = displayOrder;
        this.glyph = glyph;
        this.label = label;
        this.desc = desc;
    }

    /** MainActivity 内部 tab 值；旧 0/1/2 语义保持不变。 */
    public int id() {
        return id;
    }

    /** 底栏/侧栏从左到右的位置（0=书库、1=媒体、2=扩展、3=我的）。 */
    public int displayOrder() {
        return displayOrder;
    }

    public String glyph() {
        return glyph;
    }

    public String label() {
        return label;
    }

    /** 读屏描述：T-B3 既有 contentDescription 口径。 */
    public String desc() {
        return desc;
    }

    /** 宽屏标签：保留图标与双空格，与原 T-B3 宽屏文案形态一致。 */
    public String wideLabel() {
        return glyph + "  " + label;
    }

    /** 窄屏标签：去双空格，保证小屏单行不被挤压。 */
    public String narrowLabel() {
        return glyph + label;
    }

    /** id → 目的地；非法 id 回落书库，与既有 tab 默认值一致。 */
    public static NavDestination fromId(int id) {
        for (NavDestination d : values()) if (d.id == id) return d;
        return SHELF;
    }

    /** 显示位置 → 目的地；非法位置回落书库。 */
    public static NavDestination fromDisplayOrder(int order) {
        for (NavDestination d : values()) if (d.displayOrder == order) return d;
        return SHELF;
    }

    /** 一级目的地数量（恒为 4；插件/媒体分类不得增加）。 */
    public static int count() {
        return values().length;
    }
}
