package org.openleaf.reader.core;

/**
 * pending_write 中断收敛：纯 JVM 状态机。
 * SAF 写入与 SQLite 无法原子提交，进程可能死在"文件已改、版本未登记"之间。
 * 给定写前记录的意图指纹与冷启动重读到的实际指纹，决定如何收敛。
 */
public final class PendingWriteReconciler {
    private PendingWriteReconciler() { }

    public enum Decision {
        /** 记录缺失：无事可做。 */
        NOTHING,
        /** 文件尚未写入（仍是旧内容）：可安全放弃记录。 */
        NOT_WRITTEN,
        /** 文件已是意图内容：补登记版本后清除记录。 */
        COMMIT_VERSION,
        /** 文件是第三种内容：冲突，需用户选择（重新授权/选回读目标/恢复副本）。 */
        CONFLICT,
        /** 记录本身不合法：丢弃。 */
        INVALID
    }

    public static final class PendingWrite {
        public final String targetUri;
        public final String expectedOldSha;
        public final String intendedSha;
        public final long size;
        public final String bookId;

        public PendingWrite(String targetUri, String expectedOldSha, String intendedSha,
                            long size, String bookId) {
            this.targetUri = targetUri;
            this.expectedOldSha = expectedOldSha;
            this.intendedSha = intendedSha;
            this.size = size;
            this.bookId = bookId;
        }
    }

    public static final class Resolution {
        public final Decision decision;
        public final String detail;

        Resolution(Decision decision, String detail) {
            this.decision = decision;
            this.detail = detail;
        }

        public boolean needsUserAction() { return decision == Decision.CONFLICT; }
    }

    /** observedSha 为冷启动时重读 target 得到的当前指纹（hex）。 */
    public static Resolution resolve(PendingWrite pending, String observedSha) {
        if (pending == null) return new Resolution(Decision.NOTHING, "没有待处理写入");
        if (!validHex(pending.intendedSha) || (pending.expectedOldSha != null
                && !pending.expectedOldSha.isEmpty() && !validHex(pending.expectedOldSha)))
            return new Resolution(Decision.INVALID, "待写入记录的指纹格式不合法");
        if (observedSha == null || !validHex(observedSha))
            return new Resolution(Decision.NOT_WRITTEN, "无法读取目标文件，写入未生效");

        if (eq(observedSha, pending.intendedSha))
            return new Resolution(Decision.COMMIT_VERSION, "目标已是意图内容，补登记版本");
        if (pending.expectedOldSha != null && eq(observedSha, pending.expectedOldSha))
            return new Resolution(Decision.NOT_WRITTEN, "目标仍是写入前内容，可放弃记录");
        return new Resolution(Decision.CONFLICT,
                "目标内容既不是写入前也不是意图内容，需用户确认如何处理");
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static boolean validHex(String sha) {
        return sha != null && sha.length() == 64 && sha.matches("(?i)[0-9a-f]{64}");
    }
}
