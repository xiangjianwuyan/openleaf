import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1000 本纯元数据书架夹具生成器（纯 JDK 17，无第三方依赖、无网络、无随机数）。
 *
 * <p>内容只引用 {@code ../txt/} 已有的合成 TXT 文件（复用其字节与 SHA-256），
 * 不复制、不修改用户真实文件。同一 JDK 下重复运行逐字节一致。
 *
 * <p>用法（在 library-1000 目录）：
 * <pre>
 *   javac -encoding UTF-8 --release 17 -d &lt;tmp&gt; GenerateLibrary1000.java
 *   java -cp &lt;tmp&gt; GenerateLibrary1000
 * </pre>
 */
public final class GenerateLibrary1000 {
    private static final int COUNT = 1000;
    private static final long FIXED_ADDED_MILLIS = 1_700_000_000_000L;
    private static final String[] SOURCES = {
            "txt/three-chapters-bom-crlf.txt",
            "txt/boundary-128kib.txt",
            "txt/manual-gb18030.txt",
            "txt/manual-big5.txt",
            "txt/utf16-bom.txt"
    };
    private static final String[] FORMATS = {"txt", "md", "epub", "pdf"};

    private GenerateLibrary1000() { }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path fixtures = dir.getParent();
        Files.createDirectories(dir);

        List<String> hashes = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        for (String source : SOURCES) {
            byte[] bytes = Files.readAllBytes(fixtures.resolve(source));
            hashes.add(sha256Hex(bytes));
            sizes.add((long) bytes.length);
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n  \"schema\": 1,\n  \"count\": ").append(COUNT).append(",\n  \"books\": [\n");
        for (int i = 0; i < COUNT; i++) {
            int source = i % SOURCES.length;
            json.append("    {\"id\":\"lib1000-").append(String.format(Locale.ROOT, "%04d", i))
                    .append("\",\"title\":\"合成书 ")
                    .append(String.format(Locale.ROOT, "%04d", i))
                    .append("\",\"format\":\"").append(FORMATS[i % FORMATS.length])
                    .append("\",\"sha256\":\"").append(hashes.get(source))
                    .append("\",\"size\":").append(sizes.get(source))
                    .append(",\"added\":").append(FIXED_ADDED_MILLIS + i * 60_000L)
                    .append("}");
            json.append(i + 1 < COUNT ? ",\n" : "\n");
        }
        json.append("  ]\n}\n");
        Files.write(dir.resolve("books.json"), json.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder manifest = new StringBuilder();
        manifest.append(sha256Hex(Files.readAllBytes(dir.resolve("books.json"))))
                .append("  library-1000/books.json\n");
        for (String source : SOURCES) {
            manifest.append(sha256Hex(Files.readAllBytes(fixtures.resolve(source))))
                    .append("  ").append(source).append('\n');
        }
        Files.write(dir.resolve("MANIFEST.sha256"), manifest.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("generated " + COUNT + " metadata-only books at " + dir);
    }

    private static String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
