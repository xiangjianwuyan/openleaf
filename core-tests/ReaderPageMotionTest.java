package org.openleaf.reader.core;

/** READ-1：系统动画缩放判断的纯 JVM 断言（scale=0 跳过，读取失败保守播放）。 */
public final class ReaderPageMotionTest {
    public static void main(String[] args) {
        // 任一缩放为 0 → 跳过动画，直接到最终态。
        require(PageMotionPolicy.skipMotion(0f, 1f, true));
        require(PageMotionPolicy.skipMotion(1f, 0f, true));
        require(PageMotionPolicy.skipMotion(0f, 0f, true));

        // 正常缩放、读取失败(null) → 播放动画，不影响正常用户。
        require(!PageMotionPolicy.skipMotion(1f, 1f, true));
        require(!PageMotionPolicy.skipMotion(null, null, true));
        require(!PageMotionPolicy.skipMotion(null, 1f, true));
        require(!PageMotionPolicy.skipMotion(1f, null, true));
        require(!PageMotionPolicy.skipMotion(0.5f, 0.7f, true));

        // ValueAnimator 被系统关闭（API>=26）→ 跳过；API<26 传入 true 不受影响。
        require(PageMotionPolicy.skipMotion(1f, 1f, false));
        require(PageMotionPolicy.skipMotion(null, null, false));

        System.out.println("PASS: READ-1 page motion policy (scale=0 skips, read failure plays conservatively)");
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("unexpected page motion policy result");
    }
}
