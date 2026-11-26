package org.openleaf.reader.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 导入编排：枚举 → 待选 → 校验 → 入库，逐项可恢复。
 * 借鉴 OpenLeaf 路线图的 ImportUseCase/Job 模型，纯 JVM，由 Android 层注入执行器。
 */
public final class ImportUseCase {
    private ImportUseCase() { }

    /** 单个候选项。业务层不持有 android.net.Uri，用字符串 id 表示资产。 */
    public static final class Candidate {
        public final String assetId;
        public final String name;
        public final String format;
        public final long size;
        public boolean selected = true;

        public Candidate(String assetId, String name, String format, long size) {
            this.assetId = assetId == null ? "" : assetId;
            this.name = name == null ? "" : name;
            this.format = format == null ? "" : format;
            this.size = size;
        }
    }

    /** 每项执行结果。 */
    public static final class ItemResult {
        public final String assetId;
        public final boolean success;
        public final boolean duplicate;
        public final String bookId;
        public final String error;

        ItemResult(String assetId, boolean success, boolean duplicate, String bookId, String error) {
            this.assetId = assetId;
            this.success = success;
            this.duplicate = duplicate;
            this.bookId = bookId;
            this.error = error;
        }
        public static ItemResult added(String assetId, String bookId) { return new ItemResult(assetId, true, false, bookId, null); }
        public static ItemResult duplicate(String assetId, String bookId) { return new ItemResult(assetId, true, true, bookId, null); }
        public static ItemResult failed(String assetId, String error) { return new ItemResult(assetId, false, false, null, error); }
    }

    /** 由 Android 层实现：真正执行一次导入（校验 + 入库），返回幂等结果。 */
    public interface ItemExecutor {
        ItemResult execute(Candidate candidate) throws IOException;
    }

    public interface Progress {
        void onItemDone(int processed, int total, ItemResult result);
        boolean cancelled();
    }

    /**
     * 从检查点续跑：已完成项（DONE/DUPLICATE）跳过，失败项可重试。
     * 每项成功后再推进检查点，保证"进程杀在提交前"可重跑且不重复。
     */
    public static Summary run(List<Candidate> candidates, ContentContracts.JobRepository jobs,
                              String jobId, ItemExecutor executor, Progress progress) {
        List<ContentContracts.JobItem> persisted = jobs.items(jobId);
        java.util.Set<String> finished = new java.util.HashSet<>();
        for (ContentContracts.JobItem item : persisted)
            if (item.state == ContentContracts.ItemState.DONE || item.state == ContentContracts.ItemState.DUPLICATE)
                finished.add(item.uri);

        int processed = 0, added = 0, duplicates = 0, failed = 0;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (!candidate.selected) continue;
            if (finished.contains(candidate.assetId)) {
                // 从检查点恢复：按持久化状态计数，不再重复入库
                for (ContentContracts.JobItem item : persisted) if (item.uri.equals(candidate.assetId)) {
                    if (item.state == ContentContracts.ItemState.DUPLICATE) duplicates++; else added++;
                    break;
                }
                processed++;
                continue;
            }
            if (progress != null && progress.cancelled()) {
                jobs.updateState(jobId, ContentContracts.JobState.PAUSED, processed, added, duplicates, failed);
                return new Summary(processed, added, duplicates, failed, true);
            }
            ItemResult result;
            try {
                result = executor.execute(candidate);
            } catch (IOException | RuntimeException error) {
                result = ItemResult.failed(candidate.assetId,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
            if (result.success && !result.duplicate) {
                added++;
                jobs.updateItem(jobId, i, ContentContracts.ItemState.DONE, result.bookId, null);
            } else if (result.success) {
                duplicates++;
                jobs.updateItem(jobId, i, ContentContracts.ItemState.DUPLICATE, result.bookId, null);
            } else {
                failed++;
                jobs.updateItem(jobId, i, ContentContracts.ItemState.FAILED, null, result.error);
            }
            processed++;
            if (progress != null) progress.onItemDone(processed, candidates.size(), result);
        }
        boolean paused = progress != null && progress.cancelled();
        jobs.updateState(jobId, paused ? ContentContracts.JobState.PAUSED : ContentContracts.JobState.DONE,
                processed, added, duplicates, failed);
        return new Summary(processed, added, duplicates, failed, paused);
    }

    public static final class Summary {
        public final int processed, added, duplicates, failed;
        public final boolean paused;
        Summary(int processed, int added, int duplicates, int failed, boolean paused) {
            this.processed = processed; this.added = added; this.duplicates = duplicates;
            this.failed = failed; this.paused = paused;
        }
    }

    /** 把候选列表转换成持久化 JobItem 列表。 */
    public static List<ContentContracts.JobItem> toJobItems(List<Candidate> candidates) {
        List<ContentContracts.JobItem> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            result.add(new ContentContracts.JobItem(i, c.assetId, c.name, c.format, c.size));
        }
        return Collections.unmodifiableList(result);
    }
}
