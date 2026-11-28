package org.openleaf.reader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Debug-only, exported entry point. Android 17 blocks activity starts from instrumentation on this
 * device, so the QA flow imports a synthetic book here and then launches the real reader from a
 * foreground app activity. Never shipped in a release build.
 */
public final class EpubQaBootstrapActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                Context context = getApplicationContext();
                BookStore store = new BookStore(context);
                File statusFile = new File(context.getFilesDir(), "qa-epub.json");
                String existing = statusFile.isFile()
                        ? new String(java.nio.file.Files.readAllBytes(statusFile.toPath()), StandardCharsets.UTF_8) : "";
                String id = "";
                int at = existing.indexOf("\"bookId\":\"");
                if (at >= 0) {
                    int start = at + 10, end = existing.indexOf('"', start);
                    if (end > start) id = existing.substring(start, end);
                }
                // Reuse the already imported book so position restore can be tested across restarts.
                if (!id.isEmpty() && store.find(id) != null) {
                    if (getIntent().getBooleanExtra("qaScroll", false)) {
                        getSharedPreferences("reader", 0).edit().putBoolean("epub_paged", false)
                                .remove("epub_page_" + id).remove("epubCfi_" + id).commit();
                    }
                    startActivity(new Intent(this, EpubActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("bookId", id).putExtra("title", "分页回归样书")
                            .putExtra("qaMetrics", true));
                    finish();
                    return;
                }
                File dir = new File(context.getFilesDir(), "epub-qa");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("no fixture dir");
                File epub = new File(dir, "qa-two-chapters.epub");
                try (FileOutputStream out = new FileOutputStream(epub)) { out.write(epubBytes()); }
                File pack = new File(dir, "qa-two-chapters.olbook");
                try (FileOutputStream out = new FileOutputStream(pack)) {
                    TaggedBookPackage.write(out, epub, "分页回归样书", "epub", Collections.emptyList());
                }
                BookStore.ImportResult imported = store.importTagged(Uri.fromFile(pack), null);
                if (imported == null || imported.book == null) throw new Exception("import failed");
                id = imported.book.id;
                getSharedPreferences("reader", 0).edit()
                        .putBoolean("epub_paged", true)
                        .remove("epub_chapter_" + id).remove("epub_scroll_" + id)
                        .remove("epub_page_" + id).remove("epubCfi_" + id).commit();
                String status = "{\"bookId\":\"" + id + "\",\"format\":\"" + imported.book.format
                        + "\",\"file\":\"" + epub.getName() + "\",\"bytes\":" + epub.length() + "}";
                try (FileOutputStream out = new FileOutputStream(statusFile)) {
                    out.write(status.getBytes(StandardCharsets.UTF_8));
                }
                SystemClock.sleep(300L);
                startActivity(new Intent(this, EpubActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("bookId", id).putExtra("title", "分页回归样书")
                        .putExtra("qaMetrics", true));
            } catch (Exception error) {
                try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "qa-epub.json"))) {
                    out.write(("{\"error\":\"" + String.valueOf(error.getMessage()).replace('"', '\'') + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) { }
            }
            finish();
        }, "epub-qa-bootstrap").start();
    }

    /** Two long chapters, an image with a raw Chinese space path, and deliberately crossed tags. */
    static byte[] epubBytes() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            byte[] mime = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            ZipEntry stored = new ZipEntry("mimetype");
            stored.setMethod(ZipEntry.STORED); stored.setSize(mime.length);
            CRC32 crc = new CRC32(); crc.update(mime); stored.setCrc(crc.getValue());
            zip.putNextEntry(stored); zip.write(mime); zip.closeEntry();
            put(zip, "META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                    + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
                    + "</rootfiles></container>");
            put(zip, "OEBPS/content.opf", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"bookid\">"
                    + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                    + "<dc:identifier id=\"bookid\">openleaf-epub-qa-0631</dc:identifier>"
                    + "<dc:title>分页回归样书</dc:title><dc:language>zh</dc:language></metadata>"
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
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String chapter(int number) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>QA 第").append(number).append("章</h1>");
        body.append("<p>OPENLEAF_QA_CHAPTER_").append(number).append(" 起始段落。</p>");
        // Crossed tags plus a missing </p>: the strict SAX pass fails, the controlled body repair runs.
        body.append("<p><b>错配标签中文</p>继续正文</b>");
        body.append("<figure><img src=\"images/中文 空格.png\" alt=\"样图\"/><figcaption>带空格路径的图像</figcaption></figure>");
        for (int i = 0; i < 140; i++) {
            body.append("<p>第").append(i).append("段：用于真机分页回归的长文本，验证左右翻页、跨章返回上一章末页、")
                    .append("正文不被底部操作栏遮挡，以及最后一行文字完整位于可视区域内。OPENLEAF_QA_CHAPTER_")
                    .append(number).append(" 分页不应裁掉任何文字，也不应出现横向多余空白。");
            body.append("这一段特意写得更长一些，使得单段就能跨越多页，从而检验页内位置锚点的字符偏移在重新排版后仍然可用。")
                    .append("继续补充句子，让 CSS 多列布局产生足够多的分页。").append("</p>");
        }
        body.append("<p>OPENLEAF_QA_CHAPTER_").append(number).append(" 结束段落。</p>");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE html>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第" + number + "章</title></head>"
                + "<body>" + body + "</body></html>";
    }

    private static byte[] pixelPng() {
        return new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1,
                8, 6, 0, 0, 0, 31, 21, (byte) 196, (byte) 137, 0, 0, 0, 13, 73, 68, 65, 84, 120, (byte) 156, 99, 96, 96,
                96, 0, 0, 0, 4, 0, 1, (byte) 232, 38, 5, (byte) 155, 0, 0, 0, 0, 73, 69, 78, 68, (byte) 174, 66, 96,
                (byte) 130};
    }
}
