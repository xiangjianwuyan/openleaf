import org.openleaf.reader.core.BackupRestoreGuard;
import java.util.*;

/** 旧备份恢复悬挂 FK 过滤 core 回归。 */
public final class BackupRestoreGuardTest {
    private static int passed;

    public static void main(String[] args) {
        realBackupShape();
        idRemap();
        nullSafety();
        System.out.println("BackupRestoreGuardTest: " + passed + " checks passed");
    }

    private static void realBackupShape() {
        // 复刻 ol037 backup-v6.zip：11 books、10 reading_time，其中 2 个悬挂
        List<String> bookIds = Arrays.asList("b1","b2","b3","b4","b5","b6","b7","b8","b9","b10","welcome");
        List<String> stats = Arrays.asList("b1","b2","b3","b4","b5","b6","b7","b8","b9","b10",
                "8e681af3-dangling", "b424d176-dangling");
        BackupRestoreGuard.Result r = BackupRestoreGuard.filterStats(stats, null,
                BackupRestoreGuard.bookIds(bookIds));
        eq(10, r.kept.size());
        eq(2, r.skipped.size());
        yes(r.skipped.contains("8e681af3-dangling"));
        passed++;
    }

    private static void idRemap() {
        Map<String,String> map = new HashMap<>();
        map.put("old1","new1"); map.put("old2","new2");
        Set<String> targets = BackupRestoreGuard.bookIds(Arrays.asList("new1","new3"));
        BackupRestoreGuard.Result r = BackupRestoreGuard.filterStats(
                Arrays.asList("old1","old2","old3"), map, targets);
        eq(Collections.singletonList("old1"), r.kept);
        eq(Arrays.asList("old2","old3"), r.skipped);
        passed++;
    }

    private static void nullSafety() {
        eq(0, BackupRestoreGuard.filterStats(null, null, null).kept.size());
        BackupRestoreGuard.Result r = BackupRestoreGuard.filterStats(
                Arrays.asList("a", null, "b"), null, BackupRestoreGuard.bookIds(Arrays.asList("a","b")));
        eq(2, r.kept.size());
        eq(1, r.skipped.size());
        eq(0, BackupRestoreGuard.bookIds(null).size());
        passed++;
    }

    private static void eq(Object a, Object b) { if (!Objects.equals(a,b)) throw new AssertionError("期望 "+a+" 实际 "+b); }
    private static void yes(boolean b) { if (!b) throw new AssertionError("断言失败"); }
}
