package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.openleaf.reader.core.PageCrop;
import org.openleaf.reader.core.PdfViewOptions;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native, offline PDF reader; requires API 26 and no external libraries.
 *
 * Intent extras: bookId (String or Number),
 * title (optional String). Register this Activity with android:exported="false".
 * SharedPreferences("reader"): pdf_page_<bookId> is a zero-based int;
 * pdf_count_<bookId> is the total page count. Use the default application process
 * when the parent reads these preferences: SharedPreferences is not cross-process IPC.
 *
 * All PdfRenderer/Page/file-descriptor operations run on one executor. Only one
 * rendering task/result can be outstanding; repeated navigation replaces the target.
 */
public final class PdfActivity extends Activity {
    private static final int CREAM = 0xFFF7F3E8;
    private static final int GREEN = 0xFF234F3D;
    private static final int INK = 0xFF283B31;
    private static final int MUTED = 0xFF6B746B;
    private static final int MAX_WIDTH = 2048;
    private static final int MAX_HEIGHT = 4096;
    private static final long MAX_PIXELS = 4_000_000L;
    /** 白边至少省下这个比例才裁，避免把本来满页的排版误裁。 */
    private static final float MIN_CROP_GAIN = 0.08f;
    private static final String STATE_BOOK = "pdf.book";
    private static final String STATE_PAGE = "pdf.page";
    private static final String STATE_SCROLL = "pdf.scroll";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicInteger generation = new AtomicInteger(0);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "openleaf-pdf");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    // Worker-thread ownership only. Never close this from a lifecycle/UI callback.
    private PdfRenderer renderer;

    // Everything below is owned by the main thread unless copied into a render task.
    private SharedPreferences preferences;
    private String bookId;
    private String pageKey;
    private String countKey;
    private int pageCount;
    private int requestedPage;
    private int displayedPage = -1;
    private int requestedWidth;
    private boolean renderInFlight;
    private boolean ready;
    private long pixelLimit;
    private float restoredScrollFraction;
    private int restoredScrollPage = -1;
    private Bitmap displayedBitmap;
    private ScrollView scrollView;
    private HorizontalScrollView horizontalScroll;
    private ImageView imageView;
    /** 渲染选项：缩放档位、页面反色、裁白边。 */
    private int zoomIndex;
    private boolean night;
    private boolean cropMargins = true;
    private ScaleGestureDetector scaleDetector;
    private ProgressBar progress;
    private TextView status;
    private TextView errorView;
    private Button previous;
    private Button next;
    private Button pageButton;
    private AlertDialog jumpDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        pixelLimit = Math.min(MAX_PIXELS,
                Math.max(262_144L, Runtime.getRuntime().maxMemory() / 32L));
        preferences = getSharedPreferences("reader", MODE_PRIVATE);
        zoomIndex = PdfViewOptions.clampIndex(preferences.getInt("pdf_zoom", 0));
        night = preferences.getBoolean("pdf_night", false);
        cropMargins = preferences.getBoolean("pdf_crop", true);
        String title = "PDF 阅读";
        String inputError = null;
        try {
            Intent intent = getIntent();
            Bundle extras = intent == null ? null : intent.getExtras();
            Object rawId = extras == null ? null : extras.get("bookId");
            if (rawId instanceof String || rawId instanceof Number) {
                bookId = String.valueOf(rawId);
            }
            String suppliedTitle = intent == null ? null : intent.getStringExtra("title");
            if (!TextUtils.isEmpty(suppliedTitle)) title = suppliedTitle;
            if (TextUtils.isEmpty(bookId)) inputError = "缺少图书标识，无法打开 PDF。";
        } catch (RuntimeException malformedIntent) {
            inputError = "打开参数无效，请返回书架后重试。";
        }
        setTitle(title);
        buildUi(title);
        if (inputError != null) {
            showError(inputError);
            return;
        }
        pageKey = "pdf_page_" + bookId;
        countKey = "pdf_count_" + bookId;
        try {
            requestedPage = Math.max(0, preferences.getInt(pageKey, 0));
        } catch (ClassCastException incompatiblePreference) {
            requestedPage = 0;
        }
        if (savedInstanceState != null
                && bookId.equals(savedInstanceState.getString(STATE_BOOK))) {
            requestedPage = Math.max(0, savedInstanceState.getInt(STATE_PAGE, requestedPage));
            restoredScrollFraction = savedInstanceState.getFloat(STATE_SCROLL, 0f);
            if (Float.isNaN(restoredScrollFraction) || Float.isInfinite(restoredScrollFraction)) {
                restoredScrollFraction = 0f;
            }
            restoredScrollFraction = Math.max(0f, Math.min(1f, restoredScrollFraction));
            restoredScrollPage = requestedPage;
        }
        openDocument();
    }

    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("ClickableViewAccessibility") // 双指手势不是点击，监听器返回 false 交给 ScrollView
    private void buildUi(String title) {
        Window window = getWindow();
        SystemBars.apply(this, CREAM); // 阶段 3-B 收尾：颜色/图标统一，布局标志在下保留
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT < 30) {
            // PDF 全屏布局标志仅旧 API 需要；API30+ 用 setDecorFitsSystemWindows(false)
            int flags = window.getDecorView().getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            window.getDecorView().setSystemUiVisibility(flags);
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CREAM);
        int side = dp(12);
        int top = dp(4);
        int bottom = dp(8);
        root.setPadding(side, top, side, bottom);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int leftInset;
            int topInset;
            int rightInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                leftInset = safe.left;
                topInset = safe.top;
                rightInset = safe.right;
                bottomInset = safe.bottom;
            } else {
                leftInset = insets.getSystemWindowInsetLeft();
                topInset = insets.getSystemWindowInsetTop();
                rightInset = insets.getSystemWindowInsetRight();
                bottomInset = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    leftInset = Math.max(leftInset, insets.getDisplayCutout().getSafeInsetLeft());
                    topInset = Math.max(topInset, insets.getDisplayCutout().getSafeInsetTop());
                    rightInset = Math.max(rightInset, insets.getDisplayCutout().getSafeInsetRight());
                    bottomInset = Math.max(bottomInset, insets.getDisplayCutout().getSafeInsetBottom());
                }
            }
            view.setPadding(side + leftInset, top + topInset,
                    side + rightInset, bottom + bottomInset);
            return insets;
        });

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = makeButton("返回");
        back.setContentDescription("返回书架");
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(68), dp(48)));
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(GREEN);
        heading.setTextSize(20);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setPadding(dp(12), 0, dp(4), 0);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        status = new TextView(this);
        status.setText("正在打开 PDF…");
        status.setTextColor(MUTED);
        status.setTextSize(13);
        status.setPadding(dp(4), dp(4), dp(4), dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout pageArea = new FrameLayout(this);
        // 放大后页宽超过视口，需要横向滚动；纵向仍由外层 ScrollView 负责
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setSaveEnabled(false);
        horizontalScroll = new HorizontalScrollView(this);
        horizontalScroll.setFillViewport(false);
        horizontalScroll.setSaveEnabled(false);
        imageView = new ImageView(this);
        imageView.setContentDescription("PDF 页面图像");
        // 显式给出布局尺寸并按比例绘制，adjustViewBounds 会让裁边后的尺寸被再次猜测，关闭它
        imageView.setAdjustViewBounds(false);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundColor(Color.WHITE);
        // A software layer keeps display-list references from outliving explicit recycling.
        // The page dimensions and number of live page bitmaps are both bounded.
        imageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        imageView.setSaveEnabled(false);
        horizontalScroll.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        scrollView.addView(horizontalScroll, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        pageArea.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // onScaleEnd 只给最后一次事件的因子，不是整段手势的累积值，自己攒比例再定档
        final float[] gestureScale = new float[]{1f};
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                gestureScale[0] = 1f;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                gestureScale[0] *= detector.getScaleFactor();
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                setZoom(PdfViewOptions.zoomByGesture(zoomIndex, gestureScale[0]));
            }
        });
        scrollView.setOnTouchListener((view, event) -> {
            scaleDetector.onTouchEvent(event);
            return false; // 继续交给内外两层 ScrollView 处理平移
        });
        errorView = new TextView(this);
        errorView.setGravity(Gravity.CENTER);
        errorView.setTextColor(INK);
        errorView.setTextSize(16);
        errorView.setPadding(dp(20), dp(20), dp(20), dp(20));
        errorView.setBackgroundColor(CREAM);
        errorView.setVisibility(View.GONE);
        pageArea.addView(errorView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(44), dp(44));
        progressParams.gravity = Gravity.CENTER;
        pageArea.addView(progress, progressParams);
        root.addView(pageArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 渲染选项：缩放按档重渲染（不是位图插值），反色与裁边即时生效
        LinearLayout options = new LinearLayout(this);
        options.setGravity(Gravity.CENTER_VERTICAL);
        options.setPadding(0, dp(4), 0, 0);
        Button zoomOutButton = makeButton("－");
        zoomOutButton.setContentDescription("缩小");
        Button zoomInButton = makeButton("＋");
        zoomInButton.setContentDescription("放大");
        Button nightButton = makeButton(night ? "日间" : "夜间");
        nightButton.setContentDescription("切换页面反色");
        Button cropButton = makeButton(cropMargins ? "裁边：开" : "裁边：关");
        cropButton.setContentDescription("切换白边裁剪");
        zoomOutButton.setOnClickListener(view -> setZoom(PdfViewOptions.zoomOut(zoomIndex)));
        zoomInButton.setOnClickListener(view -> setZoom(PdfViewOptions.zoomIn(zoomIndex)));
        nightButton.setOnClickListener(view -> {
            night = !night;
            nightButton.setText(night ? "日间" : "夜间");
            applyNight();
            persistOptions();
        });
        cropButton.setOnClickListener(view -> {
            cropMargins = !cropMargins;
            cropButton.setText(cropMargins ? "裁边：开" : "裁边：关");
            persistOptions();
            requestPage(requestedPage, false);
        });
        options.addView(zoomOutButton, new LinearLayout.LayoutParams(0, dp(44), 1f));
        options.addView(zoomInButton, new LinearLayout.LayoutParams(0, dp(44), 1f));
        options.addView(nightButton, new LinearLayout.LayoutParams(0, dp(44), 1.2f));
        options.addView(cropButton, new LinearLayout.LayoutParams(0, dp(44), 1.6f));
        root.addView(options, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(0, dp(8), 0, 0);
        previous = makeButton("上一页");
        pageButton = makeButton("页码跳转");
        next = makeButton("下一页");
        pageButton.setTextSize(13);
        previous.setOnClickListener(view -> requestPage(requestedPage - 1, true));
        next.setOnClickListener(view -> requestPage(requestedPage + 1, true));
        pageButton.setOnClickListener(view -> showPageDialog());
        navigation.addView(previous, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams jumpParams = new LinearLayout.LayoutParams(0, dp(52), 1.6f);
        jumpParams.setMargins(dp(6), 0, dp(6), 0);
        navigation.addView(pageButton, jumpParams);
        navigation.addView(next, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(navigation, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        updateNavigation();
        setContentView(root);
        root.requestApplyInsets();
        scrollView.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (ready && r - l > 0 && r - l != oldR - oldL) {
                requestPage(requestedPage, false);
            }
        });
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(6), dp(3), dp(6), dp(3));
        button.setTextColor(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_enabled}, new int[]{}},
                new int[]{Color.WHITE, 0xFFEEF0E9}));
        GradientDrawable background = new GradientDrawable();
        background.setColor(GREEN);
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        button.setBackgroundTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_enabled}, new int[]{}},
                new int[]{GREEN, 0xFFA6B1A5}));
        return button;
    }

    private void openDocument() {
        worker.execute(() -> {
            ParcelFileDescriptor descriptor = null;
            try {
                if (destroyed.get()) return;
                BookStore store = new BookStore(this);
                BookStore.Book book = store.find(bookId);
                if (!"pdf".equals(book.format)) throw new IOException("这本书不是 PDF");
                try (BookAccess access = store.open(book)) {
                    descriptor = access.descriptor == null
                            ? ParcelFileDescriptor.open(access.file, ParcelFileDescriptor.MODE_READ_ONLY)
                            : access.descriptor.dup();
                    renderer = new PdfRenderer(descriptor);
                    descriptor = null;
                }
                int count = renderer.getPageCount();
                if (count <= 0) throw new IOException("PDF 没有可阅读的页面");
                main.post(() -> {
                    if (destroyed.get()) return;
                    pageCount = count;
                    ready = true;
                    requestedPage = Math.min(requestedPage, pageCount - 1);
                    preferences.edit().putInt(countKey, pageCount).apply();
                    requestPage(requestedPage, false);
                });
            } catch (SecurityException encrypted) {
                closeRenderer();
                postOpenFailure("无法打开 PDF。请检查文件权限，密码或加密 PDF 暂不支持。");
            } catch (OutOfMemoryError insufficientMemory) {
                closeRenderer();
                postOpenFailure("打开 PDF 时内存不足，请关闭其它应用后重试。");
            } catch (IOException | RuntimeException error) {
                closeRenderer();
                String detail = error.getMessage();
                postOpenFailure("无法读取 PDF" + (TextUtils.isEmpty(detail) ? "。请检查文件。" : "。" + detail));
            } finally {
                if (descriptor != null) {
                    try { descriptor.close(); } catch (IOException ignored) { }
                }
                if (destroyed.get()) closeRenderer();
            }
        });
    }

    private void postOpenFailure(String message) {
        main.post(() -> {
            if (destroyed.get()) return;
            ready = false;
            showError(message);
            updateNavigation();
            new AlertDialog.Builder(this).setTitle("无法打开 PDF").setMessage(message)
                    .setPositiveButton("书籍详情", (d, w) -> { startActivity(new Intent(this, BookDetailActivity.class).putExtra("bookId", bookId)); finish(); })
                    .setNegativeButton("返回", (d, w) -> finish()).show();
        });
    }

    private void requestPage(int page, boolean resetScroll) {
        if (!ready || destroyed.get() || page < 0 || page >= pageCount) return;
        int viewport = scrollView.getWidth();
        if (viewport <= 1) {
            viewport = Math.max(1, getResources().getDisplayMetrics().widthPixels - dp(24));
        }
        // 按档位提高渲染分辨率：放大后文字是真的更清晰，不是把小位图拉大
        int width = PdfViewOptions.renderWidth(viewport, zoomIndex, MAX_WIDTH);
        if (page == requestedPage && width == requestedWidth
                && (renderInFlight || displayedPage == page) && errorView.getVisibility() != View.VISIBLE) {
            return;
        }
        if (resetScroll || page != requestedPage) {
            restoredScrollPage = -1;
            restoredScrollFraction = 0f;
        }
        requestedPage = page;
        requestedWidth = width;
        generation.incrementAndGet();
        errorView.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setText("正在加载第 " + (page + 1) + " 页…");
        updateNavigation();
        startLatestRender();
    }

    private void startLatestRender() {
        if (renderInFlight || !ready || destroyed.get()) return;
        renderInFlight = true;
        final int pageIndex = requestedPage;
        final int width = requestedWidth;
        final int token = generation.get();
        try {
            worker.execute(() -> renderPage(pageIndex, width, token));
        } catch (RejectedExecutionException closedExecutor) {
            renderInFlight = false;
            if (!destroyed.get()) showError("阅读器已关闭，请返回书架后重新打开。");
        }
    }

    private void renderPage(int pageIndex, int targetWidth, int token) {
        Bitmap bitmap = null;
        String error = null;
        try {
            if (!destroyed.get() && token == generation.get()) {
                if (renderer == null) throw new IllegalStateException("renderer closed");
                try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                    int pageWidth = page.getWidth();
                    int pageHeight = page.getHeight();
                    if (pageWidth <= 0 || pageHeight <= 0) {
                        throw new IllegalArgumentException("invalid page size");
                    }
                    double scale = Math.min(targetWidth / (double) pageWidth,
                            MAX_HEIGHT / (double) pageHeight);
                    scale = Math.min(scale,
                            Math.sqrt(pixelLimit / ((double) pageWidth * pageHeight)));
                    int width = Math.max(1, Math.min(MAX_WIDTH,
                            (int) Math.floor(pageWidth * scale)));
                    int height = Math.max(1, Math.min(MAX_HEIGHT,
                            (int) Math.floor(pageHeight * scale)));
                    if ((long) width * height > pixelLimit) {
                        height = Math.max(1, (int) (pixelLimit / width));
                    }
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    Matrix transform = new Matrix();
                    transform.setScale(width / (float) pageWidth, height / (float) pageHeight);
                    page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    if (cropMargins) {
                        final Bitmap source = bitmap; // lambda 要捕获最终变量
                        final int w = source.getWidth(), h = source.getHeight();
                        PageCrop.Bounds raw = PageCrop.contentBounds(w, h,
                                (x, y) -> source.getPixel(x, y));
                        int margin = Math.max(4, Math.min(w, h) / 100);
                        PageCrop.Bounds box = raw.expanded(margin, w, h);
                        if (PageCrop.worthCropping(box, w, h, MIN_CROP_GAIN)) {
                            // 不 recycle(source)：createBitmap 的子位图可能共用像素缓冲，
                            // 提前回收会让显示出来的页面变黑；让它随本次渲染结果一起被 GC
                            bitmap = Bitmap.createBitmap(source,
                                    box.left, box.top, box.width(), box.height());
                        }
                    }
                }
            }
        } catch (OutOfMemoryError insufficientMemory) {
            error = "此页渲染时内存不足，请关闭其它应用后点击页码重试。";
        } catch (SecurityException unsupportedEncryption) {
            error = "此 PDF 无法渲染，密码或加密 PDF 暂不支持。";
        } catch (RuntimeException invalidPage) {
            error = "此页无法显示，文件可能损坏。可切换其它页，或点击页码重试。";
        }
        if (error != null || destroyed.get() || token != generation.get()) {
            recycle(bitmap);
            bitmap = null;
        }
        final Bitmap result = bitmap;
        final String failure = error;
        // Do not start another render until the UI consumes/recycles this result.
        // This also bounds results awaiting the main looper to exactly one bitmap.
        if (!main.post(() -> acceptRender(pageIndex, token, result, failure))) recycle(result);
    }

    private void acceptRender(int pageIndex, int token, Bitmap bitmap, String error) {
        renderInFlight = false;
        if (destroyed.get()) {
            recycle(bitmap);
            return;
        }
        if (token != generation.get()) {
            recycle(bitmap);
            startLatestRender();
            return;
        }
        progress.setVisibility(View.GONE);
        if (error != null || bitmap == null) {
            recycle(bitmap);
            showError(error == null ? "页面未能加载，请点击页码重试。" : error);
            return;
        }
        clearDisplayedBitmap();
        displayedBitmap = bitmap;
        displayedPage = pageIndex;
        // Bound the software display layer as well, including pathological tall pages.
        int viewport = Math.max(1, scrollView.getWidth());
        int layoutWidth = PdfViewOptions.renderWidth(viewport, zoomIndex, MAX_WIDTH);
        int layoutHeight = Math.max(1,
                Math.round(bitmap.getHeight() * (layoutWidth / (float) bitmap.getWidth())));
        imageView.setLayoutParams(new FrameLayout.LayoutParams(layoutWidth, layoutHeight));
        applyNight();
        imageView.setImageBitmap(bitmap);
        if (horizontalScroll != null) horizontalScroll.scrollTo(0, 0);
        imageView.setContentDescription("PDF 第 " + (pageIndex + 1) + " 页，共 " + pageCount + " 页");
        errorView.setVisibility(View.GONE);
        status.setText("第 " + (pageIndex + 1) + " 页，共 " + pageCount + " 页 · 点击下方页码跳转");
        saveProgress();
        final float fraction = restoredScrollPage == pageIndex ? restoredScrollFraction : 0f;
        restoredScrollPage = -1;
        restoredScrollFraction = 0f;
        imageView.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (imageView.getViewTreeObserver().isAlive()) {
                            imageView.getViewTreeObserver().removeOnPreDrawListener(this);
                        }
                        if (!destroyed.get() && token == generation.get() && displayedPage == pageIndex) {
                            int range = Math.max(0, imageView.getHeight() - scrollView.getHeight());
                            scrollView.scrollTo(0, Math.round(range * fraction));
                        }
                        return true;
                    }
                });
        updateNavigation();
    }

    private void showError(String message) {
        progress.setVisibility(View.GONE);
        status.setText("PDF 暂时无法显示");
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    private void updateNavigation() {
        previous.setEnabled(ready && requestedPage > 0);
        next.setEnabled(ready && requestedPage + 1 < pageCount);
        pageButton.setEnabled(ready);
        pageButton.setText(ready ? "第 " + (requestedPage + 1) + " / " + pageCount + " 页 · 跳转" : "页码跳转");
        pageButton.setContentDescription(ready
                ? "当前第 " + (requestedPage + 1) + " 页，共 " + pageCount + " 页，点击跳转" : "页码跳转");
    }

    private void showPageDialog() {
        if (!ready || destroyed.get() || (jumpDialog != null && jumpDialog.isShowing())) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setTextColor(INK);
        input.setText(String.valueOf(requestedPage + 1));
        input.selectAll();
        input.setHint("请输入 1 至 " + pageCount);
        input.setContentDescription("跳转页码，范围 1 至 " + pageCount);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(dp(24), dp(8), dp(24), 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("跳转到指定页")
                .setMessage("请输入 1 至 " + pageCount + " 的页码")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("跳转", null)
                .create();
        jumpDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (jumpDialog == dialog) jumpDialog = null;
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(GREEN);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(GREEN);
            View.OnClickListener jump = view -> {
                try {
                    int page = Integer.parseInt(input.getText().toString().trim());
                    if (page < 1 || page > pageCount) throw new NumberFormatException();
                    dialog.dismiss();
                    requestPage(page - 1, true);
                } catch (NumberFormatException invalidNumber) {
                    input.setError("请输入 1 至 " + pageCount + " 的有效整数");
                }
            };
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(jump);
            input.setOnEditorActionListener((view, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_GO) return false;
                jump.onClick(view);
                return true;
            });
            input.requestFocus();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private void setZoom(int index) {
        int target = PdfViewOptions.clampIndex(index);
        if (target == zoomIndex) return;
        zoomIndex = target;
        persistOptions();
        // 档位变了渲染宽度就变了，requestPage 的宽度比较会触发重新渲染
        requestPage(requestedPage, false);
    }

    /** 只反色页面图像与页底，工具条保持系统配色，避免整页控件对比度失控。 */
    private void applyNight() {
        if (imageView == null) return;
        imageView.setColorFilter(night
                ? new ColorMatrixColorFilter(PdfViewOptions.invertedColorMatrix()) : null);
        imageView.setBackgroundColor(night ? Color.BLACK : Color.WHITE);
    }

    private void persistOptions() {
        if (preferences == null) return;
        preferences.edit().putInt("pdf_zoom", zoomIndex)
                .putBoolean("pdf_night", night)
                .putBoolean("pdf_crop", cropMargins).apply();
    }

    private void saveProgress() {
        if (preferences != null && pageKey != null && displayedPage >= 0 && pageCount > 0) {
            preferences.edit().putInt(pageKey, displayedPage).putInt(countKey, pageCount).apply();
        }
    }

    @Override
    protected void onPause() {
        saveProgress();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_BOOK, bookId);
        outState.putInt(STATE_PAGE, requestedPage);
        float fraction = 0f;
        if (displayedPage == requestedPage && imageView != null && scrollView != null) {
            int range = Math.max(0, imageView.getHeight() - scrollView.getHeight());
            if (range > 0) fraction = Math.min(1f, scrollView.getScrollY() / (float) range);
        } else if (restoredScrollPage == requestedPage) {
            fraction = restoredScrollFraction;
        }
        outState.putFloat(STATE_SCROLL, fraction);
    }

    @Override
    protected void onDestroy() {
        saveProgress();
        destroyed.set(true);
        generation.incrementAndGet();
        if (jumpDialog != null) {
            jumpDialog.dismiss();
            jumpDialog = null;
        }
        clearDisplayedBitmap();
        // A serial close is queued AFTER any constructor/render currently running.
        // shutdownNow(), awaitTermination() and closing renderer on main are unsafe here.
        try {
            worker.execute(this::closeRenderer);
        } catch (RejectedExecutionException alreadyClosed) {
            // Only this callback shuts down the executor; an already queued close owns it.
        } finally {
            worker.shutdown();
        }
        // Keep posted result callbacks: they must recycle any bitmap already handed off.
        super.onDestroy();
    }

    private void clearDisplayedBitmap() {
        if (imageView != null) imageView.setImageDrawable(null);
        Bitmap old = displayedBitmap;
        displayedBitmap = null;
        recycle(old);
    }

    // Worker-thread only; all Page objects have already left their try-with-resources.
    private void closeRenderer() {
        PdfRenderer old = renderer;
        renderer = null;
        if (old != null) {
            try { old.close(); } catch (RuntimeException ignored) { }
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
