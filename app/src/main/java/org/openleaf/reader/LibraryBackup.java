package org.openleaf.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openleaf.reader.core.BackupBookFiles;
import org.openleaf.reader.core.BackupManifest;
import org.openleaf.reader.core.ContentContracts;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 可迁移书库备份导出。
 *
 * 打包内容：
 * - manifest.json：版本 + 逐项 SHA-256/字节数 + 能力分类（结构由 BackupManifest 校验）
 * - data/<table>.json：白名单数据段（设备字段已剥离）
 * - files/<bookId>.<ext>：书文件本体（books.file 由导出侧引用，恢复时重新生成）
 *
 * 严格排除：SAF URI、任务/待写事务、缓存路径、迁移标记（见 BackupManifest.DEVICE_ONLY_TABLES/FIELDS）。
 */
public final class LibraryBackup {
    private static final String MANIFEST = "manifest.json";
    private static final int BUFFER = 64 * 1024;

    /** 参与备份的数据表（顺序即恢复写入顺序，保证外键先行）。 */
    private static final String[] TABLES = {
            "books", "book_metadata", "book_tags", "book_reviews", "book_folders",
            "folders", "annotations", "ideas", "reading_time", "reading_sessions",
            "media_sources", "media_time", "media_items", "asset_revisions", "online_books"
    };

    /** 需要连同书文件一起备份的表（目前仅 books）。 */
    private static final String BOOKS = "books";

    private final Context context;
    private final LibraryDatabase database;
    private final BackupProgress progress;

    /** 本次导出中「库里有记录但磁盘上找不到书文件」的清单（书名 + 期望文件名），供 UI 如实告知。 */
    private final List<String> missingFiles = new ArrayList<>();

    public interface BackupProgress { void onProgress(String stage); }

    public LibraryBackup(Context context, BackupProgress progress) {
        this.context = context.getApplicationContext();
        this.database = LibraryDatabase.get(this.context);
        this.progress = progress;
    }

