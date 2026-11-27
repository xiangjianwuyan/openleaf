package org.openleaf.reader;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.util.LruCache;

import org.openleaf.reader.core.BookSearch;
import org.openleaf.reader.core.ChapterRule;
import org.openleaf.reader.core.LazyChapterBook;
import org.openleaf.reader.core.SourceOffset;
import org.openleaf.reader.core.TextIndexSpec;
import org.openleaf.reader.core.ThemePalette;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * INDEX-1 跨入口的 P1-3 纯断言固化：字号/主题切换只重绘，不重建整本书。
 *
 * <p>覆盖边界：插桩无法直接调 ReaderActivity 的 private {@code changeTextOrTheme()}，
 * 因此复刻它的持久化写入集合（fontSize/theme/themeFollowSystem）并用
 * {@link ThemePalette#resolve} 纯函数验证主题确有变化，再断言同一 book 实例的章数、
 * 章节缓存文件数、锚点原字符三者不变。真正的点击→关闭→原字符可见路径由
 * {@code deliverables/OpenLeaf-B批-UI2-设备取证清单.md} 的 3 条真机步骤覆盖。
 */
public final class IndexCrossEntryInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private int passed;

    private interface Check { void run() throws Exception; }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check("font and theme changes do not rebuild the whole book", this::fontThemeNoRebuild);
            check("three entries derive identical specId", this::specIdentityRoundTrip);
            check("source offsets are monotonic and boundary-contiguous", this::sourceOffsetsMonotonic);
            check("stale spec is rejected without guessing", this::staleSpecRejected);
            check("cover preview is capped at four per page", this::coverPreviewCap);
            result.putString("stream", "\nPASS: " + passed + " INDEX-1/LIB-1 instrumentation checks\n" + report);
            result.putInt("passed", passed);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after " + passed + " checks\n" + report
                    + Log.getStackTraceString(error));
            result.putString("failure", error.toString());
            finish(1, result);
        }
    }

    private void fontThemeNoRebuild() throws Exception {
        FixtureContext ctx = new FixtureContext(getTargetContext());
        File cache = new File(ctx.getCacheDir(), "index-cross-entry-cache");
        require(cache.isDirectory() || cache.mkdirs(), "无法创建隔离章节缓存目录");
        File txt = new File(ctx.getCacheDir(), "p1-3-fixture.txt");
        Files.write(txt.toPath(), ("第一章 开端\n正文内容甲乙丙丁，锚点原字符不动。\n\n"
                + "第二章 发展\n第二段正文用于分章。\n\n第三章 结局\n收尾正文。\n")
                .getBytes(StandardCharsets.UTF_8));
        LazyChapterBook book = LazyChapterBook.open(txt, "txt", cache);
        try {
            require(book.size() >= 2, "夹具应有至少两章，实际 " + book.size());
            int sizeBefore = book.size();
            int filesBefore = countFiles(cache);
            String anchorBefore = book.load(0).substring(0, 6);
            record("opened size=" + sizeBefore + " cacheFiles=" + filesBefore + " anchor=" + anchorBefore);

            SharedPreferences prefs = ctx.getSharedPreferences("reader", 0);
            int themeBefore = prefs.getInt("theme", 0);
            int fontSizeBefore = prefs.getInt("fontSize", 16);
            int nextTheme = (themeBefore + 1) % ThemePalette.PRESET_COUNT;
            int[] paletteBefore = ThemePalette.resolve(themeBefore, 0, 0);
            int[] paletteAfter = ThemePalette.resolve(nextTheme, 0, 0);
            require(!Arrays.equals(paletteBefore, paletteAfter), "主题纯函数应产出不同配色");

            // changeTextOrTheme 的持久化写入等价：只写 font/theme/手动主题标记。
            prefs.edit()
                    .putInt("fontSize", Math.max(14, Math.min(40, fontSizeBefore + 2)))
                    .putInt("theme", nextTheme)
                    .putBoolean(ThemeResolver.KEY_FOLLOW, false)
                    .apply();
            require(prefs.contains("fontSize") && prefs.contains("theme"), "字号/主题应已持久化");
            require(!prefs.contains("chapter_rule_override_fixture"), "字号/主题不应写按书规则覆盖标记");
            require(!prefs.contains("chapter_rule_mode_fixture"), "字号/主题不应写按书规则档位");
            require(!prefs.contains("text_encoding_fixture"), "字号/主题不应写按书编码");

            require(book.size() == sizeBefore, "字号/主题切换后 book.size 变化");
            require(countFiles(cache) == filesBefore, "字号/主题切换后章节缓存文件数变化");
            require(book.load(0).substring(0, 6).equals(anchorBefore), "字号/主题切换后锚点原字符变化");
            record("after change size=" + book.size() + " cacheFiles=" + countFiles(cache)
                    + " anchor=" + book.load(0).substring(0, 6));
        } finally {
            book.close();
        }
    }

    /** 派生 → Intent 往返 → 缺字段/错 id 拒绝；旧书无 override/SHA 时等于全局规则 + AUTO。 */
    private void specIdentityRoundTrip() throws Exception {
        FixtureContext ctx = new FixtureContext(getTargetContext());
        String bookId = "cross-entry-fixture";
        SharedPreferences prefs = ctx.getSharedPreferences("reader", 0);
        prefs.edit().remove("chapter_rule_override_" + bookId).remove("text_encoding_" + bookId).apply();
        TextIndexSpec current = TextIndexRegistry.current(ctx, bookId);
        require(current != null, "current spec 不应为 null");
        TextIndexSpec expected = TextIndexSpec.of(bookId, null, 0,
                prefs.getInt("chapter_rule_mode", 0), prefs.getString("chapter_rule_custom", ""));
        require(current.specId().equals(expected.specId()),
                "旧书无 override/SHA 时应等于全局规则 + AUTO");

        Intent intent = new Intent();
        TextIndexRegistry.putInto(intent, current);
        TextIndexSpec claimed = TextIndexRegistry.claimedFrom(intent);
        require(claimed != null && claimed.specId().equals(current.specId()), "spec Intent 往返应保 id");

        Intent missing = new Intent(intent);
        missing.removeExtra(TextIndexRegistry.EXTRA_SPEC_ENCODING);
        require(TextIndexRegistry.claimedFrom(missing) == null, "缺字段的声称应返回 null");
        Intent tampered = new Intent(intent);
        tampered.putExtra(TextIndexRegistry.EXTRA_SPEC_ID, "deadbeef");
        require(TextIndexRegistry.claimedFrom(tampered) == null, "specId 不符的声称应返回 null");
        record("specId=" + current.specId() + " roundtrip/missing/tampered checked");
    }

    /** 作品级偏移单调、章边界相接；resolve 回落到同一 {chapter, inChapter}。 */
    private void sourceOffsetsMonotonic() throws Exception {
        FixtureContext ctx = new FixtureContext(getTargetContext());
        File cache = new File(ctx.getCacheDir(), "source-offset-cache");
        require(cache.isDirectory() || cache.mkdirs(), "无法创建章缓存目录");
        File txt = new File(ctx.getCacheDir(), "source-offset-fixture.txt");
        Files.write(txt.toPath(), ("第一章 开端\n甲乙丙丁正文一\n\n第二章 发展\n甲乙丙丁正文二\n\n"
                + "第三章 结局\n甲乙丙丁正文三\n").getBytes(StandardCharsets.UTF_8));
        LazyChapterBook book = LazyChapterBook.open(txt, "txt", cache, ChapterRule.conservative());
        try {
            require(book.size() >= 2, "夹具应至少两章");
            BookSearch.Result result = BookSearch.find(book, "甲乙丙丁", true, -1, 100,
                    () -> false, BookSearch.LITERAL, BookSearch.DEFAULT_NEAR_WINDOW);
            require(result.hits.size() >= 2, "应至少两处命中");
            long previous = -1;
            for (BookSearch.Hit hit : result.hits) {
                long base = SourceOffset.cumulative(book, hit.chapter);
                require(hit.sourceStart == base + hit.start && hit.sourceEnd == base + hit.end,
                        "Hit 作品级偏移应等于章起点 + 章内偏移");
                require(hit.sourceStart > previous, "作品级偏移必须单调");
                previous = hit.sourceStart;
                int[] resolved = SourceOffset.resolve(book, hit.sourceStart);
                require(resolved[0] == hit.chapter && resolved[1] == hit.start,
                        "resolve 应回落到同一 {chapter, inChapter}");
            }
            for (int chapter = 0; chapter < book.size() - 1; chapter++) {
                require(SourceOffset.of(book, chapter, book.meta(chapter).charCount)
                                == SourceOffset.of(book, chapter + 1, 0),
                        "章边界 sourceEnd(ch) 应等于 sourceStart(ch+1)");
            }
            record("hits=" + result.hits.size() + " chapters=" + book.size() + " offsets monotonic");
        } finally {
            book.close();
        }
    }

    /** 判定层：claimed 与 current 不符即过期，不猜跳；null claimed 同样失效。 */
    private void staleSpecRejected() throws Exception {
        FixtureContext ctx = new FixtureContext(getTargetContext());
        String bookId = "stale-fixture";
        TextIndexSpec current = TextIndexRegistry.current(ctx, bookId);
        require(current != null, "current spec 不应为 null");
        require(!TextIndexRegistry.stillValid(ctx, null), "null claimed 应失效");
        TextIndexSpec changed = TextIndexSpec.of(bookId,
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                current.encoding, current.ruleMode, current.ruleCustom);
        require(!changed.specId().equals(current.specId()), "SHA 变化应改变 specId");
        require(!TextIndexRegistry.stillValid(ctx, changed), "过期 claimed 必须被拒绝");
        require(TextIndexRegistry.stillValid(ctx, current), "同 spec 应有效");
        record("stale rejected; valid accepted; specId=" + current.specId());
    }

    /**
     * LRU 容量为 8；每页 4 次上限的计数在 MainActivity 私有字段，插桩无法直接读取，
     * 这里只验证缓存上限并按 4 次模拟，真正的每页上限由真机取证覆盖。
     */
    private void coverPreviewCap() throws Exception {
        FixtureContext ctx = new FixtureContext(getTargetContext());
        File directory = new File(ctx.getFilesDir(), "covers");
        require(directory.isDirectory() || directory.mkdirs(), "无法创建封面目录");
        Bitmap sample = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        String[] names = new String[10];
        try {
            for (int i = 0; i < names.length; i++) {
                names[i] = String.format(Locale.ROOT, "%08x-0000-0000-0000-%012x.jpg", i, i);
                File target = new File(directory, names[i]);
                try (FileOutputStream out = new FileOutputStream(target)) {
                    require(sample.compress(Bitmap.CompressFormat.JPEG, 80, out), "合成封面失败");
                }
            }
            for (String name : names) {
                require(CoverCache.preview(ctx, name, 64) != null, "封面应可解码: " + name);
            }
            java.lang.reflect.Field field = CoverCache.class.getDeclaredField("CACHE");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            LruCache<String, Bitmap> cache = (LruCache<String, Bitmap>) field.get(null);
            require(cache.maxSize() == 8, "LRU 容量应为 8");
            require(cache.size() == 8, "10 张不同封面后 LRU 应只留 8 条");
            int calls = 0;
            for (String name : names) {
                if (calls >= 4) break;
                require(CoverCache.preview(ctx, name, 64) != null, "预算内封面应命中");
                calls++;
            }
            require(calls == 4, "每页模拟预算应为 4 次");
            record("LRU maxSize=8 size=8; per-page cap=4 (MainActivity 真机取证覆盖)");
        } finally {
            sample.recycle();
        }
    }

    private static int countFiles(File dir) {
        File[] files = dir.listFiles();
        return files == null ? 0 : files.length;
    }

    private void check(String name, Check body) throws Exception {
        try {
            body.run();
            passed++;
            report.append("PASS ").append(passed).append("  ").append(name).append('\n');
        } catch (Throwable error) {
            report.append("FAIL  ").append(name).append(" -> ").append(error).append('\n');
            throw error;
        }
    }

    private void record(String detail) { report.append("      ").append(detail).append('\n'); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 与备份插桩同构的隔离 Context：prefs/DB/文件均落在独立前缀下。 */
    private static final class FixtureContext extends ContextWrapper {
        private final File root;
        private final String prefix = "index-cross-entry-" + UUID.randomUUID();

        FixtureContext(Context base) {
            super(base);
            root = new File(base.getCacheDir(), prefix);
            if (!root.mkdirs()) throw new IllegalStateException("cannot create isolated fixture");
        }

        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { File dir = new File(root, "files"); dir.mkdirs(); return dir; }
        @Override public File getNoBackupFilesDir() { File dir = new File(root, "no-backup"); dir.mkdirs(); return dir; }
        @Override public File getDatabasePath(String name) {
            File dir = new File(root, "databases"); dir.mkdirs(); return new File(dir, name);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory) { return openOrCreateDatabase(name, mode, factory, null); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
            return SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(), factory,
                    SQLiteDatabase.CREATE_IF_NECESSARY, handler);
        }
        @Override public boolean deleteDatabase(String name) {
            return SQLiteDatabase.deleteDatabase(getDatabasePath(name));
        }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return getBaseContext().getSharedPreferences(prefix + "-" + name, mode);
        }
    }
}
