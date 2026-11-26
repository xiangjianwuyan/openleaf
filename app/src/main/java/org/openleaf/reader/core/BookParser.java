package org.openleaf.reader.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/** Dependency-free, synchronous TXT/EPUB importer; call on a worker thread, not the UI thread. */
public final class BookParser {
    /** 单文件导入上限 512 MiB（>500MB）；TXT/MD 同步提升，导入校验改走流式路径避免 OOM。 */
    public static final long MAX_IMPORT_BYTES = 512L * 1024 * 1024;
    public static final long MAX_TXT_BYTES = 512L * 1024 * 1024;
    /** TXT 全量校验阈值：小于该值沿用精确解码校验，超过则走流式校验，避免大文件在导入阶段 OOM。 */
    private static final long MAX_TXT_FULL_VALIDATE_BYTES = 32L * 1024 * 1024;
    /** EPUB 全 ZIP 展开预算与重复引用预算分开，均 512 MiB。 */
    public static final long MAX_EPUB_EXPANDED_BYTES = 512L * 1024 * 1024;
    public static final long MAX_EPUB_REFERENCED_BYTES = 512L * 1024 * 1024;
    /** 单项上限 24 MiB：兼顾大幅扫描图，同时避免单章 XML 解析的堆放大。 */
    public static final long MAX_EPUB_ENTRY_BYTES = 24L * 1024 * 1024;
    public static final int MAX_CHAPTERS = 5000;
    public static final int MAX_CHAPTER_CHARS = 20000;
    public static final int MAX_ZIP_ENTRIES = 30000;

    /** 人类可读的 MiB 文案，避免错误提示写死数字。 */
    public static String mib(long bytes) { return (bytes / (1024L * 1024L)) + "MB"; }

