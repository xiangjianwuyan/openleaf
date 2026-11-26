package org.openleaf.reader;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openleaf.reader.core.ChapterOutline;
import org.openleaf.reader.core.ChapterSurvey;
import org.openleaf.reader.core.DigestBuilder;
import org.openleaf.reader.core.LazyChapterBook;
import org.openleaf.reader.core.MindMapLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** 提要 + 章节树：两个实例共享一个 BookSurveyCache，一次解析两处渲染。 */
final class IdeasBookPane extends IdeasPane {
    enum Mode { DIGEST, TREE }

    private final BookSurveyCache cache;
    private final Mode mode;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean loading;
    // §5.1 行 10/11：提要初始「正在读取章节…」，>1.5s 换「章节较多，仍在读取…」；
    // 章节树沿用「正在读取目录…」。只改文案、不切状态块 = 持续加载态，不是错误态。
    private final Runnable slowHint = () -> {
        if (loading) setLoadingText(slowLoadingText());
    };

    /** 慢加载文案按视图区分（§5.1 行 11）。单独成方法：字段初始化器里直接读 blank final mode 会触发 JLS §16 明确赋值校验。 */
    private String slowLoadingText() {
        return mode == Mode.DIGEST ? LOADING_SLOW : LOADING_TREE;
    }
    private ChapterSurvey survey;
    private List<ChapterOutline.Node> nodes = Collections.emptyList();
    private boolean[] collapsedFlags = new boolean[0];
    private TreeAdapter treeAdapter;

    // P1-2 提要导出：SAF 回执请求码 + 仅导出期间暂存的 Markdown（不进 Bundle，旋转后取消即可）。
    private static final int REQUEST_EXPORT_DIGEST = 4096;
    private String pendingDigestMarkdown;

    // ---- T03 导图双模式（只在 TREE pane 内，四视图契约零改动）----
    private final MindMapStore mapStore;
    private LinearLayout outlineBlock, mapBlock;
    private TextView outlineToggle, mapToggle, mapStatus, mapDirty;
    private MindMapView mapView;
    private boolean mapBuilt, mapDirtyFlag, mapGenerating;
    private String mapStatusOverride;

    // §6.3 三态文案逐字；仅 %d/%s 占位用 String.format(Locale.ROOT, ...)。
    private static final String TX_MAP_EMPTY = "还没有导图 · 点「生成导图」从章节树创建";
    private static final String TX_MAP_GENERATING = "正在生成导图…";
    private static final String TX_MAP_GENERATED = "已生成 %d 个节点 · 本地生成，不联网";
    private static final String TX_MAP_DIRTY = "有未保存的修改";
    private static final String TX_MAP_SAVED = "导图已保存到本机";
    private static final String TX_MAP_SAVE_FAIL = "导图保存失败（%s），请重试或减少节点。";
    private static final String TX_MAP_TOO_MANY = "章节超过 200，导图仅展示前 200 个节点，可折叠查看其余。";
    private static final String TX_MAP_FP_MISMATCH = "章节结构已变化，已按新目录重新生成导图（原编辑未保留）。";
    private static final String TX_MAP_OFFLINE = "本地生成，无需联网";
    private static final String BTN_OUTLINE = "大纲";
    private static final String BTN_MAP = "导图";
    private static final String BTN_MAP_GENERATE = "生成导图";
    private static final String BTN_MAP_SAVE = "保存导图";
    private static final String BTN_MAP_CLEAR = "清除本书导图";
    private static final String TX_MAP_CLEAR_HINT = "将删除这本书的本机导图编辑，不影响书籍与想法内容。";
    // 与 generateMap() 中 MindMapLayout.layout(src, 24, 24, 200, 84, 160, 56) 的
    // nodeW/nodeH 参数一致（84 是 siblingGapY）。旧存档 JSON 不含 w/h，解码时按此恢复，
    // 否则 x/y 有效但 0×0 的框会让 MindMapView 画布全白。
    private static final int MAP_NODE_W = 160;
    private static final int MAP_NODE_H = 56;

    IdeasBookPane(IdeasActivity host, BookSurveyCache cache, Mode mode) {
        super(host);
        this.cache = cache;
        this.mode = mode;
        this.mapStore = new MindMapStore(host);
    }

    @Override void onEnter() {
        if (host.bookContextError != null) {
            Throwable failure = host.bookContextError;
            showNotice(TX_PARSE_FAIL_T, messageOf(failure), BTN_RETRY, host::retryBookContext);
            return;
        }
        if (host.bookId == null) {
            showBookPicker();
            return;
        }
        loading = true;
        handler.removeCallbacks(slowHint);
        handler.postDelayed(slowHint, 1500);
        showLoading(mode == Mode.DIGEST ? LOADING_DIGEST : LOADING_TREE);
        setSubtitle("");
        cache.load(host.bookId, host.bookFormat, host.bookTitle, host.worker,
                new BookSurveyCache.Listener() {
                    @Override public void onSurvey(long generation, ChapterSurvey snapshot, boolean complete) {
                        if (generation != host.generation || host.isDestroyed()) return;
                        applySurvey(snapshot);
                    }

                    @Override public void onFailure(long generation, Throwable error) {
                        if (generation != host.generation || host.isDestroyed()) return;
                        loading = false;
                        handler.removeCallbacks(slowHint);
                        showFailure(error);
                    }

                    // §5.3 格式事实态：pdf/cbz 在 BookStore.open() 之前判定，0 次 I/O，
                    // 不进异常分类、不进 showFailure、无重试按钮、无原始异常。
                    @Override public void onFormatFact(long generation, boolean pdf) {
                        if (generation != host.generation || host.isDestroyed()) return;
                        loading = false;
                        handler.removeCallbacks(slowHint);
                        setSubtitle("");
                        showNotice(pdf ? TX_PDF_T : TX_CBZ_T,
                                pdf ? TX_PDF_H : TX_CBZ_H, null, null);
                    }
                });
    }

