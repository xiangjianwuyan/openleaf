import org.openleaf.reader.core.SpeechText;

/** 0.45.0 本地音频睡眠定时：验证复用的 SpeechText.SleepTimer 状态机不变量与双实例隔离。 */
public final class AudioSleepTimerTest {
    private static int passed;

    public static void main(String[] args) {
        defaultOff();
        expiredOnlyInMinutes();
        invalidMinutes();
        remainingClamped();
        switchModesClearsDeadline();
        independentInstances();
        System.out.println("PASS: " + passed + " audio sleep timer checks");
    }

    private static void defaultOff() {
        SpeechText.SleepTimer timer = new SpeechText.SleepTimer();
        eq(SpeechText.SleepTimer.Kind.OFF, timer.kind());
        check(!timer.expired(0L), "默认 OFF 永不到点");
        check(!timer.expired(Long.MAX_VALUE), "默认 OFF 即使极大注入时刻也不到点");
        eq(-1L, timer.remainingSeconds(0L));
        passed++;
    }

    private static void expiredOnlyInMinutes() {
        SpeechText.SleepTimer timer = new SpeechText.SleepTimer();
        timer.setChapterEnd();
        check(!timer.expired(0L), "CHAPTER_END 模式不按时间到点（0）");
        check(!timer.expired(1000L + 60 * 60_000L), "CHAPTER_END 模式不按时间到点（+60 分）");
        timer.off();
        check(!timer.expired(Long.MAX_VALUE), "OFF 模式 expired 恒 false");
        passed++;
    }

    private static void invalidMinutes() {
        SpeechText.SleepTimer timer = new SpeechText.SleepTimer();
        timer.setMinutes(1000L, 30);
        timer.setMinutes(1000L, 0);
        eq(SpeechText.SleepTimer.Kind.OFF, timer.kind());
        eq(-1L, timer.remainingSeconds(1000L));
        timer.setMinutes(1000L, 30);
        timer.setMinutes(1000L, -5);
        eq(SpeechText.SleepTimer.Kind.OFF, timer.kind());
        check(!timer.expired(Long.MAX_VALUE), "非正分钟数归 OFF 后无残留截止时刻");
        passed++;
    }

    private static void remainingClamped() {
        SpeechText.SleepTimer timer = new SpeechText.SleepTimer();
        timer.setMinutes(1000L, 1);
        eq(60L, timer.remainingSeconds(1000L));
        eq(1L, timer.remainingSeconds(1000L + 59_000L));
        eq(0L, timer.remainingSeconds(1000L + 60_000L));
        eq(0L, timer.remainingSeconds(1000L + 600_000L));
        check(timer.expired(1000L + 60_000L), "到点 expired 为 true");
        passed++;
    }

    private static void switchModesClearsDeadline() {
        SpeechText.SleepTimer timer = new SpeechText.SleepTimer();
        for (int i = 0; i < 3; i++) {
            timer.setMinutes(1000L, 30);
            eq(SpeechText.SleepTimer.Kind.MINUTES, timer.kind());
            check(!timer.expired(1000L + 29 * 60_000L), "切换后旧 deadline 未到点");
            timer.setChapterEnd();
            eq(SpeechText.SleepTimer.Kind.CHAPTER_END, timer.kind());
            eq(-1L, timer.remainingSeconds(1000L + 60_000L));
            check(!timer.expired(1000L + 60_000L), "切到 CHAPTER_END 后旧 deadline 不再触发 expired");
            check(timer.shouldStopAtChapterEnd(true), "CHAPTER_END 模式章末判定 intact");
        }
        timer.setMinutes(5000L, 15);
        eq(15 * 60L, timer.remainingSeconds(5000L));
        check(!timer.expired(5000L + 14 * 60_000L), "新 deadline 基于新注入时刻");
        check(timer.expired(5000L + 15 * 60_000L), "新 deadline 到点");
        passed++;
    }

    private static void independentInstances() {
        SpeechText.SleepTimer a = new SpeechText.SleepTimer();
        SpeechText.SleepTimer b = new SpeechText.SleepTimer();
        a.setMinutes(1000L, 30);
        eq(SpeechText.SleepTimer.Kind.MINUTES, a.kind());
        check(b.kind() == SpeechText.SleepTimer.Kind.OFF, "A setMinutes(1000,30) 后 B 仍 OFF（双实例隔离）");
        check(!b.expired(Long.MAX_VALUE), "B 不受 A 的 deadline 影响");
        eq(-1L, b.remainingSeconds(1000L));
        a.off();
        eq(SpeechText.SleepTimer.Kind.OFF, a.kind());
        check(b.kind() == SpeechText.SleepTimer.Kind.OFF, "A.off() 后 B 仍 OFF、状态不受影响");
        b.setMinutes(1000L, 15);
        check(a.kind() == SpeechText.SleepTimer.Kind.OFF, "B setMinutes 后 A 仍 OFF（反向隔离）");
        eq(15 * 60L, b.remainingSeconds(1000L));
        passed++;
    }

    private static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
