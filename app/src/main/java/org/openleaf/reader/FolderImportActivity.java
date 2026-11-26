package org.openleaf.reader;

import org.openleaf.reader.application.ServiceRegistry;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Folder chooser and retained, cancellable batch importer. Parent registers this Activity. */
public final class FolderImportActivity extends Activity {
    private static final int REQUEST_TREE=7021;
    private static final int REQUEST_REPORT=7022;
    private static final String PREFS="folder_import";
    /**
     * 书架里的导入根分组名。{@link BookStore#ensureImportFolder} 按 (source_tree, relative_path) 找到既有行时
     * 会顺手把 name 改成传入的 rootName，所以重试执行器（JobRetryRunner）必须复用同一个字面量，
     * 否则会把用户已有的「导入文件夹」改名。
     */
    static final String IMPORT_ROOT_NAME = "导入文件夹";
    private static ImportTask task;
    private LinearLayout root, body, actions;
    private Button importButton;
    private TextView heading;
    private AlertDialog settingsDialog, confirmDialog;
    /** 导入完成后的结果对话框与可复制/可导出的失败明细文本。 */
    private AlertDialog resultDialog;
    private String pendingReport;
    private TextView status, summary, errors, source;
    private ProgressBar importProgress;
    private ListView list;
    private CandidateAdapter adapter;
    private Spinner filter;
    private CheckBox epubFormat,pdfFormat,txtFormat,referenceFiles,autoImport,groupFolders;
    private EditText maxSize;
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean attached;
    private final TaskListener listener=new TaskListener() {
        public void changed() { if (attached && !isFinishing() && !isDestroyed()) main.post(() -> { if (attached && !isFinishing() && !isDestroyed()) render(); }); }
        public void batch() { if (attached && !isFinishing() && !isDestroyed()) main.post(() -> { if (attached && !isFinishing() && !isDestroyed()) renderImportSummary(); }); }
        public void progress() { if (attached && !isFinishing() && !isDestroyed()) main.post(() -> { if (attached && !isFinishing() && !isDestroyed()) renderProgress(); }); }
        public void finished() { main.post(() -> {
            // 与 changed/batch/progress 同一口径：Activity 已不可见（按 Home/切走）时不在其上弹窗，
            // 交给 onStart 的 offerPendingResult() 补弹。
            if (!attached || isFinishing() || isDestroyed()) return;
            // 不在这里置 resultShown：失败明细是异步读出来的，置早了会在
            // 「结果还没 show 出来、Activity 就被销毁」的窗口里把补弹机会一起吃掉。
            showResult();
        }); }
    };

