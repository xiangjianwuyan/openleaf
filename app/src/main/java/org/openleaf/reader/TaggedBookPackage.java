package org.openleaf.reader;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Versioned single-book exchange format. Paths in the archive are never used as disk paths. */
final class TaggedBookPackage {
    private static final int SCHEMA_VERSION = 3;
    private static final long MIB = 1024L * 1024;
    private static final long MAX_ARCHIVE_BYTES = org.openleaf.reader.core.BookParser.MAX_IMPORT_BYTES + 2 * MIB;
    private static final long MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_TITLE_CODEPOINTS = 256;
    // Transport bound; BookStore separately enforces tag semantics and its per-book limit.
    private static final int MAX_TAGS = 256;

    private TaggedBookPackage() {}

    static final class Payload implements AutoCloseable {
        final File file;
        final String title, format, sha256, review, sharer;
        final List<String> tags;

        private Payload(File file, Manifest manifest, String sha256) {
            this.file = file;
            this.title = manifest.title;
            this.format = manifest.format;
            this.tags = manifest.tags;
            this.review = manifest.review;
            this.sharer = manifest.sharer;
            this.sha256 = sha256;
        }

        @Override public void close() throws IOException { Files.deleteIfExists(file.toPath()); }
    }

    private static final class Manifest {
        final String title, format, sha256, review, sharer;
        final List<String> tags;

        Manifest(String title, String format, String sha256, List<String> tags, String review, String sharer) {
            this.review = review;
            this.sharer = sharer == null ? "" : sharer;
            this.title = title;
            this.format = format;
            this.sha256 = sha256;
            this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
        }
    }

    /** Writes an exchange archive, finishing the ZIP without closing the caller's stream. */
    static void write(OutputStream out, File book, String title, String format, List<String> tags) throws IOException {
        write(out, book, title, format, tags, "", "");
    }

    static void write(OutputStream out, File book, String title, String format, List<String> tags, String review) throws IOException {
        write(out, book, title, format, tags, review, "");
    }

