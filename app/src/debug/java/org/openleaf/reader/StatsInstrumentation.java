package org.openleaf.reader;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import org.openleaf.reader.core.BackupManifest;
import org.openleaf.reader.core.MediaAttribution;
import org.openleaf.reader.core.ReadingLedger;

import java.util.Arrays;
import java.util.List;

/**
 * 统计归属设备回归：确认非书媒体真的写进了三张表、总时长没有漏算、
 * 备份清单把它们带上，并且重复提交同一段不会翻倍。
 */
public final class StatsInstrumentation extends Instrumentation {
    private static final String FIXTURE_KEY = "stats-instrumentation-comic";
    private static final long SEGMENT_START = 1_700_000_000_000L;

    private final StringBuilder report = new StringBuilder();
    private int passed;
    private String sourceId;

    private interface Check { void run() throws Exception; }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        // 设备会反复跑同一套插桩；开始前先清掉自己的夹具，避免上一轮的残留让本轮基线变 180
        cleanup();
        try {
            check("database schema 16 creates non-book media tables", this::schema);
            check("media seconds write source, aggregate and session rows", this::writesThreeTables);
            check("per-kind totals see the media segment", this::perKindTotals);
            check("overall totals include non-book media", this::overallTotals);
            check("backup manifest carries both media tables at version 4", this::backupLists);
            check("re-submitting the same segment does not double count", this::idempotentSegment);
            result.putString("stream", "\nPASS: " + passed + " stats attribution checks\n" + report);
            result.putInt("passed", passed);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after " + passed + " checks\n" + report
                    + android.util.Log.getStackTraceString(error));
            result.putString("failure", error.toString());
            finish(1, result);
        } finally {
            cleanup();
        }
    }

    private void schema() {
        SQLiteDatabase db = LibraryDatabase.get(getTargetContext()).getWritableDatabase();
        eq(16, db.getVersion(), "DATABASE_VERSION 应为 16");
        for (String table : new String[]{"media_sources", "media_time"}) {
            Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                    new String[]{table});
            try { require(c.moveToFirst(), "缺少表 " + table); } finally { c.close(); }
        }
        record("schema=16 media_sources+media_time");
    }

    private void writesThreeTables() {
        Context ctx = getTargetContext();
        sourceId = MediaAttribution.id(ReadingLedger.Kind.COMIC, FIXTURE_KEY);
        require(sourceId != null, "媒体 id 生成失败");
        new ReadingStatsStore(ctx).addMediaSeconds(ReadingLedger.Kind.COMIC,
                FIXTURE_KEY, "统计测试样书", 90, SEGMENT_START);

        SQLiteDatabase db = LibraryDatabase.get(ctx).getReadableDatabase();
        eq(1, count(db, "SELECT COUNT(*) FROM media_sources WHERE id=?", sourceId), "media_sources 未写入");
        eq(90, scalar(db, "SELECT seconds FROM media_time WHERE source_id=?", sourceId), "media_time 秒数不对");
        eq(1, count(db, "SELECT COUNT(*) FROM reading_sessions WHERE book_id=? AND kind='comic'", sourceId),
                "分列会话未写入");
        record("id=" + sourceId);
    }

    private void perKindTotals() {
        long comic = new ReadingStatsStore(getTargetContext())
                .totalsByKind().get(ReadingLedger.Kind.COMIC);
        require(comic >= 90, "COMIC 分列合计应至少 90 秒，实际 " + comic);
        record("COMIC=" + comic);
    }

    private void overallTotals() {
        ReadingStatsStore store = new ReadingStatsStore(getTargetContext());
        require(store.totalSeconds() >= 90, "总时长应含非书媒体，实际 " + store.totalSeconds());
        require(store.dailyTotals(1).values().iterator().next() >= 90, "当日合计应含非书媒体");
        record("total=" + store.totalSeconds());
    }

    private void backupLists() {
        eq(4, BackupManifest.SCHEMA_VERSION, "备份清单版本应为 4");
        List<String> sections = Arrays.asList(BackupManifest.DATA_SECTIONS);
        require(sections.contains("media_sources"), "DATA_SECTIONS 缺 media_sources");
        require(sections.contains("media_time"), "DATA_SECTIONS 缺 media_time");
        record("schemaVersion=3 sections=" + sections.size());
    }

    private void idempotentSegment() {
        Context ctx = getTargetContext();
        new ReadingStatsStore(ctx).addMediaSeconds(ReadingLedger.Kind.COMIC,
                FIXTURE_KEY, "统计测试样书", 90, SEGMENT_START);
        SQLiteDatabase db = LibraryDatabase.get(ctx).getReadableDatabase();
        eq(90, scalar(db, "SELECT seconds FROM media_time WHERE source_id=?", sourceId),
                "同段重复提交不应翻倍");
        eq(1, count(db, "SELECT COUNT(*) FROM reading_sessions WHERE book_id=?", sourceId),
                "同段重复提交不应产生第二条会话");
        record("90/1 after replay");
    }

    private static int count(SQLiteDatabase db, String sql, String arg) {
        Cursor c = db.rawQuery(sql, new String[]{arg});
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    private static long scalar(SQLiteDatabase db, String sql, String arg) {
        Cursor c = db.rawQuery(sql, new String[]{arg});
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    private void cleanup() {
        String id = MediaAttribution.id(ReadingLedger.Kind.COMIC, FIXTURE_KEY);
        if (id == null) return;
        try {
            SQLiteDatabase db = LibraryDatabase.get(getTargetContext()).getWritableDatabase();
            db.execSQL("DELETE FROM reading_sessions WHERE book_id=?", new Object[]{id});
            db.execSQL("DELETE FROM media_time WHERE source_id=?", new Object[]{id});
            db.execSQL("DELETE FROM media_sources WHERE id=?", new Object[]{id});
        } catch (RuntimeException ignored) { }
    }

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
}
