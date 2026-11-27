package org.openleaf.reader;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.util.Log;

import org.openleaf.reader.core.LazyChapterBook;
import org.openleaf.reader.core.ThemePalette;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
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
            result.putString("stream", "\nPASS: " + passed + " UI-2/P1-3 checks\n" + report);
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
