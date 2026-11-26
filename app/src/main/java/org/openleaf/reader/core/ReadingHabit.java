package org.openleaf.reader.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阅读习惯与目标：以「yyyy-MM-dd → 有效页数」为单一事实源，派生每日目标、连续天数、
 * 周/月视图。借鉴 Shelf（MIT）的 habit/streak/recap 设计，由 OpenLeaf 自行实现，
 * 纯 JVM 可测，不含 Android / SQLite 依赖。
 *
 * 计量口径：以「页」为阅读与听书的统一货币（默认 40 页/小时 = 90 秒/页）。
 * 听书快进/跳转产生的跳变一律不计时，秒余数结转到下一次，避免虚增。
 */
public final class ReadingHabit {
    private ReadingHabit() { }

    public static final int DEFAULT_PAGES_PER_HOUR = 40;
    public static final long SECONDS_PER_PAGE = 3600L / DEFAULT_PAGES_PER_HOUR; // 90
    public static final int MAX_DAILY_GOAL = 500;
    /** 听书单次进度跳变超过该秒数视为 seek，不计入有效听书时长。 */
    public static final long MAX_HEARD_STEP = 5;

    /** 可变习惯状态：只有 activity 是事实源，其余全部派生。 */
    public static final class State {
        private final Map<String, Integer> activity = new LinkedHashMap<>();
        private int dailyGoal; // 0 表示关闭目标

        public State(int dailyGoal) { setDailyGoal(dailyGoal); }

        public void setDailyGoal(int goal) {
            if (goal < 0 || goal > MAX_DAILY_GOAL) throw new IllegalArgumentException("目标页数超出范围");
            this.dailyGoal = goal;
        }

        public int dailyGoal() { return dailyGoal; }

        /** 记录某天新增页数（可跨天补记，用于导入历史）。 */
        public void addPages(LocalDate day, int pages) {
            if (day == null || pages <= 0) return;
            String key = day.toString();
            activity.put(key, (activity.containsKey(key) ? activity.get(key) : 0) + pages);
        }

        public Map<String, Integer> activity() { return Collections.unmodifiableMap(activity); }
    }

    /** 派生习惯：目标、今日、连续天数、近 7 天、是否达标。 */
    public static final class Habit {
        public final int goal, today, streak;
        public final int[] week; // 6 天前 → 今天
        public final boolean done;

        Habit(int goal, int today, int streak, int[] week) {
            this.goal = goal; this.today = today; this.streak = streak;
            this.week = week.clone(); this.done = goal > 0 && today >= goal;
        }
    }

    /**
     * 计算习惯。streak 规则：今天达标从今天起算，未达标从昨天起算（今天尚未结束不算断签）。
     */
    public static Habit habitOf(State state, LocalDate today) {
        int goal = state.dailyGoal();
        int todayPages = pages(state, today);
        int startOffset = (goal > 0 && todayPages >= goal) ? 0 : 1;
        int streak = 0;
        for (int i = startOffset; ; i++) {
            if (pages(state, today.minusDays(i)) >= (goal > 0 ? goal : 1)) streak++;
            else break;
            if (i > 3650) break; // 防御性上限
        }
        int[] week = new int[7];
        for (int i = 0; i < 7; i++) week[i] = pages(state, today.minusDays(6 - i));
        return new Habit(goal, todayPages, streak, week);
    }

    private static int pages(State state, LocalDate day) {
        Integer value = state.activity().get(day.toString());
        return value == null ? 0 : value;
    }

    /** 听书累计器：只接受 1..MAX_HEARD_STEP 秒的小步跳变，其余（seek）返回 0，余数结转。 */
    public static final class HeardAccumulator {
        private long remainderSeconds;

        /** 返回本次应计入的页数，同时更新内部余数。 */
        public int addStep(long beforeMillis, long afterMillis) {
            long delta = (afterMillis - beforeMillis) / 1000;
            if (delta <= 0 || delta > MAX_HEARD_STEP) return 0;
            long total = remainderSeconds + delta;
            int pages = (int) (total / SECONDS_PER_PAGE);
            remainderSeconds = total - pages * SECONDS_PER_PAGE;
            return pages;
        }

        public long remainderSeconds() { return remainderSeconds; }
    }

    /** 秒 → 页（四舍五入）。 */
    public static int secondsToPages(long seconds) {
        return (int) ((seconds + SECONDS_PER_PAGE / 2) / SECONDS_PER_PAGE);
    }

    /** 月统计：某月的总页数、阅读天数、活跃天数。 */
    public static final class MonthStats {
        public final YearMonth month;
        public final int pages, readingDays;
        MonthStats(YearMonth month, int pages, int readingDays) {
            this.month = month; this.pages = pages; this.readingDays = readingDays;
        }
    }

    public static MonthStats monthOf(State state, YearMonth month) {
        int pages = 0, days = 0;
        for (Map.Entry<String, Integer> entry : state.activity().entrySet()) {
            LocalDate day = LocalDate.parse(entry.getKey());
            if (YearMonth.from(day).equals(month)) {
                pages += entry.getValue();
                if (entry.getValue() > 0) days++;
            }
        }
        return new MonthStats(month, pages, days);
    }

    /** 最长连续天数（用于年度回顾）。 */
    public static int longestStreak(State state) {
        List<LocalDate> days = new ArrayList<>();
        for (String key : state.activity().keySet()) {
            if (state.activity().get(key) > 0) days.add(LocalDate.parse(key));
        }
        Collections.sort(days);
        int best = 0, current = 0;
        LocalDate previous = null;
        for (LocalDate day : days) {
            if (previous != null && day.equals(previous.plusDays(1))) current++;
            else current = 1;
            if (current > best) best = current;
            previous = day;
        }
        return best;
    }

    /** 可解释读者画像：用阈值规则给出结论，不依赖 ML。genreCount 由调用方按标签统计后注入。 */
    public static String readerType(State state, LocalDate today, int genreCount) {
        Habit habit = habitOf(state, today);
        int longest = longestStreak(state);
        if (longest >= 21) return "马拉松读者（连续 " + longest + " 天）";
        if (genreCount >= 5) return "探索型读者（涉猎 " + genreCount + " 类）";
        if (habit.streak >= 7) return "坚持型读者（连续 " + habit.streak + " 天）";
        return "常规读者";
    }

    /** 周视图中某天是周几（供 UI 标注）。 */
    public static DayOfWeek dayOfWeek(int weekIndex, LocalDate today) {
        return today.minusDays(6 - weekIndex).getDayOfWeek();
    }
}
