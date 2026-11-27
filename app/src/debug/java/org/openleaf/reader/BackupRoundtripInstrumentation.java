package org.openleaf.reader;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openleaf.reader.core.BackupManifest;
import org.openleaf.reader.core.ContentContracts;
import org.openleaf.reader.core.MediaAttribution;
import org.openleaf.reader.core.ReadingAnchor;
import org.openleaf.reader.core.ReadingLedger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 备份恢复端到端回归：此前 51 项插桩一项都没碰过 LibraryBackup / LibraryRestore，
 * 而界面明确承诺 SHA-256 校验、不部分写入、ID 重映射与统计迁移。这里用两个隔离的
 * FixtureContext 做真实导出→恢复往返，不靠改断言放过缺陷。
 */
public final class BackupRoundtripInstrumentation extends Instrumentation {
    private static final String MEDIA_KEY = "backup-roundtrip-comic";
    private static final long SEGMENT = 1_700_000_123_000L;

    private final StringBuilder report = new StringBuilder();
    private int passed;
    private FixtureContext source;
    private FixtureContext target;
    private String sourceBookId;
    private String targetBookId;
    private byte[] backup;

    // SAF 引用书回归：0.62.0 真机回环失败模式的固定夹具（books.file 为空、字节在 source_uri）。
    private static final String SAF_BOOK_ID = "saf-fixture-book";
    private static final String SAF_DOCUMENT_ID = "backup-saf/saf-fixture.txt";
    private byte[] safExpectedBytes;
    private String safExpectedSha;
    private List<String> safMissing;