    /** onLeave 清 UI 侧延迟回执；导图有未确认编辑则自动保存一次（退出页面不丢编辑）。 */
    @Override void onLeave() {
        loading = false;
        handler.removeCallbacks(slowHint);
        if (mapDirtyFlag && host.treeMapMode) saveMap(true);
    }

    private void applySurvey(ChapterSurvey snapshot) {
        survey = snapshot;
        // onBatch 前缀副本不是 cache.current()；最终 read() 返回值 / 缓存命中才是最终态
        boolean isFinal = snapshot == cache.current();
        if (isFinal) {
            loading = false;
            handler.removeCallbacks(slowHint);
        }
        if (mode == Mode.DIGEST) renderDigest(snapshot, isFinal);
        else renderTree(snapshot);
    }

    // ---------------- 提要 ----------------

    private void renderDigest(ChapterSurvey snapshot, boolean isFinal) {
        DigestBuilder.Digest digest = DigestBuilder.build(host.currentBookTitle(), cache.tags(), snapshot,
                host.digestAll ? Integer.MAX_VALUE : DigestBuilder.DEFAULT_MAX_LINES);
        content.removeAllViews();

        LinearLayout head = Ui.row(host);
        head.setPadding(Ui.dp(host, 16), Ui.dp(host, 14), Ui.dp(host, 16), Ui.dp(host, 8));
        TextView bookTitle = Ui.text(host, "《" + nullToEmpty(digest.bookTitle) + "》", 16, Ui.INK);
        head.addView(bookTitle);
        for (String tag : digest.tags) {       // #9：书无标签时不渲染标签行（不留空行）
            TextView chip = Ui.text(host, " #" + tag, 12, ACCENT);
            head.addView(chip);
        }
        content.addView(head, new LinearLayout.LayoutParams(-1, -2));

        ListView list = new ListView(host);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setAdapter(new DigestAdapter(digest.lines));
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = Ui.column(host);
        footer.setPadding(Ui.dp(host, 16), Ui.dp(host, 10), Ui.dp(host, 16), Ui.dp(host, 24));
        // §5.1 行 12/13：仅对最终 survey 用 complete/timedOut 区分两种部分完成态。
        // onBatch 前缀副本恒为 complete=false、timedOut=false，属「持续加载态」，不能误判成章数触顶。
        // 只依据 survey 字段，不看 Digest.truncated（它只对应「显示全部」按钮，不据此显示「仍在读取」）。
        if (!isFinal) {
            // 每批 onBatch 部分结果：已读部分照常渲染 + 底部「章节较多，仍在读取…」
            addFooterText(footer, LOADING_SLOW);
        } else if (!snapshot.complete && snapshot.timedOut) {
            // 行 12：预算超时 → 「章节较多，仍在读取…」
            addFooterText(footer, LOADING_SLOW);
        } else if (!snapshot.complete && !snapshot.timedOut
                && snapshot.total > snapshot.maxHeads) {
            // 行 13：章数触顶 → 「仅展示前 2000 章的提要」，不是错误、不加「仍在读取」
            addFooterText(footer, TX_HEAD_CAP);
        }
        if (digest.truncated) {
            TextView showAll = Ui.text(host, String.format(Locale.ROOT, TX_SHOW_ALL, digest.totalChapters), 14, ACCENT);
            showAll.setPadding(0, Ui.dp(host, 8), 0, Ui.dp(host, 8));
            showAll.setOnClickListener(v -> {
                host.digestAll = true;
                renderDigest(survey, survey == cache.current());
            });
            footer.addView(showAll);
        }
        // P1-2：导出提要为 Markdown（拼串复用 NotePackage.exportDigestMarkdown，SAF 写文件）。
        TextView exportBtn = Ui.text(host, "导出提要", 14, ACCENT);
        exportBtn.setPadding(0, Ui.dp(host, 8), 0, Ui.dp(host, 8));
        exportBtn.setContentDescription("导出本书提要为 Markdown 文件");
        exportBtn.setOnClickListener(v -> exportDigest(digest));
        footer.addView(exportBtn);
        content.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        showContent();
        setSubtitle(snapshot.total == 1 ? TX_SINGLE_CHAPTER_SUBTITLE
                : String.format(Locale.ROOT, SUBTITLE_DIGEST, snapshot.total));
    }

    private void addFooterText(LinearLayout footer, String text) {
        TextView t = Ui.text(host, text, 13, Ui.MUTED);
        t.setPadding(0, Ui.dp(host, 4), 0, Ui.dp(host, 4));
        footer.addView(t);
    }

