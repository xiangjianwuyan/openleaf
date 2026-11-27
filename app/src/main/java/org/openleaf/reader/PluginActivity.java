package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
        LinearLayout headerBand = Ui.column(this);
        headerBand.setBackgroundColor(PluginScreen.SURFACE);
        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        header.addView(PluginScreen.button(this, "返回", "返回上一页", PluginScreen.PAPER, PluginScreen.SAGE, this::finish));
        TextView title = PluginScreen.heading(this, "应用插件", 22);
        LinearLayout.LayoutParams titleParams = Ui.weight();
        titleParams.setMarginStart(Ui.dp(this, 14));
        header.addView(title, titleParams);
        PluginScreen.center(headerBand, header, 700);
        View divider = new View(this);
        divider.setBackgroundColor(PluginScreen.EDGE);
        headerBand.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        root.addView(headerBand);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = Ui.column(this);
        LinearLayout body = Ui.column(this);
        Ui.pad(body, 16);
        LinearLayout intro = PluginScreen.card(this, PluginScreen.SOFT);
        intro.addView(PluginScreen.heading(this, "本地文本工具", 19));
        Ui.gap(intro, 4);
        intro.addView(PluginScreen.text(this, "插件仅在你主动运行并选择文档后读取文件，不会修改原件或书库。", 14, PluginScreen.INK));
        Ui.gap(intro, 12);
        source = PluginScreen.button(this, "插件源", "选择插件来源，查看用途和访问范围", PluginScreen.SAGE, Color.WHITE, this::chooseSource);
        intro.addView(source, new LinearLayout.LayoutParams(-1, -2));
        body.addView(intro);
        activityStatus = Ui.column(this);
        activityStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(activityStatus);
        installed = Ui.column(this);
        body.addView(installed);
        PluginScreen.center(content, body, 700);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        PluginScreen.mount(this, root);
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
        LinearLayout body = Ui.column(this);
        body.addView(PluginScreen.text(this, "选择插件来源。查看用途和访问范围后，再确认安装。", 14, PluginScreen.MUTED));
        Ui.gap(body, 12);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("插件源")
                .setView(PluginScreen.dialogBody(this, body)).setNegativeButton("取消", null).create();
        addSourceCard(body, "随应用提供", "内置目录", "离线预览内置「文本体检」样例，无需准备文件。",
                "内置目录：添加官方样例", () -> {
                    dialog.dismiss();
                    try { confirmInstall(PluginManifest.parse(PluginManifest.BUILTIN), PluginStore.Source.BUILTIN); }
                    catch (IOException e) { error("无法打开内置插件", e); }
                });
        Ui.gap(body, 10);
        addSourceCard(body, "从设备导入", "本地 JSON", "用系统文件选择器导入声明式清单（最多 16 KiB），不执行脚本。",
                "本地 JSON：选择插件清单", () -> { dialog.dismiss(); pick(PICK_PLUGIN); });
        PluginScreen.show(dialog);
    }

    private void addSourceCard(LinearLayout body, String origin, String name, String purpose, String label, Runnable open) {
        LinearLayout card = PluginScreen.card(this, PluginScreen.SURFACE);
        card.addView(PluginScreen.state(this, origin, true));
        Ui.gap(card, 6);
        card.addView(PluginScreen.heading(this, name, 17));
        card.addView(PluginScreen.text(this, purpose, 14, PluginScreen.MUTED));
        Ui.gap(card, 8);
        card.addView(PluginScreen.button(this, label, label, PluginScreen.SOFT, PluginScreen.SAGE, open),
                new LinearLayout.LayoutParams(-1, -2));
        body.addView(card);
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
        installed.setVisibility(View.GONE);
        Ui.gap(activityStatus, 12);
        LinearLayout panel = PluginScreen.card(this, PluginScreen.SURFACE);
        LinearLayout progress = Ui.row(this);
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(PluginScreen.SAGE));
        spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams indicator = new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24));
        indicator.setMarginEnd(Ui.dp(this, 12));
        progress.addView(spinner, indicator);
        progress.addView(PluginScreen.text(this, label, 14, PluginScreen.INK), Ui.weight());
        panel.addView(progress);
        Ui.gap(panel, 8);
        panel.addView(PluginScreen.button(this, "取消", "取消当前插件读取操作", PluginScreen.PAPER, PluginScreen.SAGE, this::cancelRead));
        activityStatus.addView(panel);
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
        LinearLayout body = Ui.column(this);
        LinearLayout purpose = PluginScreen.card(this, PluginScreen.SURFACE);
        purpose.addView(PluginScreen.state(this, "来源：" + origin.label, true));
        Ui.gap(purpose, 8);
        purpose.addView(PluginScreen.heading(this, "用途", 16));
        purpose.addView(PluginScreen.text(this, manifest.description, 14, PluginScreen.INK));
        Ui.gap(purpose, 6);
        purpose.addView(PluginScreen.text(this, "ID：" + manifest.id, 13, PluginScreen.MUTED));
        purpose.addView(PluginScreen.text(this, "标题前缀：" + formatPrefixes(manifest.prefixes), 13, PluginScreen.MUTED));
        body.addView(purpose);
        Ui.gap(body, 10);
        LinearLayout access = PluginScreen.card(this, PluginScreen.SOFT);
        access.addView(PluginScreen.heading(this, "访问范围", 16));
        access.addView(PluginScreen.text(this, "只读你主动选择的 UTF-8 TXT / MD（最多 1 MiB）。不保留文件授权、不写入、不上传。", 14, PluginScreen.INK));
        Ui.gap(access, 6);
        access.addView(PluginScreen.text(this, "安装默认禁用，需手动启用后才能运行。", 13, PluginScreen.MUTED));
        body.addView(access);
        PluginScreen.show(new AlertDialog.Builder(this).setTitle("确认安装：" + manifest.name)
                .setView(PluginScreen.dialogBody(this, body))
                .setNegativeButton("取消", null).setPositiveButton("安装（默认禁用）", (dialog, which) -> {
                    try { store.install(manifest, origin); refresh(); }
                    catch (IOException e) { error("未安装插件", e); }
                }));
    }

    private void refresh() {
        installed.setVisibility(View.VISIBLE);
        installed.removeAllViews();
        Ui.gap(installed, 20);
        installed.addView(PluginScreen.heading(this, "已安装插件", 18));
        Ui.gap(installed, 4);
        try {
            List<PluginStore.Installed> entries = store.list();
            int enabled = 0;
            for (PluginStore.Installed entry : entries) if (entry.enabled) enabled++;
            TextView count = PluginScreen.text(this, entries.size() + " 个已安装 · " + enabled + " 个已启用", 13, PluginScreen.MUTED);
            installed.addView(count);
            if (entries.isEmpty()) {
                Ui.gap(installed, 12);
                LinearLayout empty = PluginScreen.card(this, PluginScreen.SURFACE);
                empty.addView(PluginScreen.heading(this, "还没有插件", 17));
                Ui.gap(empty, 4);
                empty.addView(PluginScreen.text(this, "从插件源添加内置样例，或导入本地 JSON 清单。", 14, PluginScreen.MUTED));
                installed.addView(empty);
            }
            for (PluginStore.Installed entry : entries) showEntry(entry);
        } catch (IOException e) {
            Ui.gap(installed, 12);
            LinearLayout failure = PluginScreen.card(this, PluginScreen.SURFACE);
            failure.addView(PluginScreen.text(this, "插件设置无法读取。没有运行任何插件。", 14, PluginScreen.INK));
            installed.addView(failure);
        }
    }

    private void showEntry(PluginStore.Installed entry) {
        Ui.gap(installed, 10);
        LinearLayout card = PluginScreen.card(this, PluginScreen.SURFACE);
        card.addView(PluginScreen.heading(this, entry.manifest.name, 18));
        Ui.gap(card, 4);
        card.addView(PluginScreen.state(this, entry.source.label + " · " + (entry.enabled ? "已启用" : "已禁用"), entry.enabled));
        Ui.gap(card, 8);
        card.addView(PluginScreen.text(this, entry.manifest.description, 14, PluginScreen.INK));
        Ui.gap(card, 4);
        card.addView(PluginScreen.text(this, "访问范围：仅在运行时读取所选文档；不保留授权、不写入、不上传。", 13, PluginScreen.MUTED));
        Ui.gap(card, 10);
        if (entry.enabled) {
            card.addView(PluginScreen.button(this, "运行：选择 TXT / MD", "运行插件 " + entry.manifest.name + "，选择 TXT 或 MD 文档", PluginScreen.SAGE, Color.WHITE, () -> {
                awaitingRunId = entry.manifest.id;
                pick(PICK_TEXT);
            }), new LinearLayout.LayoutParams(-1, -2));
            Ui.gap(card, 8);
        }
        String toggle = entry.enabled ? "禁用" : "启用";
        LinearLayout actions = Ui.row(this);
        LinearLayout.LayoutParams toggleParams = Ui.weight();
        toggleParams.setMarginEnd(Ui.dp(this, 8));
        actions.addView(PluginScreen.button(this, toggle, toggle + " " + entry.manifest.name,
                PluginScreen.SOFT, PluginScreen.SAGE, () -> {
                    try { store.setEnabled(entry.manifest.id, !entry.enabled); refresh(); }
                    catch (IOException e) { error("未更新插件", e); }
                }), toggleParams);
        actions.addView(PluginScreen.button(this, "移除", "移除 " + entry.manifest.name,
                PluginScreen.SURFACE, PluginScreen.REMOVE, () -> PluginScreen.show(new AlertDialog.Builder(this)
                        .setTitle("移除此插件？")
                        .setMessage(entry.manifest.name + "（只删除应用内的安装记录，不会删除任何源文件。）")
                        .setNegativeButton("取消", null).setPositiveButton("移除", (dialog, which) -> {
                            try { store.remove(entry.manifest.id); refresh(); }
                            catch (IOException e) { error("未移除插件", e); }
                        }))), Ui.weight());
        card.addView(actions);
        installed.addView(card);
    }

    private void showReport(PluginStore.Installed entry, PluginTextReport.Report report) {
        LinearLayout body = Ui.column(this);
        LinearLayout metrics = PluginScreen.card(this, PluginScreen.SOFT);
        metrics.addView(PluginScreen.heading(this, "文本统计", 16));
        Ui.gap(metrics, 6);
        String summary = "总行数：" + report.lines() + "\n空行：" + report.blankLines()
                + "\n字符数（不含换行）：" + report.codePoints() + "\n标题候选：" + report.headings();
        metrics.addView(PluginScreen.text(this, summary, 15, PluginScreen.INK));
        body.addView(metrics);
        Ui.gap(body, 10);
        LinearLayout findings = PluginScreen.card(this, PluginScreen.SURFACE);
        findings.addView(PluginScreen.heading(this, "标题示例", 16));
        Ui.gap(findings, 4);
        findings.addView(PluginScreen.text(this, "匹配前缀：" + formatPrefixes(entry.manifest.prefixes), 13, PluginScreen.MUTED));
        Ui.gap(findings, 8);
        if (report.examples().isEmpty()) {
            findings.addView(PluginScreen.text(this, "未找到匹配的标题示例。", 14, PluginScreen.MUTED));
        } else {
            findings.addView(PluginScreen.text(this, "最多显示 20 条", 13, PluginScreen.MUTED));
            for (PluginTextReport.Finding finding : report.examples()) {
                Ui.gap(findings, 4);
                findings.addView(PluginScreen.text(this, "第 " + finding.lineNumber() + " 行 · " + finding.text(), 14, PluginScreen.INK));
            }
        }
        body.addView(findings);
        Ui.gap(body, 10);
        body.addView(PluginScreen.text(this, "仅按字面前缀匹配，不会改动正文或阅读器目录。结果仅显示在此面板。", 13, PluginScreen.MUTED));
        resultDialog = PluginScreen.show(new AlertDialog.Builder(this).setTitle(entry.manifest.name + " · 结果")
                .setView(PluginScreen.dialogBody(this, body)).setPositiveButton("完成", null));
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
