package org.openleaf.reader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.MobiChapters;

/**
 * libmobi 桥接（0.14.0 T02；T01 的 {@code available()} / {@code version()} 探针保留）。
 *
 * <p>本类位于 {@code org.openleaf.reader} 而非 {@code core}，以保持 core 为纯 JVM。
 * so 缺失时静态初始化吞掉 {@link LinkageError} 并记入 {@link #sLoadError}，上层走 §6 降级文案，不闪退。
 * 加密文档在 {@code nativeOpen} 拿到句柄后、任何正文解析之前由 {@link #nativeIsEncrypted(long)}
 * 前置硬拒绝——本类从不调用任何 libmobi 解密接口。</p>
 */
public final class MobiNative {

    private static volatile boolean sLoaded;
    private static volatile LinkageError sLoadError;

    static {
        try {
            System.loadLibrary("openleafmobi");
            sLoaded = true;
            // 把 native 实现注入纯 JVM 的 BookParser；不污染 core-tests 的编译类路径。
            BookParser.setMobiDecoder(new Bridge());
        } catch (LinkageError error) {
            sLoadError = error;
        }
    }

    private MobiNative() { }

    /** 强制触发类加载（从而完成 so 加载与 decoder 注入）；不抛异常。 */
    public static void ensureInstalled() { /* 静态块即安装逻辑 */ }

    /** so 是否可用；仅用于诊断。 */
    public static boolean loaded() { return sLoaded; }

    /** so 已成功加载并返回 JNI_TRUE 时为真（T01 探针）。 */
    public static native boolean available();

    /** 返回 libmobi 的 mobi_version() 字符串（T01 探针）。 */
    public static native String version();

    // ---------- native 解析接口（符号与 §5.4 逐字对应）----------

    private static native long nativeOpen(String path);
    private static native String nativeTitle(long handle);
    private static native int nativeChapterCount(long handle);
    private static native String nativeChapterText(long handle, int index);
    private static native boolean nativeIsEncrypted(long handle);
    private static native void nativeClose(long handle);

    /** DRM 硬拒绝文案（§6.1 状态 2，逐字）。 */
    public static final class EncryptedDocumentException extends Exception {
        public EncryptedDocumentException() {
            super("这本书受 DRM 保护，本应用不提供解密，已停止导入。");
        }
    }

    /** 解析结果：书名 + 章节（章节标题保留在正文首行，供 head()/想法区零改动复用）。 */
    public static final class Result {
        public final String title;
        public final List<MobiChapters.Chapter> chapters;

        public Result(String title, List<MobiChapters.Chapter> chapters) {
            if (title == null || chapters == null) throw new NullPointerException();
            this.title = title;
            this.chapters = chapters;
        }
    }

    private static final String NO_SO = "本机缺少 MOBI 解析组件，暂时无法打开；可用 Calibre 转为 EPUB 后导入。";
    private static final String NOT_MOBI = "无法解析这本书的内容（不是有效的 MOBI 文件），可尝试转换为 EPUB。";

    /** 完整解析：加密前置拒绝 → 标题 → 逐段 rawml 纯文本 → MobiChapters 切章；finally 关闭句柄。 */
    public static Result read(File file) throws IOException, EncryptedDocumentException {
        if (file == null || !file.isFile() || !file.canRead()) throw new IOException("电子书文件不存在或不可读取");
        if (!sLoaded) throw new IOException(NO_SO, sLoadError);
        long handle;
        try {
            handle = nativeOpen(file.getAbsolutePath());
        } catch (UnsatisfiedLinkError error) {
            throw new IOException(NO_SO, error);
        }
        if (handle == 0) throw new IOException("不是有效的 MOBI 文件");
        try {
            if (nativeIsEncrypted(handle)) throw new EncryptedDocumentException();
            String title = nativeTitle(handle);
            int segments = nativeChapterCount(handle);
            if (segments <= 0) throw new IOException(NOT_MOBI);
            List<MobiChapters.Chapter> chapters = new ArrayList<>();
            int remaining = BookParser.MAX_CHAPTERS;
            for (int i = 0; i < segments && remaining > 0; i++) {
                String text = nativeChapterText(handle, i);
                if (text == null || text.isEmpty()) continue;
                List<MobiChapters.Chapter> part =
                        MobiChapters.split(text, remaining, BookParser.MAX_CHAPTER_CHARS);
                chapters.addAll(part);
                remaining -= part.size();
            }
            if (chapters.isEmpty()) throw new IOException(NOT_MOBI);
            if (title == null || title.trim().isEmpty()) {
                String name = file.getName();
                int dot = name.lastIndexOf('.');
                title = dot > 0 ? name.substring(0, dot) : name;
            }
            return new Result(title, chapters);
        } finally {
            nativeClose(handle);
        }
    }

