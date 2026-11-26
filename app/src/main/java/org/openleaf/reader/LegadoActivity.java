package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LegadoActivity extends Activity {
    private static final int IMPORT = 910, EXPORT = 911, LIMIT = 512 * 1024, MAX_SOURCES = 100;
    private static final int COVER_LIMIT = 1024 * 1024, COVER_CACHE = 40, COVER_EDGE = 360;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String, Bitmap> coverCache = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > COVER_CACHE;
        }
    };
    private final Set<String> coverFailed = Collections.synchronizedSet(new HashSet<String>());
    private final LegadoClient client = new LegadoClient();
    private SourceStore sourceStore;
    private final LinkedHashMap<String, LegadoClient.Source> sources = new LinkedHashMap<>();
    private LinearLayout body;
    private TextView status, heading;
    private CancellationSignal running;
    private int generation, page = 1, stage, chapterIndex;
    private String keyword = "";
    private LegadoClient.Source source;
    private LegadoClient.Book book;
    private List<LegadoClient.Book> results = new ArrayList<>();
    private List<LegadoClient.Chapter> chapters = new ArrayList<>();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = Ui.column(this);
        Ui.mount(this, root);
        LinearLayout top = Ui.row(this);
        Ui.pad(top, 8);
        top.addView(Ui.button(this, "返回", this::onBackPressed));
        heading = Ui.title(this, "Legado 书源", 20);
        Ui.pad(heading, 8);
        top.addView(heading, Ui.weight());
        root.addView(top);
        status = Ui.text(this, "", 13, Ui.MUTED);
        Ui.pad(status, 12);
        root.addView(status);
        body = Ui.column(this);
        body.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        try {
            sourceStore = new SourceStore(this);
            sources.putAll(sourceStore.load());
            home();
        } catch (Exception error) {
            home();
            status.setText("读取已保存书源失败。" + errorMessage(error));
        }
    }

    private void home() {
        reset(0, "Legado 书源");
        LinearLayout tools = Ui.row(this);
        tools.addView(Ui.primary(this, "导入 JSON", () -> pick(false)), Ui.weight());
        tools.addView(Ui.button(this, "粘贴书源", this::paste), Ui.weight());
        tools.addView(Ui.button(this, "导出", () -> pick(true)), Ui.weight());
        body.addView(tools);
        LinearLayout tools2 = Ui.row(this);
        tools2.addView(Ui.button(this, "检查在线书更新", this::checkOnlineUpdates), Ui.weight());
        body.addView(tools2);
        Ui.gap(body, 12);
        if (sources.isEmpty()) {
            status.setText("请导入 Legado JSON 文件或粘贴配置。支持静态 HTTPS 书源。");
            return;
        }
        ArrayList<LegadoClient.Source> available = new ArrayList<>(sources.values());
        List<String> labels = new ArrayList<>();
        for (LegadoClient.Source item : available) labels.add(item.name);
        Spinner selector = new Spinner(this);
        selector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        body.addView(selector);
        int selected = available.indexOf(source);
        if (selected >= 0) selector.setSelection(selected);
        LinearLayout search = Ui.row(this);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("输入书名或作者");
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
        input.setText(keyword);
        search.addView(input, Ui.weight());
        Runnable submit = () -> {
            String query = input.getText().toString().trim();
            if (query.isEmpty()) { status.setText("请输入搜索关键词。"); return; }
            source = available.get(selector.getSelectedItemPosition());
            keyword = query;
            hideKeyboard(input);
            search(1);
        };
        search.addView(Ui.primary(this, "搜书", submit));
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit.run();
            return true;
        });
        body.addView(search);
        body.addView(Ui.button(this, "移除所选书源", () -> {
            LegadoClient.Source item = available.get(selector.getSelectedItemPosition());
            new AlertDialog.Builder(this).setTitle("移除书源？").setMessage(item.name)
                    .setNegativeButton("取消", null).setPositiveButton("移除", (d, w) -> {
                        LinkedHashMap<String, LegadoClient.Source> updated = new LinkedHashMap<>(sources);
                        updated.remove(item.url);
                        try { save(updated); home(); }
                        catch (Exception error) { status.setText(errorMessage(error)); }
                    }).show();
        }));
        status.setText("已导入 " + sources.size() + " 个书源。选择书源后搜索。");
    }

    private void search(int requestedPage) {
        reset(1, "搜索 · " + source.name);
        request("正在搜索…", signal -> () -> client.search(source, keyword, requestedPage, signal), found -> {
            page = requestedPage;
            results = found;
            showResults();
        });
    }

    private void showResults() {
        reset(1, "搜索 · " + source.name);
        status.setText(keyword + " · 第 " + page + " 页 · " + results.size() + " 项");
        showResultList();
        LinearLayout nav = Ui.row(this);
        android.widget.Button previous = Ui.button(this, "上一页", () -> search(page - 1));
        previous.setEnabled(page > 1);
        android.widget.Button next = Ui.button(this, "下一页", () -> search(page + 1));
        next.setEnabled(!results.isEmpty() && page < 100 && source.searchUrl.contains("{{page}}"));
        nav.addView(previous, Ui.weight());
        nav.addView(next, Ui.weight());
        body.addView(nav);
    }

    private void details(LegadoClient.Book selected) {
        reset(2, selected.title);
        request("正在读取详情…", signal -> () -> client.detail(source, selected, signal), item -> {
            book = item;
            status.setText(item.author == null || item.author.isEmpty() ? source.name : item.author);
            LinearLayout content = scrolled();
            LinearLayout head = Ui.row(this);
            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setImageDrawable(Ui.shape(covers[Math.floorMod(item.title.hashCode(), covers.length)], 6, this));
            cover.setContentDescription(item.title + " 封面");
            head.addView(cover, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 104)));
            LinearLayout headInfo = Ui.column(this);
            headInfo.addView(Ui.title(this, item.title, 19));
            if (item.author != null && !item.author.isEmpty())
                headInfo.addView(Ui.text(this, item.author, 13, Ui.MUTED));
            head.addView(headInfo, new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(head);
            Ui.gap(content, 12);
            loadCover(cover, item.cover, generation);
            if (item.intro != null && !item.intro.isEmpty()) {
                TextView intro = Ui.text(this, item.intro, 16, Ui.INK);
                intro.setLineSpacing(Ui.dp(this, 4), 1.15f);
                content.addView(intro);
                Ui.gap(content, 12);
            }
            content.addView(Ui.primary(this, "打开目录", this::loadChapters));
            Ui.gap(content, 8);
            content.addView(Ui.button(this, "加入书架（离线可读）", this::addToShelf));
        });
    }

    /** 加入书架：只抓目录生成骨架 + 存章节清单，正文按章缓存、按需下载。 */
    private void addToShelf() {
        if (book == null) { android.widget.Toast.makeText(this, "请先选择书籍", android.widget.Toast.LENGTH_SHORT).show(); return; }
        request("正在抓取目录…", signal -> () -> client.toc(source, book, signal), items -> {
            chapters = items;
            if (chapters.isEmpty()) { android.widget.Toast.makeText(this, "目录为空，无法入库", android.widget.Toast.LENGTH_SHORT).show(); return; }
            worker.execute(() -> {
                try {
                    // 骨架：每章标题 + 占位，LazyChapterBook 得到正确章节数/标题，进度锚点可复用
                    List<org.openleaf.reader.core.OnlineChapter.Entry> entries = new ArrayList<>();
                    for (LegadoClient.Chapter chapter : chapters)
                        entries.add(new org.openleaf.reader.core.OnlineChapter.Entry(entries.size(), chapter.title, chapter.url));
                    String skeleton = org.openleaf.reader.core.OnlineChapter.skeletonText(entries);
                    BookStore store = new BookStore(this);
                    BookStore.ImportResult result = store.importOnlineText(book.title, skeleton, book.url);
                    // 章节清单 + source 规则写入 remote_meta，供逐章下载重建
                    String sourceJson = source.json().toString();
                    String manifest = new OnlineChapterStore(this).manifest(sourceJson, entries);
                    String remoteKey = org.openleaf.reader.core.OnlineLibrary.remoteKey(source.url, null, book.url);
                    new OnlineBookStore(this).bind(result.book.id, source.url, remoteKey, sourceVersion(), manifest);
                    final BookStore.ImportResult saved = result;
                    final int chapterCount = entries.size();
                    runOnUiThread(() -> {
                        android.widget.Toast.makeText(this, saved.duplicate ? "书架已有此书" : "已加入书架 · " + chapterCount + " 章（逐章缓存）", android.widget.Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this, "入库失败：" + error.getMessage(), android.widget.Toast.LENGTH_SHORT).show());
                }
            });
        });
    }

    /** 真抓远端目录检查新章节；只有规则变化时仍按原口径给出引导。 */
    private void checkOnlineUpdates() {
        status.setText("正在检查在线书目录…");
        worker.execute(() -> {
            final List<ChapterUpdateChecker.Result> all =
                    ChapterUpdateChecker.checkAll(this, new android.os.CancellationSignal());
            runOnUiThread(() -> {
                final List<ChapterUpdateChecker.Result> actionable = new ArrayList<>();
                for (ChapterUpdateChecker.Result item : all) if (item.canReparse()) actionable.add(item);
                if (actionable.isEmpty()) {
                    status.setText("检查完成：" + all.size() + " 本在线书均无新章节（规则变化已单独提示）。");
                    return;
                }
                final String[] labels = new String[actionable.size()];
                for (int i = 0; i < actionable.size(); i++)
                    labels[i] = ChapterUpdateChecker.describe(actionable.get(i));
                new AlertDialog.Builder(this).setTitle("发现 " + actionable.size() + " 本在线书有更新")
                        .setMessage("选择一本「重新解析」：重抓目录并保留进度与已缓存章节。")
                        .setSingleChoiceItems(labels, -1, null)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("重新解析选中项", (dialog, which) -> {
                            android.widget.ListView list = ((AlertDialog) dialog).getListView();
                            int checked = list.getCheckedItemPosition();
                            if (checked < 0 || checked >= actionable.size()) {
                                status.setText("未选择书籍");
                                return;
                            }
                            final String bookId = actionable.get(checked).bookId;
                            status.setText("正在重新解析…");
                            worker.execute(() -> {
                                final OnlineBookReparser.Result result =
                                        new OnlineBookReparser(this).reparse(bookId);
                                if (result.success) ChapterUpdateChecker.clearPending(this, bookId);
                                runOnUiThread(() -> {
                                    status.setText(result.message);
                                    android.widget.Toast.makeText(this, result.message,
                                            android.widget.Toast.LENGTH_SHORT).show();
                                });
                            });
                        }).show();
                status.setText("检查完成：" + actionable.size() + " 本需重新解析。");
            });
        });
    }

    /** 规则版本：与 SourceStore/ReaderActivity 共用同一规范化算法（字段白名单 + 键排序 + 长度前缀）。 */
    private String sourceVersion() {
        return SourceStore.ruleVersion(source);
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }

    private void loadChapters() {
        reset(3, book.title);
        request("正在读取目录…", signal -> () -> client.toc(source, book, signal), items -> {
            chapters = items;
            showChapters();
        });
    }

    private void showChapters() {
        reset(3, book.title);
        status.setText("目录 · " + chapters.size() + " 章");
        ArrayList<String> labels = new ArrayList<>();
        for (LegadoClient.Chapter chapter : chapters) labels.add(chapter.title);
        showList(labels, this::readChapter);
    }

    private void readChapter(int index) {
        LegadoClient.Chapter chapter = chapters.get(index);
        chapterIndex = index;
        reset(4, chapter.title);
        request("正在读取正文…", signal -> () -> client.content(source, chapter, signal), content -> {
            status.setText(book.title + " · " + (chapterIndex + 1) + " / " + chapters.size());
            LinearLayout textBody = scrolled();
            TextView text = Ui.text(this, content.text, 19, Ui.INK);
            text.setTextIsSelectable(true);
            text.setLineSpacing(Ui.dp(this, 5), 1.25f);
            int margin = Math.max(8, (getResources().getConfiguration().screenWidthDp - 720) / 2);
            text.setPadding(Ui.dp(this, margin), Ui.dp(this, 12), Ui.dp(this, margin), Ui.dp(this, 24));
            textBody.addView(text);
            LinearLayout nav = Ui.row(this);
            android.widget.Button previous = Ui.button(this, "上一章", () -> readChapter(index - 1));
            previous.setEnabled(index > 0);
            android.widget.Button next = Ui.button(this, "下一章", () -> readChapter(index + 1));
            next.setEnabled(index + 1 < chapters.size());
            nav.addView(previous, Ui.weight());
            nav.addView(Ui.button(this, "目录", this::showChapters), Ui.weight());
            nav.addView(next, Ui.weight());
            body.addView(nav);
        });
    }

    private void showResultList() {
        final int token = generation;
        ListView list = new ListView(this);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return results.size(); }
            @Override public Object getItem(int position) { return results.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View reuse, ViewGroup parent) {
                LinearLayout row = reuse instanceof LinearLayout ? (LinearLayout) reuse : Ui.row(LegadoActivity.this);
                row.removeAllViews();
                Ui.pad(row, 8);
                final LegadoClient.Book item = results.get(position);
                ImageView cover = new ImageView(LegadoActivity.this);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setImageDrawable(Ui.shape(covers[Math.floorMod(item.title.hashCode(), covers.length)], 6, LegadoActivity.this));
                cover.setContentDescription(item.title + " 封面");
                row.addView(cover, new LinearLayout.LayoutParams(Ui.dp(LegadoActivity.this, 48), Ui.dp(LegadoActivity.this, 68)));
                LinearLayout info = Ui.column(LegadoActivity.this);
                info.addView(Ui.title(LegadoActivity.this, item.title, 15));
                if (item.author != null && !item.author.isEmpty())
                    info.addView(Ui.text(LegadoActivity.this, item.author, 12, Ui.MUTED));
                row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
                loadCover(cover, item.cover, token);
                return row;
            }
        });
        list.setOnItemClickListener((p, v, index, id) -> details(results.get(index)));
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private final int[] covers = {0xFF315D4F, 0xFF8B6249, 0xFF59687A, 0xFF8C7752};

    private void loadCover(ImageView target, String address, int token) {
        target.setTag(address == null ? "" : address);
        if (address == null || address.isEmpty() || coverFailed.contains(address)) return;
        Bitmap cached = coverCache.get(address);
        if (cached != null) { target.setImageBitmap(cached); return; }
        worker.execute(() -> {
            Bitmap bitmap = null;
            try {
                byte[] bytes = MediaSourceClient.download(address, COVER_LIMIT, null);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8192 || bounds.outHeight > 8192)
                    throw new java.io.IOException("封面尺寸无效");
                int sample = 1;
                while (bounds.outWidth / sample > COVER_EDGE || bounds.outHeight / sample > COVER_EDGE) sample *= 2;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null) throw new java.io.IOException("封面解码失败");
            } catch (Exception ignored) {
                coverFailed.add(address);
            }
            final Bitmap decoded = bitmap;
            runOnUiThread(() -> {
                if (isDestroyed() || token != generation) { if (decoded != null) decoded.recycle(); return; }
                if (decoded != null) coverCache.put(address, decoded);
                if (address.equals(target.getTag())) {
                    if (decoded != null) target.setImageBitmap(decoded);
                    else target.setImageDrawable(Ui.shape(covers[Math.floorMod(address.hashCode(), covers.length)], 6, LegadoActivity.this));
                }
            });
        });
    }

    private void showList(List<String> labels, java.util.function.IntConsumer select) {
        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        list.setOnItemClickListener((p, v, index, id) -> select.accept(index));
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private LinearLayout scrolled() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = Ui.column(this);
        scroll.addView(layout, new ViewGroup.LayoutParams(-1, -2));
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return layout;
    }

    private void paste() {
        EditText input = new EditText(this);
        input.setMinLines(4);
        input.setHint("Legado JSON");
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(LIMIT)});
        new AlertDialog.Builder(this).setTitle("粘贴 Legado 书源").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("检查", (d, w) -> importText(input.getText().toString())).show();
    }

    private void pick(boolean export) {
        if (export && sources.isEmpty()) { status.setText("没有可导出的书源。"); return; }
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(export ? "application/json" : "*/*");
        if (export) intent.putExtra(Intent.EXTRA_TITLE, "openleaf-legado.json");
        try { startActivityForResult(intent, export ? EXPORT : IMPORT); }
        catch (android.content.ActivityNotFoundException error) { status.setText("系统文件选择器不可用。"); }
    }

    @Override protected void onActivityResult(int requestCode, int result, Intent data) {
        super.onActivityResult(requestCode, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == IMPORT) {
            request("正在检查书源…", signal -> () -> parseImport(readImport(uri, signal), signal), this::confirmImport);
        } else if (requestCode == EXPORT) {
            ArrayList<LegadoClient.Source> snapshot = new ArrayList<>(sources.values());
            request("正在导出…", signal -> () -> {
                byte[] bytes = LegadoClient.exportSources(snapshot).getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("无法打开保存目标");
                    out.write(bytes);
                }
                return bytes.length;
            }, count -> status.setText("已导出 " + snapshot.size() + " 个书源。"));
        }
    }

    private String readImport(Uri uri, CancellationSignal signal) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("无法打开书源文件");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                signal.throwIfCanceled();
                if (count > LIMIT - bytes.size()) throw new IOException("书源文件超过 512 KiB");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void importText(String text) {
        request("正在检查书源…", signal -> () -> parseImport(text, signal), this::confirmImport);
    }

    private static final class Imported {
        final List<LegadoClient.Source> accepted = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();
    }

    private static Imported parseImport(String text, CancellationSignal signal) throws Exception {
        if (text.getBytes(StandardCharsets.UTF_8).length > LIMIT) throw new IOException("书源文件超过 512 KiB");
        JSONTokener tokener = new JSONTokener(text.replaceFirst("^\\uFEFF", ""));
        Object value = tokener.nextValue();
        if (tokener.nextClean() != 0) throw new IOException("JSON 末尾有多余内容");
        JSONArray items;
        if (value instanceof JSONArray) items = (JSONArray) value;
        else if (value instanceof JSONObject) items = new JSONArray().put(value);
        else throw new IOException("请选择 Legado 书源 JSON 对象或数组");
        if (items.length() > MAX_SOURCES) throw new IOException("每次最多导入 100 个书源");
        Imported parsed = new Imported();
        for (int i = 0; i < items.length(); i++) {
            signal.throwIfCanceled();
            JSONObject item = items.optJSONObject(i);
            try { parsed.accepted.add(new LegadoClient.Source(items.getJSONObject(i))); }
            catch (Exception error) {
                String name = item == null ? "第 " + (i + 1) + " 项" : item.optString("bookSourceName", "第 " + (i + 1) + " 项");
                parsed.rejected.add(shortText(name, 80) + "：" + shortText(errorMessage(error), 180));
            }
        }
        return parsed;
    }

    private void confirmImport(Imported parsed) {
        home();
        StringBuilder message = new StringBuilder("可导入 ").append(parsed.accepted.size()).append(" 个书源。");
        if (!parsed.rejected.isEmpty()) {
            message.append("\n跳过 ").append(parsed.rejected.size()).append(" 项\n");
            for (int i = 0; i < Math.min(8, parsed.rejected.size()); i++) message.append("\n").append(parsed.rejected.get(i));
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("书源检查结果").setMessage(message);
        if (parsed.accepted.isEmpty()) dialog.setPositiveButton("关闭", null);
        else dialog.setNegativeButton("取消", null).setPositiveButton("导入 / 更新", (d, w) -> {
            LinkedHashMap<String, LegadoClient.Source> updated = new LinkedHashMap<>(sources);
            int added = 0, replaced = 0;
            for (LegadoClient.Source item : parsed.accepted) {
                LegadoClient.Source old = updated.get(item.url);
                if (old != null && !SourceStore.ruleVersion(old).equals(SourceStore.ruleVersion(item))) replaced++;
                else if (old == null) added++;
                // 同 URL 覆盖：书源规则更新后，在线书"检查书源更新"才能检测到版本变化
                updated.put(item.url, item);
            }
            try {
                save(updated);
                home();
                status.setText("新增 " + added + " 个，更新 " + replaced + " 个书源规则。已有在线书可在书架长按图书「检查更新」。");
            } catch (Exception error) { status.setText(errorMessage(error)); }
        });
        dialog.show();
    }

    private void save(LinkedHashMap<String, LegadoClient.Source> updated) throws Exception {
        if (updated.size() > MAX_SOURCES) throw new IOException("最多保存 100 个书源");
        sourceStore.save(updated);
        sources.clear();
        sources.putAll(updated);
    }

    private interface Operation<T> { Callable<T> withSignal(CancellationSignal signal); }

    private <T> void request(String loading, Operation<T> operation, java.util.function.Consumer<T> done) {
        cancel();
        final int token = generation;
        CancellationSignal signal = new CancellationSignal();
        running = signal;
        status.setText(loading);
        android.widget.Button stop = Ui.button(this, "停止", () -> {
            cancel();
            body.removeViewAt(body.getChildCount() - 1);
            status.setText("已停止，可返回重试。");
        });
        body.addView(stop);
        Callable<T> task = operation.withSignal(signal);
        worker.execute(() -> {
            try {
                signal.throwIfCanceled();
                T value = task.call();
                runOnUiThread(() -> {
                    if (isDestroyed() || token != generation) return;
                    running = null;
                    body.removeView(stop);
                    done.accept(value);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || token != generation) return;
                    running = null;
                    body.removeView(stop);
                    status.setText("操作失败。" + errorMessage(error));
                });
            }
        });
    }

    private void reset(int screen, String title) {
        cancel();
        stage = screen;
        heading.setText(title);
        status.setText("");
        body.removeAllViews();
    }

    private void cancel() {
        generation++;
        if (running != null) running.cancel();
        running = null;
    }

    @Override public void onBackPressed() {
        switch (stage) {
            case 4: showChapters(); break;
            case 3: if (book != null) details(book); else showResults(); break;
            case 2: showResults(); break;
            case 1: home(); break;
            default: finish();
        }
    }

    @Override protected void onDestroy() {
        cancel();
        worker.shutdownNow();
        for (Bitmap bitmap : coverCache.values()) if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        coverCache.clear();
        super.onDestroy();
    }

    private void hideKeyboard(EditText field) {
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    private static String optionalLine(String value) { return value == null || value.isEmpty() ? "" : "\n" + value; }
    private static String errorMessage(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private static String shortText(String text, int limit) { return text.length() > limit ? text.substring(0, limit) + "…" : text; }
}
