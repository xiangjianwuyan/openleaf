import org.openleaf.reader.core.BookParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** BookParser 的 MOBI 注入分支回归（纯 JVM；fake decoder，不加载 native so）。 */
public final class MobiParserBridgeTest {
    private static int passed;
    static final String DRM = "这本书受 DRM 保护，本应用不提供解密，已停止导入。";
    static final String DEGRADE = "本机缺少 MOBI 解析组件，暂时无法打开；可用 Calibre 转为 EPUB 后导入。";

    /**
     * 可配置的注入 decoder。默认字段值保持既有 7 项测试语义不变；
     * 新增覆写 stream() 模拟真流式（逐段 accept，不构建 List/BookContent），
     * streamAggregate=true 时退化为 read()+sink.accept 供 old 聚合对照。
     * 包可见，供 MobiChapterIndexerTest / MobiStreamingOomProbe 复用同一份 fake 语义。
     */
    static final class Fake implements BookParser.MobiDecoder {
        String title = "Fake Mobi Title";
        String validateTitle = "校验书名";
        boolean throwEncrypted;
        int readCalls, validateCalls, streamCalls;

        boolean streamAggregate;              // true → stream() 退化聚合（old 档）
        int segments;                         // >0 → 合成 N 段；0 → 用 fixedChapters
        int charsPerSegment;                  // 合成段正文长度（中文，2 字节/码元）
        int segmentLimit = Integer.MAX_VALUE; // 模拟 Bridge 的 remaining 递减上限
        String[][] fixedChapters = {{"第一章", "正文一"}, {"第二章", "正文二"}};

        @Override public BookParser.BookContent read(File file) {
            readCalls++;
            if (throwEncrypted) throw new IllegalStateException("文档已加密");
            List<BookParser.Chapter> chapters = new ArrayList<BookParser.Chapter>();
            int count = segmentCount();
            for (int i = 0; i < count; i++) {
                String[] c = chapterAt(i);
                chapters.add(new BookParser.Chapter(c[0], c[1]));
            }
            return new BookParser.BookContent(title, chapters);
        }

        @Override public String validate(File file) throws Exception {
            validateCalls++;
            if (throwEncrypted) throw new IOException(DRM);
            return validateTitle;
        }

        @Override public void stream(File file, BookParser.ChapterSink sink) throws Exception {
            streamCalls++;
            if (throwEncrypted) throw new IOException(DRM);
            if (streamAggregate) {
                // 模拟 default 实现：先 read() 聚合，再逐个 accept（old 档用来复现全本峰值）。
                for (BookParser.Chapter c : read(file).chapters) sink.accept(c.title, c.text, c.spineIndex);
                return;
            }
            // 真流式：逐段生成后立即 accept，堆内不保留已产出的章节。
            int produced = 0;
            for (int i = 0; i < segmentCount() && produced < segmentLimit; i++) {
                String[] c = chapterAt(i);
                if (c[1] == null || c[1].isEmpty()) continue; // 空段跳过（与 Bridge 一致）
                sink.accept(c[0], c[1], -1);
                produced++;
            }
        }

        private int segmentCount() { return segments > 0 ? segments : fixedChapters.length; }

        private String[] chapterAt(int i) {
            if (segments > 0) return new String[] {"第" + (i + 1) + "章", "字".repeat(charsPerSegment)};
            return fixedChapters[i];
        }
    }

    /** 只实现 read/validate，走 BookParser.MobiDecoder 的 default stream（聚合回退）。 */
    private static final class DefaultFake implements BookParser.MobiDecoder {
        @Override public BookParser.BookContent read(File file) {
            return new BookParser.BookContent("默认书名",
                    Arrays.asList(new BookParser.Chapter("默认章", "默认正文")));
        }
        @Override public String validate(File file) { return "默认书名"; }
    }

