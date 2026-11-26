import java.io.Closeable;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * 纯 JVM 回归：NotePackage.exportDigestMarkdown 的拼串规则
 * （书名标题 / 每章一行 / tags 行 / truncated 尾注 / 空 digest 边界）。
 *
 * NotePackage 其余方法依赖 Android Context 与若干 Store，无法整体在 JVM 编译；
 * 本测试在临时目录读取【真实的 NotePackage.java 源码】，仅去掉 android.content.Context
 * 的 import 并追加最小桩类后编译，exportDigestMarkdown 方法体逐字取自真实源码。
 * 不引入 Android SDK / JUnit / 第三方依赖；断言始终启用，不依赖 -ea；结束删除临时目录。
 */
public final class DigestExportTest {
    private static int passed = 0, failed = 0;
    private static Path tempDir;
    private static URLClassLoader loader;
    private static Class<?> digestClass, lineClass;
    private static Method export;

    public static void main(String[] args) throws Exception {
        try {
            prepare();
            testBookTitle();
            testEachLine();
            testTags();
            testTruncated();
            testEmptyDigest();
            testNullDigest();
        } finally {
            cleanup();
        }
        System.out.println("DigestExportTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** 编译真实 NotePackage + DigestBuilder 到临时目录，并反射拿到导出方法。 */
    private static void prepare() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("需要 JDK（系统 Java 编译器不可用）");
        File noteSource = new File("app/src/main/java/org/openleaf/reader/NotePackage.java");
        File digestSource = new File("app/src/main/java/org/openleaf/reader/core/DigestBuilder.java");
        if (!noteSource.isFile() || !digestSource.isFile())
            throw new IllegalStateException("请在 openleaf 目录下运行本测试");

        tempDir = Files.createTempDirectory("digest-export-test");
        String original = new String(Files.readAllBytes(noteSource.toPath()), StandardCharsets.UTF_8);
        String patched = original.replace("import android.content.Context;\n", "");
        if (patched.equals(original))
            throw new IllegalStateException("NotePackage.java 缺少 android.content.Context import");
        Path patchedSource = tempDir.resolve("NotePackage.java");
        Files.write(patchedSource, (patched + STUBS).getBytes(StandardCharsets.UTF_8));

        int rc = compiler.run(null, null, System.err,
                "-encoding", "UTF-8", "--release", "17",
                "-cp", "core-tests/t02build",       // org.json 已预编译于此
                "-d", tempDir.toString(),
                digestSource.getPath(), patchedSource.toString());
        if (rc != 0) throw new IllegalStateException("编译 NotePackage 失败（exit " + rc + "）");

        URL[] urls = { tempDir.toUri().toURL(), new File("core-tests/t02build").toURI().toURL() };
        loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
        digestClass = loader.loadClass("org.openleaf.reader.core.DigestBuilder$Digest");
        lineClass = loader.loadClass("org.openleaf.reader.core.DigestBuilder$Line");
        Class<?> noteClass = loader.loadClass("org.openleaf.reader.NotePackage");
        export = noteClass.getDeclaredMethod("exportDigestMarkdown", digestClass);
        export.setAccessible(true);
    }

    /** 最小桩类：只为让 NotePackage 整体通过编译；与本测试断言的纯函数无关。 */
    private static final String STUBS = """
            
            class Context { }
            class BookMetadataStore {
                static final class Info { String review = ""; String author = ""; }
                BookMetadataStore(Context c) { }
                Info get(String bookId) { return new Info(); }
            }
            class BookStore {
                BookStore(Context c) { }
                java.util.List<String> tags(String bookId) { return java.util.Collections.emptyList(); }
            }
            class AnnotationStore {
                static final int SORT_CHAPTER = 0;
                static final String TYPE_BOOKMARK = "bookmark";
                static final String TYPE_HIGHLIGHT = "highlight";
                static final String STYLE_UNDERLINE = "underline";
                static final String STYLE_STRIKE = "strike";
                static final class Anchor {
                    final int chapter, start, end, spineIndex, sourceStart;
                    final String quote;
                    Anchor(int chapter, int start, int end, int spineIndex, int sourceStart, String quote) {
                        this.chapter = chapter; this.start = start; this.end = end;
                        this.spineIndex = spineIndex; this.sourceStart = sourceStart; this.quote = quote;
                    }
                }
                static final class Record {
                    Anchor anchor;
                    String type = TYPE_HIGHLIGHT, style = "", note = "";
                    int color = 0;
                    long createdAt = 0L;
                }
                AnnotationStore(Context c) { }
                java.util.List<Record> list(String bookId) { return java.util.Collections.emptyList(); }
                java.util.List<Record> list(String bookId, int sort, int limit) { return java.util.Collections.emptyList(); }
                String insert(String bookId, Anchor anchor, String type, int color, String note) { return ""; }
            }
            class IdeaStore {
                static final class Idea {
                    String id = "", bookId = "", sharer = "", bookTitle = "", review = "";
                    long createdAt = 0L;
                }
                IdeaStore(Context c) { }
                java.util.List<Idea> list() { return java.util.Collections.emptyList(); }
            }
            """;

    private static Object line(int chapter, String title, String lead) throws Exception {
        Constructor<?> c = lineClass.getDeclaredConstructor(int.class, String.class, String.class);
        c.setAccessible(true);
        return c.newInstance(chapter, title, lead);
    }

    private static Object digest(String title, List<String> tags, List<Object> lines,
                                 int totalChapters, boolean truncated) throws Exception {
        Constructor<?> c = digestClass.getDeclaredConstructor(
                String.class, List.class, List.class, int.class, boolean.class);
        c.setAccessible(true);
        return c.newInstance(title, tags, lines, totalChapters, truncated);
    }

    private static String export(Object digest) throws Exception {
        return (String) export.invoke(null, new Object[] { digest });
    }

    // ---- 5 项断言（§5） ----

    private static void testBookTitle() throws Exception {
        String md = export(digest("三体", Collections.emptyList(), Collections.emptyList(), 0, false));
        check("书名标题", "# 三体\n".equals(md), md);
    }

    private static void testEachLine() throws Exception {
        Object d = digest("书", Collections.emptyList(), Arrays.asList(
                line(0, "第一章 开始", "这是第一章的首句。"),
                line(1, "第二章 继续", "这是第二章的首句。")), 5, false);
        String md = export(d);
        check("每章一行 1", md.contains("- **第一章 开始**：这是第一章的首句。\n"), md);
        check("每章一行 2", md.contains("- **第二章 继续**：这是第二章的首句。\n"), md);
        check("不额外生成标题层级", !md.contains("## "), md);
    }

    private static void testTags() throws Exception {
        String md = export(digest("书", Arrays.asList("科幻", "推理"), Collections.emptyList(), 0, false));
        check("tags 行", md.contains("**标签**：科幻、推理\n"), md);
        String noTags = export(digest("书", Collections.emptyList(), Collections.emptyList(), 0, false));
        check("无 tags 不加行", !noTags.contains("**标签**"), noTags);
    }

    private static void testTruncated() throws Exception {
        Object d = digest("书", Collections.emptyList(), Arrays.asList(
                line(0, "第一章", "首句。"), line(1, "第二章", "次句。")), 100, true);
        String md = export(d);
        check("truncated 尾注", md.contains("> 仅显示前 2 条提要\n"), md);
        String full = export(digest("书", Collections.emptyList(),
                Collections.singletonList(line(0, "第一章", "首句。")), 1, false));
        check("未截断无尾注", !full.contains("仅显示前"), full);
    }

    private static void testEmptyDigest() throws Exception {
        String md = export(digest("", Collections.emptyList(), Collections.emptyList(), 0, false));
        check("空 digest 仍有标题行", "# \n".equals(md), md);
    }

    private static void testNullDigest() throws Exception {
        check("null digest 返回空串", "".equals(export(null)), export(null));
    }

    private static void check(String name, boolean ok, String actual) {
        if (ok) { passed++; System.out.println("  OK   " + name); }
        else { failed++; System.out.println("  FAIL " + name + " -> " + actual.replace("\n", "\\n")); }
    }

    private static void cleanup() {
        if (loader != null) {
            try { loader.close(); } catch (Exception ignored) { }
        }
        if (tempDir == null) return;
        try {
            Files.walk(tempDir)
                    .sorted(Collections.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
        } catch (Exception ignored) { }
    }
}
