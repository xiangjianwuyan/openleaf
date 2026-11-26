package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * EPUB 位置锚点（纯 JVM，无 Android 依赖）——OpenLeaf 自有的简化 CFI。
 *
 * 背景：标准 EPUB CFI 依赖原书 XHTML 的 id/class 与 DOM 路径，但 OpenLeaf 的图文渲染
 * 在 BookParser.RichHandler 阶段剥离了 id/class、重写了 body/img，标准 CFI 无法在输出的
 * fragment 上解析。因此采用"渲染后 DOM 的稳定结构路径"作为锚点，由 RichHandler 输出
 * data-cfi，EpubActivity 保存/恢复。
 *
 * 格式：{@code ocfcfi:v1:s<spine>/<path>/<offset>}
 *   spine  : spine 索引（0 起）
 *   path   : body 下元素结构路径，斜杠分隔的子元素序号（0 起），如 "2/3/1"
 *   offset : 目标元素文本内字符偏移（0 起）
 * 各段均有界，解析失败返回 null 而非抛异常，绝不因坏锚点崩溃。
 */
public final class EpubCfi {
    private EpubCfi() { }

    public static final String SCHEME = "ocfcfi";
    public static final int VERSION = 1;
    private static final int MAX_PATH_DEPTH = 32;
    private static final int MAX_OFFSET = 10_000_000;

    /** 解析后的位置。 */
    public static final class Location {
        public final int spineIndex;
        public final int[] path;
        public final int offset;
        Location(int spineIndex, int[] path, int offset) {
            this.spineIndex = spineIndex; this.path = path; this.offset = offset;
        }
        /** 结构路径字符串（供 data-cfi 属性用，不含 spine/offset）。 */
        public String pathString() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < path.length; i++) {
                if (i > 0) b.append('/');
                b.append(path[i]);
            }
            return b.toString();
        }
    }

    /** 生成 CFI 字符串。参数非法时返回空串（不抛异常）。 */
    public static String create(int spineIndex, int[] path, int offset) {
        if (spineIndex < 0 || offset < 0 || offset > MAX_OFFSET) return "";
        StringBuilder b = new StringBuilder(SCHEME).append(":v").append(VERSION)
                .append(":s").append(spineIndex).append('/');
        if (path != null && path.length > 0) {
            int depth = Math.min(path.length, MAX_PATH_DEPTH);
            for (int i = 0; i < depth; i++) {
                if (path[i] < 0) return "";
                if (i > 0) b.append('/');
                b.append(path[i]);
            }
        } else {
            b.append('0'); // 空路径用 0 占位，保证段数稳定
        }
        b.append('/').append(offset);
        return b.toString();
    }

    /** 便捷生成：仅 spine + offset（无结构路径）。 */
    public static String createSimple(int spineIndex, int offset) {
        return create(spineIndex, new int[]{0}, offset);
    }

    /** 解析 CFI；任何格式/范围错误返回 null。 */
    public static Location parse(String cfi) {
        if (cfi == null || cfi.trim().isEmpty()) return null;
        String value = cfi.trim();
        String[] head = value.split(":", 3);
        if (head.length != 3 || !SCHEME.equalsIgnoreCase(head[0])) return null;
        if (!("v" + VERSION).equalsIgnoreCase(head[1])) return null; // 未知版本拒绝
        String body = head[2];
        if (!body.startsWith("s")) return null;
        String[] parts = body.substring(1).split("/", -1);
        if (parts.length < 3 || parts.length > MAX_PATH_DEPTH + 3) return null;
        int spine = parseInt(parts[0], -1);
        int offset = parseInt(parts[parts.length - 1], -1);
        if (spine < 0 || offset < 0 || offset > MAX_OFFSET) return null;
        int pathLen = parts.length - 2;
        int[] path = new int[pathLen];
        for (int i = 0; i < pathLen; i++) {
            path[i] = parseInt(parts[i + 1], -1);
            if (path[i] < 0) return null;
        }
        return new Location(spine, path, offset);
    }

    /**
     * 顺序比较：返回负数 a 在 b 前、正数 a 在 b 后、0 相同位置。
     * 用于判断进度先后；不同 spine 直接按 spine 序，同 spine 按路径逐段再按偏移。
     */
    public static int compare(String a, String b) {
        Location la = parse(a), lb = parse(b);
        if (la == null || lb == null) return 0;
        if (la.spineIndex != lb.spineIndex) return Integer.compare(la.spineIndex, lb.spineIndex);
        int n = Math.min(la.path.length, lb.path.length);
        for (int i = 0; i < n; i++) {
            if (la.path[i] != lb.path[i]) return Integer.compare(la.path[i], lb.path[i]);
        }
        if (la.path.length != lb.path.length) return Integer.compare(la.path.length, lb.path.length);
        return Integer.compare(la.offset, lb.offset);
    }

    /** 失效回退：CFI 无法解析或 spine 越界时返回安全位置。 */
    public static Location fallback(String cfi, int spineCount) {
        Location loc = parse(cfi);
        if (loc == null || spineCount <= 0) return new Location(0, new int[]{0}, 0);
        if (loc.spineIndex >= spineCount) return new Location(spineCount - 1, new int[]{0}, 0);
        return loc;
    }

    /** 摘要文案：不暴露完整路径。 */
    public static String shortText(String cfi) {
        Location loc = parse(cfi);
        if (loc == null) return "（无效位置）";
        return String.format(Locale.ROOT, "第%d节 · %s:%d", loc.spineIndex + 1, loc.pathString(), loc.offset);
    }

    private static int parseInt(String value, int fallback) {
        try {
            int n = Integer.parseInt(value);
            return n < 0 ? fallback : n;
        } catch (NumberFormatException e) { return fallback; }
    }
}
