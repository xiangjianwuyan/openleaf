package org.openleaf.reader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 阅读统计页：今日/累计/连续天数 + 近 7 日柱状 + 近 12 周热力图 + 书籍时长榜。 */
public final class StatsActivity extends Activity {
    private ReadingStatsStore stats;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        stats = new ReadingStatsStore(this);
        LinearLayout root = Ui.column(this);
        Ui.mount(this, root);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        Ui.pad(body, 20);
        body.addView(Ui.title(this, "阅读记录", 22));
        body.addView(Ui.text(this, "数据仅保存在本机", 12, Ui.MUTED));
        Ui.gap(body, 16);

        // 为你推荐：只读本机数据，不新增表、不新增 Activity（设计 §2 / Q3）
        addRecommendations(body);

        long total = stats.totalSeconds();
        long today = stats.todaySeconds();
        int streak = stats.streakDays();
        int activeDays = stats.activeDays();
        body.addView(Ui.text(this, "累计阅读  " + duration(total), 18, Ui.INK));
        body.addView(Ui.text(this, "今日阅读  " + duration(today), 16, Ui.GREEN));
        body.addView(Ui.text(this, "连续 " + streak + " 天  ·  累计阅读 " + activeDays + " 天", 14, Ui.MUTED));

        // 分列口径：文本 / 漫画 / 音视频，各自独立，不再混为一个总数
        java.util.Map<org.openleaf.reader.core.ReadingLedger.Kind, Long> byKind = stats.totalsByKind();
        StringBuilder split = new StringBuilder("分列统计  ");
        for (org.openleaf.reader.core.ReadingLedger.Kind kind : org.openleaf.reader.core.ReadingLedger.Kind.values()) {
            long value = byKind.get(kind) == null ? 0L : byKind.get(kind);
            split.append(kind.label()).append(' ').append(duration(value)).append("   ");
        }
        body.addView(Ui.text(this, split.toString(), 13, Ui.MUTED));
        // 0.22.0：漫画与本地音频按文件的稳定指纹归属，写独立媒体表，和书库阅读一起计入三列。
        body.addView(Ui.text(this, "漫画与本地音频按文件归属计入分列；同一媒体跨设备恢复后独立累计。", 12, Ui.MUTED));
        // 每日目标（借鉴 Shelf：以「页」为统一货币，streak 由日期分桶派生）
        int goal = getSharedPreferences("shelf", 0).getInt("daily_goal_pages", 20);
        int todayPages = org.openleaf.reader.core.ReadingHabit.secondsToPages(today);
        // 剩余时间：以今日目标进度估算，明确标注"估算"而非精确值
        float todayProgress = goal > 0 ? Math.min(1f, (float) todayPages / goal) : 0f;
        long estimated = org.openleaf.reader.core.ReadingLedger.estimateRemainingSeconds(today, todayProgress);
        body.addView(Ui.text(this, org.openleaf.reader.core.ReadingLedger.estimatedLabel(estimated), 12, Ui.MUTED));
        org.openleaf.reader.core.ReadingHabit.State habitState = new org.openleaf.reader.core.ReadingHabit.State(goal);
        java.util.Map<String, Long> recent = stats.dailyTotals(400);
        for (java.util.Map.Entry<String, Long> entry : recent.entrySet())
            habitState.addPages(java.time.LocalDate.parse(entry.getKey()),
                    org.openleaf.reader.core.ReadingHabit.secondsToPages(entry.getValue()));
        org.openleaf.reader.core.ReadingHabit.Habit habit = org.openleaf.reader.core.ReadingHabit.habitOf(habitState, java.time.LocalDate.now());
        body.addView(Ui.text(this, "今日目标  " + todayPages + " / " + (goal > 0 ? goal + " 页" : "未设置")
                + (habit.done ? "  ✓ 已达标" : ""), 14, habit.done ? Ui.GREEN : Ui.MUTED));
        body.addView(Ui.button(this, "设置每日目标（当前 " + goal + " 页）", () -> showGoalDialog(goal)));
        Ui.gap(body, 20);

