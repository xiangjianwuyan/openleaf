package org.openleaf.reader.core;

import java.util.UUID;

/**
 * 阅读位置「外部写入」的检测契约。
 *
 * <p>阅读页和听书服务都会写同一组位置键。阅读页在后台被听书改了位置后，如果只靠
 * {@code anchor_} 本身无法区分「这次是我写的」还是「别人写的」——于是回前台时
 * 旧的内存位置会在下一次保存时把新进度覆盖掉。这里给外部写入方一个独立的修订号：
 * 只有 {@link #newRevision()} 产生的变化才触发阅读页重定位。
 */
public final class PositionSync {

    private PositionSync() { }

    /** 某本书的外部位置修订号键。 */
    public static String revisionKey(String bookId) {
        return "external_position_rev_" + (bookId == null ? "" : bookId);
    }

    /** 生成一次新的外部修订号。 */
    public static String newRevision() {
        return UUID.randomUUID().toString();
    }
}
