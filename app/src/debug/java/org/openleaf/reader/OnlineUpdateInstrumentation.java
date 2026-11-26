package org.openleaf.reader;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.openleaf.reader.core.ChapterDiff;

import java.util.Arrays;
import java.util.Collections;

/**
 * 在线书追更的设备侧回归。
 *
 * <p>设备无可达 HTTPS 测试源，这里不假装验证网络抓取；只验证角标契约与 URL 差异判定
 * 在真机上行为一致。真实目录抓取需在接入可用书源的环境里手工复验。
 */
public final class OnlineUpdateInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private int passed;

    private interface Check { void run() throws Exception; }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check("unknown book has no update badge", this::unknownBadge);
            check("badge can be set and cleared", this::badgeLifecycle);
            check("chapter url diff detects new chapters on device", this::urlDiff);
            result.putString("stream", "\nPASS: " + passed + " online update checks\n" + report);
            result.putInt("passed", passed);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after " + passed + " checks\n" + report
                    + android.util.Log.getStackTraceString(error));
            result.putString("failure", error.toString());
            finish(1, result);
        } finally {
            cleanup();
        }
    }

    private static final String BOOK_ID = "online-update-instrumentation";

    private void unknownBadge() {
        eq(0L, ChapterUpdateChecker.pendingCount(getTargetContext(), BOOK_ID), "未知书不应有更新角标");
        record("pending=0");
    }

    private void badgeLifecycle() {
        SharedPreferences prefs = prefs();
        prefs.edit().putInt("online_added_" + BOOK_ID, 3).apply();
        eq(3L, ChapterUpdateChecker.pendingCount(getTargetContext(), BOOK_ID), "角标写入后应可读");
        ChapterUpdateChecker.clearPending(getTargetContext(), BOOK_ID);
        eq(0L, ChapterUpdateChecker.pendingCount(getTargetContext(), BOOK_ID), "重新解析后角标应清零");
        record("3 -> 0");
    }

    private void urlDiff() {
        eq(2L, ChapterDiff.addedCount(
                Collections.singletonList("u1"), Arrays.asList("u1", "u2", "u3")), "应识别两章新增");
        eq(0L, ChapterDiff.addedCount(
                Arrays.asList("u1", "u2"), Arrays.asList("u2", "u1")), "顺序变化不算追更");
        record("2/0");
    }

    private SharedPreferences prefs() {
        return getTargetContext().getApplicationContext()
                .getSharedPreferences("reader", Context.MODE_PRIVATE);
    }

    private void cleanup() {
        try { prefs().edit().remove("online_added_" + BOOK_ID).remove("online_remote_count_" + BOOK_ID).apply(); }
        catch (RuntimeException ignored) { }
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

    private static void eq(long expected, long actual, String message) {
        if (expected != actual) throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
    }
}
