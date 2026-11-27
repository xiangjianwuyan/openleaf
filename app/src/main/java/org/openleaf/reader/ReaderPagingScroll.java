package org.openleaf.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ScrollView;

import org.openleaf.reader.core.PageMotionPolicy;

/** The same selectable text and layout as scroll mode, but with a fixed page viewport. */
final class ReaderPagingScroll extends ScrollView {
    interface Turns { boolean turn(int direction); }

    private final float slop;
    private final float swipeDistance;
    private final Turns turns;
    private boolean paged, selection, swiping, multi, previous, next;
    private float downX, downY;
    private int pageBottom = -1;

    ReaderPagingScroll(Context context, Turns turns) {
        super(context);
        this.turns = turns;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        swipeDistance = 48f * context.getResources().getDisplayMetrics().density;
    }

    void setPaged(boolean paged) {
        this.paged = paged;
        swiping = false;
        pageBottom = -1;
        if (!paged) stopMotion();
        invalidate();
    }

    void setSelectionActive(boolean active) { selection = active; }

    void frame(int bottom, boolean canPrevious, boolean canNext) {
        pageBottom = bottom;
        previous = canPrevious;
        next = canNext;
        invalidate();
    }

    void stopMotion() {
        animate().cancel();
        setTranslationX(0);
        setAlpha(1f);
    }

    /**
     * 系统动画关闭语义：ValueAnimator（API≥26）、窗口动画缩放、过渡动画缩放任一为 0，
     * 就跳过翻页动画直接到最终态。API&lt;26 没有 ValueAnimator 开关，按 true 处理。
     */
    private boolean systemMotionDisabled() {
        boolean animatorsEnabled = android.os.Build.VERSION.SDK_INT < 26
                || android.animation.ValueAnimator.areAnimatorsEnabled();
        return PageMotionPolicy.skipMotion(
                readAnimationScale(Settings.Global.WINDOW_ANIMATION_SCALE),
                readAnimationScale(Settings.Global.TRANSITION_ANIMATION_SCALE),
                animatorsEnabled);
    }

    /** 读取系统动画缩放；失败返回 null，由策略按「动画开启」保守处理。 */
    private Float readAnimationScale(String key) {
        try {
            return Settings.Global.getFloat(getContext().getContentResolver(), key, 1f);
        } catch (Exception ignored) {
            return null;
        }
    }

    void enter(int direction, int motion) {
        stopMotion();
        if (motion == 0 || systemMotionDisabled()) return;
        if (motion == 1) {
            setTranslationX(direction * (float) getWidth());
            animate().translationX(0f).setDuration(230).start();
        } else {
            setAlpha(0f);
            animate().alpha(1f).setDuration(180).start();
        }
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (!paged || pageBottom < 0) {
            super.dispatchDraw(canvas);
            return;
        }
        int checkpoint = canvas.save();
        canvas.clipRect(getScrollX(), getScrollY(), getScrollX() + getWidth(),
                getScrollY() + Math.min(getHeight(), pageBottom));
        super.dispatchDraw(canvas);
        canvas.restoreToCount(checkpoint);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (!paged) return super.dispatchTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            swiping = false;
            multi = false;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multi = true;
        } else if (action == MotionEvent.ACTION_MOVE && !swiping && !multi && !selection
                && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
            float dx = Math.abs(event.getX() - downX);
            if (dx > slop * 2 && dx > Math.abs(event.getY() - downY) * 1.5f) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                swiping = true;
            }
        }
        if (swiping) {
            if (action == MotionEvent.ACTION_UP) {
                float dx = event.getX() - downX;
                if (!multi && Math.abs(dx) >= swipeDistance && Math.abs(dx) > Math.abs(event.getY() - downY) * 1.5f)
                    turns.turn(dx < 0 ? 1 : -1);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swiping = false;
            return true;
        }
        // Do not deliver taps to lines deliberately clipped off the current page.
        if (action == MotionEvent.ACTION_DOWN && pageBottom >= 0 && event.getY() > pageBottom) return true;
        return super.dispatchTouchEvent(event) || action == MotionEvent.ACTION_DOWN;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        return !paged && super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        return paged || super.onTouchEvent(event);
    }

    @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rectangle) {
        return paged ? 0 : super.computeScrollDeltaToGetChildRectOnScreen(rectangle);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        return !paged && super.onGenericMotionEvent(event);
    }

    @Override public boolean executeKeyEvent(android.view.KeyEvent event) {
        if (!paged) return super.executeKeyEvent(event);
        if (event.getAction() != android.view.KeyEvent.ACTION_DOWN || selection) return false;
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_PAGE_UP) return turns.turn(-1);
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_PAGE_DOWN) return turns.turn(1);
        return false;
    }

    @Override public boolean requestChildRectangleOnScreen(View child, android.graphics.Rect rectangle, boolean immediate) {
        return !paged && super.requestChildRectangleOnScreen(child, rectangle, immediate);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (!paged) return;
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN);
        info.setScrollable(previous || next);
        if (previous) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (next) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (!paged) return super.performAccessibilityAction(action, arguments);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return next && turns.turn(1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return previous && turns.turn(-1);
        return super.performAccessibilityAction(action, arguments);
    }
}
