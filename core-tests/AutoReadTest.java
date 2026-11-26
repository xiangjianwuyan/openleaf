import org.openleaf.reader.core.AutoRead;
import java.io.File;
import java.util.Objects;

/** 自动阅读积分与路径防护回归。 */
public final class AutoReadTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        clamp();
        stepIntegral();
        gateAndController();
        pathSafety();
        archiveKind();
        System.out.println("PASS: " + passed + " auto read checks");
    }

    private static void clamp() {
        eq(300, AutoRead.clampInterval(0, true));
        eq(5000, AutoRead.clampInterval(99999, true));
        eq(800, AutoRead.clampInterval(0, false));
        eq(10000, AutoRead.clampInterval(99999, false));
        passed++;
    }

    private static void stepIntegral() {
        // 视口 1000px、10% 距离、16ms 帧、1000ms 间隔 → 1.6px
        approx(1.6f, AutoRead.stepDistance(1000, 0.1f, 16, 1000), 0.01f);
        // 帧间隔超 48ms 被 clamp：100ms 帧按 48ms 算 → 4.8px
        approx(4.8f, AutoRead.stepDistance(1000, 0.1f, 100, 1000), 0.01f);
        eq(0f, AutoRead.stepDistance(1000, 0.1f, 16, 0));
        passed++;
    }

    private static void gateAndController() {
        check(AutoRead.canTick(false, false, false, false), "无干扰可 tick");
        check(!AutoRead.canTick(true, false, false, false), "菜单显示不 tick");
        check(!AutoRead.canTick(false, true, false, false), "拖拽不 tick");
        AutoRead.Controller c = new AutoRead.Controller();
        c.configure(true, 16, true, true);
        eq(300, c.intervalMs()); // clamp 到最小
        check(c.shouldTick(true), "启用可 tick");
        c.togglePause();
        check(!c.shouldTick(true), "暂停后不 tick");
        c.togglePause();
        check(c.shouldTick(true), "恢复 tick");
        passed++;
    }

    private static void pathSafety() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "breeze-test-root");
        root.mkdirs();
        check(AutoRead.isSafeExtractTarget(root, "chapter/001.jpg"), "普通路径安全");
        check(!AutoRead.isSafeExtractTarget(root, "../evil.jpg"), ".. 拒绝");
        check(!AutoRead.isSafeExtractTarget(root, "/abs.jpg"), "绝对路径拒绝");
        check(!AutoRead.isSafeExtractTarget(root, "C:\\evil.jpg"), "盘符拒绝");
        check(!AutoRead.isSafeExtractTarget(root, "a/../../evil.jpg"), "嵌套越界拒绝");
        passed++;
    }

    private static void archiveKind() {
        eq("zip", AutoRead.archiveKind("a.cbz"));
        eq("zip", AutoRead.archiveKind("a.zip"));
        eq("rar", AutoRead.archiveKind("a.cbr"));
        eq("7z", AutoRead.archiveKind("a.cb7"));
        eq("tar", AutoRead.archiveKind("a.cbt"));
        eq("", AutoRead.archiveKind("a.pdf"));
        passed++;
    }

    private static void approx(float expected, float actual, float tol) {
        if (Math.abs(expected - actual) > tol) throw new AssertionError("expected " + expected + " but got " + actual);
    }
    private static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
