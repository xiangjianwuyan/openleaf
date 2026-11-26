package org.openleaf.reader;

import android.content.Context;

import org.openleaf.reader.core.SourceRuleVersion;

import java.util.ArrayList;
import java.util.List;

/**
 * 在线书"检查书源更新"服务。
 *
 * 判定口径（关键）：入库时的规则版本不直接信任 rule_version 字段，而是从 remote_meta
 * 里的 sourceJson 现场重算——这样即使版本算法升级，旧数据也不会被误报为"已更新"。
 * 当前版本取自应用级 SourceStore 的当前书源；书源被删除则单独标记 SOURCE_MISSING。
 */
public final class SourceUpdateChecker {
    private final Context context;
    private final SourceStore sourceStore;
    private final OnlineBookStore onlineBooks;
    private final OnlineChapterStore chapters;

    public SourceUpdateChecker(Context context) {
        this.context = context.getApplicationContext();
        this.sourceStore = new SourceStore(this.context);
        this.onlineBooks = new OnlineBookStore(this.context);
        this.chapters = new OnlineChapterStore(this.context);
    }

    public enum Status {
        MATCH,          // 规则一致
        RULE_CHANGED,   // 当前书源规则与入库时不同，需重新解析
        SOURCE_MISSING, // 书源已删除/不存在，无法检查
        NO_META         // 旧版骨架无 source 规则，无法比对
    }

    public static final class CheckResult {
        public final OnlineBookStore.OnlineBook binding;
        public final Status status;
        public final String storedVersion, currentVersion, sourceName;
        public final String bookId;
        CheckResult(OnlineBookStore.OnlineBook binding, Status status,
                    String storedVersion, String currentVersion, String sourceName) {
            this.binding = binding;
            this.status = status;
            this.storedVersion = storedVersion;
            this.currentVersion = currentVersion;
            this.sourceName = sourceName;
            this.bookId = binding == null ? "" : binding.bookId;
        }
        public boolean needsUpdate() { return status == Status.RULE_CHANGED || status == Status.SOURCE_MISSING; }
    }

    /** 检查单条绑定。 */
    public CheckResult check(OnlineBookStore.OnlineBook binding) {
        if (binding == null) return new CheckResult(null, Status.NO_META, "", "", "");
        String storedJson = chapters.sourceJson(binding.remoteMeta);
        if (storedJson == null || storedJson.trim().isEmpty())
            return new CheckResult(binding, Status.NO_META, binding.ruleVersion, "", "");
        String storedVersion = SourceStore.ruleVersion(storedJson);
        LegadoClient.Source current = sourceStore.find(binding.sourceId);
        if (current == null)
            return new CheckResult(binding, Status.SOURCE_MISSING, storedVersion, "", "");
        String currentVersion = SourceStore.ruleVersion(current);
        Status status = SourceRuleVersion.changed(storedVersion, currentVersion)
                ? Status.RULE_CHANGED : Status.MATCH;
        return new CheckResult(binding, status, storedVersion, currentVersion, current.name);
    }

    /** 按本地 bookId 检查（阅读页自动提示用）。 */
    public CheckResult checkByBookId(String bookId) {
        return check(onlineBooks.findByBookId(bookId));
    }

    /** 扫描全部在线书，返回需要更新的条目。 */
    public List<CheckResult> checkAll() {
        List<CheckResult> result = new ArrayList<>();
        for (OnlineBookStore.OnlineBook binding : onlineBooks.allBindings()) {
            CheckResult checked = check(binding);
            if (checked.needsUpdate()) result.add(checked);
        }
        return result;
    }

    /** 结果摘要文案（供提示/列表展示）。 */
    public static String describe(CheckResult result) {
        if (result == null) return "未找到在线书绑定";
        switch (result.status) {
            case MATCH: return "书源规则一致";
            case RULE_CHANGED: return "书源规则已更新（" + SourceRuleVersion.shortVersion(result.storedVersion)
                    + " → " + SourceRuleVersion.shortVersion(result.currentVersion) + "）";
            case SOURCE_MISSING: return "书源已移除，无法检查更新";
            case NO_META: return "旧版在线书无规则记录，请重新加入书架";
            default: return "未知状态";
        }
    }
}
