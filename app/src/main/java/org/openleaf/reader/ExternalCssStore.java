package org.openleaf.reader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.TextView;

import org.openleaf.reader.core.BookParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部 CSS 的导入、SP 存储、合并与套用（0.13.0 缺口 1）。
 *
 * 清洗统一走 BookParser.parseCss()/safeCss() 白名单；不进 DB、不写 book_metadata、不请求网络。
 * 本类只提供数据与套用能力，菜单入口由 T03 接线。
 */
final class ExternalCssStore {
    static final String SP = "reader";
    static final String KEY_TEXT = "cssExternalText";            // 清洗后的 CSS 文本，硬上限 64 KiB
    static final String KEY_NAME = "cssExternalName";            // 显示名
    static final String KEY_SCOPE = "cssExternalScope";          // "global" | "book"
    static final String KEY_ENABLED = "cssExternalEnabled";      // boolean
    static final String KEY_BOOK_PREFIX = "cssExternal_book_";   // + bookId，单书覆盖文本

    static final int MAX_EXTERNAL_CSS_BYTES = 256 * 1024;
    static final int MAX_EXTERNAL_CSS_TEXT = 64 * 1024;
    static final int REQUEST_CSS_IMPORT = 4101;
    static final String SCOPE_GLOBAL = "global";
    static final String SCOPE_BOOK = "book";

    // 进程内缓存：单一不可变快照整体替换，避免 text 与 rules 两步赋值被并发交错错配
    private static volatile RulesSnapshot cachedGlobal;
    private static volatile BookRulesSnapshot cachedBook;
    private static String lastStatus;
    private static int lastIgnored;

    private static final class RulesSnapshot {
        final String text;
        final Map<String, String> rules;
        RulesSnapshot(String text, Map<String, String> rules) { this.text = text; this.rules = rules; }
    }
    private static final class BookRulesSnapshot {
        final String bookId, text;
        final Map<String, String> rules;
        BookRulesSnapshot(String bookId, String text, Map<String, String> rules) {
            this.bookId = bookId; this.text = text; this.rules = rules;
        }
    }

    private ExternalCssStore() { }

    static int requestCode() { return REQUEST_CSS_IMPORT; }