    private static final Pattern CHINESE_HEADING = Pattern.compile(
            "^(?:第[零〇一二三四五六七八九十百千万两0-9０-９]+[章节回卷部篇集][\\s　:：、.．—-]*.{0,60}"
            + "|(?:序章|序言|楔子|引子|前言|后记|尾声|终章|番外)(?:[\\s　:：、.．—-].{0,60})?)$");
    private static final Pattern XML_ENCODING = Pattern.compile(
            "^\\s*<\\?xml\\s+[^?]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECLARATION = Pattern.compile("<!\\s*(?:DOCTYPE|ENTITY)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_HTML_DOCTYPE = Pattern.compile("<!DOCTYPE\\s+html\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STANDARD_XHTML_DOCTYPE = Pattern.compile(
            "<!DOCTYPE\\s+html\\s+(?:(?:PUBLIC\\s+(['\"])-//W3C//DTD XHTML (?:1\\.0 (?:Strict|Transitional|Frameset)|1\\.1)//EN\\1\\s+)|SYSTEM\\s+)"
            + "(['\"])https?://www\\.w3\\.org/TR/(?:xhtml1/DTD/xhtml1-(?:strict|transitional|frameset)|xhtml11/DTD/xhtml11)\\.dtd\\2\\s*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STANDARD_NCX_DOCTYPE = Pattern.compile(
            "<!DOCTYPE\\s+ncx\\s+(?:(?:PUBLIC\\s+(['\"])-//NISO//DTD ncx 2005-1//EN\\1\\s+)|SYSTEM\\s+)"
            + "(['\"])https?://www\\.daisy\\.org/z3986/2005/ncx-2005-1\\.dtd\\2\\s*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY = Pattern.compile("&([A-Za-z][A-Za-z0-9]+);");
    private static final Pattern VOID_ELEMENT = Pattern.compile("(?i)<(br|hr|img|meta|link|input)(?=[\\s/>])([^>]*?)(?<!/)>");
    private static final Map<String, String> ENTITIES = htmlEntities();

    private BookParser() { }

    /**
     * MOBI 解码注入点：core 保持纯 JVM，Android 层（{@code MobiNative.Bridge}）注入 native 实现。
     * core-tests 不注入时，MOBI 分支给出 §6 降级文案，不引用任何 Android / JNI 类型。
     */
    public interface MobiDecoder {
        /** 完整解析（加密文档应抛 IOException 且消息为「这本书受 DRM 保护，本应用不提供解密，已停止导入。」）。 */
        BookContent read(File file) throws Exception;
        /** 只校验并取书名，不解析正文。 */
        String validate(File file) throws Exception;

        /**
         * 流式解析：逐段切章后交给 sink，不在内存聚合全书章节列表。
         *
         * <p>default 实现回退到 read()+sink.accept，保证既有实现（含 core-tests 的 Fake）
         * 源码/二进制兼容；真正去聚合由 Android 层 {@code MobiNative.Bridge} 覆写实现。</p>
         *
         * <p>sink 的 spineIndex 参数对 MOBI 恒为 -1，章节 key 一律 "chapter:&lt;ordinal&gt;"。</p>
         */
        default void stream(File file, ChapterSink sink) throws Exception {
            BookContent content = read(file);
            for (Chapter chapter : content.chapters) {
                sink.accept(chapter.title, chapter.text, chapter.spineIndex);
            }
        }
    }

    /**
     * EPUB 流式消费回调：由 {@link #indexEpub(File, ChapterSink)} 按 spine 顺序驱动。
     * 只透传切分后的源数据（标题、正文、spineIndex）；缓存编码与元数据由实现方负责。
     */
    public interface ChapterSink {
        void accept(String title, String text, int spineIndex) throws IOException;
    }

    private static volatile MobiDecoder mobiDecoder;

    public static void setMobiDecoder(MobiDecoder decoder) { mobiDecoder = decoder; }

    private static MobiDecoder requireMobiDecoder() throws IOException {
        MobiDecoder decoder = mobiDecoder;
        if (decoder == null) {
            throw new IOException("本机缺少 MOBI 解析组件，暂时无法打开；可用 Calibre 转为 EPUB 后导入。");
        }
        return decoder;
    }

    /** MOBI / PRC / AZW / AZW3 走 native 桥；AZW4（Print Replica）明确不支持。 */
    private static boolean isMobiKind(String kind) {
        return "mobi".equals(kind) || "prc".equals(kind) || "azw".equals(kind) || "azw3".equals(kind)
                || "azw4".equals(kind);
    }

    private static void rejectAzW4(String kind) throws IOException {
        if ("azw4".equals(kind)) {
            throw new IOException("该格式暂不支持（支持 MOBI / PRC / AZW / AZW3；AZW4 请先转换）。");
        }
    }

    public static final class BookContent {
        public final String title;
        public final List<Chapter> chapters;

        public BookContent(String title, List<Chapter> chapters) {
            if (title == null || chapters == null) throw new NullPointerException();
            this.title = title;
            this.chapters = Collections.unmodifiableList(new ArrayList<Chapter>(chapters));
        }
    }

    public static final class Chapter {
        public final String title;
        public final String text;
        /** Zero-based EPUB spine index, or -1 when there is no EPUB spine. */
        public final int spineIndex;
        /** Original line-normalized source offset for TXT/MD; -1 otherwise. */
        public final int sourceStart;

        public Chapter(String title, String text) {
            this(title, text, -1);
        }

        private Chapter(String title, String text, int spineIndex) {
            this(title, text, spineIndex, -1);
        }

        private Chapter(String title, String text, int spineIndex, int sourceStart) {
            if (title == null || text == null) throw new NullPointerException();
            this.title = title;
            this.text = text;
            this.spineIndex = spineIndex;
            this.sourceStart = sourceStart;
        }
    }

    /** format accepts txt/epub, optionally prefixed by a dot; null/blank infers the extension. */
    public static BookContent parse(File file, String format) throws Exception {
        if (file == null || !file.isFile() || !file.canRead()) throw new IOException("电子书文件不存在或不可读取");
        if (file.length() > MAX_IMPORT_BYTES) throw new IOException("导入文件超过 " + mib(MAX_IMPORT_BYTES) + " 上限");
        String kind = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (kind.isEmpty()) {
            int dot = file.getName().lastIndexOf('.');
            kind = dot < 0 ? "" : file.getName().substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        if (kind.startsWith(".")) kind = kind.substring(1);
        if ("txt".equals(kind) || "md".equals(kind)) return parseTxt(file, false);
        if ("epub".equals(kind)) return parseEpub(file, null);
        if (isMobiKind(kind)) {
            rejectAzW4(kind);
            return requireMobiDecoder().read(file);
        }
        throw new IOException("不支持的电子书格式：" + kind);
    }

    /** Import-time validation without retaining a second copy of TXT chapter bodies.
     * The check callback may throw a runtime cancellation exception; it is never swallowed.
     * EPUB keeps the existing full validation, including archive and XML safety limits. */
    public static String validateForImport(File file, String format, Runnable check) throws IOException {
        checkpoint(check);
        if (file == null || !file.isFile() || !file.canRead()) throw new IOException("电子书文件不存在或不可读取");
        if (file.length() > MAX_IMPORT_BYTES) throw new IOException("导入文件超过 " + mib(MAX_IMPORT_BYTES) + " 上限");
        String kind = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (kind.isEmpty()) {
            int dot = file.getName().lastIndexOf('.');
            kind = dot < 0 ? "" : file.getName().substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        if (kind.startsWith(".")) kind = kind.substring(1);
        if ("txt".equals(kind) || "md".equals(kind)) {
            validateText(file, check);
            checkpoint(check);
            return fileTitle(file);
        }
        if (isMobiKind(kind)) {
            rejectAzW4(kind);
            String title;
            try {
                title = requireMobiDecoder().validate(file);
            } catch (IOException | RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException(error.getMessage(), error);
            }
            checkpoint(check);
            return title == null || title.trim().isEmpty() ? fileTitle(file) : title.trim();
        }
        if (!"epub".equals(kind)) throw new IOException("不支持的电子书格式：" + kind);
        try {
            // 轻量路径：只做 ZIP 安全校验 + container/OPF 元数据，不构建整本章节列表，
            // 避免 500MB EPUB 在导入校验阶段把正文堆进内存。
            String title = validateEpubMeta(file, check);
            checkpoint(check);
            return title;
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    /** 只检查 EPUB 容器安全与 OPF 元数据，返回标题；不解析正文，适合大文件导入校验。 */
    private static String validateEpubMeta(File file, Runnable check) throws Exception {
        try (Archive archive = new Archive(file, check)) {
            for (String path : archive.entries.keySet()) {
                checkpoint(check);
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.equals("meta-inf/rights.xml") || lower.equals("meta-inf/license.lcpl"))
                    throw new IOException("此 EPUB 含 DRM 版权保护，暂不支持解密");
                if (lower.equals("meta-inf/encryption.xml")) {
                    EncryptionHandler encryption = new EncryptionHandler();
                    parseXml(archive.read(path), encryption);
                    if (encryption.unsupported || encryption.methods == 0)
                        throw new IOException("此 EPUB 含 DRM 或不支持的加密内容，暂不支持解密");
                }
            }
            ContainerHandler container = new ContainerHandler();
            parseXml(archive.read("META-INF/container.xml"), container);
            if (container.rootfile == null) throw new IOException("EPUB container.xml 缺少 OPF 路径");
            PackageHandler opf = new PackageHandler();
            parseXml(archive.read(resolvePath("", container.rootfile)), opf);
            if (opf.spine.isEmpty()) throw new IOException("EPUB 缺少阅读顺序 spine");
            for (String id : opf.spine) {
                checkpoint(check);
                Item item = opf.manifest.get(id);
                if (item == null) throw new IOException("EPUB spine 引用了缺失的 manifest 项：" + id);
                if (!"application/xhtml+xml".equals(item.mediaType) && !"text/html".equals(item.mediaType))
                    throw new IOException("EPUB spine 含不支持的正文类型：" + item.mediaType);
            }
            String title = trimWhitespace(opf.title.toString());
            return title.isEmpty() ? fileTitle(file) : boundedTitle(title);
        }
    }

    private static void checkpoint(Runnable check) {
        if (check != null) check.run();
    }

    private static void validateText(File file, Runnable check) throws IOException {
        if (file.length() > MAX_TXT_BYTES) throw new IOException("TXT 文件超过 " + mib(MAX_TXT_BYTES) + " 上限");
        // 大 TXT 若沿用全量解码，512MiB 文本会在 StringBuilder 中放大为约 1GiB 堆，
        // Android 导入阶段必然 OOM；超过阈值改走流式校验，只保留有界章节窗口。
        if (file.length() > MAX_TXT_FULL_VALIDATE_BYTES) {
            validateTextStream(file, check);
            return;
        }
        byte[] prefix = new byte[3];
        int length = 0;
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while (length < prefix.length && (n = in.read(prefix, length, prefix.length - length)) > 0) length += n;
        }
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (length >= 2 && hasPrefix(prefix, 0xFF, 0xFE)) { charset = StandardCharsets.UTF_16LE; skip = 2; }
        else if (length >= 2 && hasPrefix(prefix, 0xFE, 0xFF)) { charset = StandardCharsets.UTF_16BE; skip = 2; }
        else if (length >= 3 && hasPrefix(prefix, 0xEF, 0xBB, 0xBF)) skip = 3;
        StringBuilder text;
        try {
            text = decodeTextForValidation(file, charset, skip, check);
        } catch (CharacterCodingException invalid) {
            if (skip == 3) throw new IOException("TXT 的 UTF-8 BOM 与内容编码不一致", invalid);
            if (skip != 0) throw invalid;
            try { text = decodeTextForValidation(file, Charset.forName("GB18030"), 0, check); }
            catch (CharacterCodingException legacy) { throw new IOException("TXT 无法按 UTF-8 或 GB18030 解码", legacy); }
        }
        // Work on source ranges, not substring copies or Chapter objects. This deliberately
        // retains one decoded buffer: reader offsets and chapter-count limits remain identical.
        int position = 0, chapterStart = 0, count = 0;
        boolean previousBlank = true;
        char fence = 0;
        int fenceWidth = 0;
        while (position < text.length()) {
            checkpoint(check);
            int end = position;
            while (end < text.length() && text.charAt(end) != '\n') {
                if ((end & 16383) == 0) checkpoint(check);
                end++;
            }
            int left = position, right = end;
            while (left < right && isWhitespace(text.charAt(left))) left++;
            while (right > left && isWhitespace(text.charAt(right - 1))) right--;
            boolean isolated = previousBlank;
            for (int next = end + 1; isolated && next < text.length() && text.charAt(next) != '\n'; next++) {
                if ((next & 16383) == 0) checkpoint(check);
                if (!isWhitespace(text.charAt(next))) isolated = false;
            }
            char marker = left == right ? 0 : text.charAt(left);
            int markerWidth = 0;
            if (marker == '`' || marker == '~') {
                while (left + markerWidth < right && text.charAt(left + markerWidth) == marker) markerWidth++;
            }
            boolean heading = fence == 0 && markerWidth < 3 && right - left <= 512
                    && TextChapterHeadings.isHeading(text.substring(left, right), isolated);
            if (markerWidth >= 3) {
                if (fence == 0) { fence = marker; fenceWidth = markerWidth; }
                else if (marker == fence && markerWidth >= fenceWidth && markerWidth == right - left) fence = 0;
            }
            if (heading) {
                count = countTextChunks(text, chapterStart, position, count, check);
                chapterStart = position;
            }
            previousBlank = left == right;
            position = end + 1;
        }
        count = countTextChunks(text, chapterStart, text.length(), count, check);
        if (count == 0) throw new IOException("电子书内容为空");
    }

    private static StringBuilder decodeTextForValidation(File file, Charset charset, int skip, Runnable check)
            throws IOException {
        int capacity = (int) Math.min(MAX_TXT_BYTES, Math.max(16, file.length()));
        StringBuilder text = new StringBuilder(capacity);
        try (InputStream raw = new FileInputStream(file)) {
            for (int i = 0; i < skip; i++) if (raw.read() < 0) throw new IOException("TXT 编码标记不完整");
            // Bound actual bytes as well as reported length, including files changed during validation.
            InputStream limited = new java.io.FilterInputStream(raw) {
                private long total = skip;
                private void used(int n) throws IOException {
                    checkpoint(check);
                    if (n > 0 && (total += n) > MAX_TXT_BYTES) throw new IOException("TXT 文件超过 " + mib(MAX_TXT_BYTES) + " 上限");
                }
                @Override public int read() throws IOException { int n = super.read(); used(n < 0 ? 0 : 1); return n; }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = in.read(b, off, len); used(n); return n;
                }
            };
            try (Reader reader = new InputStreamReader(limited, charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
                char[] buffer = new char[16384];
                int n;
                boolean cr = false;
                while ((n = reader.read(buffer)) != -1) {
                    checkpoint(check);
                    for (int i = 0; i < n; i++) {
                        char c = buffer[i];
                        if (cr && c == '\n') { cr = false; continue; }
                        cr = c == '\r';
                        text.append(cr ? '\n' : c);
                    }
                }
            }
        }
        return text;
    }

    /**
     * 大文件流式 TXT 校验：逐块解码、逐行推进有界章节窗口，不保留整本解码文本。
     * 章节切分与上限判断复用 countTextChunks 的同一套逻辑，保持与小文件精确校验一致的语义。
     */
    private static void validateTextStream(File file, Runnable check) throws IOException {
        byte[] prefix = new byte[3];
        int prefixLength = 0;
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while (prefixLength < prefix.length
                    && (n = in.read(prefix, prefixLength, prefix.length - prefixLength)) > 0) prefixLength += n;
        }
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (prefixLength >= 2 && hasPrefix(prefix, 0xFF, 0xFE)) { charset = StandardCharsets.UTF_16LE; skip = 2; }
        else if (prefixLength >= 2 && hasPrefix(prefix, 0xFE, 0xFF)) { charset = StandardCharsets.UTF_16BE; skip = 2; }
        else if (prefixLength >= 3 && hasPrefix(prefix, 0xEF, 0xBB, 0xBF)) skip = 3;
        try {
            streamTextChapters(file, charset, skip, check);
        } catch (CharacterCodingException invalid) {
            if (skip == 3) throw new IOException("TXT 的 UTF-8 BOM 与内容编码不一致", invalid);
            if (skip != 0) throw invalid;
            try {
                streamTextChapters(file, Charset.forName("GB18030"), 0, check);
            } catch (CharacterCodingException legacy) {
                throw new IOException("TXT 无法按 UTF-8 或 GB18030 解码", legacy);
            }
        }
    }

    /** 流式解码并逐行累计章节数；fence 状态与 parseTxt / validateText 保持一致。 */
    private static void streamTextChapters(File file, Charset charset, int skip, Runnable check) throws IOException {
        ChunkCounter counter = new ChunkCounter();
        int[] fence = new int[2]; // [0]=fence marker, [1]=fence width
        try (InputStream raw = new FileInputStream(file)) {
            for (int i = 0; i < skip; i++) if (raw.read() < 0) throw new IOException("TXT 编码标记不完整");
            InputStream limited = new java.io.FilterInputStream(raw) {
                private long total = skip;
                private void used(int n) throws IOException {
                    checkpoint(check);
                    if (n > 0 && (total += n) > MAX_TXT_BYTES)
                        throw new IOException("TXT 文件超过 " + mib(MAX_TXT_BYTES) + " 上限");
                }
                @Override public int read() throws IOException { int n = super.read(); used(n < 0 ? 0 : 1); return n; }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = in.read(b, off, len); used(n); return n;
                }
            };
            try (Reader reader = new InputStreamReader(limited, charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
                char[] buffer = new char[16384];
                // 行缓冲必须有界：超长单行（损坏/无换行文本）不可能是标题（标题上限 512 字符），
                // 达到上限后直接流式喂入章节窗口，避免把整行堆进内存。
                final int lineLimit = Math.max(512, MAX_CHAPTER_CHARS);
                StringBuilder line = new StringBuilder(256);
                boolean lineFlushed = false; // 本行已因过长直接入窗，只等换行收尾
                String pendingLine = null;
                boolean previousBlank = true; // 上上行是否全空白
                boolean pendingBlank = false; // 上一行是否全空白
                boolean cr = false;
                int n;
                while ((n = reader.read(buffer)) != -1) {
                    checkpoint(check);
                    for (int i = 0; i < n; i++) {
                        char c = buffer[i];
                        if (cr && c == '\n') { cr = false; continue; } // CRLF 只保留一个换行
                        cr = c == '\r';
                        if (cr) c = '\n';
                        if (c == '\n') {
                            if (lineFlushed) {
                                // 超长行整体已入窗，此处只回补其结尾换行
                                counter.append('\n', check);
                                previousBlank = false;
                                pendingBlank = false;
                                pendingLine = null;
                                lineFlushed = false;
                                line.setLength(0);
                                continue;
                            }
                            boolean currentBlank = isBlankLine(line);
                            if (pendingLine != null)
                                processValidationLine(pendingLine, previousBlank && currentBlank, counter, fence, check);
                            previousBlank = pendingBlank;
                            pendingLine = line.toString();
                            pendingBlank = currentBlank;
                            line.setLength(0);
                        } else if (lineFlushed) {
                            counter.append(c, check);
                        } else {
                            line.append(c);
                            if (line.length() > lineLimit) {
                                // 超长行不可能是标题：先结算上一行，再把已读内容流式入窗
                                if (pendingLine != null) {
                                    processValidationLine(pendingLine, previousBlank, counter, fence, check);
                                    pendingLine = null;
                                }
                                for (int j = 0; j < line.length(); j++) counter.append(line.charAt(j), check);
                                line.setLength(0);
                                lineFlushed = true;
                            }
                        }
                    }
                }
                if (lineFlushed) {
                    counter.append('\n', check);
                } else {
                    if (pendingLine != null) processValidationLine(pendingLine, previousBlank, counter, fence, check);
                    if (line.length() > 0) processValidationLine(line.toString(), pendingBlank, counter, fence, check);
                }
            }
        }
        counter.finish(check);
        if (counter.count() == 0) throw new IOException("电子书内容为空");
    }

    private static boolean isBlankLine(CharSequence line) {
        for (int i = 0; i < line.length(); i++) if (!isWhitespace(line.charAt(i))) return false;
        return true;
    }

    /** 与 validateText 的逐行判定同源：heading 处结算前一段，再把本行正文喂入窗口。 */
    private static void processValidationLine(String line, boolean isolated, ChunkCounter counter,
                                              int[] fence, Runnable check) throws IOException {
        int left = 0, right = line.length();
        while (left < right && isWhitespace(line.charAt(left))) left++;
        while (right > left && isWhitespace(line.charAt(right - 1))) right--;
        char marker = left == right ? 0 : line.charAt(left);
        int markerWidth = 0;
        if (marker == '`' || marker == '~') {
            while (left + markerWidth < right && line.charAt(left + markerWidth) == marker) markerWidth++;
        }
        boolean heading = fence[1] == 0 && markerWidth < 3 && right - left <= 512
                && TextChapterHeadings.isHeading(line.substring(left, right), isolated);
        if (markerWidth >= 3) {
            if (fence[1] == 0) { fence[0] = marker; fence[1] = markerWidth; }
            else if (marker == fence[0] && markerWidth >= fence[1] && markerWidth == right - left) fence[1] = 0;
        }
        if (heading) counter.finish(check);
        for (int i = 0; i < line.length(); i++) counter.append(line.charAt(i), check);
        counter.append('\n', check);
    }

    /** 有界章节窗口：按 MAX_CHAPTER_CHARS 增量结算，复用 countTextChunks 的计数语义。 */
    private static final class ChunkCounter {
        private final StringBuilder pending = new StringBuilder(MAX_CHAPTER_CHARS + 4096);
        private final int[] state = new int[1];
        private boolean skipLeading = true;

        void append(char c, Runnable check) throws IOException {
            if (skipLeading) {
                if (isWhitespace(c)) return;
                skipLeading = false;
            }
            pending.append(c);
            while (pending.length() > MAX_CHAPTER_CHARS + 1) {
                int end = countOneChunk(pending, 0, pending.length(), state, check);
                pending.delete(0, end);
            }
        }

        void finish(Runnable check) throws IOException {
            int count = countTextChunks(pending, 0, pending.length(), state[0], check);
            state[0] = count;
            pending.setLength(0);
            skipLeading = true;
        }

        int count() { return state[0]; }
    }

    private static int countTextChunks(CharSequence text, int start, int stop, int count, Runnable check)
            throws IOException {
        while (start < stop && isWhitespace(text.charAt(start))) { if ((start & 16383) == 0) checkpoint(check); start++; }
        while (stop > start && isWhitespace(text.charAt(stop - 1))) { if ((stop & 16383) == 0) checkpoint(check); stop--; }
        int[] state = {count};
        while (start < stop) start = countOneChunk(text, start, stop, state, check);
        return state[0];
    }

    /** 单次切块：返回下一块起点；state[0] 累计非空块数，供全量与流式校验共用同一套边界规则。 */
    private static int countOneChunk(CharSequence text, int start, int stop, int[] state, Runnable check)
            throws IOException {
        checkpoint(check);
        int end = Math.min(start + MAX_CHAPTER_CHARS, stop);
        if (end < stop) {
            int minimum = start + MAX_CHAPTER_CHARS / 2;
            int paragraph = -1, newline = -1, space = -1;
            for (int i = end - 1; i >= minimum; i--) {
                char c = text.charAt(i);
                if (c == '\n') {
                    if (newline < 0) newline = i;
                    if (i > minimum && text.charAt(i - 1) == '\n') { paragraph = i; break; }
                } else if (c == ' ' && space < 0) space = i;
            }
            if (paragraph >= 0) end = paragraph + 1;
            else if (newline >= 0) end = newline + 1;
            else if (space >= 0) end = space + 1;
            if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        }
        boolean content = false;
        for (int i = start; i < end && !content; i++) content = !isWhitespace(text.charAt(i));
        if (content && ++state[0] > MAX_CHAPTERS) throw new IOException("电子书章节数超过 " + MAX_CHAPTERS + " 上限");
        return end;
    }

    /** Reconstruct the old text partition for migration of pre-1.0 reading positions. */
    public static BookContent parseLegacyText(File file) throws IOException {
        return parseTxt(file, true);
    }

    private static BookContent parseTxt(File file, boolean legacy) throws IOException {
        if (file.length() > MAX_TXT_BYTES) throw new IOException("TXT 文件超过 " + mib(MAX_TXT_BYTES) + " 上限");
        byte[] bytes;
        try (InputStream in = new FileInputStream(file)) {
            bytes = readLimited(in, MAX_TXT_BYTES, "TXT 文件超过 " + mib(MAX_TXT_BYTES) + " 上限", null);
        }
        String text;
        if (hasPrefix(bytes, 0xFF, 0xFE)) {
            text = decode(bytes, 2, StandardCharsets.UTF_16LE);
        } else if (hasPrefix(bytes, 0xFE, 0xFF)) {
            text = decode(bytes, 2, StandardCharsets.UTF_16BE);
        } else {
            int offset = hasPrefix(bytes, 0xEF, 0xBB, 0xBF) ? 3 : 0;
            try {
                text = decode(bytes, offset, StandardCharsets.UTF_8);
            } catch (CharacterCodingException invalidUtf8) {
                // A UTF-8 BOM is authoritative; without one, legacy Chinese text may be GB18030.
                if (offset != 0) throw new IOException("TXT 的 UTF-8 BOM 与内容编码不一致", invalidUtf8);
                try {
                    text = decode(bytes, 0, Charset.forName("GB18030"));
                } catch (CharacterCodingException invalidLegacy) {
                    throw new IOException("TXT 无法按 UTF-8 或 GB18030 解码", invalidLegacy);
                }
            }
        }
        text = normalizeLines(text);
        String bookTitle = fileTitle(file);
        List<Chapter> chapters = new ArrayList<Chapter>();
        String chapterTitle = bookTitle;
        StringBuilder content = new StringBuilder();
        int position = 0, chapterStart = 0;
        boolean previousBlank = true;
        char fence = 0;
        int fenceWidth = 0;
        while (position < text.length()) {
            int end = text.indexOf('\n', position);
            if (end < 0) end = text.length();
            String line = text.substring(position, end);
            String heading = trimWhitespace(line);
            // Loose headings require visual separation; explicit headings need no blank lines.
            boolean isolated = previousBlank;
            for (int next = end + 1; isolated && next < text.length() && text.charAt(next) != '\n'; next++)
                if (!isWhitespace(text.charAt(next))) isolated = false;
            boolean headingLine;
            if (legacy) {
                headingLine = heading.length() <= 80 && CHINESE_HEADING.matcher(heading).matches();
            } else {
                int markerWidth = 0;
                char marker = heading.isEmpty() ? 0 : heading.charAt(0);
                if (marker == '`' || marker == '~') {
                    while (markerWidth < heading.length() && heading.charAt(markerWidth) == marker) markerWidth++;
                }
                headingLine = fence == 0 && markerWidth < 3 && TextChapterHeadings.isHeading(heading, isolated);
                if (markerWidth >= 3) {
                    if (fence == 0) { fence = marker; fenceWidth = markerWidth; }
                    else if (marker == fence && markerWidth >= fenceWidth && markerWidth == heading.length()) fence = 0;
                }
            }
            if (headingLine) {
                appendTextChapters(chapters, chapterTitle, content.toString(), chapterStart);
                chapterStart = position;
                chapterTitle = heading;
                content.setLength(0);
            }
            previousBlank = heading.isEmpty();
            // Retain headings in chapter text, including otherwise-empty chapter headings.
            content.append(line);
            if (end < text.length()) content.append('\n');
            position = end + 1;
        }
        appendTextChapters(chapters, chapterTitle, content.toString(), chapterStart);
        if (chapters.isEmpty()) throw new IOException("电子书内容为空");
        return new BookContent(bookTitle, chapters);
    }

    private static BookContent parseEpub(File file, Runnable check) throws Exception {
        try (Archive archive = new Archive(file, check)) {
            for (String path : archive.entries.keySet()) {
                checkpoint(check);
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.equals("meta-inf/rights.xml") || lower.equals("meta-inf/license.lcpl")) {
                    throw new IOException("此 EPUB 含 DRM 版权保护，暂不支持解密");
                }
                if (lower.equals("meta-inf/encryption.xml")) {
                    EncryptionHandler encryption = new EncryptionHandler();
                    parseXml(archive.read(path), encryption);
                    if (encryption.unsupported || encryption.methods == 0) {
                        throw new IOException("此 EPUB 含 DRM 或不支持的加密内容，暂不支持解密");
                    }
                }
            }
            ContainerHandler container = new ContainerHandler();
            parseXml(archive.read("META-INF/container.xml"), container);
            if (container.rootfile == null) throw new IOException("EPUB container.xml 缺少 OPF 路径");
            String opfPath = resolvePath("", container.rootfile);
            PackageHandler opf = new PackageHandler();
            parseXml(archive.read(opfPath), opf);
            if (opf.spine.isEmpty()) throw new IOException("EPUB 缺少阅读顺序 spine");
            String base = directoryOf(opfPath);
            Map<String, String> labels = tocLabels(archive, opf, base);
            List<Chapter> chapters = new ArrayList<Chapter>();
            int itemNumber = 0;
            for (String id : opf.spine) {
                checkpoint(check);
                Item item = opf.manifest.get(id);
                if (item == null) throw new IOException("EPUB spine 引用了缺失的 manifest 项：" + id);
                if (!"application/xhtml+xml".equals(item.mediaType) && !"text/html".equals(item.mediaType)) {
                    throw new IOException("EPUB spine 含不支持的正文类型：" + item.mediaType);
                }
                String path = resolvePath(base, item.href);
                HtmlHandler html = new HtmlHandler();
                parseXml(archive.read(path), html);
                itemNumber++;
                String title = chapterTitle(labels, path, html, itemNumber - 1);
                String plain=html.plainText();
                if(plain.isEmpty()&&hasLocalIllustration(archive,directoryOf(path),html.imageReferences,base,opf.manifest))
                    plain="[图文章节：请使用图文模式查看插图]";
                appendChapters(chapters, title, plain, itemNumber - 1);
            }
            if (chapters.isEmpty()) throw new IOException("电子书内容为空");
            String title = trimWhitespace(opf.title.toString());
            return new BookContent(title.isEmpty() ? fileTitle(file) : boundedTitle(title), chapters);
        }
    }

    /**
     * 与 {@link #parseEpub(File, Runnable)} 同源的 EPUB 流式入口：DRM 检查、Archive 安全校验、
     * container/OPF 解析、tocLabels、标题决策、图文章节占位与 appendChapters 切分全部复用既有设施，
     * 唯一差异是把每个分片交给 {@link ChapterSink}，不构建 BookContent、不积累全书章节列表。
     *
     * @return 书名，与 parseEpub 的 BookContent.title 完全同源
     */
    public static String indexEpub(File file, ChapterSink sink) throws Exception {
        if (file == null || !file.isFile() || !file.canRead()) throw new IOException("电子书文件不存在或不可读取");
        if (file.length() > MAX_IMPORT_BYTES) throw new IOException("导入文件超过 " + mib(MAX_IMPORT_BYTES) + " 上限");
        if (sink == null) throw new NullPointerException("sink");
        try (Archive archive = new Archive(file)) {
            for (String path : archive.entries.keySet()) {
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.equals("meta-inf/rights.xml") || lower.equals("meta-inf/license.lcpl")) {
                    throw new IOException("此 EPUB 含 DRM 版权保护，暂不支持解密");
                }
                if (lower.equals("meta-inf/encryption.xml")) {
                    EncryptionHandler encryption = new EncryptionHandler();
                    parseXml(archive.read(path), encryption);
                    if (encryption.unsupported || encryption.methods == 0) {
                        throw new IOException("此 EPUB 含 DRM 或不支持的加密内容，暂不支持解密");
                    }
                }
            }
            ContainerHandler container = new ContainerHandler();
            parseXml(archive.read("META-INF/container.xml"), container);
            if (container.rootfile == null) throw new IOException("EPUB container.xml 缺少 OPF 路径");
            String opfPath = resolvePath("", container.rootfile);
            PackageHandler opf = new PackageHandler();
            parseXml(archive.read(opfPath), opf);
            if (opf.spine.isEmpty()) throw new IOException("EPUB 缺少阅读顺序 spine");
            String base = directoryOf(opfPath);
            Map<String, String> labels = tocLabels(archive, opf, base);
            List<Chapter> parts = new ArrayList<Chapter>();
            int itemNumber = 0;
            int produced = 0;
            for (String id : opf.spine) {
                Item item = opf.manifest.get(id);
                if (item == null) throw new IOException("EPUB spine 引用了缺失的 manifest 项：" + id);
                if (!"application/xhtml+xml".equals(item.mediaType) && !"text/html".equals(item.mediaType)) {
                    throw new IOException("EPUB spine 含不支持的正文类型：" + item.mediaType);
                }
                String path = resolvePath(base, item.href);
                HtmlHandler html = new HtmlHandler();
                parseXml(archive.read(path), html);
                itemNumber++;
                String title = chapterTitle(labels, path, html, itemNumber - 1);
                String plain = html.plainText();
                if (plain.isEmpty() && hasLocalIllustration(archive, directoryOf(path), html.imageReferences, base, opf.manifest))
                    plain = "[图文章节：请使用图文模式查看插图]";
                // 复用既有切分语义；parts 仅是当前 spine 的临时分片，逐章清空，不随全书累积。
                parts.clear();
                appendChaptersPublic(parts, title, plain, itemNumber - 1);
                for (Chapter part : parts) {
                    sink.accept(part.title, part.text, part.spineIndex);
                    produced++;
                }
            }
            if (produced == 0) throw new IOException("电子书内容为空");
            String title = trimWhitespace(opf.title.toString());
            return title.isEmpty() ? fileTitle(file) : boundedTitle(title);
        }
    }

    /**
     * 与 {@link #indexEpub(File, ChapterSink)} 同构的 MOBI 流式入口：纯 JVM，不引用任何 Android/JNI 类型。
     * 书名走既有 validate 路径（与 parse(mobi) 的 BookContent.title 同源），正文由注入的 decoder
     * 逐段切章后交给 sink，不构建 BookContent、不积累全书章节列表。
     * DRM 拒绝与无 so 降级文案仍由 requireMobiDecoder / decoder 原样透出。
     *
     * @return 书名
     */
    public static String indexMobi(File file, ChapterSink sink) throws Exception {
        if (file == null || !file.isFile() || !file.canRead()) throw new IOException("电子书文件不存在或不可读取");
        if (file.length() > MAX_IMPORT_BYTES) throw new IOException("导入文件超过 " + mib(MAX_IMPORT_BYTES) + " 上限");
        if (sink == null) throw new NullPointerException("sink");
        String title = validateForImport(file, "mobi", null);
        requireMobiDecoder().stream(file, sink);
        return title;
    }

    /** 供 ChapterSink 实现方复用既有切分语义（20000 边界、回退与代理对裁剪）的公开胶囊。 */
    public static void appendChaptersPublic(List<Chapter> out, String title, String content, int spineIndex)
            throws IOException {
        appendChapters(out, title, content, spineIndex);
    }

    private static String chapterTitle(Map<String, String> labels, String path, HtmlHandler html, int spineIndex) {
        String title = labels.get(path);
        if (title == null || title.isEmpty()) title = trimWhitespace(html.heading.toString());
        if (title.isEmpty()) title = trimWhitespace(html.title.toString());
        return title.isEmpty() ? "第" + (spineIndex + 1) + "章" : boundedTitle(title);
    }

    /** TOCs supply labels, never a replacement reading order. A file with multiple anchor links
     * keeps the first such label; the existing reader navigates by spine file, not by anchor. */
    private static Map<String, String> tocLabels(Archive archive, PackageHandler opf, String base) {
        Map<String, String> labels = new HashMap<String, String>();
        Set<String> spinePaths = new HashSet<String>();
        for (String id : opf.spine) {
            Item item = opf.manifest.get(id);
            if (item != null) try { spinePaths.add(resolvePath(base, item.href)); }
            catch (IOException ignored) { /* The main spine pass reports invalid content. */ }
        }
        for (Item item : opf.manifest.values()) {
            if (hasToken(item.properties, "nav") && "application/xhtml+xml".equals(item.mediaType)) {
                readToc(archive, item, base, spinePaths, labels, false);
                break;
            }
        }
        if (labels.size() < spinePaths.size()) {
            Item ncx = opf.manifest.get(opf.ncxId);
            if (ncx == null || !"application/x-dtbncx+xml".equals(ncx.mediaType)) {
                ncx = null;
                for (Item item : opf.manifest.values()) if ("application/x-dtbncx+xml".equals(item.mediaType)) {
                    ncx = item;
                    break;
                }
            }
            if (ncx != null) readToc(archive, ncx, base, spinePaths, labels, true);
        }
        return labels;
    }

    private static boolean hasToken(String tokens, String token) {
        if (tokens != null) for (String value : tokens.split("\\s+")) if (token.equals(value)) return true;
        return false;
    }

    private static void readToc(Archive archive, Item item, String base, Set<String> spinePaths,
            Map<String, String> labels, boolean ncx) {
        try {
            String path = resolvePath(base, item.href);
            Long size = archive.sizes.get(path);
            if (size == null || size > 2L * 1024 * 1024) return;
            TocHandler handler = new TocHandler(ncx);
            parseXml(archive.readDisplay(path), handler);
            for (TocLink link : handler.links) {
                if (link.href == null || link.href.length() > 8192) continue;
                String title = boundedTitle(link.label.toString());
                if (title.isEmpty()) continue;
                try {
                    String target = resolvePath(directoryOf(path), link.href.trim());
                    if (spinePaths.contains(target)) labels.putIfAbsent(target, title);
                } catch (IOException ignored) { /* TOC metadata cannot access anything outside the archive. */ }
            }
        } catch (IOException ignored) { /* Missing/broken optional TOC must not hide readable content. */ }
    }

    private static final class TocLink {
        String href;
        final int depth;
        final StringBuilder label = new StringBuilder();
        TocLink(int depth) { this.depth = depth; }
    }

    /** Streaming metadata reader for EPUB 3 nav and EPUB 2 NCX. */
    private static final class TocHandler extends DefaultHandler {
        final boolean ncx;
        final List<TocLink> links = new ArrayList<TocLink>();
        final ArrayDeque<TocLink> points = new ArrayDeque<TocLink>();
        int depth, section = -1, labelDepth = -1, textDepth = -1, ignoredDepth = -1;
        TocLink anchor;
        TocHandler(boolean ncx) { this.ncx = ncx; }

        @Override public void startElement(String uri, String local, String q, Attributes a) {
            depth++;
            String name = element(local, q);
            if (ignoredDepth > 0) return;
            if ("script".equals(name) || "style".equals(name)) { ignoredDepth = depth; return; }
            if (section < 0) {
                if (ncx ? "navmap".equals(name) : "nav".equals(name) &&
                        (hasToken(a.getValue("http://www.idpf.org/2007/ops", "type"), "toc") ||
                         hasToken(a.getValue("role"), "doc-toc"))) section = depth;
                return;
            }
            if (ncx) {
                if ("navpoint".equals(name)) {
                    if (links.size() >= MAX_ZIP_ENTRIES) { ignoredDepth = depth; return; }
                    TocLink link = new TocLink(depth);
                    points.addLast(link);
                    links.add(link); // Preorder, so a parent is recorded before a nested point.
                } else if (!points.isEmpty()) {
                    TocLink link = points.peekLast();
                    if (depth == link.depth + 1 && "content".equals(name) && link.href == null) link.href = a.getValue("src");
                    if (depth == link.depth + 1 && "navlabel".equals(name)) labelDepth = depth;
                    if (labelDepth > 0 && depth == labelDepth + 1 && "text".equals(name)) textDepth = depth;
                }
            } else if ("a".equals(name) && anchor == null && links.size() < MAX_ZIP_ENTRIES) {
                anchor = new TocLink(depth);
                anchor.href = a.getValue("href");
                links.add(anchor);
            }
        }

        @Override public void characters(char[] chars, int start, int length) {
            if (ignoredDepth > 0) return;
            TocLink link = ncx ? (textDepth > 0 ? points.peekLast() : null) : anchor;
            if (link != null) link.label.append(chars, start, Math.min(length, 4096 - link.label.length()));
        }

        @Override public void endElement(String uri, String local, String q) {
            if (ignoredDepth > 0) {
                if (depth == ignoredDepth) ignoredDepth = -1;
                depth--;
                return;
            }
            if (depth == section) section = -1;
            if (depth == labelDepth) labelDepth = -1;
            if (depth == textDepth) textDepth = -1;
            if (anchor != null && depth == anchor.depth) anchor = null;
            if (!points.isEmpty() && depth == points.peekLast().depth) points.removeLast();
            depth--;
        }
    }

    private static boolean hasLocalIllustration(Archive archive,String chapterBase,List<String> references,
            String packageBase,Map<String,Item> manifest){
        Set<String> allowed=new HashSet<String>();
        for(Item item:manifest.values())if(rasterMime(item.mediaType)!=null){
            try{allowed.add(resolvePath(packageBase,item.href));}catch(IOException unsafe){/* Optional external images are unavailable offline. */}
        }
        for(String reference:references)try{
            String path=resolvePath(chapterBase,reference);
            if(allowed.contains(path)&&archive.entries.containsKey(path))return true;
        }catch(IOException unsafe){/* Never treat unsafe resources as image-only content. */}
        return false;
    }

    /** Offline illustrated EPUB. HTML is a sanitized body fragment; image keys are opaque tokens. */
    public static IllustratedBook openIllustrated(File file) throws Exception {
        return new IllustratedBook(file);
    }

    public static final class EpubImage {
        public final String mime;
        public final byte[] bytes;
        EpubImage(String mime, byte[] bytes) { this.mime = mime; this.bytes = bytes; }
    }

    public static final class IllustratedBook implements AutoCloseable {
        private final Archive archive;
        private final List<String> paths = new ArrayList<String>();
        private final List<String> titles = new ArrayList<String>();
        private final Map<String, String> imagePaths = new LinkedHashMap<String, String>();
        private final Map<String, String> pathTokens = new HashMap<String, String>();
        private final Map<String, String> imageTypes = new HashMap<String, String>();
        private final Map<String, String> css = new LinkedHashMap<String, String>();
        private boolean closed;
        public final String bookTitle;

        private IllustratedBook(File file) throws Exception {
            if (file == null || !file.isFile() || file.length() > MAX_IMPORT_BYTES)
                throw new IOException("EPUB 文件不可读取或超过 " + mib(MAX_IMPORT_BYTES));
            archive = new Archive(file);
            boolean success = false;
            try {
                checkEncryption(archive);
                ContainerHandler container = new ContainerHandler();
                parseXml(archive.read("META-INF/container.xml"), container);
                if (container.rootfile == null) throw new IOException("EPUB 缺少OPF文件");
                String opfPath = resolvePath("", container.rootfile);
                PackageHandler opf = new PackageHandler();
                parseXml(archive.read(opfPath), opf);
                if (opf.spine.isEmpty()) throw new IOException("EPUB 缺少阅读顺序");
                bookTitle = trimWhitespace(opf.title.toString()).isEmpty() ? fileTitle(file) : boundedTitle(opf.title.toString());
                String base = directoryOf(opfPath);
                for (Item item : opf.manifest.values()) {
                    String type = rasterMime(item.mediaType);
                    if (type != null) {
                        try { String path = resolvePath(base, item.href); imageTypes.put(path, type); }
                        catch (IOException unavailable) { /* Optional external images remain unavailable offline. */ }
                    } else if ("text/css".equals(item.mediaType)) {
                        try {
                            String path = resolvePath(base, item.href);
                            byte[] bytes = archive.readDisplay(path);
                            if (bytes.length <= 512 * 1024) addCss(css, new String(bytes, StandardCharsets.UTF_8));
                        } catch (IOException unavailable) { /* A missing/remote optional stylesheet must not hide readable text. */ }
                    }
                }
                Map<String, String> labels = tocLabels(archive, opf, base);
                for (String id : opf.spine) {
                    Item item = opf.manifest.get(id);
                    if (item == null || !("application/xhtml+xml".equals(item.mediaType) || "text/html".equals(item.mediaType)))
                        throw new IOException("EPUB 含不支持的正文类型");
                    String path = resolvePath(base, item.href);
                    paths.add(path);
                    HtmlHandler text = new HtmlHandler();
                    parseXml(archive.readDisplay(path), text);
                    titles.add(chapterTitle(labels, path, text, paths.size() - 1));
                }
                success = true;
            } finally {
                if (!success) archive.close();
            }
        }

        public synchronized int size() { return paths.size(); }
        public synchronized String title(int index) { return titles.get(index); }
        /** 默认：套用原书 CSS（安全白名单内联），并输出 data-cfi 结构锚点。 */
        public synchronized String html(int index) throws IOException {
            return html(index, true);
        }

        /**
         * 渲染章节 HTML 片段。
         * @param applyBookCss true=套用原书 CSS（白名单声明，内联）；false=不套原书样式，由应用统一样式接管。
         * 两种情况都会输出 data-cfi 结构锚点（基于渲染后 DOM 路径，稳定不依赖原书 id/class）。
         */
        public synchronized String html(int index, boolean applyBookCss) throws IOException {
            return html(index, applyBookCss, Collections.<String, String>emptyMap());
        }

        /**
         * 渲染章节 HTML 片段，并叠加调用方提供的外部 CSS 规则（设计 §1.2）。
         *
         * @param extraRules 已经过 parseCss() 白名单清洗的 selector → 声明串；可为 null。
         *                   合并时排在书内规则之后，同一 selector 的声明追加在末尾 → 同名属性用户优先。
         *                   传入空 map 时与 html(index, applyBookCss) 逐字节等价。
         */
        public synchronized String html(int index, boolean applyBookCss, Map<String, String> extraRules) throws IOException {
            requireOpen();
            String path = paths.get(index);
            Map<String, String> rules = applyBookCss
                    ? new LinkedHashMap<String, String>(css) : new LinkedHashMap<String, String>();
            if (extraRules != null) for (Map.Entry<String, String> entry : extraRules.entrySet()) {
                String selector = entry.getKey();
                String addition = entry.getValue();
                if (selector == null || addition == null || addition.isEmpty()) continue;
                String previous = rules.get(selector);
                String combined = (previous == null ? "" : previous) + addition;
                if (combined.length() <= 4096) rules.put(selector, combined);
            }
            RichHandler handler = new RichHandler(this, directoryOf(path), rules, applyBookCss);
            parseXml(archive.readDisplay(path), handler);
            return handler.result.toString();
        }
        public synchronized EpubImage image(String token) throws IOException {
            requireOpen();
            String path = imagePaths.get(token);
            if (path == null) return null;
            byte[] bytes = archive.readDisplay(path);
            String declared = imageTypes.get(path);
            String actual = imageSignature(bytes);
            if (actual == null || !actual.equals(declared)) return null;
            return new EpubImage(actual, bytes);
        }
        private String imageToken(String base, String reference) {
            try {
                String path = resolvePath(base, reference);
                if (!imageTypes.containsKey(path) || !archive.entries.containsKey(path)) return null;
                String token = pathTokens.get(path);
                if (token == null) {
                    token = Integer.toString(imagePaths.size());
                    pathTokens.put(path, token); imagePaths.put(token, path);
                }
                return token;
            } catch (IOException unsafe) { return null; }
        }
        private void requireOpen() throws IOException { if (closed) throw new IOException("EPUB 已关闭"); }
        @Override public synchronized void close() throws IOException { if (!closed) { closed = true; archive.close(); } }
    }

    private static void checkEncryption(Archive archive) throws IOException {
        for (String path : archive.entries.keySet()) {
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.equals("meta-inf/rights.xml") || lower.equals("meta-inf/license.lcpl"))
                throw new IOException("此EPUB包含DRM版权保护，暂不支持解密");
            if (lower.equals("meta-inf/encryption.xml")) {
                EncryptionHandler encryption = new EncryptionHandler();
                parseXml(archive.readDisplay(path), encryption);
                if (encryption.unsupported || encryption.methods == 0)
                    throw new IOException("此EPUB包含DRM或不支持的加密");
            }
        }
    }

