package org.openleaf.reader;

import org.openleaf.reader.application.ServiceRegistry;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.LeadingMarginSpan;
import android.os.Handler;
import android.os.Looper;
import org.openleaf.reader.core.BookSearch;
import org.openleaf.reader.core.ContentContracts;
import org.openleaf.reader.core.LazyChapterBook;
import org.openleaf.reader.core.ProgressModel;
import org.openleaf.reader.core.ReadingAnchor;
import org.openleaf.reader.core.ReadingPace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Layout;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;
import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.ChapterOutline;
import org.openleaf.reader.core.ChapterRule;
import org.openleaf.reader.core.FontLibrary;
import org.openleaf.reader.core.FontLibrary.AddResult;
import org.openleaf.reader.core.FontLibrary.Entry;
import org.openleaf.reader.core.FontResolver;
import org.openleaf.reader.core.FontResolver.Kind;
import org.openleaf.reader.core.FontResolver.Result;
import org.openleaf.reader.core.ParagraphReflow;
import org.openleaf.reader.core.ReaderPresets;
import org.openleaf.reader.core.ThemePalette;
import org.openleaf.reader.core.TocFilter;
import org.openleaf.reader.core.TextEncoding;
import org.openleaf.reader.core.TextChapterIndexer;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReaderActivity extends Activity {
    // 0.30.0 #2：自定义背景 / 字色（ARGB，0 = 未设置，回退内置预设）。
    private static final String KEY_CUSTOM_BG = "custom_bg_color";
    private static final String KEY_CUSTOM_INK = "custom_ink_color";
    private static final int[] BG_COLORS = {
            0xFFFDFBF7, 0xFFF5EBD8, 0xFFE8F0E3, 0xFFF0F0F0,
            0xFFE8EFF5, 0xFF18221F, 0xFF2B2B2B, 0xFF000000};
    private static final int[] INK_COLORS = {
            0xFF24302A, 0xFF473D2C, 0xFF1F2A44, 0xFF1F3A2A,
            0xFF555555, 0xFFD3DBD4, 0xFFF5F1E8, 0xFFFFFFFF};
    private static final String[] BG_NAMES = {
            "暖白", "米黄", "护眼绿", "浅灰", "淡蓝", "夜间", "深灰", "纯黑"};
    private static final String[] INK_NAMES = {
            "墨黑", "深棕", "深蓝", "深绿", "灰", "浅灰", "米白", "纯白"};
    // 0.57.0 P1-2：排版草稿面板的静态文案与小样文本（中英混排 + 标点 + 3 行）。
    private static final String[] PRESET_NAMES = {"紧凑", "标准", "宽松", "小说", "漫画"};
    private static final String[] ALIGN_LABELS = {"左对齐", "两端对齐", "居中", "右对齐"};
    private static final String[] CONVERT_LABELS = {"原文", "简→繁", "繁→简"};
    private static final String STYLE_SAMPLE_TEXT =
            "墨香伴长夜，开卷见青山。读书写字，字字入心。OpenLeaf 0123456789 abcXYZ。\n"
            + "标点齐备：逗号，句号。叹号！问号？冒号：引号「你好」；English words 42。\n"
            + "小样只反映草稿，点「应用」才会写入配置并重排正文。Lorem ipsum 2026。";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LazyChapterBook book;
    private ProgressModel progressModel;
    private AnnotationStore annotations;
    private ReaderStyle style;
    private ReaderFonts fonts;
    // 0.29.0 P2-3：字体管理对话框与预览缓存（按 id，最多 20，对话框关闭即清）。
    private FontManagerUi fontUi = new FontManagerUi();
    private final Map<String, Typeface> previewCache = new LinkedHashMap<>();
    private AlertDialog fontDialog;
    private LinearLayout fontCardsContainer;
    private List<Entry> fontDialogEntries = new ArrayList<>();
    private int fontPreviewGeneration;
    // 0.57.0 P1-2：排版草稿与面板视图引用，生命周期与排版 dialog 完全绑定。
    private StyleDraft styleDraft;
    private StylePanelViews stylePanelViews;
    /** 排版面板内可变的视图引用；只用于刷新文案与小样，dismiss 时整组清空。 */
    private static final class StylePanelViews {
        TextView preview;
        TextView presetTitle;
        Button[] presets;
        TextView sizeValue;
        Button reflow;
        Button[] options;
    }
    private ReadingStatsStore statsStore;
    private BookStore.Book currentRecord;
    private android.net.Uri displayedSource;
    private String displayedSourceSha;
    // 在线书逐章缓存：从骨架 txt 打开，正文按需下载到 chapter_cache
    private OnlineChapterStore onlineChapters;
    private OnlineChapterFetcher onlineFetcher;
    private OnlineBookStore.OnlineBook onlineBinding;
    private java.util.List<org.openleaf.reader.core.OnlineChapter.Entry> onlineEntries = java.util.Collections.emptyList();
    private boolean onlineBook;
    private SourceUpdateChecker.CheckResult sourceCheck; // 在线书书源规则检查结果
    private String id;
    /** 上一次看到的外部（听书）位置修订号；只用它区分外部写入与自己的保存。 */
    private String lastExternalRevision;
    private int chapter, pendingOffset, fontSize = 20, theme;
    // 当前生效配色（用于系统主题/自定义色变化时的幂等比较）。
    private int paperColor, inkColor;
    private static final float[] DIM_LEVELS = {1.0f, 0.78f, 0.55f}; // 夜间调光档位（系统窗口亮度）
    private int dimIndex;
    private boolean prefetchWifiOnly = true; // 仅 Wi-Fi 预取（默认开，避免移动流量）
    // 阅读计时：本次会话起始、上次结算锚点与自动翻页
    private long sessionStartMs, segmentStartMs;
    private static final long[] AUTO_TURN_INTERVALS = {0, 4000, 8000, 15000, 30000, 60000};
    private int autoTurnIndex = 0;
    private final Handler autoHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoTurnTask = new Runnable() {
        @Override public void run() {
            if (autoTurnIndex <= 0 || book == null || isFinishing() || isDestroyed()) return;
            if (!restoring && !readerSelectionActive && !readerDialogHasFocus()) {
                commitReadingSegment();
                if (horizontalPages) {
                    turnPage(1);
                    if (readerPages != null && chapter == book.size() - 1
                            && pageIndex == readerPages.count() - 1) return;
                } else scroll.smoothScrollBy(0, Math.max(120, scroll.getHeight() - Ui.dp(ReaderActivity.this, 48)));
            }
            autoHandler.removeCallbacks(this);
            autoHandler.postDelayed(this, AUTO_TURN_INTERVALS[autoTurnIndex]);
        }
    };
    private boolean volumeKeysEnabled;
    private boolean readerSelectionActive;

    private boolean readerDialogHasFocus() {
        return !hasWindowFocus();
    }
    private String pendingExportFormat = "json";
    private float pendingLineFraction;
    private boolean restoring, pendingBottom;
    private int layoutGeneration;
    private LinearLayout root, toolbar, footer;
    private ReaderPagingScroll scroll;
    private android.widget.FrameLayout scrollContent;
    private Button previousRead, nextRead;
    private ReaderPaging readerPages;
    private boolean horizontalPages;
    private int pageMotion, pageIndex, pageAnchor, enterPageDirection;
    private static final String[] PAGE_MOTIONS = {"无动画", "平移", "淡化"};
    /** 阶段 4 精修： immersive 阅读（向下滚动隐藏工具栏/底栏，停止或上滑恢复），借鉴 baca. 形态。 */
    private boolean immersiveEnabled=false;
    private int lastScrollY=0;
    private final android.os.Handler chromeHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable showChrome=()->setChromeVisible(true);
    private TextView text, chapterLabel, progress, searchStatus, cssStatus, themeStatus;
    private LinearLayout searchControls;
    private Button previousMatch,nextMatch;
    private String searchQuery="";
    private boolean searchCaseSensitive,searchFinding;
    private int searchScope=-1,searchGeneration,searchMode=BookSearch.LITERAL,searchWindow=BookSearch.DEFAULT_NEAR_WINDOW;
    private List<BookSearch.Hit> searchHits=new ArrayList<>();
    private int activeMatch=-1,requestedChapter=-1,requestedStart=-1;
    private Future<?> searchFuture;
    private static final int REQUEST_SEARCH=430, REQUEST_EDIT=431, REQUEST_EXPORT_NOTES=432, REQUEST_IMPORT_NOTES=433;
    private static final int REQUEST_PICK_FONT=434;
    private static final int SEARCH_LIMIT=10000;
    private static final int RULE_PREVIEW_SAMPLE_CHARS=4000;
    private static final int RULE_PREVIEW_MAX_LINES=400;
    private int rulePreviewGeneration;
    private int indexGeneration; // 编码与规则共用一个结果所有者
    private Future<?> indexFuture;
    private AlertDialog indexDialog;
    private final ExecutorService rulePreviewWorker = Executors.newSingleThreadExecutor();
    private Future<?> rulePreviewFuture;
    private final Handler rulePreviewHandler = new Handler(Looper.getMainLooper());
    private Runnable rulePreviewTask;
    private static final long RULE_PREVIEW_DEBOUNCE_MS = 250L;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("reader", 0);
        id = getIntent().getStringExtra("bookId");
        if (id == null) { finish(); return; }
        lastExternalRevision = prefs.getString(org.openleaf.reader.core.PositionSync.revisionKey(id), null);
        annotations = new AnnotationStore(this);
        style = ReaderStyle.get(this);
        fonts = new ReaderFonts(this);
        statsStore = new ReadingStatsStore(this);
        onlineChapters = new OnlineChapterStore(this);
        onlineFetcher = new OnlineChapterFetcher(this);
        volumeKeysEnabled = prefs.getBoolean("volumeKeys", false);
        pageMotion = Math.max(0, Math.min(PAGE_MOTIONS.length - 1, prefs.getInt("readerPageMotion", 1)));
        autoTurnIndex = Math.max(0, Math.min(AUTO_TURN_INTERVALS.length - 1, prefs.getInt("autoTurn", 0)));
        ReadingAnchor anchor = ReadingAnchor.parse(prefs.getString("anchor_" + id, null));
        chapter = prefs.getInt("chapter_" + id, 0);
        pendingOffset = prefs.getInt("offset_" + id, 0);
        pendingBottom = prefs.getBoolean("bottom_" + id, false);
        pendingLineFraction = prefs.getFloat("lineFraction_" + id, 0);
        // 无障碍：无显式字号设置时按系统字体缩放定初始值（系统大字体用户得到更大字号）
        float systemScale = getResources().getConfiguration().fontScale;
        int defaultFont = Math.max(14, Math.min(32, Math.round(20 * systemScale)));
        fontSize = Math.max(14, Math.min(40, prefs.getInt("fontSize", defaultFont)));
        // 深色跟随：默认关，未开启时与既有 clamp 语义逐字节一致（T02 只做判定接入）
        theme = ThemeResolver.resolve(prefs.getBoolean(ThemeResolver.KEY_FOLLOW, false),
                getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK,
                prefs.getInt("theme", 0));
        // 关键初始化：字段默认 0 不是任何合法色值。若不在 build() 前写入当前调色板，
        // onResume→recomputeTheme 会把「未变化」误判为「已变化」，在 text 尚未创建时调 colors()。
        int[] initialPalette = ThemePalette.resolve(theme,
                prefs.getInt(KEY_CUSTOM_BG, 0), prefs.getInt(KEY_CUSTOM_INK, 0));
        paperColor = initialPalette[0];
        inkColor = initialPalette[1];
        dimIndex = Math.max(0, Math.min(DIM_LEVELS.length - 1, prefs.getInt("dimLevel", 0)));
        prefetchWifiOnly = prefs.getBoolean("prefetchWifiOnly", true);
        immersiveEnabled = prefs.getBoolean("immersive", false);
        if (state != null) {
            chapter = state.getInt("chapter", chapter);
            pendingOffset = state.getInt("offset", pendingOffset);
            pendingBottom = state.getBoolean("bottom", pendingBottom);
            pendingLineFraction = state.getFloat("lineFraction", pendingLineFraction);
            searchQuery=state.getString("searchQuery","");searchCaseSensitive=state.getBoolean("searchCaseSensitive",false);
            searchScope=state.getInt("searchScope",-1);searchMode=state.getInt("searchMode",BookSearch.LITERAL);
            searchWindow=state.getInt("searchWindow",BookSearch.DEFAULT_NEAR_WINDOW);
            requestedChapter=state.getInt("matchChapter",chapter);requestedStart=state.getInt("matchStart",pendingOffset);
            ReadingAnchor fromState=ReadingAnchor.parse(state.getString("anchor",null));
            if(fromState!=null)anchor=fromState;
        }
        final ReadingAnchor savedAnchor=anchor;
        root = Ui.column(this);
        Ui.mount(this, root);
        TextView loading = Ui.text(this, "正在整理书页…", 18, Ui.GREEN);
        Ui.pad(loading, 28);
        root.addView(loading);
        worker.execute(() -> {
            try {
                BookStore store = new BookStore(this);
                BookStore.Book record = store.find(id);
                currentRecord=record;
                // 在线书：检测绑定并加载章节清单（正文从 chapter_cache 按需读取）
                final OnlineBookStore.OnlineBook binding = new OnlineBookStore(this).findByBookId(id);
                if (binding != null) {
                    onlineBook = true;
                    onlineBinding = binding;
                    onlineEntries = onlineChapters.chapters(binding.remoteMeta);
                    // 检查书源规则是否更新（不阻塞开书，结果稍后在主线程提示）
                    sourceCheck = new SourceUpdateChecker(this).check(binding);
                }
                final android.net.Uri sourceAtOpen = store.sourceUri(record);
                final String sourceShaAtOpen = sourceAtOpen == null ? null : store.storedHash(id);
                try (BookAccess access = store.open(record)) {
                java.io.File file = access.file;
                // P1-3：按书读取编码覆盖（默认 AUTO→null，走 0.27.0 原自动检测）
                ChapterRule appliedRule = currentRule();
                LazyChapterBook parsed = LazyChapterBook.open(
                        file, record.format, getCacheDir(), appliedRule, currentEncodingOverride(file),
                        TextChapterIndexer.DEFAULT_RULE_MATCH_BUDGET_MS);
                int migrationAnchor = -1;
                if (savedAnchor == null && state == null && parsed.size() > 0
                        && parsed.meta(0).sourceStart >= 0
                        && prefs.contains("chapter_" + id) && prefs.getInt("text_layout_version_" + id, 0) == 0) {
                    // Only upgrades need the old partition, and only once per book.
                    try {
                        List<BookParser.Chapter> old = BookParser.parseLegacyText(file).chapters;
                        BookParser.Chapter previous = old.get(Math.max(0, Math.min(chapter, old.size() - 1)));
                        migrationAnchor = previous.sourceStart + Math.max(0, Math.min(pendingOffset, previous.text.length()));
                    } catch (java.io.IOException ignored) { /* Leave existing positions intact if the old partition cannot be reconstructed. */ }
                }
                final int legacyAnchor = migrationAnchor;
                runOnUiThread(() -> {
                    if (isDestroyed()) { parsed.close(); return; }
                    book = parsed;
                    displayedSource = sourceAtOpen;
                    displayedSourceSha = sourceShaAtOpen;
                    progressModel = new ProgressModel(book);
                    chapter = Math.max(0, Math.min(chapter, book.size() - 1));
                    int[] mappedOffset = new int[]{pendingOffset};
                    if (savedAnchor != null) {
                        chapter = resolveAnchor(savedAnchor, mappedOffset);
                        pendingOffset = mappedOffset[0];
                        pendingBottom = savedAnchor.atEnd;
                        pendingLineFraction = savedAnchor.lineFraction;
                    } else if (legacyAnchor >= 0) {
                        int found = 0;
                        for (int i = 1; i < book.size() && book.meta(i).sourceStart <= legacyAnchor; i++) found = i;
                        LazyChapterBook.Section newPart = book.meta(found);
                        int mapped = Math.max(0, Math.min(legacyAnchor - newPart.sourceStart, newPart.charCount));
                        if (found != chapter || mapped != pendingOffset) { pendingBottom = false; pendingLineFraction = 0; }
                        chapter = found; pendingOffset = mapped;
                    }
                    int targetSpine = state == null ? getIntent().getIntExtra("epubTargetSpine", -1) : -1;
                    if (targetSpine >= 0 && book.meta(chapter).spineIndex != targetSpine) {
                        int matchingChapter = -1;
                        for (int i = 0; i < book.size(); i++) {
                            if (book.meta(i).spineIndex == targetSpine) { matchingChapter = i; break; }
                        }
                        if (matchingChapter < 0) {
                            Toast.makeText(this, "此 EPUB 章节没有可定位的纯文字内容", Toast.LENGTH_LONG).show();
                            finish();
                            return;
                        }
                        chapter = matchingChapter; pendingOffset = 0; pendingLineFraction = 0; pendingBottom = false;
                    }
                    int startChapter = state == null ? getIntent().getIntExtra("startChapter", -1) : -1;
                    if (startChapter >= 0) {
                        chapter = Math.max(0, Math.min(startChapter, book.size() - 1));
                        pendingOffset = 0; pendingBottom = false; pendingLineFraction = 0;
                    }
                    build();
                    showChapter(pendingOffset, pendingBottom, pendingLineFraction);
                    sessionStartMs = System.currentTimeMillis();
                    segmentStartMs = sessionStartMs;
                    startAutoTurn();
                    promptSourceUpdateIfNeeded();
                    if (isTextFormat()) warnUnavailableRule(appliedRule);
                    if (getIntent().getBooleanExtra("startBookSearch", false)) {
                        getIntent().removeExtra("startBookSearch");
                        launchSearch();
                    } else if (!searchQuery.isEmpty()) computeSearch(false);
                });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) new AlertDialog.Builder(this)
                            .setTitle("无法阅读这本书").setMessage(e.getMessage())
                            .setNeutralButton("书籍详情", (d, w) -> { startActivity(new Intent(this, BookDetailActivity.class).putExtra("bookId", id)); finish(); })
                            .setPositiveButton("返回书架", (d, w) -> finish())
                            .setOnCancelListener(d -> finish()).show();
                });
            }
        });
    }

    /** 在线书：书源规则失配时提示用户到书源页重新解析（不阻塞阅读）。 */
    private void promptSourceUpdateIfNeeded() {
        if (!onlineBook || sourceCheck == null || !sourceCheck.needsUpdate() || isDestroyed()) return;
        String detail = SourceUpdateChecker.describe(sourceCheck);
        String message = "《" + (currentRecord == null ? "" : currentRecord.title) + "》" + detail
                + "。已缓存的章节仍可离线阅读；如需按新规则抓取后续章节，请到书源页更新后重新加入书架。";
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("书源规则已变化")
                .setMessage(message)
                .setNeutralButton("稍后", null);
        if (sourceCheck.status == SourceUpdateChecker.Status.RULE_CHANGED) {
            // 一键重新解析：用当前书源重抓目录，保留 bookId/进度/缓存
            builder.setPositiveButton("重新解析", (d, w) -> {
                final String bookId = id;
                final OnlineBookReparser reparser = new OnlineBookReparser(this);
                worker.execute(() -> {
                    final OnlineBookReparser.Result result = reparser.reparse(bookId);
                    runOnUiThread(() -> {
                        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show();
                        if (result.success) recreate(); // 重新加载骨架与章节清单
                    });
                });
            });
            builder.setNegativeButton("书源页", (d, w) ->
                    startActivity(new Intent(this, LegadoActivity.class)));
        }
        builder.show();
    }

    private void build() {
        root.removeAllViews();
        toolbar = Ui.row(this);
        Ui.pad(toolbar, 4);
        Button back = Ui.button(this, "返回", this::finish, "返回书架"); toolbar.addView(back);
        TextView title = Ui.text(this, getIntent().getStringExtra("title"), 15, Ui.GREEN);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        toolbar.addView(title, Ui.weight());
        Button layout = Ui.button(this, "排版", this::openStyleSettings, "排版设置"); toolbar.addView(layout);
        if (showDirectEdit()) {
            toolbar.addView(Ui.button(this, "编辑", this::openEditor, "编辑当前文本"));
        }
        if (showDirectListen()) {
            toolbar.addView(Ui.button(this, "听书", this::listenFromCurrentChapter, "从当前章节开始朗读"));
        }
        Button more = Ui.button(this, "更多", this::openMoreMenu, "更多阅读操作"); toolbar.addView(more);
        root.addView(toolbar);
        chapterLabel = Ui.text(this, "", 13, Ui.MUTED);
        chapterLabel.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 8));
        if (android.os.Build.VERSION.SDK_INT >= 28) chapterLabel.setAccessibilityHeading(true);
        root.addView(chapterLabel);
        searchControls=Ui.column(this);Ui.pad(searchControls,4);
        searchStatus=Ui.text(this,"",12,Ui.MUTED);searchStatus.setMaxLines(2);searchControls.addView(searchStatus);
        LinearLayout matchActions=Ui.row(this);
        previousMatch=Ui.button(this,"上一个",()->moveMatch(-1));matchActions.addView(previousMatch,Ui.weight());
        nextMatch=Ui.button(this,"下一个",()->moveMatch(1));matchActions.addView(nextMatch,Ui.weight());
        matchActions.addView(Ui.button(this,"结果列表",this::openSearch),Ui.weight());
        matchActions.addView(Ui.button(this,"清除",this::clearSearch),Ui.weight());searchControls.addView(matchActions);
        searchControls.setVisibility(View.GONE);root.addView(searchControls);
        horizontalPages = supportsPaging() && prefs.getBoolean("readerHorizontalPages", false);
        pageIndex = pageAnchor = enterPageDirection = 0;
        readerPages = null;
        readerSelectionActive = false;
        scroll = new ReaderPagingScroll(this, this::turnPage);
        scroll.setFillViewport(true);
        scroll.setPaged(horizontalPages);
        scroll.setVerticalScrollBarEnabled(!horizontalPages);
        // The book anchor is restored explicitly; do not mix it with view hierarchy state.
        scroll.setSaveEnabled(false);
        text = Ui.text(this, "", fontSize, ThemePalette.presetInk(theme)); // colors() 会再套自定义字色
        text.setSaveEnabled(false);
        text.setTextIsSelectable(true);
        text.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
            public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                readerSelectionActive = true;
                scroll.setSelectionActive(true);
                menu.add(0, 1, 0, "高亮");
                menu.add(0, 2, 1, "笔记");
                menu.add(0, 3, 2, "朗读选中"); // 候选 A：选中文本直达系统 TTS
                return true;
            }
            public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) { return false; }
            public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                if (item.getItemId() == 1) { addHighlightFromSelection(false); mode.finish(); return true; }
                if (item.getItemId() == 2) { addHighlightFromSelection(true); mode.finish(); return true; }
                if (item.getItemId() == 3) {
                    int start = text.getSelectionStart(), end = text.getSelectionEnd();
                    if (end > start) {
                        String selected = text.getText().subSequence(start, end).toString();
                        startActivity(new Intent(ReaderActivity.this, TtsActivity.class)
                                .putExtra(TtsActivity.EXTRA_SPEAK_TEXT, selected));
                    }
                    mode.finish();
                    return true;
                }
                return false;
            }
            public void onDestroyActionMode(android.view.ActionMode mode) {
                readerSelectionActive = false;
                scroll.setSelectionActive(false);
            }
        });
        applyTextStyle();
        int horizontal = Math.max(24, (getResources().getConfiguration().screenWidthDp - 680) / 2);
        text.setPadding(Ui.dp(this, Math.max(horizontal, style.sideMarginDp())),
                Ui.dp(this, 28), Ui.dp(this, Math.max(horizontal, style.sideMarginDp())), Ui.dp(this, 48));
        scrollContent = new android.widget.FrameLayout(this);
        scrollContent.addView(text, new android.widget.FrameLayout.LayoutParams(-1, -2));
        scroll.addView(scrollContent, new ScrollView.LayoutParams(-1, -2));
        scroll.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (view != scroll || !horizontalPages || (r - l == or - ol && b - t == ob - ot)) return;
            syncPageScrollRange();
            if (!restoring && readerPages != null) {
                restoring = true;
                restore(pendingOffset, pendingBottom, pendingLineFraction);
            }
        });
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = Ui.row(this);
        Ui.pad(footer, 4);
        previousRead = Ui.button(this, "上一章", () -> turnFromFooter(-1));
        Button tocButton = Ui.button(this, "目录", this::contents); tocButton.setContentDescription("章节目录");
        nextRead = Ui.button(this, "下一章", () -> turnFromFooter(1));
        footer.addView(previousRead, Ui.weight());
        footer.addView(tocButton, Ui.weight());
        footer.addView(nextRead, Ui.weight());
        refreshPageControls();
        root.addView(footer);
        progress = Ui.text(this, "", 12, Ui.MUTED);
        progress.setGravity(android.view.Gravity.CENTER);
        progress.setPadding(0, 0, 0, Ui.dp(this, 10));
        root.addView(progress);
        cssStatus = Ui.text(this, "", 11, Ui.MUTED);
        cssStatus.setGravity(android.view.Gravity.CENTER);
        cssStatus.setVisibility(View.GONE); // v2.1 页脚降噪：默认隐藏，仅非默认/异常态显示
        root.addView(cssStatus);
        themeStatus = Ui.text(this, "", 11, Ui.MUTED);
        themeStatus.setGravity(android.view.Gravity.CENTER);
        themeStatus.setVisibility(View.GONE);
        root.addView(themeStatus);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (restoring || horizontalPages) return;
            if (immersiveEnabled) {
                if (y > lastScrollY + 6 && y > Ui.dp(this, 160)) setChromeVisible(false);
                else if (y < lastScrollY - 6) setChromeVisible(true);
                lastScrollY = y;
                chromeHandler.removeCallbacks(showChrome);
                chromeHandler.postDelayed(showChrome, 1200);
            }
            save();
            noteInteraction();
        });
        scroll.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) noteInteraction();
            return false;
        });
        colors();
    }

    private void colors() {
        // onResume 可能早于 worker 的 build()：UI 未就绪时直接返回，
        // build() 末尾会再调一次 colors()，不会丢失配色。
        if (root == null || text == null || chapterLabel == null || progress == null
                || toolbar == null || footer == null) return;
        // 0.30.0 #2：自定义色优先，未设置时逐字回退 0.29.0 三套预设。
        int[] palette = ThemePalette.resolve(theme,
                prefs.getInt(KEY_CUSTOM_BG, 0), prefs.getInt(KEY_CUSTOM_INK, 0));
        int paper = palette[0];
        int ink = palette[1];
        paperColor = paper;
        inkColor = ink;
        root.setBackgroundColor(paper);
        text.setTextColor(ink);
        boolean darkPaper = Color.luminance(paper) < 0.27f;
        int panel = darkPaper ? 0xFF202C25 : 0xFFF2F2E9;
        int chip = darkPaper ? 0xFF304339 : 0xFFE7EDE1;
        int strong = darkPaper ? 0xFFB8D4BD : 0xFF315C48;
        int chromeInk = darkPaper ? 0xFFEAF2E8 : 0xFF26372C;
        toolbar.setBackgroundColor(panel);
        footer.setBackgroundColor(panel);
        chapterLabel.setBackgroundColor(chip);
        chapterLabel.setTextColor(chromeInk);
        progress.setTextColor(chromeInk);
        progress.setBackgroundColor(panel);
        if (searchStatus != null) searchStatus.setTextColor(ink);
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child instanceof Button) styleReaderAction((Button) child, panel, chromeInk);
            else if (child instanceof TextView) ((TextView) child).setTextColor(chromeInk);
        }
        for (int i = 0; i < footer.getChildCount(); i++) {
            View child = footer.getChildAt(i);
            if (child instanceof Button) styleReaderAction((Button) child, i == 1 ? strong : chip,
                    i == 1 ? (darkPaper ? 0xFF17291E : Color.WHITE) : chromeInk);
        }
        SystemBars.apply(this, paper); // 阶段 3-B 收尾：API30+ 用 WindowInsetsController 控制图标
        updateStatusVisibility(); // v2.1 页脚降噪：随配色刷新同步两行调试状态的可见性
    }

    private void styleReaderAction(Button button, int fill, int ink) {
        button.setTextColor(new android.content.res.ColorStateList(new int[][] {
                {-android.R.attr.state_enabled}, new int[0]
        }, new int[] {Color.luminance(fill) < 0.27f ? 0xFF9EAFA2 : 0xFF626C61, ink}));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x307A8B7C), Ui.shape(fill, 12, this), null));
    }

    private boolean showDirectEdit() { return supportsPaging() && displayedSource != null; }

    private boolean showDirectListen() {
        return !showDirectEdit() || getResources().getConfiguration().screenWidthDp >= 420;
    }

    private void listenFromCurrentChapter() {
        startActivity(new Intent(this, TtsActivity.class)
                .putExtra(TtsPlaybackService.EXTRA_BOOK_ID, id)
                .putExtra(TtsPlaybackService.EXTRA_CHAPTER, chapter));
    }

    /** 低频操作收进「更多」；窄屏优先保留排版与本地编辑。 */
    private void openMoreMenu() {
        final boolean isTxt = "txt".equals(currentRecord != null ? currentRecord.format : "");
        final boolean epubBook = "epub".equals(getIntent().getStringExtra("format"))
                || (getIntent().getStringExtra("path") != null
                && getIntent().getStringExtra("path").toLowerCase(java.util.Locale.ROOT).endsWith(".epub"));
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        labels.add("查找"); actions.add(this::openSearch);
        if (supportsPaging() && !showDirectEdit()) { labels.add("编辑"); actions.add(this::openEditor); }
        if (!showDirectListen()) { labels.add("从当前章听书"); actions.add(this::listenFromCurrentChapter); }
        if (epubBook) { labels.add("图文"); actions.add(this::switchIllustrated); }
        labels.add("标注管理"); actions.add(this::showAnnotations);
        if (isTxt) { labels.add("AI 单章摘要"); actions.add(this::openAiPanel); }
        labels.add("阅读设置"); actions.add(this::settings);
        new AlertDialog.Builder(this).setTitle("更多阅读操作")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                }).setNegativeButton("关闭", null).show();
    }

    /** v2.1 页脚降噪：外部样式 / 跟随系统状态仅在非默认或异常态显示，默认 GONE。 */
    private void updateStatusVisibility() {
        if (cssStatus != null) {
            String s = cssStatus.getText().toString();
            // 仅展示有信息量的提示（忽略告警 / 已关闭 / 导入失败）；默认空文案与「未导入 / 已取消」一律隐藏。
            boolean meaningful = !s.isEmpty() && !s.startsWith("未导入外部样式") && !"已取消".equals(s);
            cssStatus.setVisibility(meaningful ? View.VISIBLE : View.GONE);
        }
        if (themeStatus != null) {
            String s = themeStatus.getText().toString();
            // 跟随系统开启即非默认态；关闭（含"关（当前：…）"）隐藏。
            themeStatus.setVisibility(s.startsWith("跟随系统：开") ? View.VISIBLE : View.GONE);
        }
    }

    /** 设置面板分组小标题：暖纸外壳下使用柔和鼠尾草分组胶囊，适配窄屏。 */
    private TextView sectionTitle(String label) {
        TextView t = Ui.text(this, label, 12, Color.rgb(55, 79, 63));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.setBackground(Ui.outline(this, Color.rgb(232, 239, 227), 10));
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        t.setLayoutParams(lp);
        return t;
    }

    private Button settingsItemButton(String label, Runnable onClick, String desc) {
        Button b = Ui.button(this, label, onClick, desc);
        b.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT);
        b.setTextColor(Color.rgb(38, 53, 44));
        b.setBackground(Ui.outline(this, Color.WHITE, 10));
        b.setMinHeight(Ui.dp(this, 52));
        b.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * 设置面板分组项（动态 label）：点击后先跑原处理逻辑，再用 labelSupplier 重新读取实时状态刷新按钮文案。
     * 用 holder 数组而非直接引用 b，规避「变量 b 尚未初始化就被自身初始化器中的 lambda 捕获」的编译错误。
     */
    private Button settingsItemButton(java.util.function.Supplier<String> labelSupplier, Runnable onClick, String desc) {
        final Button[] holder = new Button[1];
        holder[0] = Ui.button(this, labelSupplier.get(), () -> {
            onClick.run();
            holder[0].setText(labelSupplier.get());
        }, desc);
        holder[0].setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT);
        holder[0].setTextColor(Color.rgb(38, 53, 44));
        holder[0].setBackground(Ui.outline(this, Color.WHITE, 10));
        holder[0].setMinHeight(Ui.dp(this, 52));
        holder[0].setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout.LayoutParams dynamicLp = new LinearLayout.LayoutParams(-1, -2);
        dynamicLp.setMargins(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        holder[0].setLayoutParams(dynamicLp);
        return holder[0];
    }

    /** 字号 / 主题变更：保存锚点 → 改值 → 落 SP → 重排 → 还原（原 which 0/1/2 的共同流程）。 */
    private void changeTextOrTheme(Runnable change, boolean manualTheme) {
        save();
        int anchor = pendingOffset;
        boolean bottom = pendingBottom;
        float fraction = pendingLineFraction;
        change.run();
        SharedPreferences.Editor editor = prefs.edit().putInt("fontSize", fontSize).putInt("theme", theme);
        if (manualTheme) editor.putBoolean(ThemeResolver.KEY_FOLLOW, false); // 手动切换自动关跟随
        editor.apply();
        restoring = true;
        text.setTextSize(fontSize);
        applyTextStyle();
        colors();
        restore(anchor, bottom, fraction);
    }

    /** 颜色入口的当前值文案：预设或十六进制自定义值。 */
    private String colorLabel(boolean background) {
        int custom = prefs.getInt(background ? KEY_CUSTOM_BG : KEY_CUSTOM_INK, 0);
        return custom == 0 ? "预设" : String.format(Locale.ROOT, "#%06X", custom & 0xFFFFFF);
    }

    /** 8 色色板 + 恢复预设；应用后立即重绘，并在对比度 < 4.5 时明确提示。 */
    private void openColorPicker(final boolean background) {
        final int[] colors = background ? BG_COLORS : INK_COLORS;
        final String[] names = background ? BG_NAMES : INK_NAMES;
        String[] options = new String[names.length + 1];
        for (int i = 0; i < names.length; i++) {
            options[i] = String.format(Locale.ROOT, "%s  #%06X", names[i], colors[i] & 0xFFFFFF);
        }
        options[names.length] = "恢复预设";
        new AlertDialog.Builder(this)
                .setTitle(background ? "背景色" : "字色")
                .setItems(options, (dialog, which) -> {
                    SharedPreferences.Editor editor = prefs.edit();
                    if (which == names.length) {
                        editor.remove(background ? KEY_CUSTOM_BG : KEY_CUSTOM_INK);
                    } else {
                        editor.putInt(background ? KEY_CUSTOM_BG : KEY_CUSTOM_INK, colors[which]);
                    }
                    editor.apply();
                    int[] palette = ThemePalette.resolve(theme,
                            prefs.getInt(KEY_CUSTOM_BG, 0), prefs.getInt(KEY_CUSTOM_INK, 0));
                    if (ThemePalette.lowContrast(palette[1], palette[0])) {
                        Toast.makeText(this, String.format(Locale.ROOT,
                                "对比度偏低（%.1f:1），可能影响阅读", ThemePalette.ratio(palette[1], palette[0])),
                                Toast.LENGTH_LONG).show();
                    }
                    save();
                    int anchor = pendingOffset;
                    boolean bottom = pendingBottom;
                    float fraction = pendingLineFraction;
                    afterStyleChange(anchor, bottom, fraction);
                    colors();
                    openStyleSettings();
                }).setNegativeButton("取消", null).show();
    }

    private String chapterName(int index) {
        String name = book.meta(index).title;
        String title = getIntent().getStringExtra("title");
        return title != null && name.startsWith(id) ? title + name.substring(id.length()) : name;
    }

    private void showChapter(int offset, boolean bottom, float fraction) {
        restoring = true;
        chapterLabel.setText(chapterName(chapter) + "  ·  " + (chapter + 1) + " / " + book.size());
        applySearchHighlights();
        restore(offset, bottom, fraction);
    }

    private void switchIllustrated() {
        if (book == null || restoring) return;
        save();
        Intent intent = new Intent(this, EpubActivity.class).putExtras(getIntent());
        intent.removeExtra("epubTargetSpine");
        int spine = book.meta(chapter).spineIndex;
        if (spine >= 0) intent.putExtra("epubTargetSpine", spine);
        startActivity(intent);
        finish();
    }

    private void openEditor() {
        if (book == null || currentRecord == null || restoring || !supportsPaging()) return;
        save();
        worker.execute(() -> {
            try {
                final List<BookStore.Source> sources = new BookStore(this).sources(id);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || restoring) return;
                    if (sources.isEmpty()) {
                        if (displayedSource != null) launchEditor(new BookStore.Source(displayedSource.toString(), currentRecord.title));
                        else Toast.makeText(this, "这本书没有登记可编辑的原文件", Toast.LENGTH_SHORT).show();
                    } else if (sources.size() == 1) launchEditor(sources.get(0));
                    else {
                        String[] names = new String[sources.size()];
                        for (int i = 0; i < sources.size(); i++) names[i] = sources.get(i).name;
                        new AlertDialog.Builder(this).setTitle("选择要编辑的原文件").setItems(names,
                                (d, which) -> launchEditor(sources.get(which))).setNegativeButton("取消", null).show();
                    }
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing())
                        Toast.makeText(this, "无法打开编辑：" + failure.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void launchEditor(BookStore.Source source) {
        if (book == null || currentRecord == null || restoring) return;
        save();
        android.net.Uri uri = android.net.Uri.parse(source.uri);
        String lower = source.name == null ? "" : source.name.toLowerCase(java.util.Locale.ROOT);
        String name = lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")
                ? source.name : currentRecord.title + "." + currentRecord.format;
        Intent intent = new Intent(this, LocalTextEditorActivity.class).setData(uri)
                .putExtra("title", name).putExtra("fromReader", true);
        if (uri.equals(displayedSource) && displayedSourceSha != null) {
            // Use the first visible offset even if this viewport reaches the end of the chapter.
            intent.putExtra("bookId", id)
                    .putExtra(LocalTextEditorActivity.EXTRA_READING_SOURCE, true)
                    .putExtra(LocalTextEditorActivity.EXTRA_EXPECTED_SHA256, displayedSourceSha)
                    .putExtra(LocalTextEditorActivity.EXTRA_SOURCE_OFFSET, anchorSourceOffset(chapter, pendingOffset))
                    .putExtra(LocalTextEditorActivity.EXTRA_ENCODING, TextEncoding.fromIndex(currentEncodingIndex()).name());
        }
        startActivityForResult(intent, REQUEST_EDIT);
    }

    private void writeNotes(android.net.Uri target) {
        final String format = pendingExportFormat;
        worker.execute(() -> {
            try {
                String title = currentRecord == null ? getIntent().getStringExtra("title") : currentRecord.title;
                byte[] bytes;
                if ("json".equals(format)) {
                    bytes = NotePackage.exportJson(this, id, title).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                } else if ("csv".equals(format)) {
                    bytes = NotePackage.exportCsvBytes(this, id, title);
                } else {
                    bytes = NotePackage.exportText(this, id, title, format).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                try (java.io.OutputStream out = getContentResolver().openOutputStream(target, "rwt")) {
                    if (out == null) throw new java.io.IOException("无法打开保存目标");
                    out.write(bytes);
                    out.flush();
                }
                runOnUiThread(() -> Toast.makeText(this, "笔记已导出", Toast.LENGTH_SHORT).show());
            } catch (Exception failure) {
                android.util.Log.e("OpenLeafNotes", "export failed", failure);
                runOnUiThread(() -> Toast.makeText(this, "导出失败：" + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void readNotes(android.net.Uri sourceUri) {
        worker.execute(() -> {
            try {
                byte[] bytes;
                try (java.io.InputStream in = getContentResolver().openInputStream(sourceUri)) {
                    if (in == null) throw new java.io.IOException("无法打开笔记文件");
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[16384];
                    int n, total = 0;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > 2 * 1024 * 1024) throw new java.io.IOException("笔记文件超过 2 MB 上限");
                        out.write(buffer, 0, n);
                    }
                    bytes = out.toByteArray();
                }
                final NotePackage.ImportResult result = NotePackage.importAnnotationsResult(this, id,
                        new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                        book == null ? 0 : book.size());
                runOnUiThread(() -> {
                    applySearchHighlights();
                    String text = "已导入 " + result.imported + " 条标注"
                            + (result.failed > 0 ? "，" + result.failed + " 条写入失败" : "")
                            + (result.truncated > 0 ? "，超出上限 " + result.truncated + " 条未导入" : "");
                    Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> Toast.makeText(this, "导入失败：" + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 处理类型化编辑回传：覆盖原件时登记新版本并同步 books.sha256，避免重载被哈希校验拒绝。 */
    private void handleEditOutcome(int result, Intent data) {
        if (result != RESULT_OK || data == null || id == null) return;
        String kind = data.getStringExtra(LocalTextEditorActivity.EXTRA_EDIT_KIND);
        if (kind == null) { reloadBook(); return; }
        if (ContentContracts.EditKind.SAVED_COPY.name().equals(kind)) {
            // 另存副本未改变当前书，不重载、不改哈希
            Toast.makeText(this, "已另存副本，当前书籍未改变", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ContentContracts.EditKind.CONFLICT.name().equals(kind)) {
            Toast.makeText(this, "原文件已被外部修改，未覆盖；可另存副本", Toast.LENGTH_LONG).show();
            return;
        }
        if (ContentContracts.EditKind.COVERED.name().equals(kind)) {
            String sha = data.getStringExtra(LocalTextEditorActivity.EXTRA_EDIT_SHA256);
            String uri = data.getStringExtra(LocalTextEditorActivity.EXTRA_EDIT_URI);
            long size = data.getLongExtra(LocalTextEditorActivity.EXTRA_EDIT_SIZE, 0);
            if (sha == null || sha.isEmpty() || uri == null) {
                Toast.makeText(this, "原文件已修改，但没有可登记的版本信息", Toast.LENGTH_LONG).show();
                return;
            }
            reloadBook(new ContentContracts.Revision(uri, sha, size, System.currentTimeMillis()), uri,
                    data.getStringExtra(LocalTextEditorActivity.EXTRA_EDIT_PENDING_ID),
                    data.getLongExtra(LocalTextEditorActivity.EXTRA_RESUME_SOURCE_OFFSET, -1));
        }
    }

    private void reloadBook() { reloadBook(null, null, null, -1); }

    private void reloadBook(ContentContracts.Revision revision, String uri, String pendingId, long resumeSourceOffset) {
        cancelIndexChange();
        cancelRulePreview();
        final int generation = indexGeneration;
        worker.execute(() -> {
            try {
                BookStore store = new BookStore(this);
                BookStore.Book record = store.find(id);
                final android.net.Uri sourceAtOpen = store.sourceUri(record);
                if (revision != null) {
                    if (!android.net.Uri.parse(uri).equals(sourceAtOpen))
                        throw new java.io.IOException("已保存到其他文件，当前阅读来源没有改变");
                    ServiceRegistry.of(this).revisions().register(id, uri, revision, uri);
                    if (pendingId != null) ServiceRegistry.of(this).pendingWrites().acknowledge(pendingId);
                    record = store.find(id);
                }
                final BookStore.Book updatedRecord = record;
                final String shaAtOpen = sourceAtOpen == null ? null : store.storedHash(id);
                ChapterRule appliedRule = currentRule();
                try (BookAccess access = store.open(updatedRecord)) {
                    final LazyChapterBook fresh = LazyChapterBook.open(
                            access.file, updatedRecord.format, getCacheDir(), appliedRule,
                            currentEncodingOverride(access.file),
                            TextChapterIndexer.DEFAULT_RULE_MATCH_BUDGET_MS);
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing() || generation != indexGeneration) { fresh.close(); return; }
                        currentRecord = updatedRecord;
                        displayedSource = sourceAtOpen;
                        displayedSourceSha = shaAtOpen;
                        if (book != null) book.close();
                        book = fresh;
                        progressModel = new ProgressModel(book);
                        chapter = Math.max(0, Math.min(chapter, book.size() - 1));
                        pendingOffset = Math.max(0, Math.min(pendingOffset, book.meta(chapter).charCount));
                        if (resumeSourceOffset >= 0 && book.size() > 0) {
                            // Editor offset is in normalized source text; use the same cumulative coordinates as ReadingAnchor.
                            long remaining = resumeSourceOffset;
                            for (int i = 0; i < book.size(); i++) {
                                int count = book.meta(i).charCount;
                                if (remaining < count || i + 1 == book.size()) {
                                    chapter = i;
                                    pendingOffset = (int) Math.min(remaining, count);
                                    pendingBottom = false;
                                    pendingLineFraction = 0;
                                    break;
                                }
                                remaining -= count;
                            }
                        }
                        build();
                        showChapter(pendingOffset, pendingBottom, pendingLineFraction);
                        Toast.makeText(this, "文件已更新，正文已重新载入", Toast.LENGTH_SHORT).show();
                        if (isTextFormat()) warnUnavailableRule(appliedRule);
                    });
                }
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || generation != indexGeneration) return;
                    Toast.makeText(this, "文件已保存，但重新载入失败：" + failure.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void openSearch(){
        if(book==null||restoring)return;
        save();
        launchSearch();
    }

    private void launchSearch(){
        startActivityForResult(new Intent(this,BookSearchActivity.class)
                .putExtra("bookId",id).putExtra("currentChapter",chapter).putExtra("query",searchQuery)
                .putExtra("caseSensitive",searchCaseSensitive).putExtra("scope",searchScope)
                .putExtra("searchMode",searchMode).putExtra("nearWindow",searchWindow),REQUEST_SEARCH);
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if (ExternalCssStore.handleResult(this, request, result, data)) {
            // 导入成功/失败三态随 lastStatus 常驻展示；成功即按套用结果重绘
            if (cssStatus != null) cssStatus.setText(ExternalCssStore.lastStatus());
            updateStatusVisibility(); // 失败/取消也要即时显隐，避免残留空条
            if (ExternalCssStore.enabled(this)) applyTextStyle();
            return;
        }
        if(request==REQUEST_EDIT){ handleEditOutcome(result, data); return; }
        if(request==REQUEST_EXPORT_NOTES){if(result==RESULT_OK&&data!=null&&data.getData()!=null)writeNotes(data.getData());return;}
        if(request==REQUEST_IMPORT_NOTES){if(result==RESULT_OK&&data!=null&&data.getData()!=null)readNotes(data.getData());return;}
        if (request == REQUEST_PICK_FONT) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                final android.net.Uri fontUri = data.getData();
                final String displayName = readDisplayName(fontUri);
                worker.execute(() -> {
                    final AddResult added = fonts.installFont(fontUri, displayName);
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        if (added.added) {
                            Toast.makeText(this, "已收藏字体，正文已更新", Toast.LENGTH_SHORT).show();
                            afterFontChange();
                            if (fontDialog != null && fontDialog.isShowing()) reopenFontSettings();
                        } else {
                            // 失败原子性：ReaderFonts 未改任何配置，这里只提示具体原因。
                            Toast.makeText(this, ReaderFonts.describeAddFailure(added),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                });
            }
            return;
        }
        if(request!=REQUEST_SEARCH||result!=RESULT_OK||data==null||book==null)return;
        String query=data.getStringExtra("query");int destination=data.getIntExtra("chapter",-1);
        int start=data.getIntExtra("start",-1),end=data.getIntExtra("end",-1);
        if(query==null||query.isEmpty()||query.length()>256||destination<0||destination>=book.size()
                ||start<0||end<=start||end>book.meta(destination).charCount)return;
        searchQuery=query;searchCaseSensitive=data.getBooleanExtra("caseSensitive",false);
        searchScope=data.getIntExtra("scope",-1);
        searchMode=data.getIntExtra("searchMode",BookSearch.LITERAL);
        searchWindow=data.getIntExtra("nearWindow",BookSearch.DEFAULT_NEAR_WINDOW);
        if(searchScope<-1||searchScope>=book.size())searchScope=-1;
        requestedChapter=destination;requestedStart=start;
        computeSearch(true);
    }

    private void computeSearch(boolean jump){
        if(searchFuture!=null)searchFuture.cancel(true);
        final int generation=++searchGeneration;
        final String needle=searchQuery;final boolean matchCase=searchCaseSensitive;final int scope=searchScope;
        searchFinding=true;searchControls.setVisibility(View.VISIBLE);searchStatus.setText("正在定位匹配…");
        previousMatch.setEnabled(false);nextMatch.setEnabled(false);
        searchFuture=worker.submit(()->{
            try{
                BookSearch.Result result=BookSearch.find(book,needle,matchCase,scope,SEARCH_LIMIT,
                        ()->Thread.currentThread().isInterrupted(),searchMode,searchWindow);
                runOnUiThread(()->{
                    if(isDestroyed()||generation!=searchGeneration)return;
                    searchFinding=false;searchHits=result.hits;activeMatch=-1;
                    for(int i=0;i<searchHits.size();i++){
                        BookSearch.Hit hit=searchHits.get(i);
                        if(hit.chapter==requestedChapter&&hit.start==requestedStart){activeMatch=i;break;}
                    }
                    if(activeMatch<0&&!searchHits.isEmpty())activeMatch=0;
                    if(jump&&activeMatch>=0)jumpToMatch(activeMatch);else{
                        save();int anchor=pendingOffset;boolean bottom=pendingBottom;float fraction=pendingLineFraction;
                        restoring=true;applySearchHighlights();updateSearchStatus();restore(anchor,bottom,fraction);
                    }
                });
            }catch(CancellationException ignored){}
            catch(Exception failure){runOnUiThread(()->{if(!isDestroyed()&&generation==searchGeneration){searchFinding=false;searchStatus.setText("查找失败，请重新打开结果列表重试");}});}
        });
    }

    private void updateSearchStatus(){
        if(searchControls==null)return;
        searchControls.setVisibility(searchQuery.isEmpty()?View.GONE:View.VISIBLE);
        if(searchQuery.isEmpty())return;
        String range=searchScope<0?"全书":"限定章节";
        searchStatus.setText(range+" · “"+searchQuery+"” · "+(searchHits.isEmpty()?"无匹配":(activeMatch+1)+" / "+searchHits.size())
                +(searchHits.size()>=SEARCH_LIMIT?"（最多显示10000处）":""));
        previousMatch.setEnabled(!searchFinding&&!searchHits.isEmpty());nextMatch.setEnabled(!searchFinding&&!searchHits.isEmpty());
    }

    /**
     * 章节正文来源：本地书直接读 LazyChapterBook；在线书从 chapter_cache 读，
     * 未缓存则返回骨架占位并触发后台下载，下载完成后回调刷新当前章。
     */
    private String loadChapterText(int index) throws java.io.IOException {
        if (!onlineBook || onlineBinding == null || index < 0 || index >= onlineEntries.size())
            return book.load(index);
        org.openleaf.reader.core.OnlineChapter.Entry entry = onlineEntries.get(index);
        String cacheKey = entry.cacheKey(onlineBinding.sourceId);
        String cached = onlineChapters.cachedText(id, cacheKey);
        if (cached != null) return cached;
        // 未缓存：触发下载（去重：同一章不重复发起）
        triggerChapterDownload(index, cacheKey);
        return book.load(index); // 骨架占位
    }

    private final java.util.Set<Integer> downloadingChapters = new java.util.HashSet<>();
    private void triggerChapterDownload(int index, String cacheKey) {
        synchronized (downloadingChapters) {
            if (!downloadingChapters.add(index)) return;
        }
        if (chapterLabel != null) chapterLabel.setText("正在下载本章…");
        onlineFetcher.fetch(id, index, (order, success, message) -> {
            synchronized (downloadingChapters) { downloadingChapters.remove(order); }
            if (isDestroyed()) return;
            if (success && order == chapter) {
                applySearchHighlights(); // 下载的是当前章，重新渲染（保留当前滚动位置）
                android.widget.Toast.makeText(this, "章节已下载，可离线阅读", android.widget.Toast.LENGTH_SHORT).show();
            } else if (!success) {
                android.widget.Toast.makeText(this, "下载失败：" + message, android.widget.Toast.LENGTH_SHORT).show();
            }
            // 预取后 2 章（有界），让连续阅读更顺畅
            if (success) onlineFetcher.prefetch(id, order, 2);
        });
    }

    private void applySearchHighlights(){
        if(book==null||text==null)return;
        try {
            String original=loadChapterText(chapter);
            // P1-4 唯一注入点：仅本地 TXT/MD 且用户主动开启时重排；
            // 关闭态由 ReflowUi 走 0.27.0 恒等路径（原文→简繁→缩进→标注→搜索→setText）。
            boolean reflowOn=paragraphReflowEnabled()&&isTextFormat();
            int indentPx=style.indentChars()>0?Ui.dp(this,style.indentChars()*fontSize):0;
            SpannableString marked=ReflowUi.renderWithReflow(original,reflowOn,style.convert,
                    indentPx,annotations.list(id),searchHits,chapter,activeMatch);
            text.setText(marked);
        } catch (Exception failure) {
            text.setText("");
            Toast.makeText(this,"章节读取失败："+failure.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private void moveMatch(int direction){
        if(restoring||searchFinding||searchHits.isEmpty())return;
        int index=activeMatch<0?0:(activeMatch+direction+searchHits.size())%searchHits.size();jumpToMatch(index);
    }

    private void jumpToMatch(int index){
        if(index<0||index>=searchHits.size())return;save();activeMatch=index;
        BookSearch.Hit hit=searchHits.get(index);chapter=hit.chapter;requestedChapter=chapter;requestedStart=hit.start;
        showChapter(hit.start,false,0);updateSearchStatus();
    }

    private void clearSearch(){
        if(searchFuture!=null)searchFuture.cancel(true);searchGeneration++;searchFinding=false;
        save();int offset=pendingOffset;boolean bottom=pendingBottom;float fraction=pendingLineFraction;
        searchQuery="";searchHits=new ArrayList<>();activeMatch=-1;restoring=true;
        applySearchHighlights();updateSearchStatus();restore(offset,bottom,fraction);
    }

    private void restore(int offset, boolean bottom, float fraction) {
        pendingOffset = offset;
        pendingBottom = bottom;
        pendingLineFraction = fraction;
        final int generation = ++layoutGeneration;
        final TextView restoringText = text;
        restoringText.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (isDestroyed() || text != restoringText || generation != layoutGeneration) {
                    if (restoringText.getViewTreeObserver().isAlive())
                        restoringText.getViewTreeObserver().removeOnPreDrawListener(this);
                    return true;
                }
                Layout layout = text.getLayout();
                if (layout == null || text.isLayoutRequested() || scroll.getHeight() <= 0
                        || (horizontalPages && scrollContent.isLayoutRequested())) return true;
                restoringText.getViewTreeObserver().removeOnPreDrawListener(this);
                int pos = Math.max(0, Math.min(offset, text.length()));
                int line = layout.getLineForOffset(pos);
                int lineHeight = Math.max(1, layout.getLineBottom(line) - layout.getLineTop(line));
                int y = pos == 0 && fraction == 0 ? 0 : text.getPaddingTop()
                        + layout.getLineTop(line) + Math.round(Math.max(0, Math.min(1, fraction)) * lineHeight);
                // A character anchor cannot encode the exact end-of-chapter viewport.
                // Keep this separately so progress does not jump back after reopening.
                if (bottom) y = Math.max(0, text.getHeight() - scroll.getHeight());
                if (horizontalPages) {
                    int[] bottoms = new int[layout.getLineCount()];
                    for (int i = 0; i < bottoms.length; i++) bottoms[i] = layout.getLineBottom(i);
                    readerPages = ReaderPaging.fromLineBottoms(bottoms,
                            scroll.getHeight() - text.getPaddingTop() - text.getPaddingBottom());
                    pageIndex = bottom ? readerPages.count() - 1 : readerPages.pageForLine(line);
                    int first = readerPages.firstLine(pageIndex);
                    pageAnchor = bottom ? layout.getLineStart(first) : pos;
                    showPageFrame(layout);
                    int enterDirection = enterPageDirection;
                    enterPageDirection = 0;
                    if (enterDirection != 0) scroll.enter(enterDirection, pageMotion);
                } else {
                    enterPageDirection = 0;
                    scroll.scrollTo(0, y);
                }
                scroll.post(() -> {
                    if (isDestroyed() || text != restoringText || generation != layoutGeneration) return;
                    restoring = false;
                    save();
                });
                return true;
            }
        });
        text.requestLayout();
    }

    /**
     * TXT/MD 的真实源码字符偏移 = 之前各章 charCount 累加。TextChapterIndexer.sourceStart
     * 实际是「上一章的长度」而非累加值（8 章长度 1697/4397/1117/7397/2897… 时第 5 章
     * sourceStart=7397，真实累加起点却是 14608），直接拿它做锚点会 resolve 到靠前的章。
     * 用 charCount 累加得到的偏移对同一份解码文本跨编码、跨分章规则都不变。
     */
    private static long cumulativeSourceOffset(LazyChapterBook target, int chapterIndex) {
        if (target == null || chapterIndex < 0 || chapterIndex >= target.size()) return -1;
        long sum = 0;
        for (int i = 0; i < chapterIndex; i++) sum += Math.max(0, target.meta(i).charCount);
        return sum;
    }

    private long cumulativeSourceOffset(int chapterIndex) {
        return cumulativeSourceOffset(book, chapterIndex);
    }

    /** 锚点源码偏移统一改用累加口径；sourceStart < 0（EPUB 等）维持 -1。 */
    private long anchorSourceOffset(int chapterIndex, int effective) {
        long base = cumulativeSourceOffset(chapterIndex);
        return base < 0 ? -1 : base + Math.max(0, effective);
    }

    /**
     * 还原 ReadingAnchor 到当前书的章号/章内偏移。新锚点（累加口径）用源码偏移定位，
     * 跨编码/跨分章规则都落在同一段正文；老版本与听书服务写下的非累加 sourceStart 锚点
     * 与新口径不自洽，退回 chapterKey / ordinal + chapterCharOffset。
     */
    private int resolveAnchor(ReadingAnchor anchor, int[] offsetOut) {
        if (anchor == null || book == null || book.size() == 0) {
            offsetOut[0] = 0;
            return 0;
        }
        int ordinal = Math.max(0, Math.min(anchor.chapterOrdinal, book.size() - 1));
        long source = anchor.sourceCharOffset;
        // 老版本/听书服务的锚点：source = section.sourceStart + chapterCharOffset，
        // 与 chapterKey 的 src:<sourceStart> 相减恰好等于章内偏移；新锚点（真实累加偏移）不自洽。
        boolean legacySource = false;
        // 前两章的累加起点与 sourceStart 恰好相同，这类锚点按新口径解析即可
        if (source >= 0 && source < cumulativeSourceOffset(ordinal)
                && anchor.chapterKey != null && anchor.chapterKey.startsWith("src:")) {
            try {
                long keySource = Long.parseLong(anchor.chapterKey.substring(4));
                if (source - keySource == anchor.chapterCharOffset) legacySource = true;
            } catch (NumberFormatException ignored) { /* key 不是数值时按新锚点处理 */ }
        }
        if (source >= 0 && !legacySource) {
            long cum = 0;
            for (int i = 0; i < book.size(); i++) {
                long count = Math.max(0, book.meta(i).charCount);
                if (source == cum + count && anchor.atEnd) {
                    offsetOut[0] = (int) count;
                    return i;
                }
                if (source >= cum && source < cum + count) {
                    offsetOut[0] = Math.max(0, Math.min((int) (source - cum), (int) count));
                    return i;
                }
                cum += count;
            }
        }
        if (anchor.chapterKey != null && !anchor.chapterKey.isEmpty()) {
            // 老锚点的 src:<sourceStart> 可能重复：超长章切分后各片共享同一 key。
            // 取与 chapterOrdinal 最近的匹配；等距时优先容得下章内偏移的那片，仍平局取较小下标。
            int best = -1;
            for (int i = 0; i < book.size(); i++) {
                if (!anchor.chapterKey.equals(book.meta(i).chapterKey)) continue;
                if (best < 0) { best = i; continue; }
                int bestDistance = Math.abs(best - ordinal), distance = Math.abs(i - ordinal);
                boolean bestFits = book.meta(best).charCount >= anchor.chapterCharOffset;
                boolean fits = book.meta(i).charCount >= anchor.chapterCharOffset;
                if (distance < bestDistance
                        || (distance == bestDistance
                            && ((fits && !bestFits) || (fits == bestFits && i < best)))) {
                    best = i;
                }
            }
            if (best >= 0) {
                offsetOut[0] = Math.max(0, Math.min(anchor.chapterCharOffset, book.meta(best).charCount));
                return best;
            }
        }
        if (anchor.spineIndex >= 0) {
            for (int i = 0; i < book.size(); i++) {
                if (book.meta(i).spineIndex == anchor.spineIndex) {
                    offsetOut[0] = Math.max(0, Math.min(anchor.chapterCharOffset, book.meta(i).charCount));
                    return i;
                }
            }
        }
        offsetOut[0] = Math.max(0, Math.min(anchor.chapterCharOffset, book.meta(ordinal).charCount));
        return ordinal;
    }

    private void save() {
        if (book == null || text == null || restoring || text.getLayout() == null) return;
        Layout layout = text.getLayout();
        int y = Math.max(0, scroll.getScrollY() - text.getPaddingTop());
        int line = layout.getLineForVertical(y);
        int current = layout.getLineStart(line);
        if (horizontalPages && readerPages == null) return;
        if (horizontalPages) {
            // Preserve the raw character within this page across layout changes until a real page turn.
            current = pageAnchor;
            pendingLineFraction = 0;
            pendingBottom = false;
        } else {
            pendingLineFraction = Math.max(0, Math.min(1, (y - layout.getLineTop(line))
                    / (float) Math.max(1, layout.getLineBottom(line) - layout.getLineTop(line))));
            pendingBottom = text.getHeight() > 0 && scroll.getHeight() > 0
                    && scroll.getScrollY() + scroll.getHeight() >= text.getHeight() - Ui.dp(this, 4);
        }
        pendingOffset = current;
        LazyChapterBook.Section section=book.meta(chapter);
        int effective=pendingBottom?section.charCount:current;
        long source=section.sourceStart>=0?anchorSourceOffset(chapter,effective):-1;
        ReadingAnchor anchor=ReadingAnchor.text(section.chapterKey,chapter,section.spineIndex,
                source,effective,pendingBottom,pendingLineFraction);
        int percent=progressModel.percent(chapter,current,pendingBottom);
        SharedPreferences.Editor position = prefs.edit().putInt("chapter_" + id, chapter).putInt("offset_" + id, current)
                .putBoolean("bottom_" + id, pendingBottom).putFloat("lineFraction_" + id, pendingLineFraction)
                .putInt("progress_" + id, percent).putString("anchor_" + id,anchor.serialize());
        if (section.sourceStart >= 0) position.putInt("text_layout_version_" + id, 1);
        position.apply();
        int remainingChars = section.charCount - effective;
        if (horizontalPages) {
            progress.setText("本章第 " + (pageIndex + 1) + " / " + readerPages.count() + " 页 · 全书 " + percent + "%");
        } else {
            progress.setText("全书 " + percent + "%  ·  " + fontSize + " 号字"
                    + "  ·  " + ReadingPace.chapterFooterLabel(remainingChars, 400));
        }
    }

    private boolean supportsPaging() { return isTextFormat() && !onlineBook; }

    private void syncPageScrollRange() {
        // Allow the final short page to start at its own line rather than clamping to the previous page.
        int reserve = horizontalPages ? scroll.getHeight() : 0;
        if (scrollContent.getPaddingBottom() != reserve) scrollContent.setPadding(0, 0, 0, reserve);
    }

    private void refreshPageControls() {
        if (previousRead == null || nextRead == null) return;
        boolean hasPrevious = chapter > 0 || (readerPages != null && pageIndex > 0);
        boolean hasNext = book != null && (chapter + 1 < book.size()
                || (readerPages != null && pageIndex + 1 < readerPages.count()));
        previousRead.setText(horizontalPages ? "上一页" : "上一章");
        previousRead.setContentDescription(horizontalPages ? "上一页" : "上一章");
        nextRead.setText(horizontalPages ? "下一页" : "下一章");
        nextRead.setContentDescription(horizontalPages ? "下一页" : "下一章");
        previousRead.setEnabled(!horizontalPages || hasPrevious);
        nextRead.setEnabled(!horizontalPages || hasNext);
    }

    private void showPageFrame(Layout layout) {
        int first = readerPages.firstLine(pageIndex);
        int end = readerPages.endLine(pageIndex);
        int top = layout.getLineTop(first);
        scroll.scrollTo(0, top);
        int bottom = end == first ? text.getPaddingTop()
                : text.getPaddingTop() + layout.getLineBottom(end - 1) - top;
        scroll.frame(bottom, pageIndex > 0 || chapter > 0,
                pageIndex + 1 < readerPages.count() || chapter + 1 < book.size());
        refreshPageControls();
    }

    private void turnFromFooter(int direction) {
        if (horizontalPages) turnPage(direction);
        else move(direction);
    }

    private boolean turnPage(int direction) {
        if (!horizontalPages || restoring || readerSelectionActive || readerPages == null || book == null)
            return false;
        int next = pageIndex + direction;
        if (next < 0 || next >= readerPages.count()) {
            int target = chapter + direction;
            if (target < 0 || target >= book.size()) return false;
            save();
            noteInteraction();
            chapter = target;
            enterPageDirection = direction;
            showChapter(0, direction < 0, 0);
        } else {
            Layout layout = text.getLayout();
            if (layout == null || text.isLayoutRequested()) return false;
            pageIndex = next;
            pageAnchor = layout.getLineStart(readerPages.firstLine(next));
            showPageFrame(layout);
            scroll.enter(direction, pageMotion);
            save();
            noteInteraction();
        }
        return true;
    }

    private void setHorizontalPages(boolean next) {
        if (book == null || !supportsPaging() || restoring || horizontalPages == next) return;
        save();
        int anchor = pendingOffset;
        float fraction = pendingLineFraction;
        boolean bottom = pendingBottom;
        restoring = true;
        horizontalPages = next;
        prefs.edit().putBoolean("readerHorizontalPages", next).apply();
        readerPages = null;
        scroll.setPaged(next);
        scroll.setVerticalScrollBarEnabled(!next);
        syncPageScrollRange();
        setChromeVisible(true);
        refreshPageControls();
        restore(anchor, bottom, fraction);
    }

    private void move(int direction) {
        if (restoring) return;
        int next = chapter + direction;
        if (next < 0 || next >= book.size()) {
            Toast.makeText(this, direction < 0 ? "已经是第一章" : "已经是最后一章", Toast.LENGTH_SHORT).show();
            return;
        }
        save();
        chapter = next;
        showChapter(0, false, 0);
    }

    /** 仅过滤目录标题；定位当前章不改变正文的阅读位置。 */
    private void contents() {
        if (book == null) return;
        final boolean textFormat = isTextFormat();
        final int total = book.size();
        final String[] titles = new String[total];
        for (int i = 0; i < total; i++) titles[i] = chapterName(i);
        final List<ChapterOutline.Node> nodes = ChapterOutline.build(java.util.Arrays.asList(titles));
        final int[] visible = ChapterOutline.visible(nodes, null);

        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 12);
        final EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("输入章节标题关键词");
        search.setContentDescription("目录搜索框");
        panel.addView(search, new LinearLayout.LayoutParams(-1, -2));
        final TextView matchStatus = Ui.text(this, "", 13, Ui.MUTED);
        final ListView list = new ListView(this);
        list.setContentDescription("章节列表");
        final List<Integer> rowMapping = new ArrayList<>();
        if (textFormat) {
            LinearLayout statusRow = Ui.row(this);
            matchStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            statusRow.addView(matchStatus, Ui.weight());
            statusRow.addView(Ui.button(this, "定位当前章", () -> {
                if (search.length() > 0) search.setText("");
                int row = rowMapping.indexOf(chapter);
                if (row >= 0) list.setSelection(row);
            }, "在目录列表中定位当前章"));
            panel.addView(statusRow);
        }
        panel.addView(list, new LinearLayout.LayoutParams(-1, Ui.dp(this, 380)));

        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("目录")
                .setView(panel).setNegativeButton("关闭", null);
        if (textFormat) builder.setNeutralButton("章节识别规则…", (d, which) -> {
            d.dismiss();
            openChapterRuleSettings();
        });
        final AlertDialog dialog = builder.create();
        final Runnable refresh = () -> {
            String query = search.getText().toString();
            int[] filtered = TocFilter.filterVisible(nodes, visible, query);
            int currentRow = TocFilter.positionOf(filtered, chapter);
            List<String> labels = new ArrayList<>();
            rowMapping.clear();
            if (!textFormat) {
                labels.add("章节识别规则…");
                rowMapping.add(-1);
            }
            for (int index : filtered) {
                if (index < 0 || index >= nodes.size()) continue;
                ChapterOutline.Node node = nodes.get(index);
                String indent = node.depth > 0 ? "　　" : "";
                labels.add(indent + node.title + (index == chapter ? "  ● 当前" : ""));
                rowMapping.add(index);
            }
            if (textFormat) matchStatus.setText(query.trim().isEmpty() ? "共 " + visible.length + " 章"
                    : filtered.length == 0 ? "未找到匹配章节"
                    : "匹配 " + filtered.length + " / " + visible.length + " 章");
            list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels));
            if (currentRow >= 0) list.setSelection(currentRow + (textFormat ? 0 : 1));
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { refresh.run(); }
        });
        list.setOnItemClickListener((parent, view, position, row) -> {
            if (position < 0 || position >= rowMapping.size()) return;
            int index = rowMapping.get(position);
            if (index < 0) { dialog.dismiss(); openChapterRuleSettings(); return; }
            if (!textFormat || index != chapter) {
                save();
                chapter = index;
                showChapter(0, false, 0);
            }
            dialog.dismiss();
        });
        refresh.run();
        dialog.show();
    }

    /** P1-4 覆盖边界：仅本地 TXT/MD 正文 TextView 套用重排。 */
    private boolean isTextFormat() {
        String format = currentRecord == null ? "" : currentRecord.format;
        return "txt".equals(format) || "md".equals(format);
    }

    /** P1-4 全局开关，默认关（0.27.0 渲染零回归）。 */
    private boolean paragraphReflowEnabled() {
        return prefs.getBoolean("paragraph_reflow", false);
    }

    private int currentEncodingIndex() {
        int index = prefs.getInt("text_encoding_" + id, 0);
        return index < 0 || index > 4 ? 0 : index;
    }

    private Charset currentEncodingOverride(java.io.File source) {
        return encodingOverride(source, TextEncoding.fromIndex(currentEncodingIndex()));
    }

    /** 后台调用：UTF-16 按源文件实际 BOM 定 LE/BE；无 BOM 先按 LE（启发式）。 */
    private static Charset encodingOverride(java.io.File source, TextEncoding.Mode mode) {
        if (mode == TextEncoding.Mode.UTF_16) {
            byte[] prefix = new byte[2];
            int read = 0;
            try (java.io.InputStream in = new java.io.FileInputStream(source)) {
                while (read < prefix.length) {
                    int n = in.read(prefix, read, prefix.length - read);
                    if (n <= 0) break;
                    read += n;
                }
            } catch (Exception ignored) { /* 读不到前缀就按无 BOM 的 LE 处理 */ }
            return TextEncoding.resolveUtf16(prefix);
        }
        return TextEncoding.charsetOf(mode);
    }

    private String encodingModeLabel() {
        switch (TextEncoding.fromIndex(currentEncodingIndex())) {
            case UTF_8: return "UTF-8";
            case GB18030: return "GB18030";
            case UTF_16: return "UTF-16";
            case BIG5: return "Big5";
            case AUTO:
            default: return "自动检测";
        }
    }

    /** P1-3 设置入口：按书持久化覆盖值，重开书并恢复原位；仅 TXT/MD 生效。 */
    private void openEncodingSettings() {
        if (!isTextFormat()) {
            Toast.makeText(this, "文本编码仅对 TXT / MD 正文生效", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] options = {"自动检测（默认）", "UTF-8", "GB18030", "UTF-16", "Big5"};
        // material 主题下 setMessage() 会独占 contentPanel，把 setSingleChoiceItems 的列表挤出可视区；
        // 提示语并入 title（titlePanel 与 contentPanel 分离），保证 5 个选项真实可见可点。
        new AlertDialog.Builder(this)
                .setTitle("文本编码\n仅重解码显示、不改源文件；切换后回到原阅读位置")
                .setSingleChoiceItems(options, currentEncodingIndex(), (d, which) -> {
                    prefs.edit().putInt("text_encoding_" + id, which).apply();
                    d.dismiss();
                    reopenWithEncoding(which);
                })
                .setNegativeButton("取消", null).show();
    }

    private void reopenWithEncoding(int which) {
        reopenTextIndex(currentRule(), TextEncoding.fromIndex(which), false,
                () -> prefs.edit().putInt("text_encoding_" + id, which).apply());
    }

    /** P1-3：按书覆盖存在性标记；显式保守档（mode=0）与「无覆盖跟随全局」靠它区分。旧书不迁移。 */
    private boolean hasBookRuleOverride() {
        return id != null && prefs.contains("chapter_rule_override_" + id);
    }

    /** 全局规则键只读，作为未覆盖书籍的默认值来源；按书应用绝不写全局键。 */
    private ChapterRule globalRule() {
        int mode = prefs.getInt("chapter_rule_mode", 0);
        return ChapterRule.of(modeFromIndex(clampRuleMode(mode)),
                prefs.getString("chapter_rule_custom", ""));
    }

    /** 从 reader prefs 构造当前章节规则；有按书覆盖读按书键，否则回落全局键。 */
    private ChapterRule currentRule() {
        if (!hasBookRuleOverride()) return globalRule();
        return ChapterRule.of(modeFromIndex(currentRuleMode()), currentRuleCustom());
    }

    private void warnUnavailableRule(ChapterRule rule) {
        if (rule.hasUnavailableCustomPattern()) {
            Toast.makeText(this, "已保存的自定义正则在此版本不可用，当前目录仅使用保守规则；可在章节识别规则中修改",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static int clampRuleMode(int mode) {
        return mode < 0 || mode > 3 ? 0 : mode;
    }

    private int currentRuleMode() {
        String key = hasBookRuleOverride() ? "chapter_rule_mode_" + id : "chapter_rule_mode";
        return clampRuleMode(prefs.getInt(key, 0));
    }

    private String currentRuleCustom() {
        String key = hasBookRuleOverride() ? "chapter_rule_custom_" + id : "chapter_rule_custom";
        String custom = prefs.getString(key, "");
        return custom == null ? "" : custom;
    }

    /** 重建成功后才落按书覆盖：标记最后写，避免半事务状态。 */
    private void persistBookRule(ChapterRule rule) {
        prefs.edit()
                .putInt("chapter_rule_mode_" + id, rule.mode().ordinal())
                .putString("chapter_rule_custom_" + id, rule.customRegex() == null ? "" : rule.customRegex())
                .putBoolean("chapter_rule_override_" + id, true)
                .apply();
    }

    /** 恢复跟随全局：同样先重建成功，再删按书键；绝不写全局键。 */
    private void clearBookRule() {
        prefs.edit()
                .remove("chapter_rule_mode_" + id)
                .remove("chapter_rule_custom_" + id)
                .remove("chapter_rule_override_" + id)
                .apply();
    }

    private static ChapterRule.Mode modeFromIndex(int index) {
        ChapterRule.Mode[] modes = ChapterRule.Mode.values();
        return modes[index >= 0 && index < modes.length ? index : 0];
    }

    private static String[] modeButtonLabels(int selected) {
        String[] names = {"保守", "标准", "宽松", "自定义"};
        String[] labels = new String[names.length];
        for (int i = 0; i < names.length; i++) labels[i] = (i == selected ? "● " : "○ ") + names[i];
        return labels;
    }

    private static void refreshModeButtons(Button[] buttons, int selected) {
        String[] labels = modeButtonLabels(selected);
        for (int i = 0; i < buttons.length; i++) buttons[i].setText(labels[i]);
    }

    /** 章节规则设置：四档单选 + 自定义正则 + 实时命中预览 + 应用并重建目录。 */
    private void openChapterRuleSettings() {
        if (book == null) return;
        String format = currentRecord == null ? "" : currentRecord.format;
        if (!("txt".equals(format) || "md".equals(format))) {
            Toast.makeText(this, "章节识别规则仅对 TXT / MD 正文生效", Toast.LENGTH_SHORT).show();
            return;
        }
        final int[] selected = {currentRuleMode()};
        final int[] sampleChapter = {0};
        String stored = currentRuleCustom();
        final EditText regexInput = new EditText(this);
        regexInput.setSingleLine(true);
        regexInput.setHint("自定义正则，例如 ^第\\d+章");
        regexInput.setText(stored == null ? "" : stored);
        regexInput.setContentDescription("自定义章节正则");
        final TextView validation = Ui.text(this, "", 12, Ui.MUTED);
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        final ChapterRule[] readyRule = {null};

        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 18);
        panel.addView(Ui.text(this, "范围：仅本书　默认来自：全局规则", 13, Ui.MUTED));
        panel.addView(Ui.text(this, "自定义档补充保守判定。Android 仅支持简单正则，如 ^第\\d+章。", 12, Ui.MUTED));
        Ui.gap(panel, 8);
        final TextView preview = Ui.text(this, "命中预览：计算中…", 14, Ui.MUTED);
        final TextView sampleLabel = Ui.text(this, "样本：第 1 章（源文件解码）", 13, Ui.MUTED);
        LinearLayout sampleRow = Ui.row(this);
        sampleRow.addView(sampleLabel, Ui.weight());
        sampleRow.addView(Ui.button(this, "取其他章节作为样本", () -> {
            if (book == null || book.size() <= 1) return;
            sampleChapter[0] = (sampleChapter[0] + 1) % book.size();
            sampleLabel.setText(sampleChapter[0] == 0
                    ? "样本：第 1 章（源文件解码）"
                    : "样本：第 " + (sampleChapter[0] + 1) + " 章（当前索引缓存）");
            scheduleRulePreview(dialogHolder[0], preview, validation, readyRule, selected[0],
                    regexInput.getText().toString(), sampleChapter[0]);
        }, "取其他章节作为章节规则样本"));
        panel.addView(sampleRow);
        Ui.gap(panel, 6);
        panel.addView(preview);
        Ui.gap(panel, 8);
        LinearLayout modes = Ui.row(this);
        Button[] buttons = new Button[4];
        for (int i = 0; i < buttons.length; i++) {
            final int which = i;
            buttons[i] = Ui.button(this, modeButtonLabels(selected[0])[i], () -> {
                selected[0] = which;
                refreshModeButtons(buttons, selected[0]);
                scheduleRulePreview(dialogHolder[0], preview, validation, readyRule, which,
                        regexInput.getText().toString(), sampleChapter[0]);
            }, "章节规则档位" + which);
            modes.addView(buttons[i], Ui.weight());
        }
        panel.addView(modes);
        Ui.gap(panel, 8);
        panel.addView(regexInput, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(validation);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("章节识别规则（保守档与 0.26.0 一致）")
                .setView(panel)
                .setPositiveButton("应用并重建目录", null)
                .setNeutralButton("恢复跟随全局", null)
                .setNegativeButton("取消", null)
                .create();
        dialogHolder[0] = dialog;
        dialog.setOnDismissListener(d -> cancelRulePreview());
        dialog.setOnShowListener(d -> {
            regexInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable s) {
                    scheduleRulePreview(dialog, preview, validation, readyRule, selected[0],
                            s.toString(), sampleChapter[0]);
                }
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                ChapterRule rule = readyRule[0];
                if (rule == null) return;
                dialog.dismiss();
                rebuildIndexAndOutline(rule, () -> persistBookRule(rule));
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (!hasBookRuleOverride()) {
                    dialog.dismiss();
                    Toast.makeText(this, "当前已跟随全局规则", Toast.LENGTH_SHORT).show();
                    return;
                }
                ChapterRule fallback = globalRule();
                dialog.dismiss();
                // 同样先重建成功，再删按书键；删除失败时本书继续使用原覆盖。
                rebuildIndexAndOutline(fallback, this::clearBookRule);
            });
            scheduleRulePreview(dialog, preview, validation, readyRule, selected[0],
                    regexInput.getText().toString(), sampleChapter[0]);
        });
        dialog.show();
    }

    private void cancelRulePreview() {
        rulePreviewGeneration++;
        if (rulePreviewTask != null) rulePreviewHandler.removeCallbacks(rulePreviewTask);
        rulePreviewTask = null;
        if (rulePreviewFuture != null) rulePreviewFuture.cancel(true);
        rulePreviewFuture = null;
    }

    /** 先废弃旧结果，再防抖；编译和取样均不上 UI 线程，也不阻塞目录重建。 */
    private void scheduleRulePreview(AlertDialog dialog, TextView label, TextView validation,
                                     ChapterRule[] readyRule, int mode, String regex, int sampleChapterIndex) {
        cancelRulePreview();
        if (book == null || isFinishing() || isDestroyed() || !dialog.isShowing()) return;
        final int generation = rulePreviewGeneration;
        final LazyChapterBook sampleBook = book;
        final String bookId = id;
        final int sampleIndex = Math.max(0, Math.min(sampleChapterIndex, sampleBook.size() - 1));
        final TextEncoding.Mode encoding = TextEncoding.fromIndex(currentEncodingIndex());
        readyRule[0] = null;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        validation.setText("");
        label.setText("命中预览：计算中…");
        rulePreviewTask = () -> {
            rulePreviewTask = null;
            rulePreviewFuture = rulePreviewWorker.submit(() -> {
                ChapterRule.Mode ruleMode = modeFromIndex(mode);
                String error = ruleMode == ChapterRule.Mode.CUSTOM
                        ? ChapterRule.conservative().validateRegex(regex) : null;
                ChapterRule rule = error == null ? ChapterRule.of(ruleMode, regex) : null;
                runOnUiThread(() -> {
                    if (!isRulePreviewCurrent(dialog, generation)) return;
                    readyRule[0] = rule;
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(rule != null);
                    if (error != null) {
                        validation.setText(error);
                        label.setText("修正规则后查看预览");
                    }
                });
                if (rule == null || Thread.currentThread().isInterrupted()) return;
                String sample;
                ChapterRule.Preview result;
                try {
                    if (sampleIndex == 0) {
                        BookStore store = new BookStore(this);
                        try (BookAccess access = store.open(store.find(bookId))) {
                            sample = TextChapterIndexer.readSample(access.file,
                                    encodingOverride(access.file, encoding), RULE_PREVIEW_SAMPLE_CHARS);
                        }
                    } else {
                        sample = sampleBook.head(sampleIndex, RULE_PREVIEW_SAMPLE_CHARS);
                    }
                    if (Thread.currentThread().isInterrupted()) return;
                    result = rule.preview(sample, RULE_PREVIEW_MAX_LINES, 4);
                } catch (CancellationException cancelled) {
                    return;
                } catch (Exception failure) {
                    runOnUiThread(() -> {
                        if (isRulePreviewCurrent(dialog, generation)) label.setText("无法读取样本，建议换样本");
                    });
                    return;
                }
                runOnUiThread(() -> {
                    if (isRulePreviewCurrent(dialog, generation)) label.setText(formatRulePreview(result, rule.isCustom()));
                });
            });
        };
        rulePreviewHandler.postDelayed(rulePreviewTask, RULE_PREVIEW_DEBOUNCE_MS);
    }

    private boolean isRulePreviewCurrent(AlertDialog dialog, int generation) {
        return generation == rulePreviewGeneration && !isDestroyed() && !isFinishing() && dialog.isShowing();
    }

    /** 区分保守基线与扩展档新增；零命中只建议换样本，不宣称「全书无目录」。 */
    private static String formatRulePreview(ChapterRule.Preview result, boolean custom) {
        if (result.totalMatches == 0) return "当前样本未命中，建议换样本或继续用自动档";
        String addedLabel = custom ? "自定义新增" : "扩展档新增";
        StringBuilder text = new StringBuilder();
        text.append("命中预览：保守规则命中 ").append(result.conservativeMatches)
                .append(" 行 / ").append(addedLabel).append(' ').append(result.addedMatches).append(" 行");
        text.append("\n示例（最多 4 条）：");
        for (String example : result.examples) text.append("\n· ").append(example);
        return text.toString();
    }

    private void rebuildIndexAndOutline(ChapterRule rule, Runnable onSuccess) {
        reopenTextIndex(rule, TextEncoding.fromIndex(currentEncodingIndex()), true, onSuccess);
    }

    /** 编码与规则只能由同一代请求提交；保留原来的源锚点和已提交设置直到打开成功。 */
    private void reopenTextIndex(ChapterRule rule, TextEncoding.Mode encoding, boolean rebuild,
                                 Runnable onSuccess) {
        if (book == null || currentRecord == null || id == null || isFinishing() || isDestroyed()) return;
        cancelIndexChange();
        cancelRulePreview();
        save();
        final int generation = indexGeneration;
        final String bookId = id;
        final long oldSourceStart = cumulativeSourceOffset(chapter);
        final ReadingAnchor savedAnchor = ReadingAnchor.parse(prefs.getString("anchor_" + id, null));
        final int oldOffset = pendingOffset;
        final boolean oldBottom = pendingBottom;
        final float oldFraction = pendingLineFraction;
        indexDialog = new AlertDialog.Builder(this)
                .setTitle(rebuild ? "正在重建目录…" : "正在切换文本编码…")
                .setMessage("取消将保留当前目录和阅读位置。")
                .setNegativeButton("取消", null)
                .setOnCancelListener(d -> { if (generation == indexGeneration) cancelIndexChange(); })
                .create();
        indexDialog.setCanceledOnTouchOutside(false);
        indexDialog.show();
        indexDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> cancelIndexChange());
        indexFuture = worker.submit(() -> {
            LazyChapterBook opened = null;
            try {
                BookStore store = new BookStore(this);
                BookStore.Book record = store.find(bookId);
                try (BookAccess access = store.open(record)) {
                    if (Thread.currentThread().isInterrupted()) return;
                    Charset override = encodingOverride(access.file, encoding);
                    opened = LazyChapterBook.open(access.file, record.format, getCacheDir(), rule, override,
                            TextChapterIndexer.DEFAULT_RULE_MATCH_BUDGET_MS);
                }
            } catch (Exception failure) {
                if (opened != null) opened.close();
                runOnUiThread(() -> {
                    if (generation != indexGeneration || isDestroyed() || isFinishing()) return;
                    finishIndexChange();
                    Toast.makeText(this, (rebuild ? "重建目录失败：" : "切换编码失败：") + failure.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
                return;
            }
            final LazyChapterBook fresh = opened;
            if (Thread.currentThread().isInterrupted()) { fresh.close(); return; }
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || generation != indexGeneration) {
                    fresh.close();
                    return;
                }
                finishIndexChange();
                book.close();
                book = fresh;
                progressModel = new ProgressModel(book);
                int[] mapped = new int[]{oldOffset};
                if (savedAnchor != null) {
                    chapter = Math.max(0, resolveAnchor(savedAnchor, mapped));
                } else {
                    chapter = 0;
                    for (int i = 0; i < book.size() && cumulativeSourceOffset(book, i) <= oldSourceStart; i++) {
                        chapter = i;
                    }
                    mapped[0] = Math.max(0, (int) (oldSourceStart - cumulativeSourceOffset(book, chapter)));
                }
                chapter = Math.max(0, Math.min(chapter, book.size() - 1));
                pendingOffset = Math.max(0, Math.min(mapped[0], book.meta(chapter).charCount));
                pendingBottom = oldBottom;
                pendingLineFraction = oldFraction;
                onSuccess.run();
                build();
                showChapter(pendingOffset, pendingBottom, pendingLineFraction);
                if (rebuild) save();
                Toast.makeText(this, rebuild ? "章节目录已按新规则重建，共 " + book.size() + " 章"
                                : "已按 " + encodingModeLabel() + " 重新打开，仍在原位置",
                        Toast.LENGTH_SHORT).show();
                warnUnavailableRule(rule);
            });
        });
    }

    private void finishIndexChange() {
        indexFuture = null;
        if (indexDialog != null) indexDialog.dismiss();
        indexDialog = null;
    }

    private void cancelIndexChange() {
        indexGeneration++;
        if (indexFuture != null) indexFuture.cancel(true);
        finishIndexChange();
    }

    private void addHighlightFromSelection(boolean withNote) {
        if (book == null || text == null) return;
        int start = text.getSelectionStart(), end = text.getSelectionEnd();
        if (end <= start) { Toast.makeText(this, "请先长按选择文字", Toast.LENGTH_SHORT).show(); return; }
        String quote = text.getText().subSequence(start, end).toString();
        LazyChapterBook.Section section = book.meta(chapter);
        final AnnotationStore.Anchor anchor = new AnnotationStore.Anchor(chapter, start, end,
                section.spineIndex, section.sourceStart >= 0 ? section.sourceStart + start : -1, quote);
        int defaultColor = prefs.getInt("annotColor", AnnotationStore.COLORS[0]);
        String defaultStyle = prefs.getString("annotStyle", AnnotationStore.STYLE_HIGHLIGHT);
        if (!withNote) {
            boolean saved = annotations.insert(id, anchor, AnnotationStore.TYPE_HIGHLIGHT,
                    defaultColor, "", defaultStyle) != null;
            Toast.makeText(this, saved ? "已添加标注" : "标注保存失败", Toast.LENGTH_SHORT).show();
            if (saved) applySearchHighlights();
            return;
        }
        android.widget.EditText note = new android.widget.EditText(this);
        note.setHint("笔记（可留空）");
        new AlertDialog.Builder(this).setTitle("添加笔记").setView(note)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    boolean saved = annotations.insert(id, anchor, AnnotationStore.TYPE_HIGHLIGHT, defaultColor,
                            note.getText().toString().trim(), defaultStyle) != null;
                    Toast.makeText(this, saved ? "已保存笔记" : "笔记保存失败", Toast.LENGTH_SHORT).show();
                    if (saved) applySearchHighlights();
                }).show();
    }

    private int annotSort = AnnotationStore.SORT_TIME;
    private int annotColorFilter = -1;
    private boolean annotCurrentChapterOnly = true;

    /** 阶段 3-A（T1=A'）：AI 单章摘要（用户自带 key、默认关、显式触发、失败降级规则式）。 */
    private void openAiPanel() {
        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 18);
        panel.addView(Ui.text(this, org.openleaf.reader.core.AiAssist.PRIVACY_NOTICE, 13, Ui.MUTED));
        Ui.gap(panel, 12);
        final boolean[] on = {AiClient.enabled(this)};
        final Button[] toggle = new Button[1];
        toggle[0] = Ui.button(this, "AI 摘要：" + (on[0] ? "开" : "关"), () -> {
            on[0] = !on[0];
            toggle[0].setText("AI 摘要：" + (on[0] ? "开" : "关"));
        }, "切换 AI 摘要");
        panel.addView(toggle[0]);
        final EditText keyField = new EditText(this);
        keyField.setHint("API key（仅存本机，不进备份）");
        keyField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setText(AiClient.apiKey(this));
        panel.addView(keyField);
        final EditText endpointField = new EditText(this);
        endpointField.setHint("接口地址（可自建兼容端点）");
        endpointField.setSingleLine(true);
        endpointField.setText(AiClient.endpoint(this));
        panel.addView(endpointField);
        panel.addView(Ui.button(this, "清除已保存的 key", () -> {
            AiClient.clearKey(this); keyField.setText("");
            Toast.makeText(this, "已清除 API key", Toast.LENGTH_SHORT).show();
        }, "清除 API key"));
        new android.app.AlertDialog.Builder(this)
                .setTitle("AI 单章摘要（实验性）")
                .setView(panel)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并总结本章", (d, w) -> {
                    AiClient.save(this, on[0], keyField.getText().toString(), endpointField.getText().toString());
                    summarizeCurrentChapter();
                }).show();
    }

    /** 请求当前章摘要；后台线程网络，UI 线程展示；失败/未开启降级到规则式首句。 */
    private void summarizeCurrentChapter() {
        final String head = text == null ? "" : text.getText().toString();
        final String ruleLead = org.openleaf.reader.core.DigestBuilder.lead(
                chapterName(chapter), head, org.openleaf.reader.core.DigestBuilder.DEFAULT_LEAD_CHARS);
        if (!AiClient.enabled(this) || AiClient.apiKey(this).isEmpty()) {
            showAiResult("未开启或未提供 API key", "已在设置中保存；开启并填入 key 后可用。");
            return;
        }
        Toast.makeText(this, "正在请求 AI 摘要…", Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            String result = AiClient.summarize(ReaderActivity.this, head);
            final String finalResult = result;
            final String fallback = org.openleaf.reader.core.AiAssist.fallback(ruleLead);
            runOnUiThread(() -> {
                if (finalResult != null && !finalResult.isEmpty())
                    showAiResult("AI 摘要（本章）", finalResult);
                else
                    showAiResult("AI 不可用，已用规则式提要", fallback.isEmpty() ? "本章暂无可摘要内容。" : fallback);
            });
        });
    }

    private void showAiResult(String title, String body) {
        TextView content = Ui.text(this, body, 14, Ui.INK);
        content.setTextIsSelectable(true);
        Ui.pad(content, 18);
        new android.app.AlertDialog.Builder(this).setTitle(title).setView(content)
                .setPositiveButton("关闭", null).show();
    }

    private void showAnnotations() {
        if (book == null) return;
        ArrayList<AnnotationStore.Record> allRecords = new ArrayList<>(annotations.list(id, annotSort, annotColorFilter));
        final ArrayList<AnnotationStore.Record> records = new ArrayList<>();
        for (AnnotationStore.Record r : allRecords)
            if (!annotCurrentChapterOnly || r.anchor.chapter == chapter) records.add(r);
        ArrayList<String> labels = new ArrayList<>();
        labels.add("导出笔记（书评 / 标注 / 想法）");
        labels.add("导入标注（JSON）");
        labels.add("排序：" + (annotSort == AnnotationStore.SORT_CHAPTER ? "按章节" : "按时间")
                + "  ·  范围：" + (annotCurrentChapterOnly ? "本章" : "全书")
                + "  ·  颜色：" + (annotColorFilter < 0 ? "全部" : AnnotationStore.COLOR_NAMES[colorIndex(annotColorFilter)]));
        for (AnnotationStore.Record record : records) {
            String kind;
            if (AnnotationStore.TYPE_BOOKMARK.equals(record.type)) kind = "书签";
            else if (AnnotationStore.STYLE_UNDERLINE.equals(record.style)) kind = "下划线";
            else if (AnnotationStore.STYLE_STRIKE.equals(record.style)) kind = "删除线";
            else kind = "高亮";
            String quote = record.anchor.quote.length() > 18 ? record.anchor.quote.substring(0, 18) + "…" : record.anchor.quote;
            labels.add(kind + " · " + quote + (record.note.isEmpty() ? "" : "  [" + record.note + "]"));
        }
        labels.add("+ 在当前位置添加书签");
        new AlertDialog.Builder(this).setTitle("笔记与标注（" + records.size() + "）")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == 0) { chooseExportNotes(); return; }
                    if (which == 1) { chooseImportNotes(); return; }
                    if (which == 2) { showAnnotFilters(); return; }
                    int index = which - 3;
                    if (index < records.size()) { editAnnotation(records.get(index)); return; }
                    LazyChapterBook.Section section = book.meta(chapter);
                    int at = Math.max(0, Math.min(pendingOffset, section.charCount));
                    AnnotationStore.Anchor anchor = new AnnotationStore.Anchor(chapter, at, at + 1,
                            section.spineIndex, section.sourceStart >= 0 ? section.sourceStart + at : -1, "书签");
                    boolean saved = annotations.insert(id, anchor, AnnotationStore.TYPE_BOOKMARK,
                            0xFFFFB347, "", AnnotationStore.STYLE_HIGHLIGHT) != null;
                    Toast.makeText(this, saved ? "已添加书签" : "书签保存失败", Toast.LENGTH_SHORT).show();
                    if (saved) applySearchHighlights();
                }).setNegativeButton("关闭", null).show();
    }

    private static int colorIndex(int color) {
        for (int i = 0; i < AnnotationStore.COLORS.length; i++) if (AnnotationStore.COLORS[i] == color) return i;
        return 0;
    }

    private void showAnnotFilters() {
        String[] options = {
                "排序：" + (annotSort == AnnotationStore.SORT_CHAPTER ? "按章节（点击改按时间）" : "按时间（点击改按章节）"),
                "范围：" + (annotCurrentChapterOnly ? "本章（点击改全书）" : "全书（点击改本章）"),
                "颜色筛选：" + (annotColorFilter < 0 ? "全部" : AnnotationStore.COLOR_NAMES[colorIndex(annotColorFilter)]) + "（点击切换）"
        };
        new AlertDialog.Builder(this).setTitle("标注筛选").setItems(options, (d, which) -> {
            if (which == 0) annotSort = annotSort == AnnotationStore.SORT_CHAPTER ? AnnotationStore.SORT_TIME : AnnotationStore.SORT_CHAPTER;
            else if (which == 1) annotCurrentChapterOnly = !annotCurrentChapterOnly;
            else annotColorFilter = annotColorFilter < 0 ? AnnotationStore.COLORS[0]
                    : (colorIndex(annotColorFilter) + 1 < AnnotationStore.COLORS.length
                        ? AnnotationStore.COLORS[colorIndex(annotColorFilter) + 1] : -1);
            showAnnotations();
        }).setNegativeButton("返回", null).show();
    }

    private void editAnnotation(AnnotationStore.Record record) {
        String[] actions = {"改颜色：" + AnnotationStore.COLOR_NAMES[colorIndex(record.color)],
                "改样式：" + styleName(record.style), "编辑笔记", "删除"};
        new AlertDialog.Builder(this).setTitle(record.anchor.quote.isEmpty() ? "书签" : "标注详情")
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        int next = (colorIndex(record.color) + 1) % AnnotationStore.COLORS.length;
                        annotations.update(record.id, null, AnnotationStore.COLORS[next], null);
                    } else if (which == 1) {
                        int idx = 0;
                        for (int i = 0; i < AnnotationStore.STYLES.length; i++)
                            if (AnnotationStore.STYLES[i].equals(record.style)) idx = i;
                        annotations.update(record.id, null, null, AnnotationStore.STYLES[(idx + 1) % AnnotationStore.STYLES.length]);
                    } else if (which == 2) {
                        android.widget.EditText note = new android.widget.EditText(this);
                        note.setText(record.note);
                        note.setHint("笔记内容");
                        new AlertDialog.Builder(this).setTitle("编辑笔记").setView(note)
                                .setNegativeButton("取消", null)
                                .setPositiveButton("保存", (dd, ww) -> annotations.update(record.id,
                                        note.getText().toString().trim(), null, null)).show();
                        return;
                    } else {
                        boolean removed = annotations.delete(record.id);
                        Toast.makeText(this, removed ? "已删除标注" : "删除失败，标注可能已不存在",
                                Toast.LENGTH_SHORT).show();
                    }
                    applySearchHighlights();
                }).setNegativeButton("取消", null).show();
    }

    private static String styleName(String style) {
        if (AnnotationStore.STYLE_UNDERLINE.equals(style)) return "下划线";
        if (AnnotationStore.STYLE_STRIKE.equals(style)) return "删除线";
        return "高亮";
    }

    private void chooseExportNotes() {
        String[] formats = {"JSON（可再导入）", "Markdown (.md)", "纯文本 (.txt)", "CSV（Excel，GBK）"};
        new AlertDialog.Builder(this).setTitle("导出笔记格式").setItems(formats, (d, which) -> {
            String format, ext, mime;
            switch (which) {
                case 1: format = "md"; ext = "md"; mime = "text/markdown"; break;
                case 2: format = "txt"; ext = "txt"; mime = "text/plain"; break;
                case 3: format = "csv"; ext = "csv"; mime = "text/csv"; break;
                default: format = "json"; ext = "json"; mime = "application/json"; break;
            }
            pendingExportFormat = format;
            String name = currentRecord == null ? "书籍" : currentRecord.title;
            name = name.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
            if (name.length() > 60) name = name.substring(0, 60);
            if (name.isEmpty()) name = "书籍";
            try {
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_TITLE, name + "-notes." + ext), REQUEST_EXPORT_NOTES);
            } catch (android.content.ActivityNotFoundException unavailable) {
                Toast.makeText(this, "系统没有可用的文件选择器", Toast.LENGTH_SHORT).show();
            }
        }).setNegativeButton("取消", null).show();
    }

    private void chooseImportNotes() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), REQUEST_IMPORT_NOTES);
        } catch (android.content.ActivityNotFoundException unavailable) {
            Toast.makeText(this, "系统没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 应用排版参数到 TextView：行距、字距、对齐。段距与首行缩进在文本渲染时处理。 */
    private void applyTextStyle() {
        if (text == null) return;
        text.setLineSpacing(Ui.dp(this, style.paraSpacingSp()), style.lineSpacingMultiplier());
        text.setLetterSpacing(style.letterSpacingEm());
        switch (style.align) {
            case ReaderStyle.ALIGN_CENTER: text.setGravity(android.view.Gravity.CENTER_HORIZONTAL); break;
            case ReaderStyle.ALIGN_RIGHT: text.setGravity(android.view.Gravity.RIGHT); break;
            case ReaderStyle.ALIGN_JUSTIFY: text.setGravity(android.view.Gravity.FILL_HORIZONTAL); break;
            default: text.setGravity(android.view.Gravity.LEFT); break;
        }
        // 外部 CSS 的 TXT 子集在排版档位之后套用；启用才覆盖，关闭时保留档位设置
        if (ExternalCssStore.enabled(this)) {
            ExternalCssStore.applyToReader(this, text, fontSize);
            int ignored = ExternalCssStore.lastIgnored();
            if (cssStatus != null) cssStatus.setText(ignored > 0
                    ? "已忽略 " + ignored + " 条不支持的样式声明" : "");
        } else if (cssStatus != null) {
            cssStatus.setText(ExternalCssStore.rulesFor(this, id).isEmpty()
                    ? "" : "外部样式已关闭（规则仍在，可随时开启）");
        }
        updateStatusVisibility(); // v2.1：套用样式后按结果同步页脚状态行
        // 0.27.0：正文用户字体统一在此接入；跟随系统态不触碰 setTypeface。
        applyBodyFont();
    }

    /** 字体显示层唯一接入点：G0 老路径逐字保留，新世界才走解析器。 */
    private void applyBodyFont() {
        if (!fonts.libraryInitialized()) { fonts.applyTo(text); return; }
        applyBodyFontManaged();
    }

    /** 新世界渲染链：SYSTEM 绝对短路；其余分支只有这里对正文调用 setTypeface。 */
    private void applyBodyFontManaged() {
        if (text == null) return;
        List<Entry> entries = fonts.library();
        Result resolved = FontResolver.resolveEffective(fonts.mode(), fonts.globalFontId(),
                fonts.bookFontId(id), entries, fonts.legacyAvailable());
        if (resolved.clearBookKey) {
            prefs.edit().remove(FontResolver.bookKeyName(id)).apply();
        }
        // D4 契约（0.29.0 收尾明确）：SYSTEM = 清除显式自定义字体、回到 TextView 默认继承，
        // 而不是「保留上一个自定义 Typeface」。只作用于新世界路径；G0 老路径 fonts.applyTo(text)
        // 与 ReaderFonts.resolveBodyTypeface()/applyTo() 原方法体逐字不动，老用户零回归不受影响。
        if (resolved.kind == Kind.SYSTEM) {
            text.setTypeface(null);
            return;
        }
        if (resolved.kind == Kind.BUILTIN) {
            text.setTypeface(Typeface.DEFAULT);
            return;
        }
        String fontId = resolved.fontId;
        Typeface face = fonts.createTypeface(fontId);
        if (face == null) {
            fonts.markLibraryEntryInvalid(fontId);
            text.setTypeface(Typeface.DEFAULT);
            fonts.notifyFontDegradation("字体文件不可用，已恢复默认字体，请重新选择");
            return;
        }
        text.setTypeface(face);
    }

    private String fontModeLabel() {
        String label = ReaderFonts.describeMode(fonts.mode());
        if (fonts.mode() == ReaderFonts.MODE_USER && !fonts.userFileValid()) label += "（文件不可用）";
        return label;
    }

    /** 字体切换后立即重排正文，无需重开书（字体不依赖章节索引）。 */
    private void afterFontChange() {
        if (book == null || text == null) return;
        save();
        int anchor = pendingOffset;
        boolean bottom = pendingBottom;
        float fraction = pendingLineFraction;
        restoring = true;
        applyTextStyle();
        applySearchHighlights();
        restore(anchor, bottom, fraction);
    }

    /**
     * 字体管理对话框：顶部三态 + 收藏卡片（预览/重命名/设默认/删除/本书使用）
     * + 添加 + 按书单选 + 边界说明。首次进入时惰性迁移 legacy。
     */
    private void openFontSettings() {
        fonts.adoptLegacyIfNeeded(); // 用户主动入口，绝不放在 onCreate / 渲染路径。
        final int generation = ++fontPreviewGeneration;
        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 14);

        panel.addView(Ui.text(this, "全局三态（不变的三态语义）", 13, Ui.MUTED));
        LinearLayout modes = Ui.row(this);
        int mode = fonts.mode();
        modes.addView(Ui.button(this, (mode == ReaderFonts.MODE_SYSTEM ? "● " : "○ ") + "跟随系统",
                () -> { fonts.saveMode(ReaderFonts.MODE_SYSTEM); afterFontChange(); reopenFontSettings(); },
                "全局-跟随系统"), Ui.weight());
        modes.addView(Ui.button(this, (mode == ReaderFonts.MODE_BUILTIN ? "● " : "○ ") + "内置默认",
                () -> { fonts.saveMode(ReaderFonts.MODE_BUILTIN); afterFontChange(); reopenFontSettings(); },
                "全局-内置默认"), Ui.weight());
        modes.addView(Ui.button(this, (mode == ReaderFonts.MODE_USER ? "● " : "○ ") + "使用收藏字体",
                () -> { fonts.saveMode(ReaderFonts.MODE_USER); afterFontChange(); reopenFontSettings(); },
                "全局-使用收藏字体"), Ui.weight());
        panel.addView(modes);
        Ui.gap(panel, 10);

        panel.addView(Ui.text(this, "收藏字体（点击卡片＝仅对本书使用）", 13, Ui.MUTED));
        // D1：卡片容器直接挂到 panel，由外层 panelScroll 统一滚动。
        // 内层固定 300dp ScrollView 与同向外层嵌套会吃掉竖直手势，导致第 3 张起不可达。
        fontCardsContainer = Ui.column(this);
        panel.addView(fontCardsContainer, new LinearLayout.LayoutParams(-1, -2));

        Ui.gap(panel, 8);
        panel.addView(Ui.button(this, "＋ 添加字体（TTF / OTF）", this::launchFontPicker, "添加字体"),
                new LinearLayout.LayoutParams(-1, -2));
        Ui.gap(panel, 4);
        panel.addView(Ui.button(this, "仅对本书使用…", this::openBookFontDialog, "仅对本书使用"),
                new LinearLayout.LayoutParams(-1, -2));
        Ui.gap(panel, 4);
        panel.addView(Ui.button(this, "清除本书字体（回落全局）",
                () -> { fonts.clearBookFont(id); afterFontChange(); reopenFontSettings(); },
                "清除本书字体"), new LinearLayout.LayoutParams(-1, -2));
        Ui.gap(panel, 8);
        panel.addView(Ui.text(this,
                "仅对 TXT/MD 正文生效；字体为你自带，OpenLeaf 不内置、不下载字体。跟随系统时按书字体不生效。",
                12, Ui.MUTED));

        ScrollView panelScroll = new ScrollView(this);
        panelScroll.addView(panel);
        fontDialog = new AlertDialog.Builder(this).setTitle("字体管理")
                .setView(panelScroll).setNegativeButton("关闭", null).create();
        fontDialog.setOnDismissListener(dialog -> {
            if (generation != fontPreviewGeneration) return;
            previewCache.clear();
            fontDialog = null;
            fontCardsContainer = null;
            fontPreviewGeneration++;
        });
        fontDialog.show();
        fontDialogEntries = fonts.library();
        refreshFontCards();
        preloadPreviews(fontDialogEntries);
    }

    /** 对话框内操作完成后重开，保证三态标记/卡片标记与 prefs 同步。 */
    private void reopenFontSettings() {
        if (fontDialog != null) fontDialog.dismiss();
        openFontSettings();
    }

    /** 用当前缓存重建卡片列表；缓存为空时先显示默认字形，worker 回来后再刷新一次。 */
    private void refreshFontCards() {
        if (fontCardsContainer == null || isDestroyed()) return;
        fontCardsContainer.removeAllViews();
        if (fontDialogEntries.isEmpty()) {
            fontCardsContainer.addView(Ui.text(this, "还没有收藏字体，点下方「添加字体」开始。", 13, Ui.MUTED));
            return;
        }
        String globalId = fonts.globalFontId();
        String bookFontId = fonts.bookFontId(id);
        for (final Entry entry : fontDialogEntries) {
            View card = fontUi.buildCard(this, entry, previewCache.get(entry.id),
                    globalId, bookFontId,
                    () -> {
                        fonts.setBookFont(id, entry.id);
                        afterFontChange();
                        reopenFontSettings();
                        Toast.makeText(this, "仅本书生效，其他书不变", Toast.LENGTH_SHORT).show();
                    },
                    () -> {
                        fonts.setGlobalDefault(entry.id);
                        afterFontChange();
                        reopenFontSettings();
                    },
                    () -> renameFontEntry(entry),
                    () -> confirmDeleteFont(entry));
            fontCardsContainer.addView(card, new LinearLayout.LayoutParams(-1, -2));
            Ui.gap(fontCardsContainer, 8);
        }
    }

    /** worker 按 id 预加载 Typeface（上限 20），只在 UI 线程写缓存并刷新卡片。 */
    private void preloadPreviews(final List<Entry> entries) {
        // 不在此处递增：与对话框共用同一个 generation，关闭时统一失效并清缓存。
        final int generation = fontPreviewGeneration;
        worker.execute(() -> {
            final Map<String, Typeface> loaded = new LinkedHashMap<>();
            final List<String> broken = new ArrayList<>();
            for (Entry entry : entries) {
                if (!FontLibrary.selectable(entry)) continue;
                Typeface face = fonts.createTypeface(entry.id); // D3：头校验失败返回 null
                if (face != null) loaded.put(entry.id, face);
                else broken.add(entry.id);
            }
            runOnUiThread(() -> {
                if (isDestroyed() || generation != fontPreviewGeneration) return;
                if (!broken.isEmpty()) {
                    // D3：已入库文件被损坏 → 持久置 valid=false，卡片立即灰化/禁用；
                    // 再走正文唯一渲染链，让降级 DEFAULT + 一次性提示立即生效。
                    for (String brokenId : broken) fonts.markLibraryEntryInvalid(brokenId);
                    fontDialogEntries = fonts.library();
                    applyBodyFont();
                }
                previewCache.clear();
                previewCache.putAll(loaded);
                refreshFontCards();
            });
        });
    }

    /** 按书单选项：跟随全局 + 仅 valid 的收藏字体；MODE_SYSTEM 下不生效的说明明示。 */
    private void openBookFontDialog() {
        fonts.adoptLegacyIfNeeded(); // 用户主动入口。
        final List<Entry> entries = new ArrayList<>();
        for (Entry entry : fonts.library()) {
            if (FontLibrary.selectable(entry)) entries.add(entry);
        }
        String[] names = new String[entries.size() + 1];
        names[0] = "跟随全局（清除本书覆盖）";
        for (int i = 0; i < entries.size(); i++) names[i + 1] = entries.get(i).name;
        // D2：material 主题下 setMessage 会独占 contentPanel 把 setItems 列表挤出，
        // 因此提示语并入 title，绝不与 setItems 同时使用 setMessage。
        new AlertDialog.Builder(this).setTitle("本书字体\n仅影响当前这本书，其他书仍用全局设置；重启后保持。跟随系统态下不应用自定义字体。")
                .setItems(names, (dialog, which) -> {
                    if (which == 0) fonts.clearBookFont(id);
                    else if (which <= entries.size()) fonts.setBookFont(id, entries.get(which - 1).id);
                    else return;
                    afterFontChange();
                    reopenFontSettings();
                    Toast.makeText(this, "仅本书生效，其他书不变", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("取消", null).show();
    }

    private void renameFontEntry(final Entry entry) {
        EditText input = new EditText(this);
        input.setText(entry.name);
        input.setSelection(input.getText().length());
        input.setContentDescription("重命名输入框-" + entry.name);
        new AlertDialog.Builder(this).setTitle("重命名字体")
                .setMessage("只改展示名，不改字体文件与内容。").setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    fonts.renameFont(entry.id, input.getText().toString());
                    reopenFontSettings();
                }).setNegativeButton("取消", null).show();
    }

    private void confirmDeleteFont(final Entry entry) {
        new AlertDialog.Builder(this).setTitle("删除字体")
                .setMessage("删除「" + entry.name + "」？引用它的书籍将回落全局字体。")
                .setPositiveButton("删除", (dialog, which) -> {
                    fonts.removeFont(entry.id);
                    previewCache.remove(entry.id);
                    afterFontChange();
                    reopenFontSettings();
                    Toast.makeText(this, "已删除，相关书籍已回落全局字体", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("取消", null).show();
    }

    private void launchFontPicker() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), REQUEST_PICK_FONT);
        } catch (android.content.ActivityNotFoundException unavailable) {
            Toast.makeText(this, "系统没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 从 SAF Uri 读取展示用文件名；读不到时回退到 Uri 末段。 */
    private String readDisplayName(android.net.Uri uri) {
        String fallback = uri.getLastPathSegment();
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // 部分提供器不支持 DISPLAY_NAME 列，回退到 Uri 末段即可。
        } finally {
            if (cursor != null) cursor.close();
        }
        return fallback;
    }

    /**
     * 0.57.0 P1-2 排版设置面板：所有改动先进 StyleDraft，只刷静态小样；
     * 仅点「应用」才一次性写 prefs 并重排一次，取消/返回/点外侧零写入。
     */
    private void openStyleSettings() {
        if (book == null || text == null) return;
        styleDraft = StyleDraft.from(style, paragraphReflowEnabled(), fontSize);
        final AlertDialog[] dialogHolder = new AlertDialog[1];

        StylePanelViews views = new StylePanelViews();
        stylePanelViews = views;
        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 14);

        views.presetTitle = Ui.text(this, "", 13, Ui.MUTED);
        views.presetTitle.setContentDescription("当前预设");
        panel.addView(views.presetTitle);
        Ui.gap(panel, 4);
        LinearLayout presetRow = Ui.row(this);
        views.presets = new Button[PRESET_NAMES.length];
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            final int presetId = ReaderPresets.COMPACT + i; // 与 PRESET_NAMES 顺序一致
            views.presets[i] = Ui.button(this, PRESET_NAMES[i], () -> {
                if (styleDraft == null) return;
                styleDraft.applyPreset(presetId);
                refreshStylePreview();
            }, "预设-" + PRESET_NAMES[i]);
            presetRow.addView(views.presets[i], Ui.weight());
        }
        panel.addView(presetRow);
        Ui.gap(panel, 8);

        // 字号纳入草稿：−/＋ 各 ±2，clamp 与 settings() 的既有入口一致（14–32）。
        LinearLayout sizeRow = Ui.row(this);
        sizeRow.addView(Ui.button(this, "字号 −", () -> {
            if (styleDraft == null) return;
            styleDraft.setFontSize(Math.max(StyleDraft.MIN_FONT_SIZE, styleDraft.fontSize() - 2));
            refreshStylePreview();
        }, "字号减小"));
        views.sizeValue = Ui.text(this, "", 16, Ui.INK);
        views.sizeValue.setGravity(android.view.Gravity.CENTER);
        views.sizeValue.setContentDescription("当前字号");
        sizeRow.addView(views.sizeValue, Ui.weight());
        sizeRow.addView(Ui.button(this, "字号 ＋", () -> {
            if (styleDraft == null) return;
            styleDraft.setFontSize(Math.min(StyleDraft.MAX_FONT_SIZE, styleDraft.fontSize() + 2));
            refreshStylePreview();
        }, "字号增大"));
        panel.addView(sizeRow);
        Ui.gap(panel, 8);

        // 静态小样：面板构造时创建一次，后续只改属性，绝不重载真实章节。
        views.preview = Ui.text(this, STYLE_SAMPLE_TEXT, styleDraft.fontSize(), Ui.INK);
        views.preview.setContentDescription("排版小样预览");
        LinearLayout previewBox = Ui.column(this);
        Ui.pad(previewBox, 10);
        previewBox.setBackground(Ui.outline(this, Ui.LINE, 8));
        previewBox.addView(views.preview, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(previewBox, new LinearLayout.LayoutParams(-1, -2));
        Ui.gap(panel, 8);

        views.reflow = Ui.button(this, "", () -> {
            if (styleDraft == null) return;
            styleDraft.setParagraphReflow(!styleDraft.paragraphReflow());
            refreshStylePreview();
        }, "段落重排开关");
        panel.addView(views.reflow, new LinearLayout.LayoutParams(-1, -2));
        Ui.gap(panel, 8);

        // 字体/颜色是独立即时入口，不进草稿；面板内只静态展示当前生效字体与范围。
        panel.addView(Ui.text(this, "当前生效字体：" + effectiveFontDescription(), 13, Ui.MUTED));
        panel.addView(Ui.text(this, "设置范围：" + ReaderFonts.describeMode(fonts.mode()), 13, Ui.MUTED));
        if (fonts.mode() == ReaderFonts.MODE_SYSTEM) {
            panel.addView(Ui.text(this, "跟随系统时本书字体覆盖不生效", 12, Ui.MUTED));
        }
        Ui.gap(panel, 4);
        // 保留旧自动化钩子「排版项-8」；语义仍是跳独立字体对话框。
        panel.addView(Ui.button(this, "正文字体 ›", () -> {
            dialogHolder[0].dismiss();
            openFontSettings();
        }, "排版项-8"), new LinearLayout.LayoutParams(-1, -2));
        Ui.gap(panel, 4);
        panel.addView(Ui.button(this, "背景色：" + colorLabel(true), () -> {
            dialogHolder[0].dismiss();
            openColorPicker(true);
        }, "背景色"), new LinearLayout.LayoutParams(-1, -2));
        Ui.gap(panel, 4);
        panel.addView(Ui.button(this, "字色：" + colorLabel(false), () -> {
            dialogHolder[0].dismiss();
            openColorPicker(false);
        }, "字色"), new LinearLayout.LayoutParams(-1, -2));
        Ui.gap(panel, 8);

        panel.addView(Ui.text(this, "逐项微调（仅刷小样，点应用才生效）", 13, Ui.MUTED));
        views.options = new Button[8];
        for (int i = 0; i < views.options.length; i++) {
            final int which = i;
            views.options[i] = Ui.button(this, "", () -> {
                if (styleDraft == null) return;
                switch (which) {
                    case 0: styleDraft.setLineSpacing((styleDraft.lineSpacing() + 1) % ReaderStyle.LINE_SPACING.length); break;
                    case 1: styleDraft.setParaSpacing((styleDraft.paraSpacing() + 1) % ReaderStyle.PARA_SPACING.length); break;
                    case 2: styleDraft.setLetterSpacing((styleDraft.letterSpacing() + 1) % ReaderStyle.LETTER_SPACING.length); break;
                    case 3: styleDraft.setSideMargin((styleDraft.sideMargin() + 1) % ReaderStyle.SIDE_MARGIN.length); break;
                    case 4: styleDraft.setAlign((styleDraft.align() + 1) % 4); break;
                    case 5: styleDraft.setIndent((styleDraft.indent() + 1) % ReaderStyle.INDENT.length); break;
                    case 6: styleDraft.setConvert((styleDraft.convert() + 1) % 3); break;
                    case 7: styleDraft.applyPreset(ReaderPresets.STANDARD); break; // 重置为默认
                    default: return;
                }
                if (which != 7) styleDraft.markCustom();
                refreshStylePreview();
            }, "排版项-" + i);
            panel.addView(views.options[i], new LinearLayout.LayoutParams(-1, -2));
            Ui.gap(panel, 2);
        }

        Ui.gap(panel, 10);
        LinearLayout actions = Ui.row(this);
        actions.addView(Ui.button(this, "取消", () -> dialogHolder[0].dismiss(), "取消排版"), Ui.weight());
        actions.addView(Ui.button(this, "应用", () -> applyStyleDraft(dialogHolder[0]), "应用排版"), Ui.weight());
        panel.addView(actions);

        ScrollView panelScroll = new ScrollView(this);
        panelScroll.addView(panel);
        dialogHolder[0] = new AlertDialog.Builder(this).setTitle("排版设置")
                .setView(panelScroll)
                .setNegativeButton("关闭", null)
                .setOnCancelListener(dialog -> clearStyleDraft())
                .setOnDismissListener(dialog -> clearStyleDraft())
                .create();
        refreshStylePreview();
        dialogHolder[0].show();
    }

    /** 应用：捕获锚点 → 草稿写回 → 一次落盘 → 一次重排 → dismiss；不做第二遍重排。 */
    private void applyStyleDraft(AlertDialog dialog) {
        StyleDraft draft = styleDraft;
        if (draft == null || dialog == null) return;
        save();
        int anchor = pendingOffset;
        boolean bottom = pendingBottom;
        float fraction = pendingLineFraction;
        boolean fontSizeChanged = draft.fontSize() != fontSize;
        boolean reflowChanged = draft.paragraphReflow() != paragraphReflowEnabled();

        draft.copyInto(style);
        style.save(); // 一次写全 8 个 style_* 键
        if (fontSizeChanged || reflowChanged) {
            SharedPreferences.Editor editor = prefs.edit();
            if (fontSizeChanged) editor.putInt("fontSize", draft.fontSize());
            if (reflowChanged) editor.putBoolean("paragraph_reflow", draft.paragraphReflow());
            editor.apply();
        }

        // 字号不在 afterStyleChange() 的职责内，这里同步内存值与正文 TextView 尺寸。
        if (fontSizeChanged) {
            fontSize = draft.fontSize();
            if (text != null) text.setTextSize(fontSize);
        }
        afterStyleChange(anchor, bottom, fraction);
        dialog.dismiss(); // OnDismiss 清 draft
    }

    /** 取消/返回/点外侧：只清引用，零 prefs 写入、零正文重排。 */
    private void clearStyleDraft() {
        styleDraft = null;
        stylePanelViews = null;
    }

    /** 按 FontResolver 的解析结果描述正文当前真正生效的字体（纯展示，不写 prefs）。 */
    private String effectiveFontDescription() {
        Result resolved = FontResolver.resolveEffective(fonts.mode(), fonts.globalFontId(),
                fonts.bookFontId(id), fonts.library(), fonts.legacyAvailable());
        if (resolved.kind == Kind.SYSTEM) return "跟随系统（系统默认字形）";
        if (resolved.kind == Kind.BUILTIN) return "内置默认（系统字体）";
        if (FontLibrary.LEGACY_ID.equals(resolved.fontId)) return "旧版自定义字体";
        for (Entry entry : fonts.library()) {
            if (entry.id.equals(resolved.fontId)) return entry.name;
        }
        return resolved.fontId == null || resolved.fontId.isEmpty() ? "系统默认" : resolved.fontId;
    }

    private static String presetNameFor(int preset) {
        return preset >= ReaderPresets.COMPACT && preset <= ReaderPresets.COMIC
                ? PRESET_NAMES[preset - ReaderPresets.COMPACT] : "自定义";
    }

    /**
     * 只把草稿画到独立小样与按钮文案上；档位换算直接取 ReaderStyle 的档位数组，
     * 不读 style 对象（避免误用已持久化值）。全程不碰成员 text / 章节 / 高亮。
     */
    private void refreshStylePreview() {
        StyleDraft draft = styleDraft;
        StylePanelViews views = stylePanelViews;
        if (draft == null || views == null || views.preview == null) return;

        // 标题/高亮按 7 项实际数值判定，沿用 0.56.0 的 detectPreset 口径；
        // draft.preset 仍保留持久化语义，由 copyInto 写回 style_preset。
        int preset = draft.detectedPreset();
        if (views.presetTitle != null) {
            views.presetTitle.setText("当前：" + presetNameFor(preset));
        }
        if (views.presets != null) {
            for (int i = 0; i < views.presets.length; i++) {
                int current = ReaderPresets.COMPACT + i;
                views.presets[i].setText((preset == current ? "● " : "○ ") + presetNameFor(current));
            }
        }
        if (views.sizeValue != null) views.sizeValue.setText(String.valueOf(draft.fontSize()));
        if (views.reflow != null) {
            views.reflow.setText("段落重排：" + (draft.paragraphReflow() ? "开（点击关闭）" : "关（点击开启）"));
        }
        if (views.options != null && views.options.length >= 8) {
            views.options[0].setText("行距：" + fmt2(ReaderStyle.LINE_SPACING[draft.lineSpacing()]) + " 倍");
            views.options[1].setText("段距：" + ReaderStyle.PARA_SPACING[draft.paraSpacing()] + " sp");
            views.options[2].setText("字距：" + String.format(Locale.ROOT, "%.2f",
                    ReaderStyle.LETTER_SPACING[draft.letterSpacing()]) + " em");
            views.options[3].setText("页边距：" + ReaderStyle.SIDE_MARGIN[draft.sideMargin()] + " dp");
            views.options[4].setText("对齐：" + ALIGN_LABELS[draft.align()]);
            views.options[5].setText("首行缩进：" + ReaderStyle.INDENT[draft.indent()] + " 字");
            views.options[6].setText("简繁：" + CONVERT_LABELS[draft.convert()]);
            views.options[7].setText("重置为默认");
        }

        TextView preview = views.preview;
        preview.setTextSize(draft.fontSize());
        preview.setLineSpacing(Ui.dp(this, ReaderStyle.PARA_SPACING[draft.paraSpacing()]),
                ReaderStyle.LINE_SPACING[draft.lineSpacing()]);
        preview.setLetterSpacing(ReaderStyle.LETTER_SPACING[draft.letterSpacing()]);
        switch (draft.align()) {
            case ReaderStyle.ALIGN_CENTER: preview.setGravity(android.view.Gravity.CENTER_HORIZONTAL); break;
            case ReaderStyle.ALIGN_RIGHT: preview.setGravity(android.view.Gravity.RIGHT); break;
            case ReaderStyle.ALIGN_JUSTIFY: preview.setGravity(android.view.Gravity.FILL_HORIZONTAL); break;
            default: preview.setGravity(android.view.Gravity.LEFT); break;
        }
        int side = Ui.dp(this, Math.max(12, ReaderStyle.SIDE_MARGIN[draft.sideMargin()]));
        preview.setPadding(side, Ui.dp(this, 4), side, Ui.dp(this, 4));

        String sample = STYLE_SAMPLE_TEXT;
        if (draft.paragraphReflow()) {
            sample = ParagraphReflow.reflow(sample, true, true, true).displayText;
        }
        sample = ChineseConverter.convert(sample, draft.convert());
        int indentPx = ReaderStyle.INDENT[draft.indent()] * draft.fontSize();
        if (indentPx <= 0) {
            preview.setText(sample);
            return;
        }
        // 首行缩进只在小样的段落首字叠加，不回写任何原文或标注。
        SpannableString marked = new SpannableString(sample);
        boolean[] decisions = draft.paragraphReflow() ? ParagraphReflow.indentDecisions(sample) : null;
        int paragraphStart = 0;
        int line = 0;
        for (int i = 0; i <= sample.length(); i++) {
            if (i == sample.length() || sample.charAt(i) == '\n') {
                if (i > paragraphStart && sample.charAt(paragraphStart) != '\n') {
                    boolean already = decisions != null && line < decisions.length && decisions[line];
                    if (!already) {
                        marked.setSpan(new LeadingMarginSpan.Standard(indentPx), paragraphStart,
                                paragraphStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                paragraphStart = i + 1;
                line++;
            }
        }
        preview.setText(marked);
    }

    /** 排版项/预设/重排开关套用后的原位恢复：与 afterFontChange() 同一范式。 */
    private void afterStyleChange(int anchor, boolean bottom, float fraction) {
        if (text == null) return;
        style.save();
        restoring = true;
        applyTextStyle();
        int horizontal = Math.max(24, (getResources().getConfiguration().screenWidthDp - 680) / 2);
        text.setPadding(Ui.dp(this, Math.max(horizontal, style.sideMarginDp())), Ui.dp(this, 28),
                Ui.dp(this, Math.max(horizontal, style.sideMarginDp())), Ui.dp(this, 48));
        applySearchHighlights();
        restore(anchor, bottom, fraction);
    }

    private static String fmt2(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** 结算当前阅读分段：仅当有滚动活动且时长在 [MIN,MAX] 内才计入统计（KOReader 截断思想）。 */
    private void commitReadingSegment() {
        if (id == null || segmentStartMs <= 0) return;
        long seconds = (System.currentTimeMillis() - segmentStartMs) / 1000;
        long startedAt = segmentStartMs;
        if (seconds >= ReadingStatsStore.MIN_SECONDS)
            statsStore.addSeconds(id, seconds, org.openleaf.reader.core.ReadingLedger.Kind.TEXT, startedAt);
        segmentStartMs = System.currentTimeMillis();
    }

    /** 用户主动操作：结算已过去的分段，并推迟自动翻页计时（KOReader autoturn 的输入重置思想）。 */
    private void noteInteraction() {
        commitReadingSegment();
        if (autoTurnIndex > 0) {
            autoHandler.removeCallbacks(autoTurnTask);
            autoHandler.postDelayed(autoTurnTask, AUTO_TURN_INTERVALS[autoTurnIndex]);
        }
    }

    private void startAutoTurn() {
        autoHandler.removeCallbacks(autoTurnTask);
        if (autoTurnIndex > 0 && book != null) autoHandler.postDelayed(autoTurnTask, AUTO_TURN_INTERVALS[autoTurnIndex]);
    }

    private void cycleAutoTurn() {
        autoTurnIndex = (autoTurnIndex + 1) % AUTO_TURN_INTERVALS.length;
        prefs.edit().putInt("autoTurn", autoTurnIndex).apply();
        startAutoTurn();
        String label = autoTurnIndex == 0 ? "关闭"
                : (AUTO_TURN_INTERVALS[autoTurnIndex] / 1000) + " 秒";
        Toast.makeText(this, "自动翻页：" + label, Toast.LENGTH_SHORT).show();
    }

    /** 焦点在按钮/输入控件/对话框上时，音量键交给系统，不当翻页处理（避免误触）。 */
    private boolean focusOnInteractiveControl() {
        android.view.View focused = getCurrentFocus();
        if (focused == null) return false;
        if (focused instanceof android.widget.Button || focused instanceof android.widget.EditText
                || focused instanceof android.widget.Spinner || focused instanceof android.widget.CheckBox) return true;
        android.view.ViewParent parent = focused.getParent();
        return parent instanceof android.view.ViewGroup
                && ((android.view.ViewGroup) parent).getDescendantFocusability() == android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS;
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (volumeKeysEnabled && !isFinishing()
                && (event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_UP
                || event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
                && !focusOnInteractiveControl()) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && scroll != null && !restoring) {
                int direction = event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1;
                if (horizontalPages) { turnPage(direction); return true; }
                commitReadingSegment();
                if (direction < 0) scroll.smoothScrollBy(0, -Math.max(120, scroll.getHeight() - Ui.dp(this, 48)));
                else scroll.smoothScrollBy(0, Math.max(120, scroll.getHeight() - Ui.dp(this, 48)));
                save();
            }
            return true; // 吞掉事件，避免弹出系统音量条
        }
        return super.dispatchKeyEvent(event);
    }

    private void settings() {
        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 14);
        panel.setBackgroundColor(Color.rgb(247, 243, 234));
        panel.addView(Ui.text(this, "按阅读任务分组；每项显示当前值，打开或关闭不会改变正文锚点。", 14, Ui.MUTED));
        panel.addView(settingsItemButton("可视选项  ·  字体、字号、主题", this::visualSettings, "可视选项"));
        panel.addView(settingsItemButton("控制选项  ·  翻页、动画、手势", this::controlSettings, "控制选项"));
        panel.addView(settingsItemButton("文本与网络  ·  编码、重排、预取", this::textSettings, "文本与网络"));
        showReaderOptions("阅读设置", panel);
    }

    private void visualSettings() {
        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 12);
        panel.addView(sectionTitle("排版与字体"));
        panel.addView(settingsItemButton("排版设置…", this::openStyleSettings, "排版设置"));
        panel.addView(settingsItemButton("正文字体：" + fontModeLabel(), this::openFontSettings, "当前正文字体"));
        panel.addView(settingsItemButton("字号减小  A−", () -> changeTextOrTheme(
                () -> fontSize = Math.max(14, fontSize - 2), false), "字号减小"));
        panel.addView(settingsItemButton("字号增大  A＋", () -> changeTextOrTheme(
                () -> fontSize = Math.min(40, fontSize + 2), false), "字号增大"));
        panel.addView(sectionTitle("阅读外观"));
        panel.addView(settingsItemButton(() -> "主题：" + ThemePalette.presetName(theme) + "（点击切换）",
                () -> changeTextOrTheme(() -> theme = (theme + 1) % ThemePalette.PRESET_COUNT, true), "主题"));
        panel.addView(settingsItemButton(() -> {
            boolean f = prefs.getBoolean(ThemeResolver.KEY_FOLLOW, false);
            return f ? "跟随系统：开（手动主题选择将关闭跟随）" : "跟随系统：关（当前：" + themeName() + "）";
        }, () -> {
            boolean wasFollow = prefs.getBoolean(ThemeResolver.KEY_FOLLOW, false);
            prefs.edit().putBoolean(ThemeResolver.KEY_FOLLOW, !wasFollow).apply();
            recomputeTheme();
            themeStatus.setText(wasFollow ? "跟随系统：关（当前：" + themeName() + "）"
                    : "跟随系统：开（夜间系统主题 = 夜间；手动主题选择将关闭跟随）");
            updateStatusVisibility();
        }, "跟随系统"));
        panel.addView(settingsItemButton(() -> "夜间调光：" + Math.round(DIM_LEVELS[dimIndex] * 100) + "%（点击循环）", () -> {
            save();
            dimIndex = (dimIndex + 1) % DIM_LEVELS.length;
            applyDim();
            prefs.edit().putInt("dimLevel", dimIndex).apply();
        }, "夜间调光"));
        showReaderOptions("可视选项", panel);
    }

    private void controlSettings() {
        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 12);
        panel.addView(sectionTitle("阅读方式与翻页动画"));
        Button mode = settingsItemButton("", () -> { }, "阅读方式");
        Button motion = settingsItemButton("", () -> { }, "翻页动画");
        Button immersive = settingsItemButton("", () -> { }, "沉浸阅读");
        Runnable refresh = () -> {
            mode.setText("阅读方式：" + (horizontalPages ? "左右分页" : "滚动"));
            mode.setEnabled(supportsPaging());
            motion.setText(horizontalPages ? "翻页动画：" + PAGE_MOTIONS[pageMotion] : "翻页动画（仅左右分页）");
            motion.setEnabled(horizontalPages);
            immersive.setText(horizontalPages ? "沉浸阅读（仅滚动）" :
                    "沉浸阅读：" + (immersiveEnabled ? "开（下滑隐藏工具栏）" : "关"));
            immersive.setEnabled(!horizontalPages);
        };
        mode.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("阅读方式")
                .setSingleChoiceItems(new String[]{"滚动", "左右分页"}, horizontalPages ? 1 : 0, (d, selected) -> {
                    setHorizontalPages(selected == 1);
                    refresh.run();
                    d.dismiss();
                }).setNegativeButton("取消", null).show());
        motion.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("翻页动画")
                .setSingleChoiceItems(PAGE_MOTIONS, pageMotion, (d, selected) -> {
                    pageMotion = selected;
                    prefs.edit().putInt("readerPageMotion", selected).apply();
                    refresh.run();
                    d.dismiss();
                }).setNegativeButton("取消", null).show());
        immersive.setOnClickListener(v -> {
            immersiveEnabled = !immersiveEnabled;
            prefs.edit().putBoolean("immersive", immersiveEnabled).apply();
            setChromeVisible(true);
            refresh.run();
        });
        panel.addView(mode);
        panel.addView(motion);
        panel.addView(Ui.text(this, supportsPaging() ? "左右划动或使用底部按钮翻页。系统停用动画时不会播放翻页动画。"
                : "左右分页仅适用于本地 TXT / MD。", 13, Ui.MUTED));
        panel.addView(sectionTitle("阅读操作"));
        panel.addView(settingsItemButton(
                () -> "自动翻页：" + (autoTurnIndex == 0 ? "关闭" : (AUTO_TURN_INTERVALS[autoTurnIndex] / 1000) + " 秒"),
                () -> { save(); cycleAutoTurn(); }, "自动翻页"));
        panel.addView(settingsItemButton(() -> "音量键翻页：" + (volumeKeysEnabled ? "开" : "关"), () -> {
            save();
            volumeKeysEnabled = !volumeKeysEnabled;
            prefs.edit().putBoolean("volumeKeys", volumeKeysEnabled).apply();
        }, "音量键翻页"));
        panel.addView(immersive);
        refresh.run();
        showReaderOptions("控制选项", panel);
    }

    private void textSettings() {
        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 12);
        panel.addView(sectionTitle("文本"));
        panel.addView(settingsItemButton("外部样式…", this::externalCssMenu, "外部样式"));
        panel.addView(settingsItemButton("文本编码：" + encodingModeLabel() + (isTextFormat() ? "" : "（仅 TXT/MD）"),
                this::openEncodingSettings, "文本编码"));
        panel.addView(sectionTitle("网络"));
        panel.addView(settingsItemButton(() -> "仅 Wi-Fi 预取：" + (prefetchWifiOnly ? "开" : "关"), () -> {
            save();
            prefetchWifiOnly = !prefetchWifiOnly;
            prefs.edit().putBoolean("prefetchWifiOnly", prefetchWifiOnly).apply();
        }, "仅 Wi-Fi 预取"));
        showReaderOptions("文本与网络", panel);
    }

    private void showReaderOptions(String title, LinearLayout panel) {
        panel.setBackgroundColor(Color.rgb(247, 243, 234));
        panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 14));
        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.setBackgroundColor(Color.rgb(247, 243, 234));
        settingsScroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(settingsScroll)
                .setNegativeButton("关闭", null).create();
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(Ui.shape(Color.rgb(247, 243, 234), 18, this));
            }
        });
        dialog.show();
    }

    /** 沉浸式阅读：用 alpha 淡出工具栏/底栏，不改变布局尺寸（零回归）。 */
    private void setChromeVisible(boolean visible) {
        if (toolbar == null || footer == null) return;
        float alpha = visible ? 1f : 0f;
        toolbar.animate().alpha(alpha).setDuration(180).start();
        footer.animate().alpha(alpha).setDuration(180).start();
        toolbar.setClickable(visible);
        footer.setClickable(visible);
    }

    private String themeName() { return ThemePalette.presetName(theme); }

    /** 系统主题变化后回前台即时重算；跟随关闭时沿用手动 theme SP。 */
    private void recomputeTheme() {
        int next = ThemeResolver.resolve(prefs.getBoolean(ThemeResolver.KEY_FOLLOW, false),
                getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK,
                prefs.getInt("theme", 0));
        int[] palette = ThemePalette.resolve(next,
                prefs.getInt(KEY_CUSTOM_BG, 0), prefs.getInt(KEY_CUSTOM_INK, 0));
        if (ThemePalette.samePalette(next, palette[0], palette[1], theme, paperColor, inkColor)) return;
        theme = next;
        colors();
    }

    private String cssImportedStatus() {
        return "已导入：" + ExternalCssStore.name(this) + " · "
                + ExternalCssStore.rulesFor(this, id).size() + " 条安全规则 · 作用范围："
                + (ExternalCssStore.SCOPE_BOOK.equals(ExternalCssStore.scope(this)) ? "本书" : "全局");
    }

    /** 外部样式子菜单：导入 / 启用·关闭 / 作用范围 / 清除，全部调用 T02 存储接口。 */
    private void externalCssMenu() {
        final String scope = ExternalCssStore.scope(this);
        String[] items = {
                "导入 .css",
                ExternalCssStore.enabled(this) ? "外部样式：开（点击关闭）" : "外部样式：关（点击开启）",
                "作用范围：" + (ExternalCssStore.SCOPE_BOOK.equals(scope) ? "本书" : "全局") + "（点击切换）",
                "清除外部样式"
        };
        new AlertDialog.Builder(this).setTitle("外部样式").setItems(items, (d, which) -> {
            if (which == 0) { ExternalCssStore.launchImport(this); return; }
            if (which == 1) {
                boolean enabled = !ExternalCssStore.enabled(this);
                ExternalCssStore.setEnabled(this, enabled);
                applyTextStyle();
                if (cssStatus != null) cssStatus.setText(enabled
                        ? (ExternalCssStore.rulesFor(this, id).isEmpty()
                                ? "未导入外部样式（当前使用原书样式 / 应用统一样式）" : cssImportedStatus())
                        : "外部样式已关闭（规则仍在，可随时开启）");
                updateStatusVisibility();
                return;
            }
            if (which == 2) {
                String next = ExternalCssStore.SCOPE_BOOK.equals(scope)
                        ? ExternalCssStore.SCOPE_GLOBAL : ExternalCssStore.SCOPE_BOOK;
                ExternalCssStore.setScope(this, next);
                applyTextStyle();
                if (cssStatus != null) cssStatus.setText(cssImportedStatus());
                updateStatusVisibility();
                return;
            }
            ExternalCssStore.clear(this);
            applyTextStyle();
            if (cssStatus != null) cssStatus.setText(ExternalCssStore.lastStatus());
            updateStatusVisibility();
        }).setNegativeButton("关闭", null).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        save();
        state.putInt("chapter", chapter);
        state.putInt("offset", pendingOffset);
        state.putBoolean("bottom", pendingBottom);
        state.putFloat("lineFraction", pendingLineFraction);
        state.putString("searchQuery",searchQuery);state.putBoolean("searchCaseSensitive",searchCaseSensitive);state.putInt("searchScope",searchScope);
        state.putInt("searchMode",searchMode);state.putInt("searchWindow",searchWindow);
        if(book!=null&&chapter<book.size()){
            LazyChapterBook.Section section=book.meta(chapter);
            int effective=pendingBottom?section.charCount:pendingOffset;
            long source=section.sourceStart>=0?anchorSourceOffset(chapter,effective):-1;
            state.putString("anchor",ReadingAnchor.text(section.chapterKey,chapter,section.spineIndex,source,
                    effective,pendingBottom,pendingLineFraction).serialize());
        }
        if(activeMatch>=0&&activeMatch<searchHits.size()){state.putInt("matchChapter",searchHits.get(activeMatch).chapter);state.putInt("matchStart",searchHits.get(activeMatch).start);}
        super.onSaveInstanceState(state);
    }
    /** 夜间调光：仅调整本窗口亮度，不影响系统设置；退出页面自动还原。 */
    private void applyDim() {
        android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = DIM_LEVELS[dimIndex];
        getWindow().setAttributes(params);
    }

    /**
     * 从听书返回时接上刚听到的位置。
     *
     * <p>阅读页与听书服务共用一组位置键：阅读页被切到后台时 onPause 已把旧位置写回，
     * 之后听书又写了新位置。若回前台不重载，旧的内存位置会在下一次 onPause 覆盖新进度。
     * 修订号只由听书服务写入，所以这里不会把自己保存的位置误判成外部变更。
     */
    private void applyExternalPositionIfNeeded() {
        if (id == null || book == null || progressModel == null) return;
        String revision = prefs.getString(org.openleaf.reader.core.PositionSync.revisionKey(id), null);
        if (revision == null || revision.equals(lastExternalRevision)) return;
        ReadingAnchor anchor = ReadingAnchor.parse(prefs.getString("anchor_" + id, null));
        if (anchor == null) return;
        int[] mapped = new int[]{prefs.getInt("offset_" + id, 0)};
        int target = resolveAnchor(anchor, mapped);
        if (target < 0 || target >= book.size()) return;
        // 只有真正重载成功才推进基线；解析失败时留着旧值，下次还能重试
        lastExternalRevision = revision;
        chapter = target;
        pendingOffset = mapped[0];
        pendingBottom = anchor.atEnd;
        pendingLineFraction = anchor.lineFraction;
        showChapter(pendingOffset, pendingBottom, pendingLineFraction);
        // 听书若还在后台念这本书，它会继续改位置、阅读页的 save() 又会把旧位置写回去。
        // 回到同一本书的阅读页就是「我要接着读」，停播以消除双写。
        if (TtsPlaybackService.isPlayingBook(id)) {
            TtsPlaybackService.command(this, TtsPlaybackService.ACTION_STOP);
            Toast.makeText(this, "已停止朗读，从听到的位置继续阅读", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        applyExternalPositionIfNeeded();
        applyDim();
        // 系统主题切换后回前台即时生效（跟随开时重算并刷新配色）
        recomputeTheme();
        if (segmentStartMs <= 0) segmentStartMs = System.currentTimeMillis();
        startAutoTurn();
    }
    @Override protected void onPause() {
        save();
        commitReadingSegment();
        autoHandler.removeCallbacks(autoTurnTask);
        // 还原窗口亮度为系统默认
        android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(params);
        super.onPause();
    }
    @Override protected void onDestroy() {
        searchGeneration++;
        if(searchFuture!=null)searchFuture.cancel(true);
        cancelRulePreview();
        cancelIndexChange();
        rulePreviewWorker.shutdownNow();
        worker.shutdownNow();
        if (onlineFetcher != null) onlineFetcher.shutdown();
        autoHandler.removeCallbacks(autoTurnTask);
        commitReadingSegment();
        if(book!=null)book.close();
        super.onDestroy();
    }
}
