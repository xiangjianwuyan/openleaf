package org.openleaf.reader;

import android.content.Context;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

/** Restores the initiating control only after its own window regains focus. */
final class ReaderReturnFocus {
    private ReaderReturnFocus() { }

    static void restore(View target) {
        if (target == null || !target.isAttachedToWindow()) return;
        if (target.hasWindowFocus()) {
            target.post(() -> apply(target));
            return;
        }
        ViewTreeObserver observer = target.getViewTreeObserver();
        if (observer.isAlive()) new Pending(target, observer).start();
    }

    private static void apply(View target) {
        if (!target.isAttachedToWindow() || !target.hasWindowFocus() || !target.isShown()
                || !target.isEnabled()) return;
        target.setFocusableInTouchMode(true);
        target.requestFocus();
        AccessibilityManager manager = (AccessibilityManager) target.getContext()
                .getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager != null && manager.isEnabled() && manager.isTouchExplorationEnabled()) {
            target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        }
    }

    private static final class Pending implements ViewTreeObserver.OnWindowFocusChangeListener,
            View.OnAttachStateChangeListener {
        private final View target;
        private final ViewTreeObserver observer;

        Pending(View target, ViewTreeObserver observer) {
            this.target = target;
            this.observer = observer;
        }

        void start() {
            target.addOnAttachStateChangeListener(this);
            observer.addOnWindowFocusChangeListener(this);
        }

        private void stop() {
            if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(this);
            target.removeOnAttachStateChangeListener(this);
        }

        @Override public void onWindowFocusChanged(boolean focused) {
            if (!focused) return;
            stop();
            target.post(() -> apply(target));
        }

        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { stop(); }
    }
}
