package org.openleaf.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openleaf.reader.core.BookParser;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Application-private book files plus transactional SQLite metadata. */
public final class BookStore {
    static {
        // 首次使用 BookStore 即安装 MOBI decoder：导入校验与 ReaderActivity 开书都经此类。
        // so 缺失时 MobiNative 静态块自行吞掉 LinkageError，这里只负责触发加载。
        MobiNative.ensureInstalled();
    }

    public static final long MAX_FILE = org.openleaf.reader.core.BookParser.MAX_IMPORT_BYTES;
    private static final long MAX_TXT_FILE = org.openleaf.reader.core.BookParser.MAX_TXT_BYTES;
    private static final int MAX_LEGACY_INDEX_BYTES = 2 * 1024 * 1024;
    private static final Object MIGRATION_LOCK = new Object();
    private static final String TABLE_BOOKS = "books";
    private static final String TABLE_META = "library_meta";
    private static final String MIGRATION_KEY = "legacy_json_migrated_v1";
    /**
     * 128 KiB: a read() on a SAF descriptor (/proc/self/fd/N or a cache copy of a pipe) costs one
     * call into the provider, so four times larger blocks issue four times fewer calls for the
     * same bytes. The buffer stays small enough not to matter for an import worker's heap.
     */
    private static final int CHUNK_BYTES = 128 * 1024;

    static final class Book {
        String id;
        String title;
        String format;
        String file;
        long added;

        Book(String id, String title, String format, String file, long added) {
            this.id = id;
            this.title = title;
            this.format = format;
            this.file = file;
            this.added = added;
        }
    }

    static final String UNCLASSIFIED = "@unclassified";
    static final class Folder {
        final String id, parentId, name;
        final int bookCount;
        Folder(String id, String parentId, String name, int bookCount) {
            this.id=id;this.parentId=parentId;this.name=name;this.bookCount=bookCount;
        }
    }
    static final class ShelfContents {
        final List<Folder> folders;
        final List<Book> books;
        ShelfContents(List<Folder> folders, List<Book> books) {this.folders=folders;this.books=books;}
    }

    static final class Source {
        final String uri,name;
        Source(String uri,String name){this.uri=uri;this.name=name;}
    }

    static final class ImportResult {
        final Book book;
        final boolean duplicate;

        ImportResult(Book book, boolean duplicate) {
            this.book = book;
            this.duplicate = duplicate;
        }
    }

    private final Context context;
    private final LibraryDatabase helper;

    BookStore(Context context) {
        this.context = context.getApplicationContext();
        this.helper = LibraryDatabase.get(this.context);
    }

    /**
     * A one-time transaction copies old library.json metadata into SQLite. The JSON/AtomicFile
     * (including its backup, if openRead restores one) is deliberately never deleted.
     */
    private void ensureMigrated() throws IOException {
        synchronized (MIGRATION_LOCK) {
            SQLiteDatabase db = helper.getWritableDatabase();
            if (migrationDone(db)) return;

            List<Book> legacy = readLegacyIndex();
            db.beginTransaction();
            try {
                if (!migrationDone(db)) {
                    for (int i = 0; i < legacy.size(); i++) {
                        Book b = legacy.get(i);
                        ContentValues values = LibraryDatabase.values(
                                b.id, b.title, b.format, b.file, b.added, null);
                        values.put("order_index", i);
                        long row = db.insertOrThrow(TABLE_BOOKS, null, values);
                        if (row < 0) throw new IOException("无法迁移旧书库索引");
                        LibraryDatabase.writeMediaItemShadow(context, db, values); // 3-B Y-1 双写
                    }
                    ContentValues marker = new ContentValues();
                    marker.put("key", MIGRATION_KEY);
                    marker.put("value", 1);
                    db.insertOrThrow(TABLE_META, null, marker);
                }
                db.setTransactionSuccessful();
            } catch (RuntimeException e) {
                throw new IOException("旧书库迁移失败；原索引和书籍文件已保留", e);
            } finally {
                db.endTransaction();
            }
        }
    }

    private static boolean migrationDone(SQLiteDatabase db) {
        try (Cursor cursor = db.query(TABLE_META, new String[]{"value"}, "key=?",
                new String[]{MIGRATION_KEY}, null, null, null, "1")) {
            return cursor.moveToFirst() && cursor.getInt(0) == 1;
        }
    }

