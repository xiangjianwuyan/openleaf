package org.openleaf.reader;

import android.content.Context;
import android.content.SharedPreferences;

import org.openleaf.reader.core.Direction;

/**
 * v4 方向偏好持久化（Android 边界）。
 *
 * <p>SP/key 为 {@code openleaf_ui/direction}（int 0/1/2，默认 A/STUDY），与阅读主题
 * {@code reader/theme} + {@code themeFollowSystem} 以及 {@code shelf}、{@code openleaf_plugins}
 * 完全独立，互不读写。clamp 统一走 {@link Direction#fromInt(int)}。
 *
 * <p>{@code sCachedDirection} 记录本进程最近一次 {@link #direction(Context)} 观测到的方向，
 * 供 {@link #directionChanged(Context)} 在 onResume 判定是否需要重绘 chrome；阅读位置、
 * 书库搜索/筛选/选中、插件启停均不由此类触碰。
 */
final class UiPrefs {
    static final String SP = "openleaf_ui";
    static final String KEY_DIRECTION = "direction";

    /** -1 表示本进程尚未观测过方向。 */
    private static int sCachedDirection = -1;

    private UiPrefs() {
        // 工具类，禁止实例化。
    }

    /** 读方向偏好并 clamp；同时刷新进程内缓存，返回整数值 0/1/2。 */
    static int direction(Context c) {
        int d = readPersisted(c);
        sCachedDirection = d;
        return d;
    }

    /** 写方向偏好（先 clamp），并同步进程内缓存。 */
    static void setDirection(Context c, int d) {
        int clamped = Direction.fromInt(d).value();
        sp(c).edit().putInt(KEY_DIRECTION, clamped).apply();
        sCachedDirection = clamped;
    }

    /**
     * onResume 检测：持久化值与本进程上次观测值是否不同（不同则调用方应保存状态并重建 chrome）。
     * 只读检测，不刷新缓存；缓存由 {@link #direction(Context)} 在应用时刷新。
     */
    static boolean directionChanged(Context c) {
        return readPersisted(c) != sCachedDirection;
    }

    private static int readPersisted(Context c) {
        int raw = sp(c).getInt(KEY_DIRECTION, Direction.STUDY.value());
        return Direction.fromInt(raw).value();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
