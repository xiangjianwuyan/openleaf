package org.openleaf.reader.core;

import java.nio.charset.StandardCharsets;

/**
 * 非书媒体的稳定归属标识。
 *
 * <p>漫画与本地音频都是「按文件直接打开」，没有书库 id；但它们仍然需要跨会话、跨设备
 * 稳定的身份才能记时长。这里用 {@code kind + "|" + key} 的 SHA-256 前缀生成 id，
 * 不用随机 UUID：同一份媒体在两台设备上算出同一个 id，备份恢复时无需额外映射表，
 * 重复恢复也只是覆盖同一行（幂等）。
 *
 * <p>刻意不把漫画塞进 {@code books}：那会改变书架的心智模型，还会让「按文件打开的漫画」
 * 突然出现在书库里，和导入、去重、书文件管理全搅在一起。
 */
public final class MediaAttribution {

    private MediaAttribution() { }

    /**
     * 生成非书媒体的归属 id。
     * @return 形如 {@code m_<32位十六进制>}；入参无效返回 null
     */
    public static String id(ReadingLedger.Kind kind, String mediaKey) {
        if (kind == null || mediaKey == null || mediaKey.trim().isEmpty()) return null;
        String raw = kind.key() + "|" + mediaKey.trim();
        String hash = ContentContracts.hashHex(raw.getBytes(StandardCharsets.UTF_8));
        return "m_" + hash.substring(0, 32);
    }

    /**
     * 从 URI / 路径字符串生成稳定的文件键。只做空白裁剪，不解析设备相关的授权参数。
     */
    public static String fileKey(String uriOrPath) {
        if (uriOrPath == null || uriOrPath.trim().isEmpty()) return null;
        String hash = ContentContracts.hashHex(uriOrPath.trim().getBytes(StandardCharsets.UTF_8));
        return hash.substring(0, 16);
    }
}
