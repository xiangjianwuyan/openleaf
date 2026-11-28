import org.openleaf.reader.core.NavDestination;
import org.openleaf.reader.core.NavRouter;

/**
 * OpenLeaf v4 I3 四导航迁移门禁：纯 JVM、无 JUnit、断言始终启用。
 *
 * <p>把架构 §4.2 / 规格 §2.3 的「旧入口 → 新目的地」台账钉成可执行断言：
 * 四目的地固定、旧 tab=2 语义不丢失、想法/记录/关于/插件逐条可解释跳转，
 * 且没有任何旧入口产生第五个 tab 或静默消失。runner 按文件名自动纳入。
 */
public final class NavRouterTest {
    private static int passed;

    public static void main(String[] args) {
        test("四目的地固定：id/显示序/文案/图标单一真源", NavRouterTest::fourDestinations);
        test("旧 tab 0/1/2 语义保留：tab=2 仍落在「关于」内容（现为我的）", NavRouterTest::legacyTabs);
        test("未知/越界旧 tab 可解释回落，不静默丢失", NavRouterTest::legacyTabFallback);
        test("旧入口台账：想法/记录/关于/应用插件逐条映射", NavRouterTest::legacyEntries);
        test("旧「发现」来源入口按任务分流到媒体，想法/记录按任务归到我的", NavRouterTest::legacyDiscoverEntries);
        test("每条路由都有可解释说明，且目标恒属于四个目的地", NavRouterTest::noOrphanEntry);
        System.out.println("NavRouterTest: " + passed + " checks passed");
    }

    private static void fourDestinations() {
        equal(4, NavDestination.count(), "一级目的地必须恒为 4");
        NavDestination[] ordered = NavRouter.destinations();
        equal(4, ordered.length, "显示序数组长度");
        equal("书库", ordered[0].label());
        equal("媒体", ordered[1].label());
        equal("扩展", ordered[2].label());
        equal("我的", ordered[3].label());
        equal("▤", ordered[0].glyph());
        equal("◎", ordered[1].glyph());
        equal("✧", ordered[2].glyph());
        equal("⚙", ordered[3].glyph());
        check(ordered[0].wideLabel().contains("书库") && ordered[0].narrowLabel().contains("书库"),
                "宽/窄标签均由单一真源派生");
        for (int i = 0; i < ordered.length; i++) {
            equal(i, ordered[i].displayOrder(), "显示序连续且唯一");
            check(!ordered[i].desc().isEmpty(), "contentDescription 不得为空");
        }
        // 内部 id 保持旧 0/1/2 + 新 3；显示序 2=扩展、3=我的，与 id 解耦。
        equal(0, NavDestination.SHELF.id());
        equal(1, NavDestination.MEDIA.id());
        equal(2, NavDestination.MINE.id());
        equal(3, NavDestination.EXTENSIONS.id());
        equal(NavDestination.SHELF, NavDestination.fromId(0));
        equal(NavDestination.MEDIA, NavDestination.fromId(1));
        equal(NavDestination.MINE, NavDestination.fromId(2));
        equal(NavDestination.EXTENSIONS, NavDestination.fromId(3));
        equal(NavDestination.SHELF, NavDestination.fromId(99), "非法 id 回落书库");
        equal(NavDestination.SHELF, NavRouter.destination(99));
    }

    private static void legacyTabs() {
        equal(NavDestination.SHELF, NavRouter.fromLegacyTab(0).destination);
        equal(NavDestination.MEDIA, NavRouter.fromLegacyTab(1).destination);
        // 关键：旧 tab=2 是「关于」，必须仍落在「关于」内容（现为「我的」），不得静默变成「扩展」。
        NavRouter.Route legacyAbout = NavRouter.fromLegacyTab(2);
        equal(NavDestination.MINE, legacyAbout.destination);
        check(legacyAbout.explanation.contains("关于"), "旧 tab=2 必须给出「关于」可解释说明");
        check(legacyAbout.targetActivity == null, "旧tab=2 由 MainActivity 内页承载");
        equal(NavDestination.MINE, NavRouter.fromLegacyTab(3).destination);
        equal(NavDestination.MINE, NavRouter.fromLegacyTab(4).destination);
        check(NavRouter.fromLegacyTab(4).targetActivity.contains("StatsActivity"),
                "旧 tab=4 保留记录外开目标");
    }

