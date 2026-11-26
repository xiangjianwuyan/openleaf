package org.openleaf.reader;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.util.Log;

import org.openleaf.reader.core.ContentContracts;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 任务中心「重试」的真正执行器：只接管本机能重新跑的本地导入任务（kind = folder / import）。
 *
 * <p>此前「重试失败项」只把 job_items 从 FAILED 改回 PENDING，全项目没有任何执行器消费 PENDING：
 * 用户按下重试后任务永远停在「待处理」，等于承诺了不存在的能力。这里补上执行侧：
 * 与 {@link PickerImport} 同款——进程内单例（静态持有）、只存 applicationContext、
 * 后台线程跑导入、按 jobId 持有一个 CancellationSignal 让「停止」真能停。
 *
 * <p>download 不在支持范围内：重下需要原下载页的 URL 与下载策略，这里没有，所以 {@link #start}
 * 返回 {@link #UNSUPPORTED}，由调用方给出诚实文案，不假装能跑。
 */
final class JobRetryRunner {
    /** 任务类型不支持自动重试（download 由原下载页重新发起）。 */
    static final int UNSUPPORTED = -1;
    /** 没有可重试的条目（全部已完成，或条目列表为空）。 */
    static final int NOTHING_TO_RETRY = 0;
    /** 同一任务已在本进程内重试中：不并发跑两遍，避免同一批条目重复写库。 */
    static final int ALREADY_RUNNING = -2;

    private static final String TAG = "OpenLeafRetry";
    /** 进程内正在执行的重试；key = jobId。按 jobId 取消，UI 用它判断是否在运行。 */
    private static final Map<String, CancellationSignal> ACTIVE = new ConcurrentHashMap<>();

    private JobRetryRunner() { }

    /**
     * 开始重试一个任务。只挑出 state ∈ {FAILED, PENDING} 的条目重跑，已完成项不动。
     *
     * <p>同步返回（列表与条目各读一次库，代价与任务中心渲染一次相同）：
     * <ul>
     *   <li>&gt; 0：已在后台开始重试，值为本次重试的条目数</li>
     *   <li>{@link #UNSUPPORTED}：kind 不是 folder / import</li>
     *   <li>{@link #NOTHING_TO_RETRY}：没有待重试的条目</li>
     *   <li>{@link #ALREADY_RUNNING}：该任务正在重试中</li>
     * </ul>
     */
    static int start(Context context, String jobId) {
        if (context == null || jobId == null || jobId.isEmpty()) return NOTHING_TO_RETRY;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        SqliteJobRepository jobs = new SqliteJobRepository(app);
        SqliteJobRepository.JobSummary job = null;
        for (SqliteJobRepository.JobSummary candidate : jobs.listJobs()) {
            if (jobId.equals(candidate.id)) { job = candidate; break; }
        }
        if (job == null) return NOTHING_TO_RETRY;
        boolean folderKind = "folder".equals(job.kind);
        if (!folderKind && !"import".equals(job.kind)) return UNSUPPORTED;
        if (isRunning(jobId)) return ALREADY_RUNNING;
        Uri tree = folderKind && job.sourceTree != null && !job.sourceTree.isEmpty()
                ? Uri.parse(job.sourceTree) : null;
        List<ContentContracts.JobItem> all = jobs.items(jobId);
        List<ContentContracts.JobItem> targets = new ArrayList<>();
        for (ContentContracts.JobItem item : all) {
            if (item.state == ContentContracts.ItemState.FAILED
                    || item.state == ContentContracts.ItemState.PENDING) targets.add(item);
        }
        if (targets.isEmpty()) return NOTHING_TO_RETRY;
        CancellationSignal signal = new CancellationSignal();
        ACTIVE.put(jobId, signal);
        // No Activity or UI object crosses onto the worker; the run never calls back into the UI.
        Executors.defaultThreadFactory()
                .newThread(new Retry(app, jobs, jobId, folderKind, tree, all, targets, signal)).start();
        return targets.size();
    }

    /** 真停：取消信号，正在读的那个文件会在下一个检查点抛 OperationCanceledException。 */
    static boolean cancel(String jobId) {
        if (jobId == null) return false;
        CancellationSignal signal = ACTIVE.get(jobId);
        if (signal == null || signal.isCanceled()) return false;
        // 取消监听可能在调用线程上同步跑（关闭输入流等），不占 UI 线程。
        Executors.defaultThreadFactory().newThread(signal::cancel).start();
        return true;
    }

    /** 该任务是否正在本进程内重试（线程真正收尾退出后才算结束）。 */
    static boolean isRunning(String jobId) {
        return jobId != null && ACTIVE.containsKey(jobId);
    }

    /** 本进程内是否还有任何任务在重试：任务中心用它作为自动刷新的终止条件。 */
    static boolean isAnythingRunning() {
        return !ACTIVE.isEmpty();
    }

    private static final class Retry implements Runnable {
        private final Context app;
        private final SqliteJobRepository jobs;
        private final String jobId;
        private final boolean folderKind;
        private final Uri tree;
        private final List<ContentContracts.JobItem> all;
        private final List<ContentContracts.JobItem> targets;
        private final CancellationSignal signal;

        Retry(Context app, SqliteJobRepository jobs, String jobId, boolean folderKind, Uri tree,
              List<ContentContracts.JobItem> all, List<ContentContracts.JobItem> targets,
              CancellationSignal signal) {
            this.app = app;
            this.jobs = jobs;
            this.jobId = jobId;
            this.folderKind = folderKind;
            this.tree = tree;
            this.all = all;
            this.targets = targets;
            this.signal = signal;
        }

        @Override public void run() {
            // 计数以「条目真实状态」为准重算，不在旧计数上累加：
            // retryFailed 把 FAILED 改回 PENDING 时并没有回头减掉 job.failed，累加会双计。
            int added = 0, duplicates = 0, failed = 0, skipped = 0;
            for (ContentContracts.JobItem item : all) {
                if (item.state == ContentContracts.ItemState.DONE) added++;
                else if (item.state == ContentContracts.ItemState.DUPLICATE) duplicates++;
                else if (item.state == ContentContracts.ItemState.FAILED
                        || item.state == ContentContracts.ItemState.NEEDS_AUTH) failed++;
                else if (item.state == ContentContracts.ItemState.SKIPPED) skipped++;
            }
            boolean canceled = false, broken = false;
            try {
                BookStore store = new BookStore(app);
                store.prepareImport(signal);
                if (folderKind && tree == null) throw new IOException("任务缺少来源目录");
                // 首次导入的分组名来自 SAF 显示名（"Download" 等），不是固定字面量；重试必须复用库里的
                // 真实名——ensureImportFolder 查到既有行会把 name 改成传入值（BookStore.ensureFolderRow）。
                String rootName = folderKind && tree != null ? store.importRootName(tree) : null;
                if (rootName == null) rootName = FolderImportActivity.IMPORT_ROOT_NAME;
                Map<String, String> folders = new HashMap<>();
                for (ContentContracts.JobItem item : targets) {
                    signal.throwIfCanceled();
                    final boolean wasFailed = item.state == ContentContracts.ItemState.FAILED
                            || item.state == ContentContracts.ItemState.NEEDS_AUTH;
                    ContentContracts.ItemState next;
                    String bookId = null, reason = "";
                    try {
                        String folderId = null;
                        if (folderKind && tree != null) {
                            String parent = parentOf(item.relativePath);
                            folderId = folders.get(parent);
                            if (folderId == null) {
                                folderId = store.ensureImportFolder(tree, rootName, parent);
                                folders.put(parent, folderId);
                            }
                        }
                        BookStore.ImportResult result = store.importUri(Uri.parse(item.uri), signal, folderId,
                                (name, phase, bytes, total) -> { /* 无 UI：进度不推送 */ },
                                baseName(item.relativePath), item.format);
                        bookId = result.book == null ? null : result.book.id;
                        next = result.duplicate
                                ? ContentContracts.ItemState.DUPLICATE : ContentContracts.ItemState.DONE;
                    } catch (OperationCanceledException stopped) {
                        canceled = true;
                        break;
                    } catch (SecurityException | FileNotFoundException denied) {
                        next = ContentContracts.ItemState.NEEDS_AUTH;
                        reason = "授权已失效，请重新选择文件夹或重新导入";
                    } catch (Exception failure) {
                        next = ContentContracts.ItemState.FAILED;
                        reason = message(failure);
                    }
                    if (wasFailed) failed--;
                    if (next == ContentContracts.ItemState.DONE) added++;
                    else if (next == ContentContracts.ItemState.DUPLICATE) duplicates++;
                    else failed++;
                    item.state = next;
                    item.bookId = bookId;
                    item.error = reason;
                    try {
                        jobs.checkpoint(jobId, item.seq, next, bookId, reason,
                                ContentContracts.JobState.RUNNING,
                                added + duplicates + failed + skipped, added, duplicates, failed);
                    } catch (RuntimeException bookkeeping) {
                        // 记账失败静默降级：书已入库，书架结果是准的，不因此把该项算失败。
                        Log.w(TAG, "checkpoint failed: " + item.relativePath, bookkeeping);
                    }
                }
            } catch (OperationCanceledException stopped) {
                canceled = true;
            } catch (Exception preparation) {
                Log.w(TAG, "retry preparation failed: " + jobId, preparation);
                broken = true;
            } finally {
                ACTIVE.remove(jobId, signal);
                try {
                    ContentContracts.JobState next = canceled ? ContentContracts.JobState.CANCELLED
                            : broken ? ContentContracts.JobState.FAILED
                            : ContentContracts.JobState.DONE;
                    // 停止是异步的（cancel 信号在别的线程上跑）：用户按了停止、jobs.cancel() 已落 CANCELLED，
                    // 但信号可能晚于最后一个条目完成才生效。此时 DONE 与 CANCELLED 都成立，
                    // 尊重用户意图保留 CANCELLED，不让列表和「已停止」的 toast 自相矛盾；计数照写。
                    if (next != ContentContracts.JobState.CANCELLED
                            && jobs.snapshot(jobId).state == ContentContracts.JobState.CANCELLED)
                        next = ContentContracts.JobState.CANCELLED;
                    jobs.updateState(jobId, next, added + duplicates + failed + skipped,
                            added, duplicates, failed);
                } catch (RuntimeException bookkeeping) {
                    Log.w(TAG, "updateState failed: " + jobId, bookkeeping);
                }
            }
        }
    }

    /** 相对路径的父目录：去掉最后一段；没有 '/' 时为空串（对应导入根目录）。 */
    private static String parentOf(String relativePath) {
        if (relativePath == null) return "";
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? "" : relativePath.substring(0, slash);
    }

    /** 相对路径的文件名：最后一段；拿不到则返回 null，交给 provider 查询真实显示名。 */
    private static String baseName(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }

    private static String message(Exception e) {
        String text = e.getMessage();
        return text == null || text.isEmpty() ? e.getClass().getSimpleName() : text;
    }
}
