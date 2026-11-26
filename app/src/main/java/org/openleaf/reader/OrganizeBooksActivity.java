package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Archive management only. This page never deletes books or original documents. */
public final class OrganizeBooksActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Task task;
    private boolean attached;
    private String action, targetId;
    private ArrayList<String> bookIds;
    private TextView status, selection;
    private Button apply, newFolder, rootTarget;
    private FolderAdapter adapter;
    private AlertDialog dialog;
    private final Listener listener = () -> main.post(() -> {
        if (attached && !isDestroyed() && !isFinishing()) render();
    });

    private interface Listener { void changed(); }
    private interface Work { String run(BookStore store) throws Exception; }
    private static final class Row {
        final BookStore.Folder folder;
        final String path;
        Row(BookStore.Folder folder, String path) { this.folder = folder; this.path = path; }
    }
    private static final class Task {
        final Context app;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        volatile Listener listener;
        volatile boolean busy, done;
        volatile String message = "正在读取文件夹…", createdId;
        volatile List<Row> rows = new ArrayList<>();
        boolean loaded;
        Task(Context context) { app = context.getApplicationContext(); }
        void notifyChanged() { Listener current = listener; if (current != null) current.changed(); }
        void load() {
            if (busy || done) return;
            busy = true;
            worker.execute(() -> {
                try { rows = folderRows(new BookStore(app).allFolders()); loaded = true; message = ""; }
                catch (Exception error) { message = errorMessage(error); }
                finally { busy = false; notifyChanged(); }
            });
        }
        void execute(Work work, boolean finish) {
            if (busy || done) return;
            busy = true; message = "正在处理…"; notifyChanged();
            worker.execute(() -> {
                try {
                    String result = work.run(new BookStore(app));
                    if (finish) { done = true; message = "整理完成"; }
                    else {
                        createdId = result;
                        rows = folderRows(new BookStore(app).allFolders());
                        loaded = true; message = "文件夹已创建";
                    }
                } catch (Exception error) { message = errorMessage(error); }
                finally { busy = false; notifyChanged(); }
            });
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        action = getIntent().getStringExtra("action");
        bookIds = getIntent().getStringArrayListExtra("bookIds");
        if (bookIds == null) bookIds = new ArrayList<>();
        Object retained = getLastNonConfigurationInstance();
        task = retained instanceof Task ? (Task) retained : new Task(getApplicationContext());
        targetId = state == null ? null : state.getString("targetId");
        if (action == null || !(action.equals("add") || action.equals("move") || action.equals("unfile")
                || action.equals("create") || action.equals("rename") || action.equals("delete"))) {
            Toast.makeText(this, "整理操作无效", Toast.LENGTH_LONG).show(); finish(); return;
        }
        if ((action.equals("add") || action.equals("move") || action.equals("unfile")) && bookIds.isEmpty()) {
            Toast.makeText(this, "请先选择书籍", Toast.LENGTH_LONG).show(); finish(); return;
        }
        build();
        if (!task.loaded && !task.busy && !task.done) task.load();
    }
    @Override public Object onRetainNonConfigurationInstance() { return task; }
    @Override protected void onStart() { super.onStart(); attached = true; if (task != null) { task.listener = listener; render(); } }
    @Override protected void onStop() { attached = false; if (task != null && task.listener == listener) task.listener = null; super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("targetId", targetId); super.onSaveInstanceState(out); }
    @Override public void onBackPressed() {
        if (task != null && task.busy) Toast.makeText(this, "正在整理，请等待完成", Toast.LENGTH_SHORT).show();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        if (task != null && !isChangingConfigurations()) task.worker.shutdown();
        super.onDestroy();
    }

    private void build() {
        LinearLayout root = Ui.column(this); Ui.mount(this, root); Ui.pad(root, 0);
        LinearLayout heading = Ui.column(this); Ui.pad(heading, 16); root.addView(heading);
        LinearLayout nav = Ui.row(this); nav.addView(Ui.button(this, "返回", this::onBackPressed));
        nav.addView(Ui.title(this, title(), 20), Ui.weight()); heading.addView(nav);
        heading.addView(Ui.text(this, description(), 14, Ui.MUTED));
        status = Ui.text(this, "", 13, Ui.GREEN); status.setMaxLines(3); heading.addView(status);
        selection = Ui.text(this, "", 13, Ui.INK); selection.setMaxLines(3); selection.setEllipsize(TextUtils.TruncateAt.MIDDLE); heading.addView(selection);
        boolean choose = action.equals("add") || action.equals("move");
        adapter = new FolderAdapter();
        if (choose) {
            LinearLayout tools = Ui.row(this);
            rootTarget = Ui.button(this, "新建在顶层", () -> { targetId = null; render(); });
            tools.addView(rootTarget, Ui.weight());
            newFolder = Ui.button(this, "新建文件夹", () -> inputName(false));
            tools.addView(newFolder, Ui.weight()); heading.addView(tools);
            ListView list = new ListView(this); list.setDivider(null); list.setAdapter(adapter);
            root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            View spacer = new View(this); root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        }
        LinearLayout footer = Ui.column(this); Ui.pad(footer, 16);
        footer.addView(Ui.text(this, "只修改书架归档；书籍副本、阅读进度和手机原文件均保留。", 12, Ui.MUTED));
        apply = Ui.button(this, applyLabel(), this::requestApply);
        footer.addView(apply, new LinearLayout.LayoutParams(-1, -2)); root.addView(footer);
    }
    private String title() {
        if (action.equals("add")) return "归档到文件夹";
        if (action.equals("move")) return "移动书架位置";
        if (action.equals("unfile")) return "移出文件夹";
        if (action.equals("rename")) return "重命名文件夹";
        if (action.equals("delete")) return "删除归档文件夹";
        return "新建文件夹";
    }
    private String description() {
        if (action.equals("add")) return "已选 " + bookIds.size() + " 本。增加归档位置，保留其他文件夹中的位置。";
        if (action.equals("move")) return "已选 " + bookIds.size() + " 本。" + scopeText(true);
        if (action.equals("unfile")) return "已选 " + bookIds.size() + " 本。" + scopeText(false);
        if (action.equals("delete")) return "将删除该文件夹及全部子文件夹的归档关系；书籍和原文件保留。来源目录重扫后可能重建归档。";
        if (action.equals("rename")) return "修改书架显示名称，手机原目录不变；重新扫描仍保留此显示名称。";
        return "在当前归档位置创建子文件夹；手机文件不移动。";
    }
    private String scopeText(boolean moving) {
        String from = getIntent().getStringExtra("fromFolderId");
        String name = getIntent().getStringExtra("fromFolderName");
        if (from == null || from.isEmpty() || BookStore.UNCLASSIFIED.equals(from))
            return moving ? "移出所选书籍的全部现有归档，再放入目标文件夹。" : "移出所选书籍的全部现有归档，之后在未分类中查看。";
        return "仅移出“" + (name == null ? "当前文件夹" : name) + "”中的位置，其他归档保留。"
                + (moving ? "再放入目标文件夹。" : "没有其他归档的书籍进入未分类。");
    }
    private String applyLabel() {
        if (action.equals("add")) return "归档到选中文件夹";
        if (action.equals("move")) return "移动到选中文件夹";
        if (action.equals("unfile")) return "确认移出归档";
        if (action.equals("rename")) return "输入新名称";
        if (action.equals("delete")) return "删除此文件夹及子文件夹";
        return "输入文件夹名称";
    }
    private void render() {
        if (isDestroyed() || apply == null) return;
        if (task.createdId != null) { targetId = task.createdId; task.createdId = null; }
        status.setText(task.message);
        Row selected = selectedRow();
        boolean choose = action.equals("add") || action.equals("move");
        if (choose) selection.setText(selected == null ? "尚未选择归档目标。可在顶层或选中文件夹下新建。" : "目标：" + selected.path);
        else {
            String name = action.equals("create") ? getIntent().getStringExtra("parentFolderName") : getIntent().getStringExtra("folderName");
            selection.setText((action.equals("create") ? "新建位置：" : "文件夹：") + (name == null ? "来源文件夹顶层" : name));
        }
        apply.setEnabled(!task.busy && (!choose || selected != null));
        if (newFolder != null) newFolder.setEnabled(!task.busy);
        if (rootTarget != null) rootTarget.setEnabled(!task.busy);
        adapter.notifyDataSetChanged();
        if (task.done) { setResult(RESULT_OK); finish(); }
    }
    private Row selectedRow() {
        for (Row row : task.rows) if (row.folder.id.equals(targetId)) return row;
        return null;
    }
    private void requestApply() {
        if (task.busy) return;
        if (action.equals("create") || action.equals("rename")) { inputName(action.equals("rename")); return; }
        Row destination = selectedRow();
        if ((action.equals("add") || action.equals("move")) && destination == null) return;
        String message = description() + (destination == null ? "" : "\n\n目标：" + destination.path)
                + (action.equals("delete") ? "\n\n文件夹：" + getIntent().getStringExtra("folderName") : "")
                + "\n\n书籍、阅读进度与原文件不会删除。";
        dialog = new AlertDialog.Builder(this).setTitle(title()).setMessage(message).setNegativeButton("取消", null)
                .setPositiveButton("确认", (d, w) -> {
                    final String dest = destination == null ? null : destination.folder.id;
                    final String from = getIntent().getStringExtra("fromFolderId");
                    final ArrayList<String> ids = new ArrayList<>(bookIds);
                    final String affectedFolder = getIntent().getStringExtra("folderId");
                    final String operation = action;
                    task.execute(store -> {
                        if (operation.equals("add") || operation.equals("move")) store.organizeBooks(ids, dest, from, operation.equals("move"));
                        else if (operation.equals("unfile")) store.unfileBooks(ids, from);
                        else if (operation.equals("delete")) store.deleteFolder(affectedFolder);
                        return null;
                    }, true);
                }).show();
    }
    private void inputName(boolean rename) {
        if (task.busy) return;
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("文件夹名称");
        if (rename) input.setText(getIntent().getStringExtra("folderName"));
        dialog = new AlertDialog.Builder(this).setTitle(rename ? "重命名" : "新建文件夹").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("请输入名称"); return; }
            final String parent = action.equals("create") ? getIntent().getStringExtra("parentFolderId") : targetId;
            final String affected = getIntent().getStringExtra("folderId");
            dialog.dismiss();
            task.execute(store -> {
                if (rename) { store.renameFolder(affected, name); return null; }
                return store.createFolder(parent, name);
            }, rename || action.equals("create"));
        }));
        dialog.show();
    }
    private final class FolderAdapter extends BaseAdapter {
        @Override public int getCount() { return task.rows.size(); }
        @Override public Row getItem(int position) { return task.rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : Ui.row(OrganizeBooksActivity.this);
            row.removeAllViews(); Ui.pad(row, 10); Row item = getItem(position);
            RadioButton radio = new RadioButton(OrganizeBooksActivity.this);
            radio.setChecked(item.folder.id.equals(targetId)); radio.setClickable(false); row.addView(radio);
            TextView path = Ui.text(OrganizeBooksActivity.this, item.path, 15, Ui.INK);
            path.setMaxLines(3); path.setEllipsize(TextUtils.TruncateAt.MIDDLE); row.addView(path, Ui.weight());
            row.setEnabled(!task.busy); row.setContentDescription("目标文件夹 " + item.path);
            row.setOnClickListener(v -> { if (!task.busy) { targetId = item.folder.id; render(); } });
            return row;
        }
    }
    private static List<Row> folderRows(List<BookStore.Folder> folders) {
        Map<String, BookStore.Folder> byId = new HashMap<>();
        for (BookStore.Folder folder : folders) if (!BookStore.UNCLASSIFIED.equals(folder.id)) byId.put(folder.id, folder);
        Map<String, String> cached = new HashMap<>(); List<Row> rows = new ArrayList<>();
        for (BookStore.Folder folder : byId.values()) rows.add(new Row(folder, folderPath(folder, byId, cached)));
        Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(rows, (a, b) -> { int order = collator.compare(a.path, b.path); return order != 0 ? order : a.folder.id.compareTo(b.folder.id); });
        return rows;
    }
    private static String folderPath(BookStore.Folder leaf, Map<String, BookStore.Folder> byId, Map<String, String> cached) {
        String known = cached.get(leaf.id); if (known != null) return known;
        ArrayList<BookStore.Folder> chain = new ArrayList<>(); Set<String> visited = new HashSet<>();
        BookStore.Folder current = leaf; String prefix = "";
        while (current != null) {
            String existing = cached.get(current.id);
            if (existing != null) { prefix = existing; break; }
            if (!visited.add(current.id) || chain.size() >= 256) { prefix = "[异常层级]"; break; }
            chain.add(current); current = current.parentId == null ? null : byId.get(current.parentId);
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            BookStore.Folder folder = chain.get(i); prefix = prefix.isEmpty() ? folder.name : prefix + " / " + folder.name;
            cached.put(folder.id, prefix);
        }
        return cached.get(leaf.id);
    }
    private static String errorMessage(Exception error) {
        String message = error.getMessage(); return message == null ? error.getClass().getSimpleName() : message;
    }
}
