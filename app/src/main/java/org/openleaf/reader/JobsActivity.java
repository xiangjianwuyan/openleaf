package org.openleaf.reader;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 任务中心：把 jobs/job_items 从「只有数据」变成用户可见可操作。
 * 修复了「下载失败，请查看任务记录」的悬空提示——现在这个页面真实存在。
 */
public final class JobsActivity extends Activity {
    /** 重试在后台跑、不往 Activity 推进度，页面靠这个间隔自己重绘，直到没有运行中的任务。 */
    private static final long REFRESH_INTERVAL_MS = 800;
    private SqliteJobRepository jobs;
    private LinearLayout list;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pollTick = this::refresh;

    @Override protected void onDestroy() {
        // 轮询只活在本页面生命周期内：不撤销会让已销毁的 Activity 一直被回调引着。
        main.removeCallbacks(pollTick);
        super.onDestroy();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        jobs = new SqliteJobRepository(this);
        LinearLayout page = Ui.column(this);
        Ui.mount(this, page);
        Ui.pad(page, 20);
        page.addView(Ui.title(this, "任务中心", 22));
        page.addView(Ui.text(this, "导入与下载任务；逐项状态持久化，本地导入任务可重试", 12, Ui.MUTED));
        Ui.gap(page, 16);
        // 任务项可能有上千条：列表必须可滚动，否则详情与按钮会被挤出屏幕。
        list = Ui.column(this);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        render(list);
        LinearLayout footer = Ui.row(this);
        footer.addView(Ui.button(this, "刷新", this::refresh), Ui.weight());
        footer.addView(Ui.button(this, "返回", this::finish), Ui.weight());
        page.addView(footer);
    }

    /**
     * 重绘列表。重试在后台线程跑、不往 Activity 推进度，页面自己不会变；
     * 之前只有 recreate() 能更新，用户看完一轮重试没有任何同步手段。
     *
     * <p>现在：只要本进程内还有重试在跑，就隔 {@link #REFRESH_INTERVAL_MS} 自己重绘一次，
     * 终止条件是「没有运行中的任务」——不是无限轮询，跑完自动停。
     */
    private void refresh() {
        if (list == null) return;
        main.removeCallbacks(pollTick); // 先撤销上一轮排的 tick，避免多次点击后叠加成多路轮询
        list.removeAllViews();
        render(list);
        if (JobRetryRunner.isAnythingRunning()) main.postDelayed(pollTick, REFRESH_INTERVAL_MS);
    }

