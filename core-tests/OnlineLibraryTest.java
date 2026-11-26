import org.openleaf.reader.core.OnlineLibrary;
import java.util.*;

/** 在线书入库与章节缓存策略回归。 */
public final class OnlineLibraryTest {
    private static int passed;

    public static void main(String[] args) {
        stableId();
        ruleVersion();
        lruEviction();
        prefetch();
        wifiOnly();
        System.out.println("PASS: " + passed + " online library checks");
    }

    private static void stableId() {
        eq("src1::book-42", OnlineLibrary.remoteKey("src1", "book-42", "https://x.com/b/42"));
        // 无稳定 ID 用规范化链接（去 query/fragment/尾斜杠/小写 host）
        String key = OnlineLibrary.remoteKey("src1", "", "https://EXAMPLE.com/Book/?a=1#x");
        eq("src1::https://example.com/book", key);
        check(!OnlineLibrary.remoteKey("src1", "", "https://x.com/a").equals(
                OnlineLibrary.remoteKey("src2", "", "https://x.com/a")), "不同源隔离");
        passed++;
    }

    private static void ruleVersion() {
        check(OnlineLibrary.ruleChanged("1", "2"), "版本变化检出");
        check(!OnlineLibrary.ruleChanged("1", "1"), "相同版本");
        check(OnlineLibrary.ruleChanged(null, "1"), "空→有版本");
        passed++;
    }

    private static void lruEviction() {
        List<OnlineLibrary.CacheEntry> entries = Arrays.asList(
                new OnlineLibrary.CacheEntry("c1", OnlineLibrary.CacheState.CACHED, 100, false, 1),
                new OnlineLibrary.CacheEntry("c2", OnlineLibrary.CacheState.CACHED, 100, false, 2),
                new OnlineLibrary.CacheEntry("c3", OnlineLibrary.CacheState.CACHED, 100, true, 3)); // retained
        List<String> evicted = OnlineLibrary.evictCandidates(entries, 150);
        eq(Arrays.asList("c1", "c2"), evicted); // c3 retained 永不淘汰
        eq(Collections.emptyList(), OnlineLibrary.evictCandidates(entries, 500));
        passed++;
    }

    private static void prefetch() {
        List<String> all = Arrays.asList("c1", "c2", "c3", "c4", "c5");
        Map<String, OnlineLibrary.CacheEntry> existing = new HashMap<>();
        existing.put("c2", new OnlineLibrary.CacheEntry("c2", OnlineLibrary.CacheState.CACHED, 10, false, 1));
        List<String> plan = OnlineLibrary.prefetchPlan(all, "c1", 3, existing);
        eq(Arrays.asList("c1", "c3", "c4"), plan); // 跳过已缓存的 c2
        eq(Collections.emptyList(), OnlineLibrary.prefetchPlan(all, "unknown", 3, existing));
        passed++;
    }

    private static void wifiOnly() {
        // 完整真值表：wifiOnly × unmetered
        check(OnlineLibrary.mayDownload(false, false), "不限网络时计费网络也可下载");
        check(OnlineLibrary.mayDownload(false, true), "不限网络时WiFi可下载");
        check(!OnlineLibrary.mayDownload(true, false), "仅WiFi时蜂窝不下载");
        check(OnlineLibrary.mayDownload(true, true), "仅WiFi时WiFi可下载");
        passed++;
    }

    private static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
