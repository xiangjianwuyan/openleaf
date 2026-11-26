package org.openleaf.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.openleaf.reader.core.ContentContracts;

import java.util.ArrayList;
import java.util.List;

/** SQLite 实现的持久化任务仓库；进程死亡后仍可从检查点续跑。 */
public final class SqliteJobRepository implements ContentContracts.JobRepository {
    private final LibraryDatabase database;

    public SqliteJobRepository(Context context) { database = LibraryDatabase.get(context); }

    @Override public void create(String jobId, String kind, String sourceTree, List<ContentContracts.JobItem> items) {
        SQLiteDatabase db = database.getWritableDatabase();
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            ContentValues job = new ContentValues();
            job.put("id", jobId);
            job.put("kind", kind);
            job.put("source_tree", sourceTree == null ? "" : sourceTree);
            job.put("state", ContentContracts.JobState.PENDING.name());
            job.put("processed", 0); job.put("added", 0); job.put("duplicates", 0); job.put("failed", 0);
            job.put("created_at", now); job.put("updated_at", now);
            db.insert("jobs", null, job);
            db.delete("job_items", "job_id=?", new String[]{jobId});
            for (ContentContracts.JobItem item : items) {
                ContentValues row = new ContentValues();
                row.put("job_id", jobId); row.put("seq", item.seq);
                row.put("uri", item.uri); row.put("relative_path", item.relativePath);
                row.put("format", item.format); row.put("size", item.size);
                row.put("state", item.state.name());
                db.insert("job_items", null, row);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    @Override public List<ContentContracts.JobItem> items(String jobId) {
        SQLiteDatabase db = database.getReadableDatabase();
        List<ContentContracts.JobItem> result = new ArrayList<>();
        Cursor cursor = db.query("job_items", null, "job_id=?", new String[]{jobId}, null, null, "seq ASC");
        try {
            while (cursor.moveToNext()) {
                ContentContracts.JobItem item = new ContentContracts.JobItem(
                        cursor.getInt(cursor.getColumnIndexOrThrow("seq")),
                        cursor.getString(cursor.getColumnIndexOrThrow("uri")),
                        cursor.getString(cursor.getColumnIndexOrThrow("relative_path")),
                        cursor.getString(cursor.getColumnIndexOrThrow("format")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("size")));
                try { item.state = ContentContracts.ItemState.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("state"))); }
                catch (RuntimeException ignored) { item.state = ContentContracts.ItemState.PENDING; }
                item.bookId = cursor.getString(cursor.getColumnIndexOrThrow("book_id"));
                item.error = cursor.getString(cursor.getColumnIndexOrThrow("error"));
                result.add(item);
            }
        } finally { cursor.close(); }
        return result;
    }

    @Override public void updateItem(String jobId, int seq, ContentContracts.ItemState state, String bookId, String error) {
        writeItem(database.getWritableDatabase(), jobId, seq, state, bookId, error);
    }

    @Override public void updateState(String jobId, ContentContracts.JobState state,
                                      int processed, int added, int duplicates, int failed) {
        ContentValues values = new ContentValues();
        values.put("state", state.name());
        values.put("processed", processed); values.put("added", added);
        values.put("duplicates", duplicates); values.put("failed", failed);
        values.put("updated_at", System.currentTimeMillis());
        database.getWritableDatabase().update("jobs", values, "id=?", new String[]{jobId});
    }

