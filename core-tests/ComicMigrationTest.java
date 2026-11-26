import org.openleaf.reader.core.ComicMigration;

/** 阶段 2-B DB v16→v17 迁移契约；纯 JVM、无 JUnit、断言始终启用。 */
public final class ComicMigrationTest {
    private static int passed;

    public static void main(String[] args) {
        versionAndDdl();
        legacyRowSemantics();
        mediaTypeValues();
        System.out.println("ComicMigrationTest: " + passed + " checks passed");
    }

    private static void versionAndDdl() {
        eq(17, ComicMigration.VERSION);
        eq(4, ComicMigration.DDL.length);
        yes(ComicMigration.DDL[0].contains("media_type") && ComicMigration.DDL[0].contains("DEFAULT 'book'"),
                "media_type 必须 NOT NULL DEFAULT 'book'");
        yes(ComicMigration.DDL[1].contains("page_count") && ComicMigration.DDL[1].contains("DEFAULT NULL"),
                "page_count 必须可空");
        yes(ComicMigration.DDL[2].contains("cover_entry") && ComicMigration.DDL[2].contains("DEFAULT NULL"),
                "cover_entry 必须可空");
        yes(ComicMigration.DDL[3].contains("CREATE INDEX") && ComicMigration.DDL[3].contains("media_type"),
                "必须有 media_type 索引");
        passed++;
    }

    private static void legacyRowSemantics() {
        yes(ComicMigration.keepsLegacyRowAsBook("book", null, null), "老行=文字书语义");
        yes(!ComicMigration.keepsLegacyRowAsBook("comic", null, null), "漫画行不算老行");
        yes(!ComicMigration.keepsLegacyRowAsBook("book", Integer.valueOf(42), null), "有页数不算老行");
        yes(!ComicMigration.keepsLegacyRowAsBook("book", null, "cover.jpg"), "有封面不算老行");
        passed++;
    }

    private static void mediaTypeValues() {
        yes(ComicMigration.validMediaType("book"));
        yes(ComicMigration.validMediaType("comic"));
        String[] bad = {null, "", "BOOK", "Comic", "txt", "comic ", " comic"};
        for (String v : bad) yes(!ComicMigration.validMediaType(v), "非法媒体类型：" + v);
        eq("book", ComicMigration.MEDIA_BOOK);
        eq("comic", ComicMigration.MEDIA_COMIC);
        passed++;
    }

    private static void yes(boolean b) { if (!b) throw new AssertionError("断言失败"); }
    private static void yes(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
    private static void eq(Object a, Object b) { if (!a.equals(b)) throw new AssertionError("期望 " + a + " 实际 " + b); }
}
