package org.openleaf.reader.core;

/**
 * 四导航迁移的纯函数路由表（纯 JVM，零 Android import）。
 *
 * <p>把架构 §4.2 / 规格 §2.3 / 设计稿 §10 的「旧入口 → 新目的地」台账钉成可单测的
 * 唯一真源。每条 {@link Route} 都带 {@code explanation}：旧入口必须给出可解释的
 * 跳转说明，绝不静默丢失。
 *
 * <p>边界：本轮 I3 只建立「扩展」一级目的地并以真实边界文案承载；
 * 旧「关于 → 应用插件」不直接跳转 PluginActivity（P1-A 才填真实内容），
 * 因此该路由的 {@code targetActivity} 为 null。
 */
public final class NavRouter {

    /** 路由结果：目的地 + 可选的真实目标组件 + 可解释说明。 */
    public static final class Route {
        public final NavDestination destination;
        /** 外部 Activity 的完整类名；null 表示由 MainActivity 内页承载。 */
        public final String targetActivity;
        /** 给用户/自动化钩子的可解释迁移说明，永不为空。 */
        public final String explanation;

        private Route(NavDestination destination, String targetActivity, String explanation) {
            this.destination = destination;
            this.targetActivity = targetActivity;
            this.explanation = explanation;
        }

        @Override public String toString() {
            return destination.label() + " ← " + explanation;
        }
    }

    private NavRouter() {
        // 纯函数工具类，禁止实例化。
    }

    // ---- 旧入口关键常量：与架构 §4.2 台账逐条对应 ----
    public static final String LEGACY_TAB = "openleaf.extra.legacyTab";
    public static final String LEGACY_ENTRY = "openleaf.extra.legacyEntry";

    private static String activity(String simpleName) {
        return "org.openleaf.reader." + simpleName;
    }

    private static Route route(NavDestination dest, String target, String explanation) {
        if (explanation == null || explanation.isEmpty()) {
            throw new IllegalArgumentException("路由说明不得为空：旧入口不可静默丢失");
        }
        return new Route(dest, target, explanation);
    }

    /**
     * 旧一级 tab 索引 → 新目的地。
     *
     * <p>旧索引只有 0=书库 / 1=发现 / 2=关于；新内部 id 刻意沿用这三值，
     * 其中 tab=2 继续落在「关于」内容（现为「我的」），因此旧深链/历史状态
     * 不会因一级导航 5→4 而指向错误页面。3/4 是历史上被外部钩子使用过的
     * 想法/记录索引，统一给可解释的「我的」兼容跳转。
     */
    public static Route fromLegacyTab(int legacyTab) {
        switch (legacyTab) {
            case 0:
                return route(NavDestination.SHELF, null, "旧「书库 tab=0」直达书库");
            case 1:
                return route(NavDestination.MEDIA, null,
                        "旧「发现 tab=1」的来源入口已迁到「媒体」；插件发现请到「扩展」");
            case 2:
                return route(NavDestination.MINE, null,
                        "旧「关于 tab=2」已归入「我的 → 数据与设置」");
            case 3:
                return route(NavDestination.MINE, activity("IdeasActivity"),
                        "旧「想法 tab=3」已归入「我的 → 阅读与记录」");
            case 4:
                return route(NavDestination.MINE, activity("StatsActivity"),
                        "旧「记录 tab=4」已归入「我的 → 阅读与记录」");
            default:
                return route(NavDestination.SHELF, null,
                        "未知旧 tab=" + legacyTab + "，已安全回落到书库");
        }
    }

    /**
     * 旧入口名/组件名 → 新目的地。名称匹配大小写不敏感，并兼容完整类名与
     * 简单类名两种写法（外部深链/自动化钩子可能使用任意一种）。
     */
    public static Route fromLegacyEntry(String legacyEntry) {
        if (legacyEntry == null || legacyEntry.trim().isEmpty()) {
            return route(NavDestination.MINE, null, "未知旧入口已归入「我的」");
        }
        String key = legacyEntry.trim();
        String lower = key.toLowerCase(java.util.Locale.ROOT);

        if (contains(lower, "书库") || contains(lower, "shelf") || contains(lower, "import")
                || contains(lower, "导入") || contains(lower, "folderimport")) {
            return route(NavDestination.SHELF, null, "旧「" + key + "」直达书库导入");
        }
        if (contains(lower, "插件") || contains(lower, "plugin") || contains(lower, "text-audit")) {
            return route(NavDestination.EXTENSIONS, null,
                    "旧「" + key + "」指向「扩展」一级目的地；本轮只呈现真实边界，不直接跳转插件管理");
        }
        if (contains(lower, "发现") || contains(lower, "legado") || contains(lower, "catalog")
                || contains(lower, "opds") || contains(lower, "mediasource")
                || contains(lower, "漫画源") || contains(lower, "音乐源") || contains(lower, "来源")) {
            return route(NavDestination.MEDIA, null,
                    "旧「" + key + "」的内容/网络来源入口已迁到「媒体」");
        }
        if (contains(lower, "想法") || contains(lower, "idea")) {
            return route(NavDestination.MINE, activity("IdeasActivity"),
                    "旧「" + key + "」已归入「我的 → 阅读与记录」");
        }
        if (contains(lower, "记录") || contains(lower, "stats")) {
            return route(NavDestination.MINE, activity("StatsActivity"),
                    "旧「" + key + "」已归入「我的 → 阅读与记录」");
        }
        if (contains(lower, "关于") || contains(lower, "about") || contains(lower, "backup")
                || contains(lower, "设置") || contains(lower, "settings")) {
            return route(NavDestination.MINE, null,
                    "旧「" + key + "」已归入「我的 → 数据与设置」");
        }
        return route(NavDestination.MINE, null, "未知旧入口「" + key + "」已归入「我的」");
    }

    /** 新内部 id → 目的地；非法 id 回落书库。 */
    public static NavDestination destination(int id) {
        return NavDestination.fromId(id);
    }

    /** 按显示顺序返回四个目的地，供底栏与宽屏侧栏共用单一真源。 */
    public static NavDestination[] destinations() {
        NavDestination[] ordered = new NavDestination[NavDestination.count()];
        for (NavDestination d : NavDestination.values()) ordered[d.displayOrder()] = d;
        return ordered;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack.contains(needle.toLowerCase(java.util.Locale.ROOT));
    }
}
