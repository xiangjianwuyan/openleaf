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
    private int pageIndex, pageCount = 1;
    private boolean paged, turning, restoreAtEnd;
    private float touchX, touchY;
    private long touchTime;
    private boolean multiTouch;
    private LinearLayout footer;
    // 0.30.0 #2：有效配色（自定义优先，0 = 未设置回退预设）。
    private int paperColor, inkColor;
    private boolean bookCss; // 是否套用原书 CSS（安全白名单内联）
    private String savedCfi; // CFI 主锚点；scrollY 作为回退
    private String[] titles;
    private String fragment, pageError;
    private int cfiPage = -1;
    private int lastIntent = -1;
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
        paged = prefs.getBoolean("epub_paged", true);
        pageIndex = Math.max(0, prefs.getInt("epub_page_" + id, 0));
        if (state != null) {
            chapter = state.getInt("chapter", chapter); scrollY = state.getInt("scroll", scrollY);
            savedCfi = state.getString("cfi", savedCfi);
            pageIndex = state.getInt("page", pageIndex);
        } else {
            int requested = getIntent().getIntExtra("epubTargetSpine", -1);
            if (requested >= 0 && requested != chapter) { chapter = requested; scrollY = 0; pageIndex = 0; savedCfi = ""; }
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
        // One inset owner: Android 15 edge-to-edge and older navigation modes both reserve real space.
        root.setFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return android.view.WindowInsets.CONSUMED;
            }
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
        LinearLayout top = Ui.row(this);
        Button back = Ui.button(this, "返回", this::finish); back.setContentDescription("返回书架"); top.addView(back);
        TextView title = Ui.title(this, getIntent().getStringExtra("title"), 16);
        title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, Ui.weight());
        Button find = Ui.button(this, "查找", this::searchText); find.setContentDescription("书内查找"); top.addView(find);
        Button settingsButton = Ui.button(this, "设置", this::settings); settingsButton.setContentDescription("阅读设置"); top.addView(settingsButton);
        root.addView(top);
        label = Ui.text(this, "正在打开 EPUB…", 13, Ui.GREEN);
        label.setSingleLine(); label.setEllipsize(TextUtils.TruncateAt.END); Ui.pad(label, 8); root.addView(label);
        cssStatus = Ui.text(this, ExternalCssStore.lastStatus() == null
                ? "未导入外部样式（当前使用原书样式 / 应用统一样式）" : ExternalCssStore.lastStatus(), 12, Ui.MUTED);
        cssStatus.setMaxLines(2); Ui.pad(cssStatus, 8); cssStatus.setVisibility(android.view.View.GONE); root.addView(cssStatus);
        themeStatus = Ui.text(this, "", 12, Ui.MUTED);
        themeStatus.setMaxLines(2); Ui.pad(themeStatus, 8); themeStatus.setVisibility(android.view.View.GONE); root.addView(themeStatus);
        web = new WebView(this);
        web.setSaveEnabled(false);
        web.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        web.setHorizontalScrollBarEnabled(false);
        web.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                touchX = event.getX(); touchY = event.getY(); touchTime = event.getEventTime(); multiTouch = false;
            } else if (action == android.view.MotionEvent.ACTION_POINTER_DOWN) multiTouch = true;
            else if (action == android.view.MotionEvent.ACTION_UP && paged && !multiTouch) {
                float dx = event.getX() - touchX, dy = event.getY() - touchY;
                if (event.getEventTime() - touchTime < 700 && Math.abs(dx) > Ui.dp(this, 48)
                        && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    android.view.MotionEvent cancel = android.view.MotionEvent.obtain(event);
                    cancel.setAction(android.view.MotionEvent.ACTION_CANCEL); web.onTouchEvent(cancel); cancel.recycle();
                    turnPage(dx < 0 ? 1 : -1); return true;
                }
            }
            return false;
        });
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
        // Without a wide viewport the layout viewport (CSS px) disagrees with window.innerHeight,
        // so paged content overflows by one line and the footer clips the last line.
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);
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
                        view.evaluateJavascript(EpubPageLayout.bootstrap(paged), ignored -> {
                            if (web != view || myGeneration != generation || currentPage != page) return;
                            restoreByCfi(view, savedCfi, target, () -> {
                                if (web != view || myGeneration != generation || currentPage != page) return;
                                pageReady = true; turning = false;
                                updateNavigation();
                                // Only persist once the intended page was reached, otherwise a failed
                                // first paint would overwrite the good anchor with page one.
                                if (!paged || lastIntent <= 0 || pageIndex == lastIntent) save();
                                repaintRestoredPage(view, page);
                                dumpQaMetrics(view);
                            });
                        });
                    }
                });
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = Ui.row(this);
        previous = Ui.button(this, "上一页", () -> turnPage(-1));
        next = Ui.button(this, "下一页", () -> turnPage(1));
        toc = Ui.button(this, "目录", this::contents);
        toc.setContentDescription("章节目录");
        footer.addView(previous, Ui.weight()); footer.addView(toc, Ui.weight()); footer.addView(next, Ui.weight());
        Button plain = Ui.button(this, "纯文字", this::plain); plain.setContentDescription("切换纯文字模式");
        footer.addView(plain, Ui.weight());
        root.addView(footer);
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
        boolean available = titles != null && pageReady && !turning;
        previous.setText(paged ? "上一页" : "上一章"); next.setText(paged ? "下一页" : "下一章");
        previous.setContentDescription(paged ? "上一页" : "上一章");
        next.setContentDescription(paged ? "下一页" : "下一章");
        previous.setEnabled(available && (chapter > 0 || (paged && pageIndex > 0)));
        next.setEnabled(available && (chapter + 1 < titles.length || (paged && pageIndex + 1 < pageCount)));
        toc.setEnabled(titles != null);
        if (pageError != null) label.setText(pageError);
        else if (titles != null) label.setText((chapter + 1) + " / " + titles.length
                + (paged && pageReady ? " · " + (pageIndex + 1) + "/" + pageCount + " 页" : "") + " · " + titles[chapter]);
    }

    private void turnPage(int direction) {
        if (!pageReady || turning || titles == null || web == null) return;
        if (!paged) { load(chapter + direction, 0); return; }
        int target = pageIndex + direction;
        if (target < 0 || target >= pageCount) {
            load(chapter + direction, direction < 0 ? -1 : 0); return;
        }
        turning = true; updateNavigation();
        Page expected = currentPage;
        web.evaluateJavascript("window.__openleafReader.go(" + target + ")", value -> {
            if (expected != currentPage || web == null) return;
            readPageMetrics(value); turning = false; updateNavigation(); save();
        });
    }

    /** Debug-only layout probe used by device QA; never runs unless the caller opts in. */
    private void dumpQaMetrics(WebView view) {
        if (!BuildConfig.DEBUG || !getIntent().getBooleanExtra("qaMetrics", false)) return;
        String js = "({innerH:innerHeight,innerW:innerWidth,"
                + "contentH:document.getElementById('epub-content').clientHeight,"
                + "contentScrollW:document.getElementById('epub-content').scrollWidth,"
                + "bodyH:document.body.clientHeight,docH:document.documentElement.clientHeight,"
                + "lh:getComputedStyle(document.getElementById('epub-content')).lineHeight,"
                + "scrollX:window.scrollX,scrollY:window.scrollY,page:%d,"
                + "cap:(window.__openleafReader?window.__openleafReader.capture():null),"
                + "probeScroll:(function(){try{window.scrollTo(4000,0);var a=window.scrollX;"
                + "var b=document.scrollingElement?document.scrollingElement.scrollLeft:-1;"
                + "window.scrollTo(0,0);return [a,b];}catch(e){return 'ERR';}})()})";
        view.evaluateJavascript(String.format(Locale.ROOT, js, pageIndex), value -> {
            try {
                android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                String javaSide = String.format(Locale.ROOT,
                        "\"webW\":%d,\"webH\":%d,\"density\":%s,\"scaledDensity\":%s,\"screenH\":%d,\"screenW\":%d",
                        web.getWidth(), web.getHeight(), metrics.density, metrics.scaledDensity,
                        metrics.heightPixels, metrics.widthPixels);
                String merged = value == null ? "{}" : value.replaceFirst("\\}$", "," + javaSide + "}");
                java.nio.file.Files.write(new java.io.File(getFilesDir(), "qa-metrics.json").toPath(),
                        merged.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        });
    }

    /** Debug-only trace of what each save captured; used by device QA. */
    private void dumpQaSave(String cfi, int spine) {
        if (!BuildConfig.DEBUG || !getIntent().getBooleanExtra("qaMetrics", false)) return;
        String line = "page=" + pageIndex + " spine=" + spine + " cfi=" + cfi + "\n";
        try {
            java.nio.file.Files.write(new java.io.File(getFilesDir(), "qa-save.json").toPath(), line.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    private void dumpQaRestore(String line) {
        if (!BuildConfig.DEBUG || !getIntent().getBooleanExtra("qaMetrics", false)) return;
        try {
            java.nio.file.Files.write(new java.io.File(getFilesDir(), "qa-restore.txt").toPath(),
                    (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    private void readPageMetrics(String value) {        try {
            org.json.JSONArray metrics = new org.json.JSONArray(value);
            pageCount = Math.max(1, metrics.getInt(1));
            pageIndex = Math.max(0, Math.min(pageCount - 1, metrics.getInt(0)));
        } catch (Exception ignored) { pageIndex = 0; pageCount = 1; }
    }

    /** Reflow only after the visible text anchor has been captured. */
    private void reloadCurrent() {
        turning = true; updateNavigation();
        Page expected = currentPage;
        captureCfi(cfi -> {
            if (expected != currentPage || web == null) return;
            if (cfi != null && !cfi.isEmpty()) savedCfi = cfi;
            scrollY = web.getScrollY();
            load(chapter, scrollY);
        });
    }

    private void load(int target, int offset) {
        if (titles == null || target < 0 || target >= titles.length) return;
        save();
        if (target != chapter) { savedCfi = ""; pageIndex = 0; }
        restoreAtEnd = offset < 0;
        chapter = target; scrollY = Math.max(0, offset); pageReady = false; turning = false; currentPage = null;
        prefs.edit().putInt("epub_chapter_" + id, chapter).putInt("epub_scroll_" + id, scrollY)
                .putInt("epub_page_" + id, pageIndex).putString("epubCfi_" + id, savedCfi).apply();
        fragment = null; pageError = null;
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
                    pageError = "此章无法显示，可切换纯文字。" + message(error);
                    label.setText(pageError);
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
        css += EpubPageLayout.css(paged);
        web.getSettings().setBuiltInZoomControls(!paged);
        web.setVerticalScrollBarEnabled(!paged);
        web.setBackgroundColor(paperColor);
        String viewport = "width=device-width,initial-scale=1,maximum-scale=1"
                + (paged ? ",user-scalable=no" : "");
        currentPage = new Page(url, "<!doctype html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content='" + viewport + "'>"
                + "<meta http-equiv=Content-Security-Policy content=\"default-src 'none'; img-src https://epub.openleaf.invalid; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">"
                + "<style>" + css + "</style></head><body><main id=epub-content>" + fragment + "</main></body></html>");
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
                .setSingleChoiceItems(titles, chapter, (d, which) -> {
                    d.dismiss(); savedCfi = ""; pageIndex = 0; load(which, 0);
                })
                .setNegativeButton("关闭", null).show();
    }
    private void settings() {
        boolean follow = prefs.getBoolean(ThemeResolver.KEY_FOLLOW, false);
        String followLabel = follow
                ? "跟随系统：开（夜间系统主题 = 夜间；手动主题选择将关闭跟随）"
                : "跟随系统：关（当前：" + themeName() + "）";
        new AlertDialog.Builder(this).setTitle("阅读设置").setItems(new String[]{
                "减小字号", "增大字号", "切换主题", "原书样式：" + (bookCss ? "开" : "关") + "（点击切换）",
                "纯文字与书内查找", "样式、位置与翻页方式", "外部样式…", followLabel}, (d, which) -> {
            if (which == 4) { plain(); return; }
            if (which == 5) {
                new AlertDialog.Builder(this).setTitle("样式、位置与翻页方式")
                        .setMessage("左右分页：左右划动或使用底部按钮逐页阅读，章节结尾会自动换章。上下滚动：自由滚动正文，底栏用于换章。\n\n原书样式：保留原书的对齐、间距与行高；关闭后使用统一排版。字号和主题始终由应用控制。\n\n调整排版后会尽量保持当前文字位置。\n\n" + cssStatus.getText())
                        .setPositiveButton("知道了", null)
                        .setNeutralButton(paged ? "改用上下滚动" : "改用左右分页", (dialog, button) -> {
                            paged = !paged;
                            prefs.edit().putBoolean("epub_paged", paged).apply();
                            reloadCurrent();
                        }).show();
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
            reloadCurrent();
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
        if (fragment != null) reloadCurrent();
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
        final Page expected = currentPage;
        final int spine = chapter;
        prefs.edit().putInt("epub_chapter_" + id, spine).putInt("epub_scroll_" + id, scrollY)
                .putInt("epub_page_" + id, pageIndex).apply();
        captureCfi(cfi -> {
            if (expected != currentPage || spine != chapter) return;
            if (cfi == null || cfi.isEmpty()) {
                // Never keep an anchor that belongs to another page: it would outrank the saved page.
                if (cfiPage == pageIndex) { savedCfi = ""; cfiPage = -1; }
                dumpQaSave("empty", spine);
                return;
            }
            savedCfi = cfi;
            prefs.edit().putString("epubCfi_" + id, cfi).apply();
            dumpQaSave(cfi, spine);
        });
    }

    /** Capture visible text, including offsets inside paragraphs split across pages. */
    private void captureCfi(java.util.function.Consumer<String> callback) {
        if (web == null || !pageReady) { callback.accept(""); return; }
        final Page expected = currentPage;
        final int spine = chapter;
        try {
            web.evaluateJavascript("window.__openleafReader ? window.__openleafReader.capture() : null", value -> {
                if (expected != currentPage || spine != chapter) { callback.accept(""); return; }
                try {
                    org.json.JSONObject anchor = new org.json.JSONObject(value);
                    callback.accept(org.openleaf.reader.core.EpubCfi.create(spine,
                            parsePath(anchor.getString("path")), anchor.optInt("offset", 0)));
                    if (spine == chapter) cfiPage = pageIndex;
                } catch (Exception invalid) { callback.accept(""); }
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

    private void restoreByCfi(WebView view, String cfi, int fallbackScroll, Runnable onDone) {
        org.openleaf.reader.core.EpubCfi.Location loc = org.openleaf.reader.core.EpubCfi.parse(cfi);
        boolean valid = loc != null && loc.spineIndex == chapter && loc.path.length > 0;
        String path = valid ? loc.pathString() : "";
        int offset = valid ? loc.offset : 0;
        final Page expected = currentPage;
        final int intended = paged ? pageIndex : 0;
        lastIntent = intended;
        dumpQaRestore("restore path=" + path + " offset=" + offset + " intended=" + intended
                + " fallback=" + fallbackScroll + " end=" + restoreAtEnd + " cfi=" + cfi);
        String js = "window.__openleafReader.restore(\"" + path + "\"," + offset + ","
                + (paged ? pageIndex : Math.round(fallbackScroll / getResources().getDisplayMetrics().density))
                + "," + restoreAtEnd + ")";
        try {
            view.evaluateJavascript(js, value -> {
                if (expected != currentPage || web != view) return;
                readPageMetrics(value); restoreAtEnd = false;
                retryRestore(view, expected, intended, 1, onDone);
            });
        } catch (Exception ignored) {
            if (!paged) view.scrollTo(0, fallbackScroll);
            restoreAtEnd = false; onDone.run();
        }
    }

    /**
     * The first paint can clamp scrollTo before the horizontal range exists, so verify and retry
     * a few times with a growing delay instead of silently reopening at page one.
     */
    /**
     * The load-time scroll updates scrollX but the first composited frame can still show page one.
     * Re-applying the same offset after a frame (the same path the pager buttons use) repaints it.
     */
    private void repaintRestoredPage(WebView view, Page expected) {
        if (!paged || pageIndex <= 0) return;
        final int page = pageIndex;
        view.postDelayed(() -> {
            if (web != view || expected != currentPage) return;
            view.evaluateJavascript("window.__openleafReader.go(" + page + ")", value -> {
                if (web != view || expected != currentPage) return;
                readPageMetrics(value);
            });
        }, 240L);
    }

    private void retryRestore(WebView view, Page expected, int intended, int attempt, Runnable onDone) {
        dumpQaRestore("verify attempt=" + attempt + " intended=" + intended + " actual=" + pageIndex
                + " scrollX=" + (paged ? pageIndex : -1));
        if (!paged || intended <= 0 || pageIndex == intended) {
            // A programmatic scroll during the load can update scrollX without repainting the
            // column; one 1px round trip forces the compositor to redraw at the restored offset.
            if (paged && intended > 0 && pageIndex == intended) {
                view.evaluateJavascript("window.scrollBy(1,0);window.scrollBy(-1,0);null", null);
                view.invalidate();
            }
            onDone.run();
            return;
        }
        if (attempt > 3) { onDone.run(); return; }
        view.postDelayed(() -> {
            if (expected != currentPage || web != view) return;
            view.evaluateJavascript("window.__openleafReader.go(" + intended + ")", value -> {
                if (expected != currentPage || web != view) return;
                readPageMetrics(value);
                retryRestore(view, expected, intended, attempt + 1, onDone);
            });
        }, 180L * attempt);
    }
    @Override protected void onResume() {
        super.onResume();
        // 系统主题切换后回到阅读器即时生效（跟随开时重算 + 重渲染）
        recomputeTheme();
    }
    @Override protected void onPause() { save(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        save(); state.putInt("chapter", chapter); state.putInt("scroll", scrollY);
        // A CFI captured on an older page must not override the newer page number after rotation.
        state.putString("cfi", cfiPage == pageIndex ? savedCfi : "");
        state.putInt("page", pageIndex);
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

