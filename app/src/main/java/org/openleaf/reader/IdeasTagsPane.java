package org.openleaf.reader;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.openleaf.reader.core.TagIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 标签视图：库级维度（全库标签 + 计数），点标签回流书库按 tag_key 过滤。
 * 不读 currentBookId()、不渲染书籍选择器、不显示书上下文行（§1.4 / §5.5）。
 */
final class IdeasTagsPane extends IdeasPane {
    // 加载中是瞬时提示，不属于 §5 契约态；不新增 IdeasPane 文案常量，仅本类私有。
    private static final String LOADING_TAGS = "正在读取标签…";
    private static final String UNIT_BOOKS = " 本";

    private final BookStore store;
    private final ExecutorService worker;

    private List<TagIndex.Entry> entries = new ArrayList<>();

    IdeasTagsPane(IdeasActivity host, BookStore store, ExecutorService worker) {
        super(host);
        this.store = store;
        this.worker = worker;
    }

    @Override void onEnter() {
        final long gen = host.generation;
        showLoading(LOADING_TAGS);
        setSubtitle("");
        worker.execute(() -> {
            final List<TagIndex.Entry> result;
            try {
                Map<String, List<String>> raw = store.allTags();
                result = TagIndex.summarize(raw);
            } catch (RuntimeException failure) {
                postFailure(gen, failure);
                return;
            } catch (Exception failure) {
                // allTags() 抛 checked IOException，统一走错误态，不吞错、不留空白。
                postFailure(gen, failure);
                return;
            }
            host.runOnUiThread(() -> {
                if (gen != host.generation || host.isDestroyed()
                        || host.view != IdeasPane.VIEW_TAGS) return;
                entries = result;
                render();
            });
        });
    }

    private void postFailure(long gen, Throwable failure) {
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        host.runOnUiThread(() -> {
            if (gen != host.generation || host.isDestroyed()
                    || host.view != IdeasPane.VIEW_TAGS) return;
            // §5.1 行 15：读取标签失败 + 重试；按钮重驱 onEnter。
            showNotice(IdeasPane.TX_TAG_FAIL_T, message, IdeasPane.BTN_RETRY, this::onEnter);
        });
    }

    /** 只置可见性/丢弃 UI 侧引用；标签视图无 worker 资源、无写库路径。 */
    @Override void onLeave() {
        setSubtitle("");
    }

    private void render() {
        content.removeAllViews();
        if (entries.isEmpty()) {
            // §5.1 行 16：全库 0 标签的内联空态（去书库）。
            showNotice(IdeasPane.TX_NO_TAG_T, IdeasPane.TX_NO_TAG_H,
                    IdeasPane.BTN_GO_LIBRARY, host::goLibrary);
            return;
        }
        ListView list = new ListView(host);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setAdapter(new TagAdapter(entries));
        list.setOnItemClickListener((p, v, position, id) -> {
            TagIndex.Entry entry = entries.get(position);
            host.startActivity(new Intent(host, MainActivity.class)
                    // 传 normalizeTag 后的 tag_key，与 book_tags.tag_key 精确对应（§7-T03 / §9-8）。
                    .putExtra(MainActivity.EXTRA_TAG, entry.key)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            host.finish();
        });
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        showContent();
        // §5.2：「共 N 个标签 · 点击可到书库筛选」；Locale.ROOT 避免 DefaultLocale lint 告警。
        setSubtitle(String.format(Locale.ROOT, SUBTITLE_TAGS, entries.size()));
    }

    private final class TagAdapter extends ArrayAdapter<TagIndex.Entry> {
        TagAdapter(List<TagIndex.Entry> items) { super(host, 0, items); }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            TextView name, count;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof TagCell) {
                row = (LinearLayout) recycled;
                TagCell cell = (TagCell) row.getTag();
                name = cell.name;
                count = cell.count;
            } else {
                row = Ui.row(host);
                row.setPadding(Ui.dp(host, 16), Ui.dp(host, 12), Ui.dp(host, 16), Ui.dp(host, 12));
                name = Ui.text(host, "", 15, Ui.INK);
                name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                count = Ui.text(host, "", 13, Ui.MUTED);
                row.addView(name);
                row.addView(count);
                row.setTag(new TagCell(name, count));
            }
            TagIndex.Entry entry = getItem(position);
            name.setText("#" + entry.name);
            count.setText(entry.books + UNIT_BOOKS);
            return row;
        }
    }

    private static final class TagCell {
        final TextView name, count;
        TagCell(TextView name, TextView count) {
            this.name = name;
            this.count = count;
        }
    }
}
