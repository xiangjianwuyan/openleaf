import org.openleaf.reader.core.ChapterDiff;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 在线书追更判定：按 URL 集合而不是规则版本、数量。 */
public final class ChapterDiffTest {
    private static int passed;

    public static void main(String[] args) {
        detectsNewChapters();
        ignoresReordering();
        deduplicatesRemote();
        emptyInputs();
        System.out.println("PASS: " + passed + " chapter diff checks");
    }

    private static void detectsNewChapters() {
        List<String> local = Arrays.asList("u1", "u2", "u3");
        List<String> remote = Arrays.asList("u1", "u2", "u3", "u4");
        eq(1, ChapterDiff.addedCount(local, remote), "远端多一章");
        require("u4".equals(ChapterDiff.added(local, remote).get(0)), "应返回新章 URL");
        passed++;
    }

    private static void ignoresReordering() {
        eq(0, ChapterDiff.addedCount(Arrays.asList("u1", "u2"), Arrays.asList("u2", "u1")),
                "只是顺序变化不算追更");
        eq(0, ChapterDiff.addedCount(Arrays.asList("u1", "u2"), Collections.singletonList("u1")),
                "远端变少也不是新增");
        passed++;
    }

    private static void deduplicatesRemote() {
        eq(1, ChapterDiff.addedCount(Collections.singletonList("u1"),
                Arrays.asList("u1", "u2", "u2", "u2")), "重复项只能算一章");
        passed++;
    }

    private static void emptyInputs() {
        eq(2, ChapterDiff.addedCount(Collections.emptyList(), Arrays.asList("u1", "u2")),
                "本地为空时全部算新增");
        eq(0, ChapterDiff.addedCount(Arrays.asList("u1", "u2"), null), "远端为空不算新增");
        eq(0, ChapterDiff.addedCount(null, null), "两边都为空");
        passed++;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void eq(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
    }
}
