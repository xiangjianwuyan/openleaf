package org.openleaf.reader;

import android.content.Context;

import org.openleaf.reader.core.ContentContracts;
import org.openleaf.reader.core.DownloadPolicy;
import org.openleaf.reader.core.DownloadUseCase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 网络资源落盘执行器：HTTPS 策略复用 CatalogClient.checkedUrl，临时文件 + 校验后提交。
 * 实现 DownloadUseCase.Fetcher，由 JobRepository 记录检查点。
 *
 * 断点续传：仅在"本地 .part 有字节 + 资源标识可验证 + 服务端确认 206 且起点匹配"时续写；
 * 否则删除 part 从头下载。最终对完整文件重新计算 SHA-256，杜绝拼接不同版本内容。
 */
final class AssetDownloader implements DownloadUseCase.Fetcher {
    private static final long DEFAULT_MAX = org.openleaf.reader.core.BookParser.MAX_IMPORT_BYTES;
    private static final int MAX_REDIRECTS = 5;
    private final Context app;
    private final long budget;

    AssetDownloader(Context context) {
        this.app = context.getApplicationContext();
        this.budget = DEFAULT_MAX;
    }

    @Override public DownloadUseCase.Result fetch(DownloadUseCase.Request request) throws IOException {
        String name = DownloadPolicy.safeFileName(request.fileName, 80);
        File dir = new File(app.getFilesDir(), "downloads");
        if (!dir.isDirectory() && !dir.mkdirs()) return DownloadUseCase.Result.fail("无法创建下载目录");
        File target = new File(dir, java.util.UUID.randomUUID() + "-" + name);
        File staged = new File(dir, target.getName() + ".part");
        File meta = new File(dir, target.getName() + ".part.meta");
        String url = request.url;
        String lastEtag = "", lastModified = "";
        try {
            if (!DownloadPolicy.quotaAllows(0, request.expectedBytes > 0 ? request.expectedBytes : budget,
                    budget, usableSpace(dir)))
                return DownloadUseCase.Result.fail("下载超出本地配额或空间不足");
            url = CatalogClient.checkedUrl(request.url);

            // 读取本地断点元数据；仅当 URL 一致且标识可验证时才考虑续传
            Breakpoint bp = readBreakpoint(meta);
            long localBytes = staged.isFile() ? staged.length() : 0;
            boolean canTryResume = bp != null && url.equals(bp.url)
                    && DownloadPolicy.resumeEligible(localBytes, budget, bp.etag, bp.lastModified);
            if (!canTryResume && staged.exists()) { staged.delete(); localBytes = 0; deleteBreakpoint(meta); }

            HttpURLConnection connection = null;
            if (bp != null) { lastEtag = bp.etag == null ? "" : bp.etag; lastModified = bp.lastModified == null ? "" : bp.lastModified; }
            try {
                connection = openConnection(url, canTryResume ? localBytes : 0);
                long declared = connection.getContentLengthLong();
                long limit = request.expectedBytes > 0 ? request.expectedBytes : budget;

                // Range 判定：206 且起点匹配 + 资源标识一致才续；否则丢弃 part 从头下
                DownloadPolicy.RangeDecision decision = canTryResume
                        ? DownloadPolicy.rangeDecision(connection.getResponseCode(),
                                connection.getHeaderField("Content-Range"), localBytes, declared)
                        : DownloadPolicy.noRange("无本地断点");
                boolean resume = decision.resume && DownloadPolicy.identityMatches(
                        bp.etag, connection.getHeaderField("ETag"),
                        bp.lastModified, connection.getHeaderField("Last-Modified"));
                if (!resume && canTryResume) {
                    connection.disconnect(); connection = null;
                    staged.delete(); localBytes = 0; deleteBreakpoint(meta);
                    connection = openConnection(url, 0);
                    declared = connection.getContentLengthLong();
                }
                if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300)
                    return DownloadUseCase.Result.fail("HTTP " + connection.getResponseCode());
                if (declared > limit && !resume) return DownloadUseCase.Result.fail("资源超过大小上限");

                long startAt = resume ? localBytes : 0;
                // 校验预算：续传时本地+本次不能超过上限
                if (resume && declared != DownloadPolicy.UNKNOWN_LENGTH && startAt + declared > limit)
                    return DownloadUseCase.Result.fail("续传后总长度超过上限");

                String responseEtag = connection.getHeaderField("ETag");
                String responseLast = connection.getHeaderField("Last-Modified");
                if (responseEtag != null && !responseEtag.trim().isEmpty()) lastEtag = responseEtag;
                if (responseLast != null && !responseLast.trim().isEmpty()) lastModified = responseLast;
                long written = writeBody(connection, staged, startAt, limit - startAt);
                // 本次响应体写入完整：清掉断点元数据，避免完整 part 被误判为续传起点
                deleteBreakpoint(meta);

                // 完整文件重新计算哈希（续传时对全部字节摘要，不信任分段的旧摘要）
                String sha = hashFile(staged);
                if (written == 0 && startAt == 0) return DownloadUseCase.Result.fail("下载内容为空");
                long total = startAt + written;
                if (request.expectedBytes > 0 && total != request.expectedBytes)
                    return DownloadUseCase.Result.fail("下载长度与预期不符");
                if (request.expectedSha256 != null && !request.expectedSha256.isEmpty()
                        && !request.expectedSha256.equalsIgnoreCase(sha))
                    return DownloadUseCase.Result.fail("下载内容校验失败");
                if (!staged.renameTo(target)) return DownloadUseCase.Result.fail("提交下载文件失败");
                deleteBreakpoint(meta);
                return DownloadUseCase.Result.ok(target.getAbsolutePath(), sha);
            } finally {
                if (connection != null) connection.disconnect();
            }
        } catch (Exception error) {
            // 网络中断时保留 .part + 元数据供下次续传；无响应标识则删除 part（不可验证，明确重下）
            if (staged.isFile() && staged.length() > 0
                    && (!lastEtag.trim().isEmpty() || !lastModified.trim().isEmpty()))
                saveBreakpoint(meta, url, lastEtag, lastModified);
            else { staged.delete(); deleteBreakpoint(meta); }
            return DownloadUseCase.Result.fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    /** 打开连接（含重定向），可选 Range 头。 */
    private HttpURLConnection openConnection(String startUrl, long from) throws Exception {
        String url = startUrl;
        HttpURLConnection connection = null;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "OpenLeaf/1.0 DownloadAdapter");
            if (from > 0) connection.setRequestProperty("Range", "bytes=" + from + "-");
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String next = connection.getHeaderField("Location");
                connection.disconnect(); connection = null;
                if (next == null) throw new IOException("重定向缺少地址");
                url = CatalogClient.checkedUrl(new URI(url).resolve(next).toString());
                continue;
            }
            break;
        }
        if (connection == null) throw new IOException("重定向次数过多");
        return connection;
    }

    /** 写入响应体；startAt>0 时以 raf 追加模式续写。返回本次写入字节数。 */
    private long writeBody(HttpURLConnection connection, File staged, long startAt, long budget) throws IOException {
        long written = 0;
        try (InputStream in = connection.getInputStream()) {
            if (startAt > 0) {
                try (RandomAccessFile raf = new RandomAccessFile(staged, "rw")) {
                    raf.seek(startAt);
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        written += n;
                        if (written > budget) throw new IOException("下载超过大小上限");
                        raf.write(buffer, 0, n);
                    }
                }
            } else {
                try (FileOutputStream out = new FileOutputStream(staged)) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        written += n;
                        if (written > budget) throw new IOException("下载超过大小上限");
                        out.write(buffer, 0, n);
                    }
                    out.flush();
                }
            }
        }
        return written;
    }

    /** 对整份文件计算 SHA-256。 */
    private static String hashFile(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return ContentContracts.hashHex(in);
        }
    }

    // ---------- 断点元数据 sidecar（纯文本 Properties，避免引入依赖） ----------
    private static final class Breakpoint {
        final String url, etag, lastModified;
        Breakpoint(String url, String etag, String lastModified) {
            this.url = url; this.etag = etag; this.lastModified = lastModified;
        }
    }

    private Breakpoint readBreakpoint(File meta) {
        if (!meta.isFile()) return null;
        try (InputStream in = new java.io.FileInputStream(meta)) {
            Properties p = new Properties();
            p.load(in);
            return new Breakpoint(p.getProperty("url", ""), p.getProperty("etag", ""), p.getProperty("lastModified", ""));
        } catch (Exception ignored) { return null; }
    }

    /** 在失败前保存断点元数据（由 writeBody 之后的成功路径之外调用；此处在 fetch 末尾钩子中处理）。 */
    private void saveBreakpoint(File meta, String url, String etag, String lastModified) {
        try (FileOutputStream out = new FileOutputStream(meta)) {
            Properties p = new Properties();
            p.setProperty("url", url == null ? "" : url);
            p.setProperty("etag", etag == null ? "" : etag);
            p.setProperty("lastModified", lastModified == null ? "" : lastModified);
            p.store(out, "OpenLeaf download breakpoint");
        } catch (Exception ignored) { }
    }

    private void deleteBreakpoint(File meta) { if (meta.exists()) meta.delete(); }

    private static long usableSpace(File dir) {
        try { return dir.getUsableSpace(); }
        catch (RuntimeException unknown) { return DownloadPolicy.UNKNOWN_LENGTH; }
    }
}
