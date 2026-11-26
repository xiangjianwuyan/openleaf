package org.openleaf.reader.core;

import java.io.IOException;

/**
 * 下载编排：走同一 Job/JobItem 任务模型，但业务规则与导入不同。
 * 纯 JVM；HTTP 与文件写入由 Android 层注入。
 */
public final class DownloadUseCase {
    private DownloadUseCase() { }

    /** 一个下载项：URL + 目标文件名 + 预期校验值。 */
    public static final class Request {
        public final String url;
        public final String fileName;
        public final String expectedSha256;
        public final long expectedBytes;

        public Request(String url, String fileName, String expectedSha256, long expectedBytes) {
            this.url = url == null ? "" : url;
            this.fileName = fileName == null ? "download" : fileName;
            this.expectedSha256 = expectedSha256 == null ? "" : expectedSha256;
            this.expectedBytes = expectedBytes;
        }
    }

    public static final class Result {
        public final boolean success;
        public final String localPath;
        public final String sha256;
        public final String error;

        Result(boolean success, String localPath, String sha256, String error) {
            this.success = success; this.localPath = localPath; this.sha256 = sha256; this.error = error;
        }
        public static Result ok(String path, String sha) { return new Result(true, path, sha, null); }
        public static Result fail(String error) { return new Result(false, null, null, error); }
    }

    /** 下载执行器：实现方负责 HTTPS 策略、临时文件、校验后原子提交。 */
    public interface Fetcher {
        Result fetch(Request request) throws IOException;
    }

    public interface Progress {
        void onItemDone(int processed, int total, Result result);
        boolean cancelled();
    }

    /**
     * 逐项下载并写检查点；已 DONE 的项跳过（幂等），失败项可重试。
     * 与 ImportUseCase.run 共用 JobRepository，但这里没有 duplicates 概念。
     */
    public static Summary run(java.util.List<Request> requests, ContentContracts.JobRepository jobs,
                              String jobId, Fetcher fetcher, Progress progress) {
        java.util.Set<String> finished = new java.util.HashSet<>();
        for (ContentContracts.JobItem item : jobs.items(jobId))
            if (item.state == ContentContracts.ItemState.DONE) finished.add(item.uri);

        int processed = 0, succeeded = 0, failed = 0;
        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            if (finished.contains(request.url)) {
                succeeded++;
                processed++;
                continue;
            }
            if (progress != null && progress.cancelled()) {
                jobs.updateState(jobId, ContentContracts.JobState.PAUSED, processed, succeeded, 0, failed);
                return new Summary(processed, succeeded, failed, true);
            }
            Result result;
            try { result = fetcher.fetch(request); }
            catch (IOException | RuntimeException error) {
                result = Result.fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
            if (result.success) {
                succeeded++;
                jobs.updateItem(jobId, i, ContentContracts.ItemState.DONE, result.localPath, null);
            } else {
                failed++;
                jobs.updateItem(jobId, i, ContentContracts.ItemState.FAILED, null, result.error);
            }
            processed++;
            if (progress != null) progress.onItemDone(processed, requests.size(), result);
        }
        boolean paused = progress != null && progress.cancelled();
        jobs.updateState(jobId, paused ? ContentContracts.JobState.PAUSED : ContentContracts.JobState.DONE,
                processed, succeeded, 0, failed);
        return new Summary(processed, succeeded, failed, paused);
    }

    public static final class Summary {
        public final int processed, succeeded, failed;
        public final boolean paused;
        Summary(int processed, int succeeded, int failed, boolean paused) {
            this.processed = processed; this.succeeded = succeeded; this.failed = failed; this.paused = paused;
        }
    }

    public static java.util.List<ContentContracts.JobItem> toJobItems(java.util.List<Request> requests) {
        java.util.List<ContentContracts.JobItem> result = new java.util.ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            Request r = requests.get(i);
            result.add(new ContentContracts.JobItem(i, r.url, r.fileName,
                    extensionOf(r.fileName), r.expectedBytes));
        }
        return java.util.Collections.unmodifiableList(result);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }
}
