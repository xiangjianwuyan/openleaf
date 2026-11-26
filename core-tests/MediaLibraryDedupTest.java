import org.openleaf.reader.core.MediaLibraryDedup;
import java.util.*;

/** 阶段 3-B Y-3 逻辑去重 core 回归。 */
public final class MediaLibraryDedupTest {
    private static int passed;

    public static void main(String[] args) {
        noConflictOnMatchingRows();
        detectTitleAndKindConflicts();
        legacyRowsWithoutMediaAreNotConflicts();
        kindDerivation();
        System.out.println("MediaLibraryDedupTest: " + passed + " checks passed");
    }

    private static void noConflictOnMatchingRows() {
        List<MediaLibraryDedup.Pair> pairs = Arrays.asList(
                new MediaLibraryDedup.Pair("b1", "书一", "书一", "txt", "book"),
                new MediaLibraryDedup.Pair("b2", "漫一", "漫一", "cbz", "comic"));
        eq(0, MediaLibraryDedup.conflicts(pairs).size());
        eq(0, MediaLibraryDedup.conflicts(null).size());
        passed++;
    }

    private static void detectTitleAndKindConflicts() {
        List<MediaLibraryDedup.Pair> pairs = Arrays.asList(
                new MediaLibraryDedup.Pair("b1", "旧标题", "新标题", "txt", "book"),
                new MediaLibraryDedup.Pair("b2", "漫一", "漫一", "cbz", "book"));
        List<MediaLibraryDedup.Conflict> c = MediaLibraryDedup.conflicts(pairs);
        eq(2, c.size());
        eq("title", c.get(0).field);
        eq("新标题", c.get(0).authoritativeValue);
        eq("kind", c.get(1).field);
        passed++;
    }

    private static void legacyRowsWithoutMediaAreNotConflicts() {
        // mediaTitle 空 = 老库未双写，Y-2 回退 books.title，不算冲突
        List<MediaLibraryDedup.Pair> pairs = Collections.singletonList(
                new MediaLibraryDedup.Pair("legacy", "老书", "", "txt", ""));
        eq(0, MediaLibraryDedup.conflicts(pairs).size());
        passed++;
    }

    private static void kindDerivation() {
        eq("comic", MediaLibraryDedup.kindFromFormat("cbz", null));
        eq("comic", MediaLibraryDedup.kindFromFormat("RAR", null));
        eq("comic", MediaLibraryDedup.kindFromFormat("txt", "comic"));
        eq("book", MediaLibraryDedup.kindFromFormat("epub", null));
        eq("book", MediaLibraryDedup.kindFromFormat(null, null));
        passed++;
    }

    private static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("期望 " + a + " 实际 " + b); }
}
