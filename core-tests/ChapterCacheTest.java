import org.openleaf.reader.core.ChapterCache;
import java.util.*;

/** 阶段 2-B 缓存生命周期策略 core 回归。 */
public final class ChapterCacheTest {
    private static int passed;

    public static void main(String[] args) {
        keepWindow();
        eviction();
        dirNames();
        System.out.println("ChapterCacheTest: " + passed + " checks passed");
    }

    private static void keepWindow() {
        eq(new HashSet<>(Arrays.asList(0, 1)), ChapterCache.keepIndices(0, 5));
        eq(new HashSet<>(Arrays.asList(0, 1, 2)), ChapterCache.keepIndices(1, 5));
        eq(new HashSet<>(Arrays.asList(2, 3, 4)), ChapterCache.keepIndices(3, 5));
        eq(new HashSet<>(Arrays.asList(3, 4)), ChapterCache.keepIndices(4, 5));
        eq(Collections.emptySet(), ChapterCache.keepIndices(-1, 5));
        eq(Collections.emptySet(), ChapterCache.keepIndices(0, 0));
        eq(new HashSet<>(Collections.singletonList(0)), ChapterCache.keepIndices(0, 1));
        passed++;
    }

    private static void eviction() {
        Set<String> existing = new HashSet<>(Arrays.asList(
                "comic-ch01", "comic-ch02", "comic-ch03", "comic-ch04", "unrelated", "comic-"));
        Set<String> keep = new HashSet<>(Arrays.asList("ch02", "ch03"));
        List<String> evict = ChapterCache.evictable(existing, keep);
        eq(Arrays.asList("comic-", "comic-ch01", "comic-ch04"), evict); // 字典序确定
        eq(Collections.emptyList(), ChapterCache.evictable(null, keep));
        eq(Arrays.asList("comic-ch01"), ChapterCache.evictable(Collections.singleton("comic-ch01"), null));
        passed++;
    }

    private static void dirNames() {
        eq("comic-ch01", ChapterCache.dirName("ch01"));
        eq("comic-ch01_cbz", ChapterCache.dirName("ch01.cbz"));
        eq("comic-chapter", ChapterCache.dirName(null));
        passed++;
    }

    private static void eq(Object a, Object b) {
        if (!Objects.equals(a, b)) throw new AssertionError("期望 " + a + " 实际 " + b);
    }
}
