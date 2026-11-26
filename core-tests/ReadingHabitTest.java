import org.openleaf.reader.core.ReadingHabit;
import java.time.LocalDate;
import java.time.YearMonth;

/** 阅读目标与习惯回归：streak 规则、听书跳变不计时、余数结转、月聚合。 */
public final class ReadingHabitTest {
    private static int passed;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    public static void main(String[] args) {
        goalAndStreak();
        todayNotBroken();
        goalDisabled();
        heardSteps();
        secondsToPages();
        monthAndLongest();
        readerType();
        System.out.println("PASS: " + passed + " reading habit checks");
    }

    private static void goalAndStreak() {
        ReadingHabit.State state = new ReadingHabit.State(10);
        state.addPages(TODAY, 10);
        state.addPages(TODAY.minusDays(1), 12);
        state.addPages(TODAY.minusDays(2), 8); // 未达标，断在这里
        ReadingHabit.Habit habit = ReadingHabit.habitOf(state, TODAY);
        eq(10, habit.goal); eq(10, habit.today); eq(2, habit.streak);
        check(habit.done, "今日达标");
        eq(7, habit.week.length); eq(10, habit.week[6]);
        passed++;
    }

    private static void todayNotBroken() {
        ReadingHabit.State state = new ReadingHabit.State(10);
        state.addPages(TODAY.minusDays(1), 15);
        state.addPages(TODAY.minusDays(2), 15);
        state.addPages(TODAY, 3); // 今天未达标，但不应断签
        ReadingHabit.Habit habit = ReadingHabit.habitOf(state, TODAY);
        eq(2, habit.streak);
        check(!habit.done, "今日未达标");
        passed++;
    }

    private static void goalDisabled() {
        ReadingHabit.State state = new ReadingHabit.State(0);
        state.addPages(TODAY, 1);
        ReadingHabit.Habit habit = ReadingHabit.habitOf(state, TODAY);
        eq(0, habit.goal);
        check(!habit.done, "目标关闭时不显示达标");
        passed++;
    }

    private static void heardSteps() {
        ReadingHabit.HeardAccumulator acc = new ReadingHabit.HeardAccumulator();
        eq(0, acc.addStep(0, 2000));        // 2 秒有效但不足一页
        check(acc.remainderSeconds() == 2, "余数结转");
        eq(0, acc.addStep(100_000, 200_000)); // seek 跳变不计
        check(acc.remainderSeconds() == 2, "seek 不改变余数");
        // 再累计 88 秒（分两次 44 秒仍 >5 会被当 seek，故用连续小步）→ 总计 90 秒 = 1 页
        long pos = 300_000;
        int pages = 0;
        for (int i = 0; i < 18; i++) { pos += 5_000; pages += acc.addStep(pos - 5_000, pos); }
        eq(1, pages);
        check(acc.remainderSeconds() >= 0 && acc.remainderSeconds() < ReadingHabit.SECONDS_PER_PAGE, "余数始终小于一页");
        long remainderBefore = acc.remainderSeconds();
        eq(0, acc.addStep(500_000, 400_000)); // 倒退不计
        check(acc.remainderSeconds() == remainderBefore, "跳变不改变余数");
        passed++;
    }

    private static void secondsToPages() {
        eq(1, ReadingHabit.secondsToPages(90));
        eq(1, ReadingHabit.secondsToPages(134));
        eq(2, ReadingHabit.secondsToPages(135));
        eq(0, ReadingHabit.secondsToPages(44));
        passed++;
    }

    private static void monthAndLongest() {
        ReadingHabit.State state = new ReadingHabit.State(10);
        state.addPages(TODAY, 10);
        state.addPages(TODAY.minusDays(1), 20);
        ReadingHabit.MonthStats month = ReadingHabit.monthOf(state, YearMonth.from(TODAY));
        eq(30, month.pages); eq(2, month.readingDays);
        eq(2, ReadingHabit.longestStreak(state));
        passed++;
    }

    private static void readerType() {
        ReadingHabit.State state = new ReadingHabit.State(10);
        for (int i = 0; i < 25; i++) state.addPages(TODAY.minusDays(i), 12);
        check(ReadingHabit.readerType(state, TODAY, 2).startsWith("马拉松"), "长 streak 画像");
        ReadingHabit.State shortState = new ReadingHabit.State(10);
        shortState.addPages(TODAY, 12);
        check(ReadingHabit.readerType(shortState, TODAY, 6).startsWith("探索型"), "题材多样画像");
        passed++;
    }

    private static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
