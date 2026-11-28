package org.openleaf.reader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.openleaf.reader.core.ComicFlow;
import org.openleaf.reader.core.ComicSlots;
import org.openleaf.reader.core.ComicSlots.CoverMode;
import org.openleaf.reader.core.UiTokens;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 本地漫画阅读：CBZ/ZIP 安全页索引 + 跨章连读（借鉴 Venera 的分段全局索引设计）。
 * 夜间调光用应用内黑色遮罩实现，不改图片像素、不影响原文件。
 */
public final class ComicActivity extends Activity {
    /** 页文件路径（一次解压写入私有缓存，避免逐页重扫 ZIP） */
    private final List<File> pageFiles = new ArrayList<>();
    private final List<String> pageNames = new ArrayList<>();
    /** LRU 位图缓存：只驻留当前及邻近页，去掉 200 页硬上限 */
    private final LinkedHashMap<Integer, Bitmap> bitmapCache =
            new LinkedHashMap<Integer, Bitmap>(9, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Integer, Bitmap> eldest) {
                    return size() > 6;
                }
            };
    private File comicCacheDir;
    /** 阶段 2-B：当前章节稳定 ID（缓存目录名 + 进度归属）。 */
    private String comicChapterId;
    private Uri comicCurrentUri;
    /** 阶段 2-B：同系列漫画章节（书库内所有 comic 书按标题自然序），当前位置。 */
    private java.util.List<BookStore.Book> comicSeries = new ArrayList<>();
    /** 与 comicSeries 逐项对应的章节稳定 ID（缓存目录名后缀），buildComicSeries 时预计算。 */
    private java.util.List<String> comicSeriesChapterIds = new ArrayList<>();
    private int comicSeriesIndex = -1;
    /** 阶段 2-B 补遗：单个 CBZ 内按子目录分出的子章（含独立缓存目录/页名）。 */
    private static final class SubChapter {
        final String eid;
        final String title;
        final File dir;
        final java.util.List<String> pageNames;
        SubChapter(String eid, String title, File dir, java.util.List<String> pageNames) {
            this.eid = eid; this.title = title; this.dir = dir; this.pageNames = pageNames;
        }
    }
    private java.util.List<SubChapter> subChapters = new ArrayList<>();
    private int subChapterIndex = -1;
    /** 子章清单文件名（写在整包缓存目录下，冷启动据此恢复）。 */
    private static final String SUB_INDEX = "subchapters.json";
    /** 阶段 4：大图查看浮层（点页放大，Dialog 承载，不干扰阅读页布局）。 */
    private android.app.Dialog zoomDialog;
    private int currentPage;
    /** 0.43.0：本屏真实图片索引（1 或 2 个），供点击放大/页码标签使用。 */
    private int[] screenIndices = new int[0];
    /** 漫画进度指纹（T02 数据侧；「继续？」弹窗入口留 T03） */
    private ComicProgressStore progressStore;
    private String comicKey;
    private String comicDisplayName;
    private ImageView imageView;      // 单页模式
    private ImageView leftView, rightView; // 双页模式
    private TextView comicStatus;
    private Button pageLabel, autoButton;
    private View dimOverlay;
    private int dimPercent = 100;
    /** 0.43.0：排版三态。旧 doublePage 默认 false（单页），故默认 ALL_SINGLE。 */
    private CoverMode coverMode = CoverMode.ALL_SINGLE;
    private Button layoutButton;
    private boolean rtl;
    /** v2.1：翻页按钮引用（首末页边界禁用 + 灰化）。 */
    private Button prevPageButton, nextPageButton;
    /** v2.1：阅读方式二级面板，选中项后关闭。 */
    private android.app.Dialog methodDialog;
    /** v2.1：本屏解码失败标记（单页/双页左右），供就地提示与点击重试使用。 */
    private boolean failedSingle, failedLeft, failedRight;
    private boolean lastFailureShown;
    private boolean autoRunning;
    private final org.openleaf.reader.core.AutoRead.Controller autoController =
            new org.openleaf.reader.core.AutoRead.Controller();
    private final android.os.Handler autoHandler = new android.os.Handler();
    /** 主线程 Handler：后台解压结果回主线程更新 UI */
    private android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 载入代次号：销毁/重载后丢弃过期回调 */
    private volatile int loadGeneration;
    /** 当前解压后台线程 */
    private volatile Thread loadThread;
    private long lastAutoFrameNanos;
    private float autoAccum;
    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            long now = android.os.SystemClock.elapsedRealtimeNanos();
            long frameDelta = lastAutoFrameNanos == 0 ? 16_000_000L : (now - lastAutoFrameNanos) / 1_000_000L;
            lastAutoFrameNanos = now;
            float distance = org.openleaf.reader.core.AutoRead.stepDistance(
                    1000f, 0.35f, frameDelta, autoController.intervalMs());
            autoAccum += distance;
            // 每累计约 350px 切一页（视口高 1000 × 0.35）
            if (autoAccum >= 350f) { autoAccum = 0f; next(); }
            if (autoRunning) autoHandler.postDelayed(this, 16);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        progressStore = new ComicProgressStore(this);
        LinearLayout root = Ui.column(this);
        // v4 I2b：chrome 走方向令牌；漫画图像区与调光遮罩像素不动。
        Ui.mount(this, root, Ui.color(this, UiTokens.Role.BACKGROUND));
        TextView heading = Ui.title(this, "漫画", 20);
        heading.setTextColor(Ui.color(this, UiTokens.Role.PRIMARY));
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        root.addView(heading);

        // 图片 + 调光遮罩叠放在 FrameLayout 中（遮罩纯视图层，不改图片像素）
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setContentDescription("漫画页面");
        // 阶段 4：点页放大；v2.1：失败页先重试（openZoom 本身不变）
        imageView.setOnClickListener(v -> onPageClick(failedSingle, screenIndices.length > 0 ? screenIndices[0] : currentPage));
        stage.addView(imageView, new android.widget.FrameLayout.LayoutParams(-1, -1));
        // 双页视图：左右各半，默认隐藏
        LinearLayout doubleRow = new LinearLayout(this);
        doubleRow.setOrientation(LinearLayout.HORIZONTAL);
        leftView = new ImageView(this); rightView = new ImageView(this);
        leftView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        rightView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        leftView.setContentDescription("左页漫画");
        leftView.setOnClickListener(v -> onPageClick(failedLeft, visualLeftIndex()));
        rightView.setContentDescription("右页漫画");
        rightView.setOnClickListener(v -> onPageClick(failedRight, visualRightIndex()));
        leftView.setVisibility(View.GONE); rightView.setVisibility(View.GONE);
        doubleRow.addView(leftView, new LinearLayout.LayoutParams(0, -1, 1f));
        doubleRow.addView(rightView, new LinearLayout.LayoutParams(0, -1, 1f));
        stage.addView(doubleRow, new android.widget.FrameLayout.LayoutParams(-1, -1));
        dimOverlay = new android.view.View(this);
        dimOverlay.setBackgroundColor(0x00000000);
        stage.addView(dimOverlay, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));
        comicStatus = Ui.text(this, "", 12, Ui.color(this, UiTokens.Role.SECONDARY));
        comicStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(comicStatus);

        LinearLayout nav = Ui.row(this);
        prevPageButton = Ui.secondaryToken(this, "上一页", this::previous, "上一页");
        pageLabel = Ui.secondaryToken(this, "未载入", this::showPageJumpDialog, "页码跳转");
        nextPageButton = Ui.secondaryToken(this, "下一页", this::next, "下一页");
        nav.addView(prevPageButton);
        nav.addView(pageLabel, Ui.weight());
        nav.addView(nextPageButton);
        root.addView(nav);
        addReaderTools(root);
        applyNavEnabled();

        // Q4：拖入的 CBZ/ZIP 以 setData(uri) 打开；签名校验不通过时不进入阅读器
        android.net.Uri dropped = getIntent() == null ? null : getIntent().getData();
        // 阶段 2-B：从书架打开（只带 bookId）时，按 bookId 取回已入库漫画的引用 URI
        if (dropped == null && getIntent() != null) {
            String bookId = getIntent().getStringExtra("bookId");
            if (bookId != null && !bookId.isEmpty()) {
                try {
                    BookStore.Book book = new BookStore(this).find(bookId);
                    if (book != null) dropped = new BookStore(this).sourceUri(book);
                } catch (Exception ignored) { /* 取不到引用时回退到文件选择器 */ }
            }
        }
        if (dropped != null) {
            // 阶段 3-B 验证补全：ZIP/CBZ 与 RAR/CBR 都允许进入阅读器。
            // 旧实现一律走 zipSignatureOk，RAR4 魔数必被拒，loadComic 里的 RarArchive 分支不可达。
            if (archiveSignatureOk(dropped)) { buildComicSeries(); loadComic(dropped); }
            else comicStatus.setText(ComicProgressStore.displayNameFor(this, dropped)
                    + "：不是有效的 ZIP/CBZ/RAR/CBR 文件");
        } else pickComic();
    }

    /**
     * 漫画归档签名校验：ZIP 三签名 或 RAR4 魔数 "Rar!\u001a\u0007\u0000"。
     * RAR5（"Rar!\u001a\u0007\u0001"）虽能通过此校验，但 loadComic/RarArchive 会明确拒绝并提示，
     * 这里放行是为了让拒绝路径的提示能到用户眼前，而不是笼统报"不是有效文件"。
     */
    private boolean archiveSignatureOk(android.net.Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            byte[] head = new byte[8];
            int read = in.read(head);
            if (read <= 0) return false;
            if (read < 4) return false;
            if (org.openleaf.reader.core.DropBatch.isZipSignature(java.util.Arrays.copyOf(head, 4)))
                return true;
            if (read >= 7 && head[0] == 'R' && head[1] == 'a' && head[2] == 'r' && head[3] == '!'
                    && head[4] == 0x1a && head[5] == 0x07)
                return true; // RAR4/RAR5，具体由 RarArchive 判定与提示
            return false;
        } catch (Exception invalid) { return false; }
    }

    /** 拖拽/CBZ 入口的 ZIP 三签名校验（与 core/DropBatch.isZipSignature 同口径）。 */
    private boolean zipSignatureOk(android.net.Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            byte[] head = new byte[4];
            int read = in.read(head);
            return org.openleaf.reader.core.DropBatch.isZipSignature(
                    read == 4 ? head : java.util.Arrays.copyOf(head, Math.max(0, read)));
        } catch (Exception invalid) { return false; }
    }

    private void addReaderTools(LinearLayout root) {
        LinearLayout tools = Ui.row(this);
        tools.addView(Ui.secondaryToken(this, "打开", this::pickComic, "打开漫画文件"));
        layoutButton = Ui.secondaryToken(this, "", this::showReadingMethodDialog, "阅读方式");
        updateReadingMethodLabel();
        tools.addView(layoutButton, Ui.weight());
        autoButton = Ui.secondaryToken(this, "自动阅读", this::toggleAuto, "自动阅读");
        tools.addView(autoButton);
        root.addView(tools);
        LinearLayout tools2 = Ui.row(this);
        tools2.addView(Ui.secondaryToken(this, "夜间调光", this::cycleDim, "夜间调光档位"), Ui.weight());
        tools2.addView(Ui.secondaryToken(this, "清除漫画阅读记录", this::clearComicProgress,
                "清除本机全部漫画阅读进度"), new LinearLayout.LayoutParams(0, -2, 2f));
        tools2.addView(Ui.secondaryToken(this, "返回", this::finish, "返回书架"), Ui.weight());
        root.addView(tools2);
    }

    /** 方向和布局各自单选；确认后一次应用，取消不改变当前阅读状态。 */
    private void showReadingMethodDialog() {
        stopAuto();
        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 16);
        panel.addView(Ui.text(this, "阅读方向", 14, Ui.color(this, UiTokens.Role.SECONDARY)));
        RadioGroup directions = new RadioGroup(this);
        int ltrOption = addReadingChoice(directions, "从左到右");
        int rtlOption = addReadingChoice(directions, "从右到左");
        directions.check(rtl ? rtlOption : ltrOption);
        panel.addView(directions);
        Ui.gap(panel, 12);
        panel.addView(Ui.text(this, "页面布局", 14, Ui.color(this, UiTokens.Role.SECONDARY)));
        RadioGroup layouts = new RadioGroup(this);
        int singleOption = addReadingChoice(layouts, "单页");
        int doubleOption = addReadingChoice(layouts, "双页");
        int coverOption = addReadingChoice(layouts, "双页（封面单独显示）");
        layouts.check(coverMode == CoverMode.ALL_SINGLE ? singleOption
                : coverMode == CoverMode.DOUBLE_NO_COVER ? coverOption : doubleOption);
        panel.addView(layouts);
        if (subChapters.size() > 1 && subChapterIndex >= 0) {
            Ui.gap(panel, 12);
            panel.addView(Ui.secondaryToken(this, "章节 · " + (subChapterIndex + 1) + " / " + subChapters.size(),
                    () -> { if (methodDialog != null) methodDialog.dismiss(); showSubChapterDialog(); }, "选择章节"));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        trackReadingDialog(new android.app.AlertDialog.Builder(this)
                .setTitle("阅读方式")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", (dialog, which) -> {
                    rtl = directions.getCheckedRadioButtonId() == rtlOption;
                    int layout = layouts.getCheckedRadioButtonId();
                    coverMode = layout == coverOption ? CoverMode.DOUBLE_NO_COVER
                            : layout == doubleOption ? CoverMode.ALL_DOUBLE : CoverMode.ALL_SINGLE;
                    updateReadingMethodLabel();
                    showPage();
                }).show(), true);
    }

    private void trackReadingDialog(android.app.Dialog dialog, boolean bottom) {
        methodDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (methodDialog == dialog) methodDialog = null;
        });
        if (bottom && dialog.getWindow() != null) {
            dialog.getWindow().setGravity(android.view.Gravity.BOTTOM);
        }
    }

    private void showSubChapterDialog() {
        if (subChapterIndex < 0 || subChapters.size() < 2) return;
        stopAuto();
        String[] titles = new String[subChapters.size()];
        for (int i = 0; i < titles.length; i++) {
            titles[i] = (i + 1) + " · " + subChapters.get(i).title;
        }
        trackReadingDialog(new android.app.AlertDialog.Builder(this)
                .setTitle("选择章节")
                .setSingleChoiceItems(titles, subChapterIndex, (dialog, which) -> {
                    dialog.dismiss();
                    if (which != subChapterIndex) enterSubChapter(which, comicCurrentUri, true);
                })
                .setNegativeButton("关闭", null).show(), true);
    }

    private int addReadingChoice(RadioGroup group, String label) {
        RadioButton choice = new RadioButton(this);
        choice.setId(View.generateViewId());
        choice.setText(label);
        choice.setTextColor(Ui.color(this, UiTokens.Role.PRIMARY));
        choice.setMinHeight(Ui.dp(this, 48));
        group.addView(choice);
        return choice.getId();
    }

    /** 输入的是本章真实页号，而不是布局相关的跨页序号。 */
    private void showPageJumpDialog() {
        int total = pageFiles.size();
        if (total == 0) return;
        int generation = loadGeneration;
        stopAuto();
        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 16);
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine();
        input.setHint("输入页码（1–" + total + "）");
        input.setText(Integer.toString(currentPage + 1));
        input.setSelectAllOnFocus(true);
        panel.addView(input);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("跳转页码")
                .setView(panel)
                .setNegativeButton("取消", null)
                .setPositiveButton("跳转", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (generation != loadGeneration || pageFiles.isEmpty()) { dialog.dismiss(); return; }
                int page;
                try { page = Integer.parseInt(input.getText().toString().trim()); }
                catch (NumberFormatException invalid) { page = 0; }
                if (page < 1 || page > total) {
                    input.setError("请输入 1–" + total + " 之间的页码");
                    return;
                }
                currentPage = page - 1;
                showPage();
                dialog.dismiss();
            });
        });
        input.requestFocus();
        dialog.show();
        trackReadingDialog(dialog, false);
    }

    /** 清除确认走 Dialog（§12 明确交互），成功后进度从 0 起。 */
    private void clearComicProgress() {
        new android.app.AlertDialog.Builder(this).setTitle("清除漫画阅读记录")
                .setMessage("将删除本机全部漫画阅读进度，不影响书籍与时长统计。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (d, w) -> {
                    progressStore.clearAll();
                    comicStatus.setText("从第 1 页开始");
                }).show();
    }

    /** 自动阅读按屏翻页，到真正的阅读末端后停止。 */
    private void toggleAuto() {
        if (autoRunning) {
            stopAuto();
        } else if (canGoNext()) {
            autoRunning = true;
            autoButton.setText("暂停自动");
            autoController.configure(true, 1200, false, true);
            lastAutoFrameNanos = 0; autoAccum = 0;
            autoHandler.post(autoTick);
        }
    }

    private void stopAuto() {
        if (!autoRunning) return;
        autoRunning = false;
        autoHandler.removeCallbacks(autoTick);
        autoButton.setText("自动阅读");
    }

    private void pickComic() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 3001);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 3001 || result != RESULT_OK || data == null || data.getData() == null) return;
        loadComic(data.getData());
    }

    /** 从 ZIP 流式读取图片页：先做安全索引，再按页解码，全程限制条目/字节预算。 */
    /**
     * 一次遍历解压：先把每个图片条目写入私有缓存文件，记录路径；
     * 阅读时按 LRU 按需解码，避免对每页重新扫描 ZIP（原 O(n²)）。
     */
    /** 阶段 2-B：从书库构建漫画章节序列（同系列=所有 comic 书按标题自然序）。 */
    private void buildComicSeries() {
        comicSeries = new ArrayList<>();
        comicSeriesChapterIds = new ArrayList<>();
        comicSeriesIndex = -1;
        String bookId = getIntent() == null ? null : getIntent().getStringExtra("bookId");
        BookStore store;
        try { store = new BookStore(this); }
        catch (Exception ignored) { return; }
        try {
            for (BookStore.Book b : store.all()) if ("comic".equals(b.format)) comicSeries.add(b);
        } catch (Exception ignored) { return; }
        java.util.Collections.sort(comicSeries, (a, b) -> org.openleaf.reader.core.ComicFlow.naturalCompare(
                a.title == null ? "" : a.title, b.title == null ? "" : b.title));
        // 预计算每章稳定 ID：与 loadComic 用同一来源（sourceUri 的 displayName），保证缓存目录名一致
        for (BookStore.Book b : comicSeries) {
            String id;
            try {
                Uri u = store.sourceUri(b);
                id = org.openleaf.reader.core.ComicChapters.stableId(
                        u == null ? b.title : ComicProgressStore.displayNameFor(this, u));
            } catch (Exception e) {
                id = org.openleaf.reader.core.ComicChapters.stableId(b.title);
            }
            comicSeriesChapterIds.add(id);
        }
        if (bookId != null) {
            for (int i = 0; i < comicSeries.size(); i++)
                if (bookId.equals(comicSeries.get(i).id)) { comicSeriesIndex = i; break; }
        }
    }

    /** 切到系列中的另一章（章末/章首自动连读入口）。 */
    private boolean loadSiblingChapter(int delta) {
        int target = comicSeriesIndex + delta;
        if (comicSeriesIndex < 0 || target < 0 || target >= comicSeries.size()) return false;
        BookStore.Book sibling = comicSeries.get(target);
        Uri uri;
        try { uri = new BookStore(this).sourceUri(sibling); } catch (Exception e) { return false; }
        if (uri == null) return false;
        comicSeriesIndex = target;
        Toast.makeText(this, "第 " + (target + 1) + " 章 · " + sibling.title, Toast.LENGTH_SHORT).show();
        loadComic(uri);
        return true;
    }

    private void loadComic(Uri uri) {
        if (isFinishing() || isDestroyed()) return;
        final int gen = ++loadGeneration;
        comicCurrentUri = uri;
        subChapters = new ArrayList<>();
        subChapterIndex = -1;
        releasePages();
        resetPageDisplay("正在载入漫画…");
        // 阶段 2-B 下半场：目录按章节稳定 ID 命名（不再时间戳），为跨章保留 ±1 章打基础。
        // 关键修复：不再无条件删光所有 comic-*（旧实现打开第二章会删掉第一章已解压页）。
        String chapterId = org.openleaf.reader.core.ComicChapters.stableId(
                ComicProgressStore.displayNameFor(this, uri));
        comicChapterId = chapterId;
        File target = new File(getCacheDir(), org.openleaf.reader.core.ChapterCache.dirName(chapterId));
        if (target.isDirectory()) {
            // 复用已解压缓存（force-stop 冷启动/切回本章），无需重新解压
            comicCacheDir = target;
            // 阶段 2-B 补遗：若缓存里有子章清单，恢复子章列表并进入上次子章
            if (restoreSubChapters(target)) {
                int resumeIdx = subChapterIndex >= 0 && subChapterIndex < subChapters.size() ? subChapterIndex : 0;
                enterSubChapter(resumeIdx, uri, false);
                return;
            }
            java.util.List<File> cached = listPageFiles(target);
            if (!cached.isEmpty()) {
                // 读回首次解压保存的原始页名，保证进度指纹与首次打开逐字一致
                java.util.List<String> names = readCachedNames(target);
                if (names.size() != cached.size()) {
                    java.util.List<String> fallback = new ArrayList<>();
                    for (int i = 0; i < cached.size(); i++)
                        fallback.add(String.format(java.util.Locale.ROOT, "%06d.img", i));
                    names = fallback;
                }
                pageFiles.addAll(cached); pageNames.addAll(names);
                restoreProgressForCurrent();
                showPage();
                comicStatus.setText("已从缓存打开 · 共 " + pageFiles.size() + " 页");
                return;
            }
            deleteRecursive(target);
        }
        // 保留当前章 ±1 的缓存，只淘汰过远章节（路线图头号坑：切章不能删掉相邻章已解压页）
        java.util.Set<String> keepIds = new java.util.HashSet<>();
        keepIds.add(chapterId); // 单文件打开（无 series）时至少保当前章
        if (comicSeriesIndex >= 0 && comicSeriesIndex < comicSeriesChapterIds.size()) {
            for (Integer idx : org.openleaf.reader.core.ChapterCache.keepIndices(
                    comicSeriesIndex, comicSeriesChapterIds.size())) {
                keepIds.add(comicSeriesChapterIds.get(idx));
            }
        }
        pruneCacheDirs(keepIds, gen);
        comicCacheDir = target;
        final File cacheDir = comicCacheDir;
        if (!cacheDir.mkdirs()) { resetPageDisplay("无法创建漫画缓存目录"); return; }
        final Uri finalUri = uri;
        comicStatus.setText("正在载入漫画…");
        loadThread = new Thread(() -> {
            final java.util.List<File> localPages = new ArrayList<>();
            final java.util.List<String> localNames = new ArrayList<>();
            int extracted = 0;
            long totalBytes = 0;
            // 阶段 2-B：RAR/CBR 走 junrar 解压；ZIP/CBZ 走原有流式解压（下方一字不动）
            String display = ComicProgressStore.displayNameFor(this, finalUri);
            String lower = display == null ? "" : display.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".rar") || lower.endsWith(".cbr")) {
                if (!extractRarInto(finalUri, cacheDir, localPages, localNames, gen)) return;
                final java.util.List<File> rarPages = new ArrayList<>(localPages);
                final java.util.List<String> rarNames = new ArrayList<>(localNames);
                mainHandler.post(() -> {
                    if (gen != loadGeneration) return;
                    applyLoadedPages(rarPages, rarNames, finalUri);
                });
                return;
            }
            try (InputStream raw = getContentResolver().openInputStream(finalUri)) {
                if (raw == null) throw new java.io.IOException("无法打开文件");
                ZipInputStream zip = new ZipInputStream(raw);
                ZipEntry entry;
                java.util.Map<String, File> extractedFiles = new java.util.LinkedHashMap<>();
                // 第一遍：一次遍历，把图片条目写入缓存，收集自然排序所需的名字
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (!ComicFlow.safeEntry(name) || !ComicFlow.isImage(name)) continue;
                    if (entry.getSize() > ComicFlow.MAX_ENTRY_BYTES) throw new java.io.IOException("单页超过 64MB");
                    File pageFile = new File(cacheDir, String.format(java.util.Locale.ROOT, "%06d.img", extracted));
                    try (OutputStream out = new FileOutputStream(pageFile)) {
                        byte[] buffer = new byte[16384];
                        int n, bytes = 0;
                        while ((n = zip.read(buffer)) > 0) { out.write(buffer, 0, n); bytes += n; totalBytes += n; }
                        if (bytes > ComicFlow.MAX_ENTRY_BYTES) throw new java.io.IOException("单页超过 64MB");
                    }
                    extractedFiles.put(name, pageFile);
                    extracted++;
                    if (extracted > ComicFlow.MAX_ENTRIES) throw new java.io.IOException("图片条目数超限");
                    if (totalBytes > ComicFlow.MAX_UNCOMPRESSED) throw new java.io.IOException("总展开超过 512MB");
                }
                // 自然排序：用名字排序后映射到已解压文件（保留原顺序语义）
                java.util.List<String> ordered = new ArrayList<>(extractedFiles.keySet());
                java.util.Collections.sort(ordered, ComicFlow::naturalCompare);
                for (String name : ordered) {
                    localPages.add(extractedFiles.get(name));
                    localNames.add(name);
                }
                // 阶段 2-B 补遗：若 CBZ 内有规整子目录（≥2 章），把页按子章拆分到独立目录
                if (subChaptersFromOrdered(ordered, extractedFiles, cacheDir, gen)) {
                    mainHandler.post(() -> {
                        if (gen != loadGeneration) return;
                        enterSubChapter(0, finalUri, true);
                    });
                    return;
                }
            } catch (Exception error) {
                final String message = error.getMessage();
                mainHandler.post(() -> {
                    if (gen != loadGeneration) return;
                    Toast.makeText(ComicActivity.this, "载入失败：" + message, Toast.LENGTH_SHORT).show();
                    deleteRecursive(cacheDir);
                });
                return;
            }
            final java.util.List<File> zipPages = new ArrayList<>(localPages);
            final java.util.List<String> zipNames = new ArrayList<>(localNames);
            mainHandler.post(() -> {
                if (gen == loadGeneration) applyLoadedPages(zipPages, zipNames, finalUri);
            });
        });
        loadThread.start();
    }

    /** 解压完成后回主线程统一设置页列表/进度/继续弹窗（ZIP 与 RAR 共用，逐字取自原 ZIP 分支）。 */
    private void applyLoadedPages(java.util.List<File> pages, java.util.List<String> names, Uri finalUri) {
        if (comicCacheDir != null) saveCachedNames(comicCacheDir, names);
        pageFiles.clear(); pageNames.clear();
        pageFiles.addAll(pages); pageNames.addAll(names);
        if (pageFiles.isEmpty()) { resetPageDisplay("未找到可用图片页"); return; }
        comicDisplayName = ComicProgressStore.displayNameFor(this, finalUri);
        comicKey = progressStore.keyFor(comicDisplayName, pageFiles.size(), pageNames.get(0));
        int savedPage = progressStore.page(comicKey, pageFiles.size());
        boolean hasProgress = savedPage > 0;
        currentPage = hasProgress ? Math.min(savedPage, pageFiles.size() - 1) : 0;
        showPage();
        Toast.makeText(this, "共 " + pageFiles.size() + " 页", Toast.LENGTH_SHORT).show();
        if (hasProgress) {
            final int resumeAt = currentPage;
            new android.app.AlertDialog.Builder(this).setTitle("继续阅读？")
                    .setMessage("上次读到第 " + (savedPage + 1) + " / " + pageFiles.size() + " 页，继续？")
                    .setNegativeButton("从头", (d, w) -> { currentPage = 0; showPage(); })
                    .setPositiveButton("继续", (d, w) -> { currentPage = resumeAt; showPage(); })
                    .setOnCancelListener(d -> { currentPage = resumeAt; showPage(); })
                    .show();
        } else {
            comicStatus.setText("从第 1 页开始");
        }
    }

    /**
     * 阶段 2-B 补遗：把已解压的扁平页按子目录分章（仅 ≥2 章时启用，单章保持旧行为）。
     * 每个子章的页移动（rename）到独立子目录，写 subchapters.json 供冷启动恢复。
     * @return true 表示已成功拆分并填充 subChapters（调用方应改走 enterSubChapter）
     */
    private boolean subChaptersFromOrdered(java.util.List<String> ordered,
                                           java.util.Map<String, File> extractedFiles,
                                           File cacheDir, int gen) {
        java.util.List<org.openleaf.reader.core.ComicFlow.Segment> segs =
                org.openleaf.reader.core.ComicChapters.fromEntryNames(ordered);
        if (segs.size() < 2) return false; // 无规整子目录 → 单章，保持旧行为
        subChapters = new ArrayList<>();
        try {
            int globalIndex = 0;
            for (org.openleaf.reader.core.ComicFlow.Segment seg : segs) {
                if (gen != loadGeneration) return false;
                File subDir = new File(cacheDir, "sub-" + seg.eid);
                if (!subDir.isDirectory() && !subDir.mkdirs()) return false;
                java.util.List<String> pageNames = new ArrayList<>();
                for (String image : seg.images) {
                    File src = extractedFiles.get(image);
                    if (src == null || !src.isFile()) continue;
                    File dst = new File(subDir, String.format(java.util.Locale.ROOT, "%06d.img", pageNames.size()));
                    if (!src.renameTo(dst)) {
                        copyFile(src, dst);
                        if (!src.delete()) src.deleteOnExit();
                    }
                    pageNames.add(image);
                    globalIndex++;
                }
                if (pageNames.isEmpty()) continue;
                String title = seg.eid.startsWith("root") ? "根目录" : seg.eid;
                subChapters.add(new SubChapter(seg.eid, title, subDir, pageNames));
            }
            if (subChapters.size() < 2) { subChapters.clear(); return false; }
            // 写清单：每行 eid|标题|页数（页名从各子目录 names.txt 恢复）
            StringBuilder sb = new StringBuilder();
            for (SubChapter sc : subChapters) {
                sb.append(sc.eid).append('|').append(sc.title).append('|').append(sc.pageNames.size()).append('\n');
                saveCachedNames(sc.dir, sc.pageNames);
            }
            java.io.File index = new java.io.File(cacheDir, SUB_INDEX);
            java.nio.file.Files.write(index.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            subChapters.clear();
            return false; // 拆分失败 → 回退到扁平单章（页文件仍在，不丢数据）
        }
    }

    private static void copyFile(File src, File dst) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
    }

    /** 进入指定子章：加载该子目录的页到 pageFiles/pageNames 并显示。 */
    private void enterSubChapter(int index, Uri finalUri, boolean showToast) {
        if (index < 0 || index >= subChapters.size()) return;
        SubChapter sc = subChapters.get(index);
        java.util.List<File> files = listPageFiles(sc.dir);
        if (files.isEmpty()) {
            Toast.makeText(this, "该章节没有可用图片页", Toast.LENGTH_SHORT).show();
            return;
        }
        subChapterIndex = index;
        java.util.List<String> names = readCachedNames(sc.dir);
        if (names.size() != files.size()) {
            names = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) names.add(String.format(java.util.Locale.ROOT, "%06d.img", i));
        }
        releasePages(); // 子章的页索引从 0 开始，不能复用上一章同索引的位图。
        pageFiles.addAll(files); pageNames.addAll(names);
        comicDisplayName = ComicProgressStore.displayNameFor(this, finalUri);
        // 子章进度键：书名 + 子章 eid，保证不同子章进度独立
        comicKey = progressStore.keyFor(comicDisplayName + "#" + sc.eid, pageFiles.size(),
                names.isEmpty() ? "" : names.get(0));
        int saved = progressStore.page(comicKey, pageFiles.size());
        currentPage = saved > 0 ? Math.min(saved, pageFiles.size() - 1) : 0;
        showPage();
        if (showToast) Toast.makeText(this, "第 " + (index + 1) + " 章 · " + sc.title, Toast.LENGTH_SHORT).show();
    }

    /** 从缓存目录的 subchapters.json 恢复子章列表；无清单/解析失败返回 false。 */
    private boolean restoreSubChapters(File dir) {
        File index = new File(dir, SUB_INDEX);
        if (!index.isFile()) return false;
        subChapters = new ArrayList<>();
        try {
            String content = new String(java.nio.file.Files.readAllBytes(index.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length < 3) continue;
                File subDir = new File(dir, "sub-" + parts[0]);
                if (!subDir.isDirectory()) continue;
                java.util.List<String> names = readCachedNames(subDir);
                subChapters.add(new SubChapter(parts[0], parts[1], subDir, names));
            }
            if (subChapters.size() < 2) { subChapters.clear(); return false; }
            subChapterIndex = -1;
            return true;
        } catch (Exception e) { subChapters.clear(); return false; }
    }

    /** 切到相邻子章；无相邻返回 false。 */
    private boolean switchSubChapter(int delta, Uri finalUri) {
        int target = subChapterIndex + delta;
        if (subChapterIndex < 0 || target < 0 || target >= subChapters.size()) return false;
        enterSubChapter(target, finalUri, true);
        return true;
    }

    /** RAR 解压到缓存目录；成功 true，失败在 UI 线程提示并清理目录（与 ZIP 分支同口径）。 */
    private boolean extractRarInto(Uri uri, File cacheDir, java.util.List<File> pages,
                                   java.util.List<String> names, int gen) {
        try {
            File rarFile = resolveRarFile(uri);
            if (rarFile == null) throw new java.io.IOException("无法定位 RAR 文件");
            java.util.List<String> entries = RarArchive.imageEntries(rarFile);
            long rarTotal = 0;
            for (String name : entries) {
                if (gen != loadGeneration) return false;
                File pageFile = new File(cacheDir, String.format(java.util.Locale.ROOT, "%06d.img", pages.size()));
                RarArchive.extract(rarFile, name, pageFile);
                rarTotal += pageFile.length();
                if (rarTotal > ComicFlow.MAX_UNCOMPRESSED) throw new java.io.IOException("总展开超过 512MB");
                pages.add(pageFile); names.add(name);
                if (pages.size() > ComicFlow.MAX_ENTRIES) throw new java.io.IOException("图片条目数超限");
            }
            return true;
        } catch (Exception error) {
            final String message = error.getMessage();
            mainHandler.post(() -> {
                if (gen != loadGeneration) return;
                Toast.makeText(ComicActivity.this, "载入失败：" + message, Toast.LENGTH_SHORT).show();
                deleteRecursive(cacheDir);
            });
            return false;
        }
    }

    /** 把 SAF content:// Uri 解析为可交给 junrar 的本地文件（先查 sourceUri 引用，再复制临时副本）。 */
    private File resolveRarFile(Uri uri) throws java.io.IOException {
        String bookId = getIntent() == null ? null : getIntent().getStringExtra("bookId");
        if (bookId != null && !bookId.isEmpty()) {
            try {
                BookStore.Book book = new BookStore(this).find(bookId);
                if (book != null) {
                    BookAccess access = new BookStore(this).open(book);
                    if (access != null && access.file != null) return access.file;
                }
            } catch (Exception ignored) { /* 回退到复制 Uri */ }
        }
        // 引用模式：content:// 复制一份到缓存临时文件（RAR 需要随机访问 File）
        File temp = new File(getCacheDir(), "rar-temp-" + System.currentTimeMillis() + ".rar");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(temp)) {
            if (in == null) return null;
            byte[] buf = new byte[16384]; int n; long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > org.openleaf.reader.core.BookParser.MAX_IMPORT_BYTES)
                    throw new java.io.IOException("RAR 文件超过 512MB 上限");
                out.write(buf, 0, n);
            }
        }
        return temp;
    }

    /** 按页索引取位图：LRU 缓存，未命中才从缓存文件解码（inSampleSize 防 OOM）。 */
    private Bitmap bitmapOf(int index) {
        if (index < 0 || index >= pageFiles.size()) return null;
        Bitmap cached = bitmapCache.get(index);
        if (cached != null) return cached;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2; // 降采样防 OOM；大屏可按需调为 1
        Bitmap bitmap = BitmapFactory.decodeFile(pageFiles.get(index).getAbsolutePath(), options);
        if (bitmap != null) bitmapCache.put(index, bitmap);
        return bitmap;
    }

    /**
     * 阶段 2-B：按 core/ChapterCache 策略淘汰过远章节缓存，保留 keepIds 指定章节。
     * 当前单章模型传入当前章；接入多章后传入当前 ±1 章。
     */
    private void pruneCacheDirs(java.util.Set<String> keepIds, int gen) {
        java.io.File[] dirs = getCacheDir().listFiles((dir, name) -> name.startsWith("comic-"));
        if (dirs == null) return;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (java.io.File d : dirs) names.add(d.getName());
        java.util.List<String> evictable = org.openleaf.reader.core.ChapterCache.evictable(names, keepIds);
        for (String name : evictable) {
            if (gen != loadGeneration) return; // 代次变了（用户切章）立即停手
            deleteRecursive(new java.io.File(getCacheDir(), name));
        }
    }

    /** 把原始页名清单写入缓存目录（UTF-8，每行一名），供缓存命中时恢复进度指纹。 */
    private void saveCachedNames(File dir, java.util.List<String> names) {
        try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(new File(dir, "names.txt")), java.nio.charset.StandardCharsets.UTF_8))) {
            for (String name : names) { w.write(name == null ? "" : name.replace('\n', '_')); w.newLine(); }
        } catch (Exception ignored) { /* names.txt 只是进度辅助，写失败不影响阅读 */ }
    }

    /** 读回原始页名清单；文件不存在返回空列表。 */
    private java.util.List<String> readCachedNames(File dir) {
        java.util.List<String> out = new ArrayList<>();
        File f = new File(dir, "names.txt");
        if (!f.isFile()) return out;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        } catch (Exception ignored) { }
        return out;
    }

    /** 从已缓存目录列出页文件（按 000000.img 自然序）。 */
    private java.util.List<File> listPageFiles(File dir) {
        java.util.List<File> out = new java.util.ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".img"));
        if (files == null) return out;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        out.addAll(java.util.Arrays.asList(files));
        return out;
    }

    /** 从已缓存章节恢复阅读进度（复用现有指纹进度逻辑）。 */
    private void restoreProgressForCurrent() {
        if (pageFiles.isEmpty()) return;
        comicDisplayName = ComicProgressStore.displayNameFor(this, comicCurrentUri);
        comicKey = progressStore.keyFor(comicDisplayName, pageFiles.size(),
                pageNames.isEmpty() ? "" : pageNames.get(0));
        int saved = progressStore.page(comicKey, pageFiles.size());
        currentPage = saved > 0 ? Math.min(saved, pageFiles.size() - 1) : 0;
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    /** 阶段 4：点页打开大图浮层（双指缩放/双击切换/拖动平移/点击关闭）。 */
    private void openZoom(int pageIndex) {
        if (isFinishing() || isDestroyed() || pageIndex < 0 || pageIndex >= pageFiles.size()) return;
        Bitmap bmp = bitmapOf(pageIndex);
        if (bmp == null) return;
        if (zoomDialog != null && zoomDialog.isShowing()) zoomDialog.dismiss();
        ZoomImageView zoom = new ZoomImageView(this, bmp);
        zoom.setBackgroundColor(0xFF000000);
        zoom.setOnClickListener(v -> { if (zoomDialog != null) zoomDialog.dismiss(); });
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(zoom);
        dialog.setOnDismissListener(d -> zoomDialog = null);
        zoomDialog = dialog;
        dialog.show();
    }

    private void showPage() {
        if (pageFiles.isEmpty()) return;
        // 对齐所有入口（恢复、跳页、切布局），避免从双页的第二张开始漏翻。
        currentPage = ComicSlots.alignFirstIndex(currentPage, coverMode, pageFiles.size());
        // 0.43.0：本屏索引统一由 ComicSlots 计算（单页 1 张，双页最多 2 张）
        screenIndices = ComicSlots.screenImages(currentPage, pageFiles.size(), coverMode);
        failedSingle = false; failedLeft = false; failedRight = false;
        if (screenIndices.length == 2) {
            // 双页：RTL 时视觉左=第二张、视觉右=第一张（先读右），沿用原映射
            imageView.setVisibility(View.GONE);
            leftView.setVisibility(View.VISIBLE); rightView.setVisibility(View.VISIBLE);
            Bitmap first = bitmapOf(screenIndices[0]);
            Bitmap second = bitmapOf(screenIndices[1]);
            failedLeft = (rtl ? second : first) == null;
            failedRight = (rtl ? first : second) == null;
            leftView.setImageBitmap(rtl ? second : first);
            rightView.setImageBitmap(rtl ? first : second);
        } else {
            // 单页（含双页模式下封面独占/末页仅剩一张）
            leftView.setVisibility(View.GONE); rightView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            Bitmap single = bitmapOf(screenIndices.length > 0 ? screenIndices[0] : currentPage);
            failedSingle = single == null;
            imageView.setImageBitmap(single);
        }
        // 页码标签用真实图片索引（本屏首/尾 +1），去掉旧 mapSlotForToggle 的脱节映射
        int firstIndex = screenIndices.length > 0 ? screenIndices[0] : currentPage;
        int lastIndex = screenIndices.length > 0 ? screenIndices[screenIndices.length - 1] : currentPage;
        int firstPage = firstIndex + 1;
        int lastPage = lastIndex + 1;
        String failHint = failureHint();
        String range = firstPage == lastPage ? String.valueOf(firstPage) : firstPage + "–" + lastPage;
        String spread = screenIndices.length == 2 ? "双页"
                : coverMode == CoverMode.DOUBLE_NO_COVER && firstIndex == 0 ? "封面"
                : coverMode != CoverMode.ALL_SINGLE && firstIndex == pageFiles.size() - 1 ? "单张末页" : "单页";
        pageLabel.setText(range + " / " + pageFiles.size() + "\n" + spread);
        pageLabel.setContentDescription("跳转页码：第 " + range + " 页，共 " + pageFiles.size() + " 页，" + spread);
        String chapter = subChapterIndex >= 0 && subChapters.size() > 1
                ? "第 " + (subChapterIndex + 1) + " / " + subChapters.size() + " 章" : "";
        String detail = !failHint.isEmpty() ? failHint
                : screenIndices.length == 1 && firstIndex < pageNames.size() ? pageNames.get(firstIndex) : "";
        comicStatus.setText(chapter.isEmpty() ? detail : detail.isEmpty() ? chapter : chapter + " · " + detail);
        // 一次性 Toast：仅在"无失败 → 有失败"跃迁时提醒，自动翻页不反复打扰
        boolean hasFailure = !failHint.isEmpty();
        if (hasFailure && !lastFailureShown)
            Toast.makeText(this, "图片加载失败，点失败页可重试", Toast.LENGTH_SHORT).show();
        lastFailureShown = hasFailure;
        if (!canGoNext()) stopAuto();
        applyNavEnabled();
        applyDim();
        persistProgress();
    }

    /** v2.1：失败页就地提示文案（单页/双页左右各自带真实页码）。 */
    private String failureHint() {
        StringBuilder sb = new StringBuilder();
        if (failedSingle && screenIndices.length > 0)
            sb.append(" · 第 ").append(screenIndices[0] + 1).append(" 页加载失败，点该页重试");
        if (failedLeft && screenIndices.length == 2)
            sb.append(" · 第 ").append(visualLeftIndex() + 1).append(" 页加载失败，点该页重试");
        if (failedRight && screenIndices.length == 2)
            sb.append(" · 第 ").append(visualRightIndex() + 1).append(" 页加载失败，点该页重试");
        return sb.toString();
    }

    /** v2.1：点页——失败页就地重试；正常页仍进大图缩放（openZoom 未改）。 */
    private void onPageClick(boolean failed, int index) {
        if (failed && index >= 0) retryPage(index);
        else openZoom(index);
    }

    /** v2.1：重试入口——清掉该页位图缓存后重新解码本屏。 */
    private void retryPage(int index) {
        if (index < 0 || index >= pageFiles.size()) return;
        bitmapCache.remove(index);
        showPage();
        boolean ok = bitmapOf(index) != null;
        Toast.makeText(this, "第 " + (index + 1) + " 页" + (ok ? "已重新加载" : "仍无法加载，请检查文件"),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * v2.1：首末页禁用。仅当"本章已到边界"且"无相邻子章/兄弟章可翻"时才禁用并灰化；
     * 有子章或兄弟章时一律保持 enabled，避免误禁用导致翻不了章。
     */
    private void applyNavEnabled() {
        boolean loaded = !pageFiles.isEmpty();
        boolean hasPrev = loaded && (ComicSlots.advanceFirst(currentPage, pageFiles.size(), coverMode, false) >= 0
                || hasPrevSubChapter() || hasPrevSibling());
        boolean hasNext = canGoNext();
        prevPageButton.setEnabled(hasPrev);
        nextPageButton.setEnabled(hasNext);
        pageLabel.setEnabled(loaded);
        autoButton.setEnabled(hasNext);
        prevPageButton.setAlpha(hasPrev ? 1f : 0.4f);
        nextPageButton.setAlpha(hasNext ? 1f : 0.4f);
        prevPageButton.setText(loaded && ComicSlots.advanceFirst(currentPage, pageFiles.size(), coverMode, false) < 0
                && hasPrev ? "上一章" : "上一页");
        nextPageButton.setText(loaded && ComicSlots.advanceFirst(currentPage, pageFiles.size(), coverMode, true) < 0
                && hasNext ? "下一章" : "下一页");
    }

    private boolean canGoNext() {
        return !pageFiles.isEmpty() && (ComicSlots.advanceFirst(currentPage, pageFiles.size(), coverMode, true) >= 0
                || hasNextSubChapter() || hasNextSibling());
    }

    /** 是否还有上一/下一子章（与 next()/previous() 的 switchSubChapter 口径一致）。 */
    private boolean hasPrevSubChapter() { return !subChapters.isEmpty() && subChapterIndex > 0; }
    private boolean hasNextSubChapter() {
        return !subChapters.isEmpty() && subChapterIndex >= 0 && subChapterIndex < subChapters.size() - 1;
    }
    /** 是否还有上一/下一兄弟章（与 loadSiblingChapter 口径一致）；无法确认时返回 false。 */
    private boolean hasPrevSibling() { return comicSeriesIndex > 0 && comicSeriesIndex < comicSeries.size(); }
    private boolean hasNextSibling() {
        return comicSeriesIndex >= 0 && comicSeriesIndex < comicSeries.size() - 1;
    }

    private void updateReadingMethodLabel() {
        String direction = rtl ? "从右到左" : "从左到右";
        String layout = coverMode == CoverMode.ALL_SINGLE ? "单页"
                : coverMode == CoverMode.DOUBLE_NO_COVER ? "双页·封面单独" : "双页";
        layoutButton.setText("阅读方式\n" + direction + " · " + layout);
        layoutButton.setContentDescription("阅读方式：" + direction + "，" + layout);
    }

    /** 视觉左页对应的真实索引：RTL 时是本屏第二张，非 RTL 是第一张。 */
    private int visualLeftIndex() {
        if (screenIndices.length == 0) return -1;
        return screenIndices.length == 1 || !rtl ? screenIndices[0] : screenIndices[1];
    }

    /** 视觉右页对应的真实索引：RTL 时是本屏第一张，非 RTL 是第二张。 */
    private int visualRightIndex() {
        if (screenIndices.length == 0) return -1;
        return screenIndices.length == 1 || rtl ? screenIndices[0] : screenIndices[1];
    }

    /** 数据侧：页码写入独立 SP，apply() 不阻塞 UI。 */
    private void persistProgress() {
        if (progressStore != null && comicKey != null && !comicKey.isEmpty())
            progressStore.save(comicKey, currentPage, pageFiles.size(), comicDisplayName);
    }

    /** 0.43.0：按排版三态计算下一屏；越界(-1)保持原跨章切换逻辑。 */
    private void next() {
        if (pageFiles.isEmpty()) return;
        int target = ComicSlots.advanceFirst(currentPage, pageFiles.size(), coverMode, true);
        if (target >= 0) { currentPage = target; showPage(); }
        else {
            // 章末：子目录分章优先（同包内），否则走系列下一章（阶段 2-B 跨章连读）
            boolean moved = !subChapters.isEmpty() && switchSubChapter(1, comicCurrentUri);
            if (!moved && !loadSiblingChapter(1))
                Toast.makeText(this, "已是最后一章", Toast.LENGTH_SHORT).show();
        }
    }
    private void previous() {
        if (pageFiles.isEmpty()) return;
        int target = ComicSlots.advanceFirst(currentPage, pageFiles.size(), coverMode, false);
        if (target >= 0) { currentPage = target; showPage(); }
        else {
            boolean moved = !subChapters.isEmpty() && switchSubChapter(-1, comicCurrentUri);
            if (!moved && !loadSiblingChapter(-1))
                Toast.makeText(this, "已是第一章", Toast.LENGTH_SHORT).show();
        }
    }

    /** 夜间调光循环：100% → 75% → 50% → 100%；纯视图遮罩，不改图片。 */
    private void cycleDim() {
        dimPercent = dimPercent == 100 ? 75 : dimPercent == 75 ? 50 : 100;
        applyDim();
        Toast.makeText(this, "亮度 " + dimPercent + "%", Toast.LENGTH_SHORT).show();
    }

    private void applyDim() {
        int alpha = (int) ((1f - dimPercent / 100f) * 255);
        dimOverlay.setBackgroundColor(android.graphics.Color.argb(alpha, 0, 0, 0));
    }

    private void resetPageDisplay(String status) {
        stopAuto();
        screenIndices = new int[0];
        imageView.setImageDrawable(null);
        leftView.setImageDrawable(null);
        rightView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);
        leftView.setVisibility(View.GONE);
        rightView.setVisibility(View.GONE);
        lastFailureShown = false;
        pageLabel.setText("未载入");
        pageLabel.setContentDescription(null);
        comicStatus.setText(status);
        applyNavEnabled();
    }

    private void releasePages() {
        // 不主动 recycle：ImageView 可能仍持有同一 Bitmap 引用，recycle 会导致绘制崩溃；交给 GC
        bitmapCache.clear();
        pageFiles.clear(); pageNames.clear();
    }

    // 漫画阅读时长：会话级分段（进入→离开），按 COMIC 口径分列
    private long comicSessionStartMs;

    @Override protected void onResume() {
        super.onResume();
        comicSessionStartMs = System.currentTimeMillis();
    }

    @Override protected void onPause() {
        commitComicSession();
        super.onPause();
    }

    private void commitComicSession() {
        if (comicSessionStartMs <= 0) return;
        long seconds = (System.currentTimeMillis() - comicSessionStartMs) / 1000;
        long startedAt = comicSessionStartMs;
        comicSessionStartMs = 0;
        String bookId = getIntent() == null ? null : getIntent().getStringExtra("bookId");
        if (seconds < org.openleaf.reader.ReadingStatsStore.MIN_SECONDS) return;
        org.openleaf.reader.ReadingStatsStore store = new org.openleaf.reader.ReadingStatsStore(this);
        if (bookId != null && !bookId.isEmpty()) {
            store.addSeconds(bookId, seconds, org.openleaf.reader.core.ReadingLedger.Kind.COMIC, startedAt);
        } else if (comicKey != null && !comicKey.isEmpty()) {
            // 按文件直接打开的漫画没有书库 id：用已有的阅读进度指纹做稳定归属，
            // 写无外键的媒体统计；不塞进 books，也不碰书架心智模型。
            store.addMediaSeconds(org.openleaf.reader.core.ReadingLedger.Kind.COMIC,
                    comicKey, comicDisplayName, seconds, startedAt);
        }
    }

    @Override protected void onDestroy() {
        if (zoomDialog != null && zoomDialog.isShowing()) zoomDialog.dismiss(); // 阶段 4：浮层不泄漏
        zoomDialog = null;
        if (methodDialog != null) methodDialog.dismiss();
        stopAuto();
        loadGeneration++;
        if (loadThread != null) { loadThread.interrupt(); loadThread = null; }
        mainHandler.removeCallbacksAndMessages(null);
        autoHandler.removeCallbacks(autoTick);
        commitComicSession();
        persistProgress(); // onDestroy 兜底一次，必须在 releasePages() 之前
        releasePages();
        super.onDestroy();
    }
}
