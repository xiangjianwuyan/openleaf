package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit, per-source deletion controls. Original files are never removed implicitly. */
public final class DeleteBookActivity extends Activity {
    private static final int REQUEST_SOURCE = 7301;
    private static final int SOURCES_PER_PAGE = 500;
    private static final int MAX_SELECTED = 500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Operation operation;
    private boolean attached;
    private LinearLayout root, header, footer;
    private TextView title, summary, notice, status;
    private ListView sourceList;
    private SourceAdapter adapter;
    private Button removeBookButton, deleteFilesButton, authorizeButton;
    private int sourcePage;
    private final Set<String> selected = new HashSet<>();
    private AlertDialog activeDialog;

    private final Listener listener = () -> {
        if (attached && !isFinishing() && !isDestroyed()) {
            main.post(() -> {
                if (attached && !isFinishing() && !isDestroyed()) render();
            });
        }
    };

    private interface Listener { void changed(); }

    private static final class Operation {
        final android.content.Context app;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        volatile Listener observer;
        volatile boolean busy;
        volatile String status = "正在读取书籍…";
        volatile BookStore.Book book;
        volatile List<BookStore.Source> sources = new ArrayList<>();
        volatile List<String> errors = new ArrayList<>();
        volatile String resultMessage = "";
        volatile boolean finished;
        volatile boolean referenced;
        private volatile CancellationSignal signal;

        Operation(android.content.Context context) { app = context.getApplicationContext(); }
        void notifyChanged() { Listener target = observer; if (target != null) target.changed(); }
        void attach(Listener listener) { observer = listener; }
        void detach(Listener listener) { if (observer == listener) observer = null; }
        void beginSignal() { signal = new CancellationSignal(); }
        void cancel() { CancellationSignal active = signal; if (active != null) active.cancel(); }

        void load(String bookId) {
            if (busy || finished) return;
            busy = true;
            status = "正在读取书籍来源…";
            notifyChanged();
            worker.execute(() -> {
                try {
                    BookStore store = new BookStore(app);
                    book = store.find(bookId);
                    referenced = store.isReferenced(book);
                    sources = new ArrayList<>(store.sources(bookId));
                    status = "";
                } catch (Exception error) {
                    errors = oneError(error);
                    status = "书籍或来源信息读取失败";
                } finally {
                    busy = false;
                    notifyChanged();
                }
            });
        }

        void attachSource(String bookId, Uri uri) {
            if (busy || finished) return;
            busy = true;
            beginSignal();
            CancellationSignal current = signal;
            status = "正在核对并授权该文件…";
            resultMessage = "";
            errors = new ArrayList<>();
            notifyChanged();
            worker.execute(() -> {
                try {
                    BookStore store = new BookStore(app);
                    store.attachSource(bookId, uri, current);
                    sources = new ArrayList<>(store.sources(bookId));
                    status = referenced?"原文件已核对并重新关联；未执行删除。":"原文件已核对并记录；请勾选后再确认删除。";
                } catch (Exception error) {
                    errors = oneError(error);
                    resultMessage = "未记录这个原文件，书架及原文件均未删除。";
                    status = "未添加来源；书架与原文件均未删除。";
                } finally {
                    signal = null;
                    busy = false;
                    notifyChanged();
                }
            });
        }

        void removeOnly(String bookId) {
            if (busy || finished) return;
            busy = true;
            status = "正在移除书架记录…";
            resultMessage = "";
            notifyChanged();
            worker.execute(() -> {
                try {
                    BookStore store = new BookStore(app);
                    BookStore.Book current = store.find(bookId);
                    store.remove(current);
                    finished = true;
                    status = "书架记录已移除；原文件未删除。";
                } catch (Exception error) {
                    errors = oneError(error);
                    status = "移除失败；请重试。";
                } finally {
                    busy = false;
                    notifyChanged();
                }
            });
        }

