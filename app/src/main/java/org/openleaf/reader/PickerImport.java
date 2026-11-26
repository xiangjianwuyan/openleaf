package org.openleaf.reader;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/** One process-local picker import, retained across Activity recreation. UI fields are main-thread only. */
final class PickerImport {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static PickerImport active;
    final int total;
    private final CancellationSignal signal = new CancellationSignal();
    private final Context app;
    Runnable listener;
    int item, added, duplicates, failed;
    boolean stopping, done;
    String name = "", errors = "";
    BookStore.ImportPhase phase;
    long bytes, size;

    PickerImport(Context context, List<Uri> selected) {
        app = context.getApplicationContext();
        ArrayList<Uri> uris = new ArrayList<>(selected);
        total = uris.size();
        // No Activity or UI object crosses onto the worker.
        Executors.defaultThreadFactory().newThread(() -> run(uris)).start();
    }

    private void run(ArrayList<Uri> uris) {
        int newBooks = 0, repeated = 0, failures = 0;
        StringBuilder problems = new StringBuilder();
        try {
            BookStore store = new BookStore(app);
            store.prepareImport(signal);
            for (int i = 0; i < uris.size(); i++) {
                signal.throwIfCanceled();
                int position = i + 1;
                postProgress(position, "", null, 0, -1);
                // The progress callback is the only place a name is known before the read starts;
                // remember it so a failure can be reported as "file name + reason", not just an index.
                String[] currentName = {""};
                try {
                    BookStore.ImportResult result = store.importUri(uris.get(i), signal, null,
                            (file, step, count, total) -> {
                                if (file != null && !file.isEmpty()) currentName[0] = file;
                                postProgress(position, file, step, count, total);
                            });
                    if (result.duplicate) repeated++; else newBooks++;
                } catch (OperationCanceledException ex) {
                    if (signal.isCanceled()) throw ex;
                    failures++;
                    problems.append("• ").append(label(currentName[0], position)).append("：读取已中止\n");
                } catch (Exception ex) {
                    if (signal.isCanceled()) break;
                    failures++;
                    problems.append("• ").append(label(currentName[0], position)).append("：").append(message(ex)).append('\n');
                }
            }
        } catch (OperationCanceledException ex) {
            if (!signal.isCanceled()) problems.append("导入已中止");
        } catch (Exception ex) {
            if (!signal.isCanceled()) problems.append("准备书库失败：").append(message(ex));
        }
        final int inserted = newBooks, skipped = repeated, broken = failures;
        MAIN.post(() -> {
            added = inserted;
            duplicates = skipped;
            failed = broken;
            errors = problems.toString();
            done = true;
            notifyView();
        });
    }

    private void postProgress(int index, String file, BookStore.ImportPhase step, long count, long contentLength) {
        MAIN.post(() -> {
            item = index;
            name = file;
            phase = step;
            bytes = count;
            size = contentLength;
            if (!stopping) notifyView();
        });
    }

    void stop() {
        if (stopping || done) return;
        stopping = true;
        notifyView();
        Executors.defaultThreadFactory().newThread(signal::cancel).start();
    }

    void notifyView() { if (listener != null) listener.run(); }

    /**
     * 把底层异常消息归入「权限 / 超限 / 格式或解析 / 其他」四类，分类依据只读取既有文案，
     * 不新增依赖也不改写原始消息：前缀之后始终保留全文，复制清单/另存 txt 仍可拿到细节。
     * 文件选择导入与文件夹导入共用本函数，避免两条入口的原因口径不一致。
     */
    static String message(Throwable e) {
        String raw = e.getMessage();
        String detail = raw == null || raw.isEmpty() ? e.getClass().getSimpleName() : raw;
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("请通过系统文件选择器授权原文件") || lower.contains("未获得原文件的持久读取授权")) {
            return "「权限」" + detail;
        }
        if ((lower.contains("不能超过") && (lower.contains("mb") || lower.contains("上限")))
                || (lower.contains("超过") && lower.contains("上限")) || lower.contains("toolarge")) {
            return "「超限」" + detail;
        }
        if (lower.contains("请选择 txt") || lower.contains("pdf") || lower.contains("不是有效的")
                || lower.contains("spine") || lower.contains("opf") || lower.contains("container.xml")
                || lower.contains("manifest") || lower.contains("编码") || lower.contains("bom")
                || lower.contains("格式") || lower.contains("解析") || lower.contains("drm")
                || lower.contains("解码") || lower.contains("不支持的电子书")) {
            return "「格式或解析」" + detail;
        }
        return "「其他」" + detail;
    }

    /** 失败条目先给文件名；读不到名字（例如连文件名都无法查询）时才退回序号。 */
    private static String label(String knownName, int position) {
        return knownName == null || knownName.trim().isEmpty() ? "文件 " + position : knownName;
    }
}
