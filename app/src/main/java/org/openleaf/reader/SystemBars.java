package org.openleaf.reader;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

import org.openleaf.reader.core.SystemBarsPolicy;

/**
 * 阶段 3-B 收尾：状态栏/导航栏统一适配。
 *
 * <p>此前 README/QA 标注"setStatusBarColor 在 targetSdk35+Android12 是 no-op"——
 * 本类显式改用 {@link WindowInsetsController#setSystemBarsAppearance}（API 30+）控制图标明暗，
 * 旧 API 仍走 SYSTEM_UI_FLAG_LIGHT_*；背景色沿用 setStatusBarColor/NavigationBarColor
 * （API 30+ 仍生效，图标与背景分别控制）。颜色→图标策略由 core SystemBarsPolicy 决定。</p>
 */
final class SystemBars {
    private SystemBars() { }

    static void apply(Activity activity, int backgroundColor) {
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window == null) return;
        window.setStatusBarColor(backgroundColor);
        window.setNavigationBarColor(backgroundColor);
        boolean darkIcons = SystemBarsPolicy.darkIcons(backgroundColor);
        if (Build.VERSION.SDK_INT >= 30) {
            // 双保险：DecorView 未安装时 getInsetsController() 内部会解引用 null 抛 NPE，
            // 外层 if(controller!=null) 救不了（异常在 getter 本身）。先查 DecorView，再 try/catch 兜底。
            try {
                View decor = window.getDecorView();
                if (decor == null) return;
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) {
                    int appearance = 0;
                    if (darkIcons) appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    controller.setSystemBarsAppearance(appearance,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } catch (RuntimeException unavailable) {
                // 图标适配失败不该阻断启动；背景色已设置，系统默认图标仍可读
            }
        } else {
            try {
                View decor = window.getDecorView();
                if (decor == null) return;
                int flags = darkIcons
                        ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        : 0;
                decor.setSystemUiVisibility(flags);
            } catch (RuntimeException ignored) { /* 同上，不阻断启动 */ }
        }
    }
}