    @Override protected void onCreate(Bundle state) { super.onCreate(state); if (task==null) task=new ImportTask(getApplicationContext()); build(); if(state!=null)filter.setSelection(state.getInt("formatFilter",0)); }
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("formatFilter",filter.getSelectedItemPosition());super.onSaveInstanceState(out);}
    @Override protected void onStart() { super.onStart(); attached=true; task.attach(listener); render(); offerPendingResult(); }
    @Override protected void onStop() { attached=false; task.detach(listener); super.onStop(); }
    @Override public void onBackPressed() {
        if (task.running()) { new AlertDialog.Builder(this).setTitle("导入正在进行")
                .setMessage("返回会停止扫描或等待当前文件解析完成，已经导入的书籍会保留。")
                .setNegativeButton("继续",null).setPositiveButton("停止并返回",(d,w)->{task.cancel();finish();}).show(); }
        else super.onBackPressed();
    }

    private void build() {
        root=Ui.column(this); Ui.mount(this,root); body=Ui.column(this); Ui.pad(body,12); root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        heading=Ui.title(this,"批量导入文件夹",21); body.addView(heading);
        source=Ui.text(this,"尚未选择目录",12,Ui.MUTED);
        source.setMaxLines(1);source.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);body.addView(source);
        actions=Ui.row(this);
        actions.addView(Ui.button(this,"选择文件夹",this::chooseTree),Ui.weight());
        actions.addView(Ui.button(this,"重扫上次",this::scanLast),Ui.weight());
        actions.addView(Ui.button(this,"增量差异",this::showDelta),Ui.weight());
        actions.addView(Ui.button(this,"设置",this::showSettings),Ui.weight());
        body.addView(actions);
        LinearLayout settings=Ui.column(this);Ui.pad(settings,16);addImportSettings(settings);
        android.widget.ScrollView settingsScroll=new android.widget.ScrollView(this);settingsScroll.addView(settings);
        settingsDialog=new AlertDialog.Builder(this).setTitle("导入设置").setView(settingsScroll)
                .setPositiveButton("完成",(d,w)->saveSettings()).create();
        filter=new Spinner(this); String[] filters={"全部格式","TXT","EPUB","PDF"}; filter.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,filters)); filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){} public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(adapter!=null){adapter.filter=pos==0?"":filters[pos].toLowerCase(Locale.ROOT);adapter.refresh();renderSummary();}}}); body.addView(filter); body.addView(Ui.text(this,"筛选只改变显示；导入全部已勾选文件。",12,Ui.MUTED)); Ui.gap(body,6);
        // P1-1：可见集合与全局集合分别给按钮，避免两个含义共用一个「全选」。窄屏拆成两行，
        // 简短标签 + contentDescription 兼顾布局与 TalkBack。
        LinearLayout selectVisible=Ui.row(this);
        selectVisible.addView(Ui.button(this,"全选当前",()->setAll(true,true),"全选当前结果：只勾选当前筛选可见的候选"),Ui.weight());
        selectVisible.addView(Ui.button(this,"取消当前",()->setAll(false,true),"取消当前结果：只取消当前筛选可见的候选"),Ui.weight());
        body.addView(selectVisible); Ui.gap(body,4);
        LinearLayout selectAll=Ui.row(this);
        selectAll.addView(Ui.button(this,"全选全部",()->setAll(true,false),"选中全部文件：勾选所有候选（含被筛选隐藏的候选），跳过 0 字节文件"),Ui.weight());
        selectAll.addView(Ui.button(this,"清空全部",()->setAll(false,false),"清空全部选择：取消所有候选的勾选（含被筛选隐藏的候选）"),Ui.weight());
        body.addView(selectAll); Ui.gap(body,6);
        summary=Ui.text(this,"尚未扫描",13,Ui.MUTED); body.addView(summary); status=Ui.text(this,"",13,Ui.GREEN); status.setMaxLines(2);status.setEllipsize(android.text.TextUtils.TruncateAt.END);body.addView(status);
        importProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);importProgress.setMax(1000);importProgress.setVisibility(View.GONE);importProgress.setContentDescription("当前文件导入进度");body.addView(importProgress,new LinearLayout.LayoutParams(-1,Ui.dp(this,6)));
        errors=Ui.text(this,"",12,Ui.MUTED); errors.setMaxLines(3); errors.setEllipsize(android.text.TextUtils.TruncateAt.END); errors.setOnClickListener(v->{if(task.errorText().length()>0)new AlertDialog.Builder(this).setTitle("扫描与导入详情").setMessage(task.errorText()).setPositiveButton("知道了",null).show();}); body.addView(errors); Ui.gap(body,4);
        list=new ListView(this); list.setDivider(null); adapter=new CandidateAdapter(this); list.setAdapter(adapter); body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=Ui.row(this);Ui.pad(footer,8);
        Button stop=Ui.button(this,"停止任务",()->{if(task.running())task.cancel();else toast("当前没有运行中的任务");});
        footer.setMinimumHeight(Ui.dp(this,64));
        stop.setMinHeight(Ui.dp(this,52));
        footer.addView(stop,new LinearLayout.LayoutParams(-2,Ui.dp(this,52)));
        importButton=Ui.button(this,"导入选中的文件",this::startImport);
        importButton.setTextColor(android.graphics.Color.WHITE);
        importButton.setBackground(Ui.shape(Ui.GREEN,14,this));
        importButton.setMinHeight(Ui.dp(this,52));
        importButton.setContentDescription("导入选中的文件");
        footer.addView(importButton,new LinearLayout.LayoutParams(0,Ui.dp(this,52),1));
        root.addView(footer,new LinearLayout.LayoutParams(-1,-2));
    }
    private void showSettings() {
        if (task.running()) { toast("请先等待当前任务结束"); return; }
        settingsDialog.show();
    }
    @Override protected void onDestroy() {
        if (settingsDialog != null) settingsDialog.dismiss();
        if (confirmDialog != null) confirmDialog.dismiss();
        if (resultDialog != null) resultDialog.dismiss();
        super.onDestroy();
    }
    private void addImportSettings(LinearLayout body){
        body.addView(Ui.title(this,"文件格式",18));
        LinearLayout formats=Ui.row(this);epubFormat=check("EPUB",true);pdfFormat=check("PDF",true);txtFormat=check("TXT/MD",true);formats.addView(epubFormat,Ui.weight());formats.addView(pdfFormat,Ui.weight());formats.addView(txtFormat,Ui.weight());body.addView(formats);Ui.gap(body,8);
        LinearLayout limit=Ui.row(this);limit.addView(Ui.text(this,"默认勾选文件大小不超过",14,Ui.INK),Ui.weight());maxSize=new EditText(this);maxSize.setSingleLine(true);maxSize.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);maxSize.setHint("512");maxSize.setText(getSharedPreferences(PREFS,0).getString("maxMb","512"));limit.addView(maxSize,new LinearLayout.LayoutParams(Ui.dp(this,90),Ui.dp(this,52)));limit.addView(Ui.text(this,"MB",14,Ui.INK));body.addView(limit);body.addView(Ui.text(this,"该阈值只影响默认勾选；PDF/EPUB/TXT 仍受各自导入上限保护，大小未知或超限项可手动勾选尝试。",12,Ui.MUTED));Ui.gap(body,8);
        referenceFiles=check("直接引用原文件（不复制到书库）",true);referenceFiles.setEnabled(false);body.addView(referenceFiles);body.addView(Ui.text(this,"导入只登记原文件与书籍信息；阅读时直接访问原文件。移动、删除文件或撤销目录授权后需重新关联。文件必须提供长期读取授权，不会自动改为复制导入。",12,Ui.MUTED));
        body.addView(Ui.text(this,"实际文件大小上限：TXT/MD "+sizeText(BookStore.importLimit("txt"))+" · EPUB "+sizeText(BookStore.importLimit("epub"))+" · PDF "+sizeText(BookStore.importLimit("pdf"))+"。扫描大小可能不准；超限将由导入时的实际读取检查拦截。",12,Ui.MUTED));
        autoImport=check("自动导入此文件夹中的新书（偏好占位）",getSharedPreferences(PREFS,0).getBoolean("auto",false));body.addView(autoImport);body.addView(Ui.text(this,"当前版本仅保存此偏好，尚未实现后台监视；新书仍需手动点击扫描。",12,Ui.MUTED));
        body.addView(Ui.title(this,"文件夹结构",18));groupFolders=check("新导入默认从子文件夹创建分组",getSharedPreferences(PREFS,0).getBoolean("group",true));body.addView(groupFolders);body.addView(Ui.text(this,"这是默认值；每次导入前的「导入确认」还可以单独调整。关闭后，新导入的文件直接归档到当前导入文件夹。",12,Ui.MUTED));Ui.gap(body,12);
    }
    private CheckBox check(String label,boolean checked){CheckBox box=new CheckBox(this);box.setText(label);box.setTextSize(15);box.setTextColor(Ui.INK);box.setChecked(checked);return box;}
    private void saveSettings(){getSharedPreferences(PREFS,0).edit().putString("maxMb",maxSize.getText().toString()).putBoolean("auto",autoImport.isChecked()).putBoolean("group",groupFolders.isChecked()).apply();}
    private boolean formatEnabled(String format){return ("epub".equals(format)&&epubFormat.isChecked())||("pdf".equals(format)&&pdfFormat.isChecked())||("txt".equals(format)&&txtFormat.isChecked());}
    private long maxBytes(){try{long mb=Math.max(1,Long.parseLong(maxSize.getText().toString().trim()));return mb>Long.MAX_VALUE/(1024L*1024L)?Long.MAX_VALUE:mb*1024L*1024L;}catch(Exception ignored){return org.openleaf.reader.core.BookParser.MAX_IMPORT_BYTES;}}

    private void chooseTree(){
        if(task.running()){toast("请先等待当前任务结束");return;}
        try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),REQUEST_TREE);}
        catch(android.content.ActivityNotFoundException missing){new AlertDialog.Builder(this).setTitle("无法选择文件夹")
                .setMessage("系统未提供文件夹选择器，请启用系统文件管理器后重试。").setPositiveButton("知道了",null).show();}
    }
    /**
     * 阶段 3-B Y-1：增量差异（只读展示，不自动入库/删行）。
     * 对比 media_items 快照与本次扫描候选的路径集合；mtime/size 变化在 Y-1 不判 modified
     * （SAF 不返回可靠 mtime，避免全量误报），只展示新增/消失，符合设计 Y 的"候选"边界。
     */
    private void showDelta() {
        if (task == null || task.snapshotCandidates().isEmpty()) {
            toast("请先扫描文件夹"); return;
        }
        try {
            android.database.Cursor c = LibraryDatabase.get(this).getReadableDatabase()
                    .query("media_items", new String[]{"path"}, null, null, null, null, null);
            java.util.Set<String> dbPaths = new java.util.HashSet<>();
            while (c.moveToNext()) { String p = c.getString(0); if (p != null) dbPaths.add(p); }
            c.close();
            java.util.Set<String> diskPaths = new java.util.HashSet<>();
            for (DirectoryScanner.Candidate cand : task.snapshotCandidates()) {
                String p = cand.relativePath == null || cand.relativePath.isEmpty() ? cand.name : cand.relativePath;
                if (p != null) diskPaths.add(p);
            }
            java.util.List<String> added = new java.util.ArrayList<>(diskPaths); added.removeAll(dbPaths);
            java.util.List<String> gone = new java.util.ArrayList<>(dbPaths); gone.removeAll(diskPaths);
            java.util.Collections.sort(added); java.util.Collections.sort(gone);
            String body = "快照 " + dbPaths.size() + " 项 / 本次扫描 " + diskPaths.size() + " 项\n\n"
                    + "磁盘新增（未入库候选）：" + added.size() + "\n"
                    + (added.isEmpty() ? "" : String.join("\n", added.subList(0, Math.min(12, added.size()))) + "\n")
                    + "\n快照中但本次未见：" + gone.size() + "\n"
                    + (gone.isEmpty() ? "" : String.join("\n", gone.subList(0, Math.min(12, gone.size()))))
                    + "\n\n仅展示差异，不会自动导入或删除书库中的书。";
            TextView text = Ui.text(this, body, 13, Ui.INK);
            text.setTextIsSelectable(true); Ui.pad(text, 16);
            new AlertDialog.Builder(this).setTitle("媒体库增量差异").setView(text)
                    .setPositiveButton("关闭", null).show();
        } catch (Exception e) { toast("读取媒体库快照失败：" + e.getMessage()); }
    }

    private void scanLast(){ if(task.running()){toast("扫描已在进行");return;} String raw=getSharedPreferences(PREFS,0).getString("lastTree",null); if(raw==null){toast("还没有可用的上次目录");return;} Uri uri=Uri.parse(raw); if(!hasPermission(uri)){new AlertDialog.Builder(this).setTitle("目录授权已过期").setMessage("请重新选择文件夹并授予读取权限。").setPositiveButton("选择文件夹",(d,w)->chooseTree()).setNegativeButton("取消",null).show();return;} beginScan(uri); }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==REQUEST_REPORT){
            if(result==RESULT_OK&&data!=null&&data.getData()!=null)writeReport(data.getData());
            else{ toast("已取消保存"); finish(); }
            return;
        }
        if(request!=REQUEST_TREE||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if((data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0){
            try{
                if((data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)
                    getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                else getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            catch(SecurityException|IllegalArgumentException temporary){
                try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
                catch(SecurityException|IllegalArgumentException unavailable){/* Check persisted read permission below. */}
            }
        }
        if(!hasPermission(uri)){new AlertDialog.Builder(this).setTitle("需要长期读取授权").setMessage("直接引用原文件需要文件夹的长期读取权限。请重新选择支持授权的目录；本次未扫描或复制文件。").setPositiveButton("重新选择",(d,w)->chooseTree()).setNegativeButton("取消",null).show();return;}
        getSharedPreferences(PREFS,0).edit().putString("lastTree",uri.toString()).apply();
        beginScan(uri);
    }
    private boolean hasPermission(Uri uri){for(android.content.UriPermission p:getContentResolver().getPersistedUriPermissions())if(uri.equals(p.getUri())&&p.isReadPermission())return true;return false;}
    private void beginScan(Uri uri){saveSettings();synchronized(task.dataLock){task.enabledFormats.clear();if(epubFormat.isChecked())task.enabledFormats.add("epub");if(pdfFormat.isChecked())task.enabledFormats.add("pdf");if(txtFormat.isChecked())task.enabledFormats.add("txt");task.maxBytes=maxBytes();task.groupFolders=groupFolders.isChecked();}task.startScan(uri,getApplicationContext().getContentResolver());render();}
    private void startImport(){
        if(task.running()){toast("任务已在进行");return;}
        final int count=task.selectedCount();
        if(count==0){toast("请至少选择一个文件");return;}
        if(task.sourceTree==null||!hasPermission(task.sourceTree)){toast("目录长期读取授权已失效，请重新选择文件夹；不会复制导入");return;}
        long size=0;
        for(DirectoryScanner.Candidate c:task.snapshotCandidates()) if(task.isSelected(c)&&c.size>0) size+=c.size;
        LinearLayout view=Ui.column(this);Ui.pad(view,20);
        CheckBox group=check("同时导入目录分组信息",getSharedPreferences(PREFS,0).getBoolean("group",true));
        group.setContentDescription("同时导入目录分组信息");
        view.addView(group);
        view.addView(Ui.text(this,"关闭后，所选文件会直接放在导入文件夹的直接层级。",12,Ui.MUTED));
        confirmDialog=new AlertDialog.Builder(this).setTitle("导入确认")
                .setMessage("将导入 "+count+" 个文件"+(size>0?"，约 "+sizeText(size):"")+"。")
                .setView(view).setNegativeButton("取消",null)
                .setPositiveButton("确认",(d,w)->{
                    if(task.running()){toast("任务已在进行");return;}
                    if(task.sourceTree==null||!hasPermission(task.sourceTree)){toast("目录长期读取授权已失效，请重新选择文件夹；不会复制导入");return;}
                    synchronized(task.dataLock){task.groupFolders=group.isChecked();}
                    getSharedPreferences(PREFS,0).edit().putBoolean("group",group.isChecked()).apply();
                    if(groupFolders!=null) groupFolders.setChecked(group.isChecked());
                    task.startImport();render();
                }).create();
        confirmDialog.show();
        if (confirmDialog.getWindow()!=null) {
            android.view.WindowManager.LayoutParams lp=confirmDialog.getWindow().getAttributes();
            lp.gravity=Gravity.BOTTOM;
            lp.width=android.view.WindowManager.LayoutParams.MATCH_PARENT;
            confirmDialog.getWindow().setAttributes(lp);
        }
    }

    /**
     * 结果回调没送达时（用户离开页面、或 Activity 不可见时 early-return）回到页面补一次结果页。
     * 成功与有失败都补：只补失败会让「全部导入成功」的用户回来看到书架多了书却没有任何回执，
     * 无法确认是否导入完整。processed/failed/added/duplicates 是 ImportTask 上的 volatile 字段，
     * 由 worker 直接维护，所以不依赖 finished() 是否执行过。
     */
    private void offerPendingResult() {
        if (task == null || task.running() || task.resultShown || task.resultOpening || task.processed <= 0) return;
        showResult();
    }

    /**
     * 导入完成后的结果页。旧实现只弹一个「失败 N」的 Toast 就 finish()，
     * 失败明细随 Activity 一起消失：用户既不知道是哪本书，也不知道原因。
     * 现在结果对话框常驻，明细可滚动阅读、可长按复制、可另存为 txt；
     * 数据源是 job_items 的持久化记录（不经过 MAX_ERRORS 截断，重启后仍可在任务中心查看）。
     *
     * <p>取消态也要出结果页（用户需要知道停止前导入了多少），但**不能写「导入完成」**：
     * 明明是自己按的停止，却提示"完成"，等于告知错误事实。
     */
    private void showResult() {
        final boolean stopped = task.cancelled;
        final String title = stopped ? "导入已取消" : "导入完成";
        final String summary = (stopped ? "已取消 · " : "")
                + "已添加 " + task.added + " · 重复 " + task.duplicates + " · 失败 " + task.failed;
        if (task.failed <= 0) {
            task.resultShown = true;
            // 全重复必须单独说明：「已添加 0」很容易被读成"一本都没导入，坏了"。
            String conclusion = stopped
                    ? "\n\n已停止导入，已经导入的书籍会保留。"
                    : task.added == 0 && task.duplicates > 0
                        ? "\n\n这些书已在书架中，未重复入库。"
                        : "\n\n全部文件已加入书架。";
            resultDialog = new AlertDialog.Builder(this).setTitle(title)
                    .setMessage(summary + conclusion)
                    .setPositiveButton(stopped ? "关闭" : "完成", (d, w) -> finish())
                    .setCancelable(false).create();
            resultDialog.show();
            return;
        }
        // 失败明细要读一次 job_items（可能上千行），放到后台线程，避免主线程掉帧。
        // resultOpening 只挡住「读到一半又被 onStart 触发一次」的重入；resultShown 要等
        // 弹窗真正 show 出来才置位——否则 Activity 在此期间销毁，补弹机会就被永久吃掉。
        task.resultOpening = true;
        new Thread(() -> {
            final String detail = task.failureReport();
            main.post(() -> {
                task.resultOpening = false;
                if (isFinishing() || isDestroyed()) return;
                showFailureDetail(title, summary, detail);
            });
        }, "openleaf-import-report").start();
    }

    private void showFailureDetail(String title, String summary, String detail) {
        pendingReport = summary + "\n\n" + detail;
        LinearLayout view = Ui.column(this);
        Ui.pad(view, 16);
        view.addView(Ui.text(this, summary, 15, Ui.INK));
        Ui.gap(view, 6);
        view.addView(Ui.text(this, "失败明细（这些文件未加入书架，原文件未被修改或删除）", 13, Ui.MUTED));
        Ui.gap(view, 6);
        // 只截断「界面显示」；pendingReport 保存的是全量明细，复制与另存导出的都是完整清单。
        TextView body = Ui.text(this, visibleDetail(detail), 12, Ui.INK);
        body.setTextIsSelectable(true);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);
        view.addView(scroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 260)));
        Ui.gap(view, 6);
        view.addView(Ui.text(this, "同一份明细也记录在「任务中心」，重启后仍可查看。", 12, Ui.MUTED));
        Ui.gap(view, 6);
        // 结果页只给入口，不直接调 jobs.retryFailed()：重试仍走任务中心既有的
        // JobRetryRunner.start(Context, String) 链路；补弹路径同样经过 showFailureDetail。
        view.addView(Ui.button(this, "到任务中心重试",
                () -> startActivity(new Intent(this, JobsActivity.class)),
                "到任务中心重试：打开任务中心，使用重试失败项入口继续导入失败的文件"),
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        resultDialog = new AlertDialog.Builder(this).setTitle(title).setView(view).setCancelable(false)
                .setPositiveButton("复制清单", (d, w) -> { copyReport(); finish(); })
                .setNeutralButton("另存为 txt", (d, w) -> saveReport())
                .setNegativeButton("关闭", (d, w) -> finish()).create();
        resultDialog.show();
        // 真正 show 出来才算「已展示过」，此后 offerPendingResult 不再补弹。
        task.resultShown = true;
    }

    /**
     * 明细过长时只截断界面显示（上千条失败会让单次 setText 的主线程 layout 明显卡顿）。
     * 省略行只存在于 TextView，不会进入 pendingReport —— 复制与另存拿到的始终是全量清单。
     */
    private String visibleDetail(String detail) {
        final int limit = 300;
        String body = detail.endsWith("\n") ? detail.substring(0, detail.length() - 1) : detail;
        String[] lines = body.split("\n", -1);
        if (lines.length <= limit) return detail;
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < limit; i++) shown.append(lines[i]).append('\n');
        shown.append("（此处仅显示前 ").append(limit).append(" 条，共 ").append(lines.length)
                .append(" 条；用「复制清单」或「另存为 txt」获取完整清单）");
        return shown.toString();
    }

    private void copyReport() {
        String text = pendingReport == null ? "" : pendingReport;
        if (text.isEmpty()) return;
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) { toast("当前系统不支持剪贴板"); return; }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("导入失败明细", text));
        toast("失败明细已复制");
    }

    private void saveReport() {
        String text = pendingReport == null ? "" : pendingReport;
        if (text.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "OpenLeaf-导入失败明细.txt");
        try { startActivityForResult(intent, REQUEST_REPORT); }
        catch (android.content.ActivityNotFoundException missing) { toast("系统没有可用的保存位置，可改用复制清单"); }
    }

    private void writeReport(Uri target) {
        String text = pendingReport == null ? "" : pendingReport;
        try (java.io.OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
            if (out == null) throw new java.io.IOException("无法写入所选位置");
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            toast("失败明细已保存");
        } catch (Exception error) {
            toast("保存失败：" + ImportTask.msg(error));
        }
        finish();
    }

    private void setAll(boolean checked){setAll(checked,false);}
    /** visibleOnly=true 只改当前筛选可见集合；false 改全部候选（含隐藏项）。筛选本身不清空选择。 */
    private void setAll(boolean checked,boolean visibleOnly){
        if(task.running()){toast("请等待当前任务结束后修改选择");return;}
        int skipped=0;
        synchronized(task.dataLock){
            for(DirectoryScanner.Candidate c:task.candidates){
                if(visibleOnly&&adapter!=null&&!adapter.matches(c))continue;
                // 空文件（0 字节）导入必然失败：全选也不勾上，否则等于没修默认勾选。
                // 大小未知（-1）保留勾选——用户按下「全选」是明确意图，导入时会按上限复核。
                if(checked&&c.size==0){skipped++;continue;}
                task.selected.put(c.uri.toString(),checked);
            }
        }
        if(adapter!=null){adapter.refresh();}
        renderSummary();
        if(skipped>0)toast("已跳过 "+skipped+" 个空文件（0 字节）");
    }
    private void render(){
        if(adapter!=null)adapter.refresh();
        renderSummary();errors.setText(task.errorText());source.setText(task.sourceText());renderProgress();
    }
    private void renderImportSummary(){
        if(summary!=null)summary.setText("已选 "+task.total+" 项 · 正在导入 "+Math.min(task.processed+1,task.total)+" / "+task.total
                +"\n已添加 "+task.added+" · 重复 "+task.duplicates+" · 失败 "+task.failed);
        renderProgress();
    }
    private void renderProgress(){
        if(status==null||importProgress==null)return;
        status.setText(task.status());
        if(!task.importing){importProgress.setVisibility(View.GONE);return;}
        importProgress.setVisibility(View.VISIBLE);
        long total=task.copyTotal,done=task.copiedBytes;
        if(task.importPhase==BookStore.ImportPhase.COPY&&total>0){
            importProgress.setIndeterminate(false);
            importProgress.setProgress((int)Math.min(1000,Math.max(0,(1000.0*done)/total)));
        }else{
            importProgress.setIndeterminate(true);
        }
    }
    private void renderSummary(){
        if(summary==null)return;
        if(heading!=null){int found=task.candidateCount();heading.setText(found>0?"扫描结果（"+found+"）":"批量导入文件夹");}
        int count=0,unknown=0,visible=0,hidden=0;long size=0;
        for(DirectoryScanner.Candidate c:task.snapshotCandidates()){
            boolean matches=adapter==null||adapter.matches(c);
            if(matches)visible++;
            if(task.isSelected(c)){
                count++;
                if(!matches)hidden++; // 被当前筛选隐藏、但仍会被提交的候选
                if(c.size<0)unknown++;else size+=c.size;
            }
        }
        summary.setText("发现 "+task.candidateCount()+" · 当前筛选 "+visible+" · 已选 "+count+"（含隐藏 "+hidden+"）· "+sizeText(size)
                +(unknown==0?"":"（另 "+unknown+" 项大小未知）")
                +"\n已添加 "+task.added+" · 重复 "+task.duplicates+" · 失败 "+task.failed);
        if(importButton!=null){
            importButton.setText(task.importing?"正在导入…":task.scanning?"正在扫描…":"导入选中的文件（"+count+"）");
            importButton.setEnabled(!task.running());
        }
    }
    private String sizeText(long size){if(size<0)return "大小未知";if(size<1024)return size+" B";if(size<1024*1024)return (size/1024)+" KB";return (size/(1024*1024))+" MB";}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    private interface TaskListener { void changed(); void batch(); void progress(); void finished(); }
    private static final class ImportTask {
        final Context app; final Object dataLock=new Object(); final ExecutorService executor=Executors.newSingleThreadExecutor();
        volatile CancellationSignal activeSignal;
        volatile Uri sourceTree; volatile String rootName=IMPORT_ROOT_NAME; volatile java.util.HashSet<String> enabledFormats=new java.util.HashSet<String>(); volatile long maxBytes=org.openleaf.reader.core.BookParser.MAX_IMPORT_BYTES; volatile boolean groupFolders=true;
        final List<DirectoryScanner.Candidate> candidates=new ArrayList<DirectoryScanner.Candidate>(); final java.util.HashMap<String,Boolean> selected=new java.util.HashMap<String,Boolean>(); final List<String> errorList=new ArrayList<String>();
        volatile TaskListener attached; volatile boolean scanning, importing, cancelled; volatile boolean resultShown, resultOpening; volatile int visited, added, duplicates, failed, processed, total; volatile String current=""; volatile DirectoryScanner.Result scanResult; volatile long lastNotify;
        volatile BookStore.ImportPhase importPhase; volatile String currentFile=""; volatile long copiedBytes,copyTotal; volatile long lastProgressNotify,lastBatchNotify;
        volatile String jobId; final org.openleaf.reader.core.ContentContracts.JobRepository jobRepo;
        ImportTask(Context c){app=c.getApplicationContext();jobRepo=ServiceRegistry.of(c).jobs();}
        int candidateCount(){synchronized(dataLock){return candidates.size();}}
        int selectedCount(){synchronized(dataLock){int n=0;for(DirectoryScanner.Candidate c:candidates)if(Boolean.TRUE.equals(selected.get(c.uri.toString())))n++;return n;}}
        ArrayList<DirectoryScanner.Candidate> snapshotCandidates(){synchronized(dataLock){return new ArrayList<DirectoryScanner.Candidate>(candidates);}}
        boolean isSelected(DirectoryScanner.Candidate c){synchronized(dataLock){return Boolean.TRUE.equals(selected.get(c.uri.toString()));}}
        void attach(TaskListener l){attached=l;}
        void detach(TaskListener l){if(attached==l)attached=null;}
        boolean running(){return scanning||importing;}
        void notifyChanged(){long now=android.os.SystemClock.uptimeMillis();if(now-lastNotify<100)return;lastNotify=now;TaskListener l=attached;if(l!=null)l.changed();}
        void notifyBatch(){long now=android.os.SystemClock.uptimeMillis();if(now-lastBatchNotify<150)return;lastBatchNotify=now;TaskListener l=attached;if(l!=null)l.batch();}
        void notifyProgress(){long now=android.os.SystemClock.uptimeMillis();if(now-lastProgressNotify<150)return;lastProgressNotify=now;TaskListener l=attached;if(l!=null)l.progress();}
        void notifyFinal(){lastNotify=0;lastBatchNotify=lastProgressNotify=0;TaskListener l=attached;if(l!=null)l.changed();}
        ArrayList<DirectoryScanner.Candidate> snapshotSelected(){ArrayList<DirectoryScanner.Candidate> out=new ArrayList<DirectoryScanner.Candidate>();synchronized(dataLock){for(DirectoryScanner.Candidate c:candidates)if(Boolean.TRUE.equals(selected.get(c.uri.toString())))out.add(c);}return out;}
        void startScan(final Uri tree,final ContentResolver resolver){if(running())return;sourceTree=tree;rootName=IMPORT_ROOT_NAME;synchronized(dataLock){candidates.clear();selected.clear();}synchronized(errorList){errorList.clear();}added=duplicates=failed=processed=visited=0;cancelled=false;current="准备扫描";scanning=true;
            final CancellationSignal fresh=new CancellationSignal(); activeSignal=fresh;
            executor.execute(()->{try{scanResult=DirectoryScanner.scan(resolver,tree,fresh,new DirectoryScanner.Listener(){public void onCandidate(DirectoryScanner.Candidate c){synchronized(dataLock){candidates.add(c);selected.put(c.uri.toString(),enabledFormats.contains(c.format)&&c.size>0&&c.size<=maxBytes&&c.size<=BookStore.importLimit(c.format));}}public void onProgress(int v,int m){visited=v;notifyChanged();}});rootName=scanResult.rootName;for(String e:scanResult.errors)addError(e);}catch(Exception e){if(!cancelled)addError("扫描失败："+msg(e));}finally{activeSignal=null;scanning=false;current=cancelled?"扫描已取消":"扫描完成";notifyFinal();}}); }
        void startImport(){if(running()||sourceTree==null)return;cancelled=false;synchronized(errorList){errorList.clear();}added=duplicates=failed=processed=0;lastBatchNotify=0;resultShown=false;resultOpening=false;current="准备书库";importPhase=null;currentFile="";copiedBytes=copyTotal=0;importing=true;final ArrayList<DirectoryScanner.Candidate> picked=snapshotSelected();total=picked.size();final CancellationSignal fresh=new CancellationSignal();activeSignal=fresh;
            // 持久化任务与逐项检查点：进程死亡后可从 jobId 续跑
            jobId="folder-"+System.currentTimeMillis();
            ArrayList<org.openleaf.reader.core.ImportUseCase.Candidate> jobCandidates=new ArrayList<>();
            for(int seq=0;seq<picked.size();seq++){DirectoryScanner.Candidate c=picked.get(seq);jobCandidates.add(new org.openleaf.reader.core.ImportUseCase.Candidate(c.uri.toString(),c.relativePath,c.format,c.size));}
            final int[] jobSeq={0};
            jobRepo.create(jobId,"folder",sourceTree.toString(),org.openleaf.reader.core.ImportUseCase.toJobItems(jobCandidates));
            jobRepo.updateState(jobId,org.openleaf.reader.core.ContentContracts.JobState.RUNNING,0,0,0,0);
            executor.execute(()->{try{BookStore store=new BookStore(app);store.prepareImport(fresh);
                // 落库名优先用库里已有的：SAF 显示名可能因扫描异常停在默认值，直接传会把用户已有分组改名。
                final String storedName=store.importRootName(sourceTree);final String archiveName=storedName!=null?storedName:rootName;
                java.util.HashMap<String,String> folderIds=new java.util.HashMap<>();for(DirectoryScanner.Candidate c:picked){if(cancelled)break;current=c.relativePath;importPhase=null;currentFile=c.relativePath;copiedBytes=copyTotal=0;
                    // 逐项结果先攒在本地，等 processed 递增后一次性写入（单次提交，见 jobRepo.checkpoint）。
                    final int itemSeq=jobSeq[0];
                    String itemBook=null,itemError=null;
                    org.openleaf.reader.core.ContentContracts.ItemState itemState=org.openleaf.reader.core.ContentContracts.ItemState.PENDING;
                    try{int slash=c.relativePath.lastIndexOf('/');String relativeDirectory=(groupFolders&&slash>=0)?c.relativePath.substring(0,slash):"";
                    fresh.throwIfCanceled();String folderId=folderIds.get(relativeDirectory);
                    if(folderId==null){folderId=store.ensureImportFolder(sourceTree,archiveName,relativeDirectory);folderIds.put(relativeDirectory,folderId);}
                    BookStore.ImportResult r=store.importUri(c.uri,fresh,folderId,(name,phase,bytes,totalBytes)->{currentFile=name==null||name.isEmpty()?c.relativePath:name;importPhase=phase;copiedBytes=Math.max(0,bytes);copyTotal=Math.max(0,totalBytes);notifyProgress();},c.name,c.format);
                        itemBook=r.book==null?null:r.book.id;
                        if(r.duplicate){duplicates++;itemState=org.openleaf.reader.core.ContentContracts.ItemState.DUPLICATE;}
                        else{added++;itemState=org.openleaf.reader.core.ContentContracts.ItemState.DONE;}
                        jobSeq[0]++;}catch(android.os.OperationCanceledException e){if(cancelled)break;failed++;itemError="操作被取消";itemState=org.openleaf.reader.core.ContentContracts.ItemState.FAILED;addError(c.relativePath+"："+itemError);jobSeq[0]++;}catch(Exception e){if(cancelled)break;failed++;itemError=PickerImport.message(e);itemState=org.openleaf.reader.core.ContentContracts.ItemState.FAILED;addError(c.relativePath+"："+itemError);jobSeq[0]++;}processed++;try{jobRepo.checkpoint(jobId,itemSeq,itemState,itemBook,itemError,cancelled?org.openleaf.reader.core.ContentContracts.JobState.PAUSED:org.openleaf.reader.core.ContentContracts.JobState.RUNNING,processed,added,duplicates,failed);}catch(RuntimeException bookkeeping){android.util.Log.w("OpenLeafImport","checkpoint failed, book already imported: "+c.relativePath,bookkeeping);/* 记账失败静默降级：书已入库，不能 failed++（否则结果页"失败数"与明细条数对不上），也不进用户可见明细。任务中心该项可能停留旧状态，但书架结果是准的。 */}importPhase=null;notifyBatch();}}catch(android.os.OperationCanceledException e){if(!cancelled)addError("导入被系统取消");}catch(Exception e){if(!cancelled)addError("准备书库失败："+msg(e));}finally{activeSignal=null;importing=false;importPhase=null;current=cancelled?"导入已取消":"导入完成";if(jobId!=null){try{jobRepo.updateState(jobId,cancelled?org.openleaf.reader.core.ContentContracts.JobState.CANCELLED:org.openleaf.reader.core.ContentContracts.JobState.DONE,processed,added,duplicates,failed);}catch(RuntimeException updateFailure){android.util.Log.w("OpenLeafImport","updateState failed",updateFailure);/* 计数落盘失败不得阻断 notifyFinal()/finished()：否则导入完成却永远不出结果页，线上零线索 */}}notifyFinal();if(!cancelled){TaskListener finished=attached;if(finished!=null)finished.finished();}}});}
        void cancel(){
            if(cancelled||!running())return;
            cancelled=true;
            CancellationSignal signal=activeSignal;
            notifyFinal();
            if(signal!=null)new Thread(signal::cancel,"openleaf-folder-cancel").start();
        }
        void addError(String s){synchronized(errorList){if(errorList.size()<DirectoryScanner.MAX_ERRORS)errorList.add(s);}}
        String sourceText(){if(sourceTree==null)return "尚未选择目录";String path;try{path=DocumentsContract.getTreeDocumentId(sourceTree);}catch(RuntimeException invalid){path=sourceTree.toString();}return "当前目录："+path+"\n书架归档："+rootName+"（保留子文件夹）";}
        String status(){
            if(cancelled&&running())return "正在停止…已导入的书籍会保留";
            if(scanning)return "扫描 "+visited+" / "+DirectoryScanner.MAX_ENTRIES+"："+current;
            if(importing){
                String batch="导入 "+Math.min(processed+1,total)+" / "+total;
                if(importPhase==null)return batch+"\n准备："+current;
                String phase=importPhase==BookStore.ImportPhase.COPY?"读取":importPhase==BookStore.ImportPhase.VALIDATE?"校验":"保存";
                String detail=phase+"："+(currentFile.isEmpty()?current:currentFile);
                if(importPhase==BookStore.ImportPhase.COPY){
                    detail+=" · "+progressSize(copiedBytes);
                    if(copyTotal>0){
                        int percent=(int)Math.min(100,Math.max(0,(100.0*copiedBytes)/copyTotal));
                        detail+=" / "+progressSize(copyTotal)+"（"+percent+"%）";
                    }else detail+=" · 总大小未知";
                }
                return batch+"\n"+detail;
            }
            return current;
        }
        private static String progressSize(long bytes){
            if(bytes<1024)return bytes+" B";
            if(bytes<1024L*1024)return String.format(Locale.ROOT,"%.1f KB",bytes/1024.0);
            if(bytes<1024L*1024*1024)return String.format(Locale.ROOT,"%.1f MB",bytes/(1024.0*1024));
            return String.format(Locale.ROOT,"%.2f GB",bytes/(1024.0*1024*1024));
        }

        String errorText(){synchronized(errorList){if(errorList.isEmpty())return "";StringBuilder b=new StringBuilder("问题（最多显示 20 条）：\n");for(String s:errorList)b.append("• ").append(s).append('\n');return b.toString();}}

        /**
         * 失败明细（文件名 + 原因）。数据源是 job_items 的持久化记录，因此
         * 不受 errorList 的 MAX_ERRORS 截断，读完结果页、重启应用后仍可从任务中心取回。
         */
        String failureReport(){
            if(jobId==null)return "本次导入没有生成任务记录，无法读取明细。";
            StringBuilder out=new StringBuilder();
            int count=0;
            try{
                for(org.openleaf.reader.core.ContentContracts.JobItem item:jobRepo.items(jobId)){
                    if(item.state!=org.openleaf.reader.core.ContentContracts.ItemState.FAILED)continue;
                    count++;
                    String name=item.relativePath==null||item.relativePath.isEmpty()?item.uri:item.relativePath;
                    String reason=item.error==null||item.error.isEmpty()?"未知原因":item.error;
                    out.append(count).append(". ").append(name).append("：").append(reason).append('\n');
                }
            }catch(RuntimeException unreadable){
                return "读取失败明细出错："+msg(unreadable);
            }
            return count==0?"没有失败项。":out.toString();
        }
        static String msg(Exception e){String s=e.getMessage();return s==null||s.length()==0?e.getClass().getSimpleName():s;}
    }
    private final class CandidateAdapter extends BaseAdapter {
        final Context context; String filter=""; final ArrayList<DirectoryScanner.Candidate> visible=new ArrayList<DirectoryScanner.Candidate>(); CandidateAdapter(Context c){context=c;}
        boolean matches(DirectoryScanner.Candidate c){return filter.length()==0||filter.equals(c.format);}
        void refresh(){synchronized(task.dataLock){visible.clear();for(DirectoryScanner.Candidate c:task.candidates)if(matches(c))visible.add(c);}notifyDataSetChanged();}
        public int getCount(){return visible.size();}
        public Object getItem(int p){return p>=0&&p<visible.size()?visible.get(p):null;}
        public long getItemId(int p){return p;}
        public View getView(int p,View convert,android.view.ViewGroup parent){
            LinearLayout row=convert instanceof LinearLayout?(LinearLayout)convert:Ui.row(context);
            if(convert==null){CheckBox box=new CheckBox(context);box.setTag("check");row.addView(box);LinearLayout info=Ui.column(context);info.setTag("info");row.addView(info,new LinearLayout.LayoutParams(0,-2,1));Ui.pad(row,4);}
            CheckBox box=(CheckBox)row.findViewWithTag("check");LinearLayout info=(LinearLayout)row.findViewWithTag("info");
            DirectoryScanner.Candidate c=(DirectoryScanner.Candidate)getItem(p);if(c==null)return row;
            info.removeAllViews();info.addView(Ui.text(context,c.name,15,Ui.INK));
            String detail=c.relativePath+"  ·  "+c.format.toUpperCase(Locale.ROOT)+"  ·  "+sizeText(c.size);
            // P1-1：副文案前给明确状态词，复用既有扫描字段，不改 Candidate 数据结构。
            String statusWord,statusDetail;
            if(c.size==0){statusWord="空文件";statusDetail="空文件（0 字节），导入必然失败，已默认不勾选";}
            else if(c.size<0){statusWord="大小未知";statusDetail="大小未知（文件提供器未返回大小），已默认不勾选；可手动勾选，导入时按上限复核";}
            else if(c.size>BookStore.importLimit(c.format)){statusWord="超过格式上限";statusDetail="扫描大小超出格式上限；可手动勾选尝试，导入时复核";}
            else if(c.size>task.maxBytes){statusWord="超过默认阈值";statusDetail="超出默认勾选阈值；可手动勾选";}
            else{statusWord="可导入";statusDetail="符合当前格式与大小规则";}
            detail+="\n"+statusWord+"："+statusDetail;
            info.addView(Ui.text(context,detail,12,Ui.MUTED));
            box.setOnCheckedChangeListener(null);box.setChecked(task.isSelected(c));box.setEnabled(!task.running());
            box.setOnCheckedChangeListener((button,checked)->{synchronized(task.dataLock){task.selected.put(c.uri.toString(),checked);}renderSummary();});
            return row;
        }
    }
}
