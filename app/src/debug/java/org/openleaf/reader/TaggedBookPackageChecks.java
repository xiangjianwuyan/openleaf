package org.openleaf.reader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Opt-in codec checks; fixtures are synthesized and no personal book files are read. */
final class TaggedBookPackageChecks {
    private static final byte[] BOOK = "abc".getBytes(StandardCharsets.US_ASCII);
    private static final String BOOK_SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private TaggedBookPackageChecks() {}

    static void run() throws Exception {
        File root = Files.createTempDirectory("olbook-regression-").toFile();
        try {
            File file = new File(root, "synthetic.txt");
            File cache = new File(root, "cache");
            Files.write(file.toPath(), BOOK);
            List<String> tags = Arrays.asList("馆藏", "Research", "Unicode 📚");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            TaggedBookPackage.write(bytes, file, "样书 📖", "txt", tags);
            try (TaggedBookPackage.Payload book = TaggedBookPackage.read(new ByteArrayInputStream(bytes.toByteArray()), cache)) {
                require("样书 📖".equals(book.title) && "txt".equals(book.format) && tags.equals(book.tags), "invalid metadata roundtrip");
                require(Arrays.equals(BOOK, Files.readAllBytes(book.file.toPath())), "invalid book roundtrip");
                require(cache.list().length == 1, "only staged book should survive reading");
            }
            require(cache.list().length == 0, "closing payload did not clear cache");

            String review = String.join("", Collections.nCopies(200, "评"));
            ByteArrayOutputStream reviewed = new ByteArrayOutputStream();
            TaggedBookPackage.write(reviewed, file, "样书", "txt", tags, review);
            try (TaggedBookPackage.Payload book = TaggedBookPackage.read(new ByteArrayInputStream(reviewed.toByteArray()), cache)) {
                require(review.equals(book.review), "review roundtrip failed");
            }
            ByteArrayOutputStream invalid = new ByteArrayOutputStream();
            boolean invalidReview = false;
            try { TaggedBookPackage.write(invalid, file, "样书", "txt", tags, "太短"); }
            catch (IOException expected) { invalidReview = true; }
            require(invalidReview && invalid.size() == 0, "invalid review emitted archive bytes");

            String good = manifest("txt", BOOK_SHA);
            try (TaggedBookPackage.Payload book = TaggedBookPackage.read(new ByteArrayInputStream(makeZip(
                    "manifest.json", good.getBytes(StandardCharsets.UTF_8), "book.txt", BOOK)), cache)) {
                require("Synthetic fixture".equals(book.title) && book.review.isEmpty(), "failed legacy manifest-first archive");
            }

            rejected(cache, makeZip("manifest.json", manifest("txt", BOOK_SHA.substring(1) + "0").getBytes(StandardCharsets.UTF_8), "book.txt", BOOK));
            rejected(cache, makeZip("manifest.json", good.getBytes(StandardCharsets.UTF_8), "../book.txt", BOOK));
            rejected(cache, makeZip("manifest.json", good.getBytes(StandardCharsets.UTF_8), "book.pdf", BOOK));
            rejected(cache, makeZip("manifest.json", new byte[]{(byte) 0xC3, (byte) 0x28}, "book.txt", BOOK));
            rejected(cache, makeZip("manifest.json", new byte[65537], "book.txt", BOOK));
            rejected(cache, makeZip("manifest.json", (good.substring(0, good.length() - 1) + ",\"title\":\"Other\"}").getBytes(StandardCharsets.UTF_8), "book.txt", BOOK));
            rejected(cache, makeZip("other.json", good.getBytes(StandardCharsets.UTF_8), "book.txt", BOOK));
            rejected(cache, makeZip("manifest.json", good.replace("\"schemaVersion\":1", "\"schemaVersion\":3").getBytes(StandardCharsets.UTF_8), "book.txt", BOOK));
            // Stored ZIP gives an exact content position: flip the body without updating CRC or manifest hash.
            byte[] corrupt = makeZip("book.txt", BOOK, "manifest.json", good.getBytes(StandardCharsets.UTF_8));
            corrupt[30 + "book.txt".length()] ^= 1;
            rejected(cache, corrupt);
            rejected(cache, makeZip("manifest.json", good.getBytes(StandardCharsets.UTF_8), "book.txt", new byte[0]));
            rejected(cache, makeOversizedTextZip(good));
            try (InputStream oversizedArchive = new InputStream() {
                private long remaining = 81L * 1024 * 1024 + 1;
                @Override public int read() { return remaining-- > 0 ? 0 : -1; }
                @Override public int read(byte[] b, int offset, int count) {
                    int read = (int) Math.min(remaining, count);
                    if (read <= 0) return -1;
                    remaining -= read;
                    return read;
                }
            }) {
                rejected(cache, oversizedArchive);
            }
            // Double-check export rejects too-large TXT without touching the destination.
            // 导出上限跟产品常量对齐：这里曾写死 20 MiB，而 BookParser.MAX_TXT_BYTES 自 0.13.0 起
            // 就是 512 MiB，样本永远不超限，于是本项长期误报失败。文件用稀疏写，只改长度不占空间。
            try (java.io.RandomAccessFile sparse = new java.io.RandomAccessFile(file, "rw")) {
                sparse.setLength(org.openleaf.reader.core.BookParser.MAX_TXT_BYTES + 1);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            boolean tooLong = false;
            try { TaggedBookPackage.write(output, file, "Large TXT", "txt", Collections.emptyList()); }
            catch (IOException expected) { tooLong = true; }
            require(tooLong && output.size() == 0, "oversized export not rejected before output");
        } finally {
            purge(root);
        }
    }

    private static byte[] makeZip(String a, byte[] dataA, String b, byte[] dataB) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            stored(zip, a, dataA);
            stored(zip, b, dataB);
        }
        return bytes.toByteArray();
    }

    private static void stored(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] makeOversizedTextZip(String manifest) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            stored(zip, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("book.txt"));
            byte[] chunk = new byte[32768];
            for (int i = 0; i < 640; i++) zip.write(chunk);
            zip.write(0);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String manifest(String format, String hash) {
        return "{\"schemaVersion\":1,\"title\":\"Synthetic fixture\",\"format\":\"" + format
                + "\",\"sha256\":\"" + hash + "\",\"tags\":[]}";
    }

    private static void rejected(File cache, byte[] bytes) throws IOException { rejected(cache, new ByteArrayInputStream(bytes)); }

    private static void rejected(File cache, InputStream stream) throws IOException {
        boolean failed = false;
        try (TaggedBookPackage.Payload ignored = TaggedBookPackage.read(stream, cache)) { }
        catch (IOException expected) { failed = true; }
        require(failed, "invalid book package was accepted");
        require(cache.list().length == 0, "failed import leaked a temporary file");
    }

    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static void purge(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) purge(child);
        Files.deleteIfExists(file.toPath());
    }
}
