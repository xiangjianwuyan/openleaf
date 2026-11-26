package org.openleaf.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openleaf.reader.core.BackupManifest;
import org.openleaf.reader.core.ContentContracts;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 可迁移书库备份恢复。
 *
 * 两阶段：
 * 1. 预检（preflight）：读 manifest → 结构校验 → 逐条 SHA-256/字节数校验 → 生成 ID 重映射方案。
 *    预检只读不写，任何致命问题都返回错误，绝不部分恢复。
 * 2. 提交（commit）：在单事务内按依赖顺序写入白名单表 + 落盘书文件 + 恢复进度锚点；
 *    sha256 去重命中复用目标 book id，标注/进度/想法全部改指目标 id。
 */
public final class LibraryRestore {
    private static final String MANIFEST = "manifest.json";
    private static final String PREFS_READER = "reader";
    private static final int MAX_ENTRY_BYTES = 512 * 1024 * 1024;
    private static final int MAX_FILES = 5000;

    private final Context context;
    private final LibraryDatabase database;
    private final RestoreProgress progress;

    public interface RestoreProgress { void onProgress(String stage); }

    public LibraryRestore(Context context, RestoreProgress progress) {
        this.context = context.getApplicationContext();
        this.database = LibraryDatabase.get(this.context);
        this.progress = progress;
    }

    /** 预检结果：清单条目 + 重映射方案 + 问题列表。 */
    public static final class Preflight {
        public final JSONObject manifest;
        public final List<BackupManifest.Entry> entries;
        public final Map<String, String> bookIdMap;
        public final Map<String, String> folderIdMap;
        public final List<BackupManifest.Issue> issues;
        public final Map<String, byte[]> payloads;
        Preflight(JSONObject manifest, List<BackupManifest.Entry> entries,
                  Map<String, String> bookIdMap, Map<String, String> folderIdMap,
                  List<BackupManifest.Issue> issues, Map<String, byte[]> payloads) {
            this.manifest = manifest; this.entries = entries; this.bookIdMap = bookIdMap;
            this.folderIdMap = folderIdMap; this.issues = issues; this.payloads = payloads;
        }
        public boolean ok() {
            for (BackupManifest.Issue i : issues) if (i.fatal) return false;
            return true;
        }
    }

