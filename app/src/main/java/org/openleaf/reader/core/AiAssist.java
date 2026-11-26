package org.openleaf.reader.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 阶段 3-A AI 云端旁路（T1=A'）的纯 JVM 逻辑：输入裁剪、请求/响应封装、结果清洗与降级判定。
 *
 * <p>网络 IO 由 Android 层用 HttpURLConnection 完成；本类不碰网络、不碰 Android API，可单测。
 * 隐私口径：只传"单章"文本（规则式已提取的首句/开头），不传整本；输出可被用户关闭。
 * 安全口径：服务端返回的文本必须经 {@link #sanitize(String)} 去除控制字符与超长截断。</p>
 */
public final class AiAssist {
    private AiAssist() { }

    /** 单次请求最多发送的字符数（按码点），远小于整本书。 */
    public static final int MAX_INPUT_CHARS = 2000;
    /** 单次返回最多接受的字符数。 */
    public static final int MAX_OUTPUT_CHARS = 2000;
    /** 超时上限（毫秒），Android 层据此设置 connect/read timeout。 */
    public static final int TIMEOUT_MS = 15000;

    /** 默认指令：只做摘要，不改写原文。 */
    public static final String DEFAULT_PROMPT = "请用中文简洁总结以下内容，不超过150字，不要编造原文没有的信息：\n";

    /**
     * 把单章开头裁到 MAX_INPUT_CHARS 码点，绝不切开代理对；
     * null/空返回空串（调用方应跳过该章，不发起请求）。
     */
    public static String clipInput(String chapterHead) {
        if (chapterHead == null || chapterHead.isEmpty()) return "";
        String s = chapterHead.trim();
        if (s.isEmpty()) return "";
        int count = s.codePointCount(0, s.length());
        if (count <= MAX_INPUT_CHARS) return s;
        return s.substring(0, s.offsetByCodePoints(0, MAX_INPUT_CHARS));
    }

    /** 组装完整 prompt；输入为空返回 null（不发送空请求）。 */
    public static String buildPrompt(String chapterHead) {
        String clipped = clipInput(chapterHead);
        return clipped.isEmpty() ? null : DEFAULT_PROMPT + clipped;
    }

    /**
     * 清洗模型输出：去掉控制字符（保留换行）、压缩连续空行、按码点截断到 MAX_OUTPUT_CHARS。
     * 空/全空白返回 null（调用方降级到规则式结果）。
     */
    public static String sanitize(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder();
        boolean lastNewline = false;
        for (int i = 0; i < raw.length();) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\r') {
                if (!lastNewline && sb.length() > 0) { sb.append('\n'); lastNewline = true; }
            } else if (cp >= 0x20 || cp >= 0x4e00 && cp <= 0x9fff) {
                sb.appendCodePoint(cp);
                lastNewline = false;
            }
        }
        String out = sb.toString().trim();
        if (out.isEmpty()) return null;
        if (out.codePointCount(0, out.length()) > MAX_OUTPUT_CHARS)
            out = out.substring(0, out.offsetByCodePoints(0, MAX_OUTPUT_CHARS));
        return out;
    }

    /** 从简化的 OpenAI 兼容 JSON 响应中提取 content；不依赖 org.json（纯 JVM 可测）。 */
    public static String parseContent(String json) {
        if (json == null || json.isEmpty()) return null;
        int idx = json.indexOf("\"content\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + 9);
        if (colon < 0) return null;
        int q = json.indexOf('"', colon + 1);
        if (q < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = q + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(n);
                }
            } else sb.append(c);
        }
        return sanitize(sb.toString());
    }

    /** 是否应发起请求：默认关、无 key、无输入 任一成立则不发。 */
    public static boolean shouldRequest(boolean enabled, String apiKey, String chapterHead) {
        if (!enabled) return false;
        if (apiKey == null || apiKey.trim().isEmpty()) return false;
        return clipInput(chapterHead).length() > 0;
    }

    /** 降级结果：AI 失败时用规则式首句，保持输出结构一致。 */
    public static String fallback(String ruleBasedLead) {
        return sanitize(ruleBasedLead);
    }

    /** 隐私告知文案（UI 必须原样展示，不能含糊）。 */
    public static final String PRIVACY_NOTICE =
            "此功能会把你选中的单章文本发送到你配置的第三方服务，需要联网并提供 API key。"
                    + "关闭后应用完全离线工作，AI 不参与任何流程。应用不会代付费用、不收集你的阅读内容。";

    /** 生成请求体（OpenAI chat/completions 兼容格式），用字符串拼接不引入 JSON 库。 */
    public static String requestBody(String prompt) {
        String safe = escapeJson(prompt == null ? "" : prompt);
        return "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"" + safe + "\"}],"
                + "\"max_tokens\":300,\"temperature\":0.3}";
    }

    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
}
