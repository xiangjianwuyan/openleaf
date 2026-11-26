package org.openleaf.reader.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 1B 媒体源视频落盘的轻量索引编解码（纯 JVM、无 android import）。
 *
 * 设计约束：
 * - 索引 key 只由 sourceId|itemId 决定，绝不使用 stream URL（URL 常带一次性签名，用它做键永远命中不了）。
 * - 索引值只存相对路径；读取时由 {@link #inside(File, File)} 做 canonical 校验，挡住 ../ 与符号链接穿越。
 * - 编码用十六进制而非 org.json，保证 core 不依赖 Android SDK，且 core-tests 可直接跑。
 */
public final class MediaDownloads {
    private MediaDownloads() { }

    /** SharedPreferences 文件名。 */
    public static final String PREFS = "media_dl_index";

    /** 一条已下载视频索引。 */
    public static final class Entry {
        public final String relPath;
        public final String title;
        public final long bytes;

        public Entry(String relPath, String title, long bytes) {
            this.relPath = relPath == null ? "" : relPath;
            this.title = title == null ? "" : title;
            this.bytes = bytes < 0 ? 0 : bytes;
        }
    }

    /**
     * 稳定键：SHA-256(sourceId + "|" + itemId) 的 64 位小写十六进制。
     * 同一个源条目在任意时刻拿到的 stream URL 可以不同，键始终不变。
     */
    public static String key(String sourceId, String itemId) {
        String raw = (sourceId == null ? "" : sourceId) + "|" + (itemId == null ? "" : itemId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return hex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 是 Java 平台强制要求的算法；理论上不可达，保留确定性降级避免抛错到 UI 线程。
            return hex(raw.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 编码为 hex(relPath)|hex(title)|bytes，分隔符与十六进制字符集互斥，可无损往返。
     */
    public static String encode(Entry entry) {
        if (entry == null) return "";
        return hex(entry.relPath.getBytes(StandardCharsets.UTF_8)) + "|"
                + hex(entry.title.getBytes(StandardCharsets.UTF_8)) + "|"
                + entry.bytes;
    }

    /**
     * 解码；null、空串、缺段、非数字长度、非法相对路径一律返回 null（让调用方自愈删除）。
     */
    public static Entry decode(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 3) return null;
        try {
            String rel = new String(unhex(parts[0]), StandardCharsets.UTF_8);
            String title = new String(unhex(parts[1]), StandardCharsets.UTF_8);
            long bytes = Long.parseLong(parts[2]);
            if (!safeRelative(rel) || bytes < 0) return null;
            return new Entry(rel, title, bytes);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * 相对路径安全校验：拒绝空、绝对路径、盘符、反斜杠、空段、"." 与 ".."。
     * 只做字符串层校验；符号链接由 {@link #inside(File, File)} 兜底。
     */
    public static boolean safeRelative(String rel) {
        if (rel == null || rel.isEmpty()) return false;
        if (rel.startsWith("/") || rel.startsWith("\\") || rel.indexOf(':') >= 0 || rel.indexOf('\\') >= 0)
            return false;
        for (String segment : rel.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    /**
     * canonical 路径必须等于 base 或位于 base 之内（解析符号链接后判定）。
     */
    public static boolean inside(File base, File target) {
        if (base == null || target == null) return false;
        try {
            String root = base.getCanonicalPath();
            String path = target.getCanonicalPath();
            return path.equals(root) || path.startsWith(root + File.separator);
        } catch (IOException broken) {
            return false;
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = Character.forDigit(v >>> 4, 16);
            out[i * 2 + 1] = Character.forDigit(v & 0x0F, 16);
        }
        return new String(out);
    }

    private static byte[] unhex(String value) {
        if (value == null || (value.length() & 1) != 0) throw new IllegalArgumentException("odd hex");
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16);
            int lo = Character.digit(value.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
