package org.openleaf.reader.core;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式 TXT 章节索引：打开大书时不再把整本正文解码进内存。
 *
 * 逐行读取 → 判定标题 → 章节正文直接以 UTF-16LE 写入私有缓存文件，
 * 内存里只保留每章的元数据（标题、偏移、长度、字符数）。这解决了
 * LazyChapterBook.open() 调用 BookParser.parse() 时的全本建索引内存峰值。
 *
 * 分章与切分语义与 BookParser.parseTxt 保持一致：
 * - 标题判定复用 TextChapterHeadings.isHeading（含 isolated 前后空行判定）
 * - 单章超过 MAX_CHAPTER_CHARS 时在段落/换行/空格处切分
 * - 编码检测：BOM（UTF-8/UTF-16LE/BE）→ 严格 UTF-8 → GB18030 回退
 */
public final class TextChapterIndexer {
    private TextChapterIndexer() { }

    /** 自定义正则单行匹配预算（毫秒）：在匹配执行路径中断灾难性回溯，0 表示不设预算。 */
    public static final long DEFAULT_RULE_MATCH_BUDGET_MS = 25L;

    /** 章节元数据：与 LazyChapterBook.Section 对应的纯数据。 */
    public static final class ChapterMeta {
        public final String title;
        public final String chapterKey;
        public final int spineIndex; // TXT 恒为 -1
        public final int sourceStart;
        public final int charCount;
        public final int byteSize;
        public final long offset;

        ChapterMeta(String title, String chapterKey, int sourceStart, int charCount, int byteSize, long offset) {
            this.title = title;
            this.chapterKey = chapterKey;
            this.spineIndex = -1;
            this.sourceStart = sourceStart;
            this.charCount = charCount;
            this.byteSize = byteSize;
            this.offset = offset;
        }
    }

    /** 索引结果：书名 + 章节元数据 + 缓存文件（二进制 UTF-16LE 正文）。 */
    public static final class Result {
        public final String title;
        public final List<ChapterMeta> chapters;
        public final File cacheFile;
        Result(String title, List<ChapterMeta> chapters, File cacheFile) {
            this.title = title; this.chapters = chapters; this.cacheFile = cacheFile;
        }
    }

    /**
     * 流式建立章节索引。caller 负责在 LazyChapterBook 关闭时删除 cacheFile。
     */
    public static Result index(File file, File cacheDir) throws Exception {
        return index(file, cacheDir, ChapterRule.conservative());
    }

    /**
     * 注入章节规则重建索引。rule 为 null 时按保守档处理；外围 guard、切分、缓存与锚点语义均不变，
     * 仅替换「哪些行算标题」这一处判定。
     */
    public static Result index(File file, File cacheDir, ChapterRule rule) throws Exception {
        return index(file, cacheDir, rule, null);
    }

    /**
     * P1-3 编码兜底：override 非 null 时跳过自动检测、按指定 Charset 解码；
     * override 为 null 时原路径逐字不变（AUTO 零回归）。
     */
    public static Result index(File file, File cacheDir, ChapterRule rule, Charset override) throws Exception {
        return index(file, cacheDir, rule, override, 0L);
    }

