package org.openleaf.reader;

import org.openleaf.reader.application.ServiceRegistry;
import org.openleaf.reader.core.DropBatch;
import org.openleaf.reader.core.ImportUseCase;
import org.openleaf.reader.core.ResolvedContinueReading;
import org.openleaf.reader.core.UiTokens;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
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
    /** v3 续读主卡插槽：位于搜索之后、浏览范围之前；由 renderShelf 的 worker 异步填充。 */
    private LinearLayout resumeSlot;
    /** 标题栏导入按钮：有可靠续读时降次操作，空库时升唯一主操作。 */
    private Button importButton;
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
    // LIB-1：当前页已发起的封面解码次数（含 LRU 命中），每页上限 4。
    private int coverDecodesThisPage;
    // 容错搜索索引缓存：仅在书库内容签名变化时重建，避免每次查询重新 index()
    private org.openleaf.reader.core.FuzzySearch.Index fuzzyIndex;
    private String fuzzyIndexSignature;
    private boolean organizing;
    private int sortOrder;
    private boolean favoritesOnly;
    /** v4 §6.1：格式筛选（0=全部、1=EPUB、2=PDF、3=TXT/MD、4=漫画）；与范围、排序三者分离。 */
    private int formatFilter;
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
        root=Ui.column(this); Ui.mount(this,root,UiTokens.color(UiTokens.Role.BACKGROUND));
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
        // v3 §3.10：5 项标签、路由、contentDescription、返回栈逐字不变；只修竖排。
        String[] labels={"▤  书库","◎  发现","✧  想法","▦  记录","⚙  关于"};
        String[] narrowLabels={"▤书库","◎发现","✧想法","▦记录","⚙关于"};
        String[] descs={"切换到书库","切换到在线发现","打开想法","打开阅读记录","切换到关于"};
        for(int i=0;i<labels.length;i++){
            final int selected=i>3?i-2:i;
            String text=wide()?labels[i]:narrowLabels[i]; // 窄屏去双空格，宽屏保留原文
            Button item;
            if(i==2) item=Ui.button(this,text,()->startActivity(new Intent(this,IdeasActivity.class)),descs[i]);
            else if(i==3) item=Ui.button(this,text,()->startActivity(new Intent(this,StatsActivity.class)),descs[i]);
            else item=Ui.button(this,text,()->{tab=selected;show();},descs[i]);
            // 单行：大字号倍率下 ellipsize(END) 只截视觉，读屏 desc 仍完整。
            item.setTextSize(UiTokens.textSizeSp(UiTokens.Typography.TAB_LABEL));
            item.setSingleLine(true);item.setMaxLines(1);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.setMinWidth(Ui.dp(this,48));item.setMinimumWidth(Ui.dp(this,48));
            item.setPadding(Ui.dp(this,4),Ui.dp(this,2),Ui.dp(this,4),Ui.dp(this,2));
            item.setTextColor(UiTokens.color(UiTokens.Role.PRIMARY));
            // 选中态：accent 文字 + accent-soft 14dp 圆角底；图标字形与 medium 字重并用，不靠颜色 alone。
            if(i!=2&&i!=3&&tab==selected){
                item.setBackground(Ui.shape(UiTokens.color(UiTokens.Role.ACCENT_SOFT),
                        UiTokens.radiusDp(UiTokens.Radius.TAB_SELECTED),this));
                item.setTextColor(UiTokens.color(UiTokens.Role.ACCENT));
                item.setTypeface(android.graphics.Typeface.create(
                        UiTokens.fontFamily(UiTokens.Typography.TAB_LABEL),android.graphics.Typeface.BOLD));
            }
            if(wide()){
                item.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Ui.dp(this,56));
                lp.bottomMargin=Ui.dp(this,6);navigation.addView(item,lp);
            }else{
                // weight=1 均分；setMinWidth 保证 48dp 下限，不再用 Ui.weight() 的 WRAP_CONTENT 压缩双字。
                navigation.addView(item,new LinearLayout.LayoutParams(0,Ui.dp(this,56),1));
            }
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
        return (favoritesOnly ? "favorites:" : "shelf:") + folder + "\n" + query + "\n" + formatFilter;
    }

    private String formatFilterLabel() {
        switch (formatFilter) {
            case 1: return "EPUB";
            case 2: return "PDF";
            case 3: return "TXT/MD";
            case 4: return "漫画";
            default: return "";
        }
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
        // v4 §6.1 首屏顺序：标题 → 搜索 → 单一续读 → 范围/筛选/排序 → 书籍。
        LinearLayout titleRow=Ui.row(this);
        titleRow.addView(Ui.tokenText(this,"书库",UiTokens.Typography.PAGE_TITLE,UiTokens.Role.PRIMARY),Ui.weight());
        importButton=Ui.primaryToken(this,"＋ 导入",this::showImportSources,"导入书籍");
        titleRow.addView(importButton);
        body.addView(titleRow);Ui.gap(body,UiTokens.spacingDp(UiTokens.Spacing.LG));
        // 搜索范围明确为本地书库：查询不会发往任何来源。
        LinearLayout searchRow=Ui.row(this);
        EditText search=new EditText(this);searchInput=search;search.setSingleLine(true);search.setTextSize(14);
        search.setTextColor(Ui.color(this,UiTokens.Role.PRIMARY));
        search.setHintTextColor(Ui.color(this,UiTokens.Role.SECONDARY));
        search.setHint("搜索书名、作者或格式");search.setContentDescription("搜索本地书库");
        search.setText(query);
        search.setBackground(Ui.outline(this,Ui.color(this,UiTokens.Role.SURFACE),UiTokens.radiusDp(UiTokens.Radius.INPUT)));
        search.setPadding(Ui.dp(this,16),0,Ui.dp(this,12),0);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(0,Ui.dp(this,46),1);searchParams.rightMargin=Ui.dp(this,8);searchRow.addView(search,searchParams);
        Button clearSearch=Ui.secondaryToken(this,"清除",() -> search.setText(""),"清除搜索词");
        clearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        searchRow.addView(clearSearch);
        body.addView(searchRow);Ui.gap(body,UiTokens.spacingDp(UiTokens.Spacing.LG));
        // 续读主卡插槽：worker 异步填充，有可靠记录才显示、最多 1 个。
        resumeSlot=Ui.column(this);body.addView(resumeSlot);
        // 范围：全部 / 合集（来源文件夹）。
        body.addView(Ui.tokenText(this,"范围",UiTokens.Typography.AUX,UiTokens.Role.SECONDARY));
        LinearLayout segment=Ui.row(this);
        segment.setBackground(Ui.shape(Ui.color(this,UiTokens.Role.SOFT),UiTokens.radiusDp(UiTokens.Radius.SEGMENT_CONTAINER),this));
        Ui.pad(segment,3);
        segment.addView(segmentItem("全部书籍",this::allBooks,"显示全部书籍",ALL_BOOKS.equals(folderId)&&!favoritesOnly));
        segment.addView(segmentItem("文件夹",this::rootFolder,"浏览来源文件夹",!ALL_BOOKS.equals(folderId)&&!favoritesOnly));
        body.addView(segment);Ui.gap(body,UiTokens.spacingDp(UiTokens.Spacing.MD));
        // 筛选：状态（珍藏）与格式；最近打开属于排序，不与范围/筛选混淆。
        body.addView(Ui.tokenText(this,"筛选",UiTokens.Typography.AUX,UiTokens.Role.SECONDARY));
        LinearLayout filterRow=Ui.row(this);
        filterRow.addView(segmentItem("★ 珍藏",()->{if(favoritesOnly)allBooks();else showFavorites();},"按珍藏筛选",favoritesOnly));
        Spinner format=new Spinner(this);format.setContentDescription("按格式筛选书库");
        format.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"全部格式","EPUB","PDF","TXT/MD","漫画"}));
        format.setSelection(formatFilter);
        filterRow.addView(format,Ui.weight());
        body.addView(filterRow);Ui.gap(body,UiTokens.spacingDp(UiTokens.Spacing.MD));
        // 排序与布局分别表达；图标切换保留无障碍名称与当前状态。
        LinearLayout browseRow=Ui.row(this);
        browseRow.addView(Ui.tokenText(this,"排序",UiTokens.Typography.AUX,UiTokens.Role.SECONDARY));
        Spinner sorting=new Spinner(this);sorting.setContentDescription("选择书籍排序方式");
        sorting.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"最近导入","书名","最近阅读"}));
        sorting.setSelection(sortOrder);
        browseRow.addView(sorting,Ui.weight());
        browseRow.addView(Ui.secondaryToken(this,gridMode?"☷ 列表":"▦ 网格",()->{gridMode=!gridMode;getSharedPreferences("shelf",0).edit().putBoolean("grid",gridMode).apply();show();},"切换网格与列表视图"));
        if(gridMode) browseRow.addView(Ui.secondaryToken(this,hideBookTitles?"显示书名":"封面墙",()->{
            hideBookTitles=!hideBookTitles;
            getSharedPreferences("shelf",0).edit().putBoolean("hideBookTitles",hideBookTitles).apply();
            show();
        },"切换封面墙（隐藏书名）"));
        body.addView(browseRow);Ui.gap(body,UiTokens.spacingDp(UiTokens.Spacing.MD));
        LinearLayout selectionRow=Ui.row(this);
        selectionRow.addView(Ui.secondaryToken(this,organizing?"完成":"选择",()->{organizing=!organizing;if(!organizing)selectedBookIds.clear();show();},"切换批量选择模式"));
        body.addView(selectionRow);
        if(!ALL_BOOKS.equals(folderId))breadcrumb(body);
        HorizontalScrollView toolStrip = new HorizontalScrollView(this);
        toolStrip.setHorizontalScrollBarEnabled(false);
        toolStrip.setFillViewport(true);
        LinearLayout tools=Ui.row(this);
        tools.addView(Ui.button(this,"＋ 新建文件夹",this::createFolder,"新建文件夹"));
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
        format.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                if(position==formatFilter)return;
                formatFilter=position;bookPage=0;shelfScope=libraryScope();shelfScrollY=0;
                renderShelf(list,false);
            }
        });
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

    /** v4 §6.2：导入来源面板。内置路径置顶；未安装来源不渲染灰色死入口；末项指向真实扩展边界。 */
    private void showImportSources() {
        new AlertDialog.Builder(this).setTitle("导入到书库")
                .setMessage(PickerImport.sourceNote(this))
                .setItems(PickerImport.sourceItems(this), (d, which) -> {
                    if (which == 0) pickFiles();
                    else if (which == 1) startActivity(new Intent(this, FolderImportActivity.class));
                })
                .setNeutralButton("到扩展查找其他来源", (d, w) -> showExtensionSourceBoundary())
                .show();
    }

    /** 次级链接只指向真实「扩展已安装/暂无来源」说明，P1-B 未就绪时不打开假市场。 */
    private void showExtensionSourceBoundary() {
        new AlertDialog.Builder(this).setTitle("扩展与导入来源")
                .setMessage(PickerImport.extensionBoundaryText(this))
                .setNegativeButton("知道了", null)
                .setPositiveButton("查看已安装扩展", (d, w) -> startActivity(new Intent(this, PluginActivity.class)))
                .show();
    }

    /** segment 单项：选中=ACCENT 文字 + SURFACE 底 11dp 圆角；未选中=次操作描边。 */
    private Button segmentItem(String label,Runnable click,String desc,boolean selected){
        Button b=Ui.secondaryToken(this,label,click,desc);
        b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(Ui.dp(this,8),Ui.dp(this,4),Ui.dp(this,8),Ui.dp(this,4));
        if(selected){
            b.setBackground(Ui.shape(Ui.color(this,UiTokens.Role.SURFACE),
                    UiTokens.radiusDp(UiTokens.Radius.SEGMENT_ITEM),this));
            b.setTextColor(Ui.color(this,UiTokens.Role.ACCENT));
            b.setTypeface(android.graphics.Typeface.create(
                    UiTokens.fontFamily(UiTokens.Typography.AUX),android.graphics.Typeface.BOLD));
        }
        return b;
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
        if (list == null) return;
        // LIB-STATE 加载态：列表区域局部加载，已有条目不整屏清空、仍可点击。
        // 旧提示行先摘掉，避免防抖期间重复入队叠出多行；populateShelf 会整体重绘。
        View prior = list.findViewWithTag("shelf-loading");
        if (prior != null) list.removeView(prior);
        TextView hint = Ui.text(this, list.getChildCount() == 0
                ? "正在读取书架…" : "正在更新书架…（已有书籍仍可打开）", 13,
                Ui.color(this, UiTokens.Role.SECONDARY));
        hint.setTag("shelf-loading");
        list.addView(hint);
        if (list.getChildCount() == 1) {
            visibleBooks.clear();visibleChecks.clear();
        }
    }

    private void renderShelf(LinearLayout list, boolean scrollTop) {
        if (list == null || list != shelfList || isDestroyed()) return;
        if (scrollTop) shelfScrollY = 0;
        else if (shelfPopulated && libraryScroll != null) shelfScrollY = libraryScroll.getScrollY();
        shelfPopulated = false;
        final long request = ++libraryRequest;
        // T10：debug 包渲染计时起点；release 包恒为 0，不打日志、不做额外计算。
        final long perfStart = BuildConfig.DEBUG ? SystemClock.elapsedRealtime() : 0L;
        final String rawFilter = searchKey(query).trim();
        final boolean tagsOnly = rawFilter.startsWith("#");
        final String filter = tagsOnly ? rawFilter.substring(1).trim() : rawFilter;
        final String currentFolder = folderId;
        final int sorting = sortOrder;
        final int currentFormat = formatFilter;
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
                // v4 §6.1：格式是筛选（不是范围、也不是排序），只收窄当前结果。
                if (currentFormat > 0) {
                    for (int i = books.size() - 1; i >= 0; i--) {
                        String f = books.get(i).format;
                        boolean keep;
                        switch (currentFormat) {
                            case 1: keep = "epub".equals(f); break;
                            case 2: keep = "pdf".equals(f); break;
                            case 3: keep = "txt".equals(f) || "md".equals(f); break;
                            case 4: keep = "comic".equals(f); break;
                            default: keep = true;
                        }
                        if (!keep) books.remove(i);
                    }
                }
                SharedPreferences reading = getSharedPreferences("reader", MODE_PRIVATE);
                Map<String,Long> opened=new HashMap<>();
                boolean mayContinue = !searching && !favoriteFilter && currentFormat == 0 && ALL_BOOKS.equals(currentFolder);
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
                // G2 三闸门：① opened_ 降序/id 升序取 latest（上面已选）；② 进度键严格有效；
                // ③ 文件可读（BookStore.open() 口径，含 SAF 授权与 SHA）。只校验 latest 一本。
                final BookStore.Book resumeBook = latest;
                final ResolvedContinueReading resolved = resolveContinue(reading, resumeBook, mayContinue);
                Collections.sort(books,(a,b)->{
                    int result=sorting==1?collator.compare(a.title,b.title):sorting==2
                            ?Long.compare(opened.get(b.id),opened.get(a.id)):Long.compare(b.added,a.added);
                    if(result==0)result=collator.compare(a.title,b.title);
                    return result!=0?result:a.id.compareTo(b.id);
                });
                runOnUiThread(() -> {
                    if (isDestroyed() || request != libraryRequest) return;
                    cardMetadata=metadata;
                    // 续读主卡出现在搜索之后、浏览范围之前；有可靠记录才显示，最多 1 个。
                    LinearLayout slot=resumeSlot;
                    if(slot!=null){
                        slot.removeAllViews();
                        if(resolved!=null&&!organizing&&bookPage==0) renderContinueCard(slot,resolved,resumeBook);
                    }
                    // 主操作按状态迁移：有续读→继续阅读为唯一 primary，导入降 secondary；空库→导入升 primary。
                    applyImportButtonStyle(resolved==null);
                    populateShelf(list, folders, books, searching, tagsOnly, filter, tags);
                    if (BuildConfig.DEBUG && request == libraryRequest) {
                        Log.d("PERF_SHELF", "request=" + request
                                + " ms=" + (SystemClock.elapsedRealtime() - perfStart)
                                + " books=" + books.size() + " page=" + bookPage
                                + " mode=" + (scrollTop ? "top" : "keep"));
                    }
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
                    // LIB-STATE 读取失败：区分受影响范围与原因；不伪装成空库，也不假装拥有重新授权能力。
                    String message = currentFolder != null && !ALL_BOOKS.equals(currentFolder)
                            ? "当前文件夹无法读取：可能已被移除或目录授权已失效。可返回顶层重新选择，其他书籍不受影响。"
                            : "书架读取失败，本次未改动任何书籍；请保留应用数据后重试。";
                    list.addView(Ui.text(this, message, 14, Ui.color(this, UiTokens.Role.PRIMARY)));
                    list.addView(Ui.text(this, exceptionMessage(e), 12, Ui.color(this, UiTokens.Role.SECONDARY)));
                    list.addView(Ui.button(this, "重试读取", () -> renderShelf(list, false), "重新读取当前书架"));
                    list.addView(Ui.button(this, "返回来源文件夹顶层", this::rootFolder,"回到来源文件夹根目录"));
                });
            }
        });
    }

    private void populateShelf(LinearLayout list, List<BookStore.Folder> folders,
                               List<BookStore.Book> books, boolean searching, boolean tagsOnly, String filter,
                               Map<String, List<String>> tags) {
        list.removeAllViews();
        visibleBooks.clear();visibleChecks.clear();
        coverDecodesThisPage=0; // 每页重置；4 次硬上限只统计真正调用 preview 的卡片
        String summary;
        if (tagsOnly && filter.isEmpty()) summary = "标签搜索";
        else if (searching) summary = (favoritesOnly ? "在珍藏中搜索" : tagsOnly ? "标签搜索" : "全局搜索")
                + " · 找到 " + books.size() + " 本";
        else if (favoritesOnly) summary = "★ 珍藏 · " + books.size() + " 本";
        else if (ALL_BOOKS.equals(folderId)) summary = "全部书籍 · " + books.size() + " 本";
        else summary = "本层 · " + folders.size() + " 个文件夹 / " + books.size() + " 本书";
        if (formatFilter > 0) summary += " · 已应用：" + formatFilterLabel();
        if (favoritesOnly) summary += " · 已应用：珍藏";
        TextView status = Ui.text(this, summary, 13, Ui.color(this, UiTokens.Role.SECONDARY));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        list.addView(status);
        Ui.gap(list, 12);
        int totalItems = folders.size() + books.size();
        int pages = Math.max(1, (totalItems + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        bookPage = Math.max(0, Math.min(bookPage, pages - 1));
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

    /**
     * G2 三闸门的 ②③：① opened_ 已由 renderShelf 的 latest 选出并只在 mayContinue 视图生效；
     * 这里校验进度键严格有效与文件可读（BookStore.open() 完整口径，含 SAF 授权与 SHA）。
     * 任一失败返回 null → 不显示卡，绝不显示“点开报错”的卡。只在 worker 线程对 latest 一本调用。
     */
    private ResolvedContinueReading resolveContinue(SharedPreferences reading, BookStore.Book book,
                                                    boolean mayContinue) {
        if (!mayContinue || book == null) return null;
        try {
            if (store.find(book.id) == null) return null;
            ResolvedContinueReading.Format format;
            switch (book.format) {
                case "epub": format = ResolvedContinueReading.Format.EPUB; break;
                case "pdf": format = ResolvedContinueReading.Format.PDF; break;
                case "comic": format = ResolvedContinueReading.Format.COMIC; break;
                default: format = ResolvedContinueReading.Format.TEXT; break;
            }
            int progress = reading.getInt("progress_" + book.id, -1);
            String anchor = reading.getString("anchor_" + book.id, null);
            int spine = reading.getInt("epub_chapter_" + book.id, -1);
            String cfi = reading.getString("epubCfi_" + book.id, "");
            int page = reading.getInt("pdf_page_" + book.id, -1);
            int count = reading.getInt("pdf_count_" + book.id, 0);
            int comicPage = comicPageFor(book);
            if (!ResolvedContinueReading.isReliable(format, progress, anchor, spine, cfi, page, count, comicPage))
                return null;
            // 位置文案复用 savedPosition() 的取值口径；漫画与 CFI-only 由其纯 JVM 兜底。
            String text = savedPosition(reading, book);
            if (text.isEmpty())
                text = ResolvedContinueReading.positionText(format, progress, spine, cfi, page, count, comicPage);
            if (text.isEmpty()) return null;
            // ③ 文件可读：本地 isFile/canRead；SAF 持久授权 + 内容 SHA 与 books.sha256 一致。
            try (BookAccess ignored = store.open(book)) {
                // opened() 成功即通过；位置恢复由阅读页负责，这里不手动 seek。
            }
            return new ResolvedContinueReading(book.id, book.title, format, text);
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** 漫画进度在独立 comicProgress SP 按内容指纹存；书架无 id→指纹索引，按 name_ 轻量匹配，
     *  匹配不到返回 -1（宁可不显示）。不解压归档，开销可控。 */
    private int comicPageFor(BookStore.Book book) {
        try {
            android.content.SharedPreferences comic = getSharedPreferences(ComicProgressStore.SP, 0);
            String keys = comic.getString(ComicProgressStore.KEY_KEYS, "");
            if (keys == null || keys.isEmpty()) return -1;
            for (String key : keys.split(",")) {
                if (key.isEmpty()) continue;
                String name = comic.getString(ComicProgressStore.KEY_NAME + key, "");
                if (book.title.equals(name)) return comic.getInt(ComicProgressStore.KEY_PAGE + key, -1);
            }
        } catch (Exception ignored) { /* 读不到独立 SP 视为无可靠进度 */ }
        return -1;
    }

    /** 导入按钮主次迁移：有续读→次操作描边，空库→唯一实心 PRIMARY。 */
    private void applyImportButtonStyle(boolean primary) {
        if (importButton == null) return;
        int bg = Ui.color(this, primary ? UiTokens.Role.ACCENT : UiTokens.Role.SURFACE);
        int fg = Ui.color(this, primary ? UiTokens.Role.ON_ACCENT : UiTokens.Role.PRIMARY);
        importButton.setTextColor(fg);
        android.graphics.drawable.GradientDrawable shape =
                Ui.shape(bg, UiTokens.radiusDp(UiTokens.Radius.BUTTON), this);
        if (!primary) shape.setStroke(Ui.dp(this, 1), Ui.color(this, UiTokens.Role.DIVIDER));
        importButton.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Ui.color(this, UiTokens.Role.PRIMARY) & 0x18FFFFFF),
                shape, null));
    }

    /** v3 续读主卡：ACCENT_SOFT 底 + 20dp 圆角 + 小封面 + 元信息 + 唯一青绿主按钮。
     *  按钮仍走 open(book)，不改阅读页的位置恢复逻辑。 */
    private void renderContinueCard(LinearLayout slot, ResolvedContinueReading r, BookStore.Book book) {
        LinearLayout card = Ui.tokenCard(this, UiTokens.Role.ACCENT_SOFT, UiTokens.Radius.CONTINUE_CARD);
        int pad = UiTokens.spacingDp(UiTokens.Spacing.LG);
        card.setPadding(pad, pad, pad, pad);
        TextView cover = Ui.text(this, book.title.substring(0, Math.min(4, book.title.length())), 14, Color.WHITE);
        cover.setGravity(Gravity.CENTER);
        cover.setBackground(Ui.coverShape(this, covers[Math.floorMod(book.id.hashCode(), covers.length)]));
        card.addView(cover, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 84)));
        LinearLayout info = Ui.column(this);
        info.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        info.addView(Ui.tokenText(this, "继续阅读", UiTokens.Typography.AUX, UiTokens.Role.PRIMARY));
        TextView title = Ui.tokenText(this, book.title, UiTokens.Typography.GROUP_TITLE, UiTokens.Role.PRIMARY);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(title);
        info.addView(Ui.tokenText(this, r.format.name() + " · " + r.positionText,
                UiTokens.Typography.AUX, UiTokens.Role.PRIMARY));
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        Button resume = Ui.primaryToken(this, "继续阅读", () -> open(book), "继续阅读 " + book.title);
        card.addView(resume, new LinearLayout.LayoutParams(-2, -2));
        card.setContentDescription("继续阅读 " + book.title + "，" + r.positionText);
        slot.addView(card, new LinearLayout.LayoutParams(-1, -2));
        Ui.gap(slot, UiTokens.spacingDp(UiTokens.Spacing.LG));
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
        boolean emptyLibrary = allBooks && !searching && !favoritesOnly && formatFilter == 0;
        boolean filterActive = favoritesOnly || tagsOnly || formatFilter > 0 || !allBooks;
        // 条件串只用于展示，判断一律取自真实状态；双零时两组条件同时可见，不归咎于某一个。
        StringBuilder conditions = new StringBuilder();
        if (favoritesOnly) conditions.append("珍藏");
        if (formatFilter > 0) {
            if (conditions.length() > 0) conditions.append("、");
            conditions.append(formatFilterLabel());
        }
        if (tagsOnly && !filter.isEmpty()) {
            if (conditions.length() > 0) conditions.append("、");
            conditions.append("标签 ").append(filter);
        } else if (!searching && !allBooks) {
            if (conditions.length() > 0) conditions.append("、");
            conditions.append("文件夹范围");
        }
        String shownConditions = conditions.length() > 0 ? conditions.toString() : "当前文件夹范围";
        String title, message, cta, desc;
        Runnable action;
        if (searching && tagsOnly && filter.isEmpty()) {
            title = "请输入标签名"; message = "例如：#旅行；当前范围与筛选会保留。";
            cta = "输入标签"; desc = "聚焦搜索框以输入标签名"; action = this::focusSearchInput;
        } else if (searching && filterActive) {
            title = "没有匹配的书籍";
            message = "已保留关键词「" + filter + "」与筛选条件（" + shownConditions + "）。可清除筛选或修改关键词。";
            cta = "清除筛选"; desc = "清除筛选条件，保留关键词"; action = this::clearFiltersKeepKeyword;
        } else if (searching) {
            title = "没有匹配的书籍";
            message = "已保留关键词「" + filter + "」；试试更短的书名、作者或标签。";
            cta = "修改关键词"; desc = "聚焦搜索框以修改搜索词"; action = this::focusSearchInput;
        } else if (emptyLibrary) {
            title = "从本地文件开始阅读";
            message = "导入本地书籍后即可在这里继续阅读；本地导入不需要插件，离线也能使用。";
            cta = "导入文件"; desc = "导入本地书籍"; action = this::pickFiles;
        } else if (filterActive) {
            title = "没有符合筛选条件的书籍";
            message = "已应用：" + shownConditions + "；关键词与合集范围会保留。";
            cta = "清除筛选"; desc = "清除筛选条件，保留关键词"; action = this::clearFiltersKeepKeyword;
        } else {
            title = folderId == null ? "还没有来源文件夹" : "此文件夹为空";
            message = folderId == null ? "可通过导入菜单扫描文件夹后阅读。"
                    : "当前层没有直接归档的书籍或子文件夹；可导入文件或返回上层。";
            cta = "清除筛选"; desc = "清除筛选条件，保留关键词"; action = this::clearFiltersKeepKeyword;
        }
        // SURFACE 卡 + 58dp ACCENT_SOFT 图标底；次级文字只放 SURFACE（4.88:1），不碰 SOFT。
        LinearLayout panel = Ui.tokenCard(this, UiTokens.Role.SURFACE, UiTokens.Radius.CARD);
        int pad = UiTokens.spacingDp(UiTokens.Spacing.LG);
        panel.setPadding(pad, pad, pad, pad);
        TextView icon = Ui.text(this, "▤", 25, Ui.color(this, UiTokens.Role.ACCENT));
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.shape(Ui.color(this, UiTokens.Role.ACCENT_SOFT),
                UiTokens.radiusDp(UiTokens.Radius.PILL), this));
        panel.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58)));
        Ui.gap(panel, UiTokens.spacingDp(UiTokens.Spacing.MD));
        panel.addView(Ui.tokenText(this, title, UiTokens.Typography.GROUP_TITLE, UiTokens.Role.PRIMARY));
        Ui.gap(panel, UiTokens.spacingDp(UiTokens.Spacing.XS));
        panel.addView(Ui.tokenText(this, message, UiTokens.Typography.BODY, UiTokens.Role.SECONDARY));
        if (searching && filterActive) {
            Ui.gap(panel, UiTokens.spacingDp(UiTokens.Spacing.XS));
            panel.addView(Ui.tokenText(this, "关键词：" + filter + " · 筛选：" + shownConditions,
                    UiTokens.Typography.AUX, UiTokens.Role.SECONDARY));
        }
        Ui.gap(panel, UiTokens.spacingDp(UiTokens.Spacing.MD));
        // 每屏至多一个 PRIMARY：空库时标题栏导入已是 primary，此处 CTA 降次；
        // 搜索/筛选无结果时标题栏导入降次，此处 CTA 升 primary。
        if (emptyLibrary) {
            panel.addView(Ui.secondaryToken(this, cta, action, desc));
        } else {
            applyImportButtonStyle(false);
            panel.addView(Ui.primaryToken(this, cta, action, desc));
            if (searching && filterActive) {
                Ui.gap(panel, UiTokens.spacingDp(UiTokens.Spacing.SM));
                panel.addView(Ui.secondaryToken(this, "修改关键词", this::focusSearchInput,
                        "聚焦搜索框以修改搜索词"));
            }
        }
        list.addView(panel, new LinearLayout.LayoutParams(-1, -2));
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
        formatFilter = 0; // 格式也是筛选，清除时一并复位；排序与布局不动。
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
        boolean hasCover=metadata!=null&&!metadata.coverName.isEmpty();
        if(hasCover&&coverDecodesThisPage<4){
            coverDecodesThisPage++;
            final String coverName=metadata.coverName;
            // 只在确有封面要解码时创建占位；无封面的书回到 0.63 基线，零新增 View。
            final TextView placeholder=Ui.text(this,"无封面",11,Ui.MUTED);placeholder.setBackgroundColor(Ui.PANEL);
            placeholder.setPadding(Ui.dp(this,8),Ui.dp(this,4),Ui.dp(this,8),Ui.dp(this,4));placeholder.setGravity(Gravity.CENTER);
            coverFrame.addView(placeholder,new android.widget.FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
            android.widget.ImageView image=new android.widget.ImageView(this);image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            image.setContentDescription(book.title+" 封面");image.setImageBitmap(null);
            coverFrame.addView(image,new android.widget.FrameLayout.LayoutParams(-1,-1));
            long request=libraryRequest;worker.execute(()->{try{
                android.graphics.Bitmap bitmap=CoverCache.preview(this,coverName,420);
                runOnUiThread(()->{if(!isDestroyed()&&request==libraryRequest&&bitmap!=null){
                    image.setImageBitmap(bitmap);placeholder.setVisibility(View.GONE);
                }});
            }catch(Exception ignored){}});
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
        String buildLabel = "开发版";
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (version != null && !version.isEmpty()) buildLabel = version + "  /  " + buildLabel;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        body.addView(Ui.text(this, buildLabel, 14, Ui.MUTED));
        Ui.gap(body, 16);
        body.addView(Ui.button(this, "第三方许可与声明", this::showThirdPartyLicenses,"查看第三方许可与声明"));
        body.addView(Ui.button(this, "应用插件", () -> startActivity(new Intent(this, PluginActivity.class)), "打开应用插件管理"));
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
