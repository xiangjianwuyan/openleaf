package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 阶段 2-B 漫画入库的纯 JVM 逻辑（无 Android 依赖，可单测）。
 *
 * <p>负责：漫画格式识别（与 DropBatch 一致）、从条目名推断封面条目与标题、
 * books.media_type 取值、以及旧单文件指纹键到 bookId 的映射键生成。
 * 压缩包的字节读取/解压仍在 Android 层，本类只做字符串与集合判定。</p>
 */
public final class ComicLibrary {
    private ComicLibrary() { }

    /** 漫画归档扩展（ZIP/CBZ + Q3 采纳后的 RAR/CBR）。 */
    private static final String COMIC_EXTS = "|cbz|zip|cbr|rar|";
    /** 7z 后置：识别但归为"暂不支持"，给出明确提示而非静默失败。 */
    private static final String DEFERRED_EXTS = "|cb7|7z|";

    public enum Kind { COMIC, DEFERRED, NOT_COMIC }

    public static Kind classify(String displayName) {
        String ext = extension(displayName);
        if (ext.isEmpty()) return Kind.NOT_COMIC;
        if (COMIC_EXTS.contains("|" + ext + "|")) return Kind.COMIC;
        if (DEFERRED_EXTS.contains("|" + ext + "|")) return Kind.DEFERRED;
        return Kind.NOT_COMIC;
    }

    /** 是否漫画（Q3 采纳 RAR 后包含 cbr/rar）。 */
    public static boolean isComic(String displayName) { return classify(displayName) == Kind.COMIC; }

    /** 从文件名推断展示标题：去扩展名、去常见序号前缀、截断到 120 字符。 */
    public static String titleFrom(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return "未命名漫画";
        String name = displayName.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        // 去掉 "001_" / "[01]" 之类卷号前缀，保留正文可读性
        name = name.replaceFirst("^\\s*\\d{1,4}[_\\-\\s.]+", "");
        name = name.replaceFirst("^\\s*\\[\\d{1,4}\\]\\s*", "");
        name = name.trim();
        if (name.isEmpty()) name = displayName.trim();
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    /** 封面候选：按自然序取第一张图片；无图片返回 null。 */
    public static String coverEntry(List<String> imageNames) {
        if (imageNames == null || imageNames.isEmpty()) return null;
        List<String> sorted = new ArrayList<>();
        for (String n : imageNames) if (ComicFlow.isImage(n) && ComicFlow.safeEntry(n)) sorted.add(n);
        if (sorted.isEmpty()) return null;
        Collections.sort(sorted, ComicFlow::naturalCompare);
        return sorted.get(0);
    }

    /** 旧单文件指纹键 → bookId 映射的存储键（可读前缀 + 指纹哈希），避免与老键直接冲突。 */
    public static String legacyKeyToBookMapping(String legacyFingerprintKey) {
        String key = legacyFingerprintKey == null ? "" : legacyFingerprintKey;
        return "comic_bookid_" + ContentContracts.hashHex(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 老用户提示文案（未入库漫画的既有行为保持不变）。 */
    public static String deferredHint(String name) {
        return (name == null ? "" : name) + "：7z/CB7 暂不支持，请先转为 CBZ/ZIP/CBR/RAR。";
    }

    private static String extension(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 || dot == lower.length() - 1 ? "" : lower.substring(dot + 1);
    }
}