    public static void main(String[] args) throws Exception {
        File file = File.createTempFile("openleaf-mobi-bridge-", ".mobi");
        try {
            test("parse(mobi) 走注入 decoder，title/text 原样进入 BookContent", () -> {
                Fake fake = new Fake();
                BookParser.setMobiDecoder(fake);
                BookParser.BookContent content = BookParser.parse(file, "mobi");
                equal("Fake Mobi Title", content.title);
                equal(2, content.chapters.size());
                equal("第一章", content.chapters.get(0).title);
                equal("正文一", content.chapters.get(0).text);
                equal(-1, content.chapters.get(0).spineIndex);
                equal(-1, content.chapters.get(0).sourceStart);
                equal(1, fake.readCalls);
            });
            test("四种扩展名（含前导点 / 大小写）都命中 mobi 分支", () -> {
                Fake fake = new Fake();
                BookParser.setMobiDecoder(fake);
                for (String kind : new String[] { "MOBI", ".prc", "AZW", ".azw3" }) {
                    check(BookParser.parse(file, kind).chapters.size() == 2, kind + " 应命中 MOBI 分支");
                }
                equal(4, fake.readCalls);
            });
            test("azw4 明确拒绝，文案逐字", () -> {
                BookParser.setMobiDecoder(new Fake());
                try {
                    BookParser.parse(file, "azw4");
                    throw new AssertionError("azw4 应被拒绝");
                } catch (IOException error) {
                    equal("该格式暂不支持（支持 MOBI / PRC / AZW / AZW3；AZW4 请先转换）。", error.getMessage());
                }
            });
            test("validateForImport(mobi) 取 decoder 书名并触发取消点", () -> {
                Fake fake = new Fake();
                BookParser.setMobiDecoder(fake);
                AtomicInteger checks = new AtomicInteger();
                String title = BookParser.validateForImport(file, "mobi", checks::incrementAndGet);
                equal("校验书名", title);
                equal(1, fake.validateCalls);
                check(checks.get() >= 2, "导入校验应在前后各触发一次 check 回调");
            });
            test("DRM 拒绝消息从 validateForImport 原样透出", () -> {
                Fake fake = new Fake();
                fake.throwEncrypted = true;
                BookParser.setMobiDecoder(fake);
                try {
                    BookParser.validateForImport(file, "mobi", () -> { });
                    throw new AssertionError("加密文档应被拒绝");
                } catch (IOException error) {
                    equal("这本书受 DRM 保护，本应用不提供解密，已停止导入。", error.getMessage());
                }
            });
            test("未注入 decoder → §6 降级文案，不抛 UnsatisfiedLinkError", () -> {
                BookParser.setMobiDecoder(null);
                try {
                    BookParser.parse(file, "mobi");
                    throw new AssertionError("无 decoder 应失败");
                } catch (IOException error) {
                    equal("本机缺少 MOBI 解析组件，暂时无法打开；可用 Calibre 转为 EPUB 后导入。",
                            error.getMessage());
                }
            });
            test("既有 txt 分支零回归", () -> {
                File txt = File.createTempFile("openleaf-mobi-txt-", ".txt");
                try {
                    java.nio.file.Files.write(txt.toPath(),
                            Collections.singletonList("正文。"), java.nio.charset.StandardCharsets.UTF_8);
                    BookParser.BookContent content = BookParser.parse(txt, "txt");
                    check(!content.chapters.isEmpty(), "TXT 仍可解析");
                } finally {
                    if (!txt.delete()) txt.deleteOnExit();
                }
            });

            // ---- 阶段二新增：stream parity / 两档入口 / DRM / 降级 / 空段与上限 ----
            test("stream 输出与 read() 章节逐字段 parity（title/text/spineIndex/sourceStart）", () -> {
                Fake fake = new Fake();
                BookParser.setMobiDecoder(fake);
                BookParser.BookContent content = BookParser.parse(file, "mobi");
                final List<String[]> captured = new ArrayList<String[]>();
                fake.stream(file, (t, text, spine) -> captured.add(new String[] { t, text, String.valueOf(spine) }));
                equal(content.chapters.size(), captured.size());
                for (int i = 0; i < captured.size(); i++) {
                    equal(content.chapters.get(i).title, captured.get(i)[0]);
                    equal(content.chapters.get(i).text, captured.get(i)[1]);
                    equal(-1, Integer.parseInt(captured.get(i)[2]));
                    equal(-1, content.chapters.get(i).spineIndex);
                    equal(-1, content.chapters.get(i).sourceStart);
                }
                equal(1, fake.streamCalls);
            });
            test("default stream（聚合回退）与覆写 stream（真流式）都能驱动 indexMobi", () -> {
                Path cache = Files.createTempDirectory("openleaf-mobi-bridge-cache-");
                try {
                    BookParser.setMobiDecoder(new DefaultFake());
                    final int[] n = { 0 };
                    final String[] text = { null };
                    equal("默认书名", BookParser.indexMobi(file, (t, x, s) -> {
                        n[0]++; text[0] = x; equal(-1, s);
                    }));
                    equal(1, n[0]); equal("默认正文", text[0]);

                    Fake streaming = new Fake();
                    BookParser.setMobiDecoder(streaming);
                    n[0] = 0; text[0] = null;
                    equal("校验书名", BookParser.indexMobi(file, (t, x, s) -> {
                        n[0]++; text[0] = x; equal(-1, s);
                    }));
                    equal(2, n[0]); equal("正文二", text[0]);
                    equal(1, streaming.streamCalls);
                    // 聚合档复用同一个 Fake：streamAggregate=true 时读全本再 accept。
                    Fake aggregate = new Fake();
                    aggregate.streamAggregate = true;
                    BookParser.setMobiDecoder(aggregate);
                    n[0] = 0;
                    BookParser.indexMobi(file, (t, x, s) -> n[0]++);
                    equal(2, n[0]); equal(1, aggregate.readCalls);
                } finally {
                    deleteRecursively(cache);
                }
            });
            test("stream 阶段 DRM 前置拒绝原样透出（不经 read）", () -> {
                Fake fake = new Fake();
                fake.throwEncrypted = true;
                BookParser.setMobiDecoder(fake);
                try {
                    fake.stream(file, (t, x, s) -> { });
                    throw new AssertionError("加密文档应被拒绝");
                } catch (Exception error) {
                    equal(DRM, error.getMessage());
                }
                equal(1, fake.streamCalls);
                equal(0, fake.readCalls);
            });
            test("indexMobi 无 decoder 时 §6 降级文案原样", () -> {
                BookParser.setMobiDecoder(null);
                try {
                    BookParser.indexMobi(file, (t, x, s) -> { });
                    throw new AssertionError("无 decoder 应失败");
                } catch (IOException error) {
                    equal(DEGRADE, error.getMessage());
                }
            });
            test("stream 空段跳过与 remaining 上限语义", () -> {
                Fake fake = new Fake();
                fake.fixedChapters = new String[][] { { "甲", "" }, { "乙", "正文" }, { "丙", "又一段" } };
                fake.segmentLimit = 1;
                BookParser.setMobiDecoder(fake);
                final List<String> titles = new ArrayList<String>();
                fake.stream(file, (t, x, s) -> titles.add(t));
                equal(1, titles.size());
                equal("乙", titles.get(0)); // 空段跳过，第二个产出后触顶
            });
        } finally {
            BookParser.setMobiDecoder(null);
            if (!file.delete()) file.deleteOnExit();
            System.out.println("PASS: " + passed + " MobiParserBridge checks");
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    private interface Case { void run() throws Exception; }

    private static void test(String name, Case body) {
        try {
            body.run();
            passed++;
            System.out.println("  ok - " + name);
        } catch (Throwable failure) {
            System.out.println("  FAIL - " + name + " : " + failure);
            failure.printStackTrace(System.out);
            throw new AssertionError(name, failure);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
