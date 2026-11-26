package org.openleaf.reader;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Streaming recursive scanner for a user-authorized document tree. */
final class DirectoryScanner {
    static final int MAX_DEPTH = 128;
    static final int MAX_ENTRIES = 100000;
    static final int MAX_MATCHES = 10000;
    static final int MAX_ERRORS = 20;
    static final long MAX_SIZE = org.openleaf.reader.core.BookParser.MAX_IMPORT_BYTES;
    static final class Candidate {
        final Uri uri;
        final String name;
        final String relativePath;
        final String format;
        final long size;
        Candidate(Uri uri, String name, String relativePath, String format, long size) {
            this.uri = uri; this.name = name; this.relativePath = relativePath;
            this.format = format; this.size = size < 0L ? -1L : size;
        }
    }
    static final class Result {
        final List<Candidate> candidates = new ArrayList<Candidate>();
        final List<String> errors = new ArrayList<String>();
        int visited;
        String rootName="导入文件夹";
        boolean limitReached;
    }
    interface Listener { void onCandidate(Candidate candidate); void onProgress(int visited, int matches); }
    private static final class Dir { final String id, path; final int depth; Dir(String id, String path, int depth) { this.id=id; this.path=path; this.depth=depth; } }
    private DirectoryScanner() { }

    static Result scan(ContentResolver resolver, Uri tree, CancellationSignal signal, Listener listener) {
        Result result = new Result();
        if (resolver == null || tree == null) { result.errors.add("未选择文件夹"); return result; }
        String rootId;
        try { rootId = DocumentsContract.getTreeDocumentId(tree); }
        catch (RuntimeException e) { result.errors.add("无法读取文件夹授权：" + message(e)); return result; }
        ArrayDeque<Dir> queue = new ArrayDeque<Dir>();
        Set<String> seen = new HashSet<String>();
        if (rootId == null || rootId.length() == 0) { result.errors.add("文件夹授权缺少目录标识"); return result; }
        String fallback=rootId.substring(Math.max(rootId.lastIndexOf('/'),rootId.lastIndexOf(':'))+1);
        if(!fallback.trim().isEmpty())result.rootName=fallback;
        try(Cursor metadata=resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree,rootId),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null,signal)){
            if(metadata!=null&&metadata.moveToFirst()){
                String name=metadata.getString(0);
                if(name!=null&&!name.trim().isEmpty())result.rootName=name;
            }
        }catch(RuntimeException unavailable){
            if(cancelled(signal)){addError(result,"扫描已取消");return result;}
            // A provider may allow child queries while omitting optional root metadata.
        }
        queue.add(new Dir(rootId, "", 0));
        seen.add(rootId);
        while (!queue.isEmpty()) {
            if (cancelled(signal)) break;
            Dir dir = queue.removeFirst();
            if (dir.depth > MAX_DEPTH) { addError(result, "目录层级超过 " + MAX_DEPTH + " 层：" + dir.path); continue; }
            Uri children;
            try { children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir.id); }
            catch (RuntimeException e) { addError(result, "无法读取目录：" + display(dir.path, e)); continue; }
            String[] projection = new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE };
            Cursor cursor = null;
            try {
                cursor = resolver.query(children, projection, null, null, null, signal);
                if (cursor == null) { addError(result, "目录查询返回空：" + pathOrRoot(dir.path)); continue; }
                int idCol=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int mimeCol=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int nameCol=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int sizeCol=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                while (cursor.moveToNext()) {
                    if (cancelled(signal)) break;
                    if (++result.visited > MAX_ENTRIES) { result.limitReached=true; addError(result,"扫描条目超过 100000 个，已停止"); break; }
                    if (idCol < 0) { addError(result,"目录查询缺少文档标识"); break; }
                    String id=cursor.getString(idCol); if (id == null || id.length()==0 || !seen.add(id)) continue;
                    String name=nameCol<0?"未命名":cursor.getString(nameCol); if (name==null||name.length()==0) name="未命名";
                    String mime=mimeCol<0?null:cursor.getString(mimeCol);
                    String rel=dir.path.length()==0?name:dir.path+"/"+name;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (dir.depth + 1 > MAX_DEPTH) addError(result,"目录层级超过 "+MAX_DEPTH+" 层："+rel);
                        else queue.addLast(new Dir(id,rel,dir.depth+1));
                    } else {
                        String format=formatFor(name,mime);
                        if (format != null) {
                            if (result.candidates.size() >= MAX_MATCHES) { result.limitReached=true; addError(result,"匹配文件超过 10000 个，已停止"); break; }
                            long size=sizeCol<0||cursor.isNull(sizeCol)?-1L:cursor.getLong(sizeCol);
                            Candidate c=new Candidate(DocumentsContract.buildDocumentUriUsingTree(tree,id),name,rel,format,size);
                            result.candidates.add(c); if(listener!=null) listener.onCandidate(c);
                        }
                    }
                    if (listener!=null && (result.visited % 64 == 0)) listener.onProgress(result.visited,result.candidates.size());
                }
            } catch (SecurityException e) { if (!cancelled(signal)) addError(result,"没有权限读取目录："+display(dir.path,e)); }
              catch (android.os.OperationCanceledException e) { if (!cancelled(signal)) addError(result,"目录查询被系统取消："+pathOrRoot(dir.path)); }
              catch (RuntimeException e) { if (!cancelled(signal)) addError(result,"目录读取失败："+display(dir.path,e)); }
              finally { if (cursor != null) { try { cursor.close(); } catch (RuntimeException ignored) { } } }
            if (result.limitReached) break;
            if (listener!=null) listener.onProgress(result.visited,result.candidates.size());
        }
        if (cancelled(signal)) addError(result,"扫描已取消，已保留已发现文件");
        if (result.candidates.isEmpty() && result.errors.isEmpty()) result.errors.add("授权文件夹中没有找到 TXT、EPUB 或 PDF 文件");
        return result;
    }
    private static String formatFor(String name, String mime) {
        String lower=name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "txt";
        if (lower.endsWith(".epub")) return "epub";
        if (lower.endsWith(".pdf")) return "pdf";
        if ("text/plain".equals(mime)) return "txt";
        if ("application/epub+zip".equals(mime)) return "epub";
        if ("application/pdf".equals(mime)) return "pdf";
        return null;
    }
    private static boolean cancelled(CancellationSignal s) { return s != null && s.isCanceled(); }
    private static void addError(Result r,String s) { if (r.errors.size()<MAX_ERRORS) r.errors.add(s); }
    private static String pathOrRoot(String p) { return p.length()==0?"根目录":p; }
    private static String display(String p,Exception e) { return pathOrRoot(p)+"（"+message(e)+"）"; }
    private static String message(Exception e) { String s=e.getMessage(); return s==null||s.length()==0?e.getClass().getSimpleName():s; }
}
