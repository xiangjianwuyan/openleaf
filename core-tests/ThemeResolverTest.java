import org.openleaf.reader.core.ThemePalette;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.47.0 P1 回归：AMOLED 主题冷启动静默降级为夜间。
 *
 * ThemeResolver 位于 org.openleaf.reader 包且为包可见 final class，且唯一 Android 依赖
 * android.content.res.Configuration 在纯 JVM 侧不可编译，因此无法直接实例化调用 resolve()。
 * 本测试采用两条互补路径：
 *   1) 间接验证：resolve(follow=false, ...) 的判定契约现由 ThemePalette.clamp 单一真源承载，
 *      用 ThemePalette.clamp 复现断言「theme=3 不被压成 2」及越界行为；
 *   2) 直接回归：读取 ThemeResolver.java 源码文本做契约断言，确保硬编码上界 "> 2 ? 2" 已删除、
 *      follow=false 已改调 ThemePalette.clamp，且 follow=true 分支与 SP/KEY_FOLLOW 常量未动。
 * 从工程根运行，源码路径用相对路径。纯 JVM、无 JUnit、断言始终启用，不依赖 -ea。
 */
public final class ThemeResolverTest {
    private static final String RESOLVER_SRC =
            "app/src/main/java/org/openleaf/reader/ThemeResolver.java";

    private static int passed;

    public static void main(String[] args) {
        test("resolve 契约：theme=3 经 clamp 后仍为 3，不被压成 2",
                ThemeResolverTest::clampKeepsAmoled);
        test("resolve 契约：越界负数→0、大数→3", ThemeResolverTest::clampBounds);
        test("源码回归：删除硬编码 > 2 ? 2，resolve 复用 ThemePalette.clamp 单一真源",
                ThemeResolverTest::noHardcodedUpperBound);
        test("源码回归：follow=true 分支保持系统夜=2、其余=0 不变",
                ThemeResolverTest::followBranchUnchanged);
        test("源码回归：类可见性与 SP / KEY_FOLLOW 常量保持不变",
                ThemeResolverTest::constantsIntact);
        System.out.println("PASS: " + passed + " theme resolver checks");
    }

    private static void clampKeepsAmoled() {
        // ThemeResolver.resolve(follow=false, ..., manualTheme) 的判定契约由 ThemePalette.clamp 承载。
        equal(3, ThemePalette.clamp(3), "AMOLED theme=3 必须保留，不得被压回夜间 2");
        equal(2, ThemePalette.clamp(2));
        equal(1, ThemePalette.clamp(1));
        equal(0, ThemePalette.clamp(0));
    }

    private static void clampBounds() {
        equal(0, ThemePalette.clamp(-1));
        equal(0, ThemePalette.clamp(-99));
        equal(3, ThemePalette.clamp(4));
        equal(3, ThemePalette.clamp(100));
        equal(ThemePalette.PRESET_COUNT - 1, ThemePalette.clamp(ThemePalette.PRESET_COUNT));
    }

    private static void noHardcodedUpperBound() {
        String src = resolverSource();
        check(!src.contains("> 2 ? 2"),
                "ThemeResolver 仍含硬编码 > 2 ? 2，AMOLED 冷启动会被压回夜间");
        check(src.contains("ThemePalette.clamp(manualTheme)"),
                "resolve(follow=false) 必须改调 ThemePalette.clamp 单一真源");
        check(!src.contains("private static int clamp("),
                "不应保留私有 clamp，避免与 ThemePalette 形成双口径");
    }

    private static void followBranchUnchanged() {
        String src = resolverSource();
        check(src.contains("UI_MODE_NIGHT_YES ? 2 : 0"),
                "follow=true 分支必须保持系统夜=2、其余=0 的既有设计");
    }

    private static void constantsIntact() {
        String src = resolverSource();
        check(src.contains("final class ThemeResolver"), "ThemeResolver 应保持包可见 final class");
        check(src.contains("static final String SP = \"reader\""), "SP 常量不应被改动");
        check(src.contains("static final String KEY_FOLLOW = \"themeFollowSystem\""),
                "KEY_FOLLOW 常量不应被改动");
    }

    private static String resolverSource() {
        Path path = Paths.get(RESOLVER_SRC);
        check(Files.isRegularFile(path), "找不到 " + RESOLVER_SRC + "，请从工程根运行本测试");
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + RESOLVER_SRC + " 失败: " + e, e);
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

    private static void equal(int expected, int actual) {
        equal(expected, actual, "数值不相等");
    }

    private static void equal(int expected, int actual, String message) {
        if (expected != actual) {
            throw new IllegalStateException(message + "：expected=" + expected + " actual=" + actual);
        }
    }
}