    private List<Book> readLegacyIndex() throws IOException {
        File index = new File(context.getFilesDir(), "library.json");
        File backup = new File(index.getPath() + ".bak");
        AtomicFile atomic = new AtomicFile(index);
        try (InputStream in = atomic.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = in.read(buffer)) != -1) {
                if (count > MAX_LEGACY_INDEX_BYTES - total)
                    throw new IOException("旧书库索引超过 2 MiB 上限");
                total += count;
                bytes.write(buffer, 0, count);
            }
            String json = decodeUtf8(bytes.toByteArray());
            JSONArray array = new JSONArray(json);
            List<Book> books = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                books.add(new Book(row.getString("id"), row.getString("title"),
                        row.getString("format"), row.getString("file"), row.optLong("added")));
            }
            return books;
        } catch (FileNotFoundException absent) {
            if (!index.exists() && !backup.exists()) return new ArrayList<>();
            throw new IOException("旧书库索引无法读取；请保留应用数据后重试", absent);
        } catch (JSONException malformed) {
            throw new IOException("旧书库索引格式损坏；请保留应用数据，不要覆盖原索引", malformed);
        } catch (SecurityException denied) {
            throw new IOException("旧书库索引没有读取权限；请保留应用数据后重试", denied);
        } catch (CharacterCodingException malformedEncoding) {
            throw new IOException("旧书库索引不是有效 UTF-8；请保留原文件", malformedEncoding);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    List<Book> all() throws IOException {
        ensureMigrated();
        SQLiteDatabase db = helper.getReadableDatabase();
        List<Book> books = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE_BOOKS,
                new String[]{"id", "title", "format", "file", "added"},
                null, null, null, null, "order_index ASC, added DESC, id ASC")) {
            while (cursor.moveToNext()) books.add(LibraryDatabase.toBook(db, cursor));
        }
        return books;
    }

    /** Direct children only. Search/"all books" continues to use all(). */
    ShelfContents shelf(String folderId) throws IOException {
        ensureMigrated();
        SQLiteDatabase db=helper.getReadableDatabase();
        List<Folder> folders=new ArrayList<>();
        List<Book> books=new ArrayList<>();
        boolean top=folderId==null||folderId.isEmpty();
        boolean unclassified=UNCLASSIFIED.equals(folderId);
        if(!top&&!unclassified)requireFolder(db,folderId);
        if(!unclassified){
            String where=top?"f.parent_id IS NULL":"f.parent_id=?";
            try(Cursor cursor=db.rawQuery("SELECT f.id,f.parent_id,COALESCE(f.display_name,f.name),"
                    +"(SELECT COUNT(*) FROM book_folders bf WHERE bf.folder_id=f.id) AS book_count "
                    +"FROM folders f WHERE "+where+" ORDER BY COALESCE(f.display_name,f.name) COLLATE NOCASE,f.id",
                    top?null:new String[]{folderId})){
                while(cursor.moveToNext())folders.add(new Folder(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getInt(3)));
            }
        }
        if(top){
            try(Cursor cursor=db.rawQuery("SELECT COUNT(*) FROM books b WHERE NOT EXISTS "
                    +"(SELECT 1 FROM book_folders bf WHERE bf.book_id=b.id)",null)){
                if(cursor.moveToFirst()&&cursor.getInt(0)>0)
                    folders.add(new Folder(UNCLASSIFIED,null,"未分类",cursor.getInt(0)));
            }
        }else{
            String sql="SELECT b.id,b.title,b.format,b.file,b.added FROM books b ";
            String[] args;
            if(unclassified){
                sql+="WHERE NOT EXISTS (SELECT 1 FROM book_folders bf WHERE bf.book_id=b.id) ";args=null;
            }else{
                sql+="INNER JOIN book_folders bf ON bf.book_id=b.id WHERE bf.folder_id=? ";args=new String[]{folderId};
            }
            try(Cursor cursor=db.rawQuery(sql+"ORDER BY b.order_index ASC,b.added DESC,b.id ASC",args)){
                while(cursor.moveToNext())books.add(LibraryDatabase.toBook(db, cursor));
            }
        }
        return new ShelfContents(folders,books);
    }

    List<Folder> allFolders() throws IOException {
        ensureMigrated();List<Folder> result=new ArrayList<>();
        try(Cursor cursor=helper.getReadableDatabase().rawQuery("SELECT f.id,f.parent_id,COALESCE(f.display_name,f.name),"
                +"(SELECT COUNT(*) FROM book_folders bf WHERE bf.folder_id=f.id) FROM folders f "
                +"ORDER BY COALESCE(f.display_name,f.name) COLLATE NOCASE,f.id",null)){
            while(cursor.moveToNext())result.add(new Folder(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getInt(3)));
        }
        return result;
    }

    String createFolder(String parentId,String name) throws IOException {
        ensureMigrated();String normalized=validFolderName(name,parentId);SQLiteDatabase db=helper.getWritableDatabase();
        String id="folder:"+UUID.randomUUID();db.beginTransaction();
        try{
            if(parentId!=null)requireFolder(db,parentId);
            requireUniqueName(db,parentId,normalized,null);
            ContentValues values=new ContentValues();values.put("id",id);
            if(parentId==null)values.putNull("parent_id");else values.put("parent_id",parentId);
            values.put("name",normalized);values.put("source_tree","manual:"+UUID.randomUUID());values.put("relative_path","");
            db.insertOrThrow("folders",null,values);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return id;
    }

    void renameFolder(String folderId,String name) throws IOException {
        ensureMigrated();SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
        try{
            requireFolder(db,folderId);String parent;
            try(Cursor row=db.query("folders",new String[]{"parent_id"},"id=?",new String[]{folderId},null,null,null,"1")){
                if(!row.moveToFirst())throw new IOException("文件夹已不存在");parent=row.getString(0);
            }
            String normalized=validFolderName(name,parent);requireUniqueName(db,parent,normalized,folderId);
            ContentValues values=new ContentValues();values.put("display_name",normalized);
            db.update("folders",values,"id=?",new String[]{folderId});db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    private static String validFolderName(String name,String parentId) throws IOException {
        String normalized=name==null?"":name.trim();
        if(normalized.isEmpty()||normalized.length()>80||normalized.equals(".")||normalized.equals("..")
                ||normalized.indexOf('/')>=0||normalized.indexOf('\\')>=0||normalized.indexOf('\0')>=0)
            throw new IOException("名称需为1至80个字符，不能包含斜杠、空名称、. 或 ..");
        if(parentId==null&&normalized.equals("未分类"))throw new IOException("顶层“未分类”为系统保留名称，请换一个名称");
        return normalized;
    }

    private static void requireUniqueName(SQLiteDatabase db,String parentId,String name,String except) throws IOException {
        String where=parentId==null?"parent_id IS NULL":"parent_id=?";
        ArrayList<String> args=new ArrayList<>();if(parentId!=null)args.add(parentId);
        where+=" AND COALESCE(display_name,name)=? COLLATE NOCASE";args.add(name);
        if(except!=null){where+=" AND id<>?";args.add(except);}
        try(Cursor row=db.query("folders",new String[]{"id"},where,args.toArray(new String[0]),null,null,null,"1")){
            if(row.moveToFirst())throw new IOException("当前层已存在同名文件夹，请换一个名称");
        }
    }

    /** Remove metadata only, leaves books and documents untouched. */
    void deleteFolder(String folderId) throws IOException {
        ensureMigrated();SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
        try{
            requireFolder(db,folderId);
            ArrayList<String> ordered=new ArrayList<>();java.util.HashSet<String> seen=new java.util.HashSet<>();
            ordered.add(folderId);seen.add(folderId);
            for(int i=0;i<ordered.size();i++){
                try(Cursor row=db.query("folders",new String[]{"id"},"parent_id=?",new String[]{ordered.get(i)},null,null,null)){
                    while(row.moveToNext()){String id=row.getString(0);if(seen.add(id))ordered.add(id);}
                }
            }
            for(int i=ordered.size()-1;i>=0;i--){String[] arg={ordered.get(i)};
                db.delete("book_folders","folder_id=?",arg);db.delete("folders","id=?",arg);
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    private static ArrayList<String> checkedBooks(SQLiteDatabase db,List<String> bookIds) throws IOException {
        if(bookIds==null||bookIds.isEmpty())throw new IOException("请先选择书籍");
        java.util.LinkedHashSet<String> unique=new java.util.LinkedHashSet<>(bookIds);
        if(unique.size()>10000)throw new IOException("一次最多整理10000本，请分批操作");
        ArrayList<String> result=new ArrayList<>(unique);
        for(String id:result)if(id==null||findById(db,id)==null)throw new IOException("所选书籍已变化，请刷新后重新选择");
        return result;
    }

    void organizeBooks(List<String> bookIds,String destinationFolderId,String fromFolderId,boolean move) throws IOException {
        ensureMigrated();SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
        try{
            requireFolder(db,destinationFolderId);ArrayList<String> ids=checkedBooks(db,bookIds);
            boolean specific=fromFolderId!=null&&!UNCLASSIFIED.equals(fromFolderId);
            if(specific)requireFolder(db,fromFolderId);
            if(!(move&&specific&&destinationFolderId.equals(fromFolderId))){
                for(String id:ids){
                    if(move){if(specific)db.delete("book_folders","book_id=? AND folder_id=?",new String[]{id,fromFolderId});
                        else db.delete("book_folders","book_id=?",new String[]{id});}
                    linkBook(db,id,destinationFolderId);
                }
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    void unfileBooks(List<String> bookIds,String fromFolderId) throws IOException {
        ensureMigrated();SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
        try{
            ArrayList<String> ids=checkedBooks(db,bookIds);boolean specific=fromFolderId!=null&&!UNCLASSIFIED.equals(fromFolderId);
            if(specific)requireFolder(db,fromFolderId);
            for(String id:ids){if(specific)db.delete("book_folders","book_id=? AND folder_id=?",new String[]{id,fromFolderId});
                else db.delete("book_folders","book_id=?",new String[]{id});}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    /** Create logical source folders. Never resolves or modifies physical storage paths. */
    String ensureImportFolder(Uri sourceTree,String rootName,String relativeDirectory) throws IOException {
        if(sourceTree==null||!"content".equals(sourceTree.getScheme())||sourceTree.getAuthority()==null)
            throw new IOException("来源目录标识无效");
        String path=relativeDirectory==null?"":relativeDirectory;
        if(path.length()>16384||path.startsWith("/")||path.endsWith("/")||path.indexOf('\\')>=0)
            throw new IOException("归档目录必须为合法相对路径");
        String[] segments=path.isEmpty()?new String[0]:path.split("/",-1);
        if(segments.length>DirectoryScanner.MAX_DEPTH)throw new IOException("归档目录层级过深");
        for(String segment:segments)if(segment.isEmpty()||segment.equals(".")||segment.equals(".."))
            throw new IOException("归档目录不能包含空层级、. 或 ..");
        String label=rootName==null||rootName.trim().isEmpty()?"导入文件夹":rootName;
        ensureMigrated();
        SQLiteDatabase db=helper.getWritableDatabase();
        String source=sourceTree.toString(),folderId;
        db.beginTransaction();
        try{
            folderId=ensureFolderRow(db,source,"",null,label);
            String current="";
            for(String segment:segments){
                current=current.isEmpty()?segment:current+"/"+segment;
                folderId=ensureFolderRow(db,source,current,folderId,segment);
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return folderId;
    }

    /**
     * 该来源目录已入库的根分组名；尚无根分组时返回 null。
     *
     * <p>重试执行器必须用它：{@link #ensureImportFolder} 按 (source_tree, relative_path) 查到既有行时
     * 会把 name 改成传入的 rootName，而首次导入的 rootName 来自 SAF 显示名（"Download"、"Books"…），
     * 不是固定字面量。传字面量会在重试时静默改掉用户的书架分组名。
     */
    String importRootName(Uri sourceTree) {
        if (sourceTree == null || !"content".equals(sourceTree.getScheme())) return null;
        try { ensureMigrated(); } catch (IOException unavailable) { return null; }
        try (Cursor cursor = helper.getReadableDatabase().query("folders", new String[]{"name"},
                "source_tree=? AND relative_path=? AND parent_id IS NULL",
                new String[]{sourceTree.toString(), ""}, null, null, null, "1")) {
            if (cursor.moveToFirst()) {
                String name = cursor.getString(0);
                return name == null || name.trim().isEmpty() ? null : name;
            }
        }
        return null;
    }

    private static String ensureFolderRow(SQLiteDatabase db,String source,String path,String parent,String name){
        try(Cursor cursor=db.query("folders",new String[]{"id","name"},"source_tree=? AND relative_path=?",
                new String[]{source,path},null,null,null,"1")){
            if(cursor.moveToFirst()){
                String id=cursor.getString(0);
                if(!name.equals(cursor.getString(1))){ContentValues label=new ContentValues();label.put("name",name);db.update("folders",label,"id=?",new String[]{id});}
                return id;
            }
        }
        String id="folder:"+UUID.randomUUID();
        ContentValues values=new ContentValues();values.put("id",id);
        if(parent==null)values.putNull("parent_id");else values.put("parent_id",parent);
        values.put("name",name);values.put("source_tree",source);values.put("relative_path",path);
        db.insertOrThrow("folders",null,values);
        return id;
    }

    private static void requireFolder(SQLiteDatabase db,String folderId) throws IOException {
        try(Cursor cursor=db.query("folders",new String[]{"id"},"id=?",new String[]{folderId},null,null,null,"1")){
            if(!cursor.moveToFirst())throw new IOException("归档文件夹不存在，请返回书架重新选择");
        }
    }

    private static void linkBook(SQLiteDatabase db,String bookId,String folderId) throws IOException {
        if(folderId==null)return;
        requireFolder(db,folderId);
        ContentValues values=new ContentValues();values.put("book_id",bookId);values.put("folder_id",folderId);
        db.insertWithOnConflict("book_folders",null,values,SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static final int MAX_BOOK_TAGS = 24;
    private static final int MAX_TAG_LENGTH = 32;

    List<String> tags(String bookId) throws IOException {
        ensureMigrated();
        return readTags(helper.getReadableDatabase(), bookId);
    }

    /** Read labels for the library in one query so shelf search does not issue per-book lookups. */
    Map<String, List<String>> allTags() throws IOException {
        ensureMigrated();
        Map<String, List<String>> result = new HashMap<>();
        try (Cursor cursor = helper.getReadableDatabase().query("book_tags", new String[]{"book_id", "name"},
                null, null, null, null, "book_id,tag_key")) {
            while (cursor.moveToNext()) {
                result.computeIfAbsent(cursor.getString(0), key -> new ArrayList<>()).add(cursor.getString(1));
            }
        }
        return result;
    }

    List<String> addTag(String bookId, String name) throws IOException {
        String label = normalizeTag(name);
        String key = label.toLowerCase(Locale.ROOT);
        ensureMigrated();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            if (findById(db, bookId) == null) throw new IOException("书籍不存在或已移除");
            boolean exists = false;
            int count = 0;
            try (Cursor cursor = db.query("book_tags", new String[]{"tag_key"}, "book_id=?",
                    new String[]{bookId}, null, null, null)) {
                while (cursor.moveToNext()) {
                    count++;
                    if (key.equals(cursor.getString(0))) exists = true;
                }
            }
            if (!exists) {
                if (count >= MAX_BOOK_TAGS) throw new IOException("每本书最多可添加 24 个标签");
                ContentValues row = new ContentValues();
                row.put("book_id", bookId);
                row.put("tag_key", key);
                row.put("name", label);
                db.insertOrThrow("book_tags", null, row);
            }
            List<String> result = readTags(db, bookId);
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }

    List<String> removeTag(String bookId, String name) throws IOException {
        String key = normalizeTag(name).toLowerCase(Locale.ROOT);
        ensureMigrated();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("book_tags", "book_id=? AND tag_key=?", new String[]{bookId, key});
            List<String> result = readTags(db, bookId);
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }

    private static List<String> readTags(SQLiteDatabase db, String bookId) {
        List<String> result = new ArrayList<>();
        try (Cursor cursor = db.query("book_tags", new String[]{"name"}, "book_id=?",
                new String[]{bookId}, null, null, "tag_key")) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return result;
    }

    private static String normalizeTag(String name) throws IOException {
        if (name == null) throw new IOException("标签不能为空");
        String label = Normalizer.normalize(name, Normalizer.Form.NFKC).trim();
        if (label.isEmpty()) throw new IOException("标签不能为空");
        if (label.codePointCount(0, label.length()) > MAX_TAG_LENGTH) throw new IOException("标签最多 32 个字符");
        for (int offset = 0; offset < label.length();) {
            int cp = label.codePointAt(offset);
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE) {
                throw new IOException("标签不能包含换行或控制字符");
            }
            offset += Character.charCount(cp);
        }
        return label;
    }

    Book find(String id) throws IOException {
        ensureMigrated();
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_BOOKS,
                new String[]{"id", "title", "format", "file", "added"},
                "id=?", new String[]{id}, null, null, null, "1")) {
            if (cursor.moveToFirst()) return LibraryDatabase.toBook(db, cursor);
        }
        throw new IOException("书籍已移除");
    }

    boolean isReferenced(Book book) throws IOException { return sourceUri(book)!=null; }

    Uri sourceUri(Book book) throws IOException {
        ensureMigrated();
        try(Cursor row=helper.getReadableDatabase().query("book_references",new String[]{"source_uri"},
                "book_id=?",new String[]{book.id},null,null,null,"1")) {
            return row.moveToFirst()?Uri.parse(row.getString(0)):null;
        }
    }

    long size(Book book) throws IOException {
        Uri uri=sourceUri(book);
        if(uri==null)return file(book).length();
        try(ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(uri,"r")) {
            if(fd==null)throw new IOException("原文件不可用，请重新关联原文件");
            return fd.getStatSize();
        } catch(SecurityException denied) {throw new IOException("原文件授权失效，请重新关联原文件",denied);}
    }

    BookAccess open(Book book) throws IOException {
        Uri uri=sourceUri(book);
        if(uri==null) {
            File local=file(book);
            if(!local.isFile()||!local.canRead())throw new IOException("应用书库副本不存在或不可读");
            return BookAccess.local(local);
        }
        requirePersistentRead(uri);
        BookAccess access=BookAccess.source(context,uri,importLimit(book.format),null);
        boolean handedOff=false;
        try {
            String expected=storedHash(book.id);
            if(expected==null||!expected.equals(hashFile(access.file,null,importLimit(book.format))))
                throw new IOException("原文件内容已变化，请重新导入；旧阅读进度不会套用到新内容");
            handedOff=true;return access;
        } finally {if(!handedOff)access.close();}
    }

    /** 重新计算并写入书文件哈希（在线书重新解析骨架后调用，保持 open() 校验一致）。 */
    void refreshHash(Book book) throws IOException {
        File file = new File(context.getFilesDir(), "books/" + book.file);
        String digest = org.openleaf.reader.core.ContentContracts.toHex(digestSha256(
                java.nio.file.Files.readAllBytes(file.toPath())));
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("sha256", digest);
        helper.getWritableDatabase().update(TABLE_BOOKS, values, "id=?", new String[]{book.id});
    }

    String storedHash(String id) throws IOException {
        try(Cursor row=helper.getReadableDatabase().query(TABLE_BOOKS,new String[]{"sha256"},"id=?",new String[]{id},null,null,null,"1")) {
            if(!row.moveToFirst())throw new IOException("书籍已移除");
            return row.isNull(0)?null:row.getString(0);
        }
    }

    private void requirePersistentRead(Uri uri) throws IOException {
        if(uri==null||!"content".equals(uri.getScheme()))throw new IOException("请通过系统文件选择器授权原文件，不能导入临时路径");
        // The opt-in debug provider is app-owned; fixtures never need a user grant.
        if((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
                && (context.getPackageName()+".debugfixtures").equals(uri.getAuthority()))return;
        for(android.content.UriPermission permission:context.getContentResolver().getPersistedUriPermissions()) {
            if(!permission.isReadPermission())continue;
            Uri grant=permission.getUri();
            if(grant.equals(uri))return;
            if(!java.util.Objects.equals(grant.getAuthority(),uri.getAuthority()))continue;
            try {
                if(android.provider.DocumentsContract.isTreeUri(grant)
                        && android.provider.DocumentsContract.isTreeUri(uri)
                        && android.provider.DocumentsContract.getTreeDocumentId(grant).equals(android.provider.DocumentsContract.getTreeDocumentId(uri)))return;
            } catch(IllegalArgumentException notTree) { /* Continue looking for a durable direct grant. */ }
        }
        throw new IOException("未获得原文件的持久读取授权，请通过系统选择器重新选择文件或文件夹");
    }

    private static boolean referenced(SQLiteDatabase db,String id) {
        try(Cursor row=db.query("book_references",new String[]{"book_id"},"book_id=?",new String[]{id},null,null,null,"1")){return row.moveToFirst();}
    }
    private static void bindReference(SQLiteDatabase db,String id,Uri uri) throws IOException {
        ContentValues row=new ContentValues();row.put("book_id",id);row.put("source_uri",uri.toString());
        if(db.insertWithOnConflict("book_references",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0)
            throw new IOException("无法保存原文件引用，未修改书架");
    }

    File file(Book book) throws IOException {
        if(isReferenced(book))throw new IOException("此书直接引用原文件，请使用 BookStore.open 读取");
        File directory = new File(context.getFilesDir(), "books");
        File result = new File(directory, book.file);
        String canonicalDirectory = directory.getCanonicalPath() + File.separator;
        if (!result.getCanonicalPath().startsWith(canonicalDirectory))
            throw new IOException("书籍路径无效");
        return result;
    }

    /**
     * Once per import session, stream-hash old local files that predate the hash column.
     * Missing/unreadable legacy files are skipped; cancellation propagates immediately.
     */
    void prepareImport(CancellationSignal cancellation) throws IOException {
        checkCanceled(cancellation);
        ensureMigrated();
        SQLiteDatabase db = helper.getWritableDatabase();
        List<String[]> pending = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE_BOOKS, new String[]{"id", "file"},
                "sha256 IS NULL", null, null, null, "order_index ASC")) {
            while (cursor.moveToNext()) pending.add(new String[]{cursor.getString(0), cursor.getString(1)});
        }
        for (String[] row : pending) {
            checkCanceled(cancellation);
            Book book = new Book(row[0], "", "", row[1], 0);
            File local;
            try {
                local = file(book);
                if (!local.isFile() || !local.canRead()) continue;
                String digest = hashFile(local, cancellation, MAX_FILE);
                checkCanceled(cancellation);
                ContentValues values = new ContentValues();
                values.put("sha256", digest);
                db.update(TABLE_BOOKS, values, "id=? AND sha256 IS NULL", new String[]{book.id});
            } catch (FileNotFoundException missing) {
                // A stale metadata row must not block importing new books.
            } catch (IOException unreadable) {
                // A corrupt or partially unreadable legacy file is also non-blocking.
            } catch (SecurityException inaccessible) {
                // Likewise skip a file the app can no longer read.
            }
        }
    }

    /** Compatibility entry point: preserve the old single-item import call site. */
    Book importUri(Uri uri) throws Exception {
        prepareImport(null);
        return importUri(uri, null).book;
    }

    ImportResult importUri(Uri uri, CancellationSignal cancellation) throws Exception {
        return importUri(uri,cancellation,null);
    }

    enum ImportPhase { COPY, VALIDATE, SAVE }

    interface ImportProgress {
        void onProgress(String name, ImportPhase phase, long bytes, long total);
    }

    static long importLimit(String format) {
        return "txt".equals(format) ? MAX_TXT_FILE : MAX_FILE;
    }

    private static void publish(ImportProgress observer, String name, ImportPhase phase, long bytes, long total) {
        if (observer != null) observer.onProgress(name, phase, bytes, total);
    }

    ImportResult importUri(Uri uri, CancellationSignal cancellation, String folderId) throws Exception {
        return importUri(uri, cancellation, folderId, null);
    }

    ImportResult importUri(Uri uri, CancellationSignal cancellation, String folderId, ImportProgress progress) throws Exception {
        return importUri(uri, cancellation, folderId, progress, null, null);
    }

    /**
     * 批量导入重载：目录扫描已经在同一次 provider 查询里拿到了 _display_name 与格式，
     * 这里直接复用，不再为每个文件重复 query OpenableColumns.DISPLAY_NAME / ContentResolver.getType
     * （逐文件两次额外的 SAF 进程间往返）。
     *
     * @param knownName   扫描得到的文件名（null/空则回退到 provider 查询）
     * @param knownFormat 扫描得到的 txt/epub/pdf（null/空则按扩展名与 MIME 判定）
     */
    ImportResult importUri(Uri uri, CancellationSignal cancellation, String folderId, ImportProgress progress,
                           String knownName, String knownFormat) throws Exception {
        if (uri == null) throw new IOException("没有选择要导入的文件");
        checkCanceled(cancellation);
        ensureMigrated();

        if (folderId != null) requireFolder(helper.getReadableDatabase(), folderId);
        String displayName = knownName == null || knownName.trim().isEmpty()
                ? displayName(uri, cancellation) : knownName;
        if (displayName.toLowerCase(Locale.ROOT).endsWith(".olbook")) return importTagged(uri, cancellation, displayName, progress);
        String format = knownFormat == null || knownFormat.trim().isEmpty()
                ? identifyFormat(uri, displayName) : knownFormat.trim().toLowerCase(Locale.ROOT);
        return importReference(uri, displayName, format, cancellation, folderId, progress);
    }

    ImportResult importTagged(Uri uri, CancellationSignal cancellation) throws Exception {
        return importTagged(uri, cancellation, "带标签书包", null);
    }

    private ImportResult importTagged(Uri uri, CancellationSignal cancellation, String name, ImportProgress progress) throws Exception {
        checkCanceled(cancellation);
        ensureMigrated();
        publish(progress, name, ImportPhase.COPY, 0, -1);
        try (InputStream input = openInputStream(uri, cancellation)) {
            if (input == null) throw new IOException("无法打开书包");
            try (TaggedBookPackage.Payload payload = TaggedBookPackage.read(input, context.getCacheDir(), () -> checkCanceled(cancellation))) {
                checkCanceled(cancellation);
                for (String label : payload.tags) normalizeTag(label);
                return importContent(null, payload.title, payload.format, payload, cancellation, null, progress);
            }
        }
    }

    private ImportResult importReference(Uri uri,String name,String format,CancellationSignal cancellation,
                                         String folderId,ImportProgress progress) throws Exception {
        requirePersistentRead(uri);
        publish(progress,name,ImportPhase.COPY,0,-1);
        try(BookAccess access=BookAccess.source(context,uri,importLimit(format),cancellation)) {
            long size=access.descriptor==null?access.file.length():access.descriptor.getStatSize();
            publish(progress,name,ImportPhase.COPY,0,size);
            String hash=hashReference(access.file,cancellation,importLimit(format),name,size,progress);
            String parsedTitle=null;
            int comicPageCount=0;String comicCoverEntry=null;
            Book existing=findByHash(helper.getReadableDatabase(),hash);
            if(existing==null) {
                publish(progress,name,ImportPhase.VALIDATE,0,-1);
                if("comic".equals(format)){
                    org.openleaf.reader.core.ComicFlow.ComicMeta meta=validateComicArchive(access.file, name, cancellation);
                    comicPageCount=meta.pageCount;comicCoverEntry=meta.coverEntry;
                }
                else if("pdf".equals(format))validatePdf(access.file);
                else parsedTitle=BookParser.validateForImport(access.file,format,()->checkCanceled(cancellation));
            }
            checkCanceled(cancellation);
            publish(progress,name,ImportPhase.SAVE,0,-1);
            SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
            try {
                checkCanceled(cancellation);
                existing=findByHash(db,hash);
                boolean duplicate=existing!=null;
                if(existing==null) {
                    String id=UUID.randomUUID().toString();
                    String title=deriveTitle(name,id);
                    if(!"txt".equals(format)&&parsedTitle!=null&&!parsedTitle.trim().isEmpty()
                            &&!parsedTitle.equals(access.file.getName()))title=parsedTitle;
                    existing=new Book(id,title,format,"",System.currentTimeMillis());
                    ContentValues row=LibraryDatabase.values(existing.id,existing.title,format,"",existing.added,hash);
                    if("comic".equals(format)){
                        row.put("media_type", org.openleaf.reader.core.ComicMigration.MEDIA_COMIC);
                        row.put("page_count", comicPageCount);
                        row.put("cover_entry", comicCoverEntry);
                    }
                    row.put("order_index",firstOrderIndex(db)-1);db.insertOrThrow(TABLE_BOOKS,null,row);
                    LibraryDatabase.writeMediaItemShadow(context, db,row); // 3-B Y-1 双写（漫画）
                    bindReference(db,existing.id,uri);
                } else if(referenced(db,existing.id)) {
                    // Re-importing identical bytes can repair a moved or revoked reference.
                    bindReference(db,existing.id,uri);
                }
                applyImportMetadata(db,existing.id,folderId,uri,name,null);
                checkCanceled(cancellation);db.setTransactionSuccessful();
                return new ImportResult(existing,duplicate);
            } finally {db.endTransaction();}
        }
    }

    private static String hashReference(File file,CancellationSignal cancellation,long limit,String name,long size,ImportProgress observer) throws IOException {
        MessageDigest digest=sha256Digest();long total=0,last=android.os.SystemClock.uptimeMillis();
        try(InputStream input=new FileInputStream(file)) {
            byte[] buffer=new byte[CHUNK_BYTES];int n;
            while((n=readChunk(input,buffer,(int)Math.min(buffer.length,limit-total+1),cancellation))!=-1) {
                if(n>limit-total)throw new IOException("原文件超过 "+(limit/1024/1024)+" MiB 上限");
                digest.update(buffer,0,n);total+=n;
                long now=android.os.SystemClock.uptimeMillis();
                if(now-last>=120){publish(observer,name,ImportPhase.COPY,total,size>0&&total<size?size:-1);last=now;}
            }
        }
        if(total==0)throw new IOException("不能导入空文件");
        checkCanceled(cancellation);publish(observer,name,ImportPhase.COPY,total,total);
        return hex(digest.digest());
    }

    private ImportResult importContent(Uri uri, String displayName, String format, TaggedBookPackage.Payload pack,
                                       CancellationSignal cancellation, String folderId, ImportProgress progress) throws Exception {
        String id = UUID.randomUUID().toString();
        File directory = new File(context.getFilesDir(), "books");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("无法创建书库目录");
        // A checked package payload is already an app-private temporary file. Adopt it instead
        // of writing a second full copy; Payload.close() safely tolerates its later rename/delete.
        File staged = pack == null ? new File(directory, ".import-" + id + ".part") : pack.file;
        File committedFile = new File(directory, id + "." + format);
        boolean committed = false;

        try {
            String sha256;
            if (pack == null) {
                publish(progress, displayName, ImportPhase.COPY, 0, -1);
                ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r", cancellation);
                if (fd == null) throw new IOException("无法打开所选文件");
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                    long size = fd.getStatSize();
                    if (size > importLimit(format)) throw tooLarge(format);
                    if (size > 0 && size > directory.getUsableSpace())
                        throw new IOException("应用书库剩余空间不足，无法复制此文件");
                    sha256 = copyAndHash(input, staged, format, cancellation, displayName, size, progress);
                }
            } else {
                // The decoder verified these exact private staged bytes while extracting them.
                sha256 = pack.sha256;
            }
            checkCanceled(cancellation);

            Book duplicate = findByHash(helper.getReadableDatabase(), sha256);
            if (duplicate != null) {
                publish(progress, displayName, ImportPhase.SAVE, 0, -1);
                SQLiteDatabase duplicateDb=helper.getWritableDatabase();
                duplicateDb.beginTransaction();
                try{
                    checkCanceled(cancellation);
                    duplicate=findByHash(duplicateDb,sha256);
                    if (duplicate != null) applyImportMetadata(duplicateDb, duplicate.id, folderId, uri, displayName, pack);
                    checkCanceled(cancellation);
                    duplicateDb.setTransactionSuccessful();
                }finally{duplicateDb.endTransaction();}
                if(duplicate!=null)return new ImportResult(duplicate,true);
            }

            String title = pack == null ? deriveTitle(displayName, id) : pack.title;
            // Deliberately parse outside a SQLite transaction: EPUB parsing can take time.
            checkCanceled(cancellation);
            publish(progress, displayName, ImportPhase.VALIDATE, 0, -1);
            if (!"pdf".equals(format)) {
                String parsedTitle = BookParser.validateForImport(staged, format, () -> checkCanceled(cancellation));
                checkCanceled(cancellation);
                String stagedFallback = staged.getName().replaceFirst("(?i)\\.part$", "");
                if (parsedTitle != null && !parsedTitle.trim().isEmpty()
                        && !parsedTitle.equals(staged.getName())
                        && !parsedTitle.equals(stagedFallback)
                        && !parsedTitle.equals(id)
                        && pack == null && !"txt".equals(format)) {
                    title = parsedTitle;
                }
            } else {
                validatePdf(staged);
                checkCanceled(cancellation);
            }
            if (title.trim().isEmpty()) title = "未命名书籍";

            publish(progress, displayName, ImportPhase.SAVE, 0, -1);
            Book candidate = new Book(id, title, format, committedFile.getName(), System.currentTimeMillis());
            SQLiteDatabase db = helper.getWritableDatabase();
            Book duplicateAfterLock = null;
            db.beginTransaction();
            try {
                checkCanceled(cancellation);
                duplicateAfterLock = findByHash(db, sha256);
                if (duplicateAfterLock == null) {
                    ContentValues values = LibraryDatabase.values(candidate.id, candidate.title,
                            candidate.format, candidate.file, candidate.added, sha256);
                    values.put("order_index", firstOrderIndex(db) - 1);
                    if (!staged.renameTo(committedFile)) throw new IOException("无法保存已导入文件");
                    db.insertOrThrow(TABLE_BOOKS, null, values);
                    LibraryDatabase.writeMediaItemShadow(context, db, values); // 3-B Y-1 双写
                    checkCanceled(cancellation);
                }
                String storedId=duplicateAfterLock==null?candidate.id:duplicateAfterLock.id;
                applyImportMetadata(db, storedId, folderId, uri, displayName, pack);
                checkCanceled(cancellation);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            if (duplicateAfterLock != null) return new ImportResult(duplicateAfterLock, true);
            committed = true;
            return new ImportResult(candidate, false);
        } finally {
            staged.delete();
            if (!committed && committedFile.exists()) committedFile.delete();
        }
    }

    /**
     * 在线书入库：把从书源抓取的正文写成本地 txt 并登记 books 表，
     * 让 ReaderActivity 的开书、进度锚点、搜索、标注全部自动复用。
     * sha256 去重：同内容已存在时直接返回既有书（幂等）。
     */
    public ImportResult importOnlineText(String title, String text, String sourceUri) throws IOException {
        if (title == null || title.trim().isEmpty()) throw new IOException("在线书缺少标题");
        if (text == null) throw new IOException("在线书正文为空");
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha256 = org.openleaf.reader.core.ContentContracts.toHex(digestSha256(bytes));
        File directory = new File(context.getFilesDir(), "books");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("无法创建书库目录");
        SQLiteDatabase db = helper.getWritableDatabase();
        Book duplicate = findByHash(db, sha256);
        if (duplicate != null) return new ImportResult(duplicate, true);
        String id = UUID.randomUUID().toString();
        File committed = new File(directory, id + ".txt");
        db.beginTransaction();
        try {
            try (java.io.OutputStream out = new java.io.FileOutputStream(committed)) { out.write(bytes); }
            Book candidate = new Book(id, title.trim(), "txt", committed.getName(), System.currentTimeMillis());
            ContentValues values = LibraryDatabase.values(candidate.id, candidate.title,
                    candidate.format, candidate.file, candidate.added, sha256);
            values.put("order_index", firstOrderIndex(db) - 1);
            db.insertOrThrow(TABLE_BOOKS, null, values);
            LibraryDatabase.writeMediaItemShadow(context, db, values); // 3-B Y-1 双写
            // 绑定来源 URI 供书源追溯（book_references 表）
            try {
                db.execSQL("INSERT OR REPLACE INTO book_references (book_id,source_uri) VALUES (?,?)",
                        new Object[]{id, sourceUri == null ? "" : sourceUri});
            } catch (Exception ignored) { /* 旧库无表时跳过 */ }
            db.setTransactionSuccessful();
            return new ImportResult(candidate, false);
        } finally {
            db.endTransaction();
            if (!committed.exists()) committed.delete();
        }
    }

    private static byte[] digestSha256(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void applyImportMetadata(SQLiteDatabase db, String id, String folderId, Uri uri,
                                            String displayName, TaggedBookPackage.Payload pack) throws IOException {
        linkBook(db, id, folderId);
        if (pack == null) {
            rememberSource(db, id, uri, displayName);
            return;
        }
        // Never silently overwrite a different local review on content de-duplication.
        // The incoming review is preserved as an idea instead, grouped by sharer — book — review.
        String localReview = BookMetadataStore.readReview(db, id);
        if (!pack.review.isEmpty() && !localReview.isEmpty() && !pack.review.equals(localReview)) {
            IdeaStore.insert(db, pack.sharer, pack.title, pack.review, id);
        } else if (!pack.review.isEmpty()) BookMetadataStore.saveReview(db, id, pack.review);
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String tag : readTags(db, id)) keys.add(tag.toLowerCase(Locale.ROOT));
        for (String raw : pack.tags) {
            String label = normalizeTag(raw);
            String key = label.toLowerCase(Locale.ROOT);
            if (!keys.add(key)) continue;
            if (keys.size() > MAX_BOOK_TAGS) throw new IOException("合并后超过每本书 24 个标签的上限，未导入任何标签");
            ContentValues row = new ContentValues();
            row.put("book_id", id);
            row.put("tag_key", key);
            row.put("name", label);
            db.insertOrThrow("book_tags", null, row);
        }
    }

    List<Source> sources(String bookId) throws IOException {
        ensureMigrated();
        List<Source> result=new ArrayList<>();
        try(Cursor cursor=helper.getReadableDatabase().query("book_sources",new String[]{"source_uri","name"},
                "book_id=?",new String[]{bookId},null,null,"name COLLATE NOCASE,source_key")){
            while(cursor.moveToNext())result.add(new Source(cursor.getString(0),cursor.getString(1)));
        }
        return result;
    }

    private static String sourceKey(Uri uri) throws IOException {
        if(uri==null||!"content".equals(uri.getScheme())||uri.getAuthority()==null)
            throw new IOException("原文件必须通过系统文件选择器授权");
        try{return uri.getAuthority()+"|document|"+android.provider.DocumentsContract.getDocumentId(uri);}
        catch(IllegalArgumentException notDocument){return uri.getAuthority()+"|uri|"+uri;}
    }

    private static void rememberSource(SQLiteDatabase db,String bookId,Uri uri,String name) throws IOException {
        String key=sourceKey(uri);
        // One physical document now contains this version. Remove stale links to older content.
        db.delete("book_sources","source_key=? AND book_id<>?",new String[]{key,bookId});
        ContentValues values=new ContentValues();values.put("book_id",bookId);values.put("source_key",key);
        values.put("source_uri",uri.toString());values.put("name",name==null||name.trim().isEmpty()?"原文件":name);
        db.insertWithOnConflict("book_sources",null,values,SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Explicit file-picker selection is verified before it can become a deletion target. */
    void attachSource(String bookId,Uri uri,CancellationSignal cancellation) throws Exception {
        checkCanceled(cancellation);
        Book book=find(bookId);
        requirePersistentRead(uri);
        verifySource(book,uri,cancellation);
        String name=displayName(uri,cancellation);
        checkCanceled(cancellation);
        SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
        try{
            if(findById(db,bookId)==null)throw new IOException("书籍已移除");
            rememberSource(db,bookId,uri,name);
            if(referenced(db,bookId))bindReference(db,bookId,uri);
            checkCanceled(cancellation);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    private void verifySource(Book book,Uri uri,CancellationSignal cancellation) throws Exception {
        sourceKey(uri);
        if(!android.provider.DocumentsContract.isDocumentUri(context,uri))
            throw new IOException("该来源不支持文档删除，请通过系统文件选择器重新选择原文件");
        try(Cursor cursor=context.getContentResolver().query(uri,
                new String[]{android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null,cancellation)){
            if(cursor==null||!cursor.moveToFirst())throw new IOException("原文件不存在或当前无法访问");
            if(android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0)))
                throw new IOException("不能把文件夹作为书籍原文件删除");
        }
        String expected=null;
        SQLiteDatabase db=helper.getReadableDatabase();
        try(Cursor cursor=db.query(TABLE_BOOKS,new String[]{"sha256"},"id=?",new String[]{book.id},null,null,null,"1")){
            if(cursor.moveToFirst())expected=cursor.getString(0);else throw new IOException("书籍已移除");
        }
        if(expected==null||expected.isEmpty())expected=hashFile(file(book),cancellation,MAX_FILE);
        MessageDigest digest=sha256Digest();long total=0;
        try(InputStream input=openInputStream(uri,cancellation)){
            if(input==null)throw new IOException("无法打开原文件，请重新授权");
            byte[] buffer=new byte[CHUNK_BYTES];int count;
            while((count=input.read(buffer))!=-1){
                checkCanceled(cancellation);total+=count;
                if(total>MAX_FILE)throw new IOException("所选原文件超过本书导入时的大小上限，已停止删除");
                digest.update(buffer,0,count);
            }
        }
        checkCanceled(cancellation);
        if(!expected.equals(hex(digest.digest())))throw new IOException("原文件内容与书架副本不同或已经变化，已停止删除；可选择正确文件或仅删除书架副本");
    }

    /** Never removes the library copy. Callers confirm selected sources before invoking this. */
    void deleteSource(String bookId,Source source,CancellationSignal cancellation) throws Exception {
        checkCanceled(cancellation);
        Book book=find(bookId);Uri uri=Uri.parse(source.uri);String key=sourceKey(uri);
        SQLiteDatabase db=helper.getWritableDatabase();
        try(Cursor cursor=db.query("book_sources",new String[]{"source_uri"},"book_id=? AND source_key=?",
                new String[]{bookId,key},null,null,null,"1")){
            if(!cursor.moveToFirst()||!source.uri.equals(cursor.getString(0)))
                throw new IOException("原文件记录已变更，请刷新后重新选择");
        }
        try{
            verifySource(book,uri,cancellation);
            checkCanceled(cancellation);
            if(!android.provider.DocumentsContract.deleteDocument(context.getContentResolver(),uri))
                throw new IOException("文件提供器没有删除原文件，书架副本已保留");
        }catch(SecurityException denied){throw new IOException("没有原文件的删除权限，请点“选择或重新授权原文件”后再试",denied);}
        // After the provider reports success, do not treat a late cancellation as a failed deletion.
        db.delete("book_sources","book_id=? AND source_key=?",new String[]{bookId,key});
    }

    private String displayName(Uri uri, CancellationSignal cancellation) throws IOException {
        String name = null;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null, cancellation)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (OperationCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException providerFailure) {
            throw new IOException("无法读取所选文件名称", providerFailure);
        }
        return name == null ? "" : name;
    }

    private InputStream openInputStream(Uri uri, CancellationSignal cancellation) throws IOException {
        checkCanceled(cancellation);
        ParcelFileDescriptor descriptor = context.getContentResolver()
                .openFileDescriptor(uri, "r", cancellation);
        if (descriptor == null) return null;
        // Check each read, including TaggedBookPackage's transport copy, without replacing the
        // caller-owned CancellationSignal listener. Copy callers already use bounded 32 KiB blocks;
        // avoid an extra read-ahead buffer that could consume bytes beyond a size-limit probe.
        InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        return new FilterInputStream(input) {
            @Override public int read() throws IOException {
                checkCanceled(cancellation);
                int value = in.read();
                checkCanceled(cancellation);
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                checkCanceled(cancellation);
                int count = in.read(bytes, offset, length);
                checkCanceled(cancellation);
                return count;
            }
        };
    }

    private static String deriveTitle(String displayName, String id) {
        String title = displayName == null ? "" : displayName.trim();
        title = title.replaceFirst("(?i)\\.(txt|epub|pdf)$", "").trim();
        return title.isEmpty() ? "未命名书籍" : title;
    }

    private String identifyFormat(Uri uri, String name) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".epub")) return "epub";
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "txt";
        // MOBI 家族：既有 txt/epub/pdf 分支顺序不动，仅在后面追加。
        if (lower.endsWith(".azw4")) {
            throw new IOException("该格式暂不支持（支持 MOBI / PRC / AZW / AZW3；AZW4 请先转换）。");
        }
        if (lower.endsWith(".mobi") || lower.endsWith(".prc")
                || lower.endsWith(".azw") || lower.endsWith(".azw3")) return "mobi";
        // 阶段 2-B：漫画归档走引用模式入库（cbz/zip + Q3 采纳后的 cbr/rar）
        if (org.openleaf.reader.core.ComicLibrary.isComic(name)) return "comic";
        String mime = context.getContentResolver().getType(uri);
        if ("application/pdf".equals(mime)) return "pdf";
        if ("application/epub+zip".equals(mime)) return "epub";
        if ("text/plain".equals(mime)) return "txt";
        if ("application/x-mobipocket-ebook".equals(mime) || "application/x-mobi8-ebook".equals(mime)
                || "application/vnd.amazon.ebook".equals(mime)) return "mobi";
        throw new IOException("请选择 TXT、EPUB、PDF 或 MOBI 文件");
    }

    private static IOException tooLarge(String format) {
        return new IOException("txt".equals(format) ? "TXT/MD 文件不能超过 " + org.openleaf.reader.core.BookParser.mib(MAX_TXT_FILE)
                : "单个文件不能超过 " + org.openleaf.reader.core.BookParser.mib(MAX_FILE));
    }

    private static String copyAndHash(InputStream input, File destination, String format,
                                      CancellationSignal cancellation, String name, long expected,
                                      ImportProgress progress) throws Exception {
        checkCanceled(cancellation);
        if (input == null) throw new IOException("无法打开所选文件");
        MessageDigest digest = sha256Digest();
        long total = 0;
        long limit = importLimit(format);
        long lastUpdate = progress == null ? 0 : android.os.SystemClock.uptimeMillis();
        publish(progress, name, ImportPhase.COPY, 0, expected);
        try (OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = readChunk(input, buffer, (int) Math.min(buffer.length, limit - total + 1),
                    cancellation)) != -1) {
                if (count > limit - total) throw tooLarge(format);
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                total += count;
                if (progress != null) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastUpdate >= 120) {
                        lastUpdate = now;
                        publish(progress, name, ImportPhase.COPY, total, expected > total ? expected : -1);
                    }
                }
            }
            output.flush();
        }
        checkCanceled(cancellation);
        if (total == 0) throw new IOException("文件为空");
        publish(progress, name, ImportPhase.COPY, total, total);
        checkCanceled(cancellation);
        return hex(digest.digest());
    }

    /** Bound I/O between cancellation checks; tolerate providers returning zero without spinning. */
    private static int readChunk(InputStream input, byte[] buffer, int length,
                                 CancellationSignal cancellation) throws IOException {
        checkCanceled(cancellation);
        int count = input.read(buffer, 0, length);
        checkCanceled(cancellation);
        if (count != 0) return count;
        int next = input.read();
        checkCanceled(cancellation);
        if (next < 0) return -1;
        buffer[0] = (byte) next;
        return 1;
    }

    /**
     * 阶段 2-B：校验漫画归档并取页数/封面。只列条目名、不解压图片内容；
     * RAR/CBR 需要 junrar（阶段二），此处明确拒绝而非静默失败。
     */
    private static org.openleaf.reader.core.ComicFlow.ComicMeta validateComicArchive(File file, String name,
                                                                                     CancellationSignal cancellation)
            throws IOException {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".cbr") || lower.endsWith(".rar")) {
            java.util.List<String> rarEntries = RarArchive.imageEntries(file);
            return org.openleaf.reader.core.ComicFlow.metaFrom(rarEntries);
        }
        java.util.List<String> entries = new java.util.ArrayList<>();
        try (InputStream raw = new FileInputStream(file);
             java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(raw)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (cancellation != null) checkCanceled(cancellation);
                if (!entry.isDirectory()) entries.add(entry.getName());
                if (entries.size() > org.openleaf.reader.core.ComicFlow.MAX_ENTRIES)
                    throw new IOException("压缩包条目数超过上限");
            }
        }
        if (entries.isEmpty()) throw new IOException("压缩包内没有条目");
        return org.openleaf.reader.core.ComicFlow.metaFrom(entries);
    }

    private static void validatePdf(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            byte[] header = new byte[5];
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) throw new IOException("文件不是有效的 PDF");
                offset += read;
            }
            if (!"%PDF-".equals(new String(header, StandardCharsets.US_ASCII)))
                throw new IOException("文件不是有效的 PDF");
        }
    }

    private static Book findByHash(SQLiteDatabase db, String hash) {
        try (Cursor cursor = db.query(TABLE_BOOKS,
                new String[]{"id", "title", "format", "file", "added"},
                "sha256=?", new String[]{hash}, null, null, "order_index ASC", "1")) {
            return cursor.moveToFirst() ? LibraryDatabase.toBook(db, cursor) : null;
        }
    }

    private static long firstOrderIndex(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT MIN(order_index) FROM books", null)) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) return 0;
            return cursor.getLong(0);
        }
    }

    void remove(Book book) throws Exception {
        ensureMigrated();
        SQLiteDatabase db=helper.getWritableDatabase();
        db.beginTransaction();
        try{
            Book current=findById(db,book.id);
            if(current!=null){
                if(!referenced(db,current.id)) {
                    File local=file(current);
                    if(local.exists()&&!local.delete())throw new IOException("应用内文件删除失败，书架记录与阅读进度已保留");
                }
                db.delete(TABLE_BOOKS,"id=?",new String[]{book.id});
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        context.getSharedPreferences("reader",Context.MODE_PRIVATE).edit()
                .remove("chapter_"+book.id).remove("offset_"+book.id)
                .remove("bottom_"+book.id).remove("lineFraction_"+book.id)
                .remove("progress_"+book.id).remove("opened_"+book.id).remove("pdf_page_"+book.id).remove("pdf_count_"+book.id).apply();
    }

    void sample() throws Exception {
        ensureMigrated();
        if (context.getSharedPreferences("reader", Context.MODE_PRIVATE)
                .getBoolean("sample_added", false)) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            Book existing = findById(db, "welcome");
            if (existing != null) {
                context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit()
                        .putBoolean("sample_added", true).apply();
                db.setTransactionSuccessful();
                return;
            }
            File directory = new File(context.getFilesDir(), "books");
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
                throw new IOException("无法创建书库目录");
            File destination = new File(directory, "welcome.txt");
            try (InputStream input = context.getAssets().open("welcome.txt");
                 OutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            String hash = hashFile(destination, null, MAX_TXT_FILE);
            ContentValues row = LibraryDatabase.values("welcome", "从一页开始", "txt",
                    destination.getName(), System.currentTimeMillis(), hash);
            row.put("order_index", firstOrderIndex(db) - 1);
            db.insertOrThrow(TABLE_BOOKS, null, row);
            LibraryDatabase.writeMediaItemShadow(context, db, row); // 3-B Y-1 双写
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit()
                    .putBoolean("sample_added", true).apply();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static Book findById(SQLiteDatabase db, String id) {
        try (Cursor cursor = db.query(TABLE_BOOKS,
                new String[]{"id", "title", "format", "file", "added"},
                "id=?", new String[]{id}, null, null, null, "1")) {
            return cursor.moveToFirst() ? LibraryDatabase.toBook(db, cursor) : null;
        }
    }

    private static String hashFile(File file, CancellationSignal cancellation, long maxBytes)
            throws IOException {
        checkCanceled(cancellation);
        MessageDigest digest = sha256Digest();
        long total = 0;
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_BYTES];
            int count;
            while ((count = readChunk(input, buffer,
                    (int) Math.min(buffer.length, maxBytes - total + 1), cancellation)) != -1) {
                if (count > maxBytes - total) throw new IOException("本地书籍文件超过哈希处理上限");
                total += count;
                digest.update(buffer, 0, count);
            }
        }
        checkCanceled(cancellation);
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("此设备不支持 SHA-256", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = alphabet[value >>> 4];
            chars[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(chars);
    }

    private static void checkCanceled(CancellationSignal cancellation) {
        if (cancellation != null) cancellation.throwIfCanceled();
    }
}
