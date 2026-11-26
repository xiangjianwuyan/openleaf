package org.openleaf.reader.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 纯 JVM 契约：把「书籍身份」「资产位置」「内容版本」三者分开。
 * 借鉴 OpenLeaf 架构路线图的接口设计，由 OpenLeaf 自行实现。
 * 不含任何 Android 依赖，便于 core-tests 直接验证。
 */
public final class ContentContracts {
    private ContentContracts() { }

    /** 资产的读写与可寻址能力；业务层只认这个句柄，不直接持有原始 URI。 */
    public interface AssetGateway {
        /** 打开资产句柄。caller 负责 close。 */
        AssetHandle open(String assetId) throws IOException;
        /** 是否有该资产的持久读授权。 */
        boolean authorized(String assetId);
        /** 计算资产当前内容的 SHA-256（小写 hex），与 BinaryContent.hashHex 一致。 */
        String fingerprint(String assetId) throws IOException;
    }

    /** 资产句柄：可读、可写、可寻址三项能力显式声明。 */
    public abstract static class AssetHandle implements AutoCloseable {
        public abstract String id();
        public abstract boolean readable();
        public abstract boolean writable();
        public abstract boolean seekable();
        public abstract String fingerprint() throws IOException;
        @Override public abstract void close() throws IOException;
    }

    /** 内容版本：一次已验证的内容快照。SHA-256 是内容指纹，不是条目 ID。 */
    public static final class Revision {
        public final String assetId;
        public final String sha256;
        public final long size;
        public final long timestamp;

        public Revision(String assetId, String sha256, long size, long timestamp) {
            this.assetId = assetId;
            this.sha256 = sha256 == null ? "" : sha256;
            this.size = size;
            this.timestamp = timestamp;
        }

        public boolean sameContent(Revision other) {
            return other != null && sha256.equals(other.sha256);
        }
    }

    /** 编辑回传：类型化区分「已覆盖原件」「已另存副本」「冲突」「失败」。 */
    public enum EditKind { COVERED, SAVED_COPY, CONFLICT, FAILED }

    public static final class EditOutcome {
        public final EditKind kind;
        public final String targetAssetId;
        public final Revision newRevision;
        public final String message;

        public EditOutcome(EditKind kind, String targetAssetId, Revision newRevision, String message) {
            this.kind = kind;
            this.targetAssetId = targetAssetId == null ? "" : targetAssetId;
            this.newRevision = newRevision;
            this.message = message == null ? "" : message;
        }

        public static EditOutcome covered(String assetId, Revision revision) {
            return new EditOutcome(EditKind.COVERED, assetId, revision, "已覆盖原文件");
        }
        public static EditOutcome savedCopy(String assetId, String message) {
            return new EditOutcome(EditKind.SAVED_COPY, assetId, null, message);
        }
        public static EditOutcome conflict(String message) {
            return new EditOutcome(EditKind.CONFLICT, "", null, message);
        }
        public static EditOutcome failed(String message) {
            return new EditOutcome(EditKind.FAILED, "", null, message);
        }

        /** 只有覆盖原件才改变资产内容，需要登记新版本。 */
        public boolean changesAsset() { return kind == EditKind.COVERED; }
        public boolean ok() { return kind == EditKind.COVERED || kind == EditKind.SAVED_COPY; }
    }

    /** 任务状态机：导入与下载共用模型，业务规则各自实现。 */
    public enum JobState { PENDING, RUNNING, PAUSED, DONE, CANCELLED, FAILED }
    public enum ItemState { PENDING, DONE, DUPLICATE, FAILED, SKIPPED, NEEDS_AUTH }

    public static final class JobItem {
        public final int seq;
        public final String uri;
        public final String relativePath;
        public final String format;
        public final long size;
        public ItemState state;
        public String bookId;
        public String error;

        public JobItem(int seq, String uri, String relativePath, String format, long size) {
            this.seq = seq;
            this.uri = uri == null ? "" : uri;
            this.relativePath = relativePath == null ? "" : relativePath;
            this.format = format == null ? "" : format;
            this.size = size;
            this.state = ItemState.PENDING;
        }
    }

    /** 持久化任务仓库接口；Android 实现用 SQLite。 */
    public interface JobRepository {
        void create(String jobId, String kind, String sourceTree, List<JobItem> items);
        List<JobItem> items(String jobId);
        void updateItem(String jobId, int seq, ItemState state, String bookId, String error);
        void updateState(String jobId, JobState state, int processed, int added, int duplicates, int failed);
        JobSnapshot snapshot(String jobId);
        void delete(String jobId);

        /**
         * 单次检查点：该项结果与任务计数一起落盘。批量导入每本书会写一次，
         * 支持事务的仓库应覆盖本方法以避免两次独立提交；语义与先后调用
         * updateItem + updateState 相同。
         */
        default void checkpoint(String jobId, int seq, ItemState state, String bookId, String error,
                               JobState jobState, int processed, int added, int duplicates, int failed) {
            updateItem(jobId, seq, state, bookId, error);
            updateState(jobId, jobState, processed, added, duplicates, failed);
        }

        public static final class JobSnapshot {
            public final JobState state;
            public final int processed, added, duplicates, failed;
            public JobSnapshot(JobState state, int processed, int added, int duplicates, int failed) {
                this.state = state;
                this.processed = processed;
                this.added = added;
                this.duplicates = duplicates;
                this.failed = failed;
            }
        }
    }

    /** 内存实现，供纯 JVM 测试与回退使用。 */
    public static final class InMemoryJobRepository implements JobRepository {
        private final java.util.Map<String, List<JobItem>> stores = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, JobSnapshot> snapshots = new java.util.LinkedHashMap<>();

        @Override public void create(String jobId, String kind, String sourceTree, List<JobItem> items) {
            stores.put(jobId, new ArrayList<>(items));
            snapshots.put(jobId, new JobSnapshot(JobState.PENDING, 0, 0, 0, 0));
        }
        @Override public List<JobItem> items(String jobId) {
            List<JobItem> found = stores.get(jobId);
            return found == null ? Collections.<JobItem>emptyList() : Collections.unmodifiableList(found);
        }
        @Override public void updateItem(String jobId, int seq, ItemState state, String bookId, String error) {
            for (JobItem item : items(jobId)) if (item.seq == seq) {
                item.state = state; item.bookId = bookId; item.error = error; return;
            }
        }
        @Override public void updateState(String jobId, JobState state, int processed, int added, int duplicates, int failed) {
            snapshots.put(jobId, new JobSnapshot(state, processed, added, duplicates, failed));
        }
        @Override public JobSnapshot snapshot(String jobId) {
            JobSnapshot found = snapshots.get(jobId);
            return found == null ? new JobSnapshot(JobState.PENDING, 0, 0, 0, 0) : found;
        }
        @Override public void delete(String jobId) { stores.remove(jobId); snapshots.remove(jobId); }
    }

    /** SHA-256 工具：统一 hex 格式，消除 byte[] 与 hex 两种表示的混乱。 */
    public static String hashHex(java.io.InputStream in) throws IOException {
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256 不可用", e); }
        byte[] buffer = new byte[32768];
        int n;
        while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        return toHex(digest.digest());
    }

    public static String hashHex(byte[] bytes) {
        try { return hashHex(new java.io.ByteArrayInputStream(bytes)); }
        catch (IOException impossible) { return ""; }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        final char[] hex = "0123456789abcdef".toCharArray();
        for (byte b : bytes) { sb.append(hex[(b >> 4) & 0xF]); sb.append(hex[b & 0xF]); }
        return sb.toString();
    }
}