    private void render(LinearLayout root) {
        List<SqliteJobRepository.JobSummary> all = jobs.listJobs();
        if (all.isEmpty()) {
            root.addView(Ui.text(this, "暂无任务", 14, Ui.MUTED));
            return;
        }
        for (SqliteJobRepository.JobSummary job : all) {
            LinearLayout card = Ui.column(this);
            Ui.pad(card, 14);
            card.setBackground(Ui.shape(0xFFF2EFE9, 10, this));
            String kind = "import".equals(job.kind) ? "导入" : "download".equals(job.kind) ? "下载" : job.kind;
            card.addView(Ui.text(this, kind + " · " + stateLabel(job.state), 15, Ui.INK));
            card.addView(Ui.text(this, "处理 " + job.processed + " · 新增 " + job.added
                    + " · 重复 " + job.duplicates + " · 失败 " + job.failed, 13, Ui.MUTED));
            if (job.sourceTree != null && !job.sourceTree.isEmpty())
                card.addView(Ui.text(this, "来源：" + job.sourceTree, 12, Ui.MUTED));
            // 逐项展开失败项（用户真正需要的「哪本书 / 为什么」）；成功项只给汇总，避免长列表刷屏。
            List<org.openleaf.reader.core.ContentContracts.JobItem> items = jobs.items(job.id);
            List<org.openleaf.reader.core.ContentContracts.JobItem> failures = new java.util.ArrayList<>();
            int done = 0, duplicates = 0;
            for (org.openleaf.reader.core.ContentContracts.JobItem item : items) {
                if (item.state == org.openleaf.reader.core.ContentContracts.ItemState.FAILED) failures.add(item);
                else if (item.state == org.openleaf.reader.core.ContentContracts.ItemState.DUPLICATE) duplicates++;
                else if (item.state == org.openleaf.reader.core.ContentContracts.ItemState.DONE) done++;
            }
            if (!failures.isEmpty()) {
                card.addView(Ui.text(this, "失败明细（" + failures.size() + "）", 13, Ui.INK));
                for (org.openleaf.reader.core.ContentContracts.JobItem item : failures) {
                    String label = "✕ " + (item.relativePath == null || item.relativePath.isEmpty() ? item.uri : item.relativePath);
                    if (item.error != null && !item.error.isEmpty()) label += "（" + item.error + "）";
                    TextView line = Ui.text(this, label, 12, Ui.MUTED);
                    line.setTextIsSelectable(true);
                    card.addView(line);
                }
            }
            if (done + duplicates > 0)
                card.addView(Ui.text(this, "已完成 " + done + " · 重复 " + duplicates, 12, Ui.MUTED));
            final String jobId = job.id;
            // 只有本地导入类的任务能被 JobRetryRunner 真正重跑；download 重下需要原下载页的链接与策略。
            final boolean retryable = "folder".equals(job.kind) || "import".equals(job.kind);
            final boolean running = JobRetryRunner.isRunning(jobId);
            // 被「停止」的任务剩下的条目还是 PENDING：执行器挑的就是 {FAILED, PENDING}，所以它同样能续跑，
            // 之前只按 failed 判断会让取消后的任务永远没有入口。
            final boolean resumable = job.pending > 0;
            LinearLayout actions = Ui.row(this);
            // 只有「纯续跑」（没有失败项、只剩未处理项）才显示条数；failed 计数与条目状态不一致的
            // 历史任务会落到「重试失败项」，不至于出现「继续未完成项（0）」这种自相矛盾的标签。
            final String actionLabel = job.failed == 0 && job.pending > 0
                    ? "继续未完成项（" + job.pending + "）" : "重试失败项";
            if (!running && (job.failed > 0 || resumable || "FAILED".equals(job.state)))
                actions.addView(Ui.button(this, actionLabel, () -> {
                    if (!retryable) {
                        Toast.makeText(this, "download".equals(job.kind)
                                ? "下载任务不支持自动重试，请在原下载页重新发起"
                                : "该任务类型不支持自动重试", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 先清掉上一轮的失败标记与错误文案，再把条目交给执行器（同一次点击内完成，没有竞态）。
                    // 纯 PENDING（取消后续跑）时 retryFailed 返回 0 且不动 job 状态，start() 照样挑得到。
                    jobs.retryFailed(jobId);
                    int started = JobRetryRunner.start(this, jobId);
                    if (started > 0)
                        Toast.makeText(this, job.failed > 0
                                ? "已开始重试 " + started + " 项"
                                : "已继续导入 " + started + " 项", Toast.LENGTH_SHORT).show();
                    else if (started == JobRetryRunner.ALREADY_RUNNING)
                        Toast.makeText(this, "该任务正在重试，请稍候", Toast.LENGTH_SHORT).show();
                    else
                        Toast.makeText(this, "没有可重试的条目", Toast.LENGTH_SHORT).show();
                    refresh();
                }), Ui.weight());
            // running 也算「可停止」：续跑刚启动时库里的状态还是 CANCELLED（要等第一个检查点才置 RUNNING），
            // 只按状态判断会让刚点完「继续未完成项」的用户在这段时间里找不到停止入口。
            if (running || (!"DONE".equals(job.state) && !"CANCELLED".equals(job.state)))
                actions.addView(Ui.button(this, "停止任务", () -> {
                    // 先真停执行器（取消信号），再落库；只改库不停线程等于骗用户。
                    boolean stopped = JobRetryRunner.cancel(jobId);
                    jobs.cancel(jobId);
                    Toast.makeText(this, stopped ? "已停止" : "任务未在运行，已标记为已取消",
                            Toast.LENGTH_SHORT).show();
                    refresh();
                }), Ui.weight());
            if (actions.getChildCount() > 0) card.addView(actions);
            Ui.gap(root, 10);
            root.addView(card);
        }
    }

    private static String stateLabel(String state) {
        if (state == null) return "未知";
        switch (state) {
            case "PENDING": return "待处理";
            case "RUNNING": return "进行中";
            case "PAUSED": return "已暂停";
            case "DONE": return "完成";
            case "CANCELLED": return "已取消";
            case "FAILED": return "失败";
            default: return state;
        }
    }
}
