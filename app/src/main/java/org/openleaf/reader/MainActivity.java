package org.openleaf.reader;

import org.openleaf.reader.application.ServiceRegistry;
import org.openleaf.reader.core.DropBatch;
import org.openleaf.reader.core.ImportUseCase;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.DragEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Collections;
import java.text.Collator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int ITEMS_PER_PAGE = 40;
    private static final int REQUEST_ORGANIZE = 51;
    // A UI-only state; it is never passed to BookStore.shelf().
    private static final String ALL_BOOKS = "@main-all-books";
    private static final String ROOT_LABEL = "来源文件夹";
    /** 标签视图回流：点标签时携带 normalizeTag 后的 tag_key，书库翻译成 #tag 查询。 */
    public static final String EXTRA_TAG = "openleaf.extra.tag";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // 封面占位色板是装饰色，刻意不随主题变化（书架固定使用 Ui 全局色），不是散落的语义令牌。
    private final int[] covers = {0xFF315D4F, 0xFF8B6249, 0xFF59687A, 0xFF8C7752};
    private final ArrayList<String> ancestorIds = new ArrayList<>();
    private final ArrayList<String> ancestorLabels = new ArrayList<>();
    private BookStore store;
    private LinearLayout page, root, navigation;
    private boolean gridMode=true;
    /** 阶段 4 精修：封面墙（隐藏网格书卡下方文字，只留封面），借鉴 baca. Hide Book Titles 形态。 */
    private boolean hideBookTitles=false;
    private boolean wide(){return getResources().getConfiguration().screenWidthDp>=720;}
    private EditText searchInput;
    // 搜索防抖：快速输入时合并为一次书库渲染，避免每次按键都 removeAllViews + 入队
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private LinearLayout shelfList;
    private final Runnable delayedSearch = () -> renderShelf(shelfList, true);
    private ScrollView libraryScroll;
    private String shelfScope;
    private int shelfScrollY;
    private boolean shelfPopulated;
    private int tab, bookPage;
    private String query = "";
    private String folderId;
    private String folderLabel = ROOT_LABEL;
    private volatile long libraryRequest;
    // 容错搜索索引缓存：仅在书库内容签名变化时重建，避免每次查询重新 index()
    private org.openleaf.reader.core.FuzzySearch.Index fuzzyIndex;
    private String fuzzyIndexSignature;
    private boolean organizing;
    private int sortOrder;
    private boolean favoritesOnly;
    private final LinkedHashSet<String> selectedBookIds = new LinkedHashSet<>();
    private final ArrayList<BookStore.Book> visibleBooks = new ArrayList<>();
    private final Map<String, CheckBox> visibleChecks = new HashMap<>();
    private Map<String,BookMetadataStore.Card> cardMetadata=Collections.emptyMap();
    private TextView selectionCount;
    private Button addArchiveButton, moveArchiveButton, unfileButton;
    /** 拖拽落点提示只弹一次；拖拽导入期间禁止重复投递。 */
    private boolean dragHintShown, dropImportRunning;
    /** 拖拽结果 Dialog 旋转恢复：generation 双检 + Bundle 落盘，避免旧 Activity 回调 BadTokenException。 */
    private volatile long dropGeneration;
    private static final String KEY_DROP_RESULT = "openleaf.dropResult";
    private Bundle pendingDropResult;
    private static Bundle retainedDropResult; // worker 完成时旧 Activity 已销毁的进程内兜底


    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new BookStore(this);
        recoverPendingWrites();
        gridMode=getSharedPreferences("shelf",0).getBoolean("grid",true);
        hideBookTitles=getSharedPreferences("shelf",0).getBoolean("hideBookTitles",false);
        if(state==null){folderId=ALL_BOOKS;folderLabel="全部书籍";}
        sortOrder = Math.max(0, Math.min(2, getSharedPreferences("shelf",0).getInt("sort",0)));
        favoritesOnly = getSharedPreferences("shelf",0).getBoolean("favoritesOnly",false);
        // 标签回流：必须在 favoritesOnly 的 prefs 恢复之后调用，否则 applyTagQuery 设的
        // favoritesOnly=false 会被 prefs 值覆盖（冷启动上次停在「★ 珍藏」时叠加珍藏过滤）。
        // state!=null 走 Bundle 恢复路径，不重放 EXTRA_TAG。
        if(state==null) applyTagQuery(getIntent());
        if (state != null) {
            tab = state.getInt("tab", 0);
            query = state.getString("query", "");
            bookPage = state.getInt("bookPage", 0);
            folderId = state.getString("folderId");
            folderLabel = state.getString("folderLabel", ROOT_LABEL);
            organizing = state.getBoolean("organizing", false);
            favoritesOnly = state.getBoolean("favoritesOnly", favoritesOnly);
            shelfScrollY = Math.max(0, state.getInt("shelfScrollY", 0));
            shelfScope = state.getString("shelfScope", libraryScope());
            ArrayList<String> selectedBooks = state.getStringArrayList("selectedBookIds");
            if (selectedBooks != null) selectedBookIds.addAll(selectedBooks);
            ArrayList<String> ids = state.getStringArrayList("ancestorIds");
            ArrayList<String> labels = state.getStringArrayList("ancestorLabels");
            if (ids != null && labels != null && ids.size() == labels.size()) {
                ancestorIds.addAll(ids);
                ancestorLabels.addAll(labels);
            }
        }
        shell();
        // 拖拽结果旋转恢复：Bundle 落盘优先，进程内兜底次之；展示后清标志避免重复弹
        Bundle restoredDrop = state == null ? null : state.getBundle(KEY_DROP_RESULT);
        if (restoredDrop == null) restoredDrop = retainedDropResult;
        if (restoredDrop != null) {
            retainedDropResult = null;
            pendingDropResult = restoredDrop;
            presentDropResult();
            pendingDropResult = null;
        }
        worker.execute(() -> {
            try {
                store.sample();
                runOnUiThread(() -> { if (!isDestroyed()) show(); });
                promptResumableJobsIfNeeded();
            } catch (Exception e) {
                error("书库初始化失败", e);
            }
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);   // 必须：否则 getIntent() 仍指向旧 intent，再次点标签不生效（§10 R5）
        applyTagQuery(intent);
    }

    /** 标签回流：EXTRA_TAG(tag_key) → 书库 #tag 查询；其余过滤状态归零，复用既有 tagsOnly 分支。 */
    private void applyTagQuery(Intent intent) {
        if (intent == null) return;
        String tag = intent.getStringExtra(EXTRA_TAG);
        if (tag == null || tag.isEmpty()) return;
        query = "#" + tag;
        tab = 0;
        folderId = ALL_BOOKS;
        folderLabel = "全部书籍";
        favoritesOnly = false;
        organizing = false;
        bookPage = 0;
        selectedBookIds.clear();
        ancestorIds.clear();
        ancestorLabels.clear();
        if (searchInput != null && !query.equals(searchInput.getText().toString()))
            searchInput.setText(query);   // TextWatcher 会再写回 query，值相同无害
        show();
    }

    /** 冷启动时若有未完成/暂停任务，提示一次（用户可到任务中心恢复）。 */
    /** 容错搜索索引：按书库内容签名缓存，仅书籍/标签变化时重建。 */
    private org.openleaf.reader.core.FuzzySearch.Index fuzzyIndexFor(
            List<BookStore.Book> all, Map<String, List<String>> tags) {
        StringBuilder signature = new StringBuilder();
        for (BookStore.Book book : all) {
            signature.append(book.id).append('|').append(book.title).append('|')
                    .append(String.join(",", tags.getOrDefault(book.id, Collections.emptyList()))).append(';');
        }
        String sig = org.openleaf.reader.core.ContentContracts.hashHex(
                signature.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (fuzzyIndex != null && sig.equals(fuzzyIndexSignature)) return fuzzyIndex;
        List<org.openleaf.reader.core.FuzzySearch.Document> docs = new ArrayList<>();
        for (BookStore.Book book : all) docs.add(new org.openleaf.reader.core.FuzzySearch.Document(
                book.id, book.title, "", String.join(" ", tags.getOrDefault(book.id, Collections.emptyList()))));
        fuzzyIndex = org.openleaf.reader.core.FuzzySearch.index(docs);
        fuzzyIndexSignature = sig;
        return fuzzyIndex;
    }

    private void promptResumableJobsIfNeeded() {
        try {
            final List<String> resumable = new SqliteJobRepository(this).resumableJobs();
            if (resumable.isEmpty()) return;
            // 同一批任务只提示一次，避免每次启动打扰
            String signature = resumable.size() + ":" + resumable.get(0);
            android.content.SharedPreferences prefs = getSharedPreferences("shelf", 0);
            if (signature.equals(prefs.getString("lastResumableHint", ""))) return;
            prefs.edit().putString("lastResumableHint", signature).apply();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                new AlertDialog.Builder(this).setTitle("有未完成的任务")
                        .setMessage("检测到 " + resumable.size() + " 个未完成/暂停的任务。可到任务中心查看并重试。")
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("前往任务中心", (d, w) ->
                                startActivity(new Intent(this, JobsActivity.class))).show();
            });
        } catch (Exception ignored) { /* 提示失败不影响书库加载 */ }
    }

    private AlertDialog importDialog;
    private TextView importStatus;
    private ProgressBar importBar;
    private final Runnable importListener = this::renderPickerImport;

    @Override protected void onStart() {
        super.onStart();
        attachPickerImport();
    }

    @Override protected void onStop() {
        PickerImport task = PickerImport.active;
        if (task != null && task.listener == importListener) task.listener = null;
        closeImportDialog();
        super.onStop();
    }

    /** 冷启动恢复：检测 SAF 写入与版本登记之间中断的写入，幂等收敛。 */
    private void recoverPendingWrites() {
        final PendingWriteStore pending = ServiceRegistry.of(this).pendingWrites();
        final java.util.List<PendingWriteStore.Record> unfinished = pending.unfinished();
        if (unfinished.isEmpty()) return;
        new Thread(() -> {
            for (PendingWriteStore.Record record : unfinished) {
                try {
                    String observed = null;
                    try (java.io.InputStream in = getContentResolver().openInputStream(android.net.Uri.parse(record.targetUri))) {
                        if (in != null) observed = org.openleaf.reader.core.ContentContracts.hashHex(in);
                    } catch (Exception unreadable) {
                        // 授权失效或目标不可读：保留记录，等重新授权后再收敛
                    }
                    org.openleaf.reader.core.PendingWriteReconciler.Resolution resolution =
                            org.openleaf.reader.core.PendingWriteReconciler.resolve(record.toPending(), observed);
                    if (resolution.decision == org.openleaf.reader.core.PendingWriteReconciler.Decision.COMMIT_VERSION) {
                        if (record.bookId != null && !record.bookId.isEmpty()) {
                            org.openleaf.reader.core.ContentContracts.Revision revision =
                                    new org.openleaf.reader.core.ContentContracts.Revision(
                                            record.targetUri, record.intendedSha, record.size, System.currentTimeMillis());
                            ServiceRegistry.of(MainActivity.this).revisions().register(record.bookId, record.targetUri, revision, record.targetUri);
                        }
                        pending.acknowledge(record.id);
                    } else if (resolution.decision == org.openleaf.reader.core.PendingWriteReconciler.Decision.NOT_WRITTEN
                            || resolution.decision == org.openleaf.reader.core.PendingWriteReconciler.Decision.INVALID) {
                        pending.acknowledge(record.id);
                    } else if (resolution.decision == org.openleaf.reader.core.PendingWriteReconciler.Decision.CONFLICT) {
                        pending.updateState(record.id, PendingWriteStore.CONFLICT, resolution.detail);
                    }
                } catch (Exception ignored) { /* 单条失败不影响其他记录 */ }
            }
        }, "openleaf-pending-write-recovery").start();
    }

    @Override protected void onResume() {
        super.onResume();
        if (root != null) show();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putString("query", query);
        out.putString("shelfScope", shelfScope);
        out.putInt("shelfScrollY", shelfPopulated && libraryScroll != null ? libraryScroll.getScrollY() : shelfScrollY);
        out.putInt("bookPage", bookPage);
        out.putString("folderId", folderId);
        out.putString("folderLabel", folderLabel);
        out.putBoolean("organizing", organizing);
        out.putBoolean("favoritesOnly", favoritesOnly);
        out.putStringArrayList("selectedBookIds", new ArrayList<>(selectedBookIds));
        out.putStringArrayList("ancestorIds", new ArrayList<>(ancestorIds));
        out.putStringArrayList("ancestorLabels", new ArrayList<>(ancestorLabels));
        if (pendingDropResult != null) out.putBundle(KEY_DROP_RESULT, pendingDropResult);
    }

    @Override public void onBackPressed() {
        if(tab==0 && organizing){organizing=false;selectedBookIds.clear();show();return;}
        if (tab == 0 && favoritesOnly) { allBooks();return; }
        if (tab == 0 && !query.isEmpty()) {
            query = "";
            bookPage = 0;
            // Keep the visible field and the query state in sync.
            if (searchInput != null && searchInput.length() > 0) searchInput.setText("");
            else show();
            return;
        }
        if (tab == 0 && folderId != null) {
            parentFolder();
            return;
        }
        super.onBackPressed();
    }

    private void shell() {
        root=Ui.column(this); Ui.mount(this,root);
        root.setOnDragListener(this::onDrag); // 拖拽批量导入落点（第二入口，不改 SAF 多选链路）
        LinearLayout workspace=Ui.row(this);workspace.setGravity(Gravity.TOP);
        root.addView(workspace,new LinearLayout.LayoutParams(-1,0,1));
        navigation=wide()?Ui.column(this):Ui.row(this);
        if(wide()){
            navigation.setBackgroundColor(Ui.PANEL);Ui.pad(navigation,16);
            workspace.addView(navigation,new LinearLayout.LayoutParams(Ui.dp(this,208),-1));
        }
        page=Ui.column(this);workspace.addView(page,new LinearLayout.LayoutParams(0,-1,1));
        if(!wide()){Ui.pad(navigation,8);root.addView(navigation,new LinearLayout.LayoutParams(-1,-2));}
        show();
    }
    private void navigation() {
        navigation.removeAllViews();
        if(wide()){
            TextView brand=Ui.title(this,"◫  OpenLeaf",24);Ui.pad(brand,8);navigation.addView(brand);
            TextView caption=Ui.text(this,"青简 · 我的阅读空间",12,Ui.MUTED);Ui.pad(caption,8);navigation.addView(caption);Ui.gap(navigation,32);
        }
        String[] labels={"▤  书库","◎  发现","✧  想法","▦  记录","⚙  关于"};
        String[] descs={"切换到书库","切换到在线发现","打开想法","打开阅读记录","切换到关于"};
        for(int i=0;i<labels.length;i++){
            final int selected=i>3?i-2:i;
            Button item;
            if(i==2) item=Ui.button(this,labels[i],()->startActivity(new Intent(this,IdeasActivity.class)),descs[i]);
            else if(i==3) item=Ui.button(this,labels[i],()->startActivity(new Intent(this,StatsActivity.class)),descs[i]);
            else item=Ui.button(this,labels[i],()->{tab=selected;show();},descs[i]);
            // 选中态容器色：书架固定用 Ui 全局色，宽屏浅底 / 窄屏 INK 高对比，刻意不随主题映射。
            if(i!=2&&i!=3&&tab==selected){item.setBackground(Ui.shape(wide()?0xFFE3E1DC:Ui.INK,9,this));if(!wide())item.setTextColor(Color.WHITE);}
            if(wide()){
                item.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Ui.dp(this,48));lp.bottomMargin=Ui.dp(this,6);navigation.addView(item,lp);
            }else navigation.addView(item,Ui.weight());
        }
        if(wide()){
            Ui.gap(navigation,28);TextView label=Ui.text(this,"我的收藏",11,Ui.MUTED);Ui.pad(label,8);navigation.addView(label);
            navigation.addView(Ui.button(this,"全部书籍",()->{tab=0;favoritesOnly=false;allBooks();},"显示全部书籍"));Ui.gap(navigation,6);
            navigation.addView(Ui.button(this,"★ 珍藏",this::showFavorites,"查看珍藏书籍"));Ui.gap(navigation,6);
            navigation.addView(Ui.button(this,"文件夹",()->{tab=0;favoritesOnly=false;rootFolder();},"浏览来源文件夹"));Ui.gap(navigation,6);
            navigation.addView(Ui.button(this,"最近阅读",()->{sortOrder=2;tab=0;favoritesOnly=false;allBooks();},"按最近阅读排序"));Ui.gap(navigation,6);
            navigation.addView(Ui.button(this,"想法",()->startActivity(new Intent(this,IdeasActivity.class)),"打开想法"));Ui.gap(navigation,6);
            navigation.addView(Ui.button(this,"阅读记录",()->startActivity(new Intent(this,StatsActivity.class)),"查看阅读记录"));Ui.gap(navigation,6);
            navigation.addView(Ui.button(this,"听书",()->startActivity(new Intent(this,TtsActivity.class)),"打开听书"));Ui.gap(navigation,6);
            navigation.addView(Ui.button(this,"音频",()->startActivity(new Intent(this,AudioPlayerActivity.class)),"打开音频播放器"));
            View spacer=new View(this);navigation.addView(spacer,new LinearLayout.LayoutParams(1,0,1));
            TextView local=Ui.text(this,"本地书库\n离线也能安心阅读",12,Ui.MUTED);local.setLineSpacing(Ui.dp(this,6),1);Ui.pad(local,8);navigation.addView(local);
        }
    }

    private String libraryScope() {
        String folder = folderId == null ? "" : folderId;
        return (favoritesOnly ? "favorites:" : "shelf:") + folder + "\n" + query;
    }

    private void show() {
        if (isDestroyed() || page == null) return;
        if (shelfPopulated && libraryScroll != null) shelfScrollY = libraryScroll.getScrollY();
        if (tab == 0) {
            String scope = libraryScope();
            if (!scope.equals(shelfScope)) shelfScrollY = 0;
            shelfScope = scope;
        }
        mainHandler.removeCallbacks(delayedSearch);
        navigation();
        libraryRequest++;
        selectionCount = null; addArchiveButton = null; moveArchiveButton = null; unfileButton = null;
        visibleBooks.clear();visibleChecks.clear();
        searchInput = null;
        shelfList = null;
        libraryScroll = null;
        shelfPopulated = false;
        page.removeAllViews();
        if (tab == 0) library();
        else if (tab == 1) network();
        else about();
    }

    private LinearLayout content() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = Ui.column(this);
        Ui.pad(body, wide()?28:16);
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        if (tab == 0) libraryScroll = scroll;
        return body;
    }

    private void library() {
        LinearLayout body = content();
        LinearLayout titleRow=Ui.row(this);
        titleRow.addView(Ui.title(this,"书库",28),Ui.weight());
        Button importBtn=Ui.primary(this,"＋ 导入",()->new AlertDialog.Builder(this).setTitle("导入书籍").setItems(new String[]{"添加文件（含标签与书评书包）","扫描文件夹"},(d,which)->{if(which==0)pickFiles();else startActivity(new Intent(this,FolderImportActivity.class));}).show());
        importBtn.setContentDescription("导入书籍");
        titleRow.addView(importBtn);
        body.addView(titleRow);Ui.gap(body,20);
        LinearLayout searchRow=Ui.row(this);
        EditText search=new EditText(this);searchInput=search;search.setSingleLine(true);search.setTextSize(14);search.setTextColor(Ui.INK);search.setHintTextColor(Ui.MUTED);search.setHint("搜索书名、标签…");search.setContentDescription("搜索书名、标签");search.setText(query);search.setBackground(Ui.outline(this,Color.WHITE,10));search.setPadding(Ui.dp(this,16),0,Ui.dp(this,12),0);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(0,Ui.dp(this,46),1);searchParams.rightMargin=Ui.dp(this,8);searchRow.addView(search,searchParams);
        Button clearSearch = Ui.button(this, "清除", () -> search.setText(""), "清除搜索词");
        clearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        searchRow.addView(clearSearch);
        body.addView(searchRow);Ui.gap(body,12);
        Button wall=Ui.button(this,hideBookTitles?"▦ 显示书名":"▤ 封面墙",()->{
            hideBookTitles=!hideBookTitles;
            getSharedPreferences("shelf",0).edit().putBoolean("hideBookTitles",hideBookTitles).apply();
            show();
        },"切换封面墙（隐藏书名）");
        if(gridMode) body.addView(wall);
        LinearLayout modes=Ui.row(this);
        Button all=Ui.button(this,"全部书籍",this::allBooks,"显示全部书籍");Button folders=Ui.button(this,"文件夹",this::rootFolder,"浏览来源文件夹");
        Button favorites=Ui.button(this,"★ 珍藏",()->{if(favoritesOnly)allBooks();else showFavorites();},"按珍藏筛选");
        if(favoritesOnly){favorites.setBackground(Ui.shape(Ui.INK,8,this));favorites.setTextColor(Color.WHITE);}
        else if(ALL_BOOKS.equals(folderId)){all.setBackground(Ui.shape(Ui.INK,8,this));all.setTextColor(Color.WHITE);}
        else{folders.setBackground(Ui.shape(Ui.INK,8,this));folders.setTextColor(Color.WHITE);}
        modes.addView(all);modes.addView(folders);modes.addView(favorites);View fill=new View(this);modes.addView(fill,new LinearLayout.LayoutParams(0,1,1));
        modes.addView(Ui.button(this,organizing?"完成":"选择",()->{organizing=!organizing;if(!organizing)selectedBookIds.clear();show();},"切换批量选择模式"));body.addView(modes);
        if(!ALL_BOOKS.equals(folderId))breadcrumb(body);
        HorizontalScrollView toolStrip = new HorizontalScrollView(this);
        toolStrip.setHorizontalScrollBarEnabled(false);
        toolStrip.setFillViewport(true);
        LinearLayout tools=Ui.row(this);
        tools.addView(Ui.button(this,gridMode?"☷ 列表":"▦ 网格",()->{gridMode=!gridMode;getSharedPreferences("shelf",0).edit().putBoolean("grid",gridMode).apply();show();},"切换网格与列表视图"));
        tools.addView(Ui.button(this,"＋ 新建文件夹",this::createFolder,"新建文件夹"));
        Spinner sorting=new Spinner(this);sorting.setContentDescription("选择书籍排序方式");sorting.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"最近导入","书名","最近阅读"}));sorting.setSelection(sortOrder);tools.addView(sorting);
        tools.addView(Ui.button(this,"漫画",()->startActivity(new Intent(this,ComicActivity.class)),"打开漫画"));
        tools.addView(Ui.button(this,"听书",()->startActivity(new Intent(this,TtsActivity.class)),"打开听书"));
        tools.addView(Ui.button(this,"音频",()->startActivity(new Intent(this,AudioPlayerActivity.class)),"打开音频播放器"));
        tools.addView(Ui.button(this,"任务",()->startActivity(new Intent(this,JobsActivity.class)),"打开任务中心"));
        toolStrip.addView(tools);
        body.addView(toolStrip);Ui.gap(body,12);
        if(organizing)selectionTools(body);

        LinearLayout list = Ui.column(this);
        shelfList = list;
        body.addView(list);
        renderShelf(list, false);
        sorting.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                if(position==sortOrder)return;
                sortOrder=position;bookPage=0;getSharedPreferences("shelf",0).edit().putInt("sort",position).apply();
                renderShelf(list,false);
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void afterTextChanged(Editable s) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String nextQuery = s.toString();
                if (query.equals(nextQuery)) return;
                clearForScopeChange();
                query = nextQuery;
                clearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                bookPage = 0;
                shelfScope = libraryScope();
                shelfScrollY = 0;
                shelfPopulated = false;
                libraryRequest++; // Do not show results for an older query during the debounce window.
                if (visibleBooks.size() > 0 || list.getChildCount() > 1) showShelfLoading(list);
                mainHandler.removeCallbacks(delayedSearch);
                mainHandler.postDelayed(delayedSearch, 250);
            }
        });
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            mainHandler.removeCallbacks(delayedSearch);
            renderShelf(list, true);
            android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            return true;
        });
    }

    private void selectionTools(LinearLayout body){
        selectionCount=Ui.text(this,"",14,Ui.GREEN);body.addView(selectionCount);
        LinearLayout pageActions=Ui.row(this);
        pageActions.addView(Ui.button(this,"选择当前页",()->{
            for(BookStore.Book book:visibleBooks)selectedBookIds.add(book.id);
            syncVisibleChecks();updateSelectionCount();
        },"选中当前页全部书籍"),Ui.weight());
        pageActions.addView(Ui.button(this,"取消选择",()->{
            selectedBookIds.clear();syncVisibleChecks();updateSelectionCount();
        },"清空全部选择"),Ui.weight());body.addView(pageActions);
        LinearLayout actions=Ui.row(this);
        addArchiveButton=Ui.button(this,"归档到",()->organize("add"),"归档选中书籍");actions.addView(addArchiveButton,Ui.weight());
        moveArchiveButton=Ui.button(this,"移动到",()->organize("move"),"移动选中书籍");actions.addView(moveArchiveButton,Ui.weight());
        unfileButton=Ui.button(this,"移出文件夹",()->organize("unfile"),"将选中书籍移出文件夹");actions.addView(unfileButton,Ui.weight());body.addView(actions);
        body.addView(Ui.text(this,"跨页保留选择；切换目录或搜索会清空选择。",12,Ui.MUTED));
        updateSelectionCount();
    }
    private void updateSelectionCount(){
        if(selectionCount!=null)selectionCount.setText("已选 "+selectedBookIds.size()+" 本");
        boolean hasSelection=!selectedBookIds.isEmpty();
        if(addArchiveButton!=null)addArchiveButton.setEnabled(hasSelection);
        if(moveArchiveButton!=null)moveArchiveButton.setEnabled(hasSelection);
        if(unfileButton!=null)unfileButton.setEnabled(hasSelection);
    }
    private void setBookSelected(String id,boolean checked){
        if(checked)selectedBookIds.add(id);else selectedBookIds.remove(id);
        updateSelectionCount();
    }
    private void syncVisibleChecks(){
        for(Map.Entry<String,CheckBox> item:visibleChecks.entrySet())item.getValue().setChecked(selectedBookIds.contains(item.getKey()));
    }
    private void toggleBook(String id){
        boolean checked=!selectedBookIds.contains(id);
        CheckBox check=visibleChecks.get(id);
        if(check!=null)check.setChecked(checked);else setBookSelected(id,checked);
    }
    private void clearForScopeChange(){
        if(!selectedBookIds.isEmpty()){
            selectedBookIds.clear();
            Toast.makeText(this,"已切换浏览范围，选择已清空",Toast.LENGTH_SHORT).show();
        }
        updateSelectionCount();
    }
    private String currentArchiveFolder(){
        return !query.isEmpty()||ALL_BOOKS.equals(folderId)||BookStore.UNCLASSIFIED.equals(folderId)?null:folderId;
    }
    private void organize(String action){
        if(selectedBookIds.isEmpty()){Toast.makeText(this,"请先选择书籍",Toast.LENGTH_SHORT).show();return;}
        Intent intent=new Intent(this,OrganizeBooksActivity.class).putExtra("action",action)
                .putStringArrayListExtra("bookIds",new ArrayList<>(selectedBookIds))
                .putExtra("fromFolderId",currentArchiveFolder()).putExtra("fromFolderName",folderLabel);
        startActivityForResult(intent,REQUEST_ORGANIZE);
    }
    private void createFolder(){
        startActivityForResult(new Intent(this,OrganizeBooksActivity.class).putExtra("action","create")
                .putExtra("parentFolderId",currentArchiveFolder())
                .putExtra("parentFolderName",currentArchiveFolder()==null?"书架顶层":folderLabel),REQUEST_ORGANIZE);
    }

    private void breadcrumb(LinearLayout body) {
        if (folderId != null) body.addView(Ui.button(this, "‹ 返回上一级", this::parentFolder,"返回上一级文件夹"));
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout trail = Ui.row(this);
        for (int i = 0; i < ancestorIds.size(); i++) {
            final int index = i;
            trail.addView(Ui.button(this, ancestorLabels.get(i), () -> ancestorFolder(index),"返回文件夹 "+ancestorLabels.get(i)));
            trail.addView(Ui.text(this, "›", 14, Ui.MUTED));
        }
        TextView current = Ui.title(this, folderLabel, 14);
        current.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        trail.addView(current);
        horizontal.addView(trail);
        body.addView(horizontal);
    }

    private void rootFolder() {
        clearForScopeChange();
        if (favoritesOnly) getSharedPreferences("shelf",0).edit().putBoolean("favoritesOnly",false).apply();
        favoritesOnly = false;
        folderId = null;
        folderLabel = ROOT_LABEL;
        ancestorIds.clear();
        ancestorLabels.clear();
        query = "";
        bookPage = 0;
        show();
    }

    private void allBooks() {
        clearForScopeChange();
        if (favoritesOnly) getSharedPreferences("shelf",0).edit().putBoolean("favoritesOnly",false).apply();
        favoritesOnly = false;
        folderId = ALL_BOOKS;
        folderLabel = "全部书籍";
        ancestorIds.clear();
        ancestorLabels.clear();
        ancestorIds.add("");
        ancestorLabels.add(ROOT_LABEL);
        query = "";
        bookPage = 0;
        show();
    }

    private void showFavorites() {
        clearForScopeChange();
        favoritesOnly = true;
        folderId = ALL_BOOKS;
        folderLabel = "★ 珍藏";
        ancestorIds.clear();
        ancestorLabels.clear();
        ancestorIds.add("");
        ancestorLabels.add(ROOT_LABEL);
        // R-A-09：施加珍藏筛选时保留纯文字关键词（如 qqq）。仅当当前是 #标签查询时才清空，
        // 避免标签查询与珍藏筛选叠加；纯文字词原样保留，show()→library() 会 setText(query) 恢复输入框。
        if (searchKey(query).trim().startsWith("#")) query = "";
        bookPage = 0;
        getSharedPreferences("shelf",0).edit().putBoolean("favoritesOnly",true).apply();
        show();
    }

    private void enterFolder(BookStore.Folder folder) {
        clearForScopeChange();
        ancestorIds.add(folderId == null ? "" : folderId);
        ancestorLabels.add(folderLabel);
        folderId = folder.id;
        folderLabel = folder.name;
        bookPage = 0;
        show();
    }

    private void parentFolder() {
        if (ancestorIds.isEmpty()) {
            rootFolder();
            return;
        }
        ancestorFolder(ancestorIds.size() - 1);
    }

    private void ancestorFolder(int index) {
        clearForScopeChange();
        String target = ancestorIds.get(index);
        folderId = target.isEmpty() ? null : target;
        folderLabel = ancestorLabels.get(index);
        while (ancestorIds.size() > index) {
            ancestorIds.remove(ancestorIds.size() - 1);
            ancestorLabels.remove(ancestorLabels.size() - 1);
        }
        query = "";
        bookPage = 0;
        show();
    }

    private void showShelfLoading(LinearLayout list) {
        visibleBooks.clear();visibleChecks.clear();
        list.removeAllViews();
        list.addView(Ui.text(this, "正在读取书架…", 13, Ui.MUTED));
    }

    private void renderShelf(LinearLayout list, boolean scrollTop) {
        if (list == null || list != shelfList || isDestroyed()) return;
        if (scrollTop) shelfScrollY = 0;
        else if (shelfPopulated && libraryScroll != null) shelfScrollY = libraryScroll.getScrollY();
        shelfPopulated = false;
        final long request = ++libraryRequest;
        final String rawFilter = searchKey(query).trim();
        final boolean tagsOnly = rawFilter.startsWith("#");
        final String filter = tagsOnly ? rawFilter.substring(1).trim() : rawFilter;
        final String currentFolder = folderId;
        final int sorting = sortOrder;
        final boolean favoriteFilter = favoritesOnly;
        showShelfLoading(list);
        worker.execute(() -> {
            if (request != libraryRequest) return;
            try {
                List<BookStore.Folder> folders = new ArrayList<>();
                List<BookStore.Book> books = new ArrayList<>();
                Map<String, List<String>> tags = store.allTags();
                Map<String,BookMetadataStore.Card> metadata=new BookMetadataStore(this).cards();
                boolean searching = tagsOnly || !filter.isEmpty();
                if (searching || ALL_BOOKS.equals(currentFolder)) {
                    List<BookStore.Book> all = store.all();
                    for (BookStore.Book book : all) {
                        BookMetadataStore.Card card = metadata.get(book.id);
                        if (!favoriteFilter || (card != null && card.favorite)) books.add(book);
                    }
                    if (tagsOnly) {
                        if (filter.isEmpty()) books.clear();
                        else books.removeIf(book -> !hasMatchingTag(tags, book.id, filter));
                    } else if (searching) {
                        List<BookStore.Book> candidates = new ArrayList<>(books);
                        books.removeIf(book -> !searchKey(book.title).contains(filter)
                                && !hasMatchingTag(tags, book.id, filter));
                        // 容错只在当前范围没有直接匹配时启用，不遮盖书名或标签的普通匹配。
                        if (books.isEmpty() && !candidates.isEmpty()) {
                            org.openleaf.reader.core.FuzzySearch.Index index = fuzzyIndexFor(all, tags);
                            java.util.Set<String> matched = new java.util.HashSet<>();
                            int limit = favoriteFilter ? all.size() : 60;
                            for (org.openleaf.reader.core.FuzzySearch.Hit hit : org.openleaf.reader.core.FuzzySearch.search(index, filter, limit))
                                matched.add(hit.document.id);
                            for (BookStore.Book book : candidates) if (matched.contains(book.id)) books.add(book);
                        }
                    }
                } else {
                    BookStore.ShelfContents contents = store.shelf(currentFolder);
                    folders.addAll(contents.folders);
                    books.addAll(contents.books);
                }
                Collator collator=Collator.getInstance(Locale.CHINA);
                Collections.sort(folders,(a,b)->{int byName=collator.compare(a.name,b.name);return byName!=0?byName:a.id.compareTo(b.id);});
                SharedPreferences reading = getSharedPreferences("reader", MODE_PRIVATE);
                Map<String,Long> opened=new HashMap<>();
                boolean mayContinue = !searching && !favoriteFilter && ALL_BOOKS.equals(currentFolder);
                BookStore.Book latest = null;
                long latestOpened = 0;
                if (sorting == 2 || mayContinue) {
                    for (BookStore.Book book : books) {
                        long time = reading.getLong("opened_" + book.id, 0);
                        if (sorting == 2) opened.put(book.id, time);
                        if (mayContinue && time > 0 && (time > latestOpened
                                || (time == latestOpened && latest != null && book.id.compareTo(latest.id) < 0))) {
                            latest = book;
                            latestOpened = time;
                        }
                    }
                }
                final BookStore.Book resumeBook = latest;
                final String resumePosition = resumeBook == null ? "" : savedPosition(reading, resumeBook);
                Collections.sort(books,(a,b)->{
                    int result=sorting==1?collator.compare(a.title,b.title):sorting==2
                            ?Long.compare(opened.get(b.id),opened.get(a.id)):Long.compare(b.added,a.added);
                    if(result==0)result=collator.compare(a.title,b.title);
                    return result!=0?result:a.id.compareTo(b.id);
                });
                runOnUiThread(() -> {
                    if (isDestroyed() || request != libraryRequest) return;
                    cardMetadata=metadata;
                    populateShelf(list, folders, books, searching, tagsOnly, filter, tags, resumeBook, resumePosition);
                    ScrollView scroll = libraryScroll;
                    if (scroll != null) scroll.post(() -> {
                        if (!isDestroyed() && request == libraryRequest && scroll == libraryScroll) {
                            scroll.scrollTo(0, shelfScrollY);
                            shelfPopulated = true;
                        }
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed() || request != libraryRequest) return;
                    list.removeAllViews();
                    String message = currentFolder != null && !ALL_BOOKS.equals(currentFolder)
                            ? "当前文件夹无法读取，可能已被移除。可返回顶层后重新选择。"
                            : "书架读取失败，请保留应用数据后重试。";
                    list.addView(Ui.text(this, message, 14, Ui.INK));
                    list.addView(Ui.text(this, exceptionMessage(e), 12, Ui.MUTED));
                    list.addView(Ui.button(this, "重试", () -> renderShelf(list, false), "重新读取当前书架"));
                    list.addView(Ui.button(this, "返回来源文件夹顶层", this::rootFolder,"回到来源文件夹根目录"));
                });
            }
        });
    }

    private void populateShelf(LinearLayout list, List<BookStore.Folder> folders,
                               List<BookStore.Book> books, boolean searching, boolean tagsOnly, String filter,
                               Map<String, List<String>> tags, BookStore.Book resumeBook, String resumePosition) {
        list.removeAllViews();
        visibleBooks.clear();visibleChecks.clear();
        String summary;
        if (tagsOnly && filter.isEmpty()) summary = "标签搜索";
        else if (searching) summary = (favoritesOnly ? "在珍藏中搜索" : tagsOnly ? "标签搜索" : "全局搜索")
                + " · 找到 " + books.size() + " 本";
        else if (favoritesOnly) summary = "★ 珍藏 · " + books.size() + " 本";
        else if (ALL_BOOKS.equals(folderId)) summary = "全部书籍 · " + books.size() + " 本";
        else summary = "本层 · " + folders.size() + " 个文件夹 / " + books.size() + " 本书";
        TextView status = Ui.text(this, summary, 13, Ui.MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        list.addView(status);
        Ui.gap(list, 12);
        int totalItems = folders.size() + books.size();
        int pages = Math.max(1, (totalItems + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        bookPage = Math.max(0, Math.min(bookPage, pages - 1));
        if (resumeBook != null && !organizing && bookPage == 0) addContinueCard(list, resumeBook, resumePosition);
        int start = bookPage * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, totalItems);
        LinearLayout gridRow=null;int gridColumn=0;
        int columns=Math.max(2,(getResources().getConfiguration().screenWidthDp-(wide()?264:32))/156);
        for(int i=start;i<end;i++){
            if(i<folders.size()){addFolderRow(list,folders.get(i));continue;}
            BookStore.Book current=books.get(i-folders.size());visibleBooks.add(current);
            if(!gridMode){addBookCard(list,current,tags.getOrDefault(current.id,Collections.emptyList()));continue;}
            if(gridColumn==0){gridRow=Ui.row(this);gridRow.setGravity(Gravity.TOP);list.addView(gridRow,new LinearLayout.LayoutParams(-1,-2));}
            addBookTile(gridRow,current,tags.getOrDefault(current.id,Collections.emptyList()));gridColumn++;
            if(gridColumn==columns)gridColumn=0;
        }
        if(gridMode&&gridColumn>0&&gridRow!=null)for(int n=gridColumn;n<columns;n++)gridRow.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        updateSelectionCount();
        if (totalItems == 0) addShelfEmptyState(list, searching, tagsOnly, filter);
        if (pages > 1) {
            LinearLayout pager = Ui.row(this);
            Button previous = Ui.button(this, "上一页", () -> {
                bookPage--;
                renderShelf(list, true);
            },"翻到上一页书单");
            previous.setEnabled(bookPage > 0);
            pager.addView(previous, Ui.weight());
            TextView label = Ui.text(this, (bookPage + 1) + " / " + pages, 14, Ui.MUTED);
            label.setGravity(Gravity.CENTER);
            pager.addView(label, Ui.weight());
            Button next = Ui.button(this, "下一页", () -> {
                bookPage++;
                renderShelf(list, true);
            },"翻到下一页书单");
            next.setEnabled(bookPage + 1 < pages);
            pager.addView(next, Ui.weight());
            list.addView(pager);
            list.addView(Ui.text(this, "文件夹与书籍合计每页最多 " + ITEMS_PER_PAGE + " 项。", 12, Ui.MUTED));
        }
    }

    private void addContinueCard(LinearLayout list, BookStore.Book book, String position) {
        LinearLayout card = Ui.row(this);
        card.setBackground(Ui.outline(this, Color.WHITE, 14));
        Ui.pad(card, 14);
        LinearLayout info = Ui.column(this);
        info.addView(Ui.text(this, "上次阅读", 12, Ui.MUTED));
        Ui.gap(info, 4);
        TextView title = Ui.title(this, book.title, 17);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(title);
        String format = book.format.toUpperCase(Locale.ROOT);
        info.addView(Ui.text(this, position.isEmpty() ? format : format + " · " + position, 12, Ui.MUTED));
        LinearLayout.LayoutParams infoParams = Ui.weight();
        infoParams.rightMargin = Ui.dp(this, 10);
        card.addView(info, infoParams);
        Button resume = Ui.primary(this, "继续阅读", () -> open(book));
        resume.setContentDescription("继续阅读 " + book.title);
        card.addView(resume);
        list.addView(card);
        Ui.gap(list, 12);
    }

    private static String savedPosition(SharedPreferences reading, BookStore.Book book) {
        String id = book.id;
        switch (book.format) {
            case "pdf":
                int count = reading.getInt("pdf_count_" + id, 0);
                int page = reading.getInt("pdf_page_" + id, -1);
                return page >= 0 && page < count ? "第 " + (page + 1) + " / " + count + " 页" : "";
            case "epub":
                int epubChapter = reading.getInt("epub_chapter_" + id, -1);
                // EPUB 首章不一定是正文；用阅读位置序号而非章节标题或完成率。
                return epubChapter >= 0 ? "阅读位置 " + ((long) epubChapter + 1) : "";
            case "comic":
                // 漫画进度由 ComicActivity 按内容键恢复，书架不推算百分比。
                return "";
            default:
                int progress = reading.getInt("progress_" + id, -1);
                return progress >= 0 && progress <= 100 ? "进度 " + progress + "%" : "";
        }
    }

    private void addShelfEmptyState(LinearLayout list, boolean searching, boolean tagsOnly, String filter) {
        boolean allBooks = ALL_BOOKS.equals(folderId);
        boolean emptyLibrary = allBooks && !searching && !favoritesOnly;
        boolean hasFilter = favoritesOnly || tagsOnly || !allBooks;
        String title;
        String message;
        if (searching) {
            title = tagsOnly && filter.isEmpty() ? "请输入标签名" : "未找到匹配的书籍";
            message = tagsOnly && filter.isEmpty() ? "例如：#旅行" : favoritesOnly
                    ? "当前只在珍藏中搜索。" : tagsOnly ? "没有书籍带有匹配的标签。" : "试试更短的书名或标签。";
        } else if (favoritesOnly) {
            title = "还没有珍藏的书籍";
            message = "在书籍详情中勾选「珍藏」，即可在这里查看。";
        } else if (emptyLibrary) {
            title = "书库还是空的";
            message = "导入本地书籍后，它们会显示在这里。";
        } else {
            title = folderId == null ? "还没有来源文件夹" : "此文件夹为空";
            message = folderId == null ? "可通过导入菜单扫描文件夹。" : "当前层没有直接归档的书籍或子文件夹。";
        }
        LinearLayout panel = Ui.column(this);
        panel.setBackground(Ui.outline(this, Color.WHITE, 14));
        Ui.pad(panel, 16);
        panel.addView(Ui.title(this, title, 17));
        Ui.gap(panel, 4);
        panel.addView(Ui.text(this, message, 14, Ui.MUTED));
        Ui.gap(panel, 12);
        if (emptyLibrary) {
            panel.addView(Ui.primary(this, "导入本地书籍", this::pickFiles));
        } else {
            if (hasFilter) panel.addView(Ui.button(this, "清除筛选", this::clearFiltersKeepKeyword,
                    tagsOnly ? "清除标签、珍藏与文件夹筛选" : "清除筛选条件，保留关键词"));
            if (searching) {
                if (hasFilter) Ui.gap(panel, 8);
                panel.addView(Ui.button(this, tagsOnly ? "输入标签" : "换个关键词", this::focusSearchInput,
                        "聚焦搜索框以修改搜索词"));
            }
        }
        list.addView(panel);
    }

    /** 空态 CTA「清除筛选」：清掉珍藏/文件夹/标签查询(#tag)筛选，但保留纯文字关键词与搜索框输入。 */
    private void clearFiltersKeepKeyword() {
        if (favoritesOnly) getSharedPreferences("shelf", 0).edit().putBoolean("favoritesOnly", false).apply();
        favoritesOnly = false;
        folderId = ALL_BOOKS;
        folderLabel = "全部书籍";
        ancestorIds.clear();
        ancestorIds.add("");
        ancestorLabels.clear();
        ancestorLabels.add(ROOT_LABEL);
        // 标签查询以 #tag 形式存在 query 中，属于筛选需清掉；纯文字关键词必须原样保留。
        if (searchKey(query).trim().startsWith("#")) query = "";
        bookPage = 0;
        clearForScopeChange();
        show();
    }

    /** 空态 CTA「换个关键词」：聚焦并全选搜索词，用户可直接输入新词覆盖。 */
    private void focusSearchInput() {
        if (searchInput != null) {
            searchInput.requestFocus();
            searchInput.selectAll();
            android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void addFolderRow(LinearLayout list, BookStore.Folder folder) {
        LinearLayout row = Ui.row(this);
        Ui.pad(row, 14);
        row.setBackground(Ui.shape(Color.WHITE, 14, this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(this, 10);
        list.addView(row, params);
        TextView icon = Ui.text(this, "▣", 25, Ui.GREEN);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 52)));
        LinearLayout info = Ui.column(this);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = Ui.title(this, folder.name, 16);
        name.setMaxLines(2);
        info.addView(name);
        info.addView(Ui.text(this, folder.bookCount + " 本（当前层）", 12, Ui.MUTED));
        row.addView(Ui.text(this, "›", 22, Ui.MUTED));
        row.setContentDescription("打开文件夹 " + folder.name + "，" + folder.bookCount + " 本，当前层");
        row.setOnClickListener(v -> enterFolder(folder));
        if(!BookStore.UNCLASSIFIED.equals(folder.id))row.setOnLongClickListener(v->{
            new AlertDialog.Builder(this).setTitle(folder.name).setItems(new String[]{"重命名","删除文件夹"},(d,which)->{
                Intent intent=new Intent(this,OrganizeBooksActivity.class).putExtra("action",which==0?"rename":"delete")
                        .putExtra("folderId",folder.id).putExtra("folderName",folder.name);
                startActivityForResult(intent,REQUEST_ORGANIZE);
            }).show();return true;
        });
    }

    private static boolean hasMatchingTag(Map<String, List<String>> tags, String id, String filter) {
        for (String tag : tags.getOrDefault(id, Collections.emptyList())) {
            if (searchKey(tag).contains(filter)) return true;
        }
        return false;
    }

    private static String searchKey(String text) { return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }

    /** 书卡标签渲染为可点 pill：点标签按该标签筛选（R-A-11）。intent 只带 EXTRA_TAG，
     *  旧筛选由接收端 applyTagQuery 归零；不 finish 书库，返回键可回到原书库。 */
    private void addTagPills(LinearLayout container, List<String> tags, int color, int textSize) {
        if (tags == null || tags.isEmpty()) return;
        LinearLayout row = Ui.row(this);
        for (String tag : tags) {
            final String target = tag;
            Button pill = Ui.button(this, "#" + target, () -> {
                Intent i = new Intent(this, MainActivity.class);
                i.putExtra(EXTRA_TAG, target);
                startActivity(i);
            }, "按标签" + target + "筛选");
            pill.setTextColor(color);
            pill.setTextSize(textSize);
            // 视觉轻量：去 Ui.button 默认 44dp 最小尺寸，收紧 padding。
            pill.setMinHeight(0); pill.setMinimumHeight(0);
            pill.setMinWidth(0); pill.setMinimumWidth(0);
            pill.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, Ui.dp(this, 6), 0);
            pill.setLayoutParams(lp);
            row.addView(pill);
        }
        container.addView(row);
    }

    private void addBookCard(LinearLayout list, BookStore.Book book, List<String> tags) {
        LinearLayout card = Ui.row(this);
        Ui.pad(card, 14);
        card.setBackground(Ui.shape(Color.WHITE, 16, this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(this, 12);
        list.addView(card, params);
        if(organizing){
            CheckBox check=new CheckBox(this);check.setChecked(selectedBookIds.contains(book.id));
            check.setContentDescription("选择书籍 "+book.title);
            check.setOnCheckedChangeListener((button,checked)->setBookSelected(book.id,checked));
            card.addView(check);visibleChecks.put(book.id,check);
        }
        TextView cover = Ui.text(this, book.title.substring(0, Math.min(8, book.title.length())), 17, Color.WHITE);
        cover.setGravity(Gravity.CENTER);
        Ui.pad(cover, 10);
        cover.setBackground(Ui.shape(covers[Math.floorMod(book.id.hashCode(), covers.length)], 5, this));
        card.addView(cover, new LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 98)));
        LinearLayout info = Ui.column(this);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, -2, 1);
        infoParams.leftMargin = Ui.dp(this, 16);
        card.addView(info, infoParams);
        LinearLayout titleLine = Ui.row(this);
        TextView title = Ui.title(this, book.title, 17);
        title.setMaxLines(2);
        titleLine.addView(title, Ui.weight());
        BookMetadataStore.Card meta = cardMetadata.get(book.id);
        if (meta != null && meta.favorite) {
            // 星标是装饰色，刻意不随主题变化。
            TextView star = Ui.text(this, "★", 17, 0xFFBA7517);
            star.setContentDescription("已珍藏");
            titleLine.addView(star);
        }
        info.addView(titleLine);
        Ui.gap(info, 8);
        SharedPreferences prefs = getSharedPreferences("reader", 0);
        int progress = prefs.getInt("progress_" + book.id, 0);
        if (book.format.equals("pdf")) {
            int count = prefs.getInt("pdf_count_" + book.id, 0);
            progress = count > 0 ? Math.round(100f * (prefs.getInt("pdf_page_" + book.id, 0) + 1) / count) : 0;
        }
        info.addView(Ui.text(this, book.format.toUpperCase(Locale.ROOT) + " · "
                + (progress == 0 ? "尚未开始" : "已读 " + progress + "%"), 12, Ui.MUTED));
        int pending = ChapterUpdateChecker.pendingCount(this, book.id);
        if (pending > 0) info.addView(Ui.text(this, "● 有 " + pending + " 章更新", 12, Ui.GREEN));
        Ui.gap(info, 8);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgress(progress);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
        info.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 4)));
        card.setContentDescription("打开 " + book.title);
        card.setOnClickListener(v -> {if(organizing)toggleBook(book.id);else open(book);});
        Button delete=Ui.button(this,"删除",()->openDelete(book));
        delete.setContentDescription("删除选项 "+book.title);
        addTagPills(info, tags, Ui.GREEN, 12);
        if (!organizing) {
            LinearLayout actions = Ui.row(this);
            Button details = Ui.button(this, "详情", () -> startActivity(new Intent(this, BookDetailActivity.class).putExtra("bookId", book.id)));
            details.setContentDescription("书籍详情 " + book.title);
            actions.addView(details, Ui.weight()); actions.addView(delete, Ui.weight()); info.addView(actions);
        }
        card.setOnLongClickListener(v->{if(organizing)toggleBook(book.id);else showBookMenu(book);return true;});
    }

    private void addBookTile(LinearLayout row,BookStore.Book book,List<String> tags){
        LinearLayout tile=Ui.column(this);Ui.pad(tile,8);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.bottomMargin=Ui.dp(this,20);row.addView(tile,lp);
        LinearLayout cover=Ui.column(this);cover.setGravity(Gravity.CENTER);Ui.pad(cover,14);
        int color=covers[Math.floorMod(book.id.hashCode(),covers.length)];cover.setBackground(Ui.shape(color,5,this));cover.setElevation(Ui.dp(this,3));
        TextView mark=Ui.text(this,"OPENLEAF  /  青简",9,0xBBFFFFFF);mark.setLetterSpacing(.12f);cover.addView(mark);Ui.gap(cover,22);
        TextView name=Ui.title(this,book.title,18);name.setTextColor(Color.WHITE);name.setGravity(Gravity.CENTER);name.setMaxLines(4);name.setEllipsize(android.text.TextUtils.TruncateAt.END);cover.addView(name,new LinearLayout.LayoutParams(-1,0,1));
        BookMetadataStore.Card metadata=cardMetadata.get(book.id);
        TextView format=Ui.text(this,(metadata!=null&&metadata.favorite?"★ ":"")+book.format.toUpperCase(Locale.ROOT),10,0xBBFFFFFF);format.setGravity(Gravity.CENTER);cover.addView(format);
        android.widget.FrameLayout coverFrame=new android.widget.FrameLayout(this);coverFrame.setContentDescription("打开 "+book.title);coverFrame.addView(cover,new android.widget.FrameLayout.LayoutParams(-1,-1));
        if(metadata!=null&&!metadata.coverName.isEmpty()){
            android.widget.ImageView image=new android.widget.ImageView(this);image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);image.setContentDescription(book.title+" 封面");coverFrame.addView(image,new android.widget.FrameLayout.LayoutParams(-1,-1));
            long request=libraryRequest;worker.execute(()->{try{android.graphics.Bitmap bitmap=CoverFiles.preview(this,metadata.coverName,420);runOnUiThread(()->{if(!isDestroyed()&&request==libraryRequest)image.setImageBitmap(bitmap);});}catch(Exception ignored){}});
        }
        boolean wall=hideBookTitles&&!organizing&&gridMode; // 封面墙：只留封面，不改变长按/点击/批量选择语义
        cover.setContentDescription("打开 "+book.title);
        tile.addView(coverFrame,new LinearLayout.LayoutParams(-1,Ui.dp(this,wall?240:184)));coverFrame.setOnClickListener(v->{if(organizing)toggleBook(book.id);else open(book);});
        if(!wall){
            Ui.gap(tile,10);
            TextView title=Ui.title(this,book.title,14);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.END);tile.addView(title);
            if(metadata!=null&&!metadata.author.isEmpty()){TextView author=Ui.text(this,metadata.author,11,Ui.MUTED);author.setSingleLine();author.setEllipsize(android.text.TextUtils.TruncateAt.END);tile.addView(author);}
            SharedPreferences reading=getSharedPreferences("reader",0);int progress=reading.getInt("progress_"+book.id,0);
            if(book.format.equals("pdf")){int count=reading.getInt("pdf_count_"+book.id,0);progress=count>0?Math.round(100f*(reading.getInt("pdf_page_"+book.id,0)+1)/count):0;}
            tile.addView(Ui.text(this,progress>0?"已读 "+progress+"%":"尚未开始阅读",11,Ui.MUTED));
            int pending = ChapterUpdateChecker.pendingCount(this, book.id);
            if(pending>0) tile.addView(Ui.text(this,"● 有 "+pending+" 章更新",11,Ui.GREEN));
            addTagPills(tile, tags, Ui.MUTED, 11);
        }
        if(organizing){CheckBox check=new CheckBox(this);check.setText("选择");check.setChecked(selectedBookIds.contains(book.id));check.setContentDescription("选择书籍 "+book.title);check.setOnCheckedChangeListener((v,checked)->setBookSelected(book.id,checked));tile.addView(check);visibleChecks.put(book.id,check);}
        else if(!wall){Button details=Ui.button(this,"详情  ·  书评",()->startActivity(new Intent(this,BookDetailActivity.class).putExtra("bookId",book.id)));details.setContentDescription("书籍详情 "+book.title);tile.addView(details,new LinearLayout.LayoutParams(-1,Ui.dp(this,40)));}
        tile.setContentDescription("打开 "+book.title);tile.setOnClickListener(v->{if(organizing)toggleBook(book.id);else open(book);});cover.setOnClickListener(v->{if(organizing)toggleBook(book.id);else open(book);});
        tile.setOnLongClickListener(v->{showBookMenu(book);return true;});
    }

    /** 书卡长按菜单：在线书多一项「检查更新」，兑现书源页那句「可在书架中检查更新」。 */
    private void showBookMenu(BookStore.Book book){
        boolean online = new OnlineBookStore(this).findByBookId(book.id) != null;
        List<String> items = new ArrayList<>();
        items.add("阅读");
        items.add("详情与书评");
        if (online) items.add("检查更新");
        items.add("删除");
        new AlertDialog.Builder(this).setTitle(book.title)
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    String action = items.get(which);
                    if ("阅读".equals(action)) open(book);
                    else if ("详情与书评".equals(action))
                        startActivity(new Intent(this, BookDetailActivity.class).putExtra("bookId", book.id));
                    else if ("检查更新".equals(action)) checkBookUpdates(book);
                    else openDelete(book);
                }).show();
    }

    /** 真抓一次远端目录，发现新章就给角标并提供「重新解析」。 */
    private void checkBookUpdates(BookStore.Book book){
        worker.execute(() -> {
            OnlineBookStore.OnlineBook binding = new OnlineBookStore(this).findByBookId(book.id);
            final ChapterUpdateChecker.Result result = binding == null
                    ? new ChapterUpdateChecker.Result(book.id, book.title,
                            ChapterUpdateChecker.Status.NO_META, "未找到在线书绑定", 0, 0, 0)
                    : ChapterUpdateChecker.check(this, binding, new android.os.CancellationSignal());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, ChapterUpdateChecker.describe(result), Toast.LENGTH_LONG).show();
                if (!result.canReparse()) return;
                String message = result.status == ChapterUpdateChecker.Status.RULE_CHANGED
                        ? "书源规则已变化，需要重新抓目录；进度与已缓存章节会保留。"
                        : "远端目录新增 " + result.added + " 章；重新解析会保留进度与缓存。";
                new AlertDialog.Builder(this).setTitle("在线书有更新")
                        .setMessage(message)
                        .setPositiveButton("重新解析", (d, w) -> reparseOnlineBook(book))
                        .setNegativeButton("稍后", null).show();
            });
        });
    }

    private void reparseOnlineBook(BookStore.Book book){
        worker.execute(() -> {
            OnlineBookReparser.Result result = new OnlineBookReparser(this).reparse(book.id);
            if (result.success) ChapterUpdateChecker.clearPending(this, book.id);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, result.success
                        ? "已更新目录，共 " + result.chapterCount + " 章" : result.message,
                        Toast.LENGTH_LONG).show();
                if (result.success) recreate();
            });
        });
    }

    private void openDelete(BookStore.Book book){
        startActivity(new Intent(this,DeleteBookActivity.class).putExtra("bookId",book.id));
    }

    private void open(BookStore.Book book) {
        try {
            // 阶段 2-B：漫画走 ComicActivity（与 BookDetailActivity 路由对称），其余格式不变
            Class<?> reader = "comic".equals(book.format) ? ComicActivity.class
                    : book.format.equals("pdf") ? PdfActivity.class
                    : book.format.equals("epub") ? EpubActivity.class : ReaderActivity.class;
            Intent intent = new Intent(this, reader);
            intent.putExtra("bookId", book.id);
            intent.putExtra("format", book.format);
            intent.putExtra("title", book.title);
            getSharedPreferences("reader",0).edit().putLong("opened_"+book.id,System.currentTimeMillis()).apply();
            startActivity(intent);
        } catch (Exception e) { error("无法打开书籍", e); }
    }

    // ---------- 拖拽批量导入（缺口 3 / Q4）----------

    private boolean onDrag(View view, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true; // 不依赖文件管理器是否填 ClipDescription
            case DragEvent.ACTION_DRAG_ENTERED:
                if (root != null) root.setAlpha(0.85f);
                if (!dragHintShown) {
                    dragHintShown = true;
                    Toast.makeText(this, "松开即可批量导入（最多 20 个文件）", Toast.LENGTH_SHORT).show();
                }
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                if (root != null) root.setAlpha(1f);
                dragHintShown = false;
                return true;
            case DragEvent.ACTION_DROP:
                if (root != null) root.setAlpha(1f);
                dragHintShown = false;
                handleDrop(event.getClipData());
                return true;
            default:
                return true;
        }
    }

    private void handleDrop(android.content.ClipData clip) {
        if (clip == null || dropImportRunning) return;
        if (PickerImport.active != null) {
            Toast.makeText(this, "已有导入任务进行中，请稍后再拖入", Toast.LENGTH_SHORT).show();
            return;
        }
        final long gen = ++dropGeneration; // 每次投递世代 +1；旧 Activity 的 worker 回调据此丢弃
        // 不预截断：全部名字交给 DropBatch.split 统一计数 overflow，否则 split.overflow 恒 0
        int count = clip.getItemCount();
        List<Uri> uris = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> mimes = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri == null) continue;
            String name = displayNameOf(uri);
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException | IllegalArgumentException expired) {
                failures.add(DropBatch.reasonPermission(name));
                continue;
            }
            uris.add(uri); names.add(name); mimes.add(clip.getDescription().getMimeType(i));
        }
        if (uris.isEmpty() && failures.isEmpty()) return;
        // 纯 JVM 一次扫描分流；不在此类重写分类规则
        DropBatch.Batch batch = DropBatch.split(names, mimes);
        for (DropBatch.Rejected rejected : batch.rejected) failures.add(rejected.reason);
        for (String duplicate : batch.duplicates) failures.add(duplicate + "：同名文件已跳过");
        if (batch.overflow > 0) failures.add("另有 " + batch.overflow + " 个文件超出单次 20 个上限，未处理");

        Map<String, Uri> uriByName = new HashMap<>();
        for (int i = 0; i < names.size(); i++) uriByName.put(names.get(i), uris.get(i));
        final List<Uri> comicsToOpen = new ArrayList<>();
        for (String comicName : batch.comics) {
            Uri comicUri = uriByName.get(comicName);
            if (comicUri != null && zipSignatureOk(comicUri)) comicsToOpen.add(comicUri);
            else failures.add(DropBatch.reasonInvalidZip(comicName));
        }
        if (batch.books.isEmpty()) {
            showDropResult(0, 0, failures.size(), comicsToOpen, failures, false);
        } else {
            startDropImport(batch.books, uriByName, comicsToOpen, failures, gen);
        }
    }

    /** BOOK 项走既有 job 体系（ImportUseCase + JobRepository），结果进任务中心与结果 Dialog。 */
    private void startDropImport(List<String> books, Map<String, Uri> uriByName,
                                 final List<Uri> comicsToOpen, final List<String> failures,
                                 final long gen) {
        dropImportRunning = true;
        final org.openleaf.reader.core.ContentContracts.JobRepository jobs = ServiceRegistry.of(this).jobs();
        final String jobId = "drag-" + System.currentTimeMillis();
        final List<ImportUseCase.Candidate> candidates = new ArrayList<>();
        for (String name : books) {
            Uri uri = uriByName.get(name);
            if (uri != null) candidates.add(new ImportUseCase.Candidate(uri.toString(), name, "", 0));
        }
        jobs.create(jobId, "import", "drop", ImportUseCase.toJobItems(candidates));
        jobs.updateState(jobId, org.openleaf.reader.core.ContentContracts.JobState.RUNNING, 0, 0, 0, 0);
        worker.execute(() -> {
            try {
                ImportUseCase.Summary summary = ImportUseCase.run(candidates, jobs, jobId, candidate -> {
                    try {
                        Uri uri = Uri.parse(candidate.assetId);
                        BookStore.ImportResult result = new BookStore(getApplicationContext())
                                .importUri(uri, new android.os.CancellationSignal());
                        String bookId = result.book == null ? candidate.assetId : result.book.id;
                        return result.duplicate
                                ? ImportUseCase.ItemResult.duplicate(candidate.assetId, bookId)
                                : ImportUseCase.ItemResult.added(candidate.assetId, bookId);
                    } catch (Exception error) {
                        // §13.4：原始 getMessage() 可能含本地路径，只给稳定中文短串
                        return ImportUseCase.ItemResult.failed(candidate.assetId,
                                safeError(error) + detailOf(error));
                    }
                }, null);
                final Bundle result = makeDropBundle(summary.added, summary.duplicates,
                        summary.failed + failures.size(), comicsToOpen, failures, summary.paused);
                retainedDropResult = result;
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || gen != dropGeneration) return;
                    dropImportRunning = false;
                    pendingDropResult = result;
                    presentDropResult();
                });
            } catch (Exception error) {
                failures.add("导入失败：" + safeError(error) + detailOf(error));
                final Bundle result = makeDropBundle(0, 0, failures.size(), comicsToOpen, failures, false);
                retainedDropResult = result;
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || gen != dropGeneration) return;
                    dropImportRunning = false;
                    pendingDropResult = result;
                    presentDropResult();
                });
            }
        });
    }

    /** §13.4：已知异常类型 → 稳定中文短串，绝不把原始 getMessage() 暴露给用户。 */
    private static String safeError(Exception error) {
        if (error instanceof SecurityException) return "授权失效";
        if (error instanceof java.io.FileNotFoundException) return "文件不可用";
        if (error instanceof IllegalArgumentException || error instanceof UnsupportedOperationException)
            return "格式不支持";
        if (error instanceof java.io.IOException) return "读取失败";
        return "文件不可用";
    }

    /**
     * 失败原因的可选短注解。{@link #safeError} 的「绝不暴露原始 message」原则不变，这里只放行
     * 明显是产品自己写的短文案（例如「导入文件超过 512 MiB 上限」），其余一律空串：
     * 过长、带路径分隔符、像类名或像栈帧，都视为可能泄漏本地路径/实现细节。
     */
    private static String detailOf(Exception error) {
        String message = error.getMessage();
        if (message == null) return "";
        String text = message.trim();
        if (text.isEmpty() || text.length() > 80) return "";
        if (text.indexOf('/') >= 0 || text.indexOf('\\') >= 0 || text.contains("java.")
                || text.contains("Exception") || text.contains("at ")) return "";
        return "（" + text + "）";
    }

    /** 结果落 Bundle：旋转后由 onSaveInstanceState/onCreate 还原，不依赖旧 Activity 引用。 */
    private static Bundle makeDropBundle(int added, int duplicates, int failed,
                                         List<Uri> comicsToOpen, List<String> failures, boolean paused) {
        Bundle out = new Bundle();
        out.putInt("added", added);
        out.putInt("duplicates", duplicates);
        out.putInt("failed", failed);
        out.putBoolean("paused", paused);
        out.putStringArray("failures", failures.toArray(new String[0]));
        ArrayList<String> comicUris = new ArrayList<String>();
        for (Uri comic : comicsToOpen) comicUris.add(comic.toString());
        out.putStringArrayList("comics", comicUris);
        return out;
    }

    /** UI 线程直出结果（无 books 分支）：先落 Bundle，再展示；保留字段供旋转恢复。 */
    private void showDropResult(int added, int duplicates, int failed, List<Uri> comicsToOpen,
                                List<String> failures, boolean paused) {
        pendingDropResult = makeDropBundle(added, duplicates, failed, comicsToOpen, failures, paused);
        presentDropResult();
    }

    /** §12 逐字三态 + 漫画分流 + 可复制失败明细；漫画只给「打开」按钮，不入库。 */
    private void presentDropResult() {
        if (pendingDropResult == null || isFinishing() || isDestroyed()) return;
        final Bundle result = pendingDropResult;
        int added = result.getInt("added", 0), duplicates = result.getInt("duplicates", 0);
        int failed = result.getInt("failed", 0);
        boolean paused = result.getBoolean("paused", false);
        String[] failures = result.getStringArray("failures");
        ArrayList<String> comicStrings = result.getStringArrayList("comics");

        StringBuilder message = new StringBuilder();
        if (paused) message.append("导入已暂停：新增 ").append(added).append(" 本 · 重复 ")
                .append(duplicates).append(" 本，可在任务中心查看失败明细");
        else message.append("导入完成：新增 ").append(added).append(" 本 · 重复 ")
                .append(duplicates).append(" 本 · 失败 ").append(failed).append(" 项");
        if (comicStrings != null && !comicStrings.isEmpty()) message.append("\n")
                .append(comicStrings.size()).append(" 个漫画文件未入库，可在下方逐个打开");
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 20);
        content.addView(Ui.text(this, message.toString(), 14, Ui.INK));
        if (failures != null && failures.length > 0) {
            TextView detail = Ui.text(this, "失败明细：\n" + String.join("\n", failures), 12, Ui.MUTED);
            detail.setTextIsSelectable(true); // 可复制
            content.addView(detail);
        }
        if (comicStrings != null) for (final String uriString : comicStrings) {
            final Uri comicUri = Uri.parse(uriString);
            String name = displayNameOf(comicUri);
            Button open = Ui.button(this, "打开 " + name, () -> startActivity(
                    new Intent(this, ComicActivity.class).setData(comicUri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
            open.setContentDescription("用漫画阅读器打开 " + name);
            content.addView(open);
        }
        new AlertDialog.Builder(this).setTitle("拖拽导入结果").setView(content)
                .setPositiveButton("知道了", null).show();
    }

    private String displayNameOf(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) { /* 回退到 URI 末段 */ }
        String last = uri.getLastPathSegment();
        return last == null ? "未命名文件" : last;
    }

    /** CBZ/ZIP 三签名校验，与 core/DropBatch.isZipSignature 同口径。 */
    private boolean zipSignatureOk(Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            byte[] head = new byte[4];
            int read = in.read(head);
            return DropBatch.isZipSignature(read == 4 ? head : java.util.Arrays.copyOf(head, Math.max(0, read)));
        } catch (Exception invalid) { return false; }
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.setType("*/*");
        // .olbook may be reported as generic binary or ZIP by document providers.
        // The importer validates the filename/format independently.
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(intent, 10); }
        catch (ActivityNotFoundException e) { error("无法打开文件选择器", e); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request==REQUEST_ORGANIZE){
            if(result==RESULT_OK){selectedBookIds.clear();bookPage=0;show();}
            return;
        }
        if (request != 10 || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < Math.min(20, data.getClipData().getItemCount()); i++)
                uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        for(Uri uri:uris){
            try{
                getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }catch(SecurityException|IllegalArgumentException unavailable){/* The importer checks durable permission and reports the affected file. */}
        }
        if (uris.isEmpty() || PickerImport.active != null) return;
        PickerImport.active = new PickerImport(this, uris);
        attachPickerImport();
    }

    private void attachPickerImport() {
        PickerImport task = PickerImport.active;
        if (task == null) return;
        task.listener = importListener;
        renderPickerImport();
    }

    private void closeImportDialog() {
        if (importDialog != null) importDialog.dismiss();
        importDialog = null;
        importStatus = null;
        importBar = null;
    }

    private void renderPickerImport() {
        PickerImport task = PickerImport.active;
        if (task == null || isDestroyed() || isFinishing()) return;
        if (task.done) {
            task.listener = null;
            PickerImport.active = null;
            closeImportDialog();
            bookPage = 0;
            show();
            String summary = (task.stopping ? "已停止 · " : "") + "已添加 " + task.added + " 本，跳过重复 "
                    + task.duplicates + " 本，失败 " + task.failed + " 本";
            new AlertDialog.Builder(this).setTitle("导入结果")
                    .setMessage(summary + (task.errors.isEmpty() ? "" : "\n\n" + task.errors))
                    .setPositiveButton("知道了", null).show();
            return;
        }
        if (importDialog == null) {
            LinearLayout content = Ui.column(this);
            Ui.pad(content, 20);
            importStatus = Ui.text(this, "", 14, Ui.INK);
            importStatus.setMaxLines(4);
            importStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
            content.addView(importStatus);
            Ui.gap(content, 12);
            importBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            importBar.setMax(100);
            content.addView(importBar);
            importDialog = new AlertDialog.Builder(this).setTitle("导入到书架").setView(content)
                    .setCancelable(false).setNegativeButton("停止导入", null).create();
            importDialog.show();
            importDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> task.stop());
        }
        String state = "准备书库…";
        boolean determinate = !task.stopping && task.phase == BookStore.ImportPhase.COPY && task.size > 0;
        if (task.stopping) state = "正在停止…已经导入的书籍会保留。";
        else if (task.item > 0) {
            state = "第 " + task.item + " / " + task.total + " 个文件";
            if (!task.name.isEmpty()) state += "\n" + task.name;
            if (task.phase == BookStore.ImportPhase.COPY) {
                state += "\n正在读取";
                if (task.bytes > 0 || determinate) state += " · " + android.text.format.Formatter.formatFileSize(this, task.bytes);
                if (determinate) state += " / " + android.text.format.Formatter.formatFileSize(this, task.size);
            } else if (task.phase == BookStore.ImportPhase.VALIDATE) state += "\n正在校验文件…";
            else if (task.phase == BookStore.ImportPhase.SAVE) state += "\n正在更新书库…";
        }
        importStatus.setText(state);
        importBar.setIndeterminate(!determinate);
        if (determinate) importBar.setProgress((int) Math.min(100, task.bytes * 100 / task.size));
        importDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!task.stopping);
    }

    private void network() {
        LinearLayout body = content();
        Button onlineSearch=Ui.primary(this, "在线搜书 · Legado 书源", () ->
                startActivity(new Intent(this, LegadoActivity.class)));
        onlineSearch.setContentDescription("在线搜书");
        body.addView(onlineSearch);
        Ui.gap(body, 8);
        body.addView(Ui.button(this, "OPDS 目录 / 漫画源 / 音乐源", () ->
                startActivity(new Intent(this, CatalogActivity.class)),"浏览 OPDS 目录与媒体源"));
        body.addView(Ui.button(this, "本地文件 / 音频播放", () ->
                startActivity(new Intent(this, LocalFilesActivity.class)),"浏览本地文件与音频播放"));
        Ui.gap(body, 12);
        body.addView(Ui.text(this, "READ THE WEB", 12, Ui.GREEN));
        Ui.gap(body, 12);
        body.addView(Ui.title(this, "沿着好奇心，继续读。", 25));
        Ui.gap(body, 12);
        body.addView(Ui.text(this, "输入 HTTPS 网址阅读静态网页。首版关闭 JavaScript，部分动态网站和登录页面无法使用。", 15, Ui.MUTED));
        Ui.gap(body, 20);
        EditText url = new EditText(this);
        url.setHint("https://example.org/article");
        url.setSingleLine(true);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setContentDescription("输入 HTTPS 网页地址");
        body.addView(url);
        body.addView(Ui.button(this, "打开网页", () -> openUrl(url.getText().toString()),"打开输入的网页"));
        Ui.gap(body, 24);
        body.addView(Ui.title(this, "网页收藏", 18));
        Ui.gap(body, 10);
        try {
            org.json.JSONArray bookmarks = new org.json.JSONArray(getSharedPreferences("web", 0)
                    .getString("bookmarks", "[]"));
            if (bookmarks.length() == 0) body.addView(Ui.text(this, "打开网页后，点“收藏”保存在这里。", 14, Ui.MUTED));
            for (int i = 0; i < bookmarks.length(); i++) {
                String address = bookmarks.getString(i);
                Button item = Ui.button(this, address, () -> openUrl(address),"打开网页收藏 "+address);
                item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                item.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this).setTitle("移除此收藏？").setMessage(address)
                            .setNegativeButton("取消", null).setPositiveButton("移除", (d, w) -> {
                                try {
                                    org.json.JSONArray fresh = new org.json.JSONArray(getSharedPreferences("web", 0)
                                            .getString("bookmarks", "[]"));
                                    org.json.JSONArray keep = new org.json.JSONArray();
                                    for (int j = 0; j < fresh.length(); j++)
                                        if (!address.equals(fresh.getString(j))) keep.put(fresh.getString(j));
                                    getSharedPreferences("web", 0).edit().putString("bookmarks", keep.toString()).apply();
                                    show();
                                } catch (Exception e) { error("移除失败", e); }
                            }).show();
                    return true;
                });
                body.addView(item);
            }
        } catch (Exception e) { error("收藏读取失败", e); }
    }

    private void openUrl(String raw) {
        String value = raw.trim();
        if (!value.contains("://")) value = "https://" + value;
        Uri uri = Uri.parse(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            Toast.makeText(this, "请输入有效的 HTTPS 网址", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, WebActivity.class).putExtra("url", value));
    }

    private void about() {
        LinearLayout body = content();
        body.addView(Ui.text(this, "OPEN SOURCE · OFFLINE FIRST", 12, Ui.GREEN));
        Ui.gap(body, 16);
        body.addView(Ui.title(this, "青简 OpenLeaf", 30));
        Ui.gap(body, 6);
        body.addView(Ui.text(this, "0.42.0  /  开发版", 14, Ui.MUTED));
        Ui.gap(body, 16);
        body.addView(Ui.button(this, "第三方许可与声明", this::showThirdPartyLicenses,"查看第三方许可与声明"));
        body.addView(Ui.button(this, "书库备份与恢复", () -> startActivity(new Intent(this, BackupActivity.class)),"备份与恢复书库"));
        Ui.gap(body, 24);
        body.addView(Ui.text(this, "让每一本书，都有安静的一页。", 20, Ui.GREEN));
        Ui.gap(body, 24);
        body.addView(Ui.text(this,
                "本地优先\n普通书籍通过系统授权直接读取原文件。阅读进度、标签和收藏保存在本机应用目录。"
                + "\n\n来源归档\n扫描文件夹导入的书按原目录层级浏览；单文件导入在未分类。重新扫描原目录，可为已导入书籍补充归档。全局搜索和全部书籍包含所有藏书；详情勾选「珍藏」后可在本页按「★ 珍藏」筛选。"
                + "\n\n格式支持\nTXT / MD：UTF-8、GB18030、带 BOM 的 UTF-16，支持章节目录与书内查找。"
                + "\nEPUB：不带 DRM；纯文字与图文两种模式，可显示书内嵌图片、表格和基础样式，插图可放大。"
                + "图文模式可切换「原书样式」（仅套用原书 CSS 中的排版声明，忽略颜色与脚本），阅读位置用 CFI 结构锚点记录，切换模式或样式后仍能回到相近位置。不保证固定版式。"
                + "\nPDF：逐页查看、页码跳转和进度保存；暂不支持密码文件、搜索和批注。"
                + "\n\n阅读与统计\n文本、漫画、听书时长分列统计，剩余时间标注为估算；同一计时段用稳定事件键去重，避免重复计时。"
                + "\n\n备份与迁移\n「关于 → 书库备份与恢复」导出带版本清单的 .zip：逐项 SHA-256 校验，恢复前完整预检，损坏即中止不写入；相同内容的书按哈希去重，进度、标注、想法自动迁移。SAF 授权、任务记录、缓存与设备字段不导出。"
                + "\n\n在线发现\n可自行导入 Legado 书源、OPDS 目录及音乐 / 漫画 JSON 源；仅静态 HTTPS，不执行脚本、不支持登录或 DRM，不内置第三方内容站。网页阅读关闭脚本、文件访问及明文 HTTP。"
                + "\n书源规则变化时可一键重新解析目录（保留进度与已缓存章节）；下载支持可验证时的断点续传，标识不符则明确重下；任务中心可重试失败项。"
                + "\n仅在主动打开网址或书源时联网。"
                + "\n\n无障碍\n支持系统大字体；音量键翻页仅在阅读内容聚焦时生效，控件聚焦时交给系统。"
                + "\n\n开源许可\nMIT License。完整源代码、构建方式与已知限制见项目 README。", 15, Ui.MUTED));
    }

    /** T03：关于页三方许可入口，复用已有 AlertDialog/ScrollView，不新增依赖。 */
    private void showThirdPartyLicenses() {
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));
        box.addView(Ui.text(this,
                "自 0.14.0 起，应用打包以下第三方组件；其余字体、书籍、统计或广告 SDK 仍不打包。"
                + "\n\nlibmobi（MOBI / PRC / AZW / AZW3 解析）"
                + "\n许可证：LGPL-3.0-or-later；形态：上游 C 源码经 NDK 编成独立 so，动态链接加载。"
                + "\n许可证原文与源码/重建/替换说明见 THIRD_PARTY_LICENSES/libmobi-LGPL-3.0.txt、"
                + "THIRD_PARTY_LICENSES/libmobi-SOURCE-OFFER.md。"
                + "\nDRM 边界：libmobi 的加密文档能力未被启用，加密文档在解析前被硬拒绝，绝不调用解密接口。"
                + "\n\nAndroidX Media3（播放器内核 / UI / 媒体会话）"
                + "\n许可证：Apache-2.0；版本：1.11.1；许可证原文见 THIRD_PARTY_LICENSES/media3-APACHE-2.0.txt。"
                + "\n\n完整声明总览见项目根目录 THIRD_PARTY_NOTICES.md。"
                + "\nOpenLeaf 原创代码仍为 MIT，与 LGPL 组件以动态链接 + 独立 so 方式隔离，MIT 主体不被传染；"
                + "Apache-2.0 组件按 Apache-2.0 条款独立声明，不混入 MIT。",
                14, Ui.MUTED));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this).setTitle("第三方许可与声明")
                .setView(scroll).setPositiveButton("知道了", null).show();
    }

    private static String exceptionMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    private void error(String title, Exception e) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this)
                    .setTitle(title).setMessage(exceptionMessage(e)).setPositiveButton("知道了", null).show();
        });
    }

    @Override protected void onDestroy() {
        libraryRequest++;
        mainHandler.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }
}
