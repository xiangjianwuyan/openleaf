package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Activity owned by OpenLeaf, not an external extension host. */
public final class PluginActivity extends Activity {
    private static final int PICK_PLUGIN = 691;
    private static final int PICK_TEXT = 692;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PluginStore store;
    private LinearLayout installed, activityStatus;
    private Button source;
    private String awaitingRunId;
    private CancellationSignal signal;
    private Future<?> task;
    private AlertDialog resultDialog;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new PluginStore(this);
        if (state != null) awaitingRunId = state.getString("awaiting_run_id");
        LinearLayout root = Ui.column(this);
        LinearLayout header = Ui.row(this);
        Ui.pad(header, 10);
        header.addView(Ui.button(this, "返回", this::finish, "返回上一页"));
        header.addView(Ui.title(this, "应用插件", 22), Ui.weight());
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        Ui.pad(body, 16);
        body.addView(Ui.text(this, "插件只提供声明式文本工具。只有你主动运行并选择文档后，才读取该文档；不会修改原文件或书库。", 15, Ui.MUTED));
        Ui.gap(body, 12);
        source = Ui.primary(this, "插件源", this::chooseSource);
        body.addView(source);
        activityStatus = Ui.column(this);
        body.addView(activityStatus);
        installed = Ui.column(this);
        body.addView(installed);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.mount(this, root);
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("awaiting_run_id", awaitingRunId);
    }

    @Override protected void onDestroy() {
        cancelRead();
        worker.shutdownNow();
        if (resultDialog != null) resultDialog.dismiss();
        super.onDestroy();
    }

    private void chooseSource() {
        new AlertDialog.Builder(this).setTitle("插件源").setItems(new String[]{
                "内置目录：添加官方样例", "本地 JSON：选择插件清单"
        }, (dialog, which) -> {
            if (which == 0) {
                try { confirmInstall(PluginManifest.parse(PluginManifest.BUILTIN), PluginStore.Source.BUILTIN); }
                catch (IOException e) { error("无法打开内置插件", e); }
            } else pick(PICK_PLUGIN);
        }).setNegativeButton("取消", null).show();
    }

    private void pick(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // SAF providers may label JSON / Markdown as generic text or binary. Trust the bounded parser, not MIME.
        try { startActivityForResult(intent, request); }
        catch (android.content.ActivityNotFoundException unavailable) {
            awaitingRunId = null;
            error("没有可用的系统文件选择器", null);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PLUGIN && request != PICK_TEXT) return;
        String runId = awaitingRunId;
        awaitingRunId = null;
        if (result != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            error("必须从系统选择一个文档", null);
            return;
        }
        if (request == PICK_PLUGIN) {
            executeRead("正在验证 JSON 清单…", current -> {
                PluginManifest manifest = PluginManifest.parse(read(uri, PluginManifest.MAX_BYTES, current));
                if (manifest.id.startsWith("openleaf.")) throw new IOException("openleaf.* 仅用于内置插件");
                return () -> confirmInstall(manifest, PluginStore.Source.LOCAL);
            });
        } else if (runId != null) {
            try {
                PluginStore.Installed entry = store.get(runId);
                if (entry == null || !entry.enabled) {
                    error("插件已禁用或移除，未读取任何文件", null);
                    return;
                }
                executeRead("正在分析所选文本…", current -> {
                    PluginTextReport.Report report = PluginTextReport.analyze(
                            read(uri, PluginTextReport.MAX_TEXT_BYTES, current), entry.manifest.prefixes);
                    return () -> showReport(entry, report);
                });
            } catch (IOException e) { error("无法运行插件", e); }
        }
    }

    private String read(Uri uri, int limit, CancellationSignal current) throws IOException {
        try (AssetFileDescriptor descriptor = getContentResolver().openAssetFileDescriptor(uri, "r", current)) {
            if (descriptor == null) throw new IOException("无法打开所选文件");
            if (descriptor.getDeclaredLength() > limit) throw new IOException("文件超出大小限制（" + (limit / 1024) + " KiB）");
            try (InputStream stream = descriptor.createInputStream()) {
                return PluginTextReport.readUtf8(stream, limit);
            }
        }
    }

    private interface ReadResult { Runnable read(CancellationSignal signal) throws IOException; }

    private void executeRead(String label, ReadResult operation) {
        if (signal != null) return;
        CancellationSignal current = new CancellationSignal();
        signal = current;
        source.setEnabled(false);
        installed.removeAllViews();
        activityStatus.addView(Ui.text(this, label, 15, Ui.MUTED));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setContentDescription(label);
        activityStatus.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
        activityStatus.addView(Ui.button(this, "取消", this::cancelRead));
        task = worker.submit(() -> {
            try {
                Runnable result = operation.read(current);
                runOnUiThread(() -> finishRead(current, result));
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> finishRead(current, () -> error("操作未完成", e)));
            }
        });
    }

    private void finishRead(CancellationSignal current, Runnable result) {
        if (signal != current || isFinishing() || isDestroyed()) return;
        signal = null;
        task = null;
        source.setEnabled(true);
        activityStatus.removeAllViews();
        refresh();
        result.run();
    }

    private void cancelRead() {
        CancellationSignal current = signal;
        Future<?> previous = task;
        signal = null;
        task = null;
        if (previous != null) previous.cancel(true);
        if (current != null) {
            // Remote providers can ignore cancellation; detach the stale UI result regardless.
            new Thread(current::cancel, "OpenLeaf-plugin-cancel").start();
            source.setEnabled(true);
            activityStatus.removeAllViews();
            if (!isFinishing() && !isChangingConfigurations()) refresh();
        }
    }

    private void confirmInstall(PluginManifest manifest, PluginStore.Source origin) {
        String details = "来源：" + origin.label + "\nID：" + manifest.id + "\n\n" + manifest.description
                + "\n\n能力：只读文本体检（UTF-8 TXT / MD，最多 1 MiB）"
                + "\n标题前缀：" + formatPrefixes(manifest.prefixes)
                + "\n\n安装默认禁用。运行时需主动选择文档，不保存文件授权，不上传。";
        new AlertDialog.Builder(this).setTitle("确认安装：" + manifest.name).setMessage(details)
                .setNegativeButton("取消", null).setPositiveButton("安装（默认禁用）", (dialog, which) -> {
                    try { store.install(manifest, origin); refresh(); }
                    catch (IOException e) { error("未安装插件", e); }
                }).show();
    }

    private void refresh() {
        installed.removeAllViews();
        Ui.gap(installed, 16);
        TextView heading = Ui.title(this, "已安装插件", 18);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        installed.addView(heading);
        try {
            List<PluginStore.Installed> entries = store.list();
            if (entries.isEmpty()) installed.addView(Ui.text(this, "还没有插件。可从插件源添加内置样例或选择本地 JSON。", 15, Ui.MUTED));
            for (PluginStore.Installed entry : entries) showEntry(entry);
        } catch (IOException e) { installed.addView(Ui.text(this, "插件设置无法读取。没有运行任何插件。", 15, Ui.INK)); }
    }

    private void showEntry(PluginStore.Installed entry) {
        Ui.gap(installed, 10);
        LinearLayout card = Ui.column(this);
        Ui.pad(card, 12);
        card.setBackground(Ui.outline(this, android.graphics.Color.WHITE, 12));
        card.addView(Ui.title(this, entry.manifest.name, 17));
        card.addView(Ui.text(this, entry.source.label + " · " + (entry.enabled ? "已启用" : "已禁用"), 14, Ui.MUTED));
        card.addView(Ui.text(this, entry.manifest.id, 13, Ui.MUTED));
        card.addView(Ui.text(this, entry.manifest.description, 14, Ui.INK));
        if (entry.enabled) card.addView(Ui.primary(this, "运行：选择 TXT / MD", () -> {
            awaitingRunId = entry.manifest.id;
            pick(PICK_TEXT);
        }));
        String toggle = entry.enabled ? "禁用" : "启用";
        LinearLayout actions = Ui.row(this);
        actions.addView(Ui.button(this, toggle, () -> {
            try { store.setEnabled(entry.manifest.id, !entry.enabled); refresh(); }
            catch (IOException e) { error("未更新插件", e); }
        }, toggle + " " + entry.manifest.name), Ui.weight());
        actions.addView(Ui.button(this, "移除", () -> new AlertDialog.Builder(this).setTitle("移除此插件？")
                .setMessage(entry.manifest.name + "（只删除应用内的安装记录，不会删除任何源文件。）")
                .setNegativeButton("取消", null).setPositiveButton("移除", (dialog, which) -> {
                    try { store.remove(entry.manifest.id); refresh(); }
                    catch (IOException e) { error("未移除插件", e); }
                }).show(), "移除 " + entry.manifest.name), Ui.weight());
        card.addView(actions);
        installed.addView(card);
    }

    private void showReport(PluginStore.Installed entry, PluginTextReport.Report report) {
        StringBuilder summary = new StringBuilder("总行数：").append(report.lines())
                .append("\n空行：").append(report.blankLines()).append("\n字符数（不含换行）：").append(report.codePoints())
                .append("\n标题候选：").append(report.headings()).append("\n\n匹配前缀：").append(formatPrefixes(entry.manifest.prefixes));
        if (!report.examples().isEmpty()) {
            summary.append("\n\n标题示例（最多 20 条）");
            for (PluginTextReport.Finding finding : report.examples()) summary.append("\n第 ").append(finding.lineNumber())
                    .append(" 行 · ").append(finding.text());
        }
        LinearLayout body = Ui.column(this);
        Ui.pad(body, 16);
        body.addView(Ui.text(this, summary.toString(), 15, Ui.INK));
        body.addView(Ui.text(this, "\n仅按字面前缀匹配；不会改动正文或阅读器目录。此次结果仅显示在本面板。", 14, Ui.MUTED));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        resultDialog = new AlertDialog.Builder(this).setTitle(entry.manifest.name + " · 结果")
                .setView(scroll).setPositiveButton("完成", null).show();
    }

    private String formatPrefixes(List<String> prefixes) {
        StringBuilder display = new StringBuilder();
        for (String prefix : prefixes) {
            if (display.length() > 0) display.append("、");
            display.append('「').append(prefix).append('」');
        }
        return display.toString();
    }

    private void error(String title, Exception e) {
        String message = e instanceof IOException ? e.getMessage() : null;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message == null ? "请重新选择文件后重试。" : message)
                .setPositiveButton("确定", null).show();
    }
}
