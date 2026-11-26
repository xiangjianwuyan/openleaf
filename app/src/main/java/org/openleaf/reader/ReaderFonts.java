package org.openleaf.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.widget.TextView;
import android.widget.Toast;

import org.openleaf.reader.core.FontLibrary;
import org.openleaf.reader.core.FontLibrary.AddResult;
import org.openleaf.reader.core.FontLibrary.Entry;
import org.openleaf.reader.core.FontResolver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TXT/MD 正文自定义字体管理（0.27.0 P0-1）。
 *
 * <p>字体仅来自用户 SAF 选择的 TTF/OTF：复制到 {@code filesDir/fonts/} 私有目录，
 * prefs 只保存文件名与展示名，不保存 Uri / 绝对路径（抗目录迁移与授权过期）。
 * 三态：跟随系统（不干预 TextView）、内置默认（显式 Typeface.DEFAULT）、用户字体。
 * 字体文件损坏或读取异常一律降级为系统默认字体并提示，绝不崩溃。
 *
 * <p>本组件只作用于 {@code TextView} 正文，不覆盖 EPUB/WebView。
 */
final class ReaderFonts {
    static final int MODE_SYSTEM = 0;
    static final int MODE_BUILTIN = 1;
    static final int MODE_USER = 2;

    private static final String PREFS_NAME = "reader";
    private static final String KEY_MODE = "font_mode";
    private static final String KEY_FILE = "font_user_file";
    private static final String KEY_DISPLAY_NAME = "font_user_name";
    private static final String KEY_VALID = "font_user_valid";
    // 0.29.0 P2：font_library 键「存在即新世界」（值可以为空，永不删除，避免回到 G0 后旧字体复活）。
    private static final String KEY_LIBRARY = "font_library";
    private static final String KEY_GLOBAL_ID = "font_global_id";
    private static final String FONT_DIRECTORY = "fonts";
    private static final String FILE_TTF = "user-font.ttf";
    private static final String FILE_OTF = "user-font.otf";
    private static final int BUFFER_SIZE = 16384;
    private static final long MAX_FONT_BYTES = 16L * 1024 * 1024;

    private final Context context;
    private final SharedPreferences prefs;
    private boolean degradationNotified;

