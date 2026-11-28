package org.openleaf.reader.core;

/**
 * OpenLeaf v4 I2 媒体会话身份（纯 JVM，零 Android import）。
 *
 * <p>音频、视频、TTS 是三类互不混用的会话：面板开合只刷新同一实例，不重建会话；
 * 停止/定时等动作必须点名对象。本枚举把「会话键」与「面向用户的对象名」收成单一真源，
 * 供 Activity 标注与纯 JVM 门禁共用；不触碰解码器、播放内核或任何持久化格式。</p>
 */
public enum MediaSessionIdentity {
    AUDIO("audio", "本地音频"),
    VIDEO("video", "视频"),
    TTS("tts", "听书朗读");

    private static final String KEY_PREFIX = "openleaf.session.";

    private final String slug;
    private final String objectLabel;

    MediaSessionIdentity(String slug, String objectLabel) {
        this.slug = slug;
        this.objectLabel = objectLabel;
    }

    /** 会话唯一键：三类互不相同，媒体键/日志/定时器标注共用，保证会话不混。 */
    public String key() {
        return KEY_PREFIX + slug;
    }

    /** 面向用户的对象名：用于「停止/定时」按钮，明确动作作用于哪个对象。 */
    public String objectLabel() {
        return objectLabel;
    }

    /** 停止动作文案：必须包含对象名，避免「停止」指向不明。 */
    public String stopLabel() {
        return "停止" + objectLabel;
    }
}
