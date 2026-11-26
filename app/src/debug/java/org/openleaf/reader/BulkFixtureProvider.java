package org.openleaf.reader;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Synthetic documents for opt-in debug instrumentation; never reads personal files. */
public final class BulkFixtureProvider extends DocumentsProvider {
    static final String AUTHORITY = "org.openleaf.reader.debugfixtures";
    static final byte[] SHARED = "第一章 测试\n\n用于验证同内容异名去重和旧书架迁移。\n".getBytes(StandardCharsets.UTF_8);
    private static final java.util.Set<String> DELETED=java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final java.util.Set<String> CHANGED=java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    static void markChanged(String id){CHANGED.add(id);}
    static boolean wasDeleted(String id){return DELETED.contains(id);}
    private static final String[] DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_FLAGS
    };
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        String[] cols = projection != null ? projection : new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS};
        return new MatrixCursor(cols);
    }
    private static void add(MatrixCursor cursor, String id, String name, String mime, long size) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : cursor.getColumnNames()) {
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(col)) row.add(id);
            else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(col)) row.add(name);
            else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(col)) row.add(mime);
            else if (DocumentsContract.Document.COLUMN_SIZE.equals(col)) row.add(size);
            else if (DocumentsContract.Document.COLUMN_FLAGS.equals(col)) row.add(id.startsWith("delete/")?DocumentsContract.Document.FLAG_SUPPORTS_DELETE:0);
            else row.add(null);
        }
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        if(DELETED.contains(id))return cursor;
        String name = id.substring(id.lastIndexOf('/') + 1);
        String mime = name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") ? "application/pdf"
                : name.toLowerCase(java.util.Locale.ROOT).endsWith(".epub") ? "application/epub+zip" : "text/plain";
        add(cursor, id, name, mime, documentSize(id));
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder)
            throws FileNotFoundException {
        if (parent.endsWith("/broken")) throw new FileNotFoundException("Synthetic unreadable directory");
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        String dir = DocumentsContract.Document.MIME_TYPE_DIR;
        if (parent.equals("scan")) {
            add(cursor,"scan/nested","子目录",dir,0);
            add(cursor,"scan/BOOK.TXT","BOOK.TXT","text/plain",64);
            add(cursor,"scan/BOOK.TXT","BOOK.TXT","text/plain",64);
            add(cursor,"scan/image.jpg","image.jpg","image/jpeg",64);
            add(cursor,"scan","循环目录",dir,0);
        } else if (parent.equals("scan/nested")) {
            add(cursor,"scan/nested/book.EPUB","book.EPUB","application/epub+zip",64);
            add(cursor,"scan/nested/paper.pdf","paper.pdf","application/pdf",64);
        } else if (parent.equals("errors")) {
            add(cursor,"errors/broken","无法读取",dir,0);
            add(cursor,"errors/good.txt","good.txt","text/plain",64);
        } else if (parent.equals("bulk") || parent.equals("limit")) {
            int count = parent.equals("bulk") ? 1050 : 10001;
            for (int i=0;i<count;i++) add(cursor,parent+"/book-"+i+".txt","book-"+i+".txt","text/plain",64);
        } else if (parent.startsWith("deep")) {
            int depth = Integer.parseInt(parent.substring(4));
            add(cursor,"deep"+(depth+1),"level-"+(depth+1),dir,0);
        }
        return cursor;
    }
    @Override public boolean isChildDocument(String parent, String child) {
        return child.startsWith(parent+"/") || parent.startsWith("deep") && child.startsWith("deep");
    }
    private static long documentSize(String id) {
        return id.endsWith("/oversize.pdf") ? BookStore.MAX_FILE + 1 : bytes(id).length;
    }
    static File fixtureFile(Context context, String id) {
        File dir=new File(context.getCacheDir(),"bulk-debug-provider");
        return new File(dir,android.util.Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP|android.util.Base64.URL_SAFE));
    }
    private static byte[] bytes(String id) {
        if(CHANGED.contains(id))return ("Changed source: "+id).getBytes(StandardCharsets.UTF_8);
        if(id.endsWith(".pdf"))return "%PDF-1.7\nSynthetic fixture only.\n".getBytes(StandardCharsets.US_ASCII);
        if(id.endsWith("/same-a.txt")||id.endsWith("/same-b.txt"))return SHARED;
        if (id.equals("store/empty.txt")) return new byte[0];
        if (id.equals("store/a.txt") || id.equals("store/b.txt")) return SHARED;
        return ("第一章 测试\n\n独立的测试内容："+id+"\n").getBytes(StandardCharsets.UTF_8);
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if(!id.startsWith("delete/")||id.endsWith("/refuse.txt"))throw new FileNotFoundException("Synthetic delete refused");
        File dir=new File(getContext().getCacheDir(),"bulk-debug-provider");
        File file=new File(dir,android.util.Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8),android.util.Base64.NO_WRAP|android.util.Base64.URL_SAFE));
        try{
            if(!file.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator))throw new FileNotFoundException("Fixture path outside test directory");
        }catch(java.io.IOException invalid){throw new FileNotFoundException(invalid.toString());}
        if(file.exists()&&!file.delete())throw new FileNotFoundException("Could not remove synthetic test file");
        DELETED.add(id);
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancellation)
            throws FileNotFoundException {
        if (cancellation != null) cancellation.throwIfCanceled();
        if(DELETED.contains(id))throw new FileNotFoundException("Synthetic document was deleted");
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only fixture");
        if(id.startsWith("pipe/")) {
            try {
                ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
                new Thread(()->{try(java.io.OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){output.write(bytes(id));}catch(java.io.IOException ignored){}},"openleaf-fixture-pipe").start();
                return pipe[0];
            } catch(java.io.IOException failure){throw new FileNotFoundException(failure.toString());}
        }
        File dir = new File(getContext().getCacheDir(), "bulk-debug-provider");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new FileNotFoundException("Cannot create fixture directory");
        File file = new File(dir, android.util.Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE));
        synchronized (BulkFixtureProvider.class) {
            if (!file.exists()||CHANGED.contains(id)||id.endsWith("/oversize.pdf")&&file.length()!=documentSize(id)) {
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes(id)); }
                catch (java.io.IOException e) { throw new FileNotFoundException(e.toString()); }
                if(id.endsWith("/oversize.pdf"))try(RandomAccessFile sparse=new RandomAccessFile(file,"rw")){
                    sparse.setLength(documentSize(id));
                }catch(java.io.IOException e){throw new FileNotFoundException(e.toString());}
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
