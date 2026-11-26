package org.openleaf.reader;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import java.util.ArrayList;
import java.util.List;
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

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isEmpty() ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** 失败条目先给文件名；读不到名字（例如连文件名都无法查询）时才退回序号。 */
    private static String label(String knownName, int position) {
        return knownName == null || knownName.trim().isEmpty() ? "文件 " + position : knownName;
    }
}
