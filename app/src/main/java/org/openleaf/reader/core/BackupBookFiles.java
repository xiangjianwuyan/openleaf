package org.openleaf.reader.core;

import java.util.Locale;

/**
 * 便携备份中书文件条目 {@code files/<bookId><ext>} 的扩展名唯一口径（纯 JVM，无 Android 依赖）。
 *
 * <p>SAF 引用书在 {@code books.file} 里没有设备内文件名，但书的内容字节仍属于书本体、必须随备份导出。
 * 恢复侧按条目名末尾的 {@code .<ext>} 重新落盘并回填 {@code books.file}，因此这里产出的扩展名
 * 直接决定恢复后书文件能否被识别。优先级：库内文件名 &gt; 展示名 &gt; 格式 &gt; {@code .bin}。</p>
 */
public final class BackupBookFiles {
    private BackupBookFiles() { }

    /**
     * 决定备份中该书的扩展名（总是以 {@code '.'} 开头的小写形式）。
     *
     * @param storedFile  books.file 中保存的设备内文件名（SAF 引用书通常为空）
     * @param format      books.format，如 txt/epub/pdf/mobi/comic
     * @param displayName 书名/展示名，SAF 引用书的展示名可能自带未被剥离的扩展名
     */
    public static String extensionFor(String storedFile, String format, String displayName) {
        String ext = extensionIn(storedFile);
        if (ext != null) return ext;
        ext = extensionIn(displayName);
        if (ext != null) return ext;
        if (format != null) {
            String normalized = format.toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && normalized.length() <= 8 && alphaNumeric(normalized))
                return "." + normalized;
        }
        return ".bin";
    }

    /** 取最后一个 '.' 之后 1–8 位字母数字；'.' 位于首位（整个名字都是扩展名）时不算。 */
    private static String extensionIn(String name) {
        if (name == null || name.isEmpty()) return null;
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        String ext = name.substring(dot + 1);
        if (ext.length() > 8 || !alphaNumeric(ext)) return null;
        return "." + ext.toLowerCase(Locale.ROOT);
    }

    private static boolean alphaNumeric(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')))
                return false;
        }
        return true;
    }
}
