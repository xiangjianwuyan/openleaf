package org.openleaf.reader.core;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 用户自带字体的收藏模型与持久化编解码（0.29.0 P2-1，纯 JVM，无 Android 依赖）。
 *
 * <p>本类只做「输入 → 输出」：内容 id 由调用方算好的 SHA-256 摘要派生，文件复制、MessageDigest
 * 与 SharedPreferences 全部留在 Android 层。沿用 {@link ChapterRule} 的纯函数/不可变范式，
 * 可直接进入 core-tests（{@code javac --release 17}）。
 *
 * <p>序列化格式（单键 {@code font_library}）：
 * {@code record(\u001Erecord)*}，record = {@code id\u001FnameEnc\u001Fext\u001Fvalid}。
 * 展示名先归一化再 URLEncoder 转义，避免分隔符冲突；坏记录一律跳过且绝不抛异常。
 */
public final class FontLibrary {

    /** legacy 保留哨兵 id：老版本单字体不移动物理文件、不改名，只以该 id 登记进列表。 */
    public static final String LEGACY_ID = "legacy";
    /** 收藏条目上限（含 legacy）。 */
    public static final int MAX_ENTRIES = 20;
    /** 原创预览示例文本：中文 + 数字 + 拉丁字母，不取自任何第三方样本。 */
    public static final String SAMPLE_TEXT =
            "墨香伴长夜，开卷见青山。读书写字，字字入心。OpenLeaf 0123456789 abcXYZ";

    private static final char FIELD_SEPARATOR = '\u001F';
    private static final char RECORD_SEPARATOR = '\u001E';
    private static final int MAX_NAME_CHARS = 120;
    private static final String EXT_TTF = "ttf";
    private static final String EXT_OTF = "otf";

    private FontLibrary() {
        // 纯工具类，禁止实例化。
    }

    /** 添加失败的具体原因，供 UI 给出确定文案。 */
    public enum Reason {
        OK,
        DUPLICATE,
        LIMIT_REACHED,
        INVALID,
        /** 复制成功但文件头不是 TTF/OTF/TTC 魔数（D3：垃圾文件改名导入必须拒绝）。 */
        NOT_FONT
    }

    /** 单条收藏记录；不可变，字段与序列化格式一一对应。 */
    public static final class Entry {
        public final String id;
        public final String name;
        public final String ext;
        public final boolean valid;

