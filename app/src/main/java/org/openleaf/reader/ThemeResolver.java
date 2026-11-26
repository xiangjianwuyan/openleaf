package org.openleaf.reader;

import android.content.res.Configuration;

import org.openleaf.reader.core.ThemePalette;

/**
 * 深色模式跟随判定（0.13.0 缺口 6）。
 *
 * 判定主体是纯函数：唯一 Android 依赖是 Configuration.UI_MODE_NIGHT_YES 常量；
 * uiMode 由调用方从 Configuration 取值后传入，便于在 JVM 侧直接断言优先级。
 */
final class ThemeResolver {
    static final String SP = "reader";
    /** 默认 false：不跟随系统，沿用既有 theme SP。 */
    static final String KEY_FOLLOW = "themeFollowSystem";

    private ThemeResolver() { }

    /**
     * follow=true 时忽略手动 theme：系统夜 = 2，其余 = 0（刻意不进 AMOLED，既有设计）。
     * follow=false 时沿用手动档位并夹到 ThemePalette 的 [0, PRESET_COUNT-1]，
     * 与 ThemePalette 共用单一真源，档位扩展时不再因硬编码上界而漂移。
     */
    static int resolve(boolean follow, int uiModeNightMask, int manualTheme) {
        return follow
                ? (uiModeNightMask == Configuration.UI_MODE_NIGHT_YES ? 2 : 0)
                : ThemePalette.clamp(manualTheme);
    }
}
