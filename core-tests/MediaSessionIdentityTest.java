package org.openleaf.reader;

import org.openleaf.reader.core.MediaSessionIdentity;

/**
 * v4 I2 MEDIA-SESSION 门禁（纯 JVM）：音频/视频/TTS 三类会话互不混，
 * 停止/定时动作必须点名对象；面板开合不重建实例属于 Activity 行为，由静态复核覆盖。
 */
public final class MediaSessionIdentityTest {
    private static int checks = 0;

    public static void main(String[] args) {
        MediaSessionIdentity[] all = MediaSessionIdentity.values();
        check(all.length == 3, "恰好三类会话：音频/视频/TTS");

        for (int i = 0; i < all.length; i++) {
            MediaSessionIdentity one = all[i];
            check(one.key() != null && one.key().startsWith("openleaf.session."),
                    one.name() + " 会话键带统一前缀");
            check(one.objectLabel() != null && !one.objectLabel().isEmpty(),
                    one.name() + " 对象名非空");
            check(one.stopLabel().contains(one.objectLabel()),
                    one.name() + " 停止文案点名对象");
            for (int j = i + 1; j < all.length; j++) {
                check(!one.key().equals(all[j].key()),
                        one.name() + " 与 " + all[j].name() + " 会话键不同（不混会话）");
            }
        }

        check("本地音频".equals(MediaSessionIdentity.AUDIO.objectLabel()), "音频对象名=本地音频");
        check("视频".equals(MediaSessionIdentity.VIDEO.objectLabel()), "视频对象名=视频");
        check("听书朗读".equals(MediaSessionIdentity.TTS.objectLabel()), "TTS 对象名=听书朗读");
        check(!"停止本地音频".equals(MediaSessionIdentity.TTS.stopLabel()),
                "TTS 停止文案不等于音频停止文案（对象明确）");

        System.out.println("PASS: MediaSessionIdentityTest (" + checks + " checks)");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError("FAIL: " + label);
    }
}
