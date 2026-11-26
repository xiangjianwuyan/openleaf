package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.ThemePalette;
import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline renderer for fragments rebuilt by the EPUB parser, not raw book HTML. */
public final class EpubActivity extends Activity {
    private static final String BASE = "https://epub.openleaf.invalid/";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object archiveLock = new Object();
    private BookParser.IllustratedBook archive;
    private BookAccess bookAccess;
    private boolean closed;
    private SharedPreferences prefs;
    private WebView web;
    private TextView label, cssStatus, themeStatus;
    private Button previous, next, toc;
    private String id;
    private int chapter, scrollY, generation, theme, font;
    // 0.30.0 #2：有效配色（自定义优先，0 = 未设置回退预设）。
    private int paperColor, inkColor;
    private boolean bookCss; // 是否套用原书 CSS（安全白名单内联）
    private String savedCfi; // CFI 主锚点；scrollY 作为回退
    private String[] titles;
    private String fragment;
    private volatile Page currentPage;
    private static final class Page {
        final String url;
        final byte[] html;
        Page(String url, String html) {
            this.url = url;
            this.html = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private boolean pageReady, previewLoading;
    private android.app.Dialog preview;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        id = getIntent().getStringExtra("bookId");
        if (id == null) { finish(); return; }
        prefs = getSharedPreferences("reader", 0);
        chapter = prefs.getInt("epub_chapter_" + id, 0);
        scrollY = prefs.getInt("epub_scroll_" + id, 0);
        // 深色跟随：默认关，未开启时与既有 clamp 语义逐字节一致（T02 只做判定接入）
        theme = ThemeResolver.resolve(prefs.getBoolean(ThemeResolver.KEY_FOLLOW, false),
                getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK,
                prefs.getInt("theme", 0));
        refreshPalette();
        font = Math.max(14, Math.min(32, prefs.getInt("fontSize", 20)));
        bookCss = prefs.getBoolean("epubBookCss_" + id, true); // 默认保留原书样式
        savedCfi = prefs.getString("epubCfi_" + id, "");
        if (state != null) {
            chapter = state.getInt("chapter", chapter); scrollY = state.getInt("scroll", scrollY);
            savedCfi = state.getString("cfi", savedCfi);
        } else {
            int requested = getIntent().getIntExtra("epubTargetSpine", -1);
            if (requested >= 0 && requested != chapter) { chapter = requested; scrollY = 0; savedCfi = ""; }
        }
        try { build(); } catch (RuntimeException unavailable) {
            new AlertDialog.Builder(this).setMessage("系统 WebView 不可用。")
                    .setPositiveButton("纯文字", (d, w) -> plain()).setNegativeButton("返回", (d, w) -> finish())
                    .setOnCancelListener(d -> finish()).show();
            return;
        }
        worker.execute(() -> {
            try {
                BookStore store = new BookStore(getApplicationContext());
                BookStore.Book book = store.find(id);
                if (!"epub".equals(book.format)) throw new IllegalArgumentException("不是 EPUB 书籍");
                BookAccess access = store.open(book);
                BookParser.IllustratedBook opened;
                try { opened = BookParser.openIllustrated(access.file); }
                catch (Exception error) { access.close(); throw error; }
                synchronized (archiveLock) {
                    if (closed) { opened.close(); access.close(); return; }
                    bookAccess = access;
                    archive = opened;
                }
                String[] names = new String[opened.size()];
                for (int i = 0; i < names.length; i++) names[i] = opened.title(i);
                if (names.length == 0) throw new IllegalArgumentException("没有可阅读的章节");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    titles = names;
                    load(Math.max(0, Math.min(chapter, names.length - 1)), scrollY);
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) label.setText("无法打开图文版，可切换纯文字。" + message(error)); });
            }
        });
    }

    private void build() {
        LinearLayout root = Ui.column(this);
        Ui.mount(this, root);
        LinearLayout top = Ui.row(this);
        Button back = Ui.button(this, "返回", this::finish); back.setContentDescription("返回书架"); top.addView(back);
        TextView title = Ui.title(this, getIntent().getStringExtra("title"), 16);
        title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, Ui.weight());
        Button find = Ui.button(this, "查找", this::searchText); find.setContentDescription("书内查找"); top.addView(find);
        Button settingsButton = Ui.button(this, "设置", this::settings); settingsButton.setContentDescription("阅读设置"); top.addView(settingsButton);
        root.addView(top);
        label = Ui.text(this, "正在打开 EPUB…", 13, Ui.GREEN);
        label.setMaxLines(2); Ui.pad(label, 8); root.addView(label);
        // 外部 CSS 三态常驻展示（不用 Toast 充数）；未导入过显示 §12 未导入态
        cssStatus = Ui.text(this, ExternalCssStore.lastStatus() == null
                ? "未导入外部样式（当前使用原书样式 / 应用统一样式）" : ExternalCssStore.lastStatus(), 12, Ui.MUTED);
        cssStatus.setMaxLines(2); Ui.pad(cssStatus, 8); root.addView(cssStatus);
        themeStatus = Ui.text(this, "", 12, Ui.MUTED);
        themeStatus.setMaxLines(2); Ui.pad(themeStatus, 8); root.addView(themeStatus);
        web = new WebView(this);
        web.setSaveEnabled(false);
        web.setBackgroundColor(paperColor);
        // 仅用于我们注入的 CFI 定位脚本；书内 script 已被 BookParser 剥离，
        // 且 CSP default-src 'none' 阻止任何外部/内联脚本，页面内容仍不可执行脚本。
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setAllowFileAccess(false);
        // The synthetic HTTPS image origin must reach shouldInterceptRequest.
        // Every resource outside its local image handler receives a blocked response.
        web.getSettings().setDomStorageEnabled(false);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String prefix = BASE + "preview/";
                if (request.isForMainFrame() && request.hasGesture() && url.startsWith(prefix)) {
                    String token = url.substring(prefix.length());
                    if (token.matches("[0-9]{1,8}")) openPreview(token);
                }
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Page page = currentPage;
                if (request.isForMainFrame() && page != null && page.url.equals(url))
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(page.html));
                if (!request.isForMainFrame() && url.startsWith(BASE + "image/")) {
                    String token = url.substring((BASE + "image/").length());
                    if (token.matches("[0-9]{1,8}")) {
                        try {
                            BookParser.EpubImage image;
                            synchronized (archiveLock) { image = closed || archive == null ? null : archive.image(token); }
                            if (image != null) {
                                BitmapFactory.Options bounds = new BitmapFactory.Options();
                                bounds.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.length, bounds);
                                if (bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth <= 8192
                                        && bounds.outHeight <= 8192 && (long) bounds.outWidth * bounds.outHeight <= 20_000_000)
                                    return new WebResourceResponse(image.mime, null, new ByteArrayInputStream(image.bytes));
                            }
                        } catch (Exception ignored) { /* Broken images do not prevent text reading. */ }
                    }
                }
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null, new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView view, String url) {
                Page page = currentPage;
                if (page == null || !url.equals(page.url)) return;
                final int target = scrollY;
                final long myGeneration = generation;
                view.postVisualStateCallback(generation, new WebView.VisualStateCallback() {
                    @Override public void onComplete(long token) {
                        if (web != view || token != myGeneration || currentPage != page) return;
                        // 双锚点：优先 CFI 结构定位，失败/无 CFI 时用 scrollY 回退
                        restoreByCfi(view, savedCfi, target, () -> {
                            if (web != view || myGeneration != generation || currentPage != page) return;
                            pageReady = true;
                            updateNavigation();
                        });
                    }
                });
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bar = Ui.row(this);
        previous = Ui.button(this, "上一章", () -> load(chapter - 1, 0));
        next = Ui.button(this, "下一章", () -> load(chapter + 1, 0));
        toc = Ui.button(this, "目录", this::contents);
        previous.setContentDescription("上一章"); next.setContentDescription("下一章"); toc.setContentDescription("章节目录");
        bar.addView(previous, Ui.weight()); bar.addView(toc, Ui.weight()); bar.addView(next, Ui.weight());
        Button plain = Ui.button(this, "纯文字", this::plain); plain.setContentDescription("切换纯文字模式");
        bar.addView(plain, Ui.weight());
        root.addView(bar);
        updateNavigation();
    }

    private void openPreview(String token) {
        if (previewLoading || preview != null || isFinishing() || isDestroyed()) return;
        previewLoading = true;
        Page page = currentPage;
        worker.execute(() -> {
            android.graphics.Bitmap bitmap = null;
            try {
                BookParser.EpubImage image;
                synchronized (archiveLock) { image = closed || archive == null ? null : archive.image(token); }
                if (image != null) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.length, options);
                    if (options.outWidth > 0 && options.outHeight > 0 && options.outWidth <= 8192
                            && options.outHeight <= 8192 && (long) options.outWidth * options.outHeight <= 20_000_000) {
                        options.inJustDecodeBounds = false;
                        options.inSampleSize = 1;
                        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 3072)
                            options.inSampleSize *= 2;
                        bitmap = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.length, options);
                    }
                }
            } catch (Exception ignored) { /* Report unavailable images in the reader. */ }
            android.graphics.Bitmap result = bitmap;
            runOnUiThread(() -> {
                previewLoading = false;
                if (isFinishing() || isDestroyed() || currentPage != page) {
                    if (result != null) result.recycle();
                    return;
                }
                if (result == null) {
                    android.widget.Toast.makeText(this, "此插图无法预览", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                preview = EpubImagePreview.show(this, result);
                preview.setOnDismissListener(dialog -> preview = null);
            });
        });
    }

    private void updateNavigation() {
        boolean available = titles != null;
        previous.setEnabled(available && chapter > 0);
        next.setEnabled(available && chapter + 1 < titles.length);
        toc.setEnabled(available);
    }

    /** 用当前样式设置重新渲染本章（保留位置锚点）。 */
    private void reloadCurrent() {
        captureCfi(view -> { savedCfi = view == null ? "" : view; load(chapter, scrollY); });
    }

    private void load(int target, int offset) {
        if (titles == null || target < 0 || target >= titles.length) return;
        save();
        chapter = target; scrollY = offset; pageReady = false; currentPage = null;
        fragment = null;
        int request = ++generation;
        label.setText((chapter + 1) + " / " + titles.length + " · " + titles[chapter]);
        updateNavigation();
        worker.execute(() -> {
            try {
                String html;
                synchronized (archiveLock) {
                    if (closed || archive == null) return;
                    // 外部 CSS 走同一白名单重载；未导入或未启用时传空 map，行为与既有实现一致
                    html = archive.html(target, bookCss, ExternalCssStore.enabled(this)
                            ? ExternalCssStore.rulesFor(getApplicationContext(), id)
                            : java.util.Collections.<String, String>emptyMap());
                }
                runOnUiThread(() -> {
                    if (web == null || isDestroyed() || request != generation) return;
                    fragment = html; render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (web == null || isDestroyed() || request != generation) return;
                    label.setText("此章无法显示，可切换纯文字。" + message(error));
                    fragment = "<p>此章无法显示。</p>";
                    render();
                });
            }
        });
    }

    private void render() {
        if (fragment == null) return;
        pageReady = false;
        String url = BASE + "chapter-" + (++generation);
        // C1：边框色取主题字色，不再硬编码灰色；cssColor 用 #%06x 保证 6 位合法 hex。
        String border = cssColor(ThemePalette.presetInk(theme));
        String css = "body{margin:1.2em;line-height:1.65;overflow-wrap:anywhere;font-family:serif;color:"
                + cssColor(inkColor) + ";background:" + cssColor(paperColor) + ";font-size:" + font + "px}"
                + "body{max-width:42em;margin:1.2em auto;padding:0 1.2em;box-sizing:border-box}"
                + "img{max-width:100%;height:auto;vertical-align:middle}figure{margin:1em 0;text-align:center}"
                + "a.epub-illustration{display:inline-block;max-width:100%;cursor:zoom-in}"
                + "a.epub-illustration:focus-visible{outline:2px solid currentColor}"
                + "figcaption{font-size:.85em;text-align:center}blockquote{margin-inline:1em;padding-inline-start:.7em;border-inline-start:2px solid " + border + "}"
                + "pre{white-space:pre-wrap}table{border-collapse:collapse;display:block;overflow:auto}td,th{padding:.35em;border:1px solid " + border + "}";
        web.setBackgroundColor(paperColor);
        currentPage = new Page(url, "<!doctype html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<meta http-equiv=Content-Security-Policy content=\"default-src 'none'; img-src https://epub.openleaf.invalid; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">"
                + "<style>" + css + "</style></head><body>" + fragment + "</body></html>");
        web.loadUrl(url);
    }

    /** 从 reader prefs 重算有效配色；0.29.0 默认路径逐字不变。 */
    private void refreshPalette() {
        int[] palette = ThemePalette.resolve(theme,
                prefs.getInt("custom_bg_color", 0), prefs.getInt("custom_ink_color", 0));
        paperColor = palette[0];
        inkColor = palette[1];
    }

    private static String cssColor(int color) { return String.format(Locale.ROOT, "#%06x", color & 0xffffff); }
    private static String message(Exception e) { return e.getMessage() == null ? "" : " " + e.getMessage(); }
    private void contents() {
        if (titles != null) new AlertDialog.Builder(this).setTitle("目录")
                .setSingleChoiceItems(titles, chapter, (d, which) -> { d.dismiss(); load(which, 0); })
                .setNegativeButton("关闭", null).show();
    }
    private void settings() {
        boolean follow = prefs.getBoolean(ThemeResolver.KEY_FOLLOW, false);
        String followLabel = follow
                ? "跟随系统：开（夜间系统主题 = 夜间；手动主题选择将关闭跟随）"
                : "跟随系统：关（当前：" + themeName() + "）";
        new AlertDialog.Builder(this).setTitle("阅读设置").setItems(new String[]{
                "减小字号", "增大字号", "切换主题", "原书样式：" + (bookCss ? "开" : "关") + "（点击切换）",
                "纯文字与书内查找", "样式与位置说明", "外部样式…", followLabel}, (d, which) -> {
            if (which == 4) { plain(); return; }
            if (which == 5) {
                new AlertDialog.Builder(this).setTitle("样式与位置说明")
                        .setMessage("原书样式：开=套用原书 CSS 中的安全样式（仅文字对齐、间距、行高等白名单声明，忽略颜色与脚本）；关=由应用统一排版。字号、主题始终由应用控制。\n\n阅读位置以 CFI 结构锚点为主、滚动偏移为回退，切换纯文字/图文或原书样式后可回到相近位置。")
                        .setPositiveButton("知道了", null).show();
                return;
            }
            if (which == 6) { externalCssMenu(); return; }
            if (which == 7) {
                prefs.edit().putBoolean(ThemeResolver.KEY_FOLLOW, !follow).apply();
                recomputeTheme();
                themeStatus.setText(follow ? "跟随系统：关（当前：" + themeName() + "）"
                        : "跟随系统：开（夜间系统主题 = 夜间；手动主题选择将关闭跟随）");
                return;
            }
            save();
            if (which == 0) font = Math.max(14, font - 2);
            if (which == 1) font = Math.min(32, font + 2);
            boolean manualTheme = which == 2;
            if (manualTheme) theme = (theme + 1) % ThemePalette.PRESET_COUNT;
            if (manualTheme) refreshPalette();
            if (which == 3) {
                bookCss = !bookCss;
                prefs.edit().putBoolean("epubBookCss_" + id, bookCss).apply();
                reloadCurrent(); // 用新样式重渲染当前章
                return;
            }
            SharedPreferences.Editor editor = prefs.edit().putInt("theme", theme).putInt("fontSize", font);
            if (manualTheme) editor.putBoolean(ThemeResolver.KEY_FOLLOW, false); // 手动切换自动关跟随
            editor.apply();
            render();
        }).show();
    }

    private String themeName() { return ThemePalette.presetName(theme); }

    /** 主题跟随重算：仅档位变化才重渲染，避免 onResume 无谓闪屏。 */
    private void recomputeTheme() {
        int next = ThemeResolver.resolve(prefs.getBoolean(ThemeResolver.KEY_FOLLOW, false),
                getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK,
                prefs.getInt("theme", 0));
        int[] palette = ThemePalette.resolve(next,
                prefs.getInt("custom_bg_color", 0), prefs.getInt("custom_ink_color", 0));
        if (ThemePalette.samePalette(next, palette[0], palette[1], theme, paperColor, inkColor)) return;
        theme = next;
        refreshPalette();
        if (web != null) web.setBackgroundColor(paperColor);
        if (fragment != null) render();
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
                cssStatus.setText(enabled
                        ? (ExternalCssStore.rulesFor(this, id).isEmpty()
                                ? "未导入外部样式（当前使用原书样式 / 应用统一样式）" : cssImportedStatus())
                        : "外部样式已关闭（规则仍在，可随时开启）");
                reloadCurrent();
                return;
            }
            if (which == 2) {
                String next = ExternalCssStore.SCOPE_BOOK.equals(scope)
                        ? ExternalCssStore.SCOPE_GLOBAL : ExternalCssStore.SCOPE_BOOK;
                ExternalCssStore.setScope(this, next);
                cssStatus.setText(cssImportedStatus());
                reloadCurrent();
                return;
            }
            ExternalCssStore.clear(this);
            cssStatus.setText(ExternalCssStore.lastStatus());
            reloadCurrent();
        }).setNegativeButton("关闭", null).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (ExternalCssStore.handleResult(this, request, result, data)) {
            if (cssStatus != null) cssStatus.setText(ExternalCssStore.lastStatus());
            reloadCurrent(); // 导入后立即重渲染（§12 后续动作）
        }
    }
    private void searchText() {
        save();
        Intent intent = new Intent(this, ReaderActivity.class).putExtras(getIntent())
                .putExtra("epubTargetSpine", chapter).putExtra("startBookSearch", true);
        startActivity(intent);
    }

    private void plain() {
        save();
        startActivity(new Intent(this, ReaderActivity.class).putExtras(getIntent()).putExtra("epubTargetSpine", chapter));
        finish();
    }
    private void save() {
        if (!pageReady || web == null || prefs == null) return;
        scrollY = web.getScrollY();
        prefs.edit().putInt("epub_chapter_" + id, chapter).putInt("epub_scroll_" + id, scrollY).apply();
        // CFI 主锚点：异步从当前可见元素采集（不阻塞翻页/暂停）
        captureCfi(cfi -> {
            savedCfi = cfi == null ? "" : cfi;
            prefs.edit().putString("epubCfi_" + id, savedCfi).apply();
        });
    }

    /** 注入脚本：取屏幕顶部最近的 data-cfi 元素，拼成 CFI（spine + 结构路径 + 0 偏移）。 */
    private void captureCfi(java.util.function.Consumer<String> callback) {
        if (web == null || !pageReady) { callback.accept(""); return; }
        String js = "(function(){"
                + "var y=window.scrollY||document.documentElement.scrollTop||0;"
                + "var els=document.querySelectorAll('[data-cfi]');var best='',bestTop=-1e9;"
                + "for(var i=0;i<els.length;i++){var r=els[i].getBoundingClientRect();var t=r.top+y;"
                + "if(t<=y+8&&t>bestTop){bestTop=t;best=els[i].getAttribute('data-cfi');}}"
                + "return best||'';})()";
        try {
            web.evaluateJavascript(js, value -> {
                String path = value == null ? "" : value.replace("\"", "");
                if (path.isEmpty() || "null".equals(path)) { callback.accept(""); return; }
                callback.accept(org.openleaf.reader.core.EpubCfi.create(chapter, parsePath(path), 0));
            });
        } catch (Exception ignored) { callback.accept(""); }
    }

    /** 解析 "1/2/3" 路径为 int[]，非法段返回空数组。 */
    private static int[] parsePath(String path) {
        if (path == null || path.isEmpty()) return new int[0];
        String[] parts = path.split("/");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Math.max(0, Integer.parseInt(parts[i])); }
            catch (NumberFormatException e) { return new int[0]; }
        }
        return result;
    }

    /** 双锚点恢复：按 CFI 找到元素滚动到顶部；失败/无 CFI 时回退到 scrollY。 */
    private void restoreByCfi(WebView view, String cfi, int fallbackScroll, Runnable onDone) {
        org.openleaf.reader.core.EpubCfi.Location loc = org.openleaf.reader.core.EpubCfi.parse(cfi);
        if (loc == null || loc.spineIndex != chapter || loc.path.length == 0) {
            view.scrollTo(0, fallbackScroll);
            onDone.run();
            return;
        }
        final String path = loc.pathString();
        // 用 CSS.escape 不存在时按构造来源（纯数字与 /）安全拼接
        String js = "(function(){var e=document.querySelector('[data-cfi=\"" + path + "\"]');"
                + "if(!e)return '0';e.scrollIntoView({block:'start'});return '1';})()";
        try {
            web.evaluateJavascript(js, value -> {
                if (value == null || !value.contains("1")) view.scrollTo(0, fallbackScroll);
                onDone.run();
            });
        } catch (Exception ignored) {
            view.scrollTo(0, fallbackScroll);
            onDone.run();
        }
    }
    @Override protected void onResume() {
        super.onResume();
        // 系统主题切换后回到阅读器即时生效（跟随开时重算 + 重渲染）
        recomputeTheme();
    }
    @Override protected void onPause() { save(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        save(); state.putInt("chapter", chapter); state.putInt("scroll", scrollY); state.putString("cfi", savedCfi);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        if (preview != null) preview.dismiss();
        currentPage = null;
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        worker.execute(() -> {
            synchronized (archiveLock) {
                closed = true;
                if (archive != null) { try { archive.close(); } catch (Exception ignored) {} archive = null; }
                if (bookAccess != null) { try { bookAccess.close(); } catch (Exception ignored) {} bookAccess = null; }
            }
        });
        worker.shutdown();
        super.onDestroy();
    }
}