    private static void legacyTabFallback() {
        for (int tab : new int[]{-1, 5, 99}) {
            NavRouter.Route r = NavRouter.fromLegacyTab(tab);
            equal(NavDestination.SHELF, r.destination);
            check(r.explanation.contains(String.valueOf(tab)), "回落必须说明原索引");
        }
    }

    private static void legacyEntries() {
        // 想法/记录 → 我的「阅读与记录」，旧外开路径不失效。
        NavRouter.Route ideas = NavRouter.fromLegacyEntry("想法");
        equal(NavDestination.MINE, ideas.destination);
        equal("org.openleaf.reader.IdeasActivity", ideas.targetActivity);
        NavRouter.Route stats = NavRouter.fromLegacyEntry("记录");
        equal(NavDestination.MINE, stats.destination);
        equal("org.openleaf.reader.StatsActivity", stats.targetActivity);
        // 完整类名同样可解释。
        equal(NavDestination.MINE, NavRouter.fromLegacyEntry("org.openleaf.reader.StatsActivity").destination);
        // 关于 → 我的「数据与设置」。
        equal(NavDestination.MINE, NavRouter.fromLegacyEntry("关于").destination);
        check(NavRouter.fromLegacyEntry("关于").explanation.contains("数据与设置"),
                "关于入口说明必须指向数据与设置");
        // 应用插件 → 扩展一级目的地，且本轮不直接跳转 PluginActivity（无假市场/假插件）。
        NavRouter.Route plugin = NavRouter.fromLegacyEntry("应用插件");
        equal(NavDestination.EXTENSIONS, plugin.destination);
        equal(null, plugin.targetActivity, "I3 不直接跳转 PluginActivity");
        check(plugin.explanation.contains("真实边界"), "必须说明当前只呈现真实边界");
        equal(NavDestination.EXTENSIONS, NavRouter.fromLegacyEntry("PluginActivity").destination);
        equal(NavDestination.EXTENSIONS, NavRouter.fromLegacyEntry("openleaf.text-audit").destination);
        // 书库/导入 → 书库。
        equal(NavDestination.SHELF, NavRouter.fromLegacyEntry("书库").destination);
        equal(NavDestination.SHELF, NavRouter.fromLegacyEntry("导入").destination);
    }

    private static void legacyDiscoverEntries() {
        String[] sources = {"发现", "LegadoActivity", "CatalogActivity", "OPDS", "漫画源", "音乐源", "MediaSourceActivity"};
        for (String entry : sources) {
            NavRouter.Route r = NavRouter.fromLegacyEntry(entry);
            equal(NavDestination.MEDIA, r.destination, "旧发现来源入口 " + entry + " 应迁到媒体");
            check(r.explanation.contains("媒体"), "来源入口说明必须可解释到媒体");
        }
    }

    private static void noOrphanEntry() {
        String[] entries = {"书库", "发现", "想法", "记录", "关于", "应用插件",
                "openleaf.text-audit", "LegadoActivity", "CatalogActivity", "OPDS",
                "备份", "设置", "未知历史入口", null, ""};
        for (String entry : entries) {
            NavRouter.Route route = NavRouter.fromLegacyEntry(entry);
            check(route.destination != null, "旧入口 " + entry + " 不得无目的地");
            check(route.explanation != null && !route.explanation.isEmpty(),
                    "旧入口 " + entry + " 不得静默丢失");
            boolean inFour = false;
            for (NavDestination d : NavDestination.values()) if (d == route.destination) inFour = true;
            check(inFour, "旧入口 " + entry + " 不得产生第五个目的地");
        }
        NavDestination[] ordered = NavRouter.destinations();
        for (NavDestination d : NavDestination.values()) {
            boolean seen = false;
            for (NavDestination o : ordered) if (o == d) seen = true;
            check(seen, "每个目的地都必须出现在显示序中");
        }
    }

    private interface Case {
        void run();
    }

    private static void test(String name, Case body) {
        try {
            body.run();
            passed++;
        } catch (Throwable failure) {
            throw new IllegalStateException("FAIL " + name + ": " + failure, failure);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void equal(Object expected, Object actual) {
        equal(expected, actual, "值不相等");
    }

    private static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(message + "：expected=" + expected + " actual=" + actual);
        }
    }

    private static void equal(int expected, int actual) {
        if (expected != actual) throw new IllegalStateException("expected=" + expected + " actual=" + actual);
    }

    private static void equal(int expected, int actual, String message) {
        if (expected != actual) throw new IllegalStateException(message + "：expected=" + expected + " actual=" + actual);
    }
}
