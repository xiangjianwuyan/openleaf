package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 想法窗口：导入书包时与本地书评冲突的外来书评，按「分享者 — 书 — 书评」收纳。
 * 展示为分层卡片，标题栏与空状态做了视觉美化。
 */
public final class IdeasActivity extends Activity {
    private IdeaStore store;
    private ListView list;
    private View empty;
    private TextView clearButton, subtitle;
    private ArrayList<IdeaStore.Idea> ideas = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();

    private static final int ACCENT = 0xFF3F7D58;      // 主题绿
    private static final int CARD = 0xFFFFFFFF;
    private static final int BORDER = 0xFFE7E5E4;
    private static final int CHIP_BG = 0xFFE9F2EC;

    // ---- 三视图骨架（新增；「想法」视图的老代码原地保留） ----
    BookStore bookStore;
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    volatile long generation;
    int view = IdeasPane.VIEW_IDEAS;
    String bookId, bookTitle, bookFormat;
    int[] collapsed = new int[0];
    int chapter;
    boolean digestAll;
    // T03 导图双模式：只持久化当前是「大纲」还是「导图」，不进 IdeasPane 六个 Bundle key。
    boolean treeMapMode;
    private static final String K_TREE_MAP_MODE = "ideas_tree_map_mode";
    volatile RuntimeException bookContextError;

    private BookSurveyCache cache;
    private IdeasBookPane digestPane, treePane;
    private IdeasTagsPane tagsPane;
    private LinearLayout ideasHost, contentHost, bookContextRow, ideaError;
    private TextView bookContextLabel;
    private final TextView[] tabLabels = new TextView[IdeasPane.VIEW_LABELS.length];
    private final View[] tabUnderlines = new View[IdeasPane.VIEW_LABELS.length];
    private View tagsHost;

    /** 想法区 SAF 回执转发：提要导出等由 pane 发起的 ACTION_CREATE_DOCUMENT 结果回到这里。
     *  两个 IdeasBookPane 都转发是安全的——未暂存待写文本的 pane 会在 onActivityResult 内直接返回。 */
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (digestPane != null) digestPane.onActivityResult(request, result, data);
        if (treePane != null) treePane.onActivityResult(request, result, data);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new IdeaStore(this);
        bookStore = new BookStore(this);
        cache = new BookSurveyCache(this);
        digestPane = new IdeasBookPane(this, cache, IdeasBookPane.Mode.DIGEST);
        treePane = new IdeasBookPane(this, cache, IdeasBookPane.Mode.TREE);
        tagsPane = new IdeasTagsPane(this, bookStore, worker);
        try { restoreState(state); }
        catch (RuntimeException failure) { bookContextError = failure; clearBook(); }
        LinearLayout root = Ui.column(this);
        Ui.mount(this, root);