        public Entry(String id, String name, String ext, boolean valid) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.ext = ext == null ? "" : ext;
            this.valid = valid;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Entry)) return false;
            Entry that = (Entry) other;
            return valid == that.valid && id.equals(that.id)
                    && name.equals(that.name) && ext.equals(that.ext);
        }

        @Override
        public int hashCode() {
            int result = id.hashCode();
            result = 31 * result + name.hashCode();
            result = 31 * result + ext.hashCode();
            result = 31 * result + (valid ? 1 : 0);
            return result;
        }

        @Override
        public String toString() {
            return "Entry{id=" + id + ", name=" + name + ", ext=" + ext + ", valid=" + valid + "}";
        }
    }

    /** 添加结果：无论成功失败都给出可立即写回的库快照，方便原子提交。 */
    public static final class AddResult {
        public final List<Entry> library;
        public final boolean added;
        public final Reason reason;

        public AddResult(List<Entry> library, boolean added, Reason reason) {
            this.library = library;
            this.added = added;
            this.reason = reason;
        }
    }

    /**
     * 从已算好的 SHA-256 摘要派生 16 位小写 hex（前 8 字节）。id 只由内容决定，
     * 与 Uri / 展示名 / 存储路径无关。摘要为空或短于 8 字节直接拒绝。
     */
    public static String stableId(byte[] digest) {
        if (digest == null || digest.length < 8) {
            throw new IllegalArgumentException("SHA-256 摘要不完整");
        }
        StringBuilder out = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            int value = digest[i] & 0xFF;
            out.append(Character.forDigit(value >>> 4, 16));
            out.append(Character.forDigit(value & 0x0F, 16));
        }
        return out.toString();
    }

    /** id 合法性：legacy 哨兵或 16 位小写 hex；用于 prefs 被篡改时的路径穿越防御。 */
    public static boolean isValidId(String id) {
        if (id == null || id.isEmpty()) return false;
        if (LEGACY_ID.equals(id)) return true;
        if (id.length() != 16) return false;
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) return false;
        }
        return true;
    }

    /**
     * 展示名归一化：trim → 去 C0 控制字符与 DEL（含两个序列化分隔符）→ 截前 120 个 UTF-16 码元。
     * 仅用于新条目；legacy 展示名取旧键原值，不经过这里，避免老用户零回归被破坏。
     */
    public static String normalizeDisplayName(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch < 0x20 || ch == 0x7F) continue;
            out.append(ch);
        }
        String value = out.toString();
        return value.length() > MAX_NAME_CHARS ? value.substring(0, MAX_NAME_CHARS) : value;
    }

    /**
     * 纯 JVM 文件头校验（D3）：只认 sfnt / TTC 四种标准魔数。
     *
     * <p>动机：部分设备上 {@code Typeface.createFromFile(垃圾文件)} 不抛异常，而是返回一个
     * 可用的降级 Typeface，因此仅靠「加载失败」无法识别损坏字体。安装路径与加载路径
     * （{@code ReaderFonts.copyFontWithDigest} / {@code createTypeface}）共用本方法。
     *
     * <p>合法魔数：{@code 0x00010000}(TTF)、{@code "true"}(Apple TTF)、
     * {@code "OTTO"}(CFF/OTF)、{@code "ttcf"}(TTC)、{@code "typ1"}(Type1 sfnt)。
     * 入参 null 或不足 4 字节一律 false。
     */
    public static boolean looksLikeFont(byte[] head4) {
        if (head4 == null || head4.length < 4) return false;
        int magic = ((head4[0] & 0xFF) << 24)
                | ((head4[1] & 0xFF) << 16)
                | ((head4[2] & 0xFF) << 8)
                | (head4[3] & 0xFF);
        return magic == 0x00010000   // TTF
                || magic == 0x74727565 // "true" Apple TTF
                || magic == 0x4F54544F // "OTTO" CFF/OTF
                || magic == 0x74746366 // "ttcf" TTC
                || magic == 0x74797031; // "typ1" Type1 sfnt
    }

    /** 从文件名推断扩展名（不带点），大小写不敏感；非白名单返回空串。 */
    public static String extFromFileName(String fileName) {
        if (fileName == null) return "";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith("." + EXT_TTF)) return EXT_TTF;
        if (lower.endsWith("." + EXT_OTF)) return EXT_OTF;
        return "";
    }

    /** 新字体落位文件名；调用前须通过 {@link #isValidId(String)} 且 ext 为 ttf/otf。 */
    public static String fileNameOf(String id, String ext) {
        return "font_" + id + "." + ext;
    }

    /** 序列化整库；null / 空列表返回空串，条目顺序即入库顺序。 */
    public static String serialize(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Entry entry : entries) {
            if (entry == null || count >= MAX_ENTRIES) continue;
            if (count > 0) out.append(RECORD_SEPARATOR);
            String encoded = encodeName(entry.name);
            out.append(entry.id).append(FIELD_SEPARATOR)
                    .append(encoded).append(FIELD_SEPARATOR)
                    .append(entry.ext).append(FIELD_SEPARATOR)
                    .append(entry.valid ? '1' : '0');
            count++;
        }
        return out.toString();
    }

    /**
     * 反序列化整库：坏记录跳过、重复 id 保留首条、最多 20 条，返回不可修改的新列表。
     * 任意输入都不抛异常。
     */
    public static List<Entry> deserialize(String raw) {
        List<Entry> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return Collections.unmodifiableList(result);
        String[] records = raw.split(String.valueOf(RECORD_SEPARATOR), -1);
        for (String record : records) {
            if (record.isEmpty() || result.size() >= MAX_ENTRIES) continue;
            String[] fields = record.split(String.valueOf(FIELD_SEPARATOR), -1);
            if (fields.length != 4) continue;
            String id = fields[0];
            if (!isValidId(id)) continue;
            if (!EXT_TTF.equals(fields[2]) && !EXT_OTF.equals(fields[2])) continue;
            if (!"0".equals(fields[3]) && !"1".equals(fields[3])) continue;
            String name;
            try {
                // 字符串字符集重载在 API 1 即可用；Charset 重载要 API 33，minSdk 26 不能用。
                name = URLDecoder.decode(fields[1], "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException malformed) {
                continue; // Percent 转义损坏：整条跳过，不影响其他收藏。
            }
            Entry candidate = new Entry(id, name, fields[2], "1".equals(fields[3]));
            if (find(result, id) != null) continue;
            result.add(candidate);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 纯函数添加：入参不被修改。id 非法 / ext 非白名单 / 名称为空 → INVALID；
     * 同 id 已存在 → DUPLICATE；已满 20 → LIMIT_REACHED；否则追加并返回新列表。
     */
    public static AddResult add(List<Entry> current, Entry candidate) {
        List<Entry> base = current == null ? Collections.<Entry>emptyList() : current;
        if (candidate == null || !isValidId(candidate.id)
                || !(EXT_TTF.equals(candidate.ext) || EXT_OTF.equals(candidate.ext))
                || normalizeDisplayName(candidate.name).isEmpty()) {
            return new AddResult(base, false, Reason.INVALID);
        }
        if (find(base, candidate.id) != null) {
            return new AddResult(base, false, Reason.DUPLICATE);
        }
        if (base.size() >= MAX_ENTRIES) {
            return new AddResult(base, false, Reason.LIMIT_REACHED);
        }
        List<Entry> updated = new ArrayList<>(base);
        updated.add(new Entry(candidate.id, normalizeDisplayName(candidate.name), candidate.ext, candidate.valid));
        return new AddResult(Collections.unmodifiableList(updated), true, Reason.OK);
    }

    /** 展示名转义：使用 API 1 起可用的字符串字符集重载，避免 minSdk 26 上的 NewApi。 */
    private static String encodeName(String raw) {
        String normalized = normalizeDisplayName(raw);
        try {
            return URLEncoder.encode(normalized, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 字符集不可用", impossible);
        }
    }

    /** 纯函数删除；id 不存在时原样返回入参列表。 */
    public static List<Entry> remove(List<Entry> current, String id) {
        List<Entry> base = current == null ? Collections.<Entry>emptyList() : current;
        if (id == null || find(base, id) == null) return base;
        List<Entry> updated = new ArrayList<>();
        for (Entry entry : base) {
            if (!id.equals(entry.id)) updated.add(entry);
        }
        return Collections.unmodifiableList(updated);
    }

    /** 纯函数重命名；经归一化，新名为空或 id 不存在时原样返回入参列表。 */
    public static List<Entry> rename(List<Entry> current, String id, String newName) {
        List<Entry> base = current == null ? Collections.<Entry>emptyList() : current;
        String normalized = normalizeDisplayName(newName);
        if (id == null || normalized.isEmpty() || find(base, id) == null) return base;
        List<Entry> updated = new ArrayList<>();
        boolean changed = false;
        for (Entry entry : base) {
            if (id.equals(entry.id)) {
                if (!normalized.equals(entry.name)) changed = true;
                updated.add(new Entry(entry.id, normalized, entry.ext, entry.valid));
            } else {
                updated.add(entry);
            }
        }
        return changed ? Collections.unmodifiableList(updated) : base;
    }

    /** 纯函数损坏标记；id 不存在或已损坏时原样返回入参列表。 */
    public static List<Entry> markInvalid(List<Entry> current, String id) {
        List<Entry> base = current == null ? Collections.<Entry>emptyList() : current;
        if (id == null) return base;
        List<Entry> updated = new ArrayList<>();
        boolean changed = false;
        for (Entry entry : base) {
            if (id.equals(entry.id) && entry.valid) {
                updated.add(new Entry(entry.id, entry.name, entry.ext, false));
                changed = true;
            } else {
                updated.add(entry);
            }
        }
        return changed ? Collections.unmodifiableList(updated) : base;
    }

    /** 按 id 查找条目；任意 null 都返回 null。 */
    public static Entry find(List<Entry> entries, String id) {
        if (entries == null || id == null) return null;
        for (Entry entry : entries) {
            if (entry != null && id.equals(entry.id)) return entry;
        }
        return null;
    }

    /**
     * 全局 id 有效性收敛：requested 命中 valid 条目用它；否则保留仍有效的 current；再否则空串。
     */
    public static String resolveGlobalId(String current, String requested, List<Entry> entries) {
        Entry asked = find(entries, requested);
        if (asked != null && asked.valid) return requested;
        Entry existing = find(entries, current);
        if (existing != null && existing.valid) return current;
        return "";
    }

    /**
     * legacy 惰性迁移判定：仅当 {@code font_library} 键「不存在」（raw==null）且旧键有值时为 true。
     * 键存在但值为空串意味着已在新世界，不再迁移。纯函数、无 IO、无副作用。
     */
    public static boolean migrationDecision(String libraryRaw, String legacyFile) {
        return libraryRaw == null && legacyFile != null && !legacyFile.isEmpty();
    }

    /**
     * 由旧三键构造 legacy 条目；ext 为空时 Android 层应跳过迁移（不生成任何新文件名）。
     * 展示名沿用旧值，空则给中文默认值；这里刻意不做归一化，保证老用户零回归。
     */
    public static Entry legacyEntry(String legacyFile, String legacyName, boolean valid) {
        String name = legacyName == null || legacyName.isEmpty() ? "用户字体" : legacyName;
        return new Entry(LEGACY_ID, name, extFromFileName(legacyFile), valid);
    }

    /** 预览卡片可选判定：只有非 null 且 valid 的条目允许设为默认 / 本书使用。 */
    public static boolean selectable(Entry entry) {
        return entry != null && entry.valid;
    }
}
