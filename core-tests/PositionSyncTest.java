import org.openleaf.reader.core.PositionSync;

/**
 * 外部位置修订号回归。
 *
 * <p>阅读页靠这个键区分「听书写的新位置」与「自己保存的位置」。键冲突会把阅读页自己的
 * 保存当成外部变更、每次回前台都强行跳转；修订号不变化则会漏掉真正的外部写入。
 */
public final class PositionSyncTest {
    private static int passed;

    public static void main(String[] args) {
        stableKey();
        distinctBooks();
        freshRevision();
        System.out.println("PASS: " + passed + " position sync checks");
    }

    private static void stableKey() {
        String a = PositionSync.revisionKey("book-1");
        String b = PositionSync.revisionKey("book-1");
        require(a.equals(b), "同一本书的修订号键必须稳定");
        require(a.startsWith("external_position_rev_"), "修订号键必须有独立前缀，不能撞位置键");
        passed++;
    }

    private static void distinctBooks() {
        require(!PositionSync.revisionKey("book-1").equals(PositionSync.revisionKey("book-2")),
                "不同书的修订号键必须隔离");
        require(!PositionSync.revisionKey("book-1").equals(PositionSync.revisionKey(null)),
                "空书 id 不能撞到真实书");
        passed++;
    }

    /** 每次外部写入都必须产生新值，否则阅读页会漏掉后续变更。 */
    private static void freshRevision() {
        String a = PositionSync.newRevision();
        String b = PositionSync.newRevision();
        require(a != null && !a.isEmpty() && b != null && !b.isEmpty(), "修订号不能为空");
        require(!a.equals(b), "连续两次外部写入必须得到不同修订号");
        passed++;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
