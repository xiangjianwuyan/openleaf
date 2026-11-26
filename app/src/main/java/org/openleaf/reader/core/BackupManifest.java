package org.openleaf.reader.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 可迁移书库备份清单（纯 JVM，无 Android 依赖）。
 *
 * 借鉴 LightRead/Venera/Anx 的既有调研结论，由 OpenLeaf 自行实现。核心口径：
 * 1. 版本化清单：schemaVersion 显式，未知高版本拒绝恢复，不做静默降级。
 * 2. 逐项 SHA-256：每条文件/数据载荷带内容哈希与字节数，恢复前全量校验。
 * 3. 三类同步能力严格拆分：library(书库数据) / archive(书文件归档) / catalog(在线目录)。
 * 4. 设备字段永不进便携备份：白名单收窄，黑名单兜底。
 * 5. 内容指纹脏检测：逻辑行哈希（忽略 WAL/页布局），用于判断"内容是否真正变化"。
 */
public final class BackupManifest {
    private BackupManifest() { }

    public static final int SCHEMA_VERSION = 6;

    /** 三类能力严格拆分，恢复时按用户勾选单独处理。 */
    public enum Part { LIBRARY, ARCHIVE, CATALOG }

    /** 条目类型：file=二进制书文件/封面，data=JSON 数据段。 */
    public enum EntryKind { FILE, DATA }

    /** 可迁移数据段（表/偏好键集合）白名单——不在此列表的一律不备份。 */
    public static final String[] DATA_SECTIONS = {
            "books", "book_metadata", "book_tags", "book_reviews", "book_folders",
            "folders", "annotations", "ideas", "reading_time", "reading_sessions",
            "media_sources", "media_time", "media_items", "asset_revisions", "online_books",
            "reader_prefs"
    };

    /**
     * 设备本地字段黑名单（表名.列名）——即使出现在数据段也必须剥离。
     * SAF URI、本机授权、绝对路径、事务日志均不可跨设备。
     */
    public static final String[] DEVICE_ONLY_FIELDS = {
            "book_references.source_uri", "book_sources.source_key", "book_sources.source_uri",
            "folders.source_tree", "folders.relative_path",
            "jobs.source_tree", "job_items.uri",
            "pending_writes.target_uri", "pending_writes.document_key",
            "chapter_cache.cache_path", "asset_revisions.source_uri"
    };

    /**
     * 需要重映射的主键/外键列名（按列名匹配，不限表）。恢复时按 id 映射表替换为目标 id。
     * 与"排除字段"严格区分：这些字段必须保留其值用于关系重建，不能直接丢弃。
     */
    public static final String[] REMAP_ID_COLUMNS = {
            "id", "book_id", "folder_id", "parent_id"
    };

    /**
     * 需要重新生成的设备内文件名。导出时写备份条目引用，导入时重新落盘并生成新名。
     */
    public static final String[] REGENERATE_FIELDS = {
            "books.file", "book_metadata.cover_name"
    };

    /** 恢复时整表排除（设备 URI、事务日志、迁移标记，不参与跨设备语义）。 */
    public static final Set<String> DEVICE_ONLY_TABLES = setOf(
            "book_references", "book_sources", "jobs", "job_items",
            "pending_writes", "chapter_cache", "library_meta");

    /** 列名是否是需要重映射的主键/外键。 */
    public static boolean isRemapColumn(String column) {
        if (column == null) return false;
        for (String c : REMAP_ID_COLUMNS) if (c.equalsIgnoreCase(column)) return true;
        return false;
    }

    /** 字段是否是"重新生成"的设备内文件名。 */
    public static boolean isRegenerateField(String qualifiedField) {
        if (qualifiedField == null) return false;
        for (String f : REGENERATE_FIELDS) if (f.equalsIgnoreCase(qualifiedField)) return true;
        return false;
    }

    /** 清单条目：路径 + 类型 + SHA-256 + 字节数。 */
    public static final class Entry {
        public final String path, sha256, part;
        public final EntryKind kind;
        public final long size;
        public Entry(String path, EntryKind kind, String sha256, long size, String part) {
            this.path = path; this.kind = kind; this.sha256 = sha256; this.size = size; this.part = part;
        }
    }

    /** 校验结果：逐条错误，不因单条失败中断整体报告。 */
    public static final class Issue {
        public final String path, message;
        public final boolean fatal;
        public Issue(String path, String message, boolean fatal) {
            this.path = path; this.message = message; this.fatal = fatal;
        }
        public String toString() { return (fatal ? "错误 " : "提示 ") + path + "：" + message; }
    }

