package org.openleaf.reader;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import org.openleaf.reader.core.EpubCfi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Real WebView regression for the paged EPUB reader.  The fixture is imported through BookStore,
 * EpubActivity is launched normally, and every pagination/layout assertion observes the private
 * activity state plus JavaScript running in the actual WebView.  The book id and all files are
 * test-owned; cleanup never clears the user's library or preferences.
 */
public final class EpubReaderInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private int passed;
    private String testBookId;
    private File fixtureDir;
    private File fixture;
    private File packageFile;
    private EpubActivity activity;
    private SharedPreferences prefs;
    private boolean hadPagedPreference;
    private boolean oldPagedPreference;
    private int firstChapter;
    private int firstPageCount;
    private String anchorBeforeReflow;

    private interface Check { void run() throws Exception; }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            prepare();
            check("real EPUB opens in a ready WebView", this::opensReader);
            check("turnPage advances a horizontal CSS page", this::turnsPage);
            check("real next-button tap and left swipe advance pages", this::physicalInteractions);
            check("font reflow keeps a nearby CFI anchor", this::fontReflowKeepsCfi);
            check("page boundary crosses chapter and returns to prior last page", this::crossesChapter);
            check("WebView stops above footer and final text fits viewport", this::layoutAndLastLine);
            check("scroll mode preserves text and changes window.scrollY", this::scrollMode);
            result.putString("stream", "\nPASS: " + passed + " EPUB WebView checks\n" + report);
            result.putInt("passed", passed);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after " + passed + " EPUB WebView checks\n" + report
                    + android.util.Log.getStackTraceString(error));
            result.putString("failure", error.toString());
            finish(1, result);
        } finally {
            cleanup();
        }
    }

    private void prepare() throws Exception {
        Context context = getTargetContext();
        fixtureDir = new File(context.getCacheDir(), "epub-webview-check");
        if (!fixtureDir.isDirectory() && !fixtureDir.mkdirs() && !fixtureDir.isDirectory())
            throw new Exception("cannot create EPUB fixture directory");
        fixture = new File(fixtureDir, "long-two-chapter.epub");
        try (FileOutputStream out = new FileOutputStream(fixture)) { out.write(epubBytes()); }
        packageFile = new File(fixtureDir, "long-two-chapter.olbook");
        try (FileOutputStream out = new FileOutputStream(packageFile)) {
            TaggedBookPackage.write(out, fixture, "分页 WebView 回归样书", "epub", Collections.emptyList());
        }
        BookStore store = new BookStore(context);
        BookStore.ImportResult imported = store.importTagged(Uri.fromFile(packageFile), null);
        require(imported != null && imported.book != null, "EPUB fixture import failed");
        testBookId = imported.book.id;

        // New test book has no per-book reader state.  Force the requested default while running,
        // then restore the exact prior global value in cleanup (including the absent-key case).
        prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE);
        hadPagedPreference = prefs.contains("epub_paged");
        oldPagedPreference = prefs.getBoolean("epub_paged", true);
        prefs.edit().putBoolean("epub_paged", true).apply();
        record("book=" + testBookId + " fixture=" + fixture.length() + " bytes");
    }

    private void opensReader() throws Exception {
        Intent intent = new Intent(getTargetContext(), EpubActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("bookId", testBookId).putExtra("title", "分页 WebView 回归样书");
        Activity opened = startActivitySync(intent);
        require(opened instanceof EpubActivity, "EpubActivity did not start");
        activity = (EpubActivity) opened;
        require(awaitReady(20_000L), "EpubActivity pageReady timeout");
        require(boolField("paged"), "reader must start in paged mode");
        firstChapter = intField("chapter");
        firstPageCount = intField("pageCount");
        require(firstPageCount > 1, "synthetic chapter must have pageCount > 1, got " + firstPageCount);
        require(web() != null && web().getWidth() > 0, "WebView has no measured width");
        require("true".equals(evaluate("document.body.innerText.indexOf('OPENLEAF_EPUB_FIXTURE')>=0")),
                "fixture text was not rendered in WebView");
        record("chapter=" + firstChapter + " page=" + intField("pageIndex") + "/" + firstPageCount
                + " viewport=" + web().getWidth() + "x" + web().getHeight());
    }

    private void turnsPage() throws Exception {
        require(firstChapter == intField("chapter"), "initial chapter changed unexpectedly");
        int before = intField("pageIndex");
        long xBefore = number(evaluate("window.scrollX"));
        require(before < intField("pageCount") - 1, "initial page must have a next page");
        invoke("turnPage", Integer.valueOf(1));
        require(awaitStateChange(firstChapter, before, 5_000L), "turnPage(1) did not settle");
        require(firstChapter == intField("chapter"), "first turn crossed chapter too early");
        int after = intField("pageIndex");
        long xAfter = number(evaluate("window.scrollX"));
        require(after != before, "pageIndex did not change: " + before + " -> " + after);
        require(xAfter != xBefore, "window.scrollX did not change: " + xBefore + " -> " + xAfter);
        record("page=" + before + "->" + after + " scrollX=" + xBefore + "->" + xAfter);
    }

    private void physicalInteractions() throws Exception {
        View next = (View) field("next");
        require(next != null && next.isEnabled(), "next-page button is not enabled");
        int beforeButton = intField("pageIndex");
        tap(next);
        require(awaitStateChange(firstChapter, beforeButton, 5_000L), "real next-page button tap did not advance");
        require(intField("chapter") == firstChapter, "next-page button crossed chapter unexpectedly");

        WebView view = web();
        int beforeSwipe = intField("pageIndex");
        require(beforeSwipe < intField("pageCount") - 1, "fixture needs another page for swipe");
        swipeLeft(view);
        require(awaitStateChange(firstChapter, beforeSwipe, 5_000L), "real left swipe did not advance");
        require(intField("chapter") == firstChapter, "left swipe crossed chapter unexpectedly");
        record("input tap page=" + beforeButton + "->" + intField("pageIndex")
                + "; swipe from=" + beforeSwipe + "->" + intField("pageIndex"));
    }

    private void tap(View target) {
        int[] p = new int[2]; target.getLocationOnScreen(p);
        float x = p[0] + target.getWidth() / 2f, y = p[1] + target.getHeight() / 2f;
        long down = SystemClock.uptimeMillis();
        sendPointerSync(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0));
        long up = SystemClock.uptimeMillis();
        sendPointerSync(android.view.MotionEvent.obtain(down, up, android.view.MotionEvent.ACTION_UP, x, y, 0));
    }

    private void swipeLeft(View target) {
        int[] p = new int[2]; target.getLocationOnScreen(p);
        float y = p[1] + target.getHeight() / 2f;
        float start = p[0] + target.getWidth() * .78f, end = p[0] + target.getWidth() * .22f;
        long down = SystemClock.uptimeMillis();
        sendPointerSync(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, start, y, 0));
        long up = down + 180L;
        sendPointerSync(android.view.MotionEvent.obtain(down, up, android.view.MotionEvent.ACTION_UP, end, y, 0));
    }

    private void fontReflowKeepsCfi() throws Exception {
        invoke("save");
        waitForAnchor(5_000L);
        anchorBeforeReflow = stringField("savedCfi");
        require(parseCfi(anchorBeforeReflow) != null, "save did not produce a valid CFI: " + anchorBeforeReflow);
        long capturedOffset = number(evaluate("(window.__openleafReader.capture()||{}).offset||0"));
        require(capturedOffset > 0, "capture must retain a non-zero offset inside a long paragraph: " + capturedOffset);
        int oldFont = intField("font");
        setIntField("font", Math.min(32, oldFont + 6));
        int oldGeneration = intField("generation");
        invoke("reloadCurrent");
        require(awaitReload(oldGeneration, 15_000L), "font reflow did not become ready");
        waitForAnchor(3_000L);
        String after = stringField("savedCfi");
        EpubCfi.Location beforeLoc = parseCfi(anchorBeforeReflow);
        EpubCfi.Location afterLoc = parseCfi(after);
        require(afterLoc != null, "font reflow lost CFI: " + after);
        require(afterLoc.spineIndex == beforeLoc.spineIndex, "font reflow moved CFI to another chapter");
        require(sameOrNearPath(beforeLoc.path, afterLoc.path),
                "font reflow moved CFI too far: " + anchorBeforeReflow + " -> " + after);
        record("cfi=" + shortCfi(anchorBeforeReflow) + " -> " + shortCfi(after));
    }

    private void crossesChapter() throws Exception {
        int chapterBefore = intField("chapter");
        int guard = 0;
        while (intField("chapter") == chapterBefore && guard++ < 40) {
            int oldPage = intField("pageIndex");
            invoke("turnPage", Integer.valueOf(1));
            awaitStateChange(chapterBefore, oldPage, 5_000L);
        }
        require(intField("chapter") == chapterBefore + 1,
                "turnPage did not cross into next chapter: " + intField("chapter"));
        require(intField("pageIndex") == 0, "next chapter must start at page 0");
        int nextChapter = intField("chapter");
        guard = 0;
        while (intField("chapter") == nextChapter && guard++ < 40) {
            int oldPage = intField("pageIndex");
            invoke("turnPage", Integer.valueOf(-1));
            awaitStateChange(nextChapter, oldPage, 5_000L);
        }
        require(intField("chapter") == chapterBefore,
                "turnPage(-1) did not return to prior chapter: " + intField("chapter"));
        int returnedCount = intField("pageCount");
        require(intField("pageIndex") == returnedCount - 1,
                "return must land on previous chapter last page, got " + intField("pageIndex")
                        + "/" + returnedCount);
        record("chapter " + nextChapter + " -> " + chapterBefore + " last=" + (returnedCount - 1));
    }

    private void layoutAndLastLine() throws Exception {
        WebView view = web();
        int[] bounds = viewAndFooterBounds();
        require(bounds[1] >= 0, "footer was not found in activity layout");
        require(bounds[0] <= bounds[1], "WebView bottom overlaps footer: " + bounds[0] + " > " + bounds[1]);
        String[] parts = evaluate("(function(){var all=document.querySelectorAll('#epub-content *'),b=null;"
                + "for(var i=0;i<all.length;i++){var e=all[i],t=(e.innerText||e.textContent||'').trim();"
                + "if(!t)continue;var rs=document.createRange();rs.selectNodeContents(e);var q=rs.getClientRects();"
                + "if(q.length)b=q[q.length-1];}if(!b)return '0|0|0|0|'+innerWidth+'|'+innerHeight;"
                + "return b.left+'|'+b.right+'|'+b.top+'|'+b.bottom+'|'+innerWidth+'|'+innerHeight;})()").split("\\|");
        require(parts.length == 6, "invalid final text rect");
        float left = Float.parseFloat(parts[0]), right = Float.parseFloat(parts[1]);
        float top = Float.parseFloat(parts[2]), bottom = Float.parseFloat(parts[3]);
        float width = Float.parseFloat(parts[4]), height = Float.parseFloat(parts[5]);
        require(bottom > 0 && top < height && bottom <= height + 2,
                "last line is outside visible vertical area: " + join(parts));
        require(left >= -2 && right <= width + 2,
                "last line has horizontal blank/overflow: " + join(parts));
        record("webBottom=" + bounds[0] + " footerTop=" + bounds[1]
                + " lastRect=" + join(parts) + " measured=" + view.getWidth() + "x" + view.getHeight());
    }

    private void scrollMode() throws Exception {
        setBooleanField("paged", false);
        int oldGeneration = intField("generation");
        invoke("reloadCurrent");
        require(awaitReload(oldGeneration, 15_000L), "scroll-mode reload did not become ready");
        require("true".equals(evaluate("document.body.innerText.indexOf('OPENLEAF_EPUB_FIXTURE')>=0")),
                "scroll-mode reload lost fixture text");
        evaluate("window.scrollTo(0,240);true");
        long y = waitForScrollY(4_000L);
        require(y > 0, "window.scrollY did not change in scroll mode: " + y);
        require("true".equals(evaluate("document.body.innerText.indexOf('OPENLEAF_EPUB_FIXTURE')>=0")),
                "text disappeared after scrolling");
        record("scrollY=" + y + " text=retained");
    }

    private boolean awaitReady(long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < end) {
            try {
                if (boolField("pageReady") && web() != null && web().getWidth() > 0) return true;
            } catch (Exception ignored) { }
            SystemClock.sleep(50L);
        }
        return false;
    }

    private boolean awaitReload(int oldGeneration, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < end) {
            try {
                if (intField("generation") > oldGeneration && boolField("pageReady")
                        && web() != null && web().getWidth() > 0) return true;
            } catch (Exception ignored) { }
            SystemClock.sleep(50L);
        }
        return false;
    }

    private boolean awaitStateChange(int oldChapter, int oldPage, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < end) {
            try {
                if (boolField("pageReady") && (intField("chapter") != oldChapter || intField("pageIndex") != oldPage)) return true;
            } catch (Exception ignored) { }
            SystemClock.sleep(35L);
        }
        return false;
    }

    private void waitForAnchor(long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < end) {
            try { if (parseCfi(stringField("savedCfi")) != null) return; } catch (Exception ignored) { }
            SystemClock.sleep(60L);
        }
    }

    private long waitForScrollY(long timeout) throws Exception {
        long end = SystemClock.uptimeMillis() + timeout;
        long value = 0;
        while (SystemClock.uptimeMillis() < end) {
            value = number(evaluate("window.scrollY"));
            if (value > 0) return value;
            SystemClock.sleep(80L);
        }
        return value;
    }

    private WebView web() throws Exception { return (WebView) field("web"); }

    private Object field(String name) throws Exception {
        final AtomicReference<Object> result = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                Field f = EpubActivity.class.getDeclaredField(name);
                f.setAccessible(true); result.set(f.get(activity));
            } catch (Throwable t) { error.set(t); }
        });
        if (error.get() != null) throw new Exception("read field " + name, error.get());
        return result.get();
    }

    private void setIntField(String name, int value) throws Exception { setField(name, Integer.valueOf(value)); }
    private void setBooleanField(String name, boolean value) throws Exception { setField(name, Boolean.valueOf(value)); }
    private void setField(String name, Object value) throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                Field f = EpubActivity.class.getDeclaredField(name);
                f.setAccessible(true); f.set(activity, value);
            } catch (Throwable t) { error.set(t); }
        });
        if (error.get() != null) throw new Exception("write field " + name, error.get());
    }

    private int intField(String name) throws Exception { return ((Number) field(name)).intValue(); }
    private boolean boolField(String name) throws Exception { return ((Boolean) field(name)).booleanValue(); }
    private String stringField(String name) throws Exception { Object value = field(name); return value == null ? "" : String.valueOf(value); }

    private void invoke(String name, Object... args) throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                Method selected = null;
                for (Method method : EpubActivity.class.getDeclaredMethods()) {
                    if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
                    selected = method; break;
                }
                if (selected == null) throw new NoSuchMethodException(name);
                selected.setAccessible(true); selected.invoke(activity, args);
            } catch (Throwable t) { error.set(t); }
        });
        if (error.get() != null) throw new Exception("invoke " + name, error.get());
    }

    private String evaluate(String script) throws Exception {
        WebView view = web();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> value = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                view.evaluateJavascript("(function(){try{return " + script + ";}catch(e){return 'ERR:'+e;}})()",
                        result -> { value.set(unquote(result)); done.countDown(); });
            } catch (Throwable t) { error.set(t); done.countDown(); }
        });
        if (!done.await(5L, TimeUnit.SECONDS)) throw new Exception("evaluateJavascript timeout");
        if (error.get() != null) throw new Exception("evaluateJavascript failed", error.get());
        return value.get() == null ? "" : value.get();
    }

    private String unquote(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') return value;
        String body = value.substring(1, value.length() - 1);
        return body.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r");
    }

    private int[] viewAndFooterBounds() throws Exception {
        WebView view = web();
        Object footerObject = field("footer");
        final AtomicReference<int[]> result = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                int[] where = new int[2]; view.getLocationOnScreen(where);
                int webBottom = where[1] + view.getHeight();
                int footerTop = -1;
                if (footerObject instanceof View) {
                    int[] footerWhere = new int[2];
                    View footer = (View) footerObject; footer.getLocationOnScreen(footerWhere);
                    footerTop = footerWhere[1];
                }
                result.set(new int[]{webBottom, footerTop});
            } catch (Throwable t) { error.set(t); }
        });
        if (error.get() != null) throw new Exception("read WebView/footer bounds", error.get());
        return result.get();
    }

    private static EpubCfi.Location parseCfi(String value) { return EpubCfi.parse(value); }
    private static boolean sameOrNearPath(int[] a, int[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return false;
        int common = 0;
        while (common < a.length && common < b.length && a[common] == b[common]) common++;
        return common >= Math.max(1, Math.min(a.length, b.length) - 1);
    }
    private static String shortCfi(String value) { return value == null ? "" : value.length() <= 42 ? value : value.substring(0, 42); }
    private static long number(String text) { try { return Math.round(Double.parseDouble(text.trim())); } catch (Exception e) { return -1; } }
    private static String join(String[] values) { return String.join("|", values); }

    private void check(String name, Check body) throws Exception {
        try { body.run(); passed++; report.append("PASS ").append(passed).append("  ").append(name).append('\n'); }
        catch (Throwable error) { report.append("FAIL  ").append(name).append(" -> ").append(error).append('\n'); throw error; }
    }
    private void record(String detail) { report.append("      ").append(detail).append('\n'); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private void cleanup() {
        try {
            if (activity != null) runOnMainSync(activity::finish);
        } catch (Exception ignored) { }
        SystemClock.sleep(250L);
        try {
            if (testBookId != null) {
                BookStore store = new BookStore(getTargetContext());
                BookStore.Book book = store.find(testBookId);
                if (book != null) store.remove(book);
                getTargetContext().getSharedPreferences("reader", 0).edit()
                        .remove("epub_chapter_" + testBookId).remove("epub_scroll_" + testBookId)
                        .remove("epub_page_" + testBookId).remove("epubCfi_" + testBookId)
                        .remove("epubBookCss_" + testBookId).apply();
            }
        } catch (Exception ignored) { }
        try {
            if (prefs != null) {
                SharedPreferences.Editor editor = prefs.edit();
                if (hadPagedPreference) editor.putBoolean("epub_paged", oldPagedPreference);
                else editor.remove("epub_paged");
                editor.apply();
            }
        } catch (Exception ignored) { }
        if (packageFile != null) packageFile.delete();
        if (fixture != null) fixture.delete();
        if (fixtureDir != null) fixtureDir.delete();
    }

    private static byte[] epubBytes() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            byte[] mime = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            ZipEntry mimetype = new ZipEntry("mimetype");
            mimetype.setMethod(ZipEntry.STORED); mimetype.setSize(mime.length);
            CRC32 crc = new CRC32(); crc.update(mime); mimetype.setCrc(crc.getValue());
            zip.putNextEntry(mimetype); zip.write(mime); zip.closeEntry();
            put(zip, "META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                    + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
                    + "</rootfiles></container>");
            put(zip, "OEBPS/content.opf", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"bookid\">"
                    + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"bookid\">openleaf-webview-check</dc:identifier>"
                    + "<dc:title>OpenLeaf WebView fixture</dc:title><dc:language>zh</dc:language></metadata>"
                    + "<manifest><item id=\"one\" href=\"chapter1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                    + "<item id=\"two\" href=\"chapter2.xhtml\" media-type=\"application/xhtml+xml\"/>"
                    + "<item id=\"pixel\" href=\"images/中文 空格.png\" media-type=\"image/png\"/></manifest>"
                    + "<spine><itemref idref=\"one\"/><itemref idref=\"two\"/></spine></package>");
            put(zip, "OEBPS/chapter1.xhtml", chapter(1));
            put(zip, "OEBPS/chapter2.xhtml", chapter(2));
            zip.putNextEntry(new ZipEntry("OEBPS/images/中文 空格.png")); zip.write(pixelPng()); zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String value) throws Exception {
        put(zip, name, value.getBytes(StandardCharsets.UTF_8));
    }
    private static void put(ZipOutputStream zip, String name, byte[] value) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value); zip.closeEntry();
    }
    private static String chapter(int number) {
        StringBuilder body = new StringBuilder();
        body.append("<p>&#160;&#160;");
        for (int i = 0; i < 500; i++) body.append("OPENLEAF_EPUB_FIXTURE 长段落锚点 ").append(number).append('-').append(i).append("。这段文字用于验证跨页 offset。 ");
        body.append("</p>");
        body.append("<h1>OPENLEAF_EPUB_FIXTURE 第").append(number).append("章</h1>");
        body.append("<p><b>甲</p>乙</b>");
        body.append("<p>OPENLEAF_EPUB_FIXTURE anchor chapter ").append(number).append(".</p>");
        body.append("<figure><img src=\"images/中文 空格.png\" alt=\"fixture image\"/><figcaption>合成图像</figcaption></figure>");
        for (int i = 0; i < 150; i++) {
            body.append("<p>这是用于 Android WebView 分页回归的长段落 ").append(number).append('-').append(i)
                    .append("。它故意包含足够多的连续文字，用来产生多个 CSS columns，并验证跨页、跨章、底栏遮挡、最后一行可读以及字号变化后的结构锚点。OPENLEAF_EPUB_FIXTURE ")
                    .append("本段还保留一段很长的句子：分页不应裁掉文字，也不应产生横向多出的空白。 </p>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第"
                + number + "章</title></head><body>" + body + "</body></html>";
    }
    private static byte[] pixelPng() {
        return new byte[]{(byte)137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,1,0,0,0,1,8,6,0,0,0,31,21,(byte)196,(byte)137,
                0,0,0,13,73,68,65,84,120,(byte)156,99,96,96,96,0,0,0,4,0,1,(byte)232,38,5,(byte)155,0,0,0,0,73,69,78,68,(byte)174,66,96,(byte)130};
    }
}
