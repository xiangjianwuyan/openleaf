package org.openleaf.reader.core;

/**
 * 翻页动画开关策略（纯 JVM，无 Android 依赖）。
 *
 * <p>把「系统是否禁用动画」的判断抽成可静态核对、可在 core-tests 直接断言的纯函数。
 * 调用方把无法读取的系统设置传 null，本策略按「动画开启」保守处理，避免影响正常用户。</p>
 */
public final class PageMotionPolicy {
    private PageMotionPolicy() { }

    /**
     * 是否应跳过翻页动画、直接到最终态。
     *
     * @param windowScale Settings.Global.WINDOW_ANIMATION_SCALE；null 表示读取失败
     * @param transitionScale Settings.Global.TRANSITION_ANIMATION_SCALE；null 表示读取失败
     * @param animatorsEnabled ValueAnimator 是否可用；API&lt;26 传 true（该 API 不存在）
     * @return true 表示跳过动画（ValueAnimator 被系统关闭，或任一动画缩放为 0）
     */
    public static boolean skipMotion(Float windowScale, Float transitionScale, boolean animatorsEnabled) {
        if (!animatorsEnabled) return true;
        if (isZero(windowScale) || isZero(transitionScale)) return true;
        return false;
    }

    private static boolean isZero(Float scale) {
        return scale != null && scale.floatValue() == 0f;
    }
}