        void deleteSelected(String bookId, List<BookStore.Source> chosen) {
            if(busy||finished||chosen.isEmpty()||chosen.size()>MAX_SELECTED)return;
            busy=true;beginSignal();CancellationSignal current=signal;
            errors=new ArrayList<>();resultMessage="";status="正在核对所选原文件…";notifyChanged();
            worker.execute(()->{
                int deleted=0;ArrayList<String> failures=new ArrayList<>();BookStore store=null;
                try{
                    store=new BookStore(app);
                    for(int i=0;i<chosen.size();i++){
                        if(current.isCanceled()){failures.add("任务已取消，未处理的原文件保留。");break;}
                        BookStore.Source source=chosen.get(i);
                        status="正在处理 "+(i+1)+" / "+chosen.size()+"："+source.name;notifyChanged();
                        try{store.deleteSource(bookId,source,current);deleted++;}
                        catch(Exception error){if(failures.size()<10)failures.add(source.name+"："+message(error));}
                    }
                    if(failures.isEmpty()&&!current.isCanceled()){
                        store.remove(store.find(bookId));finished=true;status="所选原文件及书架记录已删除。";
                    }
                }catch(Exception error){if(failures.size()<10)failures.add(message(error));}
                finally{
                    if(!finished){
                        resultMessage="已成功删除 "+deleted+" 个原文件；未全部完成，书架记录与阅读进度保留。未选原文件未删除。";
                        if(store!=null)try{sources=new ArrayList<>(store.sources(bookId));}
                        catch(Exception error){if(failures.size()<10)failures.add("刷新来源失败："+message(error));}
                        errors=failures;status="请检查失败项；不会自动重试。";
                    }
                    signal=null;busy=false;notifyChanged();
                }
            });
        }

        void stop() {
            cancel();
            worker.shutdownNow();
        }

