package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在线书章节差异（纯 JVM）。
 *
 * <p>判断追更看的是**章节 URL 集合**，不是规则版本：规则没变但作者加了新章，或者规则
 * 改了但目录没变，都是两种不同情况，不能混为一谈。URL 同时做去重，防止目录分页
 * 返回重复项导致虚报。
 */
public final class ChapterDiff {

    private ChapterDiff() { }

    /** 远端出现、本地没有的章节 URL（保持远端顺序，已去重）。 */
    public static List<String> added(List<String> localUrls, List<String> remoteUrls) {
        List<String> result = new ArrayList<>();
        Set<String> local = new LinkedHashSet<>();
        if (localUrls != null) for (String url : localUrls) if (url != null && !url.isEmpty()) local.add(url);
        Set<String> seenRemote = new LinkedHashSet<>();
        if (remoteUrls == null) return result;
        for (String url : remoteUrls) {
            if (url == null || url.isEmpty() || !seenRemote.add(url)) continue;
            if (!local.contains(url)) result.add(url);
        }
        return result;
    }

    /** 新增章节数。 */
    public static int addedCount(List<String> localUrls, List<String> remoteUrls) {
        return added(localUrls, remoteUrls).size();
    }
}
