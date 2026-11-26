package org.openleaf.reader.core;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式 MOBI 章节索引：与 {@link EpubChapterIndexer} 同构，但章节来源是注入的 MOBI decoder。
 *
 * 解析、DRM 前置拒绝、切章（MobiChapters.split）全部由 BookParser.indexMobi → decoder.stream
 * 复用既有实现；本类只负责把回调给出的每个分片以 UTF-16LE 顺序写入私有缓存文件，并保留元数据。
 * 内存里不积累全书正文，只有每章的元数据（标题、偏移、长度、字符数）。
 *
 * 横切约束（与 EpubChapterIndexer 的差异，勿照抄模板）：
 * - 缓存正文一律 UTF-16LE，offset 为缓存中的 UTF-16LE 字节偏移；
 * - chapterKey 一律 "chapter:&lt;globalOrdinal&gt;"，ordinal 是全局产出序号（含切章分片）；
 * - MOBI 无 EPUB spine / 源偏移：spineIndex 恒 -1、sourceStart 恒 -1；
 * - ChapterMeta.byteSize 是 UTF-8 字节数（喂 Section 进度权重），
 *   ChapterMeta.cacheBytes 才是 UTF-16LE 缓存字节数（喂 offsets/lengths 与 load/head）；
 * - 切章完全交给 Bridge 的 stream（已用 MobiChapters.split），本类不再切章。
 */
public final class MobiChapterIndexer {
    private MobiChapterIndexer() { }

    /** 章节元数据：字段与 TextChapterIndexer.ChapterMeta / EpubChapterIndexer.ChapterMeta 同名同义。 */
    public static final class ChapterMeta {
        public final String title;
        /** "chapter:&lt;globalOrdinal&gt;"，与 LazyChapterBook 的 chapter: 分支一致。 */
        public final String chapterKey;
        /** MOBI 恒 -1。 */
        public final int spineIndex;
        /** MOBI 恒 -1。 */
        public final int sourceStart;
        /** UTF-16 code unit 数，等于 text.length()。 */
        public final int charCount;
        /** UTF-8 字节数，只喂 Section 进度权重，不能用于缓存定位。 */
        public final int byteSize;
        /** UTF-16LE 缓存字节数，供 LazyChapterBook 的 offsets/lengths 定位与 load/head 使用。 */
        public final int cacheBytes;
        /** 缓存文件中的 UTF-16LE 字节偏移。 */
        public final long offset;

        ChapterMeta(String title, String chapterKey, int spineIndex, int sourceStart,
                    int charCount, int byteSize, int cacheBytes, long offset) {
            this.title = title;
            this.chapterKey = chapterKey;
            this.spineIndex = spineIndex;
            this.sourceStart = sourceStart;
            this.charCount = charCount;
            this.byteSize = byteSize;
            this.cacheBytes = cacheBytes;
            this.offset = offset;
        }
    }

    /** 索引结果：书名 + 章节元数据 + UTF-16LE 正文缓存文件。 */
    public static final class Result {
        public final String title;
        public final List<ChapterMeta> chapters;
        public final File cacheFile;

        Result(String title, List<ChapterMeta> chapters, File cacheFile) {
            this.title = title;
            this.chapters = chapters;
            this.cacheFile = cacheFile;
        }
    }

    /**
     * 流式建立 MOBI 章节索引。caller 负责在 LazyChapterBook 关闭时删除 cacheFile。
     */
    public static Result index(File file, File cacheDir) throws Exception {
        if (file == null || !file.isFile() || !file.canRead()) throw new IOException("电子书文件不存在或不可读取");
        if (file.length() > BookParser.MAX_IMPORT_BYTES)
            throw new IOException("导入文件超过 " + BookParser.mib(BookParser.MAX_IMPORT_BYTES) + " 上限");
        if (cacheDir == null || (!cacheDir.isDirectory() && !cacheDir.mkdirs()))
            throw new IOException("无法创建章节缓存目录");

        File cache = File.createTempFile("openleaf-mobi-idx-", ".bin", cacheDir);
        List<ChapterMeta> chapters = new ArrayList<ChapterMeta>();
        String[] bookTitle = new String[1];
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(cache))) {
            bookTitle[0] = BookParser.indexMobi(file, new WriterSink(out, chapters));
        } catch (Exception failure) {
            if (cache.exists()) cache.delete();
            throw failure;
        }
        if (chapters.isEmpty()) { cache.delete(); throw new IOException("电子书内容为空"); }
        return new Result(bookTitle[0], chapters, cache);
    }

    /** 把每个分片顺序写进缓存，并同步记录元数据。 */
    private static final class WriterSink implements BookParser.ChapterSink {
        private final DataOutputStream out;
        private final List<ChapterMeta> chapters;
        private int globalOrdinal;

        WriterSink(DataOutputStream out, List<ChapterMeta> chapters) {
            this.out = out;
            this.chapters = chapters;
        }

        @Override
        public void accept(String title, String text, int spineIndex) throws IOException {
            byte[] encoded = text.getBytes(StandardCharsets.UTF_16LE);
            long offset = out.size();
            out.write(encoded);
            String key = "chapter:" + globalOrdinal;
            chapters.add(new ChapterMeta(title, key, -1, -1,
                    text.length(), text.getBytes(StandardCharsets.UTF_8).length, encoded.length, offset));
            globalOrdinal++;
        }
    }
}