    /** 只校验并取书名：打开 → 加密前置拒绝 → 标题 → 关闭；不解析正文，不写缓存。 */
    private static String validateOnly(File file) throws IOException, EncryptedDocumentException {
        if (file == null || !file.isFile() || !file.canRead()) throw new IOException("电子书文件不存在或不可读取");
        if (!sLoaded) throw new IOException(NO_SO, sLoadError);
        long handle;
        try {
            handle = nativeOpen(file.getAbsolutePath());
        } catch (UnsatisfiedLinkError error) {
            throw new IOException(NO_SO, error);
        }
        if (handle == 0) throw new IOException("不是有效的 MOBI 文件");
        try {
            if (nativeIsEncrypted(handle)) throw new EncryptedDocumentException();
            String title = nativeTitle(handle);
            if (title == null || title.trim().isEmpty()) {
                String name = file.getName();
                int dot = name.lastIndexOf('.');
                return dot > 0 ? name.substring(0, dot) : name;
            }
            return title.trim();
        } finally {
            nativeClose(handle);
        }
    }

    /** 注入 {@link BookParser} 的适配器：MOBI 只产 title/text，不碰 spineIndex/sourceStart。 */
    public static final class Bridge implements BookParser.MobiDecoder {
        @Override public BookParser.BookContent read(File file) throws Exception {
            Result result = MobiNative.read(file);
            List<BookParser.Chapter> chapters = new ArrayList<>(result.chapters.size());
            for (MobiChapters.Chapter chapter : result.chapters) {
                chapters.add(new BookParser.Chapter(chapter.title, chapter.text));
            }
            return new BookParser.BookContent(result.title, chapters);
        }

        /**
         * 流式覆写：nativeOpen 句柄跨越整个逐段迭代，nativeIsEncrypted 前置拒绝，
         * 每个 rawml 段复用 read() 的 MobiChapters.split 语义，切出的章逐个交给 sink。
         * 正常结束、空段、split 异常、sink 异常都在 finally 里 nativeClose。
         */
        @Override public void stream(File file, BookParser.ChapterSink sink) throws Exception {
            if (file == null || !file.isFile() || !file.canRead()) throw new IOException("电子书文件不存在或不可读取");
            if (!sLoaded) throw new IOException(NO_SO, sLoadError);
            long handle;
            try {
                handle = nativeOpen(file.getAbsolutePath());
            } catch (UnsatisfiedLinkError error) {
                throw new IOException(NO_SO, error);
            }
            if (handle == 0) throw new IOException("不是有效的 MOBI 文件");
            try {
                if (nativeIsEncrypted(handle)) throw new EncryptedDocumentException();
                int segments = nativeChapterCount(handle);
                if (segments <= 0) throw new IOException(NOT_MOBI);
                int remaining = BookParser.MAX_CHAPTERS;
                int produced = 0;
                for (int i = 0; i < segments && remaining > 0; i++) {
                    String text = nativeChapterText(handle, i);
                    if (text == null || text.isEmpty()) continue;
                    List<MobiChapters.Chapter> part =
                            MobiChapters.split(text, remaining, BookParser.MAX_CHAPTER_CHARS);
                    for (MobiChapters.Chapter chapter : part) {
                        // 切分完全交给 MobiChapters.split，保留 (i/n) 后缀；MOBI 无 spine / 源偏移，
                        // 按契约 spineIndex 恒 -1，chapterKey 由索引器生成 "chapter:<globalOrdinal>"。
                        sink.accept(chapter.title, chapter.text, -1);
                        produced++;
                    }
                    remaining -= part.size();
                }
                if (produced == 0) throw new IOException(NOT_MOBI);
            } finally {
                nativeClose(handle);
            }
        }

        @Override public String validate(File file) throws Exception {
            return validateOnly(file);
        }
    }
}
