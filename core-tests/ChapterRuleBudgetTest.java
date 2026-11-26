import org.openleaf.reader.core.ChapterRule;
import org.openleaf.reader.core.TextChapterIndexer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;

/**
 * P1-3 CPU 预算与预览同源探针：验证灾难性回溯在匹配执行路径被中断，
 * 且源文件样本解码与全量索引使用同一 charset/BOM 口径。纯 JVM，无 Android/JUnit。
 */
public final class ChapterRuleBudgetTest {
    private static int passed;
    private static Path temporary;

    public static void main(String[] args) throws Exception {
        temporary = Files.createTempDirectory("openleaf-budget-tests-");
        try {
            test("灾难性回溯在匹配执行路径按预算中断", ChapterRuleBudgetTest::catastrophicBacktrack);
            test("预算不影响正常自定义命中与保守兜底", ChapterRuleBudgetTest::normalMatchWithBudget);
            test("索引路径带预算仍能完成并保留保守章", ChapterRuleBudgetTest::indexWithBudget);
            test("预览区分保守基线与扩展档新增", ChapterRuleBudgetTest::previewBreakdown);
            test("源文件样本解码与索引同口径", ChapterRuleBudgetTest::sampleSameDecode);
            System.out.println("ChapterRuleBudgetTest: " + passed + " checks passed");
        } finally {
            deleteRecursively(temporary);
        }
    }

    /** (a+)+$ 对 200 个 a + b 是教科书级灾难性回溯；无预算时足以挂住线程。 */
    private static void catastrophicBacktrack() throws Exception {
        String evilLine = repeat('a', 200) + "b";
        ChapterRule rule = ChapterRule.of(ChapterRule.Mode.CUSTOM, "(a+)+$");
        long start = System.nanoTime();
        boolean matched = withinBudget(() -> rule.isHeading(evilLine, true, 5L), 3000L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        check(!matched, "被预算中断的灾难性规则不应命中该行");
        check(elapsedMs < 3000L, "正则匹配未在预算内停止，耗时 " + elapsedMs + "ms");
    }

    private static void normalMatchWithBudget() {
        ChapterRule custom = ChapterRule.of(ChapterRule.Mode.CUSTOM, "^CH\\d+");
        check(custom.isHeading("CH07 星火", false, 25L), "小预算不应影响正常自定义命中");
        check(custom.isHeading("序章", false, 25L), "小预算不应影响保守兜底");
        check(!custom.isHeading("普通正文", false, 25L), "不匹配行仍应返回 false");
        check(ChapterRule.conservative().isHeading("第一章 归来", true, 1L),
                "保守档不应受匹配预算影响");
    }

    private static void indexWithBudget() throws Exception {
        String content = "第一章 归来\n正文\n" + repeat('a', 200) + "b\n第二章 远行\n更多正文\n";
        File file = write("budget.txt", content.getBytes(StandardCharsets.UTF_8));
        ChapterRule rule = ChapterRule.of(ChapterRule.Mode.CUSTOM, "(a+)+$");
        TextChapterIndexer.Result result = withinBudget(
                () -> TextChapterIndexer.index(file, cacheDir(), rule, null, 5L), 5000L);
        try {
            check(result != null && result.chapters.size() >= 2, "带预算索引应保留保守档章节");
        } finally {
            if (result != null) result.cacheFile.delete();
        }
    }

    private static void previewBreakdown() {
        String sample = "序章\n正文\nCH07 星火\n正文\n1. 起航\n正文\n";
        ChapterRule.Preview custom = ChapterRule.of(ChapterRule.Mode.CUSTOM, "^CH\\d+")
                .preview(sample, 0, 4);
        equal(2, custom.totalMatches);
        equal(1, custom.conservativeMatches);
        equal(1, custom.addedMatches);
        equal("CH07 星火", custom.examples.get(1));
        ChapterRule.Preview standard = ChapterRule.of(ChapterRule.Mode.STANDARD, "")
                .preview(sample, 0, 1);
        equal(1, standard.examples.size());
        equal(2, standard.totalMatches);
        equal(0, ChapterRule.conservative().preview("普通正文\n", 0, 4).totalMatches);
    }

    /** UTF-8 BOM + 强制 UTF-8：readSample 与 index 必须同样跳过 3 字节 BOM。 */
    private static void sampleSameDecode() throws Exception {
        String content = "第一章 归来\n正文\n第二章 远行\n正文\n";
        byte[] prefix = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        System.arraycopy(body, 0, bytes, prefix.length, body.length);
        File file = write("bom-sample.txt", bytes);
        String sample = TextChapterIndexer.readSample(file, StandardCharsets.UTF_8, 100);
        equal("第一章 归来\n正文\n第二章 远行\n正文\n", sample);
        check(!sample.startsWith("\uFEFF"), "readSample 应与索引一样吞掉 BOM");
        TextChapterIndexer.Result indexed = TextChapterIndexer.index(
                file, cacheDir(), ChapterRule.conservative(), StandardCharsets.UTF_8);
        try {
            check(indexed.chapters.size() >= 2, "同口径解码后的索引章数应正常");
            equal("第一章 归来", indexed.chapters.get(0).title);
        } finally {
            indexed.cacheFile.delete();
        }
    }

    private static <T> T withinBudget(Callable<T> action, long timeoutMs) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "openleaf-budget-probe");
                thread.setDaemon(true);
                return thread;
            }
        });
        try {
            Future<T> future = executor.submit(action);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(value);
        return builder.toString();
    }

    private static File write(String name, byte[] bytes) throws Exception {
        Path file = temporary.resolve(name);
        Files.write(file, bytes);
        return file.toFile();
    }

    private static File cacheDir() throws Exception {
        Path dir = temporary.resolve("cache-" + (++sequence));
        Files.createDirectories(dir);
        return dir.toFile();
    }

    private static int sequence;

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + "; got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void test(String name, ThrowingRunnable action) throws Exception {
        try { action.run(); passed++; System.out.println("OK " + name); }
        catch (Throwable failure) { throw new AssertionError("FAILED: " + name, failure); }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
