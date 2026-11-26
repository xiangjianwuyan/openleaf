package org.openleaf.reader.core;

import java.io.File;
import java.io.IOException;

/**
 * 自动阅读积分器 + 解压路径防护（纯 JVM，无 Android 依赖）。
 *
 * 设计借鉴 Breeze（MPL-2.0）的「按真实帧间隔积分位移 + delta 上限 + canTick 让位」
 * 与「canonical path 落在根内、拒绝 .. / 绝对路径 / 符号链接逃逸」思想，
 * 由 OpenLeaf 自行用 Java 重写。
 */
public final class AutoRead {
    private AutoRead() { }

    /** 参数 clamp：条漫 300-5000ms，翻页 800-10000ms。 */
    public static final int STRIP_MIN = 300, STRIP_MAX = 5000;
    public static final int PAGE_MIN = 800, PAGE_MAX = 10000;
    /** 帧间隔上限：切后台/卡顿恢复时不瞬跳一大段。 */
    public static final long MAX_DELTA_MS = 48;

    public static int clampInterval(int requested, boolean stripMode) {
        int min = stripMode ? STRIP_MIN : PAGE_MIN;
        int max = stripMode ? STRIP_MAX : PAGE_MAX;
        return Math.max(min, Math.min(max, requested));
    }

    /**
     * 按真实帧间隔积分的位移量：stepDistance = 视口高 × 百分比；
     * delta = stepDistance × (frameDeltaMs / intervalMs)，frameDelta 上限 48ms。
     */
    public static float stepDistance(float viewportHeight, float distancePercent,
                                     long frameDeltaMs, int intervalMs) {
        if (viewportHeight <= 0 || distancePercent <= 0 || intervalMs <= 0) return 0f;
        long delta = Math.max(0, Math.min(MAX_DELTA_MS, frameDeltaMs));
        return viewportHeight * distancePercent * ((float) delta / intervalMs);
    }

    /** 让位门控：菜单显示/用户拖拽/惯性滚动/滑块操作时均不 tick。 */
    public static boolean canTick(boolean menuVisible, boolean userDragging,
                                  boolean inertiaScrolling, boolean sliderActive) {
        return !menuVisible && !userDragging && !inertiaScrolling && !sliderActive;
    }

    /** 自动阅读状态机：暂停与设置解耦（toggle 只停计时，不改设置）。 */
    public static final class Controller {
        private boolean enabled;
        private boolean paused;
        private int intervalMs;
        private boolean stripMode;
        private boolean smooth;

        public void configure(boolean enabled, int intervalMs, boolean stripMode, boolean smooth) {
            this.enabled = enabled;
            this.intervalMs = clampInterval(intervalMs, stripMode);
            this.stripMode = stripMode;
            this.smooth = smooth;
        }

        public void togglePause() { if (enabled) paused = !paused; }
        public boolean shouldTick(boolean gate) { return enabled && !paused && gate; }
        public boolean isSmooth() { return smooth; }
        public boolean isStripMode() { return stripMode; }
        public int intervalMs() { return intervalMs; }
        public boolean isPaused() { return paused; }
    }

    // ---------- 解压路径防护 ----------

    /**
     * 解压目标安全校验：拒绝 .. / 绝对路径 / 盘符 / 空组件；
     * canonical path 必须落在 root 内（解析符号链接后）。
     */
    public static boolean isSafeExtractTarget(File root, String entryName) throws IOException {
        if (root == null || entryName == null || entryName.isEmpty()) return false;
        if (entryName.startsWith("/") || entryName.contains("\\") || entryName.indexOf(':') >= 0) return false;
        for (String part : entryName.split("/")) {
            if (part.equals("..")) return false;
            // 允许 "." 与空（目录条目尾部斜杠），其余正常组件
        }
        String rootPath = root.getCanonicalPath();
        File target = new File(root, entryName);
        String targetPath = target.getCanonicalPath();
        return targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator);
    }

    /** 归一化扩展名到解压器类型：cbz/zip→zip, cbr/rar→rar, cb7/7z→7z, cbt/tar→tar。 */
    public static String archiveKind(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return "";
        String ext = lower.substring(dot + 1);
        switch (ext) {
            case "cbz": case "zip": return "zip";
            case "cbr": case "rar": return "rar";
            case "cb7": case "7z": return "7z";
            case "cbt": case "tar": return "tar";
            default: return "";
        }
    }
}
