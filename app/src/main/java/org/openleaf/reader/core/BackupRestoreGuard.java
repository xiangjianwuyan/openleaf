package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段 3-B 收尾（旧备份恢复 787）：恢复前的悬挂 FK 过滤判定（纯 JVM，可单测）。
 *
 * <p>旧备份可能残留"书已删但统计行仍在"的 reading_time 行；该表 book_id 有 REFERENCES books(id)，
 * 直接写入触发 FOREIGN KEY constraint failed (787)，整次恢复回滚。本类在写入前按 books id 集合
 * （经重映射后）过滤悬挂行，保留合法统计，给用户非致命提示而非整包失败。</p>
 */
public final class BackupRestoreGuard {
    private BackupRestoreGuard() { }

    /** 过滤结果。 */
    public static final class Result {
        public final List<String> kept, skipped;
        Result(List<String> kept, List<String> skipped) {
            this.kept = Collections.unmodifiableList(kept);
            this.skipped = Collections.unmodifiableList(skipped);
        }
    }

    /**
     * 按目标书籍 id 集合过滤统计行。
     * @param statBookIds 每条统计行的原 book_id（顺序与调用方一致）
     * @param idMap 原 id → 目标 id 的重映射（null 视为恒等）
     * @param targetBookIds 目标库中存在的书籍 id 集合
     */
    public static Result filterStats(List<String> statBookIds, Map<String, String> idMap,
                                     Set<String> targetBookIds) {
        List<String> kept = new ArrayList<>(), skipped = new ArrayList<>();
        if (statBookIds == null) return new Result(kept, skipped);
        Set<String> targets = targetBookIds == null ? Collections.emptySet() : targetBookIds;
        for (String source : statBookIds) {
            String id = source == null ? "" : source;
            String target = idMap == null ? null : idMap.get(id);
            if (target == null) target = id;
            if (targets.contains(target)) kept.add(id);
            else skipped.add(id);
        }
        return new Result(kept, skipped);
    }

    /** 从备份的 books rows 提取 id 集合（供 filterStats 用）。 */
    public static Set<String> bookIds(List<String> ids) {
        Set<String> out = new HashSet<>();
        if (ids != null) for (String id : ids) if (id != null && !id.isEmpty()) out.add(id);
        return out;
    }
}