    ReaderFonts(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, 0);
    }

    int mode() { return clamp(prefs.getInt(KEY_MODE, MODE_SYSTEM)); }

    void saveMode(int mode) {
        prefs.edit().putInt(KEY_MODE, clamp(mode)).apply();
    }

    String userFileName() {
        String value = prefs.getString(KEY_FILE, "");
        return value == null ? "" : value;
    }

    String userDisplayName() {
        String value = prefs.getString(KEY_DISPLAY_NAME, "");
        return value == null || value.isEmpty() ? "用户字体" : value;
    }

    boolean userFileValid() { return prefs.getBoolean(KEY_VALID, true); }

    // ------------------------------------------------------------------
    // 0.29.0 P2 多字体收藏：G0 闸门 / 收藏读写 / 安装 / 删除 / 迁移。
    // 这里所有新键只在用户主动操作字体时写，绝不在构造或渲染路径触碰。
    // ------------------------------------------------------------------

    /** G0 闸门：只判键存在性，不读值。值为 "" 也属于新世界。 */
    boolean libraryInitialized() {
        return prefs.contains(KEY_LIBRARY);
    }

    /** 读取收藏列表；反序列化永不抛异常，返回不可修改列表。 */
    List<Entry> library() {
        return FontLibrary.deserialize(prefs.getString(KEY_LIBRARY, null));
    }

    /** 一次 editor 原子写整键；调用方负责保证键写入后永不删除。 */
    void saveLibrary(List<Entry> entries) {
        prefs.edit().putString(KEY_LIBRARY, FontLibrary.serialize(entries)).apply();
    }

    /** 全局默认字体 id；absent 与空串同义。 */
    String globalFontId() {
        String value = prefs.getString(KEY_GLOBAL_ID, "");
        return value == null ? "" : value;
    }

    /** 当前书的按书字体 id；absent、null 与空串同义。 */
    String bookFontId(String bookId) {
        if (bookId == null || bookId.isEmpty()) return "";
        String value = prefs.getString(FontResolver.bookKeyName(bookId), "");
        return value == null ? "" : value;
    }

    void setBookFont(String bookId, String fontId) {
        if (bookId == null || bookId.isEmpty()) return;
        String value = fontId == null ? "" : fontId;
        if (!value.isEmpty() && !FontLibrary.isValidId(value)) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (value.isEmpty()) editor.remove(FontResolver.bookKeyName(bookId));
        else editor.putString(FontResolver.bookKeyName(bookId), value);
        editor.apply();
    }

    void clearBookFont(String bookId) {
        setBookFont(bookId, "");
    }

    /** legacy 旧文件当前是否真的可用（旧键非空 + 对应文件存在且非空）。 */
    boolean legacyAvailable() {
        String stored = userFileName();
        if (stored.isEmpty() || FontLibrary.extFromFileName(stored).isEmpty()) return false;
        File file = fileForId(FontLibrary.LEGACY_ID);
        return file != null && file.isFile() && file.length() > 0L;
    }

    /** 由旧三键构造 legacy 条目；ext 非白名单时返回 null，调用方跳过迁移。 */
    private Entry legacyEntryForMigration() {
        String stored = userFileName();
        if (stored.isEmpty() || FontLibrary.extFromFileName(stored).isEmpty()) return null;
        return FontLibrary.legacyEntry(stored, userDisplayName(), userFileValid());
    }

    /**
     * 惰性迁移：仅在用户主动进入字体管理 / 添加 / 按书选择时调用。
     * 不移动、不改名、不删除物理文件，旧三键与 font_mode 逐字保留。
     */
    void adoptLegacyIfNeeded() {
        if (libraryInitialized()) return;
        if (!FontLibrary.migrationDecision(null, userFileName())) return;
        Entry legacy = legacyEntryForMigration();
        if (legacy == null) return;
        AddResult adopted = FontLibrary.add(library(), legacy);
        if (!adopted.added) return;
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_LIBRARY, FontLibrary.serialize(adopted.library));
        if (mode() == MODE_USER && globalFontId().isEmpty()) {
            editor.putString(KEY_GLOBAL_ID, FontLibrary.LEGACY_ID);
        }
        editor.apply();
    }

    /**
     * 收藏新字体：白名单 → 流式复制并算摘要 → 去重/上限 → 落位 → 一次写 prefs。
     * 任何失败都删临时文件、零配置改动，只把原因交给 UI 提示。
     */
    AddResult installFont(Uri uri, String displayName) {
        if (uri == null) return new AddResult(library(), false, FontLibrary.Reason.INVALID);
        String name = displayName;
        if (name == null || name.trim().isEmpty()) {
            String last = uri.getLastPathSegment();
            name = last == null ? "" : last;
        }
        String extension = extensionOf(name);
        if (extension == null) return new AddResult(library(), false, FontLibrary.Reason.INVALID);
        File directory = fontDirectory();
        if (directory == null) return new AddResult(library(), false, FontLibrary.Reason.INVALID);

        File temporary = new File(directory,
                "font-install-" + System.currentTimeMillis() + "-" + System.nanoTime() + ".tmp");
        CopyResult copied;
        try {
            copied = copyFontWithDigest(uri, temporary);
        } catch (NotAFontException notFont) {
            temporary.delete();
            return new AddResult(library(), false, FontLibrary.Reason.NOT_FONT);
        } catch (IOException failure) {
            temporary.delete();
            return new AddResult(library(), false, FontLibrary.Reason.INVALID);
        }
        String id;
        try {
            id = FontLibrary.stableId(copied.digest);
        } catch (IllegalArgumentException impossible) {
            temporary.delete();
            return new AddResult(library(), false, FontLibrary.Reason.INVALID);
        }

        // 成功路径才把 legacy 在内存里并入待写库：安装失败时绝不写 font_library。
        boolean firstFont = !libraryInitialized();
        List<Entry> base = library();
        if (firstFont && FontLibrary.migrationDecision(null, userFileName())) {
            Entry legacy = legacyEntryForMigration();
            if (legacy != null) {
                AddResult adopted = FontLibrary.add(base, legacy);
                if (adopted.added) base = adopted.library;
            }
        }
        Entry candidate = new Entry(id, FontLibrary.normalizeDisplayName(name),
                extension.substring(1), true);
        AddResult result = FontLibrary.add(base, candidate);
        if (!result.added) {
            temporary.delete();
            return result; // DUPLICATE / LIMIT_REACHED：零配置改动。
        }
        File target = new File(directory, FontLibrary.fileNameOf(id, extension.substring(1)));
        if (!temporary.renameTo(target)) {
            temporary.delete();
            return new AddResult(library(), false, FontLibrary.Reason.INVALID);
        }
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_LIBRARY, FontLibrary.serialize(result.library));
        if (firstFont && base.isEmpty()) {
            // 沿用 0.27.0 pickAndInstallFont 的自动切态，仅库原本完全为空时生效。
            editor.putInt(KEY_MODE, MODE_USER).putString(KEY_GLOBAL_ID, id);
        }
        editor.apply();
        degradationNotified = false;
        return result;
    }

    /** 删除收藏：物理文件 → 列表 → 全局 id → 所有按书引用；仅显式删除 legacy 才清旧三键。 */
    boolean removeFont(String id) {
        if (id == null || id.isEmpty()) return false;
        List<Entry> current = library();
        boolean isLegacy = FontLibrary.LEGACY_ID.equals(id);
        if (FontLibrary.find(current, id) == null && !isLegacy) return false;

        File directory = fontDirectory();
        if (directory != null) {
            if (isLegacy) {
                File byKey = fileForId(FontLibrary.LEGACY_ID);
                if (byKey != null) byKey.delete();
                new File(directory, FILE_TTF).delete();
                new File(directory, FILE_OTF).delete();
            } else {
                File file = fileForId(id);
                if (file != null) file.delete();
            }
        }
        List<Entry> updated = FontLibrary.remove(current, id);
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_LIBRARY, FontLibrary.serialize(updated));
        if (id.equals(globalFontId())) editor.remove(KEY_GLOBAL_ID);
        // prefs.getAll() 是 Map<String, ?>：抽成纯字符串快照后再交给 core 的精确扫描。
        Map<String, String> stringPrefs = new LinkedHashMap<>();
        for (Map.Entry<String, ?> item : prefs.getAll().entrySet()) {
            if (item.getValue() instanceof String) stringPrefs.put(item.getKey(), (String) item.getValue());
        }
        for (String key : FontResolver.collectBookKeysReferencing(stringPrefs, id)) {
            editor.remove(key);
        }
        if (isLegacy) {
            editor.remove(KEY_FILE).remove(KEY_DISPLAY_NAME).remove(KEY_VALID);
            if (mode() == MODE_USER) editor.putInt(KEY_MODE, MODE_SYSTEM);
        }
        editor.apply();
        degradationNotified = false;
        return true;
    }

    /** 只改展示名，不动 id、文件名与物理字体。 */
    void renameFont(String id, String name) {
        List<Entry> current = library();
        List<Entry> updated = FontLibrary.rename(current, id, name);
        if (updated != current) saveLibrary(updated);
    }

    /** 设为全局默认并自动切 MODE_USER；校验失效 id 时回落空串。 */
    void setGlobalDefault(String id) {
        String resolved = FontLibrary.resolveGlobalId(globalFontId(), id, library());
        prefs.edit().putString(KEY_GLOBAL_ID, resolved).putInt(KEY_MODE, MODE_USER).apply();
        degradationNotified = false;
    }

    /** 正文渲染发现字体损坏时持久标记 valid=false；G0 老路径不受影响。 */
    void markLibraryEntryInvalid(String id) {
        if (!libraryInitialized() || id == null) return;
        List<Entry> current = library();
        List<Entry> updated = FontLibrary.markInvalid(current, id);
        if (updated != current) saveLibrary(updated);
    }

    /**
     * id → 物理文件。legacy 永远读旧键并只取文件名（沿用 :150 防穿越），
     * 绝不生成 font_legacy.ttf；其他 id 走 font_&lt;id&gt;.&lt;ext&gt;。
     */
    File fileForId(String id) {
        if (id == null || id.isEmpty()) return null;
        File directory = fontDirectory();
        if (directory == null) return null;
        if (FontLibrary.LEGACY_ID.equals(id)) {
            String stored = userFileName();
            if (stored.isEmpty()) return null;
            return new File(directory, new File(stored).getName());
        }
        if (!FontLibrary.isValidId(id)) return null;
        Entry entry = FontLibrary.find(library(), id);
        if (entry != null) {
            if (!"ttf".equals(entry.ext) && !"otf".equals(entry.ext)) return null;
            return new File(directory, FontLibrary.fileNameOf(id, entry.ext));
        }
        for (String ext : new String[]{"ttf", "otf"}) {
            File candidate = new File(directory, FontLibrary.fileNameOf(id, ext));
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    /**
     * 供正文与独立预览 TextView 共用的 Typeface 加载；失败返回 null，由调用方降级。
     *
     * <p>D3：{@code Typeface.createFromFile} 对垃圾内容可能不抛异常而是返回降级 Typeface，
     * 所以必须先做文件头魔数校验；头不合法直接返回 null，让预览灰化与正文降级分支生效。
     */
    Typeface createTypeface(String id) {
        File file = fileForId(id);
        if (file == null || !file.isFile() || file.length() == 0L) return null;
        if (!hasFontHeader(file)) return null;
        try {
            return Typeface.createFromFile(file);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /** 读文件前 4 字节做魔数校验；读失败或不足 4 字节视为非法。 */
    private static boolean hasFontHeader(File file) {
        if (file == null || !file.isFile() || file.length() < 4L) return false;
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[4];
            int read = 0;
            while (read < 4) {
                int chunk = in.read(head, read, 4 - read);
                if (chunk < 0) break;
                read += chunk;
            }
            return read == 4 && FontLibrary.looksLikeFont(head);
        } catch (IOException failure) {
            return false;
        }
    }

    /** 一次性降级提示，语义与老路径 degradationNotified 一致（每实例一次）。 */
    void notifyFontDegradation(String message) {
        if (degradationNotified) return;
        degradationNotified = true;
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    /** 安装失败的确定中文文案，供 onActivityResult 直接展示。 */
    static String describeAddFailure(AddResult result) {
        if (result != null && result.reason == FontLibrary.Reason.DUPLICATE) return "该字体已收藏";
        if (result != null && result.reason == FontLibrary.Reason.LIMIT_REACHED) return "字体已达 20 个上限";
        if (result != null && result.reason == FontLibrary.Reason.NOT_FONT) {
            return "不是有效的 TTF / OTF 字体文件";
        }
        return "字体安装失败：仅支持 16 MiB 以内的 TTF / OTF 文件";
    }

    /** 三态的可展示文案，供阅读页字体选择对话框使用。 */
    static String describeMode(int mode) {
        switch (clamp(mode)) {
            case MODE_BUILTIN: return "内置默认（系统字体）";
            case MODE_USER: return "用户字体";
            default: return "跟随系统";
        }
    }

    /**
     * 从 SAF Uri 复制字体到私有目录并落库。校验扩展名白名单与 16 MiB 上限；
     * 复制成功后自动切到用户字体态。失败时不修改已有配置。
     */
    boolean pickAndInstallFont(Uri uri, String displayName) {
        if (uri == null) return false;
        String name = displayName;
        if (name == null || name.trim().isEmpty()) {
            String last = uri.getLastPathSegment();
            name = last == null ? "" : last;
        }
        String extension = extensionOf(name);
        if (extension == null) return false;

        File directory = fontDirectory();
        if (directory == null) return false;
        File temporary = new File(directory, "user-font-" + System.currentTimeMillis() + ".tmp");
        File target = new File(directory, extension.equals(".otf") ? FILE_OTF : FILE_TTF);
        try {
            copyFont(uri, temporary);
            // 两个候选旧文件都清掉，避免切换 TTF/OTF 后残留过期字体。
            new File(directory, FILE_TTF).delete();
            new File(directory, FILE_OTF).delete();
            if (!temporary.renameTo(target)) {
                temporary.delete();
                return false;
            }
        } catch (IOException failure) {
            temporary.delete();
            return false;
        }
        String safeName = name.length() > 120 ? name.substring(name.length() - 120) : name;
        prefs.edit()
                .putString(KEY_FILE, target.getName())
                .putString(KEY_DISPLAY_NAME, safeName)
                .putBoolean(KEY_VALID, true)
                .putInt(KEY_MODE, MODE_USER)
                .apply();
        degradationNotified = false;
        return true;
    }

    /** 清除已安装的用户字体；若当前正处于用户字体态则退回跟随系统。 */
    void clearUserFont() {
        File directory = fontDirectory();
        if (directory != null) {
            new File(directory, FILE_TTF).delete();
            new File(directory, FILE_OTF).delete();
        }
        SharedPreferences.Editor editor = prefs.edit().remove(KEY_FILE).remove(KEY_DISPLAY_NAME).remove(KEY_VALID);
        if (mode() == MODE_USER) editor.putInt(KEY_MODE, MODE_SYSTEM);
        editor.apply();
        degradationNotified = false;
    }

    /**
     * 按三态解析正文字体。返回 {@code null} 表示跟随系统、调用方不要触碰 setTypeface；
     * 其他情况返回可直接设置的 Typeface（损坏文件已降级为 Typeface.DEFAULT）。
     */
    Typeface resolveBodyTypeface() {
        int current = mode();
        if (current == MODE_BUILTIN) return Typeface.DEFAULT;
        if (current != MODE_USER) return null;

        String stored = userFileName();
        File directory = fontDirectory();
        if (stored.isEmpty() || directory == null) {
            markInvalid();
            return Typeface.DEFAULT;
        }
        // 只取文件名，杜绝 prefs 被篡改后的路径穿越。
        File file = new File(directory, new File(stored).getName());
        if (!file.isFile() || file.length() == 0L) {
            markInvalid();
            return Typeface.DEFAULT;
        }
        try {
            Typeface face = Typeface.createFromFile(file);
            if (face == null) {
                markInvalid();
                return Typeface.DEFAULT;
            }
            return face;
        } catch (RuntimeException | LinkageError failure) {
            markInvalid();
            return Typeface.DEFAULT;
        }
    }

    /** 阅读页排版入口：仅在非「跟随系统」态调用 setTypeface，降级只提示一次。 */
    void applyTo(TextView text) {
        if (text == null) return;
        Typeface face = resolveBodyTypeface();
        if (face == null) return; // 跟随系统：零干预，保留 TextView 初始默认字体
        text.setTypeface(face);
        if (mode() == MODE_USER && !userFileValid() && !degradationNotified) {
            degradationNotified = true;
            Toast.makeText(context, "字体文件不可用，已恢复默认字体，请重新选择", Toast.LENGTH_LONG).show();
        }
    }

    private File fontDirectory() {
        File directory = new File(context.getFilesDir(), FONT_DIRECTORY);
        return directory.isDirectory() || directory.mkdirs() ? directory : null;
    }

    /** 老单字体路径沿用：只保留签名，内部委托同一流式复制实现。 */
    private void copyFont(Uri uri, File target) throws IOException {
        copyFontWithDigest(uri, target);
    }

    /**
     * 复制过程中流式计算 SHA-256；16 MiB 上限校验位置与 0.27.0 原实现一致。
     * D3：复制一开始先校验源文件前 4 字节魔数，非字体直接抛 {@link NotAFontException}，
     * 由 installFont 删临时文件并保持零配置改动。
     */
    private CopyResult copyFontWithDigest(Uri uri, File target) throws IOException {
        long total = 0L;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("摘要算法不可用");
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法打开字体文件");
            byte[] header = new byte[4];
            int headerRead = 0;
            while (headerRead < 4) {
                int chunk = in.read(header, headerRead, 4 - headerRead);
                if (chunk < 0) break;
                headerRead += chunk;
            }
            if (headerRead < 4 || !FontLibrary.looksLikeFont(header)) {
                throw new NotAFontException();
            }
            try (OutputStream out = new FileOutputStream(target)) {
                digest.update(header, 0, 4);
                out.write(header, 0, 4);
                total = 4L;
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_FONT_BYTES) throw new IOException("字体文件超过 16 MiB 上限");
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
        } catch (IOException failure) {
            target.delete();
            throw failure;
        }
        return new CopyResult(total, digest.digest());
    }

    /** 文件头不是 TTF/OTF/TTC 魔数；与普通 IOException 区分，供 UI 给出准确文案。 */
    private static final class NotAFontException extends IOException {
        NotAFontException() {
            super("不是有效的 TTF/OTF 字体文件");
        }
    }

    /** 流式复制的副产物：实际字节数与内容摘要，摘要交给 core 派生稳定 id。 */
    private static final class CopyResult {
        final long bytes;
        final byte[] digest;

        CopyResult(long bytes, byte[] digest) {
            this.bytes = bytes;
            this.digest = digest;
        }
    }

    private void markInvalid() {
        if (userFileValid()) prefs.edit().putBoolean(KEY_VALID, false).apply();
    }

    private static String extensionOf(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ttf")) return ".ttf";
        if (lower.endsWith(".otf")) return ".otf";
        return null;
    }

    private static int clamp(int mode) {
        return mode < MODE_SYSTEM || mode > MODE_USER ? MODE_SYSTEM : mode;
    }
}