    /** 只读取证 + 校验 + 规划重映射，不修改任何数据。 */
    public Preflight preflight(InputStream in) throws Exception {
        Map<String, byte[]> buffers = readZip(in);
        byte[] manifestBytes = buffers.get(MANIFEST);
        if (manifestBytes == null) throw new IOException("备份包缺少 manifest.json");
        JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        int version = manifest.optInt("schemaVersion", -1);
        JSONArray array = manifest.optJSONArray("entries");
        List<BackupManifest.Entry> entries = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                entries.add(new BackupManifest.Entry(
                        item.optString("path", ""),
                        parseKind(item.optString("kind", "")),
                        item.optString("sha256", ""),
                        item.optLong("size", -1),
                        item.optString("part", "")));
            }
        }
        List<BackupManifest.Issue> issues = BackupManifest.validateStructure(version, entries);

        // 逐条校验实际载荷（哈希 + 字节数）
        for (BackupManifest.Entry e : entries) {
            byte[] actual = buffers.get(e.path);
            if (actual == null) {
                issues.add(new BackupManifest.Issue(e.path, "备份包缺少该条目", true));
                continue;
            }
            String hash = ContentContracts.hashHex(actual);
            BackupManifest.Issue bad = BackupManifest.verifyPayload(e, hash, actual.length);
            if (bad != null) issues.add(bad);
        }

        // 规划重映射：books.id 与 folders.id
        Map<String, String> bookMap = planIdMap(buffers.get("data/books.json"), "books", "books");
        Map<String, String> folderMap = planIdMap(buffers.get("data/folders.json"), "folders", "folders");
        return new Preflight(manifest, entries, bookMap, folderMap, issues, buffers);
    }

    /**
     * 判断 reading_time 行的 book_id（经重映射后）在目标 books 表中是否存在。
     * 用于过滤旧备份里"书已删但统计行残留"的悬挂引用，避免 FK 787 让整次恢复回滚。
     */
    private static boolean bookExistsAfterRemap(SQLiteDatabase db, Preflight plan, JSONObject row) {
        String sourceId = row.optString("book_id", "");
        if (sourceId.isEmpty()) return false;
        String targetId = plan.bookIdMap.get(sourceId);
        if (targetId == null) targetId = sourceId;
        try (Cursor c = db.query("books", new String[]{"id"}, "id=?", new String[]{targetId},
                null, null, null, "1")) {
            return c.moveToFirst();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 提交恢复：调用方须先 preflight 且 ok()==true。返回恢复的书籍数。 */
    public int commit(Preflight plan) throws Exception {
        SQLiteDatabase db = database.getWritableDatabase();
        int restoredBooks = 0;
        db.beginTransaction();
        try {
            // 1) 书文件落盘：把备份的 files/<sourceId> 写到新 UUID 文件，books.file 重映射
            //    父目录可能不存在（全新安装/清理数据后），必须先创建，否则 FileOutputStream 抛 ENOENT
            File bookDir = new File(context.getFilesDir(), "books");
            if (!bookDir.isDirectory() && !bookDir.mkdirs())
                throw new IOException("无法创建书文件目录：" + bookDir);
            Map<String, String> newFileNames = new LinkedHashMap<>();
            for (BackupManifest.Entry e : plan.entries) {
                if (e.kind != BackupManifest.EntryKind.FILE || !e.path.startsWith("files/")) continue;
                // archiveName 形如 "cad51e17-….epub"（含扩展名）；sourceBookId 是不含扩展名的 bookId，
                // 才是 bookIdMap 与 books.id 的键。直接拿含扩展名的名字当 id，会导致映射查不到、
                // 文件名被拼成双扩展名，恢复后书全部打不开。
                String archiveName = e.path.substring("files/".length());
                String sourceBookId = stripExtension(archiveName);
                String targetBookId = plan.bookIdMap.get(sourceBookId);
                if (targetBookId == null) targetBookId = sourceBookId;
                byte[] bytes = plan.payloads.get(e.path);
                if (bytes == null) throw new IOException("书文件载荷缺失：" + e.path);
                String fileName = targetBookId + extensionOf(archiveName);
                File dest = new File(bookDir, fileName);
                try (FileOutputStream out = new FileOutputStream(dest)) { out.write(bytes); }
                newFileNames.put(sourceBookId, fileName);
            }

            // 非书媒体的 id 是确定性哈希，不参与 books.id 重映射；但下面的会话覆盖判定
            // 必须认得它们，否则恢复后本地同媒体同日的会话不会被替换，分列会重复累加。
            Set<String> mediaIds = new HashSet<>();
            byte[] mediaPayload = plan.payloads.get("data/media_sources.json");
            if (mediaPayload != null) {
                JSONArray mediaRows = new JSONObject(new String(mediaPayload, StandardCharsets.UTF_8))
                        .optJSONArray("rows");
                if (mediaRows != null) {
                    for (int i = 0; i < mediaRows.length(); i++) {
                        JSONObject row = mediaRows.optJSONObject(i);
                        String mediaId = row == null ? "" : row.optString("id", "");
                        if (!mediaId.isEmpty()) mediaIds.add(mediaId);
                    }
                }
            }

            // 2) 数据段按依赖顺序写入（拓扑序：被引用表必须在前）
            //    folders 必须先于 book_folders：后者有 folder_id REFERENCES folders(id)，
            //    顺序颠倒会在恢复时直接 FOREIGN KEY constraint failed。
            String[] order = {"books", "folders", "book_metadata", "book_tags", "book_reviews",
                    "book_folders", "annotations", "ideas", "reading_time", "reading_sessions",
                    "media_sources", "media_time", "media_items", "asset_revisions", "online_books", "reader_prefs"};
            for (String table : order) {
                byte[] payload = plan.payloads.get("data/" + table + ".json");
                if (payload == null) continue;
                stage("正在恢复 " + table);
                JSONObject section = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                JSONArray rows = section.optJSONArray("rows");
                if (rows == null) continue;
                // 阅读位置在 prefs 里而不是数据库表，单独按重映射后的书 id 写回
                if ("reader_prefs".equals(table)) { restoreReaderPrefs(rows, plan); continue; }
                // folders.parent_id 自引用：父先子后，避免自引用外键失败
                List<JSONObject> sequence = "folders".equals(table) ? orderByParent(rows) : null;
                if (sequence != null) {
                    for (JSONObject row : sequence) writeRow(db, table, row, plan, newFileNames);
                    continue;
                }
                // reading_time 是 (book_id,day) 整行 REPLACE，reading_sessions 若无差别并集，
                // 合并式恢复到「已读过同一本书」的设备上会让分列合计大于总时长。
                // 先把备份覆盖到的 (book_id, day) 的本地会话清掉，两张表在同样这些日子上保持一致。
                if ("reading_sessions".equals(table)) purgeOverlappingSessions(db, rows, plan, mediaIds);
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    // 修复既有备份恢复 787：reading_time.book_id 有 REFERENCES books(id)，
                    // 旧备份可能残留"书已删但统计行仍在"的悬挂 FK。跳过这些行并给用户可见提示，
                    // 否则 FOREIGN KEY 约束让整次恢复回滚（v5/v6 均如此）。
                    if ("reading_time".equals(table) && !bookExistsAfterRemap(db, plan, row)) {
                        plan.issues.add(new BackupManifest.Issue("data/reading_time.json",
                                "跳过统计行 " + row.optString("book_id", "")
                                        + "：对应书籍不在备份中（悬挂引用）", false));
                        continue;
                    }
                    writeRow(db, table, row, plan, newFileNames);
                    if ("books".equals(table)) restoredBooks++;
                }
            }

            // 3) 进度锚点：按 bookIdMap 迁移 per-book 键
            restoreProgress(plan.bookIdMap);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return restoredBooks;
    }

    /**
     * 文件夹按 parent_id 排序：父先子后，避免自引用外键（folders.parent_id REFERENCES folders.id）失败。
     * 遇到父缺失或循环引用时，剩余行原样追加，交由外键约束如实报错，不静默丢数据。
     */
    private static List<JSONObject> orderByParent(JSONArray rows) throws Exception {
        List<JSONObject> pending = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) pending.add(rows.getJSONObject(i));
        List<JSONObject> result = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        while (!pending.isEmpty()) {
            List<JSONObject> next = new ArrayList<>();
            for (JSONObject row : pending) {
                String parent = row.isNull("parent_id") ? null : row.optString("parent_id", null);
                String id = row.optString("id", "");
                boolean ready = parent == null || parent.isEmpty() || placed.contains(parent);
                if (ready) { result.add(row); placed.add(id); }
                else next.add(row);
            }
            if (next.size() == pending.size()) { result.addAll(next); break; } // 无进展：父缺失/循环
            pending = next;
        }
        return result;
    }

    /** 写单行：重映射 id、重新生成文件名/封面名、剥离设备字段。 */
    private void writeRow(SQLiteDatabase db, String table, JSONObject row,
                          Preflight plan, Map<String, String> newFileNames) throws Exception {
        android.content.ContentValues values = new android.content.ContentValues();
        java.util.Iterator<String> keys = row.keys();
        while (keys.hasNext()) {
            String column = keys.next();
            Object value = row.opt(column);
            String qualified = table + "." + column;
            if (BackupManifest.isDeviceOnly(qualified)) continue;
            if (BackupManifest.isRegenerateField(qualified)) {
                if ("books.file".equalsIgnoreCase(qualified)) {
                    // sourceId 需要从同行的 id 取，已在下方统一处理
                    continue;
                }
                if ("book_metadata.cover_name".equalsIgnoreCase(qualified)) continue;
            }
            if (BackupManifest.isRemapColumn(column) && value != JSONObject.NULL) {
                String idValue = String.valueOf(value);
                if ("id".equalsIgnoreCase(column) && "books".equals(table)) {
                    String target = plan.bookIdMap.get(idValue);
                    values.put(column, target == null ? idValue : target);
                } else if ("id".equalsIgnoreCase(column) && "folders".equals(table)) {
                    String target = plan.folderIdMap.get(idValue);
                    values.put(column, target == null ? idValue : target);
                } else if ("book_id".equalsIgnoreCase(column)) {
                    String target = plan.bookIdMap.get(idValue);
                    values.put(column, target == null ? idValue : target);
                } else if ("folder_id".equalsIgnoreCase(column) || "parent_id".equalsIgnoreCase(column)) {
                    String target = plan.folderIdMap.get(idValue);
                    values.put(column, target == null ? idValue : target);
                } else {
                    putValue(values, column, value);
                }
            } else {
                putValue(values, column, value);
            }
        }
        // books.file：用源 id 找回重映射后的文件名
        if ("books".equals(table)) {
            String sourceId = row.optString("id", "");
            String fileName = newFileNames.get(sourceId);
            if (fileName != null) values.put("file", fileName);
            else if (!values.containsKey("file")) values.put("file", sourceId + ".txt");
            // order_index 无默认值，缺失时显式赋值
            if (!values.containsKey("order_index"))
                values.put("order_index", firstOrderIndex(db) - 1);
        }
        // folders.source_tree / relative_path：导出时按设备字段剥除（SAF 授权不可迁移），
        // 但表定义为 NOT NULL 且 UNIQUE(source_tree, relative_path)。沿用 BookStore 手工文件夹约定
        // manual:<uuid> + 空 relative_path，保证非空且组合唯一；恢复后这些文件夹视为"未关联目录"。
        if ("folders".equals(table)) {
            if (!values.containsKey("source_tree") || values.getAsString("source_tree") == null)
                values.put("source_tree", "manual:" + UUID.randomUUID());
            if (!values.containsKey("relative_path") || values.getAsString("relative_path") == null)
                values.put("relative_path", "");
        }
        // 冲突时替换（同一备份重复恢复幂等）
        db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * 让 reading_sessions 与 reading_time 在「同一本书同一天」上使用同一套覆盖语义。
     *
     * <p>reading_time 的主键是 (book_id,day) 且恢复时整行 REPLACE，而 reading_sessions 的
     * event_key 含源书 id、恢复后不重算，无差别写入会与本地会话并集，导致合并式恢复后
     * 「分列合计 &gt; 总时长」。这里先按备份覆盖到的 (book_id,day) 删掉本地会话，随后正常写入，
     * 于是：备份覆盖到的日子两表都以备份为准，未覆盖的日子两表都保留本地值。
     *
     * <p>只删不插；重复恢复同一份备份仍然幂等。
     */
    private static void purgeOverlappingSessions(SQLiteDatabase db, JSONArray rows,
                                                 Preflight plan, Set<String> mediaIds) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String sourceBook = row.optString("book_id", "");
            String day = row.optString("day", "");
            if (sourceBook.isEmpty() || day.isEmpty()) continue;
            String targetBook = plan.bookIdMap.get(sourceBook);
            if (targetBook == null && mediaIds.contains(sourceBook)) {
                // 非书媒体的 id 跨设备稳定，不需要映射，但同样要按 (id,day) 覆盖本地会话
                targetBook = sourceBook;
            }
            // 仍映射不到说明这本书不在本次备份里：与 writeRow 口径一致保留源 id，不做删除
            if (targetBook == null) continue;
            if (!seen.add(targetBook + '\0' + day)) continue;
            db.delete("reading_sessions", "book_id=? AND day=?", new String[]{targetBook, day});
        }
    }

    private static void putValue(android.content.ContentValues values, String column, Object value) {
        if (value == JSONObject.NULL || value == null) { values.putNull(column); return; }
        if (value instanceof Number) {
            if (value instanceof Double || value instanceof Float) values.put(column, ((Number) value).doubleValue());
            else values.put(column, ((Number) value).longValue());
        } else if (value instanceof Boolean) {
            values.put(column, (Boolean) value ? 1 : 0);
        } else {
            values.put(column, String.valueOf(value));
        }
    }

    /** 规划 id 映射：复用 sha256 去重命中的目标 id，否则生成新 id。 */
    private Map<String, String> planIdMap(byte[] payload, String table, String idColumn) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (payload == null) return mapping;
        try {
            JSONObject section = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            JSONArray rows = section.optJSONArray("rows");
            if (rows == null) return mapping;
            SQLiteDatabase db = database.getReadableDatabase();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String sourceId = row.optString(idColumn, "");
                if (sourceId.isEmpty()) continue;
                String target = null;
                if ("books".equals(table)) {
                    String hash = row.optString("sha256", "");
                    if (!hash.isEmpty()) {
                        Cursor c = db.query("books", new String[]{"id"}, "sha256=?",
                                new String[]{hash}, null, null, null);
                        try { if (c.moveToFirst()) target = c.getString(0); } finally { c.close(); }
                    }
                } else if ("folders".equals(table)) {
                    // 文件夹按名字匹配复用（手工文件夹的 source_tree/relative_path 已排除）
                    String name = row.optString("name", "");
                    if (!name.isEmpty()) {
                        Cursor c = db.query("folders", new String[]{"id"}, "name=?",
                                new String[]{name}, null, null, null);
                        try { if (c.moveToFirst()) target = c.getString(0); } finally { c.close(); }
                    }
                }
                mapping.put(sourceId, target == null ? UUID.randomUUID().toString() : target);
            }
        } catch (Exception ignored) { /* 映射规划失败时留空，写库时保底用原 id */ }
        return mapping;
    }

    /**
     * 把备份里的阅读位置写回目标设备，并按 bookIdMap 换成目标书 id。
     * 目标已有同名键时跳过：恢复是追加式合并，不该覆盖用户在这台设备上更新的进度。
     */
    private void restoreReaderPrefs(JSONArray rows, Preflight plan) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_READER, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String suffix = row.optString("suffix", "");
            String sourceBook = row.optString("book_id", "");
            if (suffix.isEmpty() || sourceBook.isEmpty()) continue;
            String targetBook = plan.bookIdMap.get(sourceBook);
            if (targetBook == null) targetBook = sourceBook;
            String key = suffix + targetBook;
            if (prefs.contains(key)) continue;
            String value = row.optString("value", "");
            try {
                switch (row.optString("type", "string")) {
                    case "int": editor.putInt(key, Integer.parseInt(value)); break;
                    case "long": editor.putLong(key, Long.parseLong(value)); break;
                    case "float": editor.putFloat(key, Float.parseFloat(value)); break;
                    case "bool": editor.putBoolean(key, Boolean.parseBoolean(value)); break;
                    default: editor.putString(key, value); break;
                }
            } catch (NumberFormatException invalid) {
                editor.putString(key, value);
            }
        }
        editor.apply();
    }

    /** 迁移 per-book 进度键：把 source_<id> 复制到 source_<target>，值不变。 */
    private void restoreProgress(Map<String, String> bookIdMap) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_READER, Context.MODE_PRIVATE);
        String[] suffixes = {"anchor_", "chapter_", "offset_", "bottom_", "lineFraction_",
                "progress_", "text_layout_version_", "opened_", "pdf_page_", "pdf_count_",
                "epub_chapter_", "epub_scroll_", "epubCfi_"};
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, String> entry : bookIdMap.entrySet()) {
            String sourceId = entry.getKey(), targetId = entry.getValue();
            if (sourceId.equals(targetId)) continue;
            for (String suffix : suffixes) {
                String from = suffix + sourceId, to = suffix + targetId;
                if (!prefs.contains(from) || prefs.contains(to)) continue;
                copyPref(prefs, editor, from, to);
            }
        }
        editor.apply();
    }

    private static void copyPref(SharedPreferences prefs, SharedPreferences.Editor editor, String from, String to) {
        try {
            Object value = prefs.getAll().get(from);
            if (value instanceof String) editor.putString(to, (String) value);
            else if (value instanceof Integer) editor.putInt(to, (Integer) value);
            else if (value instanceof Boolean) editor.putBoolean(to, (Boolean) value);
            else if (value instanceof Float) editor.putFloat(to, (Float) value);
            else if (value instanceof Long) editor.putLong(to, (Long) value);
        } catch (Exception ignored) { }
    }

    private int firstOrderIndex(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT COALESCE(MIN(order_index),0) FROM books", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    private Map<String, byte[]> readZip(InputStream in) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        int count = 0;
        while ((entry = zip.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            if (++count > MAX_FILES) throw new IOException("备份包条目过多");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = zip.read(buf)) > 0) {
                if (out.size() + n > MAX_ENTRY_BYTES) throw new IOException("单个条目超过 512 MiB");
                out.write(buf, 0, n);
            }
            result.put(entry.getName(), out.toByteArray());
            zip.closeEntry();
        }
        return result;
    }

    private static BackupManifest.EntryKind parseKind(String value) {
        try { return BackupManifest.EntryKind.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return null; }
    }

    private String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot) : "";
    }

    /** 去掉扩展名，得到 bookId（备份内文件名为 &lt;bookId&gt;&lt;.ext&gt;）。 */
    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void stage(String stage) { if (progress != null) progress.onProgress(stage); }
}
