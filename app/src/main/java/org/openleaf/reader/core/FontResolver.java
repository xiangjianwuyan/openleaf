package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.openleaf.reader.core.FontLibrary.Entry;

/**
 * 正文有效字体解析（0.29.0 P2-2，纯 JVM，无 Android 依赖、无 IO、无状态）。
 *
 * <p>三态常量与 {@code ReaderFonts.MODE_*} 逐字一致。调用方传入 mode / 全局 id / 按书键 /
 * 收藏列表 / legacy 可用性，本类按 §3.4 优先级矩阵 R0–R6 短路返回不可变结果，永不抛异常。
 * 其中 R0 是最高风险条款：MODE_SYSTEM 绝对短路，按书键也不生效、调用方一行 setTypeface 都不执行。
 */
public final class FontResolver {

    /** 跟随系统：调用方不得触碰 setTypeface，保留 TextView 初始字体。 */
    public static final int MODE_SYSTEM = 0;
    /** 内置默认：调用方使用 Typeface.DEFAULT。 */
    public static final int MODE_BUILTIN = 1;
    /** 用户字体：按全局 id / 按书键 / legacy 回退解析收藏。 */
    public static final int MODE_USER = 2;

    private static final String BOOK_KEY_PREFIX = "font_book_";

    private FontResolver() {
        // 纯工具类，禁止实例化。
    }

    /** 解析结果种类。 */
    public enum Kind {
        SYSTEM,
        BUILTIN,
        FONT
    }

    /** 不可变解析结果；clearBookKey 表示调用方应清除当前书的按书覆盖键。 */
    public static final class Result {
        public final Kind kind;
        public final String fontId;
        public final boolean clearBookKey;

        public Result(Kind kind, String fontId, boolean clearBookKey) {
            this.kind = kind;
            this.fontId = fontId;
            this.clearBookKey = clearBookKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Result)) return false;
            Result that = (Result) other;
            return clearBookKey == that.clearBookKey && kind == that.kind
                    && (fontId == null ? that.fontId == null : fontId.equals(that.fontId));
        }

        @Override
        public int hashCode() {
            int result = kind == null ? 0 : kind.hashCode();
            result = 31 * result + (fontId == null ? 0 : fontId.hashCode());
            result = 31 * result + (clearBookKey ? 1 : 0);
            return result;
        }

        @Override
        public String toString() {
            return "Result{kind=" + kind + ", fontId=" + fontId + ", clearBookKey=" + clearBookKey + "}";
        }
    }

    /** 按书键的固定前缀名。 */
    public static String bookKeyName(String bookId) {
        return BOOK_KEY_PREFIX + (bookId == null ? "" : bookId);
    }

    /**
     * 直传按书键的解析入口。优先级：R0 SYSTEM 绝对短路 → R1 按书有效 → R2 按书失效标记清键
     * → R3 全局有效 → R4 legacy 双键回退 → R5/R6 BUILTIN。
     */
    public static Result resolveEffective(int mode, String globalId, String bookKey,
                                          List<Entry> library, boolean legacyAvailable) {
        int resolvedMode = clamp(mode);
        // R0：跟随系统绝对短路，绝不读按书键，也不产生任何清键副作用。
        if (resolvedMode == MODE_SYSTEM) return new Result(Kind.SYSTEM, null, false);

        boolean clearBookKey = false;
        String requestedBook = emptyToNull(bookKey);
        if (requestedBook != null) {
            // R1：按书键有效，优先级最高（含 legacy 哨兵 id）。
            if (usable(library, requestedBook, legacyAvailable)) {
                return new Result(Kind.FONT, requestedBook, false);
            }
            // R2：按书键失效/对应字体被删 → 标记清键并继续回落。
            clearBookKey = true;
        }

        if (resolvedMode == MODE_USER) {
            String requestedGlobal = emptyToNull(globalId);
            // R3：全局收藏字体。
            if (requestedGlobal != null && usable(library, requestedGlobal, legacyAvailable)) {
                return new Result(Kind.FONT, requestedGlobal, clearBookKey);
            }
            // R4：legacy 双键回退。library 空但旧文件可用时走 0.27.0 旧路径。
            boolean hasLegacy = hasValidLegacy(library);
            if (legacyAvailable && (hasLegacy || library == null || library.isEmpty())) {
                return new Result(Kind.FONT, FontLibrary.LEGACY_ID, clearBookKey);
            }
            // R5：MODE_USER 无可用字体，降级系统默认字体。
            return new Result(Kind.BUILTIN, null, clearBookKey);
        }
        // R6：显式内置默认。
        return new Result(Kind.BUILTIN, null, clearBookKey);
    }

    /** 从 prefs Map 按 bookId 取按书键的重载；null/空 id 或非字符串值按无覆盖处理。 */
    public static Result resolveEffective(int mode, String globalId, String bookId,
                                          Map<String, String> prefs, List<Entry> library,
                                          boolean legacyAvailable) {
        String bookKey = null;
        if (prefs != null && bookId != null && !bookId.isEmpty()) {
            try {
                String value = prefs.get(bookKeyName(bookId));
                bookKey = emptyToNull(value);
            } catch (RuntimeException unreadable) {
                bookKey = null; // 契约：永不抛异常。
            }
        }
        return resolveEffective(mode, globalId, bookKey, library, legacyAvailable);
    }

    /**
     * 扫描删除某字体时需要清理的按书键：只看 font_book_ 前缀且值精确等于 fontId，
     * 返回字典序新列表，不修改入参，不碰其他任何键。
     */
    public static List<String> collectBookKeysReferencing(Map<String, String> prefs, String fontId) {
        List<String> keys = new ArrayList<>();
        if (prefs == null || fontId == null || fontId.isEmpty()) {
            return Collections.unmodifiableList(keys);
        }
        try {
            for (Map.Entry<String, String> item : prefs.entrySet()) {
                String key = item.getKey();
                String value = item.getValue();
                if (key == null || !key.startsWith(BOOK_KEY_PREFIX)) continue;
                if (value != null && fontId.equals(value)) keys.add(key);
            }
        } catch (RuntimeException unreadable) {
            return Collections.unmodifiableList(new ArrayList<String>());
        }
        Collections.sort(keys);
        return Collections.unmodifiableList(keys);
    }

    /** 可用性：library 中有该 id 用它自己的 valid；legacy 在无其他收藏且旧文件可用时视为可用。 */
    private static boolean usable(List<Entry> library, String id, boolean legacyAvailable) {
        if (id == null || id.isEmpty()) return false;
        Entry entry = FontLibrary.find(library, id);
        if (entry != null) return entry.valid;
        return FontLibrary.LEGACY_ID.equals(id) && legacyAvailable
                && (library == null || library.isEmpty());
    }

    /** library 中是否存在有效的 legacy 条目。 */
    private static boolean hasValidLegacy(List<Entry> library) {
        Entry legacy = FontLibrary.find(library, FontLibrary.LEGACY_ID);
        return legacy != null && legacy.valid;
    }

    private static int clamp(int mode) {
        return mode < MODE_SYSTEM || mode > MODE_USER ? MODE_SYSTEM : mode;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