        // ---- 顶部栏：轻量文字按钮 + 居中标题 ----
        LinearLayout header = Ui.row(this);
        header.setBackgroundColor(CARD);
        header.setPadding(Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14));
        TextView back = ghostButton("‹  返回", 15, Ui.MUTED);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = Ui.title(this, "想法", 19);
        title.setGravity(android.view.Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        header.addView(title);
        clearButton = ghostButton("清空", 14, ACCENT);
        clearButton.setOnClickListener(v -> clearAll());
        header.addView(clearButton);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        root.addView(divider, new LinearLayout.LayoutParams(-1, 1));

        // ---- 副标题 ----
        subtitle = Ui.text(this, "", 13, Ui.MUTED);
        subtitle.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 4));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        // ---- 切换条 / 书上下文行 / 内容宿主（新增） ----
        buildChrome(root);

        // ---- 空状态 ----
        empty = buildEmptyState();
        ideasHost.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- 列表 ----
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 24));
        list.setAdapter(new Adapter());
        list.setOnItemLongClickListener((p, v, index, id) -> {
            confirmDelete(ideas.get(index));
            return true;
        });
        ideasHost.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        ideaError = Ui.column(this);
        ideaError.setVisibility(View.GONE);
        ideasHost.addView(ideaError, new LinearLayout.LayoutParams(-1, 0, 1));

        contentHost.addView(ideasHost, new LinearLayout.LayoutParams(-1, 0, 1));
        contentHost.addView(digestPane.root(), new LinearLayout.LayoutParams(-1, 0, 1));
        contentHost.addView(treePane.root(), new LinearLayout.LayoutParams(-1, 0, 1));
        contentHost.addView(tagsHost, new LinearLayout.LayoutParams(-1, 0, 1));
        refresh();
        selectView(view);
    }

    /** 4 等宽切换条（选中 ACCENT + 2dp 下划线）、书上下文行、内容宿主。 */
    private void buildChrome(LinearLayout root) {
        LinearLayout tabs = Ui.row(this);
        tabs.setBackgroundColor(CARD);
        for (int i = 0; i < IdeasPane.VIEW_LABELS.length; i++) {
            LinearLayout tab = Ui.column(this);
            tab.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), 0);
            TextView label = Ui.text(this, IdeasPane.VIEW_LABELS[i], 14, Ui.MUTED);
            label.setGravity(Gravity.CENTER);
            tab.addView(label, new LinearLayout.LayoutParams(-1, -2));
            View underline = new View(this);
            underline.setBackgroundColor(Color.TRANSPARENT);
            tab.addView(underline, new LinearLayout.LayoutParams(-1, Ui.dp(this, 2)));
            final int index = i;
            tab.setOnClickListener(v -> selectView(index));
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
            tabLabels[i] = label;
            tabUnderlines[i] = underline;
        }
        root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        View tabDivider = new View(this);
        tabDivider.setBackgroundColor(BORDER);
        root.addView(tabDivider, new LinearLayout.LayoutParams(-1, 1));

        bookContextRow = Ui.row(this);
        bookContextRow.setBackgroundColor(CARD);
        bookContextRow.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        bookContextLabel = Ui.text(this, "", 14, ACCENT);
        bookContextLabel.setOnClickListener(v -> {
            IdeasBookPane pane = view == IdeasPane.VIEW_TREE ? treePane : digestPane;
            // 有书 → 「▸ 更换」弹换书 Dialog；无书 → 「选择一本书 ▸」走内联选书列表（不弹窗，§5.1 行 1/1b、§3.3.2）
            if (bookId == null) pane.showBookPicker();
            else pane.pickBook();
        });
        bookContextRow.addView(bookContextLabel);
        root.addView(bookContextRow, new LinearLayout.LayoutParams(-1, -2));

        contentHost = Ui.column(this);
        root.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1));
        ideasHost = Ui.column(this);

        // T03：标签视图为库级 pane（不显示书上下文行、不渲染书籍选择器）。
        tagsHost = tagsPane.root();
    }

    /** T02 只做「去书库」跳转；标签回流与版本号由后续任务接入，此处不引入。 */
    void goLibrary() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void restoreState(Bundle state) {
        if (state != null) {
            view = state.getInt(IdeasPane.K_VIEW, IdeasPane.VIEW_IDEAS);
            bookId = state.getString(IdeasPane.K_BOOK);
            bookTitle = state.getString(IdeasPane.K_BOOK_TITLE);
            int[] saved = state.getIntArray(IdeasPane.K_COLLAPSED);
            collapsed = saved == null ? new int[0] : saved;
            chapter = state.getInt(IdeasPane.K_CHAPTER, 0);
            digestAll = state.getBoolean(IdeasPane.K_DIGEST_ALL, false);
            treeMapMode = state.getBoolean(K_TREE_MAP_MODE, false);
        }
        if (view < IdeasPane.VIEW_IDEAS || view > IdeasPane.VIEW_TAGS) view = IdeasPane.VIEW_IDEAS;
        initBookContext();
    }

    /** 恢复时校验书仍在书库（#17）；否则回退 defaultBookId()，仍为 null 由 pane 显示选书态。 */
    private void initBookContext() {
        if (bookId != null) {
            try {
                BookStore.Book record = bookStore.find(bookId);
                bookFormat = record.format;
                if (bookTitle == null) bookTitle = record.title;
                return;
            } catch (Exception removed) {
                clearBook();
            }
        }
        try {
            String def = defaultBookId();
            if (def == null) { clearBook(); return; }
            BookStore.Book record = bookStore.find(def);
            bookId = record.id;
            bookTitle = record.title;
            bookFormat = record.format;
        } catch (Exception failure) {
            clearBook();
            throw new RuntimeException("读取书库失败", failure);
        }
    }

    /** Q1：opened_<id> 时间戳最大且 > 0 的那本；书库为空或全为 0 → null（不静默挑书）。 */
    String defaultBookId() {
        try {
            List<BookStore.Book> books = bookStore.all();
            SharedPreferences prefs = getSharedPreferences("reader", 0);
            long best = 0;
            String bestId = null;
            for (BookStore.Book book : books) {
                long opened = prefs.getLong("opened_" + book.id, 0);
                if (opened > best) { best = opened; bestId = book.id; }
            }
            return bestId;
        } catch (Exception failure) {
            throw new RuntimeException("读取书库失败", failure);
        }
    }

    private void clearBook() {
        // T03：书上下文被清掉时顺带移除本书导图编辑（只改此处，不碰 BookStore 删书清单）。
        String oldBookId = bookId;
        bookId = null; bookTitle = null; bookFormat = null;
        if (oldBookId != null && !oldBookId.isEmpty()) new MindMapStore(this).remove(oldBookId);
    }

    /** 书库读取失败后的重试：重新初始化书上下文并重驱当前视图（状态仍在 Bundle/字段里）。 */
    void retryBookContext() {
        bookContextError = null;
        try { initBookContext(); }
        catch (RuntimeException failure) { bookContextError = failure; clearBook(); }
        selectView(view);
    }

    List<BookStore.Book> books() {
        try { return bookStore.all(); }
        catch (Exception failure) { throw new RuntimeException("读取书库失败", failure); }
    }

    List<String> bookTags(String id) {
        try { return bookStore.tags(id); }
        catch (Exception failure) { throw new RuntimeException("读取标签失败", failure); }
    }

    void setSubtitle(String text) { subtitle.setText(text == null ? "" : text); }

    String currentBookId() { return bookId; }

    String currentBookTitle() { return bookTitle; }

    String currentBookFormat() { return bookFormat; }

    /** 选书后刷新书上下文行 + 让两个书级 pane 重载（先 invalidate，旧书不残留）。 */
    void setBookContext(String id, String title) {
        try {
            BookStore.Book record = bookStore.find(id);
            bookId = record.id;
            bookTitle = title == null ? record.title : title;
            bookFormat = record.format;
        } catch (Exception failure) {
            throw new RuntimeException("选择书籍失败", failure);
        }
        generation++;
        cache.invalidate();
        collapsed = new int[0];
        chapter = 0;
        digestAll = false;
        updateBookContextRow();
        if (view == IdeasPane.VIEW_DIGEST) digestPane.onEnter();
        else if (view == IdeasPane.VIEW_TREE) treePane.onEnter();
    }

    /** 当前章高亮（3.3.4）：epub 用 epub_chapter_<id> 映射 spineIndex，其余用 chapter_<id>。 */
    int currentChapterOf(String bookId, int totalChapters, int[] spineIndex, String format) {
        if (totalChapters <= 0) return 0;
        SharedPreferences prefs = getSharedPreferences("reader", 0);
        if ("epub".equalsIgnoreCase(format)) {
            int spine = prefs.getInt("epub_chapter_" + bookId, -1);
            if (spine >= 0 && spineIndex != null) {
                for (int i = 0; i < spineIndex.length; i++) if (spineIndex[i] == spine) return i;
            }
        }
        int saved = prefs.getInt("chapter_" + bookId, 0);
        return Math.max(0, Math.min(saved, totalChapters - 1));
    }

    void selectView(int target) {
        if (target < IdeasPane.VIEW_IDEAS || target > IdeasPane.VIEW_TAGS) return;
        int previous = view;
        view = target;
        for (int i = 0; i < tabLabels.length; i++) {
            boolean on = i == target;
            tabLabels[i].setTextColor(on ? ACCENT : Ui.MUTED);
            tabUnderlines[i].setBackgroundColor(on ? ACCENT : Color.TRANSPARENT);
        }
        updateBookContextRow();
        clearButton.setVisibility(target == IdeasPane.VIEW_IDEAS && !ideas.isEmpty()
                ? View.VISIBLE : View.GONE);
        ideasHost.setVisibility(target == IdeasPane.VIEW_IDEAS ? View.VISIBLE : View.GONE);
        digestPane.root().setVisibility(target == IdeasPane.VIEW_DIGEST ? View.VISIBLE : View.GONE);
        treePane.root().setVisibility(target == IdeasPane.VIEW_TREE ? View.VISIBLE : View.GONE);
        tagsHost.setVisibility(target == IdeasPane.VIEW_TAGS ? View.VISIBLE : View.GONE);
        if (previous != target) {
            if (previous == IdeasPane.VIEW_DIGEST) digestPane.onLeave();
            else if (previous == IdeasPane.VIEW_TREE) treePane.onLeave();
            else if (previous == IdeasPane.VIEW_TAGS) tagsPane.onLeave();
        }
        if (target == IdeasPane.VIEW_IDEAS) refresh();
        else if (target == IdeasPane.VIEW_DIGEST) digestPane.onEnter();
        else if (target == IdeasPane.VIEW_TREE) treePane.onEnter();
        else if (target == IdeasPane.VIEW_TAGS) tagsPane.onEnter();
    }

    private void updateBookContextRow() {
        boolean show = view == IdeasPane.VIEW_DIGEST || view == IdeasPane.VIEW_TREE;
        bookContextRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            bookContextLabel.setText(bookId == null ? IdeasPane.TX_BOOK_PICK
                    : "《" + (bookTitle == null ? "" : bookTitle) + "》 " + IdeasPane.TX_CHANGE_BOOK);
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(IdeasPane.K_VIEW, view);
        out.putString(IdeasPane.K_BOOK, bookId);
        out.putString(IdeasPane.K_BOOK_TITLE, bookTitle);
        out.putIntArray(IdeasPane.K_COLLAPSED, collapsed);
        out.putInt(IdeasPane.K_CHAPTER, chapter);
        out.putBoolean(IdeasPane.K_DIGEST_ALL, digestAll);
        out.putBoolean(K_TREE_MAP_MODE, treeMapMode);
    }

    @Override protected void onDestroy() {
        generation++;
        cache.invalidate();          // 作废 survey 缓存 + 世代，在途回调一律失效
        worker.shutdown();           // 不是 shutdownNow()：让在途任务自己 finally close()
        super.onDestroy();
    }

    private TextView ghostButton(String label, int size, int color) {
        TextView t = Ui.text(this, label, size, color);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        t.setBackground(createRoundBg(0x14000000, Ui.dp(this, 8)));
        return t;
    }

    private View buildEmptyState() {
        LinearLayout box = Ui.column(this);
        box.setGravity(android.view.Gravity.CENTER);
        box.setPadding(Ui.dp(this, 40), Ui.dp(this, 60), Ui.dp(this, 40), Ui.dp(this, 60));
        TextView icon = Ui.text(this, "✧", 44, 0xFFC9C4BB);
        icon.setGravity(android.view.Gravity.CENTER);
        box.addView(icon);
        Ui.gap(box, 12);
        TextView hint = Ui.text(this, "还没有想法", 17, Ui.INK);
        hint.setGravity(android.view.Gravity.CENTER);
        box.addView(hint);
        Ui.gap(box, 8);
        TextView hint2 = Ui.text(this,
                "导入书包时遇到不同的书评，外来书评会按\n「分享者 — 书 — 书评」收进这里，不会覆盖本地书评。",
                13, Ui.MUTED);
        hint2.setGravity(android.view.Gravity.CENTER);
        hint2.setLineSpacing(Ui.dp(this, 4), 1);
        box.addView(hint2);
        return box;
    }

    private static GradientDrawable createRoundBg(int color, int radiusDp) {
        // 用于代码构建的圆角背景；radius 为 px，由调用方传 dp 值需先换算
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp);
        return d;
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        if (view != IdeasPane.VIEW_IDEAS) return;
        try {
            ideas = new ArrayList<>(store.list());
            boolean isEmpty = ideas.isEmpty();
            empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            ideaError.setVisibility(View.GONE);
            subtitle.setText(isEmpty ? "" : "共 " + ideas.size() + " 条 · 长按卡片可删除");
            clearButton.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            ((Adapter) list.getAdapter()).notifyDataSetChanged();
        } catch (RuntimeException failure) {
            ideas = new ArrayList<>();
            empty.setVisibility(View.GONE);
            list.setVisibility(View.GONE);
            clearButton.setVisibility(View.GONE);
            subtitle.setText("");
            String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            ideaError.removeAllViews();
            ideaError.addView(IdeasPane.noticeBlock(this, IdeasPane.TX_IDEA_FAIL_T, message,
                            IdeasPane.BTN_RETRY, this::refresh),
                    new LinearLayout.LayoutParams(-1, 0, 1));
            ideaError.setVisibility(View.VISIBLE);
        }
    }

    private void confirmDelete(IdeaStore.Idea idea) {
        new AlertDialog.Builder(this).setTitle("删除想法")
                .setMessage(idea.sharer + " — " + idea.bookTitle + "\n\n删除后不可恢复，本地书评不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> { store.delete(idea.id); refresh(); })
                .show();
    }

    private void clearAll() {
        if (ideas.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("清空想法")
                .setMessage("将删除全部 " + ideas.size() + " 条想法，本地书评不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    for (IdeaStore.Idea idea : ideas) store.delete(idea.id);
                    refresh();
                }).show();
    }

    private static String timeLabel(long millis) {
        if (millis <= 0) return "";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    private final class Adapter extends ArrayAdapter<IdeaStore.Idea> {
        Adapter() { super(IdeasActivity.this, 0, ideas); }
        @Override public int getCount() { return ideas.size(); }
        @Override public IdeaStore.Idea getItem(int position) { return ideas.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            ViewHolder holder;
            View card;
            if (recycled instanceof View && recycled.getTag() instanceof ViewHolder) {
                card = recycled;
                holder = (ViewHolder) recycled.getTag();
            } else {
                card = buildCard();
                holder = (ViewHolder) card.getTag();
            }
            final IdeaStore.Idea idea = ideas.get(position);
            String initial = idea.sharer == null || idea.sharer.isEmpty() ? "?" : idea.sharer.substring(0, 1);
            holder.avatar.setText(initial);
            holder.sharer.setText(idea.sharer == null ? "未知分享者" : idea.sharer);
            holder.time.setText(timeLabel(idea.createdAt));
            holder.book.setText(idea.bookTitle == null ? "未知书籍" : idea.bookTitle);
            String review = idea.review == null ? "" : idea.review;
            boolean isExpanded = expanded.contains(idea.id);
            holder.review.setText(review.isEmpty() ? "（这条想法没有书评正文）" : review);
            holder.review.setMaxLines(isExpanded ? Integer.MAX_VALUE : 4);
            holder.review.setEllipsize(isExpanded ? null : android.text.TextUtils.TruncateAt.END);
            final boolean needsMore = review.length() > 90;
            holder.more.setVisibility(needsMore ? View.VISIBLE : View.GONE);
            holder.more.setText(isExpanded ? "收起" : "展开全部");
            holder.more.setOnClickListener(v -> {
                if (expanded.contains(idea.id)) expanded.remove(idea.id);
                else expanded.add(idea.id);
                notifyDataSetChanged();
            });
            return card;
        }

        private View buildCard() {
            LinearLayout outer = new LinearLayout(IdeasActivity.this);
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setPadding(Ui.dp(IdeasActivity.this, 18), Ui.dp(IdeasActivity.this, 8),
                    Ui.dp(IdeasActivity.this, 18), Ui.dp(IdeasActivity.this, 8));

            LinearLayout card = new LinearLayout(IdeasActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            bg.setCornerRadius(Ui.dp(IdeasActivity.this, 14));
            bg.setStroke(1, BORDER);
            card.setBackground(bg);
            card.setPadding(Ui.dp(IdeasActivity.this, 16), Ui.dp(IdeasActivity.this, 14),
                    Ui.dp(IdeasActivity.this, 16), Ui.dp(IdeasActivity.this, 14));

            // 第一行：头像 + 分享者 + 时间
            LinearLayout head = Ui.row(IdeasActivity.this);
            TextView avatar = new TextView(IdeasActivity.this);
            avatar.setTextSize(13);
            avatar.setTextColor(Color.WHITE);
            avatar.setGravity(android.view.Gravity.CENTER);
            avatar.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            GradientDrawable avBg = new GradientDrawable();
            avBg.setColor(ACCENT);
            avBg.setCornerRadius(Ui.dp(IdeasActivity.this, 18));
            avatar.setBackground(avBg);
            int avSize = Ui.dp(IdeasActivity.this, 36);
            head.addView(avatar, new LinearLayout.LayoutParams(avSize, avSize));
            LinearLayout who = Ui.column(IdeasActivity.this);
            who.setPadding(Ui.dp(IdeasActivity.this, 10), 0, 0, 0);
            TextView sharer = Ui.text(IdeasActivity.this, "", 15, Ui.INK);
            sharer.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            who.addView(sharer);
            TextView time = Ui.text(IdeasActivity.this, "", 11, Ui.MUTED);
            who.addView(time);
            head.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(head);
            Ui.gap(card, 12);

            // 第二行：书名标签
            TextView book = Ui.text(IdeasActivity.this, "", 13, ACCENT);
            GradientDrawable chip = new GradientDrawable();
            chip.setColor(CHIP_BG);
            chip.setCornerRadius(Ui.dp(IdeasActivity.this, 6));
            book.setBackground(chip);
            book.setPadding(Ui.dp(IdeasActivity.this, 8), Ui.dp(IdeasActivity.this, 3),
                    Ui.dp(IdeasActivity.this, 8), Ui.dp(IdeasActivity.this, 3));
            card.addView(book);
            Ui.gap(card, 10);

            // 第三行：书评正文
            TextView review = Ui.text(IdeasActivity.this, "", 14, Ui.GREEN);
            review.setLineSpacing(Ui.dp(IdeasActivity.this, 4), 1.15f);
            card.addView(review);
            TextView more = Ui.text(IdeasActivity.this, "展开全部", 12, ACCENT);
            more.setPadding(0, Ui.dp(IdeasActivity.this, 6), 0, 0);
            card.addView(more);

            outer.addView(card);
            outer.setTag(new ViewHolder(avatar, sharer, time, book, review, more));
            return outer;
        }
    }

    private static final class ViewHolder {
        final TextView avatar, sharer, time, book, review, more;
        ViewHolder(TextView avatar, TextView sharer, TextView time, TextView book, TextView review, TextView more) {
            this.avatar = avatar;
            this.sharer = sharer;
            this.time = time;
            this.book = book;
            this.review = review;
            this.more = more;
        }
    }
}
