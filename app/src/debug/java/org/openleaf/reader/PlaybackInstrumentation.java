package org.openleaf.reader;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import org.openleaf.reader.core.LazyChapterBook;
import org.openleaf.reader.core.ProgressModel;
import org.openleaf.reader.core.ReadingAnchor;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 播放路径设备回归。
 *
 * <p>此前 51 项插桩全部落在导入/书库/数据库，播放与朗读路径零覆盖——0.19.0 那个
 * 「播 90 秒必崩」正是靠这个盲区活了六个版本。这里补的是「界面真的能把服务拉起来、
 * 服务真的载入了正文、通知真的能控制、停止真的收得干净」，全部走真实入口
 * （启动 {@link TtsActivity} 由它起服务），而不是在测试里直接 new 一个服务。
 *
 * <p>刻意不断言 TTS 是否出声：语音引擎可用性是设备配置，不是产品契约。
 */
public final class PlaybackInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private int passed;
    private String testBookId;

    private interface Check { void run() throws Exception; }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check("foreground service declared with mediaPlayback type and permissions", this::manifestDeclarations);
            check("listening entry starts service and loads book chapters", this::bookPlayback);
            check("media notification carries three controls", this::notificationControls);
            check("chapter skip reloads next chapter and returns", this::chapterSkip);
            check("listening position writes the reader progress contract", this::positionContract);
            check("external position revision advances on each write", this::positionRevision);
            check("service tracks the active book for reader handoff", this::activeBookTracking);
            check("stop releases service and clears notification", this::stopClears);
            result.putString("stream", "\nPASS: " + passed + " playback path checks\n" + report);
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

    /** 声明面：服务必须在，且类型是媒体播放、权限齐全，否则 Android 14 起会直接拒绝起服务。 */
    private void manifestDeclarations() throws Exception {
        Context ctx = getTargetContext();
        ServiceInfo info = ctx.getPackageManager()
                .getServiceInfo(new ComponentName(ctx, TtsPlaybackService.class), PackageManager.GET_META_DATA);
        require(info != null, "TtsPlaybackService 未在清单中声明");
        require(!info.exported, "听书服务不应导出给其它应用");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // SDK 公开桩里只有 getter，没有 foregroundServiceType 字段
            require((info.getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) != 0,
                    "foregroundServiceType 缺少 mediaPlayback");
        }
        String[] requested = ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
        for (String permission : new String[]{
                android.Manifest.permission.FOREGROUND_SERVICE,
                "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WAKE_LOCK}) {
            require(contains(requested, permission), "缺少权限声明：" + permission);
        }
        record("service + mediaPlayback + 4 permissions");
    }

    /** 真实入口：从界面点「听书」那条路把服务拉起来，并确认正文真的被切成章。 */
    private void bookPlayback() throws Exception {
        Context ctx = getTargetContext();
        BookStore store = new BookStore(ctx);
        File text = File.createTempFile("playback-fixture-", ".txt", ctx.getCacheDir());
        File pack = File.createTempFile("playback-fixture-", ".olbook", ctx.getCacheDir());
        java.nio.file.Files.write(text.toPath(),
                ("第一章 甲\n甲一。甲二。\n\n第二章 乙\n乙一。乙二。\n").getBytes(StandardCharsets.UTF_8));
        // 普通 importUri 会拒绝临时路径；书包导入是插桩已验证过的入库路径（会复制正文副本）
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(pack)) {
            TaggedBookPackage.write(out, text, "播放测试样书", "txt", java.util.Collections.emptyList());
        }
        BookStore.ImportResult imported = store.importTagged(Uri.fromFile(pack), null);
        text.delete();
        pack.delete();
        require(imported != null && imported.book != null, "测试书导入失败");
        testBookId = imported.book.id;
        ctx.startActivity(new Intent(ctx, TtsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(TtsPlaybackService.EXTRA_BOOK_ID, testBookId)
                .putExtra(TtsPlaybackService.EXTRA_CHAPTER, 0));
        await(() -> {
            TtsPlaybackService.Snapshot snapshot = TtsPlaybackService.current();
            return snapshot.hasSource && snapshot.chapters >= 2;
        }, "听书入口没有把书载入服务（chapters 应 >= 2）");
        TtsPlaybackService.Snapshot snapshot = TtsPlaybackService.current();
        require(snapshot.chapter == 0, "应从第 0 章开始，实际 " + snapshot.chapter);
        require(snapshot.title != null && !snapshot.title.isEmpty(), "书名未载入");
        record("chapters=" + snapshot.chapters + " title=" + snapshot.title);
    }

    /** 通知必须带三个控制动作；没有它「锁屏续播」就无从操作。 */
    private void notificationControls() throws Exception {
        final Notification[] found = new Notification[1];
        await(() -> {
            Notification notification = activeNotification();
            if (notification == null) return false;
            found[0] = notification;
            return true;
        }, "前台服务没有发出通知");
        Notification notification = found[0];
        require(notification.actions != null && notification.actions.length >= 3,
                "通知动作不足三个，实际 " + (notification.actions == null ? 0 : notification.actions.length));
        record("actions=" + notification.actions.length);
    }

    /** 换章：服务要真的把下一章正文载进来，而不是只改一个字段。 */
    private void chapterSkip() throws Exception {
        TtsPlaybackService.command(getTargetContext(), TtsPlaybackService.ACTION_NEXT_CHAPTER);
        await(() -> TtsPlaybackService.current().chapter == 1, "下一章没有生效");
        require(TtsPlaybackService.current().count > 0, "换章后没有切出任何句子");
        TtsPlaybackService.command(getTargetContext(), TtsPlaybackService.ACTION_PREV_CHAPTER);
        await(() -> TtsPlaybackService.current().chapter == 0, "上一章没有生效");
        record("0→1→0");
    }

    /**
     * 进度契约：写回的键必须和阅读页同一套，且 anchor 能被解析回同一章。
     * 抽静态方法就是为了这里能直接验证，而不必等语音引擎可用。
     */
    private void positionContract() throws Exception {
        Context ctx = getTargetContext();
        BookStore store = new BookStore(ctx);
        BookStore.Book record = store.find(testBookId);
        require(record != null, "找不到测试书");
        try (BookAccess access = store.open(record)) {
            LazyChapterBook book = LazyChapterBook.open(access.file, record.format, ctx.getCacheDir());
            try {
                require(book.size() >= 2, "测试书应至少两章");
                TtsPlaybackService.writePosition(ctx, testBookId, book, 1, 3, new ProgressModel(book));
            } finally {
                book.close();
            }
        }
        SharedPreferences prefs = ctx.getSharedPreferences("reader", 0);
        require(prefs.getInt("chapter_" + testBookId, -1) == 1, "chapter_ 未写回第 1 章");
        require(prefs.getInt("offset_" + testBookId, -1) >= 0, "offset_ 未写回");
        require(prefs.contains("progress_" + testBookId), "progress_ 未写回");
        ReadingAnchor anchor = ReadingAnchor.parse(prefs.getString("anchor_" + testBookId, null));
        require(anchor != null, "anchor_ 无法被阅读页解析");
        record("chapter=1 offset=" + prefs.getInt("offset_" + testBookId, -1));
    }

    /** 阅读页靠这个修订号区分「听书写的」与「自己写的」。 */
    private void positionRevision() throws Exception {
        Context ctx = getTargetContext();
        String key = org.openleaf.reader.core.PositionSync.revisionKey(testBookId);
        String before = ctx.getSharedPreferences("reader", 0).getString(key, null);
        require(before != null, "第一次位置写入应带上外部修订号");
        BookStore store = new BookStore(ctx);
        BookStore.Book record = store.find(testBookId);
        require(record != null, "找不到测试书");
        try (BookAccess access = store.open(record)) {
            LazyChapterBook book = LazyChapterBook.open(access.file, record.format, ctx.getCacheDir());
            try { TtsPlaybackService.writePosition(ctx, testBookId, book, 0, 5, new ProgressModel(book)); }
            finally { book.close(); }
        }
        String after = ctx.getSharedPreferences("reader", 0).getString(key, null);
        require(after != null && !after.equals(before), "每次外部位置写入必须换一个修订号");
        record("revision changed");
    }

    /** 回阅读页时靠它判断「是不是同一本书还在后台念」，是则停播消除双写。 */
    private void activeBookTracking() {
        require(TtsPlaybackService.isPlayingBook(testBookId), "服务应标记当前载入的书");
        require(!TtsPlaybackService.isPlayingBook("some-other-book"), "其它书不应被误判为在播");
        record("activeBook=" + testBookId);
    }

    /** 停止必须收干净：通知消失、服务不再持有正文，否则会留下看不出在跑的后台服务。 */
    private void stopClears() throws Exception {
        TtsPlaybackService.command(getTargetContext(), TtsPlaybackService.ACTION_STOP);
        await(() -> activeNotification() == null, "停止后通知仍然存在");
        await(() -> !TtsPlaybackService.current().hasSource, "停止后仍持有书籍");
        require(!TtsPlaybackService.isPlayingBook(testBookId), "停止后不应再标记当前书");
        record("notification cleared");
    }

    // ---- 辅助 ----

    private Notification activeNotification() {
        NotificationManager manager =
                (NotificationManager) getTargetContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return null;
        StatusBarNotification[] active = manager.getActiveNotifications();
        if (active == null) return null;
        for (StatusBarNotification item : active) {
            if (item.getId() == TtsPlaybackService.NOTIFICATION_ID) return item.getNotification();
        }
        return null;
    }

    private void cleanup() {
        try { TtsPlaybackService.command(getTargetContext(), TtsPlaybackService.ACTION_STOP); }
        catch (RuntimeException ignored) { }
        if (testBookId != null) {
            try {
                BookStore store = new BookStore(getTargetContext());
                BookStore.Book record = store.find(testBookId);
                if (record != null) store.remove(record);
            } catch (Exception ignored) { }
            try {
                getTargetContext().getSharedPreferences("reader", 0).edit()
                        .remove("chapter_" + testBookId)
                        .remove("offset_" + testBookId)
                        .remove("progress_" + testBookId)
                        .remove("anchor_" + testBookId)
                        .remove("bottom_" + testBookId)
                        .remove("lineFraction_" + testBookId)
                        .remove("text_layout_version_" + testBookId)
                        .apply();
            } catch (RuntimeException ignored) { }
        }
    }

    private void await(java.util.function.BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) return;
            } catch (RuntimeException ignored) { }
            try { Thread.sleep(100L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        throw new AssertionError(message);
    }

    private static boolean contains(String[] values, String target) {
        if (values == null) return false;
        for (String value : values) if (target.equals(value)) return true;
        return false;
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
}
