package org.openleaf.reader.core;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Index-first chapter book. The full parse happens once at open time, then chapter
 * bodies are written to a private UTF-16LE cache file and only a small LRU of
 * decoded chapters stays in memory.
 */
public final class LazyChapterBook implements AutoCloseable {
    private static final int MAX_CACHED_CHAPTERS = 3;

    public static final class Section {
        public final String title;
        public final String chapterKey;
        public final int spineIndex;
        public final int sourceStart;
        public final int charCount;
        public final int byteSize;
        Section(String title, String chapterKey, int spineIndex, int sourceStart, int charCount, int byteSize) {
            this.title = title;
            this.chapterKey = chapterKey;
            this.spineIndex = spineIndex;
            this.sourceStart = sourceStart;
            this.charCount = charCount;
            this.byteSize = byteSize;
        }
    }

    private final String title;
    private final Section[] sections;
    private final long[] offsets;
    private final int[] byteLengths;
    private final File cacheFile;
    private final RandomAccessFile reader;
    private final LinkedHashMap<Integer, String> loaded =
            new LinkedHashMap<Integer, String>(MAX_CACHED_CHAPTERS + 1, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                    return size() > MAX_CACHED_CHAPTERS;
                }
            };

    private LazyChapterBook(String title, Section[] sections, long[] offsets, int[] byteLengths,
                            File cacheFile, RandomAccessFile reader) {
        this.title = title;
        this.sections = sections;
        this.offsets = offsets;
        this.byteLengths = byteLengths;
        this.cacheFile = cacheFile;
        this.reader = reader;
    }

    public static LazyChapterBook open(File file, String format, File cacheDir) throws Exception {
        return open(file, format, cacheDir, ChapterRule.conservative());
    }

    /**
     * 注入章节规则开书。rule 只影响 TXT/MD 分章；EPUB/MOBI/其他格式分支与 0.26.0 完全一致。
     */
    public static LazyChapterBook open(File file, String format, File cacheDir, ChapterRule rule) throws Exception {
        return open(file, format, cacheDir, rule, null);
    }

    /**
     * P1-3 编码兜底：override 只透传给 TXT/MD 的 openText→TextChapterIndexer；
     * EPUB/MOBI/其他格式分支忽略 override，行为与 0.27.0 完全一致。
     */
    public static LazyChapterBook open(File file, String format, File cacheDir,
                                       ChapterRule rule, Charset override) throws Exception {
        return open(file, format, cacheDir, rule, override, 0L);
    }

    /**
     * P1-3 CPU 防护：matchBudgetMs 只透传给 TXT/MD 的自定义正则匹配路径；
     * 0 表示不设预算，既有 4/5 参数重载口径逐字不变。
     */
    public static LazyChapterBook open(File file, String format, File cacheDir,
                                       ChapterRule rule, Charset override,
                                       long matchBudgetMs) throws Exception {
        if (cacheDir == null || (!cacheDir.isDirectory() && !cacheDir.mkdirs()))
            throw new IOException("无法创建章节缓存目录");
        String kind = format == null ? "" : format.trim().toLowerCase(java.util.Locale.ROOT);
        if (kind.startsWith(".")) kind = kind.substring(1);
        if (kind.isEmpty()) {
            int dot = file.getName().lastIndexOf('.');
            kind = dot < 0 ? "" : file.getName().substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        }
        // TXT/MD 走流式索引：不再整本 decode 驻留内存，消除大书打开峰值
        if ("txt".equals(kind) || "md".equals(kind))
            return openText(file, cacheDir, rule == null ? ChapterRule.conservative() : rule,
                    override, matchBudgetMs);
        // EPUB 走流式索引：逐 spine 写缓存，消除 BookContent 全本驻留（新增唯一分流点）
        if ("epub".equals(kind)) return openEpub(file, cacheDir);
        // MOBI 走流式索引：逐段解码写 UTF-16LE 缓存，消除 BookContent 全本驻留（唯一分流点）
        if ("mobi".equals(kind) || "prc".equals(kind) || "azw".equals(kind) || "azw3".equals(kind) || "azw4".equals(kind)) return openMobi(file, kind, cacheDir);

        BookParser.BookContent parsed = BookParser.parse(file, format);
        File cache = File.createTempFile("openleaf-chapters-", ".bin", cacheDir);
        List<Section> meta = new ArrayList<Section>(parsed.chapters.size());
        long[] offsets = new long[parsed.chapters.size()];
        int[] lengths = new int[parsed.chapters.size()];
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(cache))) {
            for (int i = 0; i < parsed.chapters.size(); i++) {
                BookParser.Chapter chapter = parsed.chapters.get(i);
                byte[] encoded = chapter.text.getBytes(StandardCharsets.UTF_16LE);
                offsets[i] = out.size();
                out.write(encoded);
                lengths[i] = encoded.length;
                String key = chapter.spineIndex >= 0
                        ? "spine:" + chapter.spineIndex + ":" + i
                        : chapter.sourceStart >= 0 ? "src:" + chapter.sourceStart : "chapter:" + i;
                meta.add(new Section(chapter.title, key, chapter.spineIndex, chapter.sourceStart,
                        chapter.text.length(), chapter.text.getBytes(StandardCharsets.UTF_8).length));
            }
        } catch (Exception failure) {
            if (cache.exists()) cache.delete();
            throw failure;
        }
        RandomAccessFile access = new RandomAccessFile(cache, "r");
        return new LazyChapterBook(parsed.title, meta.toArray(new Section[0]), offsets, lengths, cache, access);
    }

    /** TXT/MD 流式打开：正文边读边写缓存，只留章节元数据。override 透传给索引器。 */
    private static LazyChapterBook openText(File file, File cacheDir, ChapterRule rule,
                                            Charset override, long matchBudgetMs) throws Exception {
        TextChapterIndexer.Result indexed = TextChapterIndexer.index(file, cacheDir, rule, override, matchBudgetMs);
        List<Section> meta = new ArrayList<>(indexed.chapters.size());
        long[] offsets = new long[indexed.chapters.size()];
        int[] lengths = new int[indexed.chapters.size()];
        for (int i = 0; i < indexed.chapters.size(); i++) {
            TextChapterIndexer.ChapterMeta chapter = indexed.chapters.get(i);
            offsets[i] = chapter.offset;
            lengths[i] = chapter.byteSize;
            meta.add(new Section(chapter.title, chapter.chapterKey, chapter.spineIndex,
                    chapter.sourceStart, chapter.charCount, chapter.byteSize));
        }
        RandomAccessFile access = new RandomAccessFile(indexed.cacheFile, "r");
        return new LazyChapterBook(indexed.title, meta.toArray(new Section[0]), offsets, lengths, indexed.cacheFile, access);
    }

    /** EPUB 流式打开：与 openText 的转换循环同构，只新增、不改既有方法。 */
    private static LazyChapterBook openEpub(File file, File cacheDir) throws Exception {
        EpubChapterIndexer.Result indexed = EpubChapterIndexer.index(file, cacheDir);
        List<Section> meta = new ArrayList<Section>(indexed.chapters.size());
        long[] offsets = new long[indexed.chapters.size()];
        int[] lengths = new int[indexed.chapters.size()];
        for (int i = 0; i < indexed.chapters.size(); i++) {
            EpubChapterIndexer.ChapterMeta chapter = indexed.chapters.get(i);
            offsets[i] = chapter.offset;
            // 缓存是 UTF-16LE：定位必须用 cacheBytes；byteSize 是 UTF-8，只喂 Section 进度权重。
            lengths[i] = chapter.cacheBytes;
            meta.add(new Section(chapter.title, chapter.chapterKey, chapter.spineIndex,
                    chapter.sourceStart, chapter.charCount, chapter.byteSize));
        }
        RandomAccessFile access;
        try {
            access = new RandomAccessFile(indexed.cacheFile, "r");
        } catch (IOException failure) {
            indexed.cacheFile.delete();
            throw failure;
        }
        return new LazyChapterBook(indexed.title, meta.toArray(new Section[0]), offsets, lengths, indexed.cacheFile, access);
    }

    /** MOBI 流式打开：与 openEpub 的转换循环同构，只新增、不改既有方法。 */
    private static LazyChapterBook openMobi(File file, String kind, File cacheDir) throws Exception {
        // 复用 BookParser 的判定口径：AZW4（Print Replica）明确不支持，分流处必须前置拒绝，
        // 不得因流式分流绕过原有 DRM/AZW4 防护（DRM 仍由 Bridge.stream 的 nativeIsEncrypted 前置拒绝）。
        if ("azw4".equals(kind))
            throw new IOException("该格式暂不支持（支持 MOBI / PRC / AZW / AZW3；AZW4 请先转换）。");
        MobiChapterIndexer.Result indexed = MobiChapterIndexer.index(file, cacheDir);
        List<Section> meta = new ArrayList<Section>(indexed.chapters.size());
        long[] offsets = new long[indexed.chapters.size()];
        int[] lengths = new int[indexed.chapters.size()];
        for (int i = 0; i < indexed.chapters.size(); i++) {
            MobiChapterIndexer.ChapterMeta chapter = indexed.chapters.get(i);
            offsets[i] = chapter.offset;
            // 缓存是 UTF-16LE：定位必须用 cacheBytes；byteSize 是 UTF-8，只喂 Section 进度权重。
            lengths[i] = chapter.cacheBytes;
            meta.add(new Section(chapter.title, chapter.chapterKey, chapter.spineIndex,
                    chapter.sourceStart, chapter.charCount, chapter.byteSize));
        }
        RandomAccessFile access;
        try {
            access = new RandomAccessFile(indexed.cacheFile, "r");
        } catch (IOException failure) {
            indexed.cacheFile.delete();
            throw failure;
        }
        return new LazyChapterBook(indexed.title, meta.toArray(new Section[0]), offsets, lengths, indexed.cacheFile, access);
    }

    public String title() { return title; }
    public int size() { return sections.length; }
    public Section meta(int index) { return sections[index]; }

    public synchronized String load(int index) throws IOException {
        if (index < 0 || index >= sections.length) throw new IndexOutOfBoundsException("章节序号无效");
        String cached = loaded.get(index);
        if (cached != null) return cached;
        byte[] bytes = new byte[byteLengths[index]];
        synchronized (reader) {
            reader.seek(offsets[index]);
            reader.readFully(bytes);
        }
        String text = new String(bytes, StandardCharsets.UTF_16LE);
        loaded.put(index, text);
        return text;
    }

    /**
     * 只读取该章前 maxChars 个字符（UTF-16 码元），不整章解码、不进入 LRU、不改缓存格式。
     * index 越界或 maxChars <= 0 返回 ""。
     */
    public synchronized String head(int index, int maxChars) {
        if (index < 0 || index >= sections.length || maxChars <= 0) return "";
        long wanted = (long) maxChars * 2L;
        int bytes = (int) Math.min((long) byteLengths[index], wanted);
        byte[] buf = new byte[bytes];
        try {
            synchronized (reader) {
                reader.seek(offsets[index]);
                reader.readFully(buf);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("读取章节前缀失败", failure);
        }
        // 逐 2 字节按 UTF_16LE 拼 char。不能用 new String(buf, UTF_16LE)：它会把截断处
        // 的孤立高代理替换成 U+FFFD，那样就无法按规则丢弃末尾高代理（避免半个代理对）。
        char[] chars = new char[bytes / 2];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) ((buf[2 * i] & 0xFF) | ((buf[2 * i + 1] & 0xFF) << 8));
        }
        int length = chars.length;
        if (length > 0 && chars[length - 1] >= '\uD800' && chars[length - 1] <= '\uDBFF') length--;
        return new String(chars, 0, length);
    }

    public synchronized void release(int index) { loaded.remove(index); }

    @Override public synchronized void close() {
        try { reader.close(); } catch (IOException ignored) { }
        if (cacheFile.exists()) cacheFile.delete();
        loaded.clear();
    }
}
