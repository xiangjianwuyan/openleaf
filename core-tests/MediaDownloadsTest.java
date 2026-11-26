import org.openleaf.reader.core.MediaDownloads;

import java.io.File;
import java.util.Objects;

/** 1B 视频落盘索引：纯 JVM、无 JUnit、断言始终启用。 */
public final class MediaDownloadsTest {
    private static int passed;

    public static void main(String[] args) {
        test("稳定键：只由 sourceId|itemId 决定，与 stream URL 无关", MediaDownloadsTest::stableKey);
        test("键为 64 位小写十六进制", MediaDownloadsTest::keyShape);
        test("索引编解码无损往返（中文/空格/竖线/长标题）", MediaDownloadsTest::roundTrip);
        test("损坏索引自愈：非法输入一律返回 null", MediaDownloadsTest::malformed);
        test("相对路径防穿越：拒绝绝对/盘符/反斜杠/.. /空段", MediaDownloadsTest::relativeGuard);
        test("canonical 目录边界：只允许 base 及其子路径", MediaDownloadsTest::directoryBoundary);
        System.out.println("MediaDownloadsTest: " + passed + " checks passed");
    }

    private static void stableKey() {
        String a = MediaDownloads.key("src-1", "item-9");
        equal(a, MediaDownloads.key("src-1", "item-9"));
        yes(!a.equals(MediaDownloads.key("src-2", "item-9")));
        yes(!a.equals(MediaDownloads.key("src-1", "item-8")));
        // 即使两次播放拿到完全不同的签名 URL，键也不受影响（key 方法根本不接收 URL）。
        equal(a, MediaDownloads.key("src-1", "item-9"));
        equal(MediaDownloads.key("", ""), MediaDownloads.key(null, null));
    }

    private static void keyShape() {
        String key = MediaDownloads.key("源", "项目");
        equal(64, key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            yes((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
        }
    }

    private static void roundTrip() {
        String[] titles = {"Video", "视频 | 带竖线", "带空格 的 标题", ""};
        for (String title : titles) {
            MediaDownloads.Entry entry = new MediaDownloads.Entry("downloads/uuid-name.mp4", title, 123456789L);
            MediaDownloads.Entry back = MediaDownloads.decode(MediaDownloads.encode(entry));
            yes(back != null);
            equal(entry.relPath, back.relPath);
            equal(entry.title, back.title);
            equal(entry.bytes, back.bytes);
        }
    }

    private static void malformed() {
        yes(MediaDownloads.decode(null) == null);
        yes(MediaDownloads.decode("") == null);
        yes(MediaDownloads.decode("garbage") == null);
        yes(MediaDownloads.decode("616263|646566") == null);
        yes(MediaDownloads.decode("616263|646566|not-a-number") == null);
        yes(MediaDownloads.decode("616263|646566|-1") == null);
        // 解码出的相对路径含 ../ 时同样拒绝
        String evil = MediaDownloads.encode(new MediaDownloads.Entry("../secret.mp4", "x", 1));
        yes(MediaDownloads.decode(evil) == null);
    }

    private static void relativeGuard() {
        yes(MediaDownloads.safeRelative("downloads/a.mp4"));
        yes(MediaDownloads.safeRelative("a"));
        String[] bad = {"", "/downloads/a.mp4", "C:/x.mp4", "..\\x.mp4", "a\\b.mp4",
                "a/../b.mp4", "a/./b.mp4", "a//b.mp4", "downloads/", "./a.mp4"};
        for (String value : bad) yes(!MediaDownloads.safeRelative(value));
    }

    private static void directoryBoundary() {
        File base = new File(System.getProperty("java.io.tmpdir"), "ol-media-dl-" + System.nanoTime());
        if (!base.isDirectory() && !base.mkdirs()) throw new AssertionError("无法创建临时目录");
        File child = new File(new File(base, "downloads"), "uuid.mp4");
        yes(MediaDownloads.inside(base, child));
        yes(MediaDownloads.inside(base, base));
        yes(!MediaDownloads.inside(base, new File(base.getParentFile(), "outside.mp4")));
        yes(!MediaDownloads.inside(null, child));
        yes(!MediaDownloads.inside(base, null));
        if (!child.delete() && child.exists()) throw new AssertionError("无法清理临时文件");
        if (!new File(base, "downloads").delete() && new File(base, "downloads").exists())
            throw new AssertionError("无法清理临时目录");
        if (!base.delete() && base.exists()) throw new AssertionError("无法清理临时根目录");
    }

    private interface Check { void run(); }

    private static void test(String name, Check check) {
        check.run();
        passed++;
    }

    private static void yes(boolean condition) {
        if (!condition) throw new AssertionError("断言失败");
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError("期望 " + expected + "，实际 " + actual);
    }
}