    private static String rasterMime(String mime) {
        if ("image/png".equals(mime) || "image/jpeg".equals(mime) || "image/gif".equals(mime) || "image/webp".equals(mime)) return mime;
        if ("image/jpg".equals(mime)) return "image/jpeg";
        return null;
    }
    private static String imageSignature(byte[] bytes) {
        if (bytes.length >= 8 && hasPrefix(bytes, 137, 80, 78, 71, 13, 10, 26, 10)) return "image/png";
        if (bytes.length >= 3 && hasPrefix(bytes, 255, 216, 255)) return "image/jpeg";
        if (bytes.length >= 6 && new String(bytes, 0, 6, StandardCharsets.US_ASCII).matches("GIF8[79]a")) return "image/gif";
        if (bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        return null;
    }

    public static final class CoverData {
        public final String mime;
        public final byte[] bytes;
        CoverData(String mime, byte[] bytes) { this.mime = mime; this.bytes = bytes; }
    }

    /** Seven-step cover discovery returns null when the book has no usable raster cover. */
    public static CoverData discoverCover(File file) throws Exception {
        if (file == null || !file.isFile() || !file.canRead()) return null;
        try (Archive archive = new Archive(file, null)) {
            ContainerHandler container = new ContainerHandler();
            parseXml(archive.read("META-INF/container.xml"), container);
            if (container.rootfile == null) return null;
            String opfPath = resolvePath("", container.rootfile);
            PackageHandler opf = new PackageHandler();
            parseXml(archive.read(opfPath), opf);
            String base = directoryOf(opfPath);
            Item cover = findCoverItem(opf, base);
            if (cover == null) return null;
            String path = resolvePath(base, cover.href);
            Long size = archive.sizes.get(path);
            if (size == null || size > 16L * 1024 * 1024) return null;
            byte[] bytes = archive.readDisplay(path);
            String mime = imageSignature(bytes);
            return mime == null ? null : new CoverData(mime, bytes);
        }
    }

    private static Item findCoverItem(PackageHandler opf, String base) {
        if (opf.coverMetaId != null) {
            Item byId = opf.manifest.get(opf.coverMetaId);
            if (byId != null && rasterMime(byId.mediaType) != null) return byId;
            for (Item item : opf.manifest.values())
                if (opf.coverMetaId.equals(item.href) && rasterMime(item.mediaType) != null) return item;
        }
        for (Item item : opf.manifest.values())
            if (hasToken(item.properties, "cover") && rasterMime(item.mediaType) != null) return item;
        for (Item item : opf.manifest.values()) {
            String lower = (safeLower(item.href) + " " + safeLower(item.properties)).trim();
            if (rasterMime(item.mediaType) != null && lower.contains("cover")) return item;
        }
        if (opf.guideCoverHref != null) {
            for (Item item : opf.manifest.values()) {
                if (rasterMime(item.mediaType) == null) continue;
                try { if (resolvePath(base, item.href).equals(resolvePath(base, opf.guideCoverHref))) return item; }
                catch (IOException ignored) { }
            }
        }
        for (Item item : opf.manifest.values()) if (rasterMime(item.mediaType) != null) return item;
        return null;
    }

    private static String safeLower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }

    private static final Set<String> RICH_TAGS = new HashSet<String>();
    static {
        Collections.addAll(RICH_TAGS, "p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6",
                "span", "strong", "b", "em", "i", "u", "s", "small", "sub", "sup", "br", "hr", "blockquote", "pre", "code",
                "ul", "ol", "li", "dl", "dt", "dd", "figure", "figcaption", "table", "thead", "tbody", "tfoot", "tr", "th", "td", "ruby", "rt", "rp");
    }
    private static final Set<String> RICH_HIDDEN = new HashSet<String>();
    static { Collections.addAll(RICH_HIDDEN, "script", "iframe", "object", "embed", "audio", "video", "form", "input", "button", "textarea", "select", "canvas", "math"); }

    private static final class RichHandler extends DefaultHandler {
        final IllustratedBook book;
        final String base;
        final Map<String, String> rules;
        final boolean applyBookCss;
        final StringBuilder result = new StringBuilder();
        final ArrayDeque<String> emitted = new ArrayDeque<String>();
        // CFI 结构路径：记录每个已输出元素的「兄弟序号」，用于生成 data-cfi
        final ArrayDeque<Integer> elementPath = new ArrayDeque<Integer>();
        final int[] siblingCounters = new int[64];
        final StringBuilder localCss = new StringBuilder();
        int depth, body = -1, hidden = -1, style = -1;
        RichHandler(IllustratedBook book, String base, Map<String, String> css, boolean applyBookCss) {
            this.book = book; this.base = base; this.rules = new LinkedHashMap<String, String>(css);
            this.applyBookCss = applyBookCss;
        }