    static void launchImport(Activity a) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/css");
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivityForResult(intent, REQUEST_CSS_IMPORT);
        } catch (android.content.ActivityNotFoundException noPicker) {
            // 部分设备不声明 text/css：回退到任意可读文件，由导入链路自行判定内容
            intent.setType("*/*");
            a.startActivityForResult(intent, REQUEST_CSS_IMPORT);
        }
    }

    /** 消费返回 true（含失败/取消）；结果文案由 lastStatus() 读取。 */
    static boolean handleResult(Context c, int req, int res, Intent data) {
        if (req != REQUEST_CSS_IMPORT) return false;
        lastStatus = null;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) {
            lastStatus = "已取消";
            return true;
        }
        Uri uri = data.getData();
        byte[] bytes;
        try {
            bytes = readBounded(c, uri);
        } catch (IOException tooLarge) {
            lastStatus = "样式文件过大（上限 256 KiB），未导入";
            return true;
        } catch (SecurityException denied) {
            lastStatus = "读取样式文件失败：文件访问授权已失效";
            return true;
        } catch (RuntimeException failed) {
            lastStatus = "读取样式文件失败：" + reason(failed);
            return true;
        }
        String text;
        try {
            text = decodeStrictUtf8(bytes);
        } catch (CharacterCodingException invalid) {
            lastStatus = "读取样式文件失败：不是有效的 UTF-8 文本";
            return true;
        }
        Map<String, String> rules = BookParser.parseCss(text);
        if (rules.isEmpty()) {
            lastStatus = "文件里没有可用的安全样式声明（已忽略 " + countDeclarations(text) + " 条）";
            return true; // 保留原状态，不写 SP
        }
        String serialized = serialize(rules);
        if (serialized.length() > MAX_EXTERNAL_CSS_TEXT) {
            lastStatus = "清洗后的样式超过 " + (MAX_EXTERNAL_CSS_TEXT / 1024) + " KiB，未导入";
            return true;
        }
        String scope = scope(c);
        String bookId = "book".equals(scope) ? bookIdOf(c) : null;
        String name = displayName(c, uri);
        SharedPreferences.Editor editor = prefs(c).edit().putString(KEY_NAME, name);
        if (bookId != null && !bookId.isEmpty()) editor.putString(KEY_BOOK_PREFIX + bookId, serialized);
        else editor.putString(KEY_TEXT, serialized);
        editor.putBoolean(KEY_ENABLED, true).apply();
        cachedGlobal = null; cachedBook = null;
        lastStatus = "已导入：" + name + " · " + rules.size() + " 条安全规则 · 作用范围："
                + (bookId != null && !bookId.isEmpty() ? "本书" : "全局");
        return true;
    }

    /** 最近一次导入的三态文案；未导入过返回 null。 */
    static String lastStatus() { return lastStatus; }

    static boolean enabled(Context c) { return prefs(c).getBoolean(KEY_ENABLED, false); }

    static void setEnabled(Context c, boolean value) { prefs(c).edit().putBoolean(KEY_ENABLED, value).apply(); }

    static String scope(Context c) {
        String value = prefs(c).getString(KEY_SCOPE, SCOPE_GLOBAL);
        return SCOPE_BOOK.equals(value) ? SCOPE_BOOK : SCOPE_GLOBAL;
    }

    /** 切换范围只移动文本，不做隐式合并。 */
    static void setScope(Context c, String value) {
        String next = SCOPE_BOOK.equals(value) ? SCOPE_BOOK : SCOPE_GLOBAL;
        String bookId = bookIdOf(c);
        SharedPreferences.Editor editor = prefs(c).edit().putString(KEY_SCOPE, next);
        if (SCOPE_BOOK.equals(next)) {
            if (bookId != null && !bookId.isEmpty()) {
                String global = prefs(c).getString(KEY_TEXT, "");
                if (!global.isEmpty()) editor.putString(KEY_BOOK_PREFIX + bookId, global).remove(KEY_TEXT);
            }
        } else if (bookId != null && !bookId.isEmpty()) {
            String book = prefs(c).getString(KEY_BOOK_PREFIX + bookId, "");
            if (!book.isEmpty()) editor.putString(KEY_TEXT, book).remove(KEY_BOOK_PREFIX + bookId);
        }
        editor.apply();
        cachedGlobal = null; cachedBook = null;
    }

    static String name(Context c) { return prefs(c).getString(KEY_NAME, ""); }

    /** 清除当前范围的文本；scope=book 且无 bookId 时不改动全局样式。 */
    static void clear(Context c) {
        String bookId = bookIdOf(c);
        SharedPreferences.Editor editor = prefs(c).edit().remove(KEY_NAME).putBoolean(KEY_ENABLED, false);
        if (SCOPE_BOOK.equals(scope(c))) {
            if (bookId != null && !bookId.isEmpty()) editor.remove(KEY_BOOK_PREFIX + bookId);
        } else editor.remove(KEY_TEXT);
        editor.apply();
        cachedGlobal = null; cachedBook = null;
        lastStatus = "未导入外部样式（当前使用原书样式 / 应用统一样式）";
    }

    /** 全局 + 单书合并；单书同名 selector 覆盖全局。enabled=false 时仍返回规则（规则保留，可随时开启）。 */
    static Map<String, String> rulesFor(Context c, String bookId) {
        Map<String, String> result = new LinkedHashMap<String, String>(globalRules(c));
        if (bookId == null || bookId.isEmpty()) return result;
        Map<String, String> book = bookRules(c, bookId);
        result.putAll(book); // 单书覆盖全局，与设计 §1.2 一致
        return result;
    }

    /**
     * TXT 离散档位套用：font-size / line-height / letter-spacing / text-align / color / background-color。
     * 不写 theme 等 SP；不支持的声明只计数，由 lastIgnored() 供 T03 提示。
     */
    static void applyToReader(Activity a, TextView text, int baseFontSize) {
        lastIgnored = 0;
        if (text == null) return;
        String combined = "";
        for (Map.Entry<String, String> entry : rulesFor(a, bookIdOf(a)).entrySet())
            combined += entry.getValue();
        int size = baseFontSize;
        float lineHeight = 0f, letterSpacing = 0f;
        Integer align = null, foreground = null, background = null;
        for (String declaration : combined.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon < 0) continue;
            String key = declaration.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            String value = declaration.substring(colon + 1).trim().toLowerCase(java.util.Locale.ROOT);
            try {
                if ("font-size".equals(key) && value.endsWith("px")) {
                    float px = Float.parseFloat(value.substring(0, value.length() - 2));
                    float scale = a.getResources().getDisplayMetrics().scaledDensity;
                    size = Math.max(14, Math.min(40, Math.round(px / scale)));
                } else if ("line-height".equals(key) && !"normal".equals(value)) {
                    lineHeight = Float.parseFloat(value);
                } else if ("letter-spacing".equals(key) && value.endsWith("px")) {
                    // 白名单只允许 px；换算成 em 才能映射 ReaderStyle 的档位
                    letterSpacing = Float.parseFloat(value.substring(0, value.length() - 2)) / Math.max(1, baseFontSize);
                } else if ("text-align".equals(key)) {
                    if ("left".equals(value) || "start".equals(value)) align = android.view.Gravity.LEFT;
                    else if ("right".equals(value) || "end".equals(value)) align = android.view.Gravity.RIGHT;
                    else if ("center".equals(value)) align = android.view.Gravity.CENTER_HORIZONTAL;
                    else if ("justify".equals(value)) align = android.view.Gravity.FILL_HORIZONTAL;
                    else lastIgnored++;
                } else if ("color".equals(key)) {
                    foreground = parseColor(value);
                } else if ("background-color".equals(key)) {
                    background = "transparent".equals(value) ? 0 : parseColor(value);
                } else lastIgnored++;
            } catch (RuntimeException invalid) {
                lastIgnored++;
            }
        }
        text.setTextSize(size);
        if (lineHeight > 0f) {
            int index = nearestIndex(lineHeight, ReaderStyle.LINE_SPACING);
            text.setLineSpacing(Ui.dp(a, ReaderStyle.get(a).paraSpacingSp()), ReaderStyle.LINE_SPACING[index]);
        }
        if (letterSpacing > 0f) text.setLetterSpacing(nearest(letterSpacing, ReaderStyle.LETTER_SPACING));
        if (align != null) text.setGravity(align);
        if (foreground != null) text.setTextColor(foreground);
        if (background != null) text.setBackgroundColor(background);
    }

    /** 最近一次 applyToReader() 忽略的声明数。 */
    static int lastIgnored() { return lastIgnored; }

    // ---------- 内部工具 ----------

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(SP, 0); }

    private static String bookIdOf(Context c) {
        return c instanceof Activity ? ((Activity) c).getIntent().getStringExtra("bookId") : null;
    }

    private static byte[] readBounded(Context c, Uri uri) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法打开样式文件");
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                if (out.size() + n > MAX_EXTERNAL_CSS_BYTES) throw new IOException("超过大小上限");
                out.write(buffer, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static String decodeStrictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static int countDeclarations(String css) {
        int count = 0;
        for (int i = 0; i < css.length(); i++) if (css.charAt(i) == ';') count++;
        return count;
    }

    private static String serialize(Map<String, String> rules) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : rules.entrySet())
            out.append(entry.getKey()).append('{').append(entry.getValue()).append('}');
        return out.toString();
    }

    private static Map<String, String> globalRules(Context c) {
        String text = prefs(c).getString(KEY_TEXT, "");
        if (text.isEmpty()) return java.util.Collections.<String, String>emptyMap();
        RulesSnapshot snapshot = cachedGlobal;
        if (snapshot == null || !text.equals(snapshot.text)) {
            snapshot = new RulesSnapshot(text, BookParser.parseCss(text));
            cachedGlobal = snapshot; // 单一可变引用原子替换，text 与 rules 永不错位
        }
        return snapshot.rules;
    }

    private static Map<String, String> bookRules(Context c, String bookId) {
        String text = prefs(c).getString(KEY_BOOK_PREFIX + bookId, "");
        if (text.isEmpty()) return java.util.Collections.<String, String>emptyMap();
        BookRulesSnapshot snapshot = cachedBook;
        if (snapshot == null || !bookId.equals(snapshot.bookId) || !text.equals(snapshot.text)) {
            snapshot = new BookRulesSnapshot(bookId, text, BookParser.parseCss(text));
            cachedBook = snapshot;
        }
        return snapshot.rules;
    }

    private static float nearest(float value, float[] levels) {
        return levels[nearestIndex(value, levels)];
    }

    private static int nearestIndex(float value, float[] levels) {
        int best = 0;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < levels.length; i++) {
            float delta = Math.abs(value - levels[i]);
            if (delta < distance) { distance = delta; best = i; }
        }
        return best;
    }

    private static int nearest(float value, int[] levels) {
        int best = 0;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < levels.length; i++) {
            float delta = Math.abs(value - levels[i]);
            if (delta < distance) { distance = delta; best = i; }
        }
        return levels[best];
    }

    private static String displayName(Context c, Uri uri) {
        try (android.database.Cursor cursor = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (RuntimeException ignored) { /* 回退到 URI 末段 */ }
        String last = uri.getLastPathSegment();
        return last == null ? "外部样式" : last;
    }

    private static int parseColor(String value) {
        if (value.startsWith("rgb(") && value.endsWith(")")) {
            String[] parts = value.substring(4, value.length() - 1).split(",");
            if (parts.length == 3)
                return android.graphics.Color.rgb(Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        }
        // currentcolor / inherit / initial 没有确定值：按不支持处理
        return android.graphics.Color.parseColor(value);
    }

    private static String reason(Exception error) {
        if (error instanceof SecurityException) return "文件访问授权已失效";
        if (error instanceof java.io.FileNotFoundException) return "文件访问授权已失效";
        return "未知错误（" + error.getClass().getSimpleName() + "）";
    }
}