    /**
     * 校验清单结构：版本可识别、条目路径唯一且无穿越、哈希格式合法、字节数非负、part 合法。
     * 返回问题列表，空列表表示结构合法。这是"恢复前先校验载荷"的第一道门。
     */
    public static List<Issue> validateStructure(int schemaVersion, List<Entry> entries) {
        List<Issue> issues = new ArrayList<>();
        if (schemaVersion <= 0 || schemaVersion > SCHEMA_VERSION)
            issues.add(new Issue("manifest", "不支持的清单版本 " + schemaVersion
                    + "（支持 1.." + SCHEMA_VERSION + "）", true));
        if (entries == null) {
            issues.add(new Issue("manifest", "条目列表为空", true));
            return issues;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (Entry e : entries) {
            if (e == null) { issues.add(new Issue("manifest", "存在空条目", true)); continue; }
            if (e.path == null || e.path.trim().isEmpty())
                issues.add(new Issue(String.valueOf(e.path), "条目路径为空", true));
            else {
                if (!safePath(e.path))
                    issues.add(new Issue(e.path, "路径含穿越或绝对路径", true));
                if (!seen.add(e.path))
                    issues.add(new Issue(e.path, "条目路径重复", true));
            }
            if (e.sha256 == null || !e.sha256.matches("(?i)[0-9a-f]{64}"))
                issues.add(new Issue(String.valueOf(e.path), "SHA-256 格式非法", true));
            if (e.size < 0)
                issues.add(new Issue(String.valueOf(e.path), "字节数为负", true));
            if (e.kind == null)
                issues.add(new Issue(String.valueOf(e.path), "条目类型缺失", true));
            if (e.part == null || !isPart(e.part))
                issues.add(new Issue(String.valueOf(e.path), "能力分类非法：" + e.part, true));
        }
        return issues;
    }

    /** 校验一条载荷的实际哈希与大小；不符即为致命错误。 */
    public static Issue verifyPayload(Entry entry, String actualSha256, long actualSize) {
        if (entry == null) return new Issue("manifest", "条目缺失", true);
        if (!entry.sha256.equalsIgnoreCase(actualSha256 == null ? "" : actualSha256))
            return new Issue(entry.path, "内容哈希不符（期望 " + shortHash(entry.sha256)
                    + "，实际 " + shortHash(actualSha256) + "）", true);
        if (entry.size != actualSize)
            return new Issue(entry.path, "字节数不符（期望 " + entry.size + "，实际 " + actualSize + "）", true);
        return null;
    }

    /**
     * 计算 ID 重映射：把源 bookId 映射到目标 bookId。
     * 目标已存在（sha256 去重命中）时复用目标 id，否则生成新 id；未出现的源 id 保持原值。
     */
    public static Map<String, String> remapBookIds(List<String> sourceBookIds,
                                                    Map<String, String> existingByHash,
                                                    Map<String, String> sourceHashById,
                                                    IdFactory ids) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (sourceBookIds == null) return mapping;
        for (String sourceId : sourceBookIds) {
            if (sourceId == null || sourceId.isEmpty()) continue;
            String hash = sourceHashById == null ? null : sourceHashById.get(sourceId);
            String target = hash == null || existingByHash == null ? null : existingByHash.get(hash);
            if (target == null || target.isEmpty()) target = ids == null ? sourceId : ids.newId();
            mapping.put(sourceId, target);
        }
        return mapping;
    }

    public interface IdFactory { String newId(); }

    /** 设备字段是否是"排除"而非"重映射"：这些列不应写进恢复后的库。 */
    public static boolean isDeviceOnly(String qualifiedField) {
        if (qualifiedField == null) return false;
        for (String field : DEVICE_ONLY_FIELDS)
            if (field.equalsIgnoreCase(qualifiedField)) return true;
        return false;
    }

    public static boolean isDeviceOnlyTable(String table) {
        return table != null && DEVICE_ONLY_TABLES.contains(table.toLowerCase(Locale.ROOT));
    }

    public static boolean isPart(String part) {
        if (part == null) return false;
        for (Part p : Part.values()) if (p.name().equalsIgnoreCase(part)) return true;
        return false;
    }

    /**
     * 内容指纹：按逻辑行规范化后哈希，忽略换行风格（CRLF/LF）与行尾空白，
     * 用于脏检测——只有文本语义变化才算"内容变了"，避免 WAL/页布局或换行差异误报。
     */
    public static String contentFingerprint(String text) {
        if (text == null) text = "";
        StringBuilder normalized = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            normalized.append(rtrim(line)).append('\n');
        }
        return ContentContracts.hashHex(normalized.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String rtrim(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    /** 清单路径安全：拒绝绝对路径、盘符、.. 穿越、反斜杠。 */
    public static boolean safePath(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        String value = path.replace('\\', '/');
        if (value.startsWith("/") || value.contains(":") || value.contains("..")) return false;
        for (String segment : value.split("/"))
            if (segment.equals(".") || segment.isEmpty()) return false;
        return true;
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.length() < 12) return String.valueOf(hash);
        return hash.substring(0, 12) + "…";
    }

    private static Set<String> setOf(String... values) {
        Set<String> set = new java.util.LinkedHashSet<>();
        Collections.addAll(set, values);
        return set;
    }
}