    /**
     * P1-2：SAF 保存提要。拼串复用 NotePackage.exportDigestMarkdown（纯函数），
     * 真正写文件放到 worker，避免大书（2000 行）卡 UI。范式参考 ReaderActivity.chooseExportNotes/writeNotes。
     */
    private void exportDigest(DigestBuilder.Digest digest) {
        pendingDigestMarkdown = NotePackage.exportDigestMarkdown(digest);
        String name = nullToEmpty(digest.bookTitle)
                .replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        if (name.length() > 60) name = name.substring(0, 60);
        if (name.isEmpty()) name = "书籍";
        try {
            host.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/markdown")
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            .putExtra(Intent.EXTRA_TITLE, name + "-提要.md"),
                    REQUEST_EXPORT_DIGEST);
        } catch (android.content.ActivityNotFoundException unavailable) {
            pendingDigestMarkdown = null;
            android.widget.Toast.makeText(host, "系统没有可用的文件选择器",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 需由 IdeasActivity.onActivityResult 转发到本 pane（转发钩子协调见交付报告）。 */
    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_EXPORT_DIGEST) return;
        final String markdown = pendingDigestMarkdown;
        pendingDigestMarkdown = null;
        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null
                || markdown == null) return;
        final android.net.Uri target = data.getData();
        host.worker.execute(() -> {
            try (java.io.OutputStream out = host.getContentResolver().openOutputStream(target, "rwt")) {
                if (out == null) throw new java.io.IOException("无法打开保存目标");
                out.write(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                host.runOnUiThread(() -> android.widget.Toast.makeText(host, "提要已导出",
                        android.widget.Toast.LENGTH_SHORT).show());
            } catch (Exception failure) {
                android.util.Log.e("OpenLeafIdeas", "digest export failed", failure);
                host.runOnUiThread(() -> android.widget.Toast.makeText(host,
                        "导出失败：" + (failure.getMessage() == null
                                ? failure.toString() : failure.getMessage()),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    private final class DigestAdapter extends BaseAdapter {
        private final List<DigestBuilder.Line> lines;

        DigestAdapter(List<DigestBuilder.Line> lines) { this.lines = lines; }

        @Override public int getCount() { return lines.size(); }
        @Override public Object getItem(int position) { return lines.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout cell;
            DigestCell holder;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof DigestCell) {
                cell = (LinearLayout) recycled;
                holder = (DigestCell) cell.getTag();
            } else {
                cell = Ui.column(host);
                cell.setPadding(Ui.dp(host, 16), Ui.dp(host, 10), Ui.dp(host, 16), Ui.dp(host, 10));
                TextView title = Ui.text(host, "", 15, Ui.INK);
                TextView lead = Ui.text(host, "", 13, Ui.MUTED);
                lead.setLineSpacing(Ui.dp(host, 3), 1);
                cell.addView(title);
                cell.addView(lead);
                holder = new DigestCell(title, lead);
                cell.setTag(holder);
            }
            DigestBuilder.Line line = lines.get(position);
            holder.title.setText(line.title);
            if (line.lead == null || line.lead.isEmpty()) {
                holder.lead.setVisibility(View.GONE);   // 首句为空不渲染，不留空灰行
            } else {
                holder.lead.setVisibility(View.VISIBLE);
                holder.lead.setText(line.lead);
            }
            return cell;
        }
    }

    private static final class DigestCell {
        final TextView title, lead;
        DigestCell(TextView title, TextView lead) { this.title = title; this.lead = lead; }
    }

    // ---------------- 章节树 ----------------

    private void renderTree(ChapterSurvey snapshot) {
        nodes = ChapterOutline.build(Arrays.asList(snapshot.titles));
        collapsedFlags = new boolean[nodes.size()];
        for (int index : host.collapsed) {
            if (index >= 0 && index < collapsedFlags.length) collapsedFlags[index] = true;
        }
        int current = host.currentChapterOf(host.bookId, snapshot.total,
                snapshot.spineIndex, host.bookFormat);
        host.chapter = current;
        content.removeAllViews();

        // 大纲 | 导图 切换条：导图 View 作为兄弟块叠加，切模式只改 visibility（C1）。
        LinearLayout toggle = Ui.row(host);
        toggle.setPadding(Ui.dp(host, 16), Ui.dp(host, 6), Ui.dp(host, 16), Ui.dp(host, 6));
        outlineToggle = Ui.text(host, BTN_OUTLINE, 14, Ui.MUTED);
        outlineToggle.setPadding(Ui.dp(host, 12), Ui.dp(host, 6), Ui.dp(host, 12), Ui.dp(host, 6));
        outlineToggle.setOnClickListener(v -> switchMapMode(false));
        mapToggle = Ui.text(host, BTN_MAP, 14, Ui.MUTED);
        mapToggle.setPadding(Ui.dp(host, 12), Ui.dp(host, 6), Ui.dp(host, 12), Ui.dp(host, 6));
        mapToggle.setOnClickListener(v -> switchMapMode(true));
        mapDirty = Ui.text(host, "", 12, ACCENT);
        toggle.addView(outlineToggle);
        toggle.addView(mapToggle);
        toggle.addView(mapDirty, Ui.weight());
        content.addView(toggle, new LinearLayout.LayoutParams(-1, -2));

        outlineBlock = Ui.column(host);
        ListView list = new ListView(host);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        treeAdapter = new TreeAdapter(current);
        list.setAdapter(treeAdapter);
        outlineBlock.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(outlineBlock, new LinearLayout.LayoutParams(-1, 0, 1));

        mapGenerating = false;
        mapBuilt = false;
        mapStatusOverride = null;
        mapBlock = buildMapBlock();
        content.addView(mapBlock, new LinearLayout.LayoutParams(-1, 0, 1));
        applyMapModeVisibility();
        if (host.treeMapMode) prepareMap(snapshot);
        showContent();
        setSubtitle(host.treeMapMode ? TX_MAP_OFFLINE
                : String.format(Locale.ROOT, SUBTITLE_TREE, snapshot.total));
    }

    private LinearLayout buildMapBlock() {
        LinearLayout block = Ui.column(host);
        mapStatus = Ui.text(host, TX_MAP_EMPTY, 13, Ui.MUTED);
        mapStatus.setPadding(Ui.dp(host, 16), Ui.dp(host, 8), Ui.dp(host, 16), Ui.dp(host, 4));
        block.addView(mapStatus, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = Ui.row(host);
        actions.setPadding(Ui.dp(host, 8), Ui.dp(host, 4), Ui.dp(host, 8), Ui.dp(host, 4));
        actions.addView(Ui.button(host, BTN_MAP_GENERATE, this::generateMap), Ui.weight());
        actions.addView(Ui.button(host, BTN_MAP_SAVE, this::saveMap), Ui.weight());
        actions.addView(Ui.button(host, BTN_MAP_CLEAR, this::confirmClearMap), Ui.weight());
        block.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        mapView = new MindMapView(host);
        mapView.setEditable(true);
        mapView.setListener(new MindMapView.Listener() {
            @Override public void onNodeSelected(int index) { editNode(index); }
            @Override public void onNodeMoved(int index, int x, int y) { markDirtyAndSave(); }
            @Override public void onAddChild(int parentIndex) { addChild(parentIndex); }
        });
        block.addView(mapView, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView offline = Ui.text(host, TX_MAP_OFFLINE, 12, Ui.MUTED);
        offline.setPadding(Ui.dp(host, 16), Ui.dp(host, 2), Ui.dp(host, 16), Ui.dp(host, 8));
        block.addView(offline, new LinearLayout.LayoutParams(-1, -2));
        return block;
    }

    private void applyMapModeVisibility() {
        boolean map = host.treeMapMode;
        if (outlineBlock != null) outlineBlock.setVisibility(map ? View.GONE : View.VISIBLE);
        if (mapBlock != null) mapBlock.setVisibility(map ? View.VISIBLE : View.GONE);
        if (outlineToggle != null) outlineToggle.setTextColor(map ? Ui.MUTED : ACCENT);
        if (mapToggle != null) mapToggle.setTextColor(map ? ACCENT : Ui.MUTED);
    }

    private void switchMapMode(boolean map) {
        if (host.treeMapMode == map) return;
        host.treeMapMode = map;
        if (host.bookId != null && !host.bookId.isEmpty()) {
            mapStore.saveMode(host.bookId, map ? "map" : "outline");
        }
        applyMapModeVisibility();
        ChapterSurvey snapshot = survey != null ? survey : cache.current();
        setSubtitle(map ? TX_MAP_OFFLINE
                : snapshot == null ? "" : String.format(Locale.ROOT, SUBTITLE_TREE, snapshot.total));
        if (map && snapshot != null) prepareMap(snapshot);
    }

    /** 有存档且指纹一致则复用；指纹不符按 §6 文案 26 重新生成；无存档只提示 17（默认不落盘）。 */
    private void prepareMap(ChapterSurvey snapshot) {
        if (host.bookId == null) { mapStatus.setText(TX_PICK_BOOK_T); return; }
        String saved = mapStore.load(host.bookId);
        if (saved == null) { mapStatus.setText(TX_MAP_EMPTY); return; }
        if (mapStore.matchesFingerprint(saved, host.bookId, snapshot.total)) {
            List<MindMapLayout.Node> decoded = decodeMap(saved, buildMapSource());
            if (decoded != null && !decoded.isEmpty()) {
                mapView.setNodes(decoded);
                mapBuilt = true;
                mapStatus.setText(TX_MAP_OFFLINE);
                return;
            }
        }
        mapStatusOverride = TX_MAP_FP_MISMATCH;
        generateMap();
    }

    /** ChapterOutline 的两级 depth → MindMapLayout 的 parent 下标（稳定序，父为最近的上一层节点）。 */
    private List<MindMapLayout.Node> buildMapSource() {
        List<MindMapLayout.Node> src = new ArrayList<>();
        int[] lastAtDepth = new int[] { -1, -1 };
        for (int i = 0; i < nodes.size(); i++) {
            ChapterOutline.Node item = nodes.get(i);
            int depth = Math.max(0, Math.min(ChapterOutline.MAX_DEPTH, item.depth));
            int parent = depth > 0 ? lastAtDepth[depth - 1] : -1;
            MindMapLayout.Node node = new MindMapLayout.Node("c" + i, item.title, parent);
            node.collapsed = i < collapsedFlags.length && collapsedFlags[i];
            src.add(node);
            lastAtDepth[depth] = i;
            for (int d = depth + 1; d < lastAtDepth.length; d++) lastAtDepth[d] = -1;
        }
        return src;
    }

    private void generateMap() {
        if (host.bookId == null) { mapStatus.setText(TX_PICK_BOOK_T); return; }
        if (mapGenerating) return;
        final String bookId = host.bookId;
        final ChapterSurvey snapshot = survey != null ? survey : cache.current();
        if (snapshot == null) { mapStatus.setText(TX_MAP_EMPTY); return; }
        final List<MindMapLayout.Node> src = buildMapSource();
        final long gen = host.generation;
        mapGenerating = true;
        mapStatus.setText(TX_MAP_GENERATING);
        host.worker.execute(() -> {
            final List<MindMapLayout.Node> laidOut = MindMapLayout.layout(src, 24, 24, 200, 84, 160, 56);
            final String json = encodeMap(laidOut, mapStore.fingerprint(bookId, snapshot.total));
            host.runOnUiThread(() -> {
                if (host.isDestroyed() || gen != host.generation) return;   // 世代 + isDestroyed 双检
                mapGenerating = false;
                if (laidOut.isEmpty()) { mapStatus.setText(TX_MAP_EMPTY); return; }
                mapView.setNodes(laidOut);
                mapBuilt = true;
                if (mapStatusOverride != null) {
                    // 指纹不符时先给 §6 文案 26，不用「生成成功」覆盖它。
                    mapStatus.setText(mapStatusOverride);
                    mapStatusOverride = null;
                } else {
                    mapStatus.setText(laidOut.size() > MindMapLayout.MAX_NODES ? TX_MAP_TOO_MANY
                            : String.format(Locale.ROOT, TX_MAP_GENERATED, laidOut.size()));
                }
                if (json != null) {
                    try { mapStore.save(bookId, json); mapDirtyFlag = false; mapDirty.setText(""); }
                    catch (RuntimeException failure) {
                        mapStatus.setText(String.format(Locale.ROOT, TX_MAP_SAVE_FAIL, mapSaveReason(failure)));
                    }
                } else {
                    mapStatus.setText(String.format(Locale.ROOT, TX_MAP_SAVE_FAIL, "本机存储空间不足或写入失败"));
                }
            });
        });
    }

    private String encodeMap(List<MindMapLayout.Node> laidOut, String fingerprint) {
        try {
            JSONObject root = new JSONObject();
            root.put("fp", fingerprint);
            JSONArray array = new JSONArray();
            for (MindMapLayout.Node node : laidOut) {
                JSONObject item = new JSONObject();
                item.put("id", node.id);
                item.put("title", node.title);
                item.put("x", node.x);
                item.put("y", node.y);
                item.put("collapsed", node.collapsed);
                array.put(item);
            }
            root.put("nodes", array);
            root.put("edges", new JSONArray());
            return root.toString();
        } catch (Exception failure) {
            return null;
        }
    }

    /** 标题覆盖 / 坐标 / 折叠态从存档取；结构以当前章节树为准，不静默串图。 */
    private List<MindMapLayout.Node> decodeMap(String json, List<MindMapLayout.Node> src) {
        try {
            JSONArray array = new JSONObject(json).optJSONArray("nodes");
            if (array == null || array.length() == 0) return null;
            List<MindMapLayout.Node> result = new ArrayList<>();
            int n = Math.min(array.length(), src.size());
            for (int i = 0; i < n; i++) {
                JSONObject item = array.optJSONObject(i);
                MindMapLayout.Node base = src.get(i);
                if (item == null) { result.add(base); continue; }
                String title = item.optString("title", base.title);
                if (title.length() > 80) title = title.substring(0, 80);
                MindMapLayout.Node node = new MindMapLayout.Node(base.id, title, base.parent);
                node.x = item.optInt("x", base.x);
                node.y = item.optInt("y", base.y);
                node.collapsed = item.optBoolean("collapsed", base.collapsed);
                result.add(node);
            }
            for (int i = n; i < src.size(); i++) result.add(src.get(i));
            // 统一兜底所有返回分支（含 item==null、src 尾部追加）：任何缺尺寸的节点都补成
            // 与 MindMapLayout.layout 一致的 160×56，避免存档恢复后画布全白。
            for (MindMapLayout.Node node : result) {
                if (node.w <= 0) node.w = MAP_NODE_W;
                if (node.h <= 0) node.h = MAP_NODE_H;
            }
            return result;
        } catch (Exception failure) {
            return null;
        }
    }

    private void markDirtyAndSave() {
        mapDirtyFlag = true;
        mapDirty.setText(TX_MAP_DIRTY);
        saveMap(true);   // 每次编辑即写入 SP（C4 旋转不丢编辑）
    }

    private void saveMap() { saveMap(false); }

    private void saveMap(boolean auto) {
        if (host.bookId == null || mapView == null || mapView.nodes().isEmpty()) {
            mapStatus.setText(TX_PICK_BOOK_T);
            return;
        }
        List<MindMapLayout.Node> current = mapView.nodes();
        String fingerprint = mapStore.fingerprint(host.bookId, survey == null ? 0 : survey.total);
        String json = encodeMap(current, fingerprint);
        if (json == null) {
            mapStatus.setText(String.format(Locale.ROOT, TX_MAP_SAVE_FAIL, "本机存储空间不足或写入失败"));
            return;
        }
        try {
            mapStore.save(host.bookId, json);
            mapDirtyFlag = false;
            mapDirty.setText("");
            mapStatus.setText(TX_MAP_SAVED);
        } catch (RuntimeException failure) {
            mapStatus.setText(String.format(Locale.ROOT, TX_MAP_SAVE_FAIL, mapSaveReason(failure)));
        }
    }

    private static String mapSaveReason(RuntimeException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.contains("64 KiB") || message.contains("导图数据")) return "本机存储空间不足或写入失败";
        return "未知错误（" + failure.getClass().getSimpleName() + "）";
    }

    private void confirmClearMap() {
        if (host.bookId == null) { mapStatus.setText(TX_PICK_BOOK_T); return; }
        new AlertDialog.Builder(host).setTitle(BTN_MAP_CLEAR).setMessage(TX_MAP_CLEAR_HINT)
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (dialog, which) -> {
                    mapStore.remove(host.bookId);
                    if (mapView != null) mapView.setNodes(new ArrayList<>());
                    mapBuilt = false;
                    mapDirtyFlag = false;
                    if (mapDirty != null) mapDirty.setText("");
                    mapStatus.setText(TX_MAP_EMPTY);
                }).show();
    }

    private void editNode(int index) {
        if (mapView == null || index < 0 || index >= mapView.nodes().size()) return;
        final MindMapLayout.Node node = mapView.nodes().get(index);
        new AlertDialog.Builder(host).setTitle(node.title)
                .setItems(new String[] { "修改标题", "加子节点", "删除节点" }, (dialog, which) -> {
                    if (which == 0) {
                        android.widget.EditText input = new android.widget.EditText(host);
                        input.setSingleLine(true);
                        input.setText(node.title);
                        input.setSelection(input.getText().length());
                        new AlertDialog.Builder(host).setTitle("修改节点标题").setView(input)
                                .setNegativeButton("取消", null)
                                .setPositiveButton("确定", (d, w) -> {
                                    String title = input.getText().toString().trim();
                                    if (title.isEmpty()) title = "未命名节点";
                                    if (title.length() > 80) title = title.substring(0, 80);
                                    replaceNode(index, new MindMapLayout.Node(node.id, title, node.parent),
                                            node.x, node.y, node.collapsed);
                                    markDirtyAndSave();
                                }).show();
                    } else if (which == 1) {
                        addChild(index);
                    } else {
                        removeNode(index);
                        markDirtyAndSave();
                    }
                }).show();
    }

    private void addChild(int parentIndex) {
        if (mapView == null || parentIndex < 0 || parentIndex >= mapView.nodes().size()) return;
        MindMapLayout.Node parent = mapView.nodes().get(parentIndex);
        MindMapLayout.Node child = new MindMapLayout.Node("u" + System.currentTimeMillis(),
                "新节点", parentIndex);
        child.x = parent.x + parent.w + Ui.dp(host, 40);
        child.y = parent.y;
        child.w = parent.w;
        child.h = parent.h;
        mapView.nodes().add(child);
        mapView.invalidate();
        markDirtyAndSave();
    }

    private void replaceNode(int index, MindMapLayout.Node replacement, int x, int y, boolean collapsed) {
        replacement.x = x; replacement.y = y; replacement.w = 160; replacement.h = 56;
        replacement.collapsed = collapsed;
        List<MindMapLayout.Node> updated = new ArrayList<>(mapView.nodes());
        updated.set(index, replacement);
        for (int i = 0; i < updated.size(); i++) {
            MindMapLayout.Node current = updated.get(i);
            if (i != index && current.parent == index) {
                MindMapLayout.Node fixed = new MindMapLayout.Node(current.id, current.title, current.parent);
                fixed.x = current.x; fixed.y = current.y; fixed.w = current.w;
                fixed.h = current.h; fixed.collapsed = current.collapsed;
                updated.set(i, fixed);
            }
        }
        mapView.setNodes(updated);
    }

    private void removeNode(int index) {
        List<MindMapLayout.Node> updated = new ArrayList<>();
        for (int i = 0; i < mapView.nodes().size(); i++) {
            if (i == index) continue;
            MindMapLayout.Node current = mapView.nodes().get(i);
            int parent = current.parent == index ? -1 : (current.parent > index ? current.parent - 1 : current.parent);
            MindMapLayout.Node node = new MindMapLayout.Node(current.id, current.title, parent);
            node.x = current.x; node.y = current.y; node.w = current.w;
            node.h = current.h; node.collapsed = current.collapsed;
            updated.add(node);
        }
        mapView.setNodes(updated);
    }

    private void toggleCollapse(int nodeIndex) {
        if (nodeIndex < 0 || nodeIndex >= collapsedFlags.length) return;
        collapsedFlags[nodeIndex] = !collapsedFlags[nodeIndex];
        List<Integer> folded = new ArrayList<>();
        for (int i = 0; i < collapsedFlags.length; i++) if (collapsedFlags[i]) folded.add(i);
        int[] saved = new int[folded.size()];
        for (int i = 0; i < saved.length; i++) saved[i] = folded.get(i);
        host.collapsed = saved;                       // 状态进 Bundle，旋转后不丢
        if (treeAdapter != null) treeAdapter.refresh();
    }

    private final class TreeAdapter extends BaseAdapter {
        private int[] visible = ChapterOutline.visible(nodes, collapsedFlags);
        private final int current;

        TreeAdapter(int current) { this.current = current; }

        void refresh() {
            visible = ChapterOutline.visible(nodes, collapsedFlags);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return visible.length; }
        @Override public Object getItem(int position) { return nodes.get(visible[position]); }
        @Override public long getItemId(int position) { return visible[position]; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            TreeCell cell;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof TreeCell) {
                row = (LinearLayout) recycled;
                cell = (TreeCell) row.getTag();
            } else {
                row = Ui.row(host);
                TextView arrow = Ui.text(host, "", 14, Ui.MUTED);
                arrow.setGravity(Gravity.CENTER);
                arrow.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(host, 24), -2)); // 箭头列宽 24dp
                TextView title = Ui.text(host, "", 15, Ui.INK);
                title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(arrow);
                row.addView(title);
                cell = new TreeCell(arrow, title);
                row.setTag(cell);
            }
            final int nodeIndex = visible[position];
            ChapterOutline.Node node = nodes.get(nodeIndex);
            row.setPadding(Ui.dp(host, 16 + node.depth * 16), Ui.dp(host, 10),
                    Ui.dp(host, 16), Ui.dp(host, 10));   // 基础 16dp + depth*16dp
            cell.title.setText(node.title);
            // P1-4 近似已读语义：当前章之前的节点视为已读（灰）。跳章/回翻会误标；
            // 精确的章节级已读需新表，而 DB v15 禁止改表，故仅做视觉近似。
            boolean read = node.index < current;
            cell.title.setTextColor(read ? Ui.MUTED : (nodeIndex == current ? ACCENT : Ui.INK));
            if (node.parent) {
                cell.arrow.setText(collapsedFlags[nodeIndex] ? "▸" : "▾");
                cell.arrow.setOnClickListener(v -> toggleCollapse(nodeIndex)); // 只箭头区域折叠
            } else {
                cell.arrow.setText("");
                cell.arrow.setOnClickListener(null);
            }
            cell.title.setOnClickListener(v -> openChapter(nodeIndex));         // 点标题跳章
            return row;
        }
    }

    private static final class TreeCell {
        final TextView arrow, title;
        TreeCell(TextView arrow, TextView title) { this.arrow = arrow; this.title = title; }
    }

    // ---------------- 跳章 / 选书 / 失败态 ----------------

    private void openChapter(int index) {
        ChapterSurvey snapshot = survey != null ? survey : cache.current();
        if (snapshot == null) throw new IllegalStateException("章节数据未就绪，无法跳章");
        String id = host.currentBookId();
        if (id == null) throw new IllegalStateException("书上下文未就绪，无法跳章");
        String format = host.currentBookFormat();
        Intent intent;
        if ("epub".equalsIgnoreCase(format)) {
            int spine = index >= 0 && index < snapshot.spineIndex.length
                    ? snapshot.spineIndex[index] : -1;
            intent = new Intent(host, EpubActivity.class)
                    .putExtra("bookId", id)
                    .putExtra("title", host.currentBookTitle())
                    .putExtra("epubTargetSpine", spine);
        } else {
            intent = new Intent(host, ReaderActivity.class)
                    .putExtra("bookId", id)
                    .putExtra("startChapter", index);
        }
        host.startActivity(intent);
        host.finish();
    }

    /** 没有书上下文时的内联复合态（3.3.2：不弹窗、不空白页）。 */
    void showBookPicker() {
        setSubtitle("");
        final List<BookStore.Book> books;
        try { books = sortedBooks(); }
        catch (RuntimeException failure) {
            showNotice(TX_PARSE_FAIL_T, messageOf(failure), BTN_RETRY, this::onEnter);
            return;
        }
        content.removeAllViews();
        boolean emptyLibrary = books.isEmpty();
        // §5.1 行 1 / 1b：无默认书一律用内容区「内联书籍列表」选书，不弹 AlertDialog。
        // 书库 0 本才额外给「去书库」按钮；有书但 opened_ 全为 0 时靠下面的内联列表选书。
        String actionLabel = null;
        Runnable action = null;
        if (emptyLibrary) {
            actionLabel = BTN_GO_LIBRARY;
            action = host::goLibrary;
        }
        content.addView(noticeBlock(host, TX_PICK_BOOK_T,
                        emptyLibrary ? TX_PICK_BOOK_H : TX_PICK_BOOK_NO_HISTORY_H,
                        actionLabel, action),
                new LinearLayout.LayoutParams(-1, -2));

        final int limit = Math.min(books.size(), 80);
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            BookStore.Book book = books.get(i);
            labels.add(book.title + " · " + book.format);
        }
        if (books.size() > limit) labels.add(TX_MORE_BOOKS);
        ListView list = new ListView(host);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setAdapter(new ArrayAdapter<String>(host, 0, labels) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView t = recycled instanceof TextView
                        ? (TextView) recycled : Ui.text(host, "", 14, Ui.INK);
                t.setText(getItem(position));
                t.setPadding(Ui.dp(host, 20), Ui.dp(host, 12), Ui.dp(host, 20), Ui.dp(host, 12));
                t.setTextColor(position >= limit ? Ui.MUTED : Ui.INK);
                return t;
            }
        });
        list.setOnItemClickListener((p, v, position, id) -> {
            if (position >= limit) { host.goLibrary(); return; }
            BookStore.Book book = books.get(position);
            host.setBookContext(book.id, book.title);
        });
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        showContent();
    }

    /** 书上下文行「▸ 更换」触发的选书器：前 80 本 + 末尾「更多书籍请到书库打开」（§9-2）。 */
    void pickBook() {
        final List<BookStore.Book> books;
        try { books = sortedBooks(); }
        catch (RuntimeException failure) {
            showNotice(TX_PARSE_FAIL_T, messageOf(failure), BTN_RETRY, this::pickBook);
            return;
        }
        final int limit = Math.min(books.size(), 80);
        boolean more = books.size() > limit;
        String[] labels = new String[limit + (more ? 1 : 0)];
        for (int i = 0; i < limit; i++) {
            labels[i] = books.get(i).title + " · " + books.get(i).format;
        }
        if (more) labels[limit] = TX_MORE_BOOKS;
        new AlertDialog.Builder(host).setTitle(TX_PICK_BOOK_T)
                .setSingleChoiceItems(labels, -1, (dialog, which) -> {
                    dialog.dismiss();
                    if (which >= limit) { host.goLibrary(); return; }
                    BookStore.Book book = books.get(which);
                    host.setBookContext(book.id, book.title);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 最近阅读优先（opened_<id> desc），稳定排序保持书库同序；全 0 时即书库同序。 */
    private List<BookStore.Book> sortedBooks() {
        List<BookStore.Book> books = new ArrayList<>(host.books());
        final SharedPreferences prefs = host.getSharedPreferences("reader", 0);
        Collections.sort(books, new Comparator<BookStore.Book>() {
            @Override public int compare(BookStore.Book a, BookStore.Book b) {
                return Long.compare(prefs.getLong("opened_" + b.id, 0),
                        prefs.getLong("opened_" + a.id, 0));
            }
        });
        return books;
    }

    private void showFailure(Throwable error) {
        // §8.3 错误分类：只处理真正的异常。pdf/cbz 是格式事实，走 onFormatFact，不进这里。
        setSubtitle("");
        String message = messageOf(error);
        if (message.contains("原文件")) {
            showNotice(TX_NO_FILE_T, TX_NO_FILE_H, BTN_RELINK, () ->
                    host.startActivity(new Intent(host, BookDetailActivity.class)
                            .putExtra("bookId", host.currentBookId())));
        } else if (message.contains("电子书内容为空")) {
            showNotice(TX_EMPTY_FILE_T, TX_EMPTY_FILE_H, null, null);
        } else {
            showNotice(TX_PARSE_FAIL_T, message, null, null);
        }
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}

/** 同文件包级类：一个 bookId → 一次（open + survey），提要/章节树共享；I/O 只在 worker。 */
final class BookSurveyCache {
    interface Listener {
        void onSurvey(long generation, ChapterSurvey survey, boolean complete);
        void onFailure(long generation, Throwable error);
        // 格式事实态（§5.3）：pdf/cbz 在 BookStore.open() 之前判定，0 次 I/O，
        // 不进异常分类、不带重试按钮、不带原始异常。
        void onFormatFact(long generation, boolean pdf);
    }

    private final IdeasActivity host;
    private volatile ChapterSurvey cached;
    private volatile String cachedId;
    private volatile List<String> cachedTags = Collections.emptyList();

    BookSurveyCache(IdeasActivity host) { this.host = host; }

    long generation() { return host.generation; }

    ChapterSurvey current() { return cached; }

    List<String> tags() { return cachedTags; }

    /** 换书 / 离开页面 / 旋转重建时作废缓存；LazyChapterBook 已在 worker finally 里 close()。 */
    void invalidate() {
        host.generation++;
        cached = null;
        cachedId = null;
        cachedTags = Collections.emptyList();
    }

    long load(String bookId, String format, String title, ExecutorService worker, Listener listener) {
        if (cached != null && bookId != null && bookId.equals(cachedId)) {
            listener.onSurvey(host.generation, cached, cached.complete);  // 命中缓存，同步返回，不再 I/O
            return host.generation;
        }
        final long gen = ++host.generation;
        final String kind = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        // §5.3：格式事实在 BookStore.open()（worker 内）之前判定，0 次文件 I/O，
        // 直接走格式事实态，不进异常分类、不进 onFailure。
        if ("pdf".equals(kind) || "cbz".equals(kind)) {
            listener.onFormatFact(gen, "pdf".equals(kind));
            return gen;
        }
        worker.execute(() -> {
            LazyChapterBook book = null;
            try {
                BookStore.Book record = host.bookStore.find(bookId);
                final List<String> tags = host.bookTags(bookId);
                try (BookAccess access = host.bookStore.open(record)) {
                    File file = access.file;
                    // R1：LazyChapterBook.open 必须在 access.close() 之前（file 可能是 /proc/self/fd/N）
                    book = LazyChapterBook.open(file, record.format, host.getCacheDir());
                    ChapterSurvey survey = ChapterSurvey.read(book,
                            ChapterSurvey.DEFAULT_HEAD_CHARS, ChapterSurvey.DEFAULT_MAX_HEADS,
                            ChapterSurvey.DEFAULT_BUDGET_MS,
                            snapshot -> post(gen, () -> {
                                cachedTags = tags;
                                listener.onSurvey(gen, snapshot, false);   // 每批部分结果
                            }));
                    cached = survey;
                    cachedId = bookId;
                    cachedTags = tags;
                    post(gen, () -> listener.onSurvey(gen, survey, survey.complete));
                }
            } catch (RuntimeException failure) {
                // 统一捕获 RuntimeException（含 T01 的 java.io.UncheckedIOException）：
                // head()/read() 只抛 unchecked，绝不能只 catch IOException，否则 worker 线程崩、页面停在加载态。
                fail(gen, listener, failure);
            } catch (Exception failure) {
                // BookStore.find/open、BookAccess.close 的 checked IOException 也一律走 onFailure，不吞错。
                fail(gen, listener, failure);
            } finally {
                if (book != null) book.close();   // 切书 / 离开页面 / 旋转重建 / 异常，一律关闭自己打开的缓存
            }
        });
        return gen;
    }

    private void fail(long gen, Listener listener, Throwable failure) {
        cached = null;
        cachedId = null;
        cachedTags = Collections.emptyList();
        post(gen, () -> listener.onFailure(gen, failure));
    }

    /** worker → UI：runOnUiThread + 世代号 / isDestroyed() 双检；gen 过期不回调 UI。 */
    private void post(long gen, Runnable action) {
        host.runOnUiThread(() -> {
            if (host.isDestroyed() || gen != host.generation) return;
            action.run();
        });
    }
}