    /** 导出到输出流；parts 决定包含哪些能力段。返回生成的清单条目数。 */
    public int export(OutputStream out, Set<BackupManifest.Part> parts) throws Exception {
        missingFiles.clear();
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(6);
        List<BackupManifest.Entry> entries = new ArrayList<>();
        Map<String, byte[]> dataPayloads = new LinkedHashMap<>();

        SQLiteDatabase db = database.getReadableDatabase();
        db.beginTransaction();
        try {
            for (String table : TABLES) {
                BackupManifest.Part part = partOf(table);
                if (!parts.contains(part)) continue;
                stage("正在打包 " + table);
                byte[] payload = exportTable(db, table);
                if (payload == null) continue;
                String path = "data/" + table + ".json";
                dataPayloads.put(path, payload);
                entries.add(new BackupManifest.Entry(path, BackupManifest.EntryKind.DATA,
                        sha(payload), payload.length, part.name()));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        // 阅读位置在 SharedPreferences 里，不在任何表中。此前备份完全没有它，
        // 跨设备恢复后只剩「书在、进度不在」——界面却承诺进度会迁移。
        if (parts.contains(BackupManifest.Part.LIBRARY)) {
            byte[] payload = exportReaderPrefs();
            if (payload != null && payload.length > 2) {
                String path = "data/reader_prefs.json";
                dataPayloads.put(path, payload);
                entries.add(new BackupManifest.Entry(path, BackupManifest.EntryKind.DATA,
                        sha(payload), payload.length, BackupManifest.Part.LIBRARY.name()));
            }
        }

        // 书文件本体：跟随 books 段（ARCHIVE 能力）
        if (parts.contains(BackupManifest.Part.ARCHIVE)) {
            for (String table : TABLES) {
                if (!BOOKS.equals(table)) continue;
                for (FileRecord record : listBookFiles(db)) {
                    File file = new File(context.getFilesDir(), "books/" + record.file);
                    String path;
                    byte[] bytes;
                    long size;
                    if (file.isFile()) {
                        // 本地书：路径、哈希、字节数、条目顺序、进度文案全部保持原样
                        stage("正在打包书文件 " + record.title);
                        path = "files/" + record.id + extensionOf(record.file);
                        bytes = readAll(file);
                        size = file.length();
                    } else {
                        // 本地副本不可用（SAF 引用书的 file 为空，或本地文件被删）：
                        // 尝试从 SAF 原文件读取内容字节。source_uri 本身仍是设备字段，绝不进备份。
                        String sourceUri = lookupSourceUri(db, record.id);
                        byte[] referenced = null;
                        if (sourceUri != null) {
                            try {
                                Uri uri = Uri.parse(sourceUri);
                                if ("content".equalsIgnoreCase(uri.getScheme()))
                                    referenced = readUri(context, uri);
                            } catch (Exception ignored) {
                                // 授权失效 / 原文件被移动 / 读取异常：与无引用一样按缺失处理
                            }
                        }
                        if (referenced == null) {
                            // 不中止导出（部分备份仍有价值），但必须让用户看见漏了哪本，
                            // 且要区分「本地文件缺失」与「SAF 原文件读取失败」。
                            String reason = (record.file == null || record.file.isEmpty())
                                    ? "（SAF 原文件读取失败）"
                                    : "（本地文件缺失；SAF 原文件读取失败）";
                            missingFiles.add(record.title + reason);
                            continue;
                        }
                        stage("正在打包书文件 " + record.title);
                        path = "files/" + record.id + BackupBookFiles.extensionFor(
                                record.file, record.format, record.title);
                        bytes = referenced;
                        size = bytes.length;
                    }
                    // 书文件以流式写入，先记清单条目（哈希在读流时计算）
                    String hash = ContentContracts.hashHex(new java.io.ByteArrayInputStream(bytes));
                    entries.add(new BackupManifest.Entry(path, BackupManifest.EntryKind.FILE,
                            hash, size, BackupManifest.Part.ARCHIVE.name()));
                    writeEntry(zip, path, bytes);
                }
            }
        }

        // 数据段写入
        for (Map.Entry<String, byte[]> data : dataPayloads.entrySet())
            writeEntry(zip, data.getKey(), data.getValue());

        // 清单最后写入（声明的条目已全部落盘）
        JSONObject manifest = new JSONObject();
        manifest.put("schemaVersion", BackupManifest.SCHEMA_VERSION);
        manifest.put("createdAt", System.currentTimeMillis());
        manifest.put("app", "OpenLeaf/0.42.0");
        JSONArray array = new JSONArray();
        for (BackupManifest.Entry e : entries) {
            JSONObject item = new JSONObject();
            item.put("path", e.path);
            item.put("kind", e.kind.name());
            item.put("sha256", e.sha256);
            item.put("size", e.size);
            item.put("part", e.part);
            array.put(item);
        }
        manifest.put("entries", array);
        byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        writeEntry(zip, MANIFEST, manifestBytes);

        zip.finish();
        zip.flush();
        return entries.size();
    }

    /**
     * 最近一次 {@link #export} 中缺失的书文件清单（书名 + 期望文件名）。
     * 空列表表示没有漏书；非空时调用方必须在结果文案里明确告知，不能静默。
     */
    public List<String> missingFiles() {
        return new ArrayList<>(missingFiles);
    }

    /** 结构自检（供 UI 在导出后核对）。 */
    public List<BackupManifest.Issue> selfCheck(int schemaVersion, List<BackupManifest.Entry> entries) {
        return BackupManifest.validateStructure(schemaVersion, entries);
    }

    private BackupManifest.Part partOf(String table) {
        if ("online_books".equals(table)) return BackupManifest.Part.CATALOG;
        return BackupManifest.Part.LIBRARY;
    }

    /**
     * 导出每本书的阅读位置键。格式与表数据段一样是 {rows:[]}，但每行是
     * {book_id,suffix,type,value}，恢复时按书 id 重映射后写回目标设备的 prefs。
     */
    private byte[] exportReaderPrefs() throws Exception {
        JSONObject section = new JSONObject();
        JSONArray rows = new JSONArray();
        SharedPreferences prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE);
        String[] suffixes = {"anchor_", "chapter_", "offset_", "bottom_", "lineFraction_",
                "progress_", "text_layout_version_", "opened_", "pdf_page_", "pdf_count_",
                "epub_chapter_", "epub_scroll_", "epubCfi_"};
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            String suffix = null;
            for (String candidate : suffixes) {
                if (key.startsWith(candidate)) { suffix = candidate; break; }
            }
            Object value = entry.getValue();
            if (suffix == null || value == null || key.length() <= suffix.length()) continue;
            String type;
            if (value instanceof Integer) type = "int";
            else if (value instanceof Long) type = "long";
            else if (value instanceof Float) type = "float";
            else if (value instanceof Boolean) type = "bool";
            else type = "string";
            JSONObject row = new JSONObject();
            row.put("book_id", key.substring(suffix.length()));
            row.put("suffix", suffix);
            row.put("type", type);
            row.put("value", String.valueOf(value));
            rows.put(row);
        }
        section.put("rows", rows);
        return section.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 把一张表导出为 {columns:[], rows:[]}，剥离设备字段。 */
    private byte[] exportTable(SQLiteDatabase db, String table) throws Exception {
        if (BackupManifest.isDeviceOnlyTable(table)) return null;
        // reading_sessions 没有外键：删书不会级联删会话，库里可能残留孤儿行。
        // 导出时按现存书过滤，避免备份里的分列合计大于 reading_time 的总计。
        // 0.22.0：分列会话还可能属于非书媒体（漫画/音频），它们没有 books 行，
        // 只按 books 过滤会把新增的 media_* 会话悄悄丢掉。
        String selection = "reading_sessions".equals(table)
                ? "book_id IN (SELECT id FROM books) OR book_id IN (SELECT id FROM media_sources)" : null;
        Cursor c = db.query(table, null, selection, null, null, null, null);
        try {
            String[] columns = c.getColumnNames();
            JSONArray rows = new JSONArray();
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < columns.length; i++) {
                    String column = columns[i];
                    if (BackupManifest.isDeviceOnly(table + "." + column)) continue;
                    if (BackupManifest.isRegenerateField(table + "." + column)) continue;
                    int type = c.getType(i);
                    if (type == 0) row.put(column, JSONObject.NULL);          // Cursor.TYPE_NULL
                    else if (type == 1) row.put(column, c.getLong(i));         // Cursor.TYPE_INTEGER
                    else if (type == 2) row.put(column, c.getDouble(i));       // Cursor.TYPE_FLOAT
                    else row.put(column, c.getString(i));
                }
                rows.put(row);
            }
            JSONObject payload = new JSONObject();
            payload.put("table", table);
            payload.put("columns", new JSONArray(Arrays.asList(columns)));
            payload.put("rows", rows);
            return payload.toString().getBytes(StandardCharsets.UTF_8);
        } finally { c.close(); }
    }

    private static final class FileRecord {
        final String id, file, title, format;
        FileRecord(String id, String file, String title, String format) {
            this.id = id; this.file = file; this.title = title; this.format = format;
        }
    }

    private List<FileRecord> listBookFiles(SQLiteDatabase db) {
        List<FileRecord> result = new ArrayList<>();
        Cursor c = db.query("books", new String[]{"id", "file", "title", "format"},
                null, null, null, null, null);
        try {
            while (c.moveToNext()) result.add(new FileRecord(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3)));
        } finally { c.close(); }
        return result;
    }

    /** 查 SAF 原文件引用：只在导出端用于读取字节，book_references 整表仍不进备份。 */
    private String lookupSourceUri(SQLiteDatabase db, String bookId) {
        if (bookId == null || bookId.isEmpty()) return null;
        Cursor c = db.query("book_references", new String[]{"source_uri"},
                "book_id=?", new String[]{bookId}, null, null, null, "1");
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally { c.close(); }
    }

    /** 从 SAF content:// URI 读取完整字节；失败向上抛，由导出循环计入缺失清单。 */
    private byte[] readUri(Context context, Uri uri) throws IOException {
        InputStream raw = context.getContentResolver().openInputStream(uri);
        if (raw == null) throw new IOException("SAF 输入流为空");
        try (InputStream in = new BufferedInputStream(raw);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private String extensionOf(String file) {
        int dot = file.lastIndexOf('.');
        return dot >= 0 ? file.substring(dot) : "";
    }

    private byte[] readAll(File file) throws IOException {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
             BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private String sha(byte[] bytes) { return ContentContracts.hashHex(bytes); }

    private void writeEntry(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(System.currentTimeMillis());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private void stage(String stage) { if (progress != null) progress.onProgress(stage); }
}