        private static List<String> oneError(Exception error) {
            ArrayList<String> messages = new ArrayList<>();
            messages.add(message(error));
            return messages;
        }
        private static String message(Exception error) {
            String text = error.getMessage();
            return text == null || text.trim().isEmpty() ? error.getClass().getSimpleName() : text;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Object retained = getLastNonConfigurationInstance();
        operation = retained instanceof Operation ? (Operation) retained : new Operation(getApplicationContext());
        if (state != null) {
            sourcePage = state.getInt("sourcePage", 0);
            ArrayList<String> saved = state.getStringArrayList("selectedSources");
            if (saved != null) selected.addAll(saved);
        }
        build();
        String id = getIntent().getStringExtra("bookId");
        if (id == null || id.trim().isEmpty()) {
            Toast.makeText(this, "缺少书籍标识", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (operation.book == null) operation.load(id);
    }

    @Override public Object onRetainNonConfigurationInstance() { return operation; }
    @Override protected void onStart() { super.onStart(); attached = true; operation.attach(listener); render(); }
    @Override protected void onStop() { attached = false; operation.detach(listener); super.onStop(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("sourcePage", sourcePage);
        state.putStringArrayList("selectedSources", new ArrayList<>(selected));
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (operation.busy) {
            Toast.makeText(this, "操作进行中，请等待完成；已完成的原文件删除不会恢复。",
                    Toast.LENGTH_LONG).show();
        } else {
            super.onBackPressed();
        }
    }

    private void build() {
        root = Ui.column(this);
        Ui.mount(this, root);
        android.widget.ScrollView topScroll = new android.widget.ScrollView(this);
        header = Ui.column(this);
        Ui.pad(header, 20);
        topScroll.addView(header);
        root.addView(topScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout nav = Ui.row(this);
        nav.addView(Ui.button(this, "返回", this::onBackPressed));
        title = Ui.title(this, "删除书籍", 20);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        nav.addView(title, Ui.weight());
        header.addView(nav);
        Ui.gap(header, 8);
        header.addView(Ui.text(this,
                "请选择删除范围。仅移出书架不会删除原文件；删除原文件前会逐项核对内容。",
                13, Ui.MUTED));
        Ui.gap(header, 8);
        summary = Ui.text(this, "正在读取…", 13, Ui.MUTED);
        header.addView(summary);
        status = Ui.text(this, "", 13, Ui.GREEN);
        header.addView(status);
        notice = Ui.text(this, "", 12, Ui.MUTED);
        header.addView(notice);

        sourceList = new ListView(this);
        sourceList.setDivider(null);
        adapter = new SourceAdapter();
        sourceList.setAdapter(adapter);
        root.addView(sourceList, new LinearLayout.LayoutParams(-1, 0, 1));

        footer = Ui.column(this);
        Ui.pad(footer, 16);
        removeBookButton = Ui.button(this, "仅从书架移除（保留原文件）", this::confirmRemoveBook);
        authorizeButton=Ui.button(this,"选择或重新授权原文件",this::chooseSource);
        footer.addView(authorizeButton,new LinearLayout.LayoutParams(-1,-2));
        footer.addView(removeBookButton, new LinearLayout.LayoutParams(-1, -2));
        deleteFilesButton = Ui.button(this, "删除选中的原文件并移出书架", this::confirmDeleteFiles);
        deleteFilesButton.setTextColor(android.graphics.Color.WHITE);
        deleteFilesButton.setBackground(Ui.shape(0xFF9C3B32, 14, this));
        footer.addView(deleteFilesButton, new LinearLayout.LayoutParams(-1, -2));
        root.addView(footer);
    }

    private void render() {
        if (isDestroyed()) return;
        BookStore.Book book = operation.book;
        title.setText(book == null ? "删除书籍" : "删除《" + book.title + "》");
        List<BookStore.Source> sources = operation.sources;
        Set<String> available=new HashSet<>();for(BookStore.Source source:sources)available.add(source.uri);
        selected.retainAll(available);
        sourcePage=Math.max(0,Math.min(sourcePage,Math.max(0,(sources.size()-1)/SOURCES_PER_PAGE)));
        summary.setText(book == null ? "正在读取书籍来源…"
                : "已登记 " + sources.size() + " 个原文件来源；已勾选 " + selectedSources().size()
                + " 个；仅对勾选项执行删除。最多显示 500 个来源／页。"
                + (sources.size() > SOURCES_PER_PAGE ? " 当前第 "
                + (sourcePage + 1) + " / " + ((sources.size() + SOURCES_PER_PAGE - 1) / SOURCES_PER_PAGE) + " 页。" : ""));
        deleteFilesButton.setText("删除选中的原文件并移出书架");
        status.setText(operation.status);
        updateNotice(sources);
        authorizeButton.setEnabled(book!=null&&!operation.busy);
        removeBookButton.setEnabled(book != null && !operation.busy);
        deleteFilesButton.setEnabled(book != null && !operation.busy && !selectedSources().isEmpty());
        adapter.notifyDataSetChanged();
        if (!operation.resultMessage.isEmpty()) {
            String message = operation.resultMessage;
            operation.resultMessage = "";
            showDetails("删除结果", message, operation.errors);
        }
        if (operation.finished) {
            setResult(RESULT_OK);
            finish();
        }
    }

    private void updateNotice(List<BookStore.Source> sources) {
        if (sources.isEmpty()) {
            notice.setText("没有已登记的原文件来源（常见于旧书）。可选择或重新授权原文件；系统会先核对内容，之后仍须手动勾选确认删除。应用不会绕过系统权限。");
            notice.setOnClickListener(v -> chooseSource());
            notice.setTextColor(Ui.GREEN);
        } else if (!operation.errors.isEmpty()) {
            notice.setText("有失败项目。书架记录与阅读进度保留；未选来源未删除。点此查看错误详情。");
            notice.setOnClickListener(v -> showDetails("删除未全部完成", operation.resultMessage,
                    operation.errors));
            notice.setTextColor(0xFF9C3B32);
        } else {
            notice.setText((operation.referenced?"此书直接引用原文件，书库没有额外文件副本。":"此书保留旧应用副本。")+"原文件仅在勾选、确认并逐项校验后删除。若部分删除失败，书架记录与进度保留，但已删除的文件不会恢复；需要时请重新关联仍在的原文件。");
            notice.setOnClickListener(sources.isEmpty() ? v -> chooseSource() : null);
            notice.setTextColor(Ui.MUTED);
        }
    }

    private ArrayList<BookStore.Source> currentPageSources() {
        List<BookStore.Source> all = operation.sources;
        int start = Math.min(sourcePage * SOURCES_PER_PAGE, all.size());
        int end = Math.min(start + SOURCES_PER_PAGE, all.size());
        return new ArrayList<>(all.subList(start, end));
    }

    private ArrayList<BookStore.Source> selectedSources() {
        ArrayList<BookStore.Source> matches = new ArrayList<>();
        for (BookStore.Source source : operation.sources)
            if (selected.contains(source.uri)) matches.add(source);
        return matches;
    }

    private void confirmRemoveBook() {
        if (operation.book == null || operation.busy) return;
        activeDialog = new AlertDialog.Builder(this)
                .setTitle("仅移出书架？")
                .setMessage(operation.referenced?"将移除书架记录、阅读进度和所有归档关联。此书直接引用原文件，没有额外的书库文件副本；原文件不会删除。":"将删除旧应用副本、阅读进度和所有归档关联。手机上的原文件不会删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("仅移除书架", (dialog, which) -> operation.removeOnly(bookId()))
                .show();
    }

    private void confirmDeleteFiles() {
        ArrayList<BookStore.Source> chosen = selectedSources();
        if (operation.busy || operation.book == null) return;
        if (chosen.isEmpty()) {
            Toast.makeText(this, "请先勾选来源文件", Toast.LENGTH_SHORT).show();
            return;
        }
        if (chosen.size() > MAX_SELECTED) {
            Toast.makeText(this, "每次最多选择 500 个来源，未选原文件会保留。", Toast.LENGTH_LONG).show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("不可撤销：删除原文件？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除所选原文件并移出书架", (dialog, which) ->
                        operation.deleteSelected(bookId(), chosen));
        builder.setView(selectionView(chosen));
        activeDialog = builder.show();
    }

    private View selectionView(List<BookStore.Source> chosen) {
        LinearLayout contents = Ui.column(this);
        Ui.pad(contents, 20);
        TextView warning = Ui.text(this,
                "已选择 " + chosen.size() + " 个原文件。成功删除后，将一并移除书架记录、阅读进度和所有归档关联。原文件删除后不可恢复；若任一项失败，书架记录与进度保留。",
                14, Ui.INK);
        contents.addView(warning);
        Ui.gap(contents, 12);
        for (BookStore.Source source : chosen) {
            TextView path = Ui.text(this, "• " + displaySource(source), 13, Ui.MUTED);
            path.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            contents.addView(path);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(contents);
        scroll.setFillViewport(false);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 360)));
        return scroll;
    }

    private void chooseSource() {
        if (operation.busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"text/plain", "application/epub+zip", "application/pdf"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_SOURCE);
        } catch (android.content.ActivityNotFoundException error) {
            showDetails("无法打开文件选择器", "请启用系统文件管理器后重试。", new ArrayList<>());
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_SOURCE || result != RESULT_OK || data == null || data.getData() == null) return;
        int flags = data.getFlags();
        boolean read = (flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        boolean write = (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        try {
            if (read && write) getContentResolver().takePersistableUriPermission(data.getData(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            else if (read) getContentResolver().takePersistableUriPermission(data.getData(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            else if (write) getContentResolver().takePersistableUriPermission(data.getData(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException ignored) {
            if(read)try{getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(SecurityException|IllegalArgumentException unavailable){/* Recheck persisted read permission below. */}
        }
        if(operation.referenced){
            boolean persistent=false;
            for(android.content.UriPermission grant:getContentResolver().getPersistedUriPermissions())
                if(data.getData().equals(grant.getUri())&&grant.isReadPermission()){persistent=true;break;}
            if(!persistent){Toast.makeText(this,"重新关联需要长期读取授权，请重新选择；现有书架记录保留。",Toast.LENGTH_LONG).show();return;}
        }
        operation.attachSource(bookId(), data.getData());
    }

    private void updateSelection(BookStore.Source source, boolean checked) {
        if (checked) {
            if (!selected.contains(source.uri) && selected.size() >= MAX_SELECTED) {
                Toast.makeText(this, "最多勾选 500 个来源，未选原文件会保留。", Toast.LENGTH_LONG).show();
                render();
                return;
            }
            selected.add(source.uri);
        } else {
            selected.remove(source.uri);
        }
        summary.setText("已登记 " + operation.sources.size() + " 个来源；当前勾选 "
                + selectedSources().size() + " 个（每次最多 500 个）。");
        deleteFilesButton.setEnabled(!operation.busy && !selected.isEmpty());
    }

    private void showDetails(String heading, String message, List<String> errors) {
        if (isFinishing() || isDestroyed()) return;
        StringBuilder text = new StringBuilder(message == null ? "" : message);
        if (errors != null && !errors.isEmpty()) {
            text.append("\n\n失败详情：\n");
            int shown = Math.min(10, errors.size());
            for (int i = 0; i < shown; i++) text.append("• ").append(errors.get(i)).append('\n');
            if (errors.size() > shown) text.append("另有 ").append(errors.size() - shown).append(" 项未展开。\n");
        }
        activeDialog = new AlertDialog.Builder(this).setTitle(heading)
                .setMessage(text.toString()).setPositiveButton("知道了", null).show();
    }

    private static String displaySource(BookStore.Source source) {
        Uri uri=Uri.parse(source.uri);
        try{
            // Tree document URIs carry both a root ID and the actual child ID. Show the child.
            return (uri.getAuthority()==null?"":uri.getAuthority()+"\n")
                    +DocumentsContract.getDocumentId(uri);
        }catch(RuntimeException unavailable){return source.uri;}
    }

    private String bookId() { return getIntent().getStringExtra("bookId"); }

    private final class SourceAdapter extends BaseAdapter {
        @Override public int getCount() { return currentPageSources().size() + pageControls(); }
        @Override public BookStore.Source getItem(int position) {
            ArrayList<BookStore.Source> page = currentPageSources();
            return position >= 0 && position < page.size() ? page.get(position) : null;
        }
        @Override public long getItemId(int position) { return position; }
        private int pageControls() { return operation.sources.size() > SOURCES_PER_PAGE ? 1 : 0; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            ArrayList<BookStore.Source> page = currentPageSources();
            if (position >= page.size()) {
                LinearLayout row = convertView instanceof LinearLayout ? (LinearLayout) convertView : Ui.row(DeleteBookActivity.this);
                row.removeAllViews();
                Button previous = Ui.button(DeleteBookActivity.this, "上一页", () -> {
                    sourcePage = Math.max(0, sourcePage - 1); render();
                });
                Button next = Ui.button(DeleteBookActivity.this, "下一页", () -> {
                    sourcePage = Math.min((operation.sources.size() - 1) / SOURCES_PER_PAGE, sourcePage + 1);
                    render();
                });
                row.addView(previous, Ui.weight());
                row.addView(Ui.text(DeleteBookActivity.this,
                        (sourcePage + 1) + " / " + ((operation.sources.size() + SOURCES_PER_PAGE - 1) / SOURCES_PER_PAGE),
                        13, Ui.MUTED), Ui.weight());
                row.addView(next, Ui.weight());
                previous.setEnabled(sourcePage > 0); next.setEnabled((sourcePage + 1) * SOURCES_PER_PAGE < operation.sources.size());
                return row;
            }
            BookStore.Source source = page.get(position);
            LinearLayout row = convertView instanceof LinearLayout ? (LinearLayout) convertView : Ui.row(DeleteBookActivity.this);
            row.removeAllViews();
            Ui.pad(row, 8);
            CheckBox check = new CheckBox(DeleteBookActivity.this);
            check.setChecked(selected.contains(source.uri));
            check.setEnabled(!operation.busy);
            check.setOnCheckedChangeListener((button, checked) -> updateSelection(source, checked));
            row.addView(check);
            LinearLayout text = Ui.column(DeleteBookActivity.this);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            TextView name = Ui.title(DeleteBookActivity.this,
                    source.name == null || source.name.trim().isEmpty() ? "未命名文件" : source.name, 15);
            name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(name);
            TextView path = Ui.text(DeleteBookActivity.this, displaySource(source), 12, Ui.MUTED);
            path.setMaxLines(2); path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            text.addView(path);
            return row;
        }
    }

    @Override protected void onDestroy() {
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
        if (operation != null && !isChangingConfigurations()) operation.stop();
        super.onDestroy();
    }
}