    static void write(OutputStream out, File book, String title, String format, List<String> tags,
                      String review, String sharer) throws IOException {
        review = BookReview.validate(review);
        sharer = sharer == null ? "" : sharer.trim();
        if (sharer.codePointCount(0, sharer.length()) > 64) throw new IOException("分享者署名不能超过64个字符");
        long bookLimit = bookLimit(format);
        validateMetadata(title, tags);
        if (book == null || !book.isFile() || !book.canRead()) throw new IOException("书籍文件不可读取");
        if (book.length() <= 0 || book.length() > bookLimit) throw new IOException("书籍文件为空或超过书包上限");
        // Validate the manifest size before emitting any ZIP bytes. The digest always has 64 ASCII characters.
        List<String> labels = new ArrayList<>(tags);
        manifestBytes(title, format, hex(new byte[32]), labels, review, sharer);
        MessageDigest digest = sha256();
        try (ZipOutputStream zip = new ZipOutputStream(new FilterOutputStream(out) {
            @Override public void close() throws IOException { flush(); }
        }); InputStream input = new FileInputStream(book)) {
            zip.putNextEntry(new ZipEntry("book." + format));
            if (copyBounded(input, zip, bookLimit, digest, null, "书籍超过书包上限", () -> {}) == 0)
                throw new IOException("不能打包空书籍");
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifestBytes(title, format, hex(digest.digest()), labels, review, sharer));
            zip.closeEntry();
        }
    }

    /** Copies from the caller's stream (left open) and returns a checked private temporary file. */
    static Payload read(InputStream in, File cacheDir) throws IOException {
        return read(in, cacheDir, () -> {});
    }

    static Payload read(InputStream in, File cacheDir, Runnable check) throws IOException {
        check.run();
        if (in == null) throw new IOException("没有选择书包");
        if (cacheDir == null || (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()))
            throw new IOException("无法创建书包缓存目录");
        File archive = File.createTempFile("olbook-archive-", ".part", cacheDir);
        File staged = null;
        boolean handedOff = false;
        try {
            try (OutputStream out = new FileOutputStream(archive)) {
                copyBounded(in, out, MAX_ARCHIVE_BYTES, null, null, "书包超过 " + org.openleaf.reader.core.BookParser.mib(MAX_ARCHIVE_BYTES) + " 上限", check);
            }
            String checkedHash;
            Manifest manifest;
            try (ZipFile zip = new ZipFile(archive)) {
                if (zip.size() != 2) throw new IOException("书包必须仅含清单与一本书籍");
                ZipEntry metadata = null, content = null;
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()) throw new IOException("书包不允许目录条目");
                    if ("manifest.json".equals(name) && metadata == null) metadata = entry;
                    else if (("book.txt".equals(name) || "book.epub".equals(name) || "book.pdf".equals(name)) && content == null)
                        content = entry;
                    else throw new IOException("书包含有未知或重复条目");
                }
                if (metadata == null || content == null) throw new IOException("书包缺少清单或书籍文件");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                readEntry(zip, metadata, bytes, MAX_MANIFEST_BYTES, null, "书包清单超过 64 KiB 上限", check);
                manifest = parseManifest(bytes.toByteArray());
                if (!("book." + manifest.format).equals(content.getName())) throw new IOException("书包文件与清单格式不一致");
                staged = File.createTempFile("olbook-content-", "." + manifest.format, cacheDir);
                MessageDigest sha = sha256();
                try (OutputStream out = new FileOutputStream(staged)) {
                    if (readEntry(zip, content, out, bookLimit(manifest.format), sha, "书籍超过书包上限", check) == 0)
                        throw new IOException("书包内书籍为空");
                }
                checkedHash = hex(sha.digest());
                if (!checkedHash.equalsIgnoreCase(manifest.sha256)) throw new IOException("书包内书籍的 SHA-256 校验失败");
            }
            Payload result = new Payload(staged, manifest, checkedHash);
            handedOff = true;
            return result;
        } finally {
            archive.delete();
            if (!handedOff && staged != null) staged.delete();
        }
    }

    private static long readEntry(ZipFile zip, ZipEntry entry, OutputStream out, long limit, MessageDigest digest,
                                  String limitMessage, Runnable check) throws IOException {
        if (entry.getSize() > limit) throw new IOException(limitMessage);
        CRC32 crc = new CRC32();
        long actual;
        try (InputStream input = zip.getInputStream(entry)) {
            if (input == null) throw new IOException("无法读取书包条目");
            actual = copyBounded(input, out, limit, digest, crc, limitMessage, check);
        }
        if (entry.getSize() != actual || entry.getCrc() != crc.getValue()) throw new IOException("书包条目已损坏");
        return actual;
    }

    private static long copyBounded(InputStream in, OutputStream out, long limit, MessageDigest digest, CRC32 crc,
                                    String limitMessage, Runnable check) throws IOException {
        byte[] buffer = new byte[32768];
        long count = 0;
        int n;
        while (true) {
            check.run();
            n = in.read(buffer, 0, (int) Math.min(buffer.length, limit - count + 1));
            if (n == -1) break;
            // Be tolerant of providers that occasionally return zero on a bulk read.
            if (n == 0) {
                int next = in.read();
                if (next == -1) break;
                buffer[0] = (byte) next;
                n = 1;
            }
            if (n > limit - count) throw new IOException(limitMessage);
            out.write(buffer, 0, n);
            if (digest != null) digest.update(buffer, 0, n);
            if (crc != null) crc.update(buffer, 0, n);
            count += n;
        }
        return count;
    }

    private static Manifest parseManifest(byte[] encoded) throws IOException {
        String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString();
        String title = null, format = null, sha = null, review = null, sharer = null;
        int version = 0;
        boolean seenVersion = false;
        List<String> tags = null;
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "schemaVersion":
                        if (seenVersion || reader.peek() != JsonToken.NUMBER)
                            throw new IOException("不支持或重复的书包清单版本");
                        String versionText = reader.nextString();
                        if (!"1".equals(versionText) && !"2".equals(versionText) && !"3".equals(versionText))
                            throw new IOException("不支持或重复的书包清单版本");
                        version = Integer.parseInt(versionText);
                        seenVersion = true;
                        break;
                    case "sharer": sharer = readString(reader, sharer); break;
                    case "review": review = readString(reader, review); break;
                    case "title": title = readString(reader, title); break;
                    case "format": format = readString(reader, format); break;
                    case "sha256": sha = readString(reader, sha); break;
                    case "tags":
                        if (tags != null) throw new IOException("书包清单含重复字段");
                        tags = new ArrayList<>();
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (tags.size() == MAX_TAGS) throw new IOException("书包清单标签过多");
                            tags.add(readString(reader, null));
                        }
                        reader.endArray();
                        break;
                    default: throw new IOException("书包清单含未知字段");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("书包清单含无效尾随内容");
        } catch (IllegalStateException malformed) {
            throw new IOException("书包清单字段类型错误", malformed);
        }
        if (!seenVersion) throw new IOException("书包清单缺少版本");
        if (sha == null || !sha.matches("[0-9a-fA-F]{64}")) throw new IOException("书包清单缺少有效的 SHA-256");
        bookLimit(format);
        validateMetadata(title, tags);
        if ((version == 2 || version == 3) && review == null) throw new IOException("书包清单缺少书评字段");
        if (version == 1 && review != null) throw new IOException("旧版书包不能包含书评字段");
        if (version < 3 && sharer != null) throw new IOException("旧版书包不能包含分享者字段");
        if (sharer != null && sharer.codePointCount(0, sharer.length()) > 64) throw new IOException("分享者署名过长");
        return new Manifest(title, format, sha, tags, BookReview.validate(review), sharer);
    }

    private static String readString(JsonReader reader, String previous) throws IOException {
        if (previous != null || reader.peek() != JsonToken.STRING)
            throw new IOException("书包清单字段重复或类型错误");
        return reader.nextString();
    }

    private static byte[] manifestBytes(String title, String format, String sha, List<String> tags,
                                        String review, String sharer) throws IOException {
        try {
            JSONObject metadata = new JSONObject();
            metadata.put("schemaVersion", SCHEMA_VERSION);
            metadata.put("title", title);
            metadata.put("format", format);
            metadata.put("sha256", sha);
            JSONArray labels = new JSONArray();
            for (String tag : tags) labels.put(tag);
            metadata.put("tags", labels);
            metadata.put("review", review);
            metadata.put("sharer", sharer == null ? "" : sharer);
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(metadata.toString()));
            if (encoded.remaining() > MAX_MANIFEST_BYTES) throw new IOException("书包清单超过 64 KiB 上限");
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (JSONException unavailable) {
            throw new IOException("无法生成书包清单", unavailable);
        }
    }

    private static void validateMetadata(String title, List<String> tags) throws IOException {
        if (title == null || title.trim().isEmpty() || title.codePointCount(0, title.length()) > MAX_TITLE_CODEPOINTS)
            throw new IOException("书包书名为空或过长");
        validateUnicode(title);
        if (tags == null || tags.size() > MAX_TAGS) throw new IOException("书包清单标签无效或过多");
        for (String tag : tags) validateUnicode(tag);
    }

    private static void validateUnicode(String value) throws IOException {
        if (value == null) throw new IOException("书包清单含空字段");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(++i)))
                    throw new IOException("书包清单含无效 Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IOException("书包清单含无效 Unicode");
        }
    }

    private static long bookLimit(String format) throws IOException {
        if ("txt".equals(format)) return org.openleaf.reader.core.BookParser.MAX_TXT_BYTES;
        if ("epub".equals(format) || "pdf".equals(format)) return org.openleaf.reader.core.BookParser.MAX_IMPORT_BYTES;
        throw new IOException("书包只支持 TXT、EPUB、PDF");
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException("系统不支持 SHA-256", e); }
    }

    private static String hex(byte[] input) {
        final String alphabet = "0123456789abcdef";
        StringBuilder result = new StringBuilder(input.length * 2);
        for (byte b : input) result.append(alphabet.charAt((b & 255) >>> 4)).append(alphabet.charAt(b & 15));
        return result.toString();
    }
}
