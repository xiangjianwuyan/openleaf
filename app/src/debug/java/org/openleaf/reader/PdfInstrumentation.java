package org.openleaf.reader;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import org.openleaf.reader.core.PageCrop;
import org.openleaf.reader.core.PdfViewOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PDF 渲染侧设备回归：合成一份真的单页 PDF（现有 .pdf 夹具只是占位文本，PdfRenderer 打不开），
 * 在设备上真渲染一页，验证白边检测与 PdfActivity 能正常打开，而不是只测纯逻辑。
 */
public final class PdfInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private int passed;
    private String testBookId;
    private File fixture;

    private interface Check { void run() throws Exception; }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check("synthetic one-page PDF renders on device", this::rendersPage);
            check("crop finds the drawn rectangle on a real rendered page", this::cropsRealPage);
            check("zoom levels and invert matrix stay bounded", this::viewOptions);
            check("PdfActivity opens the PDF and records its page count", this::opensActivity);
            result.putString("stream", "\nPASS: " + passed + " pdf rendering checks\n" + report);
            result.putInt("passed", passed);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after " + passed + " checks\n" + report
                    + android.util.Log.getStackTraceString(error));
            result.putString("failure", error.toString());
            finish(1, result);
        } finally {
            cleanup();
        }
    }

    private File pdfFixture() throws Exception {
        if (fixture != null) return fixture;
        File dir = new File(getTargetContext().getCacheDir(), "pdf-render-check");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("cannot create PDF fixture dir");
        fixture = new File(dir, "crop-check.pdf");
        java.nio.file.Files.write(fixture.toPath(), minimalPdf());
        return fixture;
    }

    /** 一页 300×400 的白底 PDF，中间画一个黑色矩形，方便验证白边检测。 */
    private static byte[] minimalPdf() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        append(out, "%PDF-1.4\n");
        offsets.add(out.size());
        append(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        offsets.add(out.size());
        append(out, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        offsets.add(out.size());
        append(out, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] "
                + "/Resources << >> /Contents 4 0 R >>\nendobj\n");
        String content = "q 1 0 0 rg 60 60 180 280 re f Q\n";
        offsets.add(out.size());
        append(out, "4 0 obj\n<< /Length " + content.length() + " >>\nstream\n"
                + content + "endstream\nendobj\n");
        int xref = out.size();
        append(out, "xref\n0 5\n0000000000 65535 f \n");
        for (int offset : offsets) {
            append(out, String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        }
        append(out, "trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static void append(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    private void rendersPage() throws Exception {
        File file = pdfFixture();
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            eq(1, renderer.getPageCount(), "合成 PDF 应只有一页");
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(0xFFFFFFFF);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                require(!bitmap.isRecycled() && bitmap.getWidth() > 0, "页面位图无效");
                record(bitmap.getWidth() + "x" + bitmap.getHeight());
                bitmap.recycle();
            }
        }
    }

    private void cropsRealPage() throws Exception {
        File file = pdfFixture();
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd);
             PdfRenderer.Page page = renderer.openPage(0)) {
            Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xFFFFFFFF);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            final int w = bitmap.getWidth(), h = bitmap.getHeight();
            PageCrop.Bounds raw = PageCrop.contentBounds(w, h, (x, y) -> bitmap.getPixel(x, y));
            PageCrop.Bounds box = raw.expanded(4, w, h);
            require(PageCrop.worthCropping(box, w, h, 0.08f), "合成页四周有大块白边，应判定值得裁");
            require(box.width() < w && box.height() < h, "裁剪框必须比整页小");
            bitmap.recycle();
            record("crop=" + box.width() + "x" + box.height() + " from " + w + "x" + h);
        }
    }

    private void viewOptions() {
        require(PdfViewOptions.ZOOM_LEVELS[0] == 1f, "最小档应为 1×");
        eq(3, PdfViewOptions.clampIndex(99), "档位应被夹住");
        eq(3, PdfViewOptions.zoomByGesture(0, 3.1f), "大比例手势应到最大档");
        float[] matrix = PdfViewOptions.invertedColorMatrix();
        eq(20, matrix.length, "反色矩阵长度");
        record("levels=" + PdfViewOptions.ZOOM_LEVELS.length);
    }

    private void opensActivity() throws Exception {
        Context ctx = getTargetContext();
        File pack = new File(ctx.getCacheDir(), "pdf-render-check/book.olbook");
        try (FileOutputStream out = new FileOutputStream(pack)) {
            TaggedBookPackage.write(out, pdfFixture(), "PDF渲染测试", "pdf", java.util.Collections.emptyList());
        }
        BookStore store = new BookStore(ctx);
        BookStore.ImportResult imported = store.importTagged(Uri.fromFile(pack), null);
        require(imported != null && imported.book != null, "PDF 书包导入失败");
        testBookId = imported.book.id;
        ctx.startActivity(new Intent(ctx, PdfActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("bookId", testBookId)
                .putExtra("title", "PDF渲染测试"));
        long deadline = System.currentTimeMillis() + 15_000L;
        int count = 0;
        while (System.currentTimeMillis() < deadline && count <= 0) {
            count = ctx.getSharedPreferences("reader", 0).getInt("pdf_count_" + testBookId, 0);
            if (count > 0) break;
            try { Thread.sleep(200L); } catch (InterruptedException interrupted) { break; }
        }
        require(count > 0, "PdfActivity 没有成功渲染并记录页数");
        record("pageCount=" + count);
    }

    private void cleanup() {
        if (testBookId != null) {
            try {
                BookStore store = new BookStore(getTargetContext());
                BookStore.Book book = store.find(testBookId);
                if (book != null) store.remove(book);
            } catch (Exception ignored) { }
            try {
                getTargetContext().getSharedPreferences("reader", 0).edit()
                        .remove("pdf_page_" + testBookId).remove("pdf_count_" + testBookId)
                        .remove("pdf_zoom").remove("pdf_night").remove("pdf_crop").apply();
            } catch (RuntimeException ignored) { }
        }
        if (fixture != null) {
            File pack = new File(fixture.getParentFile(), "book.olbook");
            pack.delete();
            fixture.delete();
        }
    }

    private void check(String name, Check body) throws Exception {
        try {
            body.run();
            passed++;
            report.append("PASS ").append(passed).append("  ").append(name).append('\n');
        } catch (Throwable error) {
            report.append("FAIL  ").append(name).append(" -> ").append(error).append('\n');
            throw error;
        }
    }

    private void record(String detail) { report.append("      ").append(detail).append('\n'); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void eq(long expected, long actual, String message) {
        if (expected != actual) throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
    }
}
