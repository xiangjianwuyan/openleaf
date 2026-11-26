package org.openleaf.reader.core;

import java.util.Locale;

/**
 * 下载策略：纯 JVM 的校验、配额、Range 与命名安全判定。
 * Android 层只负责执行 HTTP 与文件 I/O，策略在此可独立测试。
 */
public final class DownloadPolicy {
    private DownloadPolicy() { }

    public static final long UNKNOWN_LENGTH = -1L;

    /** 配额判定：任一维度超限都拒绝；usableSpace<0 表示未知，不据此拒绝。 */
    public static boolean quotaAllows(long usedBytes, long incoming, long budget, long usableSpace) {
        if (usedBytes < 0 || incoming < 0 || budget < 0) return false;
        if (incoming > budget - Math.max(0, usedBytes)) return false;
        return usableSpace < 0 || incoming <= usableSpace;
    }

    /** Range 续传判定：仅当服务端明确 206 且 Content-Range 与本地起点一致时续传。 */
    public static RangeDecision rangeDecision(int statusCode, String contentRange,
                                              long localBytes, long contentLength) {
        if (statusCode == 200)
            return new RangeDecision(0, false, "服务端未确认 Range，从头下载");
        if (statusCode != 206 || contentRange == null || !contentRange.toLowerCase(Locale.ROOT).startsWith("bytes "))
            return new RangeDecision(0, false, "无有效 206/Content-Range，从头下载");
        String range = contentRange.substring(6).trim();
        int slash = range.indexOf('/');
        if (slash <= 0) return new RangeDecision(0, false, "Content-Range 格式错误");
        String startText = range.substring(0, slash).trim();
        int dash = startText.indexOf('-');
        if (dash <= 0) return new RangeDecision(0, false, "Content-Range 缺起始偏移");
        try {
            long start = Long.parseLong(startText.substring(0, dash));
            if (start != localBytes || start < 0) return new RangeDecision(0, false, "Range 起点与本地不一致，从头下载");
            long announcedTotal = UNKNOWN_LENGTH;
            String totalText = range.substring(slash + 1).trim();
            if (!"*".equals(totalText)) announcedTotal = Long.parseLong(totalText);
            if (announcedTotal != UNKNOWN_LENGTH && contentLength != UNKNOWN_LENGTH
                    && announcedTotal != contentLength)
                return new RangeDecision(0, false, "Content-Range 总数与 Content-Length 不一致，从头下载");
            return new RangeDecision(start, true, "确认续传自 " + start);
        } catch (NumberFormatException invalid) {
            return new RangeDecision(0, false, "Content-Range 数字非法，从头下载");
        }
    }

    /** 无需续传的判定结果（供跨包调用方使用）。 */
    public static RangeDecision noRange(String reason) { return new RangeDecision(0, false, reason); }

    public static final class RangeDecision {
        public final long from;
        public final boolean resume;
        public final String reason;
        RangeDecision(long from, boolean resume, String reason) {
            this.from = from;
            this.resume = resume;
            this.reason = reason;
        }
    }

    /**
     * 资源标识一致性：仅当本地记录的标识与当前响应标识一致时才允许续传。
     * 任一侧缺失即视为"不可验证"，拒绝续传（宁可重下，不可拼接不同版本的内容）。
     */
    public static boolean identityMatches(String recordedEtag, String responseEtag,
                                          String recordedLastModified, String responseLastModified) {
        if (recordedEtag != null && !recordedEtag.trim().isEmpty()
                && responseEtag != null && !responseEtag.trim().isEmpty())
            return recordedEtag.trim().equals(responseEtag.trim());
        if (recordedLastModified != null && !recordedLastModified.trim().isEmpty()
                && responseLastModified != null && !responseLastModified.trim().isEmpty())
            return recordedLastModified.trim().equals(responseLastModified.trim());
        return false; // 无可验证标识，拒绝续传
    }

    /** 断点元数据是否可用于续传：本地字节数为正且不超预算，且资源标识存在。 */
    public static boolean resumeEligible(long localBytes, long budget,
                                         String recordedEtag, String recordedLastModified) {
        if (localBytes <= 0 || budget <= 0 || localBytes > budget) return false;
        boolean hasIdentity = (recordedEtag != null && !recordedEtag.trim().isEmpty())
                || (recordedLastModified != null && !recordedLastModified.trim().isEmpty());
        return hasIdentity;
    }

    /** 文件名安全化：去控制字符/路径分隔符，限制长度，避免路径穿越。 */
    public static String safeFileName(String raw, int maxLength) {
        if (raw == null) return "download";
        String name = raw.replace('\\', '_').replace('/', '_');
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|')
                out.append('_');
            else out.append(c);
        }
        String result = out.toString().trim();
        if (result.isEmpty() || result.equals(".") || result.equals("..")) result = "download";
        int limit = maxLength <= 0 ? 80 : maxLength;
        return result.length() > limit ? result.substring(0, limit) : result;
    }

    /** 校验实际下载字节的指纹是否与预期一致。 */
    public static boolean hashMatches(String expectedHex, byte[] bytes) {
        if (expectedHex == null || !expectedHex.matches("(?i)[0-9a-f]{64}")) return false;
        return expectedHex.equalsIgnoreCase(ContentContracts.hashHex(bytes));
    }
}
