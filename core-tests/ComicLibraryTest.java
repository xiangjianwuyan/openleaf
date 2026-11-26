import org.openleaf.reader.core.ComicLibrary;
import org.openleaf.reader.core.ComicFlow;
import java.util.*;

/** 阶段 2-B 漫画入库纯逻辑回归；无 JUnit、断言始终启用。 */
public final class ComicLibraryTest {
    private static int passed;

    public static void main(String[] args) {
        classification();
        titleInference();
        coverSelection();
        legacyMapping();
        System.out.println("ComicLibraryTest: " + passed + " checks passed");
    }

    private static void classification() {
        for (String n : new String[]{"a.cbz", "a.zip", "a.CBZ", "a.cbr", "a.rar", "A.RAR"})
            yes(ComicLibrary.isComic(n), "漫画格式：" + n);
        for (String n : new String[]{"a.cb7", "a.7z"})
            eq(ComicLibrary.Kind.DEFERRED, ComicLibrary.classify(n));
        for (String n : new String[]{"a.txt", "a.epub", "a.pdf", "a", null, ""})
            eq(ComicLibrary.Kind.NOT_COMIC, ComicLibrary.classify(n));
        passed++;
    }

    private static void titleInference() {
        eq("海贼王", ComicLibrary.titleFrom("海贼王.cbz"));
        eq("海贼王", ComicLibrary.titleFrom("001_海贼王.cbz"));
        eq("海贼王", ComicLibrary.titleFrom("[01] 海贼王.zip"));
        eq("未命名漫画", ComicLibrary.titleFrom(""));
        eq("未命名漫画", ComicLibrary.titleFrom(null));
        String longName = new String(new char[200]).replace('\0', 'x') + ".cbz";
        eq(120, ComicLibrary.titleFrom(longName).length());
        passed++;
    }

    private static void coverSelection() {
        List<String> names = Arrays.asList("10.jpg", "2.png", "01.webp", "note.txt", "../evil.png");
        eq("01.webp", ComicLibrary.coverEntry(names));
        eq("b.gif", ComicLibrary.coverEntry(Arrays.asList("a.txt", "b.gif")));
        yes(ComicLibrary.coverEntry(Arrays.asList("a.txt", "b.md")) == null, "无图片返回 null");
        yes(ComicLibrary.coverEntry(null) == null, "空输入返回 null");
        yes(ComicLibrary.coverEntry(Collections.emptyList()) == null, "空列表返回 null");
        passed++;
    }

    private static void legacyMapping() {
        String k1 = ComicLibrary.legacyKeyToBookMapping("abc");
        String k2 = ComicLibrary.legacyKeyToBookMapping("abc");
        String k3 = ComicLibrary.legacyKeyToBookMapping("abd");
        eq(k1, k2);
        yes(!k1.equals(k3), "不同指纹产生不同键");
        yes(k1.startsWith("comic_bookid_"), "带可读前缀");
        eq(64 + "comic_bookid_".length(), k1.length());
        passed++;
    }

    private static void yes(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
    private static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("期望 " + a + " 实际 " + b); }
}