    private interface Check { void run() throws Exception; }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            prepare();
            check("export manifest declares " + BackupManifest.DATA_SECTIONS.length
                    + " sections at version " + BackupManifest.SCHEMA_VERSION, this::manifestShape);
            check("export self-check reports no issue", this::selfCheck);
            check("roundtrip restores book, progress and annotation", this::libraryRoundtrip);
            check("roundtrip restores book and media statistics", this::statsRoundtrip);
            check("restoring the same backup twice keeps totals stable", this::idempotentRestore);
            check("corrupted payload is rejected before commit", this::corruptRejected);
            check("SAF-referenced book bytes survive export/restore", this::safReferencedBookRoundtrip);
            result.putString("stream", "\nPASS: " + passed + " backup roundtrip checks\n" + report);
            result.putInt("passed", passed);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after " + passed + " checks\n" + report
                    + android.util.Log.getStackTraceString(error));
            result.putString("failure", error.toString());
            finish(1, result);
        }
    }

    private void prepare() throws Exception {
        source = new FixtureContext(getTargetContext());
        File text = new File(source.getCacheDir(), "backup-source.txt");
        java.nio.file.Files.write(text.toPath(),
                "第一章 备份测试\n正文内容用于往返校验。\n".getBytes(StandardCharsets.UTF_8));
        File pack = new File(source.getCacheDir(), "backup-source.olbook");
        try (FileOutputStream out = new FileOutputStream(pack)) {
            TaggedBookPackage.write(out, text, "备份往返样书", "txt", java.util.Collections.emptyList());
        }
        BookStore store = new BookStore(source);
        BookStore.ImportResult imported = store.importTagged(Uri.fromFile(pack), null);
        require(imported != null && imported.book != null, "源书导入失败");
        sourceBookId = imported.book.id;

        SharedPreferences prefs = source.getSharedPreferences("reader", 0);
        prefs.edit()
                .putInt("chapter_" + sourceBookId, 0)
                .putInt("offset_" + sourceBookId, 5)
                .putString("anchor_" + sourceBookId,
                        ReadingAnchor.text("src:0", 0, 0, 5, 5, false, 0f).serialize())
                .putInt("progress_" + sourceBookId, 12)
                .apply();

        AnnotationStore.Anchor anchor = new AnnotationStore.Anchor(0, 1, 5, -1, -1, "正文");
        new AnnotationStore(source).insert(sourceBookId, anchor,
                AnnotationStore.TYPE_HIGHLIGHT, AnnotationStore.COLORS[0], "往返标注",
                AnnotationStore.STYLE_HIGHLIGHT);

        ReadingStatsStore stats = new ReadingStatsStore(source);
        stats.addSeconds(sourceBookId, 90, ReadingLedger.Kind.TEXT, SEGMENT);
        stats.addMediaSeconds(ReadingLedger.Kind.COMIC, MEDIA_KEY, "备份漫画", 60, SEGMENT + 1);

        prepareSafReferencedBook();

        // SAF 书与本地书放进同一个包：保留原 6 个 check 的目标书不变，同时让 SAF 书走真实导出路径。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LibraryBackup writer = new LibraryBackup(source, null);
        writer.export(out, EnumSet.allOf(BackupManifest.Part.class));
        backup = out.toByteArray();
        safMissing = writer.missingFiles();
        require(backup.length > 0, "导出为空");
    }

    /**
     * 造一本 0.62.0 失败模式下的 SAF 引用书：books.file 为空、字节只在 ContentResolver 可读的
     * source_uri 里。authority 必须用 provider 常量，isolation 包 suffix 下硬编码会失效。
     */
    private void prepareSafReferencedBook() throws Exception {
        Context context = getTargetContext();
        Uri safUri = DocumentsContract.buildDocumentUri(
                BulkFixtureProvider.AUTHORITY, SAF_DOCUMENT_ID);
        require(safUri != null, "无法构造 SAF 文档 URI");
        try (InputStream in = context.getContentResolver().openInputStream(safUri)) {
            require(in != null, "SAF fixture 输入流为空：" + safUri);
            safExpectedBytes = readAllBytes(in);
        }
        require(safExpectedBytes.length > 0, "SAF fixture 字节不应为空");
        safExpectedSha = ContentContracts.hashHex(safExpectedBytes);

        SQLiteDatabase db = LibraryDatabase.get(source).getWritableDatabase();
        android.content.ContentValues book = new android.content.ContentValues();
        book.put("id", SAF_BOOK_ID);
        book.put("title", "SAF往返样书");
        book.put("format", "txt");
        book.put("file", "");
        book.put("added", SEGMENT + 2);
        book.put("sha256", safExpectedSha);
        book.put("order_index", 1);
        require(db.insert("books", null, book) != -1, "SAF 引用书插入失败");
        android.content.ContentValues ref = new android.content.ContentValues();
        ref.put("book_id", SAF_BOOK_ID);
        ref.put("source_uri", safUri.toString());
        require(db.insert("book_references", null, ref) != -1, "SAF 引用行插入失败");
        record("saf sha=" + safExpectedSha.substring(0, 12) + " size=" + safExpectedBytes.length);
    }

    private void manifestShape() throws Exception {
        JSONObject manifest = new JSONObject(new String(readEntry(backup, "manifest.json"), StandardCharsets.UTF_8));
        eq(BackupManifest.SCHEMA_VERSION, manifest.optInt("schemaVersion"),
                "清单版本应为 " + BackupManifest.SCHEMA_VERSION);
        JSONArray entries = manifest.optJSONArray("entries");
        require(entries != null, "清单缺少 entries");
        int dataCount = 0;
        for (int i = 0; i < entries.length(); i++) {
            if ("DATA".equals(entries.getJSONObject(i).optString("kind"))) dataCount++;
        }
        eq(BackupManifest.DATA_SECTIONS.length, dataCount,
                "应有 " + BackupManifest.DATA_SECTIONS.length + " 个数据段（含 reader_prefs）");
        record("sections=" + dataCount);
    }

    private void selfCheck() throws Exception {
        JSONObject manifest = new JSONObject(new String(readEntry(backup, "manifest.json"), StandardCharsets.UTF_8));
        List<BackupManifest.Issue> issues = new LibraryBackup(source, null)
                .selfCheck(manifest.optInt("schemaVersion"), parseEntries(manifest));
        require(issues.isEmpty(), "自检不应有问题：" + issues);
        record("issues=0");
    }

    private void libraryRoundtrip() throws Exception {
        restoreIntoFreshTarget();
        BookStore store = new BookStore(target);
        BookStore.Book restored = store.find(targetBookId);
        require(restored != null, "恢复后找不到书");
        require("备份往返样书".equals(restored.title), "书名未恢复，实际 " + restored.title);
        SharedPreferences prefs = target.getSharedPreferences("reader", 0);
        require(prefs.getInt("chapter_" + targetBookId, -1) >= 0, "章节进度未恢复");
        require(prefs.getString("anchor_" + targetBookId, null) != null, "锚点未恢复");
        require(new AnnotationStore(target).list(targetBookId).size() >= 1, "标注未恢复");
        record("book=" + targetBookId);
    }

    private void statsRoundtrip() throws Exception {
        SQLiteDatabase db = LibraryDatabase.get(target).getReadableDatabase();
        long bookSeconds = scalar(db, "SELECT COALESCE(SUM(seconds),0) FROM reading_time WHERE book_id=?", targetBookId);
        require(bookSeconds >= 90, "书籍时长未恢复，实际 " + bookSeconds);
        String mediaId = MediaAttribution.id(ReadingLedger.Kind.COMIC, MEDIA_KEY);
        require(mediaId != null, "媒体 id 生成失败");
        eq(1L, count(db, "SELECT COUNT(*) FROM media_sources WHERE id=?", mediaId), "媒体归属未恢复");
        eq(60L, scalar(db, "SELECT COALESCE(SUM(seconds),0) FROM media_time WHERE source_id=?", mediaId),
                "媒体时长未恢复");
        require(count(db, "SELECT COUNT(*) FROM reading_sessions WHERE book_id=?", mediaId) >= 1,
                "媒体分列会话未恢复");
        record("book=" + bookSeconds + " media=60");
    }

    private void idempotentRestore() throws Exception {
        long before = scalar(LibraryDatabase.get(target).getReadableDatabase(),
                "SELECT COALESCE(SUM(seconds),0) FROM reading_time WHERE book_id=?", targetBookId);
        LibraryRestore restore = new LibraryRestore(target, null);
        LibraryRestore.Preflight plan = restore.preflight(new ByteArrayInputStream(backup));
        require(plan.ok(), "重复恢复的预检应通过");
        restore.commit(plan);
        long after = scalar(LibraryDatabase.get(target).getReadableDatabase(),
                "SELECT COALESCE(SUM(seconds),0) FROM reading_time WHERE book_id=?", targetBookId);
        eq(before, after, "重复恢复不应让时长翻倍");
        record("stable=" + after);
    }

    private void corruptRejected() throws Exception {
        byte[] tampered = replaceEntry(backup, "data/reading_time.json",
                "{}".getBytes(StandardCharsets.UTF_8));
        FixtureContext clean = new FixtureContext(getTargetContext());
        LibraryRestore.Preflight plan = new LibraryRestore(clean, null)
                .preflight(new ByteArrayInputStream(tampered));
        require(!plan.ok(), "载荷被篡改后预检必须失败");
        boolean fatal = false;
        for (BackupManifest.Issue issue : plan.issues) fatal |= issue.fatal;
        require(fatal, "应有一条致命问题阻止恢复");
        eq(0L, count(LibraryDatabase.get(clean).getReadableDatabase(),
                "SELECT COUNT(*) FROM books", null), "预检失败时不应写入任何数据");
        record("rejected before commit");
    }

    /**
     * 0.62.0 真机 FAIL 的可重复回归：SAF「添加文件」的书 books.file 为空、字节只在 source_uri。
     * 修复前导出判「书文件缺失，已跳过」，空库恢复后这本书打不开；此处逐项断言修复后的行为。
     */
    private void safReferencedBookRoundtrip() throws Exception {
        String safPath = "files/" + SAF_BOOK_ID + ".txt";
        JSONObject manifest = new JSONObject(new String(readEntry(backup, "manifest.json"), StandardCharsets.UTF_8));
        JSONArray entries = manifest.optJSONArray("entries");
        require(entries != null, "清单缺少 entries");
        BackupManifest.Entry safEntry = null;
        java.util.Set<String> declaredPaths = new java.util.LinkedHashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.getJSONObject(i);
            String path = item.optString("path", "");
            declaredPaths.add(path);
            if (safPath.equals(path)) {
                safEntry = new BackupManifest.Entry(path,
                        "FILE".equals(item.optString("kind"))
                                ? BackupManifest.EntryKind.FILE : BackupManifest.EntryKind.DATA,
                        item.optString("sha256", ""), item.optLong("size", -1), item.optString("part", ""));
            }
        }
        require(safEntry != null, "清单缺少 SAF 书文件条目：" + safPath);
        require(safEntry.kind == BackupManifest.EntryKind.FILE,
                "SAF 条目类型应为 FILE，实际 " + safEntry.kind);
        require(safExpectedSha.equalsIgnoreCase(safEntry.sha256),
                "SAF 条目 sha256 不符：期望 " + safExpectedSha + "，实际 " + safEntry.sha256);
        eq(safExpectedBytes.length, safEntry.size, "SAF 条目 size 不符");
        require(safMissing.isEmpty(), "导出不应漏书（0.62 SAF 修复点）：" + safMissing);
        require(!declaredPaths.contains("data/book_references.json"),
                "SAF 授权属设备字段，不得在清单中声明");
        require(!zipEntryNames(backup).contains("data/book_references.json"),
                "导出包不得包含 data/book_references.json");
        record("entry=" + safPath + " kind=FILE size=" + safEntry.size + " missing=" + safMissing.size());

        LibraryRestore.Preflight plan = restoreIntoFreshTarget();
        String targetSafId = plan.bookIdMap.get(SAF_BOOK_ID);
        require(targetSafId != null && !targetSafId.isEmpty(), "SAF 书未被恢复（没有 id 映射）");
        SQLiteDatabase db = LibraryDatabase.get(target).getReadableDatabase();
        String restoredFile = null;
        android.database.Cursor cursor = db.query("books", new String[]{"file"}, "id=?",
                new String[]{targetSafId}, null, null, null, "1");
        try { if (cursor.moveToFirst()) restoredFile = cursor.getString(0); }
        finally { cursor.close(); }
        require(restoredFile != null, "目标库缺少恢复的 SAF 书");
        eq(targetSafId + ".txt", restoredFile, "books.file 应为映射后的 <targetId>.txt");
        eq(0L, count(db, "SELECT COUNT(*) FROM book_references", null),
                "目标库不应恢复任何 SAF 授权引用行");

        File restored = new File(target.getFilesDir(), "books/" + restoredFile);
        require(restored.isFile(), "恢复后的 SAF 书文件不存在：" + restored);
        byte[] actual = java.nio.file.Files.readAllBytes(restored.toPath());
        require(Arrays.equals(safExpectedBytes, actual), "恢复的 SAF 书字节与源字节不一致");
        require(safExpectedSha.equals(ContentContracts.hashHex(actual)), "恢复的 SAF 书 SHA 不符");

        BookStore store = new BookStore(target);
        BookStore.Book book = store.find(targetSafId);
        require(book != null, "BookStore.find 找不到恢复的 SAF 书");
        eq(restoredFile, book.file, "BookStore 看到的 books.file 与库不一致");
        try (BookAccess access = store.open(book)) {
            byte[] opened = java.nio.file.Files.readAllBytes(access.file.toPath());
            require(Arrays.equals(safExpectedBytes, opened), "BookStore.open 读回字节不一致");
            require(safExpectedSha.equals(ContentContracts.hashHex(opened)), "BookStore.open 读回 SHA 不符");
        }
        record("targetId=" + targetSafId + " file=" + restoredFile + " bytes=" + actual.length
                + " opened via BookStore.open (no UI)");
    }

    private LibraryRestore.Preflight restoreIntoFreshTarget() throws Exception {
        target = new FixtureContext(getTargetContext());
        LibraryRestore restore = new LibraryRestore(target, null);
        LibraryRestore.Preflight plan = restore.preflight(new ByteArrayInputStream(backup));
        require(plan.ok(), "预检失败：" + plan.issues);
        String mapped = plan.bookIdMap.get(sourceBookId);
        targetBookId = mapped == null ? sourceBookId : mapped;
        require(restore.commit(plan) >= 1, "恢复的书籍数应至少为 1");
        return plan;
    }

    private static List<BackupManifest.Entry> parseEntries(JSONObject manifest) throws Exception {
        List<BackupManifest.Entry> entries = new java.util.ArrayList<>();
        JSONArray array = manifest.optJSONArray("entries");
        if (array == null) return entries;
        for (int i = 0; i < array.length(); i++) {
            JSONObject e = array.getJSONObject(i);
            entries.add(new BackupManifest.Entry(e.optString("path", ""),
                    "FILE".equals(e.optString("kind")) ? BackupManifest.EntryKind.FILE : BackupManifest.EntryKind.DATA,
                    e.optString("sha256", ""), e.optLong("size", -1), e.optString("part", "")));
        }
        return entries;
    }

    private static byte[] readEntry(byte[] zipBytes, String name) throws Exception {
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = zip.read(buffer)) > 0) out.write(buffer, 0, n);
                    return out.toByteArray();
                }
            }
        } finally { zip.close(); }
        throw new Exception("备份缺少条目：" + name);
    }

    private static java.util.Set<String> zipEntryNames(byte[] zipBytes) throws Exception {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
        } finally { zip.close(); }
        return names;
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static byte[] replaceEntry(byte[] zipBytes, String name, byte[] replacement) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(out);
        ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipBytes));
        try {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = in.getNextEntry()) != null) {
                ZipEntry copy = new ZipEntry(entry.getName());
                zip.putNextEntry(copy);
                if (name.equals(entry.getName())) zip.write(replacement);
                else { int n; while ((n = in.read(buffer)) > 0) zip.write(buffer, 0, n); }
                zip.closeEntry();
            }
        } finally { in.close(); zip.close(); }
        return out.toByteArray();
    }

    private static long scalar(SQLiteDatabase db, String sql, String arg) {
        android.database.Cursor c = arg == null ? db.rawQuery(sql, null) : db.rawQuery(sql, new String[]{arg});
        try { return c.moveToFirst() ? c.getLong(0) : 0L; } finally { c.close(); }
    }

    private static long count(SQLiteDatabase db, String sql, String arg) { return scalar(db, sql, arg); }

    private void check(String name, Check body) throws Exception {
        try {
            body.run();
            passed++;
            report.append("PASS ").append(passed).append("  ").append(name).append('\n');
        } catch (Throwable error) {
            report.append("FAIL  ").append(name).append(" -> ").append(error).append('\n');
            throw error;
        }
    }

    private void record(String detail) { report.append("      ").append(detail).append('\n'); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void eq(long expected, long actual, String message) {
        if (expected != actual) throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
    }

    private static void eq(String expected, String actual, String message) {
        if (!expected.equals(actual))
            throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
    }

    private static final class FixtureContext extends android.content.ContextWrapper {
        private final File root;
        private final String prefix = "backup-fixture-" + UUID.randomUUID();

        FixtureContext(Context base) {
            super(base);
            root = new File(base.getCacheDir(), prefix);
            if (!root.mkdirs()) throw new IllegalStateException("cannot create isolated fixture");
        }

        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { File dir = new File(root, "files"); dir.mkdirs(); return dir; }
        @Override public File getNoBackupFilesDir() { File dir = new File(root, "no-backup"); dir.mkdirs(); return dir; }
        @Override public File getDatabasePath(String name) {
            File dir = new File(root, "databases"); dir.mkdirs(); return new File(dir, name);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory) { return openOrCreateDatabase(name, mode, factory, null); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
            return SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(), factory,
                    SQLiteDatabase.CREATE_IF_NECESSARY, handler);
        }
        @Override public boolean deleteDatabase(String name) {
            return SQLiteDatabase.deleteDatabase(getDatabasePath(name));
        }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return getBaseContext().getSharedPreferences(prefix + "-" + name, mode);
        }
    }
}
