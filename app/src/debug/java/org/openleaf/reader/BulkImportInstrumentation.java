package org.openleaf.reader;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in debug-only regression runner. Every book/database fixture has an isolated context. */
public final class BulkImportInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private int passed;
    private interface Check { void run() throws Exception; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check("recursive formats, case, duplicate document IDs and directory cycle", this::recursiveScan);
            check("unreadable subdirectory preserves accessible results", this::partialScan);
            check("1050 scan candidates exceed previous 20-file limit", this::largeScan);
            check("candidate limit is explicit rather than silently truncated", this::scanLimit);
            check("directory depth limit terminates safely", this::depthLimit);
            check("cancel during scan preserves found candidates", this::cancelScan);
            check("legacy JSON migration preserves IDs and reading progress", this::migration);
            check("legacy AtomicFile backup-only migration", this::backupMigration);
            check("malformed legacy index is rejected", this::brokenMigration);
            check("large-file bounded copy/hash and progress callbacks", BookStoreImportChecks::run);
            check("same content different filenames is skipped without resetting progress", this::duplicateImport);
            check("PDF import callback phases and duplicate short path", this::pdfProgressAndDuplicate);
            check("known-size oversized PDF rejected before copy", this::oversizedPdfPreflight);
            check("cancel and invalid imports leave no book or copied file", this::cancelImport);
            check("1050 unique files import once and repeat imports are skipped", this::largeImport);
            check("nested folder archive and direct shelf browsing", this::folderHierarchy);
            check("same content in two folders shares book and progress", this::folderDuplicate);
            check("same labels from different source trees remain separate", this::folderSources);
            check("database v1 upgrade preserves books and progress as unclassified", this::databaseUpgrade);
            check("folder paths reject parent traversal", this::folderTraversal);
            check("cancel and removal keep folder membership consistent", this::folderCleanup);
            check("database v2 upgrade preserves existing folder associations", this::sourceSchemaUpgrade);
            check("copy-only deletion leaves source document intact", this::copyOnlyDeletion);
            check("original deletion succeeds before removing library copy", this::originalDeletion);
            check("changed or wrong source is rejected without deleting books", this::changedSourceDeletion);
            check("provider deletion failure preserves library and source record", this::refusedSourceDeletion);
            check("selected duplicate source deletion preserves unselected originals", this::selectedSourceDeletion);
            check("old books can attach verified source without resetting progress", this::manualSourceAttachment);
            check("cancelled original deletion leaves original and library intact", this::cancelSourceDeletion);
            check("manual nested folder creation and naming rules", this::manualFolderNames);
            check("renamed source labels survive rescanning", this::renamedSourceLabel);
            check("batch archive and move preserve other memberships and progress", this::batchOrganize);
            check("global move replaces memberships without copying books", this::globalOrganize);
            check("unfile only removes selected folder associations", this::unfileSelection);
            check("deleting folder tree preserves books, sources and reading progress", this::deleteFolderTree);
            check("invalid batch operations roll back all membership changes", this::organizeRollback);
            check("database v3 upgrade preserves all records for manual organization", this::organizeSchemaUpgrade);
            check("tagged book package integrity and bounds", TaggedBookPackageChecks::run);
            check("tagged book import and transactional merge", this::taggedImport);
            check("metadata defaults and complete save round trip", this::metadataRoundTrip);
            check("invalid metadata and failed tag writes roll back title and tags", this::metadataRollback);
            check("metadata preserves classification unless explicitly replaced or unclassified", this::metadataClassification);
            check("metadata accepts private covers and rejects invalid cover references", this::metadataCovers);
            check("database v5 to v8 metadata upgrade preserves existing records", this::metadataSchemaUpgrade);
            check("review Unicode limits, clear and atomic metadata rollback", this::reviewSave);
            check("review package import, duplicate conflict and idea parking", this::reviewExchange);
            check("database v6 upgrade preserves metadata and creates empty reviews", this::reviewUpgrade);
            check("reference import reopens source without permanent copies and removal preserves source", this::referenceRead);
            check("changed and missing references fail safely and matching source rebinds", this::referenceRebind);
            check("nonseekable source uses bounded lifetime-scoped cache", this::referencePipe);
            check("database v7 upgrade preserves old copies and reviews", this::referenceUpgrade);
            result.putString("stream", "\nPASS: "+passed+" bulk integration checks\n"+report);
            result.putInt("passed", passed);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after "+passed+" checks\n"+report+android.util.Log.getStackTraceString(error));
            result.putString("failure", error.toString());
            finish(1, result);
        }
    }
    private void taggedImport() throws Exception {
        FixtureContext c = fixture();
        BookStore store = new BookStore(c);
        File original = File.createTempFile("book-fixture-", ".txt", c.getCacheDir());
        File packageFile = File.createTempFile("book-fixture-", ".olbook", c.getCacheDir());
        try {
            java.nio.file.Files.write(original.toPath(), "第一章 样书\n仅供测试。".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(packageFile)) {
                TaggedBookPackage.write(out, original, "书包样本", "txt", java.util.Arrays.asList("历史", "Test"));
            }
            BookStore.ImportResult first = store.importTagged(Uri.fromFile(packageFile), null);
            require(!first.duplicate && "书包样本".equals(first.book.title) && store.tags(first.book.id).size() == 2, "new package metadata lost");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(packageFile)) {
                TaggedBookPackage.write(out, original, "不同书名", "txt", java.util.Arrays.asList("test", "待读"));
            }
            BookStore.ImportResult repeat = store.importTagged(Uri.fromFile(packageFile), null);
            require(repeat.duplicate && repeat.book.id.equals(first.book.id), "duplicate book created");
            require(store.tags(first.book.id).size() == 3 && store.tags(first.book.id).contains("待读"), "tag merge incorrect");
            require(store.sources(first.book.id).isEmpty() && "书包样本".equals(store.find(first.book.id).title), "original metadata overwritten");
        } finally {
            original.delete(); packageFile.delete();
        }
    }

    private static String reviewText(String unit, int count) { return String.join("", java.util.Collections.nCopies(count, unit)); }
    private void reviewSave() throws Exception {
        FixtureContext c = fixture(); BookStore books = new BookStore(c); books.sample();
        BookStore.Book book = books.all().get(0); BookMetadataStore metadata = new BookMetadataStore(c);
        String review = reviewText("📚", 200);
        metadata.save(book.id, book.title, "", "", "", "", false, java.util.Collections.emptyList(), false, null, review);
        require(review.equals(metadata.get(book.id).review), "supplementary characters counted incorrectly");
        for (int count : new int[]{199,301}) {
            boolean rejected = false;
            try { metadata.save(book.id, "不应保存", "", "", "", "", false, java.util.Collections.emptyList(), false, null, reviewText("评",count)); }
            catch (java.io.IOException expected) { rejected = true; }
            require(rejected && metadata.get(book.id).review.equals(review) && metadata.get(book.id).book.title.equals(book.title), "invalid review partially saved metadata");
        }
        metadata.save(book.id, book.title, "", "", "", "", false, java.util.Collections.emptyList(), false, null, reviewText("评",300));
        require(metadata.get(book.id).review.length()==300, "300 character review rejected");
        metadata.save(book.id, book.title, "", "", "", "", false, java.util.Collections.emptyList(), false, null, "");
        require(metadata.get(book.id).review.isEmpty(), "review clear failed");
    }
    private void reviewExchange() throws Exception {
        FixtureContext c=fixture(); BookStore books=new BookStore(c); BookMetadataStore metadata=new BookMetadataStore(c);
        File content=File.createTempFile("review-fixture-", ".txt",c.getCacheDir()), pack=File.createTempFile("review-fixture-", ".olbook",c.getCacheDir());
        try {
            java.nio.file.Files.write(content.toPath(), "第一章 书评测试\n仅用于隔离回归".getBytes(StandardCharsets.UTF_8));
            String review=reviewText("读",200), foreign=reviewText("异",200);
            try(FileOutputStream out=new FileOutputStream(pack)){TaggedBookPackage.write(out,content,"书评测试","txt",java.util.Arrays.asList("原标签"),review);}
            BookStore.Book book=books.importTagged(Uri.fromFile(pack),null).book;
            require(metadata.get(book.id).review.equals(review),"new import lost review");
            require(books.importTagged(Uri.fromFile(pack),null).duplicate,"identical review did not deduplicate");
            try(FileOutputStream out=new FileOutputStream(pack)){TaggedBookPackage.write(out,content,"书评测试","txt",java.util.Arrays.asList("不得添加"),foreign);}
            // BookStore.applyImportMetadata: the conflicting review is parked as an idea instead of
            // replacing the local review, and the import itself succeeds (no IOException).
            boolean rejected=false;try{books.importTagged(Uri.fromFile(pack),null);}catch(java.io.IOException expected){rejected=true;}
            java.util.List<IdeaStore.Idea> parked=new IdeaStore(c).list();
            require(!rejected,"conflicting review must not abort the import");
            require(metadata.get(book.id).review.equals(review),"conflicting review overwrote the local review");
            require(parked.size()==1&&parked.get(0).review.equals(foreign),
                    "conflicting review must be parked as exactly one idea carrying the incoming text, got "+parked.size());
            // applyImportMetadata merges tags after the review branch and never returns early there,
            // so a review conflict must not suppress the tag merge.
            require(books.tags(book.id).contains("不得添加"),"conflicting review aborted the tag merge");
            // A legacy/empty-review package must preserve an existing local review.
            try(FileOutputStream out=new FileOutputStream(pack)){TaggedBookPackage.write(out,content,"书评测试","txt",java.util.Collections.emptyList());}
            books.importTagged(Uri.fromFile(pack),null);
            require(metadata.get(book.id).review.equals(review),"empty package erased existing review");
        } finally {content.delete();pack.delete();}
    }
    private void reviewUpgrade() throws Exception {
        FixtureContext c=fixture(); File path=c.getDatabasePath("openleaf-library.db"); path.getParentFile().mkdirs();
        try(SQLiteDatabase old=SQLiteDatabase.openOrCreateDatabase(path,null)) {
            old.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT,format TEXT,file TEXT,added INTEGER,sha256 TEXT,order_index INTEGER)");
            old.execSQL("INSERT INTO books VALUES('legacy','旧书','txt','legacy.txt',1,NULL,0)");
            old.execSQL("CREATE TABLE book_metadata(book_id TEXT PRIMARY KEY,author TEXT,description TEXT,cover_text TEXT,cover_name TEXT,favorite INTEGER)");
            old.execSQL("INSERT INTO book_metadata VALUES('legacy','原作者','原简介','','',1)"); old.setVersion(6);
        }
        SQLiteDatabase upgraded=LibraryDatabase.get(c).getWritableDatabase();
        require(upgraded.getVersion()==LibraryDatabase.schemaVersion() && BookMetadataStore.readReview(upgraded,"legacy").isEmpty(),"v6 review migration failed");
        try(android.database.Cursor row=upgraded.rawQuery("SELECT author,favorite FROM book_metadata WHERE book_id='legacy'",null)){
            require(row.moveToFirst() && row.getString(0).equals("原作者") && row.getInt(1)==1,"v6 metadata changed");
        }
    }

    private void referenceRead() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);String id="delete/reference/read.txt";
        Uri uri=document(id);BookStore.Book book=store.importUri(uri,new CancellationSignal()).book;
        require(store.isReferenced(book)&&uri.equals(store.sourceUri(book))&&fileCount(c)==0,"reference import created private copy");
        require(store.size(book)>0,"reference stat unavailable");
        for(int i=0;i<2;i++)try(BookAccess access=store.open(book)){
            require(!access.temporary && access.descriptor!=null && java.nio.file.Files.readAllBytes(access.file.toPath()).length>0,"reference did not directly reopen seekable provider");
        }
        boolean blocked=false;try{store.file(book);}catch(java.io.IOException expected){blocked=true;}
        require(blocked,"file() accidentally exposed a reference as a private file");
        store.remove(book);
        try(android.os.ParcelFileDescriptor fd=c.getContentResolver().openFileDescriptor(uri,"r")){require(fd!=null,"shelf removal deleted source");}
        require(store.all().isEmpty()&&!BulkFixtureProvider.wasDeleted(id),"reference removal deleted original");
    }
    private void referenceRebind() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);
        String run = "delete/reference-rebind-" + java.util.UUID.randomUUID();
        String first = run + "/same-a.txt", second = run + "/same-b.txt";
        BookStore.Book book=store.importUri(document(first),new CancellationSignal()).book;
        c.getSharedPreferences("reader",0).edit().putInt("progress_"+book.id,37).commit();
        BulkFixtureProvider.markChanged(first);
        boolean changed=false;try(BookAccess ignored=store.open(book)){}catch(java.io.IOException expected){changed=expected.getMessage().contains("变化");}
        require(changed,"changed content reused old progress without rejection");
        store.attachSource(book.id,document(second),new CancellationSignal());
        require(store.sourceUri(book).equals(document(second)),"attachSource did not repair broken reference");
        try(BookAccess ignored=store.open(book)){}
        require(c.getSharedPreferences("reader",0).getInt("progress_"+book.id,0)==37,"rebind lost reading progress");
        DocumentsContract.deleteDocument(c.getContentResolver(),document(second));
        boolean missing=false;try(BookAccess ignored=store.open(book)){}catch(java.io.IOException expected){missing=true;}
        require(missing,"missing source did not fail clearly");
        Uri third=document("delete/reference-rebind-new/same-a.txt");
        BookStore.ImportResult restored=store.importUri(third,new CancellationSignal());
        require(restored.duplicate&&restored.book.id.equals(book.id)&&third.equals(store.sourceUri(book))&&fileCount(c)==0,"reimport did not rebind same-hash reference");
        try(BookAccess ignored=store.open(book)){}
    }
    private void referencePipe() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);
        BookStore.Book book=store.importUri(document("pipe/reference.txt"),new CancellationSignal()).book;
        File cached;
        try(BookAccess access=store.open(book)){
            cached=access.file;
            require(access.temporary&&access.descriptor!=null&&cached.isFile()&&cached.getName().startsWith("openleaf-source-"),"pipe did not use declared ephemeral cache");
        }
        require(!cached.exists()&&fileCount(c)==0,"temporary access leaked permanent or live cache file");
    }
    private void referenceUpgrade() throws Exception {
        FixtureContext c=fixture();File path=c.getDatabasePath("openleaf-library.db");path.getParentFile().mkdirs();
        try(SQLiteDatabase old=SQLiteDatabase.openOrCreateDatabase(path,null)){
            old.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT,format TEXT,file TEXT,added INTEGER,sha256 TEXT,order_index INTEGER)");
            old.execSQL("INSERT INTO books VALUES('legacy','旧副本','txt','legacy.txt',1,NULL,0)");
            old.execSQL("CREATE TABLE book_reviews(book_id TEXT PRIMARY KEY,review TEXT)");
            old.execSQL("INSERT INTO book_reviews VALUES('legacy','"+reviewText("评",200)+"')");old.setVersion(7);
        }
        SQLiteDatabase upgraded=LibraryDatabase.get(c).getWritableDatabase();
        require(upgraded.getVersion()==LibraryDatabase.schemaVersion()&&!BookMetadataStore.readReview(upgraded,"legacy").isEmpty(),"v7 reference upgrade lost review");
        try(android.database.Cursor row=upgraded.rawQuery("SELECT file FROM books WHERE id='legacy'",null)){require(row.moveToFirst()&&row.getString(0).equals("legacy.txt"),"upgrade changed old file identity");}
        try(android.database.Cursor row=upgraded.rawQuery("SELECT COUNT(*) FROM book_references",null)){require(row.moveToFirst()&&row.getInt(0)==0,"upgrade turned old copies into references");}
    }

    private void check(String name, Check action) throws Exception {
        long start = android.os.SystemClock.elapsedRealtime();
        action.run(); passed++;
        report.append("PASS ").append(name).append(" (")
                .append(android.os.SystemClock.elapsedRealtime()-start).append(" ms)\n");
        Bundle status = new Bundle(); status.putString("stream", "PASS "+name+"\n"); sendStatus(0,status);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private Uri tree(String id) { return DocumentsContract.buildTreeDocumentUri(BulkFixtureProvider.AUTHORITY,id); }
    private Uri document(String id) { return DocumentsContract.buildDocumentUri(BulkFixtureProvider.AUTHORITY,id); }
    private DirectoryScanner.Result scan(String root, CancellationSignal signal, DirectoryScanner.Listener listener) {
        return DirectoryScanner.scan(getTargetContext().getContentResolver(),tree(root),signal,listener);
    }
    private void recursiveScan() {
        DirectoryScanner.Result r=scan("scan",new CancellationSignal(),null);
        require(r.candidates.size()==3,"expected exactly three formats; got "+r.candidates.size());
        boolean nested=false;for(DirectoryScanner.Candidate c:r.candidates)if(c.relativePath.contains("/"))nested=true;
        require(nested,"nested directory was not traversed");require(r.visited<20,"cycle was not bounded");
    }
    private void partialScan() {
        DirectoryScanner.Result r=scan("errors",new CancellationSignal(),null);
        require(r.candidates.size()==1&&!r.errors.isEmpty(),"unreadable directory must be reported without discarding accessible file");
    }
    private void largeScan() { require(scan("bulk",new CancellationSignal(),null).candidates.size()==1050,"large scan was truncated"); }
    private void scanLimit() {
        DirectoryScanner.Result r=scan("limit",new CancellationSignal(),null);
        require(r.candidates.size()==10000&&r.limitReached&&!r.errors.isEmpty(),"candidate limit missing or silent");
    }
    private void depthLimit() {
        DirectoryScanner.Result r=scan("deep0",new CancellationSignal(),null);
        require(r.visited<=130&&!r.errors.isEmpty(),"depth limit was not enforced");
    }
    private void cancelScan() {
        CancellationSignal cancellation=new CancellationSignal();final int[] count={0};
        DirectoryScanner.Result r=scan("bulk",cancellation,new DirectoryScanner.Listener(){
            public void onCandidate(DirectoryScanner.Candidate c){if(++count[0]==5)cancellation.cancel();}
            public void onProgress(int visited,int matches){}
        });
        require(r.candidates.size()==5&&cancellation.isCanceled(),"cancel did not stop scan promptly");
    }
    private FixtureContext fixture() { return new FixtureContext(getTargetContext()); }
    private void legacy(FixtureContext c, boolean backup) throws Exception {
        File books=new File(c.getFilesDir(),"books");if(!books.mkdirs()&&!books.isDirectory())throw new java.io.IOException("fixture directory");
        try(FileOutputStream out=new FileOutputStream(new File(books,"legacy.txt"))){out.write(BulkFixtureProvider.SHARED);}
        JSONObject book=new JSONObject();book.put("id","legacy");book.put("title","旧书籍");book.put("format","txt");book.put("file","legacy.txt");book.put("added",1234L);
        JSONArray array=new JSONArray();array.put(book);
        try(FileOutputStream out=new FileOutputStream(new File(c.getFilesDir(),backup?"library.json.bak":"library.json"))){out.write(array.toString().getBytes(StandardCharsets.UTF_8));}
        c.getSharedPreferences("reader",0).edit().putInt("progress_legacy",42).putInt("chapter_legacy",3).commit();
    }
    private void migration() throws Exception {
        FixtureContext c=fixture();legacy(c,false);BookStore store=new BookStore(c);
        require(store.all().size()==1&&store.find("legacy").file.equals("legacy.txt"),"legacy record changed");
        require(c.getSharedPreferences("reader",0).getInt("progress_legacy",0)==42,"progress reset");
        store.prepareImport(new CancellationSignal());
        require(store.importUri(document("store/a.txt"),new CancellationSignal()).duplicate,"legacy content hash not migrated");
        require(store.all().size()==1,"legacy duplicate inserted");
    }
    private void backupMigration() throws Exception {
        FixtureContext c=fixture();legacy(c,true);require(new BookStore(c).all().size()==1,"backup-only index was not recovered");
    }
    private void brokenMigration() throws Exception {
        FixtureContext c=fixture();
        try(FileOutputStream out=new FileOutputStream(new File(c.getFilesDir(),"library.json"))){out.write("{broken".getBytes(StandardCharsets.UTF_8));}
        boolean failed=false;try{new BookStore(c).all();}catch(Exception expected){failed=true;}
        require(failed,"corrupt index was silently replaced with empty library");
    }
    private void duplicateImport() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);store.prepareImport(new CancellationSignal());
        BookStore.ImportResult first=store.importUri(document("store/a.txt"),new CancellationSignal());
        c.getSharedPreferences("reader",0).edit().putInt("progress_"+first.book.id,73).commit();
        BookStore.ImportResult second=store.importUri(document("store/b.txt"),new CancellationSignal());
        require(!first.duplicate&&second.duplicate&&first.book.id.equals(second.book.id),"same bytes were not deduplicated");
        require(store.all().size()==1&&fileCount(c)==0,"duplicate left extra record or file");
        require(c.getSharedPreferences("reader",0).getInt("progress_"+first.book.id,0)==73,"duplicate reset old progress");
    }
    private void pdfProgressAndDuplicate() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);CancellationSignal signal=new CancellationSignal();
        Uri uri=document("progress/near-limit.pdf");long expected;
        try(android.database.Cursor size=c.getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_SIZE},null,null,null)) {
            require(size!=null&&size.moveToFirst(),"PDF fixture size unavailable");expected=size.getLong(0);
        }
        java.util.ArrayList<String> events=new java.util.ArrayList<>();
        BookStore.ImportProgress progress=(name,phase,bytes,total)->events.add(name+"|"+phase+"|"+bytes+"|"+total);
        BookStore.ImportResult first=store.importUri(uri,signal,null,progress);
        require(!first.duplicate&&events.size()>=2&&fileCount(c)==0,
                "PDF import did not copy, report progress and commit once");
        String name = "near-limit.pdf|";
        require(events.get(0).equals(name+"COPY|0|-1") && events.get(1).equals(name+"COPY|0|"+expected),
                "known-size opening callbacks did not report opening then stat size: "+events);
        require(events.contains(name+"COPY|"+expected+"|"+expected)
                        && events.get(events.size()-2).equals(name+"VALIDATE|0|-1")
                        && events.get(events.size()-1).equals(name+"SAVE|0|-1"),
                "PDF copy did not complete and proceed to validation and save: "+events);
        java.util.ArrayList<String> repeatEvents=new java.util.ArrayList<>();
        BookStore.ImportResult duplicate=store.importUri(uri,signal,null,
                (file,phase,bytes,total)->repeatEvents.add(file+"|"+phase+"|"+bytes+"|"+total));
        require(duplicate.duplicate&&duplicate.book.id.equals(first.book.id)&&fileCount(c)==0
                &&store.all().size()==1,"duplicate PDF created an extra library copy");
        require(repeatEvents.contains(name+"COPY|"+expected+"|"+expected)
                        && !repeatEvents.contains(name+"VALIDATE|0|-1")
                        && repeatEvents.get(repeatEvents.size()-1).equals(name+"SAVE|0|-1"),
                "duplicate PDF did not follow copy-then-save path: "+repeatEvents);
        File fixture=BulkFixtureProvider.fixtureFile(getTargetContext(),"progress/near-limit.pdf");
        if(fixture.exists())require(fixture.delete(),"cannot clean synthetic PDF fixture");
    }
    private void oversizedPdfPreflight() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);Uri uri=document("oversize/oversize.pdf");
        boolean rejected=false;
        // 上限文案必须按产品真实常量算：这里曾写死 "80 MiB"，而 BookStore.MAX_FILE 自 0.13.0 起
        // 就是 512 MiB，断言与产品恒不相等，于是本项长期误判为失败并中断整套检查。
        String limitText=(BookStore.importLimit("pdf")/(1024*1024))+" MiB 上限";
        try{store.importUri(uri,new CancellationSignal());}catch(java.io.IOException expected){
            rejected=expected.getMessage()!=null&&expected.getMessage().contains(limitText);
        }
        require(rejected&&store.all().isEmpty()&&fileCount(c)==0,
                "known-size oversized file was not preflight-rejected without a library stage");
        File fixture=BulkFixtureProvider.fixtureFile(getTargetContext(),"oversize/oversize.pdf");
        if(fixture.exists())require(fixture.delete(),"cannot clean sparse oversize fixture");
    }
    private void cancelImport() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);store.prepareImport(new CancellationSignal());
        CancellationSignal cancelled=new CancellationSignal();cancelled.cancel();boolean stopped=false;
        try{store.importUri(document("store/a.txt"),cancelled);}catch(OperationCanceledException expected){stopped=true;}
        require(stopped&&store.all().isEmpty()&&fileCount(c)==0,"cancelled import changed library");
        boolean failed=false;try{store.importUri(document("store/empty.txt"),new CancellationSignal());}catch(Exception expected){failed=true;}
        require(failed&&store.all().isEmpty()&&fileCount(c)==0,"invalid import left staged file or record");
    }
    private void largeImport() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);CancellationSignal signal=new CancellationSignal();store.prepareImport(signal);
        DirectoryScanner.Result files=scan("bulk",signal,null);
        int added=0;for(DirectoryScanner.Candidate file:files.candidates)if(!store.importUri(file.uri,signal).duplicate)added++;
        require(added==1050&&store.all().size()==1050,"bulk import stopped before all files committed");
        int duplicates=0;for(int i=0;i<30;i++)if(store.importUri(files.candidates.get(i).uri,signal).duplicate)duplicates++;
        require(duplicates==30&&store.all().size()==1050,"repeat import changed bulk library");
    }
    private static BookStore.Folder namedFolder(BookStore.ShelfContents shelf, String name) {
        for(BookStore.Folder folder:shelf.folders)if(name.equals(folder.name))return folder;
        throw new AssertionError("Missing folder: "+name);
    }
    private void folderHierarchy() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);CancellationSignal signal=new CancellationSignal();
        String leaf=store.ensureImportFolder(tree("library"),"Books","小说/科幻");
        require(leaf.equals(store.ensureImportFolder(tree("library"),"Books","小说/科幻")),"rescan duplicated folder hierarchy");
        BookStore.ImportResult imported=store.importUri(document("store/folder-one.txt"),signal,leaf);
        BookStore.Folder root=namedFolder(store.shelf(null),"Books");
        BookStore.Folder novels=namedFolder(store.shelf(root.id),"小说");
        BookStore.Folder science=namedFolder(store.shelf(novels.id),"科幻");
        require(science.id.equals(leaf)&&science.bookCount==1,"leaf identity or direct book count incorrect");
        require(store.shelf(root.id).books.isEmpty(),"nested books leaked into root direct listing");
        require(store.shelf(leaf).books.size()==1&&store.shelf(leaf).books.get(0).id.equals(imported.book.id),"nested book missing");
        require(store.shelf(BookStore.UNCLASSIFIED).books.isEmpty(),"classified book also listed as unclassified");
    }
    private void folderDuplicate() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);CancellationSignal signal=new CancellationSignal();
        String first=store.ensureImportFolder(tree("duplicates"),"Books","小说");
        String second=store.ensureImportFolder(tree("duplicates"),"Books","收藏");
        BookStore.ImportResult one=store.importUri(document("store/a.txt"),signal,first);
        c.getSharedPreferences("reader",0).edit().putInt("progress_"+one.book.id,68).commit();
        BookStore.ImportResult two=store.importUri(document("store/b.txt"),signal,second);
        require(two.duplicate&&two.book.id.equals(one.book.id)&&store.all().size()==1&&fileCount(c)==0,"folder duplicate copied content");
        require(store.shelf(first).books.size()==1&&store.shelf(second).books.size()==1,"duplicate did not acquire second membership");
        require(c.getSharedPreferences("reader",0).getInt("progress_"+one.book.id,0)==68,"folder reassignment reset progress");
        store.importUri(document("store/b.txt"),signal,second);
        require(store.shelf(second).books.size()==1,"rescan duplicated folder membership");
    }
    private void folderSources() throws Exception {
        BookStore store=new BookStore(fixture());
        String a=store.ensureImportFolder(tree("source-a"),"同名目录","");
        String b=store.ensureImportFolder(tree("source-b"),"同名目录","");
        require(!a.equals(b)&&store.shelf(null).folders.size()==2,"different source trees merged by label");
    }
    private void databaseUpgrade() throws Exception {
        FixtureContext c=fixture();legacy(c,false);
        SQLiteDatabase old=c.openOrCreateDatabase("openleaf-library.db",0,null);
        try{
            old.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,format TEXT NOT NULL,file TEXT NOT NULL,added INTEGER NOT NULL,sha256 TEXT,order_index INTEGER NOT NULL)");
            old.execSQL("CREATE TABLE library_meta (key TEXT PRIMARY KEY NOT NULL,value INTEGER NOT NULL)");
            old.execSQL("INSERT INTO books VALUES ('legacy','旧书籍','txt','legacy.txt',1234,NULL,0)");
            old.execSQL("INSERT INTO library_meta VALUES ('legacy_json_migrated_v1',1)");
            old.setVersion(1);
        }finally{old.close();}
        BookStore store=new BookStore(c);
        require(store.all().size()==1&&store.find("legacy").file.equals("legacy.txt"),"v1 upgrade lost old book");
        BookStore.Folder uncategorized=namedFolder(store.shelf(null),"未分类");
        require(uncategorized.id.equals(BookStore.UNCLASSIFIED)&&store.shelf(uncategorized.id).books.size()==1,"old books not available in unclassified");
        require(c.getSharedPreferences("reader",0).getInt("progress_legacy",0)==42,"v1 upgrade reset progress");
        store.prepareImport(new CancellationSignal());
        String folder=store.ensureImportFolder(tree("legacy-root"),"Books","");
        require(store.importUri(document("store/a.txt"),new CancellationSignal(),folder).duplicate,"re-scan recopied legacy content");
        require(store.shelf(folder).books.size()==1&&store.shelf(BookStore.UNCLASSIFIED).books.isEmpty(),"old book could not be archived by re-scan");
    }
    private void folderTraversal() throws Exception {
        BookStore store=new BookStore(fixture());
        for(String path:new String[]{"../escape","小说/../escape","./escape","/absolute"}){
            boolean rejected=false;
            try{store.ensureImportFolder(tree("invalid"),"Books",path);}catch(Exception expected){rejected=true;}
            require(rejected,"folder accepted non-relative or traversal path: "+path);
        }
    }
    private void folderCleanup() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);CancellationSignal signal=new CancellationSignal();
        String first=store.ensureImportFolder(tree("cleanup"),"Books","A");
        String second=store.ensureImportFolder(tree("cleanup"),"Books","B");
        CancellationSignal cancelled=new CancellationSignal();cancelled.cancel();
        try{store.importUri(document("store/a.txt"),cancelled,first);throw new AssertionError("cancel did not throw");}catch(OperationCanceledException expected){}
        require(store.shelf(first).books.isEmpty()&&fileCount(c)==0,"cancel linked staged book");
        BookStore.Book book=store.importUri(document("store/a.txt"),signal,first).book;
        store.importUri(document("store/b.txt"),signal,second);
        store.remove(book);
        require(store.shelf(first).books.isEmpty()&&store.shelf(second).books.isEmpty()&&store.all().isEmpty()&&fileCount(c)==0,"remove left dangling memberships");
    }
    private void sourceSchemaUpgrade() throws Exception {
        FixtureContext c=fixture();legacy(c,false);SQLiteDatabase old=c.openOrCreateDatabase("openleaf-library.db",0,null);
        try{
            old.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,format TEXT NOT NULL,file TEXT NOT NULL,added INTEGER NOT NULL,sha256 TEXT,order_index INTEGER NOT NULL)");
            old.execSQL("CREATE TABLE library_meta (key TEXT PRIMARY KEY NOT NULL,value INTEGER NOT NULL)");
            old.execSQL("CREATE TABLE folders (id TEXT PRIMARY KEY NOT NULL,parent_id TEXT,name TEXT NOT NULL,source_tree TEXT NOT NULL,relative_path TEXT NOT NULL,UNIQUE(source_tree,relative_path))");
            old.execSQL("CREATE TABLE book_folders (book_id TEXT NOT NULL,folder_id TEXT NOT NULL,PRIMARY KEY(book_id,folder_id))");
            old.execSQL("INSERT INTO books VALUES ('legacy','旧书籍','txt','legacy.txt',1234,NULL,0)");
            old.execSQL("INSERT INTO library_meta VALUES ('legacy_json_migrated_v1',1)");
            old.execSQL("INSERT INTO folders VALUES ('old-folder',NULL,'旧归档','content://fixture/tree/old','')");
            old.execSQL("INSERT INTO book_folders VALUES ('legacy','old-folder')");old.setVersion(2);
        }finally{old.close();}
        BookStore store=new BookStore(c);
        require(store.sources("legacy").isEmpty()&&store.shelf("old-folder").books.size()==1,"v2 source upgrade lost archive membership");
        require(c.getSharedPreferences("reader",0).getInt("progress_legacy",0)==42,"v2 source upgrade reset progress");
    }
    private String deleteFixture(String name){return "delete/"+UUID.randomUUID()+"/"+name;}
    private void copyOnlyDeletion() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);String id=deleteFixture("copy.txt");
        BookStore.Book book=store.importUri(document(id),new CancellationSignal()).book;
        require(store.sources(book.id).size()==1,"new import did not save source");
        store.remove(book);
        require(!BulkFixtureProvider.wasDeleted(id)&&store.all().isEmpty()&&store.sources(book.id).isEmpty(),"copy removal deleted original or left metadata");
        try(android.os.ParcelFileDescriptor stillThere=getTargetContext().getContentResolver().openFileDescriptor(document(id),"r")){require(stillThere!=null,"source unavailable after copy-only deletion");}
    }
    private void originalDeletion() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);String id=deleteFixture("original.txt");
        BookStore.Book book=store.importUri(document(id),new CancellationSignal()).book;
        store.deleteSource(book.id,store.sources(book.id).get(0),new CancellationSignal());
        require(BulkFixtureProvider.wasDeleted(id)&&store.sources(book.id).isEmpty(),"original not deleted or source record retained");
        require(store.all().size()==1&&fileCount(c)==0,"source deletion removed library copy before caller confirmed all sources");
        store.remove(book);require(store.all().isEmpty()&&fileCount(c)==0,"copy cleanup after original deletion failed");
    }
    private void changedSourceDeletion() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);String id=deleteFixture("changed.txt");
        BookStore.Book book=store.importUri(document(id),new CancellationSignal()).book;
        BulkFixtureProvider.markChanged(id);boolean stopped=false;
        try{store.deleteSource(book.id,store.sources(book.id).get(0),new CancellationSignal());}catch(Exception expected){stopped=true;}
        require(stopped&&!BulkFixtureProvider.wasDeleted(id)&&store.all().size()==1&&store.sources(book.id).size()==1,"changed source was deleted");
        String wrong=deleteFixture("unrelated.txt");boolean rejected=false;
        try{store.attachSource(book.id,document(wrong),new CancellationSignal());}catch(Exception expected){rejected=true;}
        require(rejected&&store.sources(book.id).size()==1,"unrelated file became a deletion target");
    }
    private void refusedSourceDeletion() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);String id=deleteFixture("refuse.txt");
        BookStore.Book book=store.importUri(document(id),new CancellationSignal()).book;boolean failed=false;
        try{store.deleteSource(book.id,store.sources(book.id).get(0),new CancellationSignal());}catch(Exception expected){failed=true;}
        require(failed&&!BulkFixtureProvider.wasDeleted(id)&&store.all().size()==1&&store.sources(book.id).size()==1,"provider failure removed book or source record");
    }
    private void selectedSourceDeletion() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);CancellationSignal signal=new CancellationSignal();
        String prefix="delete/"+UUID.randomUUID()+"/",a=prefix+"same-a.txt",b=prefix+"same-b.txt";
        BookStore.Book book=store.importUri(document(a),signal).book;
        require(store.importUri(document(b),signal).duplicate&&store.sources(book.id).size()==2,"duplicate sources not retained separately");
        BookStore.Source selected=null;for(BookStore.Source source:store.sources(book.id))if(source.uri.equals(document(a).toString()))selected=source;
        require(selected!=null,"selected source missing");store.deleteSource(book.id,selected,signal);
        require(BulkFixtureProvider.wasDeleted(a)&&!BulkFixtureProvider.wasDeleted(b)&&store.sources(book.id).size()==1,"unselected original was deleted");
        store.remove(book);require(!BulkFixtureProvider.wasDeleted(b),"removing copy also deleted remaining original");
    }
    private void manualSourceAttachment() throws Exception {
        FixtureContext c=fixture();legacy(c,false);BookStore store=new BookStore(c);CancellationSignal signal=new CancellationSignal();
        require(store.sources("legacy").isEmpty(),"legacy source was fabricated");
        store.attachSource("legacy",document("store/a.txt"),signal);
        require(store.sources("legacy").size()==1&&c.getSharedPreferences("reader",0).getInt("progress_legacy",0)==42,"manual attachment changed old progress");
        Uri alias=DocumentsContract.buildDocumentUriUsingTree(tree("store"),"store/a.txt");
        store.attachSource("legacy",alias,signal);
        require(store.sources("legacy").size()==1,"same document via tree and direct URI duplicated source");
    }
    private void cancelSourceDeletion() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);String id=deleteFixture("cancel.txt");
        BookStore.Book book=store.importUri(document(id),new CancellationSignal()).book;CancellationSignal cancelled=new CancellationSignal();cancelled.cancel();
        boolean stopped=false;try{store.deleteSource(book.id,store.sources(book.id).get(0),cancelled);}catch(OperationCanceledException expected){stopped=true;}
        require(stopped&&!BulkFixtureProvider.wasDeleted(id)&&store.sources(book.id).size()==1&&store.all().size()==1,"cancelled original delete changed data");
    }
    private static java.util.List<String> ids(String... values){return java.util.Arrays.asList(values);}
    private void manualFolderNames() throws Exception {
        BookStore store=new BookStore(fixture());String parent=store.createFolder(null,"我的书单");String child=store.createFolder(parent,"待读");
        require(namedFolder(store.shelf(null),"我的书单").id.equals(parent)&&namedFolder(store.shelf(parent),"待读").id.equals(child),"manual hierarchy missing");
        store.renameFolder(child,"读完收藏");require(namedFolder(store.shelf(parent),"读完收藏").id.equals(child),"renaming changed identity");
        for(String name:new String[]{"","  ",".","..","bad/name","bad\\name","未分类"}){
            boolean rejected=false;try{store.createFolder(null,name);}catch(Exception expected){rejected=true;}
            require(rejected,"invalid folder name accepted: "+name);
        }
        boolean duplicate=false;try{store.createFolder(parent,"读完收藏");}catch(Exception expected){duplicate=true;}
        require(duplicate,"same-parent duplicate name accepted");
        require(store.allFolders().size()==2,"invalid creation left extra folders");
    }
    private void renamedSourceLabel() throws Exception {
        BookStore store=new BookStore(fixture());Uri source=tree("rename-source");String original=store.ensureImportFolder(source,"手机书籍","小说");
        store.renameFolder(original,"我的小说");String rescanned=store.ensureImportFolder(source,"手机书籍","小说");
        require(original.equals(rescanned),"rescan recreated renamed folder identity");
        BookStore.Folder root=namedFolder(store.shelf(null),"手机书籍");
        require(namedFolder(store.shelf(root.id),"我的小说").id.equals(original),"source rescan overwrote manual label");
    }
    private void batchOrganize() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);CancellationSignal signal=new CancellationSignal();
        String origin=store.createFolder(null,"原书单"),other=store.createFolder(null,"另一个书单"),target=store.createFolder(null,"新书单");
        BookStore.Book one=store.importUri(document("store/organize-one.txt"),signal,origin).book;
        BookStore.Book two=store.importUri(document("store/organize-two.txt"),signal,origin).book;
        c.getSharedPreferences("reader",0).edit().putInt("progress_"+one.id,57).commit();
        store.organizeBooks(ids(one.id,two.id,one.id),other,origin,false);
        require(store.shelf(origin).books.size()==2&&store.shelf(other).books.size()==2,"archive removed original memberships or duplicated books");
        store.organizeBooks(ids(one.id,two.id),target,origin,true);
        require(store.shelf(origin).books.isEmpty()&&store.shelf(target).books.size()==2&&store.shelf(other).books.size()==2,"move removed unrelated memberships");
        store.organizeBooks(ids(one.id),target,target,true);
        require(store.shelf(target).books.size()==2&&store.all().size()==2&&fileCount(c)==0,"same-target move removed or copied books");
        require(c.getSharedPreferences("reader",0).getInt("progress_"+one.id,0)==57&&store.sources(one.id).size()==1,"organization changed progress or sources");
    }
    private void globalOrganize() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);CancellationSignal signal=new CancellationSignal();
        String first=store.createFolder(null,"A"),second=store.createFolder(null,"B"),target=store.createFolder(null,"C");
        BookStore.Book book=store.importUri(document("store/global-move.txt"),signal,first).book;
        store.organizeBooks(ids(book.id),second,null,false);
        store.organizeBooks(ids(book.id),target,null,true);
        require(store.shelf(first).books.isEmpty()&&store.shelf(second).books.isEmpty()&&store.shelf(target).books.size()==1&&fileCount(c)==0,"global move failed to replace memberships");
    }
    private void unfileSelection() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);String a=store.createFolder(null,"A"),b=store.createFolder(null,"B");
        BookStore.Book book=store.importUri(document("store/unfile.txt"),new CancellationSignal(),a).book;
        store.organizeBooks(ids(book.id),b,a,false);store.unfileBooks(ids(book.id),a);
        require(store.shelf(a).books.isEmpty()&&store.shelf(b).books.size()==1,"unfile removed other folder membership");
        store.unfileBooks(ids(book.id),null);
        require(store.shelf(b).books.isEmpty()&&store.shelf(BookStore.UNCLASSIFIED).books.size()==1&&store.all().size()==1&&fileCount(c)==0,"global unfile deleted book instead of making it unclassified");
    }
    private void deleteFolderTree() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);String root=store.createFolder(null,"Root"),child=store.createFolder(root,"Child"),other=store.createFolder(null,"Other");
        BookStore.Book book=store.importUri(document("store/delete-folder-book.txt"),new CancellationSignal(),child).book;
        c.getSharedPreferences("reader",0).edit().putInt("progress_"+book.id,89).commit();
        store.organizeBooks(ids(book.id),other,child,false);store.deleteFolder(root);
        require(store.allFolders().size()==1&&store.shelf(other).books.size()==1&&fileCount(c)==0,"delete folder tree removed a book or unrelated folder");
        store.deleteFolder(other);
        require(store.all().size()==1&&store.shelf(BookStore.UNCLASSIFIED).books.size()==1&&store.sources(book.id).size()==1,"last folder deletion removed book or original source");
        require(c.getSharedPreferences("reader",0).getInt("progress_"+book.id,0)==89,"folder deletion reset reading progress");
    }
    private void organizeRollback() throws Exception {
        FixtureContext c=fixture();BookStore store=new BookStore(c);String origin=store.createFolder(null,"Origin"),target=store.createFolder(null,"Target");
        BookStore.Book book=store.importUri(document("store/rollback.txt"),new CancellationSignal(),origin).book;
        boolean failed=false;try{store.organizeBooks(ids(book.id,"does-not-exist"),target,origin,true);}catch(Exception expected){failed=true;}
        require(failed&&store.shelf(origin).books.size()==1&&store.shelf(target).books.isEmpty(),"invalid batch partially moved valid book");
        failed=false;try{store.organizeBooks(ids(book.id),BookStore.UNCLASSIFIED,origin,true);}catch(Exception expected){failed=true;}
        require(failed&&store.shelf(origin).books.size()==1,"special unclassified was accepted as a real target");
    }
    private void organizeSchemaUpgrade() throws Exception {
        FixtureContext c=fixture();legacy(c,false);SQLiteDatabase old=c.openOrCreateDatabase("openleaf-library.db",0,null);
        try{
            old.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,format TEXT NOT NULL,file TEXT NOT NULL,added INTEGER NOT NULL,sha256 TEXT,order_index INTEGER NOT NULL)");
            old.execSQL("CREATE TABLE library_meta(key TEXT PRIMARY KEY NOT NULL,value INTEGER NOT NULL)");
            old.execSQL("CREATE TABLE folders(id TEXT PRIMARY KEY NOT NULL,parent_id TEXT,name TEXT NOT NULL,source_tree TEXT NOT NULL,relative_path TEXT NOT NULL,UNIQUE(source_tree,relative_path))");
            old.execSQL("CREATE TABLE book_folders(book_id TEXT NOT NULL,folder_id TEXT NOT NULL,PRIMARY KEY(book_id,folder_id))");
            old.execSQL("CREATE TABLE book_sources(book_id TEXT NOT NULL,source_key TEXT NOT NULL,source_uri TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(book_id,source_key))");
            old.execSQL("INSERT INTO books VALUES('legacy','旧书籍','txt','legacy.txt',1234,NULL,0)");old.execSQL("INSERT INTO library_meta VALUES('legacy_json_migrated_v1',1)");
            old.execSQL("INSERT INTO folders VALUES('old-folder',NULL,'来源目录','content://fixture/tree/old','')");old.execSQL("INSERT INTO book_folders VALUES('legacy','old-folder')");
            old.execSQL("INSERT INTO book_sources VALUES('legacy','fixture-key','content://fixture/document/legacy','legacy.txt')");old.setVersion(3);
        }finally{old.close();}
        BookStore store=new BookStore(c);store.renameFolder("old-folder","自定义名称");
        require(namedFolder(store.shelf(null),"自定义名称").id.equals("old-folder")&&store.shelf("old-folder").books.size()==1&&store.sources("legacy").size()==1,"v3 upgrade lost folder, membership or source");
        require(c.getSharedPreferences("reader",0).getInt("progress_legacy",0)==42,"v3 upgrade reset progress");
    }
    private void metadataRoundTrip() throws Exception {
        FixtureContext c=fixture();legacy(c,false);BookStore books=new BookStore(c);
        BookMetadataStore metadata=new BookMetadataStore(c);
        BookMetadataStore.Info initial=metadata.get("legacy");
        require("旧书籍".equals(initial.book.title)&&initial.author.isEmpty()&&initial.description.isEmpty()
                &&initial.coverText.isEmpty()&&initial.coverName.isEmpty()&&!initial.favorite
                &&initial.tags.isEmpty()&&initial.folderIds.isEmpty(),"new metadata defaults changed");
        require(metadata.cards().isEmpty(),"default read fabricated metadata cards");
        metadata.save("legacy","  合成书名  ","  合成作者  ","  第一行\n第二行\t简介  ","  封面文字  ","",true,
                ids(" Ｔｅｓｔ ","test","历史"),false,null);
        BookMetadataStore.Info saved=new BookMetadataStore(c).get("legacy");
        require("合成书名".equals(saved.book.title)&&"合成书名".equals(books.find("legacy").title)
                &&"合成作者".equals(saved.author)&&"第一行\n第二行\t简介".equals(saved.description)
                &&"封面文字".equals(saved.coverText)&&saved.coverName.isEmpty()&&saved.favorite,
                "saved metadata did not round trip or trim text");
        require(saved.tags.equals(ids("Test","历史"))&&books.tags("legacy").equals(saved.tags),
                "tags were not normalized, deduplicated and shared with BookStore");
        BookMetadataStore.Card card=metadata.cards().get("legacy");
        require(metadata.cards().size()==1&&card!=null&&"合成作者".equals(card.author)
                &&"封面文字".equals(card.coverText)&&card.coverName.isEmpty()&&card.favorite,"metadata card differs from detail");
        require(saved.book.file.equals(initial.book.file)&&saved.book.format.equals(initial.book.format)
                &&saved.book.added==initial.book.added&&books.sources("legacy").isEmpty()&&fileCount(c)==1
                &&c.getSharedPreferences("reader",0).getInt("progress_legacy",0)==42
                &&c.getSharedPreferences("reader",0).getInt("chapter_legacy",0)==3,"metadata edit changed book or progress");
        metadata.save("legacy",saved.book.title,"","","","",false,ids(),false,null);
        BookMetadataStore.Info cleared=metadata.get("legacy");
        require(cleared.author.isEmpty()&&cleared.description.isEmpty()&&cleared.coverText.isEmpty()
                &&!cleared.favorite&&cleared.tags.isEmpty()&&!metadata.cards().get("legacy").favorite,
                "optional fields, favorite or tags could not be cleared");
    }
    private static void sameMetadata(BookMetadataStore.Info before, BookMetadataStore.Info after) {
        require(before.book.title.equals(after.book.title)&&before.author.equals(after.author)
                &&before.description.equals(after.description)&&before.coverText.equals(after.coverText)
                &&before.coverName.equals(after.coverName)&&before.favorite==after.favorite
                &&before.tags.equals(after.tags)&&before.folderIds.equals(after.folderIds),
                "failed save changed title, metadata, tags or classification");
    }
    private void metadataRollback() throws Exception {
        FixtureContext c=fixture();legacy(c,false);BookStore books=new BookStore(c);
        String folder=books.createFolder(null,"Original");
        BookMetadataStore metadata=new BookMetadataStore(c);
        metadata.save("legacy","Stable title","Stable author","Stable description","Stable cover","",true,ids("Keep"),true,folder);
        BookMetadataStore.Info baseline=metadata.get("legacy");
        for(String[] invalid:new String[][]{
                {" ","author","description","cover"},
                {"Changed","bad\nauthor","description","cover"},
                {"Changed","author","bad\u0001description","cover"},
                {"Changed","author","description","bad\ncover"}}) {
            boolean rejected=false;
            try{metadata.save("legacy",invalid[0],invalid[1],invalid[2],invalid[3],"",false,ids("Replace"),true,null);}
            catch(java.io.IOException expected){rejected=true;}
            require(rejected,"invalid metadata accepted");sameMetadata(baseline,metadata.get("legacy"));
        }
        boolean rejected=false;
        try{metadata.save("legacy","Changed","author","description","cover","",false,ids("valid"," "),true,null);}
        catch(java.io.IOException expected){rejected=true;}
        require(rejected,"empty tag accepted");sameMetadata(baseline,metadata.get("legacy"));
        rejected=false;
        try{metadata.save("legacy","Changed","author","description","cover","",false,ids("Replace"),true,"missing-folder");}
        catch(java.io.IOException expected){rejected=true;}
        require(rejected,"missing classification accepted");sameMetadata(baseline,metadata.get("legacy"));
        // Fail after the title/metadata updates and tag deletion, proving actual SQL rollback.
        SQLiteDatabase db=LibraryDatabase.get(c).getWritableDatabase();
        db.execSQL("CREATE TRIGGER fail_metadata_tag BEFORE INSERT ON book_tags WHEN NEW.tag_key='reject' BEGIN SELECT RAISE(ABORT,'synthetic tag write failure'); END");
        try {
            rejected=false;
            try{metadata.save("legacy","Changed","changed","changed","changed","",false,ids("New","Reject"),true,null);}
            catch(android.database.sqlite.SQLiteException expected){rejected=true;}
            require(rejected,"synthetic tag failure did not fire");sameMetadata(baseline,metadata.get("legacy"));
            require(books.tags("legacy").equals(ids("Keep"))&&"Stable title".equals(books.find("legacy").title),
                    "transaction failure lost old tags or title");
        } finally {db.execSQL("DROP TRIGGER fail_metadata_tag");}
    }
    private void metadataClassification() throws Exception {
        FixtureContext c=fixture();legacy(c,false);BookStore books=new BookStore(c);
        String first=books.createFolder(null,"First"),second=books.createFolder(null,"Second"),target=books.createFolder(null,"Target");
        books.organizeBooks(ids("legacy"),first,null,false);books.organizeBooks(ids("legacy"),second,null,false);
        BookMetadataStore metadata=new BookMetadataStore(c);BookMetadataStore.Info before=metadata.get("legacy");
        metadata.save("legacy","Keep folders","","","","",false,ids(),false,"missing-ignored-folder");
        require(before.folderIds.size()==2&&before.folderIds.equals(metadata.get("legacy").folderIds)
                &&books.shelf(first).books.size()==1&&books.shelf(second).books.size()==1,"default save changed classification");
        metadata.save("legacy","Replace folders","","","","",false,ids(),true,target);
        require(metadata.get("legacy").folderIds.equals(ids(target))&&books.shelf(first).books.isEmpty()
                &&books.shelf(second).books.isEmpty()&&books.shelf(target).books.size()==1,"explicit classification did not replace all memberships");
        metadata.save("legacy","Unclassified","","","","",false,ids(),true,BookStore.UNCLASSIFIED);
        require(metadata.get("legacy").folderIds.isEmpty()&&books.shelf(target).books.isEmpty()
                &&books.shelf(BookStore.UNCLASSIFIED).books.size()==1,"explicit unclassified retained memberships");
        metadata.save("legacy","Classify again","","","","",false,ids(),true,first);
        metadata.save("legacy","Null unclassified","","","","",false,ids(),true,null);
        require(metadata.get("legacy").folderIds.isEmpty()&&books.all().size()==1&&fileCount(c)==1
                &&c.getSharedPreferences("reader",0).getInt("progress_legacy",0)==42,"null unclassified lost book or progress");
    }
    private void metadataCovers() throws Exception {
        FixtureContext c=fixture();legacy(c,false);BookMetadataStore metadata=new BookMetadataStore(c);
        File covers=new File(c.getFilesDir(),"covers");require(covers.mkdirs(),"cannot create private cover fixture");
        String valid=UUID.randomUUID()+".jpg",outside=UUID.randomUUID()+".jpg";
        // The store validates private file references; use a real synthetic JPEG for the accepted reference.
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888);
        try(FileOutputStream out=new FileOutputStream(new File(covers,valid))){
            require(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out),"cannot encode cover fixture");
        } finally {bitmap.recycle();}
        try(FileOutputStream out=new FileOutputStream(new File(c.getFilesDir(),outside))){out.write(1);}
        metadata.save("legacy","Private cover","","","",valid,true,ids("Cover"),false,null);
        BookMetadataStore.Info baseline=metadata.get("legacy");
        require(valid.equals(baseline.coverName)&&valid.equals(metadata.cards().get("legacy").coverName),"private cover did not round trip");
        for(String invalid:new String[]{UUID.randomUUID()+".jpg",outside,"../"+outside,
                new File(covers,valid).getAbsolutePath(),"content://fixture/cover","invalid.jpg"}) {
            boolean rejected=false;
            try{metadata.save("legacy","Changed","","","",invalid,false,ids("Replace"),false,null);}
            catch(java.io.IOException expected){rejected=true;}
            require(rejected,"invalid or non-private cover accepted: "+invalid);sameMetadata(baseline,metadata.get("legacy"));
        }
        metadata.save("legacy","Clear cover","","","",null,false,ids(),false,null);
        require(metadata.get("legacy").coverName.isEmpty()&&metadata.cards().get("legacy").coverName.isEmpty()
                &&new File(covers,valid).isFile(),"clearing cover retained reference or deleted existing cover");
    }
    private void metadataSchemaUpgrade() throws Exception {
        FixtureContext c=fixture();legacy(c,false);
        SQLiteDatabase old=c.openOrCreateDatabase("openleaf-library.db",0,null);
        try {
            old.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,format TEXT NOT NULL,file TEXT NOT NULL,added INTEGER NOT NULL,sha256 TEXT,order_index INTEGER NOT NULL)");
            old.execSQL("CREATE TABLE library_meta(key TEXT PRIMARY KEY NOT NULL,value INTEGER NOT NULL)");
            old.execSQL("CREATE TABLE folders(id TEXT PRIMARY KEY NOT NULL,parent_id TEXT REFERENCES folders(id),name TEXT NOT NULL,source_tree TEXT NOT NULL,relative_path TEXT NOT NULL,display_name TEXT DEFAULT NULL,UNIQUE(source_tree,relative_path))");
            old.execSQL("CREATE TABLE book_folders(book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,folder_id TEXT NOT NULL REFERENCES folders(id),PRIMARY KEY(book_id,folder_id))");
            old.execSQL("CREATE TABLE book_sources(book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,source_key TEXT NOT NULL,source_uri TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(book_id,source_key))");
            old.execSQL("CREATE TABLE book_tags(book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,tag_key TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(book_id,tag_key))");
            old.execSQL("INSERT INTO books VALUES('legacy','旧书籍','txt','legacy.txt',1234,'synthetic-hash',7)");
            old.execSQL("INSERT INTO library_meta VALUES('legacy_json_migrated_v1',1)");
            old.execSQL("INSERT INTO folders VALUES('v5-folder',NULL,'Source name','content://fixture/tree/v5','','Custom label')");
            old.execSQL("INSERT INTO book_folders VALUES('legacy','v5-folder')");
            old.execSQL("INSERT INTO book_sources VALUES('legacy','v5-source','content://fixture/document/v5','v5.txt')");
            old.execSQL("INSERT INTO book_tags VALUES('legacy','existing','Existing')");old.setVersion(5);
        } finally {old.close();}
        BookMetadataStore metadata=new BookMetadataStore(c);BookMetadataStore.Info info=metadata.get("legacy");
        BookStore books=new BookStore(c);SQLiteDatabase upgraded=LibraryDatabase.get(c).getReadableDatabase();
        require(upgraded.getVersion()==LibraryDatabase.schemaVersion()&&books.all().size()==1&&"旧书籍".equals(info.book.title)
                &&"legacy.txt".equals(info.book.file)&&"txt".equals(info.book.format)&&info.book.added==1234,
                "v5 upgrade changed book fields or schema version");
        require(info.author.isEmpty()&&info.description.isEmpty()&&info.coverText.isEmpty()&&info.coverName.isEmpty()
                &&!info.favorite&&metadata.cards().isEmpty(),"v5 upgrade did not supply empty metadata defaults");
        require(info.tags.equals(ids("Existing"))&&info.folderIds.equals(ids("v5-folder"))
                &&namedFolder(books.shelf(null),"Custom label").id.equals("v5-folder")&&books.shelf("v5-folder").books.size()==1,
                "v5 upgrade lost tags, display label or folder membership");
        require(books.sources("legacy").size()==1&&"content://fixture/document/v5".equals(books.sources("legacy").get(0).uri)
                &&c.getSharedPreferences("reader",0).getInt("progress_legacy",0)==42
                &&c.getSharedPreferences("reader",0).getInt("chapter_legacy",0)==3,"v5 upgrade lost source or reading state");
        try(android.database.Cursor row=upgraded.rawQuery("SELECT sha256,order_index FROM books WHERE id='legacy'",null)) {
            require(row.moveToFirst()&&"synthetic-hash".equals(row.getString(0))&&row.getInt(1)==7,"v5 upgrade changed hash or order");
        }
        metadata.save("legacy","After upgrade","Author","Description","Cover text","",true,ids("Existing","New"),false,null);
        require(metadata.get("legacy").favorite&&metadata.get("legacy").tags.size()==2
                &&metadata.get("legacy").folderIds.equals(ids("v5-folder")),"upgraded metadata table is not writable");
    }

    private static int fileCount(Context c) { File[] files=new File(c.getFilesDir(),"books").listFiles();return files==null?0:files.length; }

    private static final class FixtureContext extends ContextWrapper {
        private final File root;
        private final String prefix="bulk-fixture-"+UUID.randomUUID();
        FixtureContext(Context base){super(base);root=new File(base.getCacheDir(),prefix);if(!root.mkdirs())throw new IllegalStateException("Cannot create isolated fixture");}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getFilesDir(){File dir=new File(root,"files");dir.mkdirs();return dir;}
        @Override public File getNoBackupFilesDir(){File dir=new File(root,"no-backup");dir.mkdirs();return dir;}
        @Override public File getDatabasePath(String name){File dir=new File(root,"databases");dir.mkdirs();return new File(dir,name);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return openOrCreateDatabase(name,mode,factory,null);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler errorHandler){return SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(),factory,SQLiteDatabase.CREATE_IF_NECESSARY,errorHandler);}
        @Override public boolean deleteDatabase(String name){return SQLiteDatabase.deleteDatabase(getDatabasePath(name));}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return getBaseContext().getSharedPreferences(prefix+"-"+name,mode);}
    }
}
