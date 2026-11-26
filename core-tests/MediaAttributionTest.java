import org.openleaf.reader.core.MediaAttribution;
import org.openleaf.reader.core.ReadingLedger;

/**
 * 非书媒体归属标识回归。
 *
 * <p>这些 id 是备份恢复时不重映射的前提：同一媒体在同一台设备上反复打开、或跨设备恢复，
 * 都必须算出同一个 id；漫画与音频即使键相同也不能撞到同一个归属。
 */
public final class MediaAttributionTest {
    private static int passed;

    public static void main(String[] args) {
        deterministic();
        kindSeparation();
        keySeparation();
        format();
        invalidInputs();
        System.out.println("PASS: " + passed + " media attribution checks");
    }

    private static void deterministic() {
        String a = MediaAttribution.id(ReadingLedger.Kind.COMIC, "comic-key-1");
        String b = MediaAttribution.id(ReadingLedger.Kind.COMIC, "comic-key-1");
        require(a != null && a.equals(b), "同一 kind+key 必须得到同一 id");
        require(MediaAttribution.fileKey("content://a/book.cbz")
                .equals(MediaAttribution.fileKey("content://a/book.cbz")), "文件键必须稳定");
        passed++;
    }

    private static void kindSeparation() {
        String comic = MediaAttribution.id(ReadingLedger.Kind.COMIC, "same-key");
        String audio = MediaAttribution.id(ReadingLedger.Kind.AUDIO, "same-key");
        require(comic != null && audio != null && !comic.equals(audio),
                "同一键的漫画与音频必须是不同归属");
        passed++;
    }

    private static void keySeparation() {
        String a = MediaAttribution.id(ReadingLedger.Kind.AUDIO, "track-1");
        String b = MediaAttribution.id(ReadingLedger.Kind.AUDIO, "track-2");
        require(a != null && b != null && !a.equals(b), "不同媒体键必须是不同归属");
        passed++;
    }

    private static void format() {
        String id = MediaAttribution.id(ReadingLedger.Kind.COMIC, "key");
        require(id.startsWith("m_") && id.length() == 34, "id 应为 m_ + 32 位十六进制，实际 " + id);
        for (int i = 2; i < id.length(); i++) {
            char c = id.charAt(i);
            require((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'),
                    "id 必须只用小写十六进制字符");
        }
        String key = MediaAttribution.fileKey("path");
        require(key.length() == 16, "文件键应为 16 位十六进制，实际 " + key);
        passed++;
    }

    private static void invalidInputs() {
        require(MediaAttribution.id(null, "key") == null, "kind 为空应返回 null");
        require(MediaAttribution.id(ReadingLedger.Kind.COMIC, null) == null, "键为空应返回 null");
        require(MediaAttribution.id(ReadingLedger.Kind.COMIC, "   ") == null, "全空白键应返回 null");
        require(MediaAttribution.fileKey(null) == null, "空路径应返回 null");
        require(MediaAttribution.fileKey("  ") == null, "全空白路径应返回 null");
        require(MediaAttribution.id(ReadingLedger.Kind.COMIC, " key ")
                .equals(MediaAttribution.id(ReadingLedger.Kind.COMIC, "key")), "键的前后空白应被裁剪");
        passed++;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
