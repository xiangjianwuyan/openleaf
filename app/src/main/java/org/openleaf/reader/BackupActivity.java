package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.openleaf.reader.core.BackupManifest;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 书库备份与恢复：带版本清单、逐项哈希校验、ID 重映射与设备字段排除。 */
public final class BackupActivity extends Activity {
    private static final int EXPORT = 920, IMPORT = 921;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private boolean busy;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = Ui.column(this);
        Ui.mount(this, root);
        LinearLayout top = Ui.row(this);
        top.addView(Ui.button(this, "返回", this::finish));
        TextView title = Ui.title(this, "书库备份与恢复", 19);
        Ui.pad(title, 8);
        top.addView(title, Ui.weight());
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        Ui.pad(body, 12);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        body.addView(Ui.text(this, "便携备份包含书库数据（含阅读时长合计与分列会话）与书文件本体；SAF 授权、任务记录、章节缓存和设备字段一律不导出。恢复前会逐项校验 SHA-256，任何损坏立即中止，不会部分写入。", 14, Ui.MUTED));
        Ui.gap(body, 16);

        body.addView(Ui.primary(this, "导出备份（.zip）", this::chooseExport));
        Ui.gap(body, 10);
        body.addView(Ui.button(this, "从备份恢复", this::chooseImport));
        Ui.gap(body, 16);

        body.addView(Ui.text(this, "恢复说明", 15, Ui.GREEN));
        body.addView(Ui.text(this,
                "· 相同内容的书按 SHA-256 去重，进度、标注、想法、阅读时长统计自动迁移到目标书籍。\n"
                        + "· 同一本书同一天的阅读时长以备份为准（覆盖而非叠加），分列合计不会大于总时长。\n"
                        + "· 0.19 及更早版本导出的备份没有分列会话，恢复后「文本/漫画/听书」三列为 0，总时长与热力图照常。\n"
                        + "· 在线书的书源规则随备份保存；目标设备需安装相同书源才能按新规则抓取。\n"
                        + "· 恢复为追加式合并，不会删除设备上已有的书。", 13, Ui.MUTED));

        status = Ui.text(this, "", 13, Ui.MUTED);
        Ui.pad(status, 12);
        body.addView(status);
    }

    private void chooseExport() {
        if (busy) return;
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "openleaf-library-backup.zip");
            startActivityForResult(intent, EXPORT);
        } catch (Exception error) { status.setText("无法打开保存入口：" + error.getMessage()); }
    }

    private void chooseImport() {
        if (busy) return;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip");
            startActivityForResult(intent, IMPORT);
        } catch (Exception error) { status.setText("无法打开文件选择器：" + error.getMessage()); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == EXPORT) doExport(uri);
        else if (request == IMPORT) confirmImport(uri);
    }

    private void doExport(Uri uri) {
        busy = true;
        status.setText("正在准备导出…");
        worker.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("无法写入目标文件");
                Set<BackupManifest.Part> parts = new HashSet<>(Arrays.asList(
                        BackupManifest.Part.LIBRARY, BackupManifest.Part.ARCHIVE,
                        BackupManifest.Part.CATALOG));
                LibraryBackup backup = new LibraryBackup(this, stage ->
                        runOnUiThread(() -> status.setText("导出：" + stage)));
                int entries = backup.export(out, parts);
                List<String> missing = backup.missingFiles();
                StringBuilder done = new StringBuilder("导出完成，共 ").append(entries).append(" 个条目。");
                if (!missing.isEmpty()) {
                    int shown = Math.min(missing.size(), 5);
                    done.append(missing.size()).append(" 个书文件缺失，已跳过：")
                            .append(String.join("、", missing.subList(0, shown)))
                            .append(missing.size() > shown ? " 等" : "");
                }
                final String doneText = done.toString();
                runOnUiThread(() -> status.setText(doneText));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("导出失败：" + error.getMessage()));
            } finally { busy = false; }
        });
    }

    private void confirmImport(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("从备份恢复？")
                .setMessage("恢复为追加式合并：相同内容的书会复用，不会删除现有书籍。恢复前会完整校验备份，损坏则中止。进度、标注与阅读统计按书籍迁移，同一本书同一天的时长以备份为准。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始校验并恢复", (d, w) -> doImport(uri))
                .show();
    }

    private void doImport(Uri uri) {
        busy = true;
        status.setText("正在校验备份…");
        worker.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("无法读取备份文件");
                LibraryRestore restore = new LibraryRestore(this, stage ->
                        runOnUiThread(() -> status.setText("恢复：" + stage)));
                LibraryRestore.Preflight plan = restore.preflight(in);
                if (!plan.ok()) {
                    StringBuilder message = new StringBuilder("备份校验失败，已中止（未写入任何数据）：\n");
                    int shown = 0;
                    for (BackupManifest.Issue issue : plan.issues) {
                        if (!issue.fatal) continue;
                        message.append("\n").append(issue.toString());
                        if (++shown >= 8) break;
                    }
                    runOnUiThread(() -> status.setText(message.toString()));
                    return;
                }
                int restored = restore.commit(plan);
                // 阶段 3-B 收尾：commit 可能生成非致命 Issue（如跳过悬挂统计行）。
                // 必须在成功分支展示，否则等同静默丢弃——用户看不到"哪些数据被跳过"。
                // 全新备份 issues=0 时文案保持逐字不变，保证既有判据零回归。
                final int finalRestored = restored;
                runOnUiThread(() -> {
                    StringBuilder done = new StringBuilder("恢复完成，共 " + finalRestored + " 本书。");
                    if (plan.issues != null) {
                        int shown = 0;
                        for (BackupManifest.Issue issue : plan.issues) {
                            if (issue == null || issue.fatal) continue;
                            if (shown == 0) done.append("\n\n跳过或调整了以下内容：");
                            done.append("\n· ").append(issue.toString());
                            if (++shown >= 8) { done.append("\n· …其余略"); break; }
                        }
                    }
                    status.setText(done.toString());
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("恢复失败：" + error.getMessage()));
            } finally { busy = false; }
        });
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
