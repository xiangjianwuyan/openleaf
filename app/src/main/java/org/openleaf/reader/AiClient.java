package org.openleaf.reader;

import android.content.Context;
import android.content.SharedPreferences;

import org.openleaf.reader.core.AiAssist;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 阶段 3-A（T1=A'）：AI 云端旁路的 Android 层 HTTP 客户端。
 *
 * <p>隐私：API key 只存独立 prefs 文件 {@code ai_settings}（**不在 LibraryBackup 的 reader_prefs 白名单内**，
 * 不进备份、不写日志）；请求只在用户显式开启且有 key 时发出；单章裁剪文本，不传整本。
 * 网络用 JDK HttpURLConnection（零新依赖），超时由 core 常量约束。</p>
 */
final class AiClient {
    private static final String PREFS = "ai_settings";
    private static final String KEY_ENABLED = "ai_enabled";
    private static final String KEY_API_KEY = "ai_api_key";
    private static final String KEY_ENDPOINT = "ai_endpoint";
    /** 默认端点不含任何 key；用户可在设置里改为自建/兼容端点。 */
    static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private AiClient() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) { return prefs(c).getBoolean(KEY_ENABLED, false); }
    static String apiKey(Context c) { return prefs(c).getString(KEY_API_KEY, ""); }
    static String endpoint(Context c) { return prefs(c).getString(KEY_ENDPOINT, DEFAULT_ENDPOINT); }

    static void save(Context c, boolean enabled, String key, String endpoint) {
        SharedPreferences.Editor e = prefs(c).edit();
        e.putBoolean(KEY_ENABLED, enabled);
        e.putString(KEY_API_KEY, key == null ? "" : key.trim());
        e.putString(KEY_ENDPOINT, endpoint == null || endpoint.trim().isEmpty() ? DEFAULT_ENDPOINT : endpoint.trim());
        e.apply();
    }

    /** 清除 key（用户可一键撤销），不写日志。 */
    static void clearKey(Context c) { prefs(c).edit().putString(KEY_API_KEY, "").apply(); }

    /**
     * 请求单章摘要。返回清洗后的摘要；失败/无 key/无网时返回 null（调用方降级到规则式结果）。
     * 不抛异常到 UI 线程。
     */
    static String summarize(Context c, String chapterHead) {
        if (!AiAssist.shouldRequest(enabled(c), apiKey(c), chapterHead)) return null;
        String prompt = AiAssist.buildPrompt(chapterHead);
        if (prompt == null) return null;
        HttpURLConnection conn = null;
        try {
            // checkedUrl 强制 https、拒绝 file/javascript 等危险协议；非法 endpoint 直接不发请求
            String url = CatalogClient.checkedUrl(endpoint(c));
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(AiAssist.TIMEOUT_MS);
            conn.setReadTimeout(AiAssist.TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey(c));
            byte[] body = AiAssist.requestBody(prompt).getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(body); }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n, total = 0;
                while ((n = in.read(b)) > 0) {
                    total += n;
                    if (total > 256 * 1024) return null; // 响应体上限，防超大返回
                    buf.write(b, 0, n);
                }
                return AiAssist.parseContent(new String(buf.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            return null; // 无网/超时/解析失败 → 降级，不打断阅读
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
