package org.openleaf.reader;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 想法区四个视图的公共骨架：常量/文案的唯一出处 + 三态内联渲染（不弹 Toast/Dialog、不留空白页）。 */
abstract class IdeasPane {
    static final int VIEW_IDEAS = 0, VIEW_DIGEST = 1, VIEW_TREE = 2, VIEW_TAGS = 3;
    static final String[] VIEW_LABELS = {"想法", "提要", "章节树", "标签"};

    // Bundle key（Activity 存取，pane 只读）
    static final String K_VIEW = "ideas_view", K_BOOK = "ideas_book", K_BOOK_TITLE = "ideas_book_title",
            K_COLLAPSED = "ideas_collapsed", K_CHAPTER = "ideas_chapter", K_DIGEST_ALL = "ideas_digest_all";

    // 与 IdeasActivity.ACCENT 同值（8.1：沿用既有色值，不新增色值）
    static final int ACCENT = 0xFF3F7D58;

    // ---- 第 5 节三态文案（逐字） ----
    static final String TX_PICK_BOOK_T = "先选一本书";
    static final String TX_PICK_BOOK_H = "书库里还没有书，导入后会在这里生成提要。";
    static final String TX_PICK_BOOK_NO_HISTORY_H = "还没有阅读记录。选一本书后，这里会生成它的章节提要。";
    static final String BTN_GO_LIBRARY = "去书库";
    static final String TX_PDF_T = "PDF 是页面图像，没有可提取的文字";
    static final String TX_PDF_H = "提要需要文本；PDF 请在阅读器里按页阅读。";
    static final String TX_CBZ_T = "漫画是图片页，没有文字提要";
    static final String TX_CBZ_H = "章节树同样不可用。";
    static final String TX_NO_FILE_T = "找不到原文件";
    static final String TX_NO_FILE_H = "文件被移动或删除了，请在书籍详情里重新关联后重试。";
    static final String BTN_RELINK = "去重新关联";
    static final String TX_EMPTY_FILE_T = "这本书里没有文字";
    static final String TX_EMPTY_FILE_H = "文件可能是空文件或只有图片。";
    static final String TX_PARSE_FAIL_T = "这本书读不出来";
    static final String TX_SINGLE_CHAPTER_SUBTITLE = "未分章 · 整本作为一章";
    static final String LOADING_DIGEST = "正在读取章节…";
    static final String LOADING_TREE = "正在读取目录…";
    static final String LOADING_SLOW = "章节较多，仍在读取…";
    static final String TX_HEAD_CAP = "仅展示前 2000 章的提要";
    static final String TX_IDEA_FAIL_T = "读取想法失败";
    static final String BTN_RETRY = "重试";
    static final String TX_TAG_FAIL_T = "读取标签失败";
    static final String TX_NO_TAG_T = "还没有标签";
    static final String TX_NO_TAG_H = "在书籍详情里给书加标签，这里会汇总显示。";
    static final String TX_MORE_BOOKS = "更多书籍请到书库打开";
    static final String TX_SHOW_ALL = "显示全部（共 %d 章）";
    static final String SUBTITLE_DIGEST = "共 %d 章 · 本地生成，不联网";
    static final String SUBTITLE_TREE = "共 %d 章 · 点击标题可跳转";
    static final String SUBTITLE_TAGS = "共 %d 个标签 · 点击可到书库筛选";
    static final String TX_BOOK_PICK = "选择一本书 ▸";
    static final String TX_CHANGE_BOOK = "▸ 更换";

    final IdeasActivity host;
    private final LinearLayout root;
    private final LinearLayout status;
    final LinearLayout content;
    TextView loadingLabel;

    IdeasPane(IdeasActivity host) {
        this.host = host;
        root = Ui.column(host);
        status = Ui.column(host);
        status.setGravity(Gravity.CENTER);
        content = Ui.column(host);
        root.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        status.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
    }

    View root() { return root; }

    final void showLoading(String text) {
        status.removeAllViews();
        loadingLabel = Ui.text(host, text, 15, Ui.MUTED);
        loadingLabel.setGravity(Gravity.CENTER);
        status.addView(loadingLabel, new LinearLayout.LayoutParams(-1, 0, 1));
        status.setVisibility(View.VISIBLE);
        content.setVisibility(View.GONE);
    }

    /** 慢加载提示只改文案，不切换状态块/内容块，避免把已渲染部分打回加载态。 */
    final void setLoadingText(String text) {
        if (loadingLabel != null) loadingLabel.setText(text);
    }

    final void showContent() {
        status.removeAllViews();
        loadingLabel = null;
        status.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
    }

    final void showNotice(String heading, String hint, String actionLabel, Runnable action) {
        status.removeAllViews();
        loadingLabel = null;
        status.addView(noticeBlock(host, heading, hint, actionLabel, action),
                new LinearLayout.LayoutParams(-1, 0, 1));
        status.setVisibility(View.VISIBLE);
        content.setVisibility(View.GONE);
    }

    final void setSubtitle(String text) { host.setSubtitle(text); }

    /** ✧ 44sp 0xFFC9C4BB + 17sp INK + 13sp MUTED 行距 +4dp + 可选 14sp ACCENT 文字按钮。 */
    static LinearLayout noticeBlock(Context c, String heading, String hint,
                                    String actionLabel, Runnable action) {
        LinearLayout box = Ui.column(c);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(c, 40), Ui.dp(c, 60), Ui.dp(c, 40), Ui.dp(c, 60));
        TextView icon = Ui.text(c, "✧", 44, 0xFFC9C4BB);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon);
        Ui.gap(box, 12);
        TextView h = Ui.text(c, heading, 17, Ui.INK);
        h.setGravity(Gravity.CENTER);
        box.addView(h);
        Ui.gap(box, 8);
        TextView hintView = Ui.text(c, hint == null ? "" : hint, 13, Ui.MUTED);
        hintView.setGravity(Gravity.CENTER);
        hintView.setLineSpacing(Ui.dp(c, 4), 1);
        box.addView(hintView);
        if (actionLabel != null && action != null) {
            Ui.gap(box, 10);
            TextView btn = Ui.text(c, actionLabel, 14, ACCENT);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 12));
            btn.setOnClickListener(v -> action.run());
            box.addView(btn);
        }
        return box;
    }

    abstract void onEnter();
    abstract void onLeave();
}