        body.addView(Ui.title(this, "最近 7 天", 16));
        Map<String, Long> last7 = stats.dailyTotals(7);
        body.addView(new BarChart(this, last7));
        Ui.gap(body, 18);

        body.addView(Ui.title(this, "近 12 周", 16));
        body.addView(Ui.text(this, "每格一天，颜色越深读得越久", 12, Ui.MUTED));
        body.addView(new Heatmap(this, stats.dailyTotals(84)));
        Ui.gap(body, 18);

        body.addView(Ui.title(this, "书籍时长榜", 16));
        List<ReadingStatsStore.BookTime> ranking = stats.bookRanking(10);
        if (ranking.isEmpty()) {
            body.addView(Ui.text(this, "还没有阅读记录", 13, Ui.MUTED));
        } else {
            for (int i = 0; i < ranking.size(); i++) {
                ReadingStatsStore.BookTime item = ranking.get(i);
                body.addView(Ui.text(this, (i + 1) + ". " + item.title + "  ·  " + duration(item.seconds),
                        13, Ui.INK));
            }
        }
        Ui.gap(body, 20);
        body.addView(Ui.button(this, "清空统计", () -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("清空阅读记录")
                    .setMessage("将删除本机所有阅读时长统计，不影响书籍与标注。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清空", (d, w) -> { stats.clearAll(); recreate(); })
                    .show();
        }));
        body.addView(Ui.button(this, "返回书架", this::finish));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    /**
     * 顶部「为你推荐」区块：拼装现有只读数据源喂给 core/Recommendation，
     * 同步计算（与设计 §12 的「正在根据本机阅读记录计算…」同帧出结果）。
     */
    private void addRecommendations(LinearLayout body) {
        body.addView(Ui.title(this, "为你推荐 · 仅根据本机记录", 16));
        TextView state = Ui.text(this, "正在根据本机阅读记录计算…", 13, Ui.MUTED);
        body.addView(state);
        java.util.List<org.openleaf.reader.core.Recommendation.Suggestion> suggestions = recommend();
        if (suggestions == null) {
            state.setText("记录还不够：读满 1 分钟并留下标签后，这里会出现本地推荐");
        } else if (suggestions.isEmpty()) {
            state.setText("书架里暂无可推荐的其他书籍");
        } else {
            state.setVisibility(View.GONE);
            for (final org.openleaf.reader.core.Recommendation.Suggestion s : suggestions) {
                TextView line = Ui.text(this, s.title + "  ·  " + s.reason, 13, Ui.INK);
                line.setMaxLines(2);
                line.setEllipsize(android.text.TextUtils.TruncateAt.END);
                line.setContentDescription("查看书籍详情 " + s.title);
                line.setOnClickListener(v -> startActivity(new Intent(this, BookDetailActivity.class)
                        .putExtra("bookId", s.bookId)));
                body.addView(line);
            }
        }
        Ui.gap(body, 18);
    }

    /**
     * 只读拼装：bookRanking(50) → 时长；allTags() → 标签；opened_&lt;id&gt; → 最近阅读；all() → 候选。
     * 返回 null 表示信号不足（§12 数据不足态），空列表表示信号有但无候选。
     */
    private java.util.List<org.openleaf.reader.core.Recommendation.Suggestion> recommend() {
        try {
            BookStore store = new BookStore(this);
            Map<String, List<String>> tags = store.allTags();
            SharedPreferences reading = getSharedPreferences("reader", 0);
            List<org.openleaf.reader.core.Recommendation.Signal> signals =
                    new java.util.ArrayList<>();
            int usable = 0;
            for (ReadingStatsStore.BookTime bookTime : stats.bookRanking(50)) {
                List<String> bookTags = tags.get(bookTime.bookId);
                long opened = reading.getLong("opened_" + bookTime.bookId, 0);
                if (bookTags == null || bookTags.isEmpty()) continue;
                if (bookTime.seconds < org.openleaf.reader.core.Recommendation.DEFAULT_MIN_SECONDS || opened <= 0) continue;
                usable++;
                signals.add(new org.openleaf.reader.core.Recommendation.Signal(
                        bookTime.bookId, bookTags, bookTime.seconds, opened));
            }
            if (usable == 0) return null;
            List<org.openleaf.reader.core.Recommendation.Candidate> candidates =
                    new java.util.ArrayList<>();
            for (BookStore.Book book : store.all()) {
                List<String> bookTags = tags.get(book.id);
                candidates.add(new org.openleaf.reader.core.Recommendation.Candidate(
                        book.id, book.title, bookTags == null ? java.util.Collections.<String>emptyList() : bookTags));
            }
            return org.openleaf.reader.core.Recommendation.rank(signals, candidates,
                    java.util.Collections.<String>emptySet(), org.openleaf.reader.core.Recommendation.DEFAULT_LIMIT);
        } catch (Exception unavailable) {
            // 无数据库/授权异常时按「数据不足」中性文案收口，不抛原始异常
            return null;
        }
    }

    private void showGoalDialog(int current) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        input.setHint("页数，0 表示关闭目标");
        new android.app.AlertDialog.Builder(this)
                .setTitle("每日阅读目标")
                .setMessage("按 90 秒 ≈ 1 页换算；0 表示关闭。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString().trim());
                        if (value < 0 || value > 500) throw new NumberFormatException();
                        getSharedPreferences("shelf", 0).edit().putInt("daily_goal_pages", value).apply();
                        recreate();
                    } catch (NumberFormatException invalid) {
                        android.widget.Toast.makeText(this, "请输入 0–500 之间的页数", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    static String duration(long seconds) {
        long s = Math.max(0, seconds);
        long hours = s / 3600;
        long minutes = (s % 3600) / 60;
        if (hours > 0) return hours + " 小时 " + minutes + " 分";
        return minutes + " 分钟";
    }

    /** 横向 7 日柱状图：柱高按当天秒数归一化。 */
    private static final class BarChart extends View {
        private final Map<String, Long> data;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        BarChart(Context c, Map<String, Long> data) {
            super(c);
            this.data = data;
            bar.setColor(0xFF6B8E6B);
            label.setColor(Ui.MUTED);
            label.setTextSize(Ui.dp(c, 11));
            setMinimumHeight(Ui.dp(c, 150));
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long max = 1;
            for (long v : data.values()) max = Math.max(max, v);
            int n = data.size();
            float slot = getWidth() / (float) n;
            float base = getHeight() - Ui.dp(getContext(), 24);
            int i = 0;
            for (Map.Entry<String, Long> entry : data.entrySet()) {
                float h = (float) entry.getValue() / max * (base - Ui.dp(getContext(), 12));
                float x = i * slot + slot * 0.25f;
                canvas.drawRect(x, base - h, x + slot * 0.5f, base, bar);
                String day = entry.getKey().substring(8);
                canvas.drawText(day, x + slot * 0.25f - Ui.dp(getContext(), 6), getHeight() - Ui.dp(getContext(), 6), label);
                i++;
            }
        }
    }

    /** 12 周 × 7 天热力图：按当天秒数映射主题色透明度。 */
    private static final class Heatmap extends View {
        private final Map<String, Long> data;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Heatmap(Context c, Map<String, Long> data) {
            super(c);
            this.data = data;
            setMinimumHeight(Ui.dp(c, 130));
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int cell = Ui.dp(getContext(), 12);
            int gap = Ui.dp(getContext(), 2);
            LocalDate start = LocalDate.now().minusDays(83);
            for (int week = 0; week < 12; week++) {
                for (int dow = 0; dow < 7; dow++) {
                    LocalDate day = start.plusDays(week * 7L + dow);
                    long seconds = 0;
                    Long value = data.get(day.toString());
                    if (value != null) seconds = value;
                    int alpha;
                    if (seconds <= 0) alpha = 0x14;
                    else if (seconds < 60) alpha = 0x55;
                    else if (seconds < 600) alpha = 0x99;
                    else alpha = 0xFF;
                    paint.setColor(Color.argb(alpha, 0x4A, 0x7A, 0x5A));
                    float x = week * (cell + gap);
                    float y = dow * (cell + gap);
                    canvas.drawRoundRect(x, y, x + cell, y + cell, Ui.dp(getContext(), 2),
                            Ui.dp(getContext(), 2), paint);
                }
            }
        }
    }
}