    /**
     * 逐项检查点：把「该项结果」与「任务计数」写进同一个事务。
     * 之前批量导入每本书调用 updateItem + updateState 两次，产生两次独立提交（两次 fsync）；
     * 合并后每本书只有一次提交，崩溃语义不变（项结果与计数要么一起生效，要么都不生效）。
     */
    @Override public void checkpoint(String jobId, int seq, ContentContracts.ItemState itemState, String bookId, String error,
                           ContentContracts.JobState jobState,
                           int processed, int added, int duplicates, int failed) {
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            writeItem(db, jobId, seq, itemState, bookId, error);
            writeState(db, jobId, jobState, processed, added, duplicates, failed);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void writeItem(SQLiteDatabase db, String jobId, int seq, ContentContracts.ItemState state,
                                  String bookId, String error) {
        ContentValues values = new ContentValues();
        values.put("state", state.name());
        values.put("book_id", bookId);
        values.put("error", error);
        db.update("job_items", values, "job_id=? AND seq=?", new String[]{jobId, String.valueOf(seq)});
    }

    private static void writeState(SQLiteDatabase db, String jobId, ContentContracts.JobState state,
                                   int processed, int added, int duplicates, int failed) {
        ContentValues values = new ContentValues();
        values.put("state", state.name());
        values.put("processed", processed); values.put("added", added);
        values.put("duplicates", duplicates); values.put("failed", failed);
        values.put("updated_at", System.currentTimeMillis());
        db.update("jobs", values, "id=?", new String[]{jobId});
    }

    @Override public ContentContracts.JobRepository.JobSnapshot snapshot(String jobId) {
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.query("jobs", new String[]{"state", "processed", "added", "duplicates", "failed"},
                "id=?", new String[]{jobId}, null, null, null);
        try {
            if (cursor.moveToFirst()) {
                ContentContracts.JobState state;
                try { state = ContentContracts.JobState.valueOf(cursor.getString(0)); }
                catch (RuntimeException ignored) { state = ContentContracts.JobState.PENDING; }
                return new ContentContracts.JobRepository.JobSnapshot(state,
                        cursor.getInt(1), cursor.getInt(2), cursor.getInt(3), cursor.getInt(4));
            }
        } finally { cursor.close(); }
        return new ContentContracts.JobRepository.JobSnapshot(ContentContracts.JobState.PENDING, 0, 0, 0, 0);
    }

    @Override public void delete(String jobId) {
        SQLiteDatabase db = database.getWritableDatabase();
        db.delete("job_items", "job_id=?", new String[]{jobId});
        db.delete("jobs", "id=?", new String[]{jobId});
    }

    /** 任务摘要，供任务中心列表展示。 */
    public static final class JobSummary {
        public final String id, kind, sourceTree, state;
        public final int processed, added, duplicates, failed;
        /** 尚未处理的条目数（state = PENDING）；被取消的任务靠它判断还能不能续跑。 */
        public final int pending;
        public final long updatedAt;
        JobSummary(String id, String kind, String sourceTree, String state,
                   int processed, int added, int duplicates, int failed, int pending, long updatedAt) {
            this.id = id; this.kind = kind; this.sourceTree = sourceTree; this.state = state;
            this.processed = processed; this.added = added; this.duplicates = duplicates;
            this.failed = failed; this.pending = pending; this.updatedAt = updatedAt;
        }
    }

    /** 列出全部任务（按更新时间倒序），供任务中心展示；pending 为关联子查询算出的未处理条目数。 */
    public List<JobSummary> listJobs() {
        SQLiteDatabase db = database.getReadableDatabase();
        List<JobSummary> result = new ArrayList<>();
        // jobs 表没有 pending 列，用子查询算「还剩多少条没处理」；否则取消态的任务无法判断还能不能续跑。
        Cursor cursor = db.rawQuery(
                "SELECT j.*, (SELECT COUNT(*) FROM job_items ji WHERE ji.job_id=j.id AND ji.state='PENDING') AS pending"
                        + " FROM jobs j ORDER BY j.updated_at DESC", null);
        try {
            while (cursor.moveToNext()) {
                result.add(new JobSummary(
                        cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("kind")),
                        cursor.getString(cursor.getColumnIndexOrThrow("source_tree")),
                        cursor.getString(cursor.getColumnIndexOrThrow("state")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("processed")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("added")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("duplicates")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("failed")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("pending")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))));
            }
        } finally { cursor.close(); }
        return result;
    }

    /**
     * 把任务状态置为 CANCELLED（任务中心"停止"操作：先真停执行器，再落这个状态）。
     * 任务中心是唯一的调用点，原先的 pause（同样只改库、不停线程）已被它取代并删除。
     */
    public void cancel(String jobId) {
        setState(jobId, ContentContracts.JobState.CANCELLED);
    }

    private void setState(String jobId, ContentContracts.JobState state) {
        ContentValues values = new ContentValues();
        values.put("state", state.name());
        values.put("updated_at", System.currentTimeMillis());
        database.getWritableDatabase().update("jobs", values, "id=?", new String[]{jobId});
    }

    /**
     * 重试失败项：FAILED / NEEDS_AUTH 的 item 重置为 PENDING，任务整体回到 PENDING（任务中心"重试"）。
     *
     * <p>NEEDS_AUTH 必须一起重置：授权失效的条目在用户重新授权后本该能重试，
     * 只放 FAILED 会让它永远卡在「显示失败、点重试却说没有待重试条目」。
     *
     * @return 被重置的条目数；为 0 时任务状态不动（不制造无人消费的 PENDING）。
     */
    public int retryFailed(String jobId) {
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues item = new ContentValues();
            item.put("state", ContentContracts.ItemState.PENDING.name());
            item.put("error", "");
            int reset = db.update("job_items", item, "job_id=? AND state IN (?,?)",
                    new String[]{jobId, ContentContracts.ItemState.FAILED.name(),
                            ContentContracts.ItemState.NEEDS_AUTH.name()});
            // 只有真的重置了条目才把任务拉回 PENDING：否则会留下一个没有任何执行器消费的
            // PENDING（任务显示「待处理」、点重试又说「没有可重试的条目」）——正是本轮要消灭的状态。
            // reset == 0 但条目本来就是 PENDING（上一轮被取消剩下的）时无需改状态：start() 会挑到它们
            // 并在第一个检查点直接把任务置 RUNNING。
            if (reset > 0) {
                ContentValues job = new ContentValues();
                job.put("state", ContentContracts.JobState.PENDING.name());
                job.put("updated_at", System.currentTimeMillis());
                db.update("jobs", job, "id=?", new String[]{jobId});
            }
            db.setTransactionSuccessful();
            return reset;
        } finally { db.endTransaction(); }
    }

    /** 列出未完成/暂停的任务，供冷启动时提示恢复。 */
    public List<String> resumableJobs() {
        SQLiteDatabase db = database.getReadableDatabase();
        List<String> result = new ArrayList<>();
        Cursor cursor = db.query("jobs", new String[]{"id"}, "state IN (?,?,?)",
                new String[]{ContentContracts.JobState.PENDING.name(), ContentContracts.JobState.RUNNING.name(),
                        ContentContracts.JobState.PAUSED.name()}, null, null, "updated_at DESC");
        try { while (cursor.moveToNext()) result.add(cursor.getString(0)); }
        finally { cursor.close(); }
        return result;
    }
}
