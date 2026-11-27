package org.openleaf.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.LruCache;

/**
 * 封面解码 LRU（最多 8 张），只做缓存，不做"每页 4 次"计数。
 *
 * <p>每页预算由 {@link MainActivity} 在绑定 tile 时计数；即使命中本缓存也照计一次，
 * 避免缓存命中绕过上限。只持有 applicationContext，不持有 Activity。
 */
public final class CoverCache {
    private static final int MAX_ENTRIES = 8;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(MAX_ENTRIES);

    private CoverCache() { }

    /** 命中即返；未命中才调 CoverFiles.preview；解码失败或 null 不入缓存。 */
    public static Bitmap preview(Context context, String name, int maxSide) {
        if (context == null || name == null || name.isEmpty()) return null;
        Bitmap cached = CACHE.get(name);
        if (cached != null) return cached;
        try {
            Bitmap decoded = CoverFiles.preview(context.getApplicationContext(), name, maxSide);
            if (decoded != null) CACHE.put(name, decoded);
            return decoded;
        } catch (Exception unavailable) {
            return null;
        }
    }
}