    /**
     * P1-3 CPU 防护：matchBudgetMs > 0 时给自定义正则的每行匹配加执行路径预算；
     * 传到判定入口的原始口径（rule 不含预算字段），不改变分章、缓存与锚点语义。
     */
    public static Result index(File file, File cacheDir, ChapterRule rule, Charset override,
                               long matchBudgetMs) throws Exception {
        final ChapterRule effective = rule == null ? ChapterRule.conservative() : rule;
        if (file == null || !file.isFile() || !file.canRead()) throw new IOException("电子书文件不存在或不可读取");
        if (file.length() > BookParser.MAX_IMPORT_BYTES)
            throw new IOException("导入文件超过 " + BookParser.mib(BookParser.MAX_IMPORT_BYTES) + " 上限");
        if (cacheDir == null || (!cacheDir.isDirectory() && !cacheDir.mkdirs()))
            throw new IOException("无法创建章节缓存目录");

        // AUTO 完全走原 detectCharset；用户强制时直接采用 override
        Charset charset = override != null ? override : detectCharset(file);
        File cache = File.createTempFile("openleaf-txt-idx-", ".bin", cacheDir);
        List<ChapterMeta> chapters = new ArrayList<>();
        String bookTitle = fileTitle(file);

        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(cache))) {
            scan(file, charset, out, chapters, bookTitle, effective, override != null, matchBudgetMs);
        } catch (Exception failure) {
            if (cache.exists()) cache.delete();
            throw failure;
        }
        if (chapters.isEmpty()) { cache.delete(); throw new IOException("电子书内容为空"); }
        return new Result(bookTitle, chapters, cache);
    }

    /** BOM 检测 + UTF-8/GB18030 可用性判定。 */
    private static Charset detectCharset(File file) throws IOException {
        byte[] prefix = new byte[3];
        int length = 0;
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while (length < prefix.length && (n = in.read(prefix, length, prefix.length - length)) > 0) length += n;
        }
        if (length >= 2 && ((prefix[0] & 255) == 0xFF && (prefix[1] & 255) == 0xFE)) return StandardCharsets.UTF_16LE;
        if (length >= 2 && ((prefix[0] & 255) == 0xFE && (prefix[1] & 255) == 0xFF)) return StandardCharsets.UTF_16BE;
        if (length >= 3 && ((prefix[0] & 255) == 0xEF && (prefix[1] & 255) == 0xBB && (prefix[2] & 255) == 0xBF))
            return StandardCharsets.UTF_8;
        // 无 BOM：尝试严格 UTF-8，失败回退 GB18030
        try {
            Charset utf8 = StandardCharsets.UTF_8;
            validateDecodable(file, utf8);
            return utf8;
        } catch (CharacterCodingException invalid) {
            return Charset.forName("GB18030");
        }
    }

    private static void validateDecodable(File file, Charset charset) throws IOException {
        char[] buffer = new char[16384];
        try (Reader reader = new InputStreamReader(new FileInputStream(file), charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            while (reader.read(buffer) != -1) { /* 只做校验，不保留内容 */ }
        }
    }

    /**
     * 逐行扫描：即时判定标题、累积章节正文并流式写出。
     * forced=false 沿用 0.27.0 的 skip 逻辑；forced=true 时 skip 由文件实际 BOM 决定，
     * 修掉"用户强制 UTF-16 但文件无 BOM 时无条件 skip=2 丢前 2 字节"的坑。
     */
    private static void scan(File file, Charset charset, DataOutputStream out,
                             List<ChapterMeta> chapters, String bookTitle, ChapterRule rule,
                             boolean forced, long matchBudgetMs) throws IOException {
        int skip = bomSkip(file, forced, charset);
        try (InputStream raw = new FileInputStream(file)) {
            for (int i = 0; i < skip; i++) if (raw.read() < 0) throw new IOException("TXT 编码标记不完整");
            try (Reader reader = new InputStreamReader(raw, charset)) {
                BufferedReader lines = new BufferedReader(reader, 16384);
                ChapterWriter writer = new ChapterWriter(out, chapters, bookTitle, rule, matchBudgetMs);
                String line;
                while ((line = lines.readLine()) != null) writer.consume(line);
                writer.finish();
            }
        }
    }

    /**
     * BOM 跳过字节数，与 scan 的原有逻辑逐字一致。readSample 复用它，确保预览与全量索引
     * 使用完全相同的解码口径（同一 charset + 同一 BOM 处理）。
     */
    private static int bomSkip(File file, boolean forced, Charset charset) throws IOException {
        int skip = 0;
        if (forced) {
            byte[] head = new byte[3];
            int headLength = 0;
            try (InputStream in = new FileInputStream(file)) {
                int n;
                while (headLength < head.length
                        && (n = in.read(head, headLength, head.length - headLength)) > 0) headLength += n;
            }
            boolean utf8Bom = headLength >= 3 && (head[0] & 255) == 0xEF
                    && (head[1] & 255) == 0xBB && (head[2] & 255) == 0xBF;
            boolean utf16Bom = headLength >= 2 && (((head[0] & 255) == 0xFF && (head[1] & 255) == 0xFE)
                    || ((head[0] & 255) == 0xFE && (head[1] & 255) == 0xFF));
            if (utf8Bom) skip = 3;
            else if (utf16Bom) skip = 2;
            else skip = 0; // 用户强制编码且无 BOM：从头按 charset 解码，绝不丢字节
        } else if (charset == StandardCharsets.UTF_16LE || charset == StandardCharsets.UTF_16BE) skip = 2;
        else if (charset == StandardCharsets.UTF_8) {
            byte[] head = new byte[3];
            try (InputStream in = new FileInputStream(file)) { in.read(head); }
            if ((head[0] & 255) == 0xEF && (head[1] & 255) == 0xBB && (head[2] & 255) == 0xBF) skip = 3;
        }
        return skip;
    }

    /**
     * P1-3 预览同源：按与 index() 相同的 charset 检测/BOM 处理从源文件解码前 maxChars 个字符，
     * 不建索引、不落缓存、不改变任何索引语义。UI 用它替换「旧索引缓存 head」作为样本。
     */
    public static String readSample(File file, Charset override, int maxChars) throws IOException {
        if (file == null || !file.isFile() || !file.canRead() || maxChars <= 0) return "";
        Charset charset = override != null ? override : detectCharset(file);
        int skip = bomSkip(file, override != null, charset);
        StringBuilder sample = new StringBuilder(Math.min(maxChars, 8192));
        try (InputStream raw = new FileInputStream(file)) {
            for (int i = 0; i < skip; i++) if (raw.read() < 0) return sample.toString();
            try (Reader reader = new InputStreamReader(raw, charset)) {
                char[] buffer = new char[Math.min(maxChars, 8192)];
                int read;
                while (sample.length() < maxChars
                        && (read = reader.read(buffer, 0, Math.min(buffer.length, maxChars - sample.length()))) > 0) {
                    sample.append(buffer, 0, read);
                }
            }
        }
        return sample.toString();
    }

    /** 章节写出器：有界缓冲（单章超过上限即切分），标题处结算上一章。 */
    private static final class ChapterWriter {
        private final DataOutputStream out;
        private final List<ChapterMeta> chapters;
        private final ChapterRule rule;
        private final StringBuilder content = new StringBuilder(BookParser.MAX_CHAPTER_CHARS + 256);
        private String chapterTitle;
        private int chapterSourceStart;
        private long chapterChars;
        private boolean previousBlank = true;
        private String pendingLine;      // 延迟一行判定 isolated（需要看下一行是否空白）
        private boolean pendingBlank;
        private char fence, fenceMarker;
        private int fenceWidth;
        private final long matchBudgetMs;

        ChapterWriter(DataOutputStream out, List<ChapterMeta> chapters, String bookTitle, ChapterRule rule,
                      long matchBudgetMs) {
            this.out = out; this.chapters = chapters; this.chapterTitle = bookTitle; this.rule = rule;
            this.matchBudgetMs = matchBudgetMs;
        }

        void consume(String rawLine) {
            String line = rawLine.replace("\r", "");
            boolean currentBlank = isBlank(line);
            if (pendingLine != null) process(pendingLine, previousBlank && currentBlank);
            previousBlank = pendingBlank;
            pendingLine = line;
            pendingBlank = currentBlank;
        }

        void finish() {
            if (pendingLine != null) process(pendingLine, previousBlank);
            flushChapter();
        }

        private void process(String line, boolean isolated) {
            String heading = trim(line);
            char marker = heading.isEmpty() ? 0 : heading.charAt(0);
            int markerWidth = 0;
            if (marker == '`' || marker == '~')
                while (markerWidth < heading.length() && heading.charAt(markerWidth) == marker) markerWidth++;
            boolean isHeading = fence == 0 && markerWidth < 3 && rightTrimLeft(heading).length() <= 512
                    && rule.isHeading(rightTrimLeft(heading), isolated, matchBudgetMs);
            if (markerWidth >= 3) {
                if (fence == 0) { fence = marker; fenceMarker = marker; fenceWidth = markerWidth; }
                else if (marker == fenceMarker && markerWidth >= fenceWidth && markerWidth == heading.length()) fence = 0;
            }
            if (isHeading) {
                flushChapter();
                chapterTitle = heading.isEmpty() ? "第" + (chapters.size() + 1) + "章" : bounded(heading);
                chapterSourceStart = lineStartOffset();
                chapterChars = 0;
            }
            appendWithSplit(line);
        }

        private void appendWithSplit(String line) {
            String withNewline = line + "\n";
            content.append(withNewline);
            chapterChars += withNewline.length();
            if (content.length() > BookParser.MAX_CHAPTER_CHARS) flushChapter();
        }

        private void flushChapter() {
            if (content.length() == 0) return;
            String text = content.toString();
            byte[] encoded = text.getBytes(StandardCharsets.UTF_16LE);
            try {
                long offset = out.size();
                out.write(encoded);
                String key = chapterSourceStart >= 0 ? "src:" + chapterSourceStart : "chapter:" + chapters.size();
                chapters.add(new ChapterMeta(chapterTitle, key, chapterSourceStart,
                        text.length(), encoded.length, offset));
            } catch (IOException e) { throw new IllegalStateException("写入章节缓存失败", e); }
            content.setLength(0);
        }

        private int lineStartOffset() { return chapterChars == 0 ? chapterSourceStart + (int) chapterChars : (int) chapterChars; }
        private static boolean isBlank(String line) { return trim(line).isEmpty(); }
        private static String trim(String s) {
            int a = 0, b = s.length();
            while (a < b && isWs(s.charAt(a))) a++;
            while (b > a && isWs(s.charAt(b - 1))) b--;
            return s.substring(a, b);
        }
        private static String rightTrimLeft(String s) { return trim(s); }
        private static boolean isWs(char c) {
            return Character.isWhitespace(c) || c == '\u00a0' || c == '\ufeff';
        }
        private static String bounded(String s) {
            String value = s.replaceAll("\\s+", " ");
            return value.length() <= 200 ? value : value.substring(0, 199) + "…";
        }
    }

    private static String fileTitle(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String title = dot > 0 ? name.substring(0, dot) : name;
        return title.isEmpty() ? "未命名电子书" : title;
    }
}