        /** 当前元素的结构路径字符串（兄弟序号，0 起），空路径用 0。 */
        private String cfiString() {
            StringBuilder path = new StringBuilder();
            int i = 0;
            for (int index : elementPath) {
                if (i++ > 0) path.append('/');
                path.append(index);
            }
            return path.length() == 0 ? "0" : path.toString();
        }

        private void appendCfi(int pathDepth) throws SAXException {
            append(" data-cfi=\"" + cfiString() + "\"");
        }

        private int pushElement() {
            int level = Math.min(elementPath.size(), siblingCounters.length - 1);
            int index = siblingCounters[level]++;
            elementPath.addLast(index);
            return level;
        }

        private void popElement() {
            if (!elementPath.isEmpty()) elementPath.removeLast();
        }
        @Override public void startElement(String uri, String local, String q, Attributes attributes) throws SAXException {
            depth++;
            String name = element(local, q);
            if (hidden > 0) return;
            if (RICH_HIDDEN.contains(name)) { hidden = depth; return; }
            if ("style".equals(name)) { style = depth; localCss.setLength(0); return; }
            if ("body".equals(name)) {
                body = depth;
                pushElement();
                append("<div"); attributes(name, attributes); appendCfi(elementPath.size()); append(">");
                emitted.addLast("div");
                return;
            }
            if (body < 0 || style > 0) return;
            if ("img".equals(name) || "image".equals(name)) {
                String src = attributes.getValue("src");
                if (src == null) src = attributes.getValue("href");
                if (src == null) src = attributes.getValue("http://www.w3.org/1999/xlink", "href");
                String token = src == null ? null : book.imageToken(base, src);
                pushElement();
                if (token != null) {
                    append("<a class=\"epub-illustration\" data-cfi=\"" + cfiString() + "\" href=\"preview/" + token + "\"><img src=\"image/" + token + "\" alt=\"");
                    escape(attributes.getValue("alt") == null ? "书内插图" : attributes.getValue("alt")); append("\" loading=\"lazy\" /></a>");
                } else {
                    append("<span class=\"epub-image-unavailable\" data-cfi=\"" + cfiString() + "\">");
                    escape(attributes.getValue("alt") == null ? "[图片不支持或不可用]" : "[" + attributes.getValue("alt") + "]"); append("</span>");
                }
                emitted.addLast(""); return;
            }
            if (RICH_TAGS.contains(name)) {
                pushElement();
                append("<" + name); attributes(name, attributes); appendCfi(elementPath.size()); append(">");
                emitted.addLast(name);
            } else {
                pushElement();
                emitted.addLast("");
            }
        }
        private void attributes(String name, Attributes attrs) throws SAXException {
            // 设计 §1.2：extraRules 与书内 CSS 走同一白名单，但原书样式开关只门控「书内」来源。
            // applyBookCss=false 时 rules 只含 extraRules，必须独立生效；true 时含书内 css + extraRules。
            StringBuilder all = new StringBuilder();
            if (rules.containsKey(name)) all.append(rules.get(name));
            String id = attrs.getValue("id");
            if (id != null && id.matches("[A-Za-z0-9_-]{1,100}") && rules.containsKey("#" + id)) all.append(rules.get("#" + id));
            String classes = attrs.getValue("class");
            if (classes != null && classes.length() <= 1000) for (String cls : classes.split("\\s+")) {
                if (rules.containsKey("." + cls)) all.append(rules.get("." + cls));
                if (rules.containsKey(name + "." + cls)) all.append(rules.get(name + "." + cls));
            }
            if (applyBookCss) {
                // 只有书内的 align 属性与内联 style 受开关门控；移动后旧路径输出逐字节不变
                String align = attrs.getValue("align");
                if (align != null) all.append(safeCss("text-align:" + align));
                String inline = attrs.getValue("style"); if (inline != null) all.append(safeCss(inline));
            }
            if (all.length() > 0) { append(" style=\""); escape(all.toString()); append("\""); }
            if ("ol".equals(name)) numberAttribute(attrs, "start", 100000);
            if ("td".equals(name) || "th".equals(name)) { numberAttribute(attrs, "colspan", 50); numberAttribute(attrs, "rowspan", 50); }
        }
        private void numberAttribute(Attributes attrs, String key, int max) throws SAXException {
            String value = attrs.getValue(key); if (value == null || !value.matches("[0-9]{1,6}")) return;
            int number = Integer.parseInt(value); if (number > 0 && number <= max) append(" " + key + "=\"" + number + "\"");
        }
        @Override public void characters(char[] chars, int start, int length) throws SAXException {
            if (hidden > 0) return;
            if (style > 0) { if (localCss.length() + length <= 512 * 1024) localCss.append(chars, start, length); return; }
            if (body > 0) escape(new String(chars, start, length));
        }
        @Override public void endElement(String uri, String local, String q) throws SAXException {
            if (hidden > 0) { if (hidden == depth) hidden = -1; depth--; return; }
            if (style == depth) { addCss(rules, localCss.toString()); style = -1; depth--; return; }
            if (body > 0) {
                String emittedName = emitted.removeLast();
                if (!emittedName.isEmpty() && !"br".equals(emittedName) && !"hr".equals(emittedName)) append("</" + emittedName + ">");
                popElement();
                if (body == depth) body = -1;
            }
            depth--;
        }
        void append(String value) throws SAXException {
            if (result.length() + value.length() > 4 * 1024 * 1024) throw new SAXException("EPUB 图文章节过大，请改用纯文字模式");
            result.append(value);
        }
        void escape(String value) throws SAXException {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '&') append("&amp;"); else if (c == '<') append("&lt;"); else if (c == '>') append("&gt;");
                else if (c == '"') append("&quot;"); else append(String.valueOf(c));
            }
        }
    }

    /** Parse external CSS text into the same whitelisted rule map used by in-book CSS (§1.3). */
    public static Map<String, String> parseCss(String css) {
        Map<String, String> rules = new LinkedHashMap<String, String>();
        if (css != null) addCss(rules, css);
        return Collections.unmodifiableMap(rules);
    }

    private static void addCss(Map<String, String> rules, String css) {
        if (css.length() > 512 * 1024) return;
        css = css.replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher blocks = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(css);
        while (blocks.find() && rules.size() < 2000) {
            String value = safeCss(blocks.group(2)); if (value.isEmpty()) continue;
            for (String selector : blocks.group(1).split(",")) {
                selector = selector.trim();
                if (selector.matches("(?:[a-zA-Z][a-zA-Z0-9-]*)?(?:[.#][a-zA-Z_][a-zA-Z0-9_-]*)?") && !selector.isEmpty()) {
                    String previous = rules.get(selector);
                    String combined = (previous == null ? "" : previous) + value;
                    // Bound repeated declarations for a selector across many stylesheet entries.
                    if (combined.length() <= 4096) rules.put(selector, combined);
                }
            }
        }
    }
    private static String safeCss(String input) {
        if (input == null || input.length() > 16384) return "";
        StringBuilder safe = new StringBuilder();
        for (String declaration : input.split(";")) {
            int colon = declaration.indexOf(':'); if (colon < 0) continue;
            String key = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = declaration.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            boolean valid = false;
            if ("text-align".equals(key)) valid = value.matches("left|right|center|justify|start|end");
            else if ("font-style".equals(key)) valid = value.matches("normal|italic|oblique");
            else if ("font-weight".equals(key)) valid = value.matches("normal|bold|[1-9]00");
            else if ("text-decoration".equals(key)) valid = value.matches("none|underline|line-through");
            else if ("white-space".equals(key)) valid = value.matches("normal|pre-wrap|pre-line");
            else if ("vertical-align".equals(key)) valid = value.matches("baseline|sub|super|middle|top|bottom");
            else if ("font-size".equals(key)) valid = value.matches("small|smaller|medium|large|larger|[0-3](?:\\.[0-9]{1,2})?em|[5-9][0-9]%|1[0-9]{2}%|200%");
            else if ("line-height".equals(key)) valid = value.matches("normal|[1-3](?:\\.[0-9]{1,2})?");
            else if (key.matches("text-indent|margin-top|margin-bottom|margin-left|margin-right|padding-top|padding-bottom|letter-spacing"))
                valid = value.matches("0|[0-5](?:\\.[0-9]{1,2})?em|[0-9]{1,2}px");
            else if (key.matches("color|background-color"))
                valid = value.matches("#(?:[0-9a-f]{3}|[0-9a-f]{6})"
                    + "|rgb\\(\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*\\)"
                    + "|transparent|currentcolor|inherit|initial"
                    + "|(?:black|white|gray|grey|red|green|blue|yellow|cyan|magenta|orange|purple|silver|gold)");
            else if ("font-family".equals(key))
                valid = value.matches("(?:serif|sans-serif|monospace|cursive|fantasy)"
                    + "(?:\\s*,\\s*(?:serif|sans-serif|monospace|cursive|fantasy|[a-z][a-z0-9 -]{0,24})){0,3}");
            else if ("border".equals(key))
                valid = value.matches("[0-9]{1,2}px\\s+(?:none|solid|dashed|dotted|double)\\s+"
                    + "(?:#[0-9a-f]{3,6}|[a-z]{3,12})");
            if (valid) safe.append(key).append(':').append(value).append(';');
            if (safe.length() > 4096) break;
        }
        return safe.toString();
    }

    private static final class Archive implements AutoCloseable {
        final ZipFile zip;
        final Map<String, ZipEntry> entries = new LinkedHashMap<String, ZipEntry>();
        final Map<String, Long> sizes = new HashMap<String, Long>();
        final Runnable check;
        long referencedBytes;

        Archive(File file) throws IOException { this(file, null); }

        Archive(File file, Runnable check) throws IOException {
            this.check = check;
            checkpoint(check);
            zip = new ZipFile(file);
            boolean success = false;
            try {
                Enumeration<? extends ZipEntry> all = zip.entries();
                long total = 0;
                int count = 0;
                byte[] buffer = new byte[16384];
                while (all.hasMoreElements()) {
                    checkpoint(check);
                    ZipEntry entry = all.nextElement();
                    if (++count > MAX_ZIP_ENTRIES) throw new IOException("EPUB ZIP 条目数量超过上限");
                    String name = entry.getName();
                    validateEntryName(name);
                    if (entries.put(name, entry) != null) throw new IOException("EPUB ZIP 存在重复路径：" + name);
                    if (entry.getSize() > MAX_EPUB_ENTRY_BYTES) throw new IOException("EPUB 单项展开超过 " + mib(MAX_EPUB_ENTRY_BYTES) + " 上限");
                    long size = 0;
                    // Inspect every entry, including unused images: never trust declared ZIP sizes alone.
                    try (InputStream in = zip.getInputStream(entry)) {
                        int n;
                        while (true) {
                            checkpoint(check);
                            n = in.read(buffer);
                            if (n == -1) break;
                            size += n;
                            total += n;
                            if (size > MAX_EPUB_ENTRY_BYTES) throw new IOException("EPUB 单项展开超过 " + mib(MAX_EPUB_ENTRY_BYTES) + " 上限");
                            if (total > MAX_EPUB_EXPANDED_BYTES) throw new IOException("EPUB 总展开大小超过 " + mib(MAX_EPUB_EXPANDED_BYTES) + " 上限");
                        }
                    }
                    sizes.put(name, size);
                }
                success = true;
            } finally {
                if (!success) zip.close();
            }
        }

        byte[] read(String name) throws IOException {
            ZipEntry entry = entries.get(name);
            if (entry == null || entry.isDirectory()) throw new IOException("EPUB 缺少 ZIP 条目：" + name);
            referencedBytes += sizes.get(name).longValue();
            if (referencedBytes > MAX_EPUB_REFERENCED_BYTES) throw new IOException("EPUB 重复引用资源的总展开读取超过 " + mib(MAX_EPUB_REFERENCED_BYTES) + " 上限");
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = readLimited(in, MAX_EPUB_ENTRY_BYTES, "EPUB 单项展开超过 " + mib(MAX_EPUB_ENTRY_BYTES) + " 上限", check);
                if (bytes.length != sizes.get(name).longValue()) throw new IOException("EPUB 文件在读取期间发生变化");
                return bytes;
            }
        }

        // Repeated viewing is not a cumulative import operation. Each access is bounded
        // against the already validated archive sizes, without exhausting the import budget.
        byte[] readDisplay(String name) throws IOException {
            ZipEntry entry=entries.get(name);
            if(entry==null||entry.isDirectory())throw new IOException("EPUB 缺少资源："+name);
            try(InputStream in=zip.getInputStream(entry)){
                byte[] bytes=readLimited(in, MAX_EPUB_ENTRY_BYTES, "EPUB 资源超过8MiB上限", check);
                if(bytes.length!=sizes.get(name).longValue())throw new IOException("EPUB 资源读取期间发生变化");
                return bytes;
            }
        }

        @Override public void close() throws IOException { zip.close(); }
    }

    private static void validateEntryName(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || hasUnsafeCharacters(name)) {
            throw new IOException("EPUB ZIP 路径不安全：" + name);
        }
        for (String part : name.split("/", -1)) {
            if ("..".equals(part) || ".".equals(part)) throw new IOException("EPUB ZIP 路径穿越：" + name);
        }
    }

    /** Resolve URI references inside the archive, permitting parent paths only within the ZIP root. */
    private static String resolvePath(String base, String reference) throws IOException {
        try {
            URI uri = new URI(reference);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null) {
                throw new IOException("EPUB 禁止外部或非本地资源路径：" + reference);
            }
            String path = uri.getPath(); // Decode percent escapes once, including encoded traversal and slashes.
            if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || hasUnsafeCharacters(path)) {
                throw new IOException("EPUB 资源路径不安全：" + reference);
            }
            ArrayDeque<String> parts = new ArrayDeque<String>();
            for (String part : base.split("/")) if (!part.isEmpty()) parts.addLast(part);
            for (String part : path.split("/", -1)) {
                if (part.isEmpty() || ".".equals(part)) continue;
                if ("..".equals(part)) {
                    if (parts.isEmpty()) throw new IOException("EPUB 资源路径穿越：" + reference);
                    parts.removeLast();
                } else {
                    parts.addLast(part);
                }
            }
            if (parts.isEmpty()) throw new IOException("EPUB 资源路径为空");
            StringBuilder result = new StringBuilder();
            for (String part : parts) {
                if (result.length() != 0) result.append('/');
                result.append(part);
            }
            return result.toString();
        } catch (URISyntaxException e) {
            throw new IOException("EPUB 资源 URI 无效：" + reference, e);
        }
    }

    private static boolean hasUnsafeCharacters(String path) {
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 32 || c == 127 || c == ':') return true;
        }
        return false;
    }

    private static String directoryOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    private static void parseXml(byte[] bytes, DefaultHandler handler) throws IOException {
        try {
            String xml = decodeXml(bytes);
            // Remove harmless HTML5 / recognized W3C XHTML doctype identifiers without fetching.
            // Reject internal subsets, other DTDs and entities before any platform SAX parser sees them.
            xml = prepareXml(xml);
            // A Reader avoids reinterpreting an original encoding declaration after decoding.
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            setFeature(factory, "http://javax.xml.XMLConstants/feature/secure-processing", true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            try { reader.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", ""); }
            catch (SAXException unsupported) { /* Android SAX may not expose JAXP 1.5 properties. */ }
            try { reader.setProperty("http://javax.xml.XMLConstants/property/accessExternalSchema", ""); }
            catch (SAXException unsupported) { /* DTD ban + rejecting resolver are mandatory guards. */ }
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("禁止外部实体"); });
            reader.setContentHandler(new DefaultHandler() {
                private int depth;
                @Override public void startElement(String uri, String local, String q, Attributes attributes) throws SAXException {
                    if (++depth > 256) throw new SAXException("EPUB XML 嵌套过深");
                    handler.startElement(uri, local, q, attributes);
                }
                @Override public void endElement(String uri, String local, String q) throws SAXException {
                    handler.endElement(uri, local, q);
                    depth--;
                }
                @Override public void characters(char[] chars, int start, int length) throws SAXException {
                    handler.characters(chars, start, length);
                }
            });
            reader.setErrorHandler(handler);
            reader.parse(new InputSource(new java.io.StringReader(xml)));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("EPUB XML 解析失败：" + e.getMessage(), e);
        }
    }

    private static String prepareXml(String xml) throws IOException {
        StringBuilder result = new StringBuilder(xml.length());
        int index = 0;
        while (index < xml.length()) {
            String terminator = null;
            if (xml.startsWith("<!--", index)) terminator = "-->";
            else if (xml.startsWith("<![CDATA[", index)) terminator = "]]>";
            else if (xml.startsWith("<?", index)) terminator = "?>";
            if (terminator != null) {
                int end = xml.indexOf(terminator, index + 2);
                if (end < 0) throw new IOException("EPUB XML 注释或 CDATA 未闭合");
                end += terminator.length();
                result.append(xml, index, end);
                index = end;
                continue;
            }
            if (xml.charAt(index) == '<') {
                Matcher simple = SIMPLE_HTML_DOCTYPE.matcher(xml).region(index, xml.length());
                if (simple.lookingAt()) {
                    index = simple.end();
                    continue;
                }
                Matcher standard = STANDARD_XHTML_DOCTYPE.matcher(xml).region(index, xml.length());
                if (standard.lookingAt()) {
                    // Recognize but never fetch standard XHTML DTD identifiers used by EPUB 2.
                    index = standard.end();
                    continue;
                }
                Matcher ncx = STANDARD_NCX_DOCTYPE.matcher(xml).region(index, xml.length());
                if (ncx.lookingAt()) {
                    // Recognize legacy NCX metadata declarations without fetching them.
                    index = ncx.end();
                    continue;
                }
                if (DECLARATION.matcher(xml).region(index, xml.length()).lookingAt()) {
                    throw new IOException("EPUB XML 禁止 DTD、外部实体及实体声明");
                }
            }
            if (xml.charAt(index) == '&') {
                Matcher entity = HTML_ENTITY.matcher(xml).region(index, xml.length());
                if (entity.lookingAt() && ENTITIES.containsKey(entity.group(1))) {
                    result.append(ENTITIES.get(entity.group(1)));
                    index = entity.end();
                    continue;
                }
            }
            result.append(xml.charAt(index++));
        }
        // Repair unclosed HTML void elements common in EPUB 2 content before SAX sees them.
        return VOID_ELEMENT.matcher(result.toString()).replaceAll("<$1$2/>");
    }

    private static void setFeature(SAXParserFactory factory, String feature, boolean enabled) {
        try { factory.setFeature(feature, enabled); }
        catch (Exception unsupported) { /* XML preflight and entity resolver still fail closed. */ }
    }

    private static String decodeXml(byte[] bytes) throws IOException {
        if (hasPrefix(bytes, 0xFF, 0xFE)) return decode(bytes, 2, StandardCharsets.UTF_16LE);
        if (hasPrefix(bytes, 0xFE, 0xFF)) return decode(bytes, 2, StandardCharsets.UTF_16BE);
        if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == '<' && bytes[2] == 0 && bytes[3] == '?') {
            return decode(bytes, 0, StandardCharsets.UTF_16BE);
        }
        if (bytes.length >= 4 && bytes[0] == '<' && bytes[1] == 0 && bytes[2] == '?' && bytes[3] == 0) {
            return decode(bytes, 0, StandardCharsets.UTF_16LE);
        }
        int offset = hasPrefix(bytes, 0xEF, 0xBB, 0xBF) ? 3 : 0;
        String prefix = new String(bytes, offset, Math.min(bytes.length - offset, 512), StandardCharsets.ISO_8859_1);
        Matcher encoding = XML_ENCODING.matcher(prefix);
        Charset charset = StandardCharsets.UTF_8;
        if (encoding.find()) {
            try { charset = Charset.forName(encoding.group(1)); }
            catch (IllegalArgumentException e) { throw new IOException("EPUB XML 编码不支持", e); }
        }
        return decode(bytes, offset, charset);
    }

    private static String element(String localName, String qName) {
        String name = localName == null || localName.isEmpty() ? qName : localName;
        int colon = name.indexOf(':');
        return (colon < 0 ? name : name.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }

    private static final class ContainerHandler extends DefaultHandler {
        String rootfile;
        boolean preferred;
        @Override public void startElement(String uri, String local, String q, Attributes a) {
            if ("rootfile".equals(element(local, q))) {
                String path = a.getValue("full-path");
                boolean opf = "application/oebps-package+xml".equals(a.getValue("media-type"));
                if (path != null && !path.isEmpty() && (rootfile == null || opf && !preferred)) {
                    rootfile = path;
                    preferred = opf;
                }
            }
        }
    }

    private static final class Item {
        final String href;
        final String mediaType;
        final String properties;
        Item(String href, String mediaType, String properties) {
            this.href = href; this.mediaType = mediaType; this.properties = properties;
        }
    }

    private static final class PackageHandler extends DefaultHandler {
        final Map<String, Item> manifest = new LinkedHashMap<String, Item>();
        final List<String> spine = new ArrayList<String>();
        final StringBuilder title = new StringBuilder();
        int depth;
        int metadataDepth = -1;
        int manifestDepth = -1;
        int spineDepth = -1;
        int titleDepth = -1;
        boolean hasTitle;
        String ncxId;
        String coverMetaId;
        String guideCoverHref;
        @Override public void startElement(String uri, String local, String q, Attributes a) throws SAXException {
            depth++;
            String name = element(local, q);
            if ("metadata".equals(name)) metadataDepth = depth;
            if ("manifest".equals(name)) manifestDepth = depth;
            if ("spine".equals(name)) {
                spineDepth = depth;
                ncxId = a.getValue("toc");
            }
            if (metadataDepth > 0 && "title".equals(name) && !hasTitle) {
                titleDepth = depth;
                hasTitle = true;
            }
            if (metadataDepth > 0 && "meta".equals(name) && "cover".equals(a.getValue("name")))
                coverMetaId = a.getValue("content");
            if ("reference".equals(name) && "cover".equals(a.getValue("type")))
                guideCoverHref = a.getValue("href");
            if (manifestDepth > 0 && "item".equals(name)) {
                String id = a.getValue("id"), href = a.getValue("href"), type = a.getValue("media-type");
                if (id == null || id.isEmpty() || href == null || href.isEmpty() || type == null) {
                    throw new SAXException("manifest 项缺少 id、href 或 media-type");
                }
                if (manifest.put(id, new Item(href, type, a.getValue("properties"))) != null) throw new SAXException("manifest id 重复");
                if (manifest.size() > MAX_ZIP_ENTRIES) throw new SAXException("manifest 项过多");
            }
            if (spineDepth > 0 && "itemref".equals(name)) {
                String id = a.getValue("idref");
                if (id == null || id.isEmpty()) throw new SAXException("spine 项缺少 idref");
                spine.add(id);
                if (spine.size() > MAX_CHAPTERS) throw new SAXException("EPUB 章节数超过上限");
            }
        }
        @Override public void characters(char[] ch, int start, int length) {
            if (titleDepth > 0) title.append(ch, start, length);
        }
        @Override public void endElement(String uri, String local, String q) {
            if (depth == titleDepth) titleDepth = -1;
            if (depth == metadataDepth) metadataDepth = -1;
            if (depth == manifestDepth) manifestDepth = -1;
            if (depth == spineDepth) spineDepth = -1;
            depth--;
        }
    }

    private static final class EncryptionHandler extends DefaultHandler {
        int methods;
        boolean unsupported;
        @Override public void startElement(String uri, String local, String q, Attributes a) {
            if ("encryptionmethod".equals(element(local, q))) {
                methods++;
                String algorithm = a.getValue("Algorithm");
                if (!"http://www.idpf.org/2008/embedding".equals(algorithm)
                        && !"http://ns.adobe.com/pdf/enc#RC".equals(algorithm)) unsupported = true;
            }
        }
    }

    private static final Set<String> BLOCKS = new HashSet<String>();
    static {
        Collections.addAll(BLOCKS, "p", "div", "section", "article", "blockquote", "pre", "li", "ul", "ol",
                "h1", "h2", "h3", "h4", "h5", "h6", "tr", "table", "dl", "dt", "dd", "hr");
    }

    private static final class HtmlHandler extends DefaultHandler {
        final StringBuilder text = new StringBuilder();
        final StringBuilder title = new StringBuilder();
        final StringBuilder heading = new StringBuilder();
        final List<String> imageReferences = new ArrayList<String>();
        int depth;
        int bodyDepth = -1;
        int hiddenDepth = -1;
        int titleDepth = -1;
        int headingDepth = -1;
        boolean foundHeading;
        @Override public void startElement(String uri, String local, String q, Attributes a) throws SAXException {
            depth++;
            if (depth > 256) throw new SAXException("EPUB XML 嵌套过深");
            String name = element(local, q);
            if (hiddenDepth > 0) return;
            if ("script".equals(name) || "style".equals(name)) { hiddenDepth = depth; return; }
            if ("body".equals(name)) bodyDepth = depth;
            if ("title".equals(name) && bodyDepth < 0) titleDepth = depth;
            if (bodyDepth > 0) {
                if("img".equals(name)||"image".equals(name)){
                    String src=a.getValue("src");if(src==null)src=a.getValue("href");
                    if(src==null)src=a.getValue("http://www.w3.org/1999/xlink","href");
                    if(src!=null&&imageReferences.size()<MAX_ZIP_ENTRIES)imageReferences.add(src);
                }
                if (BLOCKS.contains(name)) newline(2);
                else if ("br".equals(name)) newline(1);
                else if ("td".equals(name) || "th".equals(name)) space(text);
                if (!foundHeading && name.matches("h[1-6]")) {
                    foundHeading = true;
                    headingDepth = depth;
                }
            }
        }
        @Override public void characters(char[] ch, int start, int length) {
            if (hiddenDepth > 0) return;
            if (titleDepth > 0) appendCollapsed(title, ch, start, length);
            if (bodyDepth > 0) {
                appendCollapsed(text, ch, start, length);
                if (headingDepth > 0) appendCollapsed(heading, ch, start, length);
            }
        }
        @Override public void endElement(String uri, String local, String q) {
            String name = element(local, q);
            if (hiddenDepth == depth) hiddenDepth = -1;
            else if (hiddenDepth < 0 && bodyDepth > 0 && BLOCKS.contains(name)) newline(2);
            if (headingDepth == depth) headingDepth = -1;
            if (titleDepth == depth) titleDepth = -1;
            if (bodyDepth == depth) bodyDepth = -1;
            depth--;
        }
        void newline(int count) {
            while (text.length() > 0 && text.charAt(text.length() - 1) == ' ') text.setLength(text.length() - 1);
            if (text.length() == 0) return;
            int existing = 0;
            for (int i = text.length() - 1; i >= 0 && text.charAt(i) == '\n'; i--) existing++;
            while (existing++ < count) text.append('\n');
        }
        String plainText() { return trimWhitespace(text.toString()); }
    }

    private static void appendCollapsed(StringBuilder out, char[] chars, int start, int length) {
        for (int i = start; i < start + length; i++) {
            char c = chars[i];
            if (Character.isWhitespace(c) || c == '\u00a0' || c == '\u3000') space(out);
            else out.append(c);
        }
    }

    private static void space(StringBuilder out) {
        if (out.length() > 0) {
            char last = out.charAt(out.length() - 1);
            if (last != ' ' && last != '\n') out.append(' ');
        }
    }

    private static void appendChapters(List<Chapter> result, String title, String content) throws IOException {
        appendChapters(result, title, content, -1);
    }

    private static void appendChapters(List<Chapter> result, String title, String content, int spineIndex) throws IOException {
        content = trimWhitespace(content);
        if (content.isEmpty()) return;
        title = boundedTitle(title);
        int start = 0, part = 1;
        boolean split = content.length() > MAX_CHAPTER_CHARS;
        while (start < content.length()) {
            int end = Math.min(start + MAX_CHAPTER_CHARS, content.length());
            if (end < content.length()) {
                int minimum = start + MAX_CHAPTER_CHARS / 2;
                int paragraph = -1, newline = -1, space = -1;
                // Search only this chunk, not the entire prefix (avoids quadratic work on long lines).
                for (int i = end - 1; i >= minimum; i--) {
                    char c = content.charAt(i);
                    if (c == '\n') {
                        if (newline < 0) newline = i;
                        if (i > minimum && content.charAt(i - 1) == '\n') { paragraph = i; break; }
                    } else if (c == ' ' && space < 0) space = i;
                }
                if (paragraph >= 0) end = paragraph + 1;
                else if (newline >= 0) end = newline + 1;
                else if (space >= 0) end = space + 1;
                if (end > start && Character.isHighSurrogate(content.charAt(end - 1))) end--;
            }
            String chunk = trimWhitespace(content.substring(start, end));
            if (!chunk.isEmpty()) {
                if (result.size() >= MAX_CHAPTERS) throw new IOException("电子书章节数超过 " + MAX_CHAPTERS + " 上限");
                result.add(new Chapter(split ? title + "（" + part + "）" : title, chunk, spineIndex));
                part++;
            }
            start = end;
        }
    }

    private static void appendTextChapters(List<Chapter> chapters, String title, String source, int base) throws IOException {
        int first = chapters.size();
        appendChapters(chapters, title, source);
        int cursor = 0;
        for (int i = first; i < chapters.size(); i++) {
            Chapter chunk = chapters.get(i);
            int position = source.indexOf(chunk.text, cursor);
            if (position < 0) throw new IOException("无法定位文本章节");
            chapters.set(i, new Chapter(chunk.title, chunk.text, -1, base + position));
            cursor = position + chunk.text.length();
        }
    }

    private static String boundedTitle(String title) {
        title = trimWhitespace(title).replaceAll("\\s+", " ");
        if (title.length() <= 200) return title;
        int end = Character.isHighSurrogate(title.charAt(199)) ? 199 : 200;
        return title.substring(0, end) + "…";
    }

    private static String normalizeLines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String trimWhitespace(String value) {
        int start = 0, end = value.length();
        while (start < end && isWhitespace(value.charAt(start))) start++;
        while (end > start && isWhitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isWhitespace(char c) {
        return Character.isWhitespace(c) || c == '\u00a0' || c == '\ufeff';
    }

    private static String fileTitle(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String title = dot > 0 ? name.substring(0, dot) : name;
        return title.isEmpty() ? "未命名电子书" : title;
    }

    private static byte[] readLimited(InputStream in, long limit, String message, Runnable check) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        long total = 0;
        while (true) {
            checkpoint(check);
            int n = in.read(buffer);
            if (n == -1) break;
            total += n;
            if (total > limit) throw new IOException(message);
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String decode(byte[] bytes, int offset, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    }

    private static boolean hasPrefix(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 255) != prefix[i]) return false;
        return true;
    }

    private static Map<String, String> htmlEntities() {
        Map<String, String> result = new HashMap<String, String>();
        String[] names = {"nbsp", "ensp", "emsp", "thinsp", "ndash", "mdash", "lsquo", "rsquo", "ldquo", "rdquo",
                "hellip", "middot", "bull", "copy", "reg", "trade", "times", "divide", "laquo", "raquo", "euro", "pound", "yen", "cent",
                "amp", "lt", "gt", "quot", "apos", "sbquo", "bdquo", "deg", "plusmn", "sup1", "sup2", "sup3",
                "frac14", "frac12", "frac34", "iexcl", "iquest", "ordm", "ordf", "para", "sect", "dagger", "Dagger"};
        int[] codes = {160, 8194, 8195, 8201, 8211, 8212, 8216, 8217, 8220, 8221, 8230, 183, 8226, 169, 174, 8482,
                215, 247, 171, 187, 8364, 163, 165, 162,
                38, 60, 62, 34, 39, 8218, 8222, 176, 177, 185, 178, 179, 188, 189, 190, 161, 191, 186, 170, 182, 167, 8224, 8225};
        for (int i = 0; i < names.length; i++) result.put(names[i], "&#" + codes[i] + ";");
        return Collections.unmodifiableMap(result);
    }
}
