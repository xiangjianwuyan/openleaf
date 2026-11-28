package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Debug-only, read-only report. One dialog owns one cancellable job. */
final class ParagraphPreview {
    private static final int PANEL = 0xFFF6F3EB;
    private static final int SAGE = 0xFF315C48;
    private static final int PALE = 0xFFE5EEE5;
    private ParagraphPreview() { }

    static void show(Activity owner, String source) {
        LinearLayout content = Ui.column(owner);
        Ui.pad(content, 16);
        content.setBackgroundColor(PANEL);
        content.addView(Ui.text(owner, "仅检查当前章 · 按未重排文本计行 · 不修改正文", 14, Ui.MUTED));
        Ui.gap(content, 12);
        TextView status = Ui.text(owner, "正在检查当前章…", 16, Ui.INK);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        LinearLayout result = Ui.column(owner);
        content.addView(result);
        ScrollView scroll = new ScrollView(owner);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(owner).setTitle("段落体检（试用）")
                .setView(scroll).setNegativeButton("取消", null).create();
        Thread job = new Thread(() -> {
            ParagraphInspection.Report report;
            try {
                report = ParagraphInspection.inspect(source);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return;
            } catch (IllegalArgumentException tooLong) {
                report = null;
            }
            if (Thread.currentThread().isInterrupted()) return;
            ParagraphInspection.Report data = report;
            owner.runOnUiThread(() -> {
                if (!dialog.isShowing() || owner.isFinishing() || owner.isDestroyed()) return;
                if (data == null) {
                    status.setText("当前章节超过检查上限，未生成报告。");
                } else {
                    String overview = "共 " + data.lines() + " 行，过长段落 " + data.longParagraphs()
                            + " 处，连续空行 " + data.consecutiveBlankRuns()
                            + " 处，疑似硬换行 " + data.suspectedHardWraps() + " 处。";
                    status.setText("检查完成。" + overview);
                    render(owner, result, data);
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("关闭");
            });
        }, "OpenLeaf-paragraph-preview");
        dialog.setOnDismissListener(d -> job.interrupt());
        dialog.show();
        job.start();
    }

    private static void render(Activity owner, LinearLayout result, ParagraphInspection.Report report) {
        Ui.gap(result, 12);
        if (report.examples().isEmpty()) {
            TextView empty = Ui.text(owner, "未发现这三类排版问题。", 15, SAGE);
            Ui.pad(empty, 12);
            empty.setBackground(Ui.shape(PALE, 12, owner));
            result.addView(empty);
            return;
        }
        result.addView(Ui.text(owner, "以下只列部分示例。疑似硬换行仅供参考。", 14, Ui.MUTED));
        for (ParagraphInspection.Kind kind : ParagraphInspection.Kind.values()) {
            int count = count(report, kind);
            if (count == 0) continue;
            Ui.gap(result, 12);
            LinearLayout card = Ui.column(owner);
            Ui.pad(card, 12);
            card.setBackground(Ui.outline(owner, 0xFFFFFDF7, 12));
            TextView heading = Ui.title(owner, label(kind) + " · " + count + " 处", 16);
            heading.setTextColor(SAGE);
            if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
            card.addView(heading);
            for (ParagraphInspection.Finding item : report.examples()) {
                if (item.kind() != kind) continue;
                String range = "第 " + item.firstLine() + (item.firstLine() == item.lastLine()
                        ? "" : "–" + item.lastLine()) + " 行";
                TextView line = Ui.text(owner, range, 15, Ui.INK);
                line.setPadding(0, Ui.dp(owner, 6), 0, 0);
                card.addView(line);
            }
            result.addView(card);
        }
    }

    private static int count(ParagraphInspection.Report report, ParagraphInspection.Kind kind) {
        switch (kind) {
            case LONG_PARAGRAPH: return report.longParagraphs();
            case CONSECUTIVE_BLANKS: return report.consecutiveBlankRuns();
            default: return report.suspectedHardWraps();
        }
    }

    private static String label(ParagraphInspection.Kind kind) {
        switch (kind) {
            case LONG_PARAGRAPH: return "过长段落";
            case CONSECUTIVE_BLANKS: return "连续空行";
            default: return "疑似硬换行";
        }
    }
}
