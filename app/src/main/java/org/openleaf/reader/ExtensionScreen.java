package org.openleaf.reader;

import android.app.Activity;
import android.content.Intent;
import android.widget.LinearLayout;

import org.openleaf.reader.core.UiTokens;

import java.io.IOException;
import java.util.List;

/**
 * v4 P1-A 扩展一级页薄壳（真实能力，无虚构数据）。
 *
 * <p>只做两件事：
 * <ol>
 *   <li>发现：B 层执行机制未就绪，给出真实边界文案（内置目录 / 本地 JSON，无在线市场），
 *       不渲染假榜单、假七类入口或假安装按钮；「插件源」只跳转到真实安装路径。</li>
 *   <li>已安装：复用 {@link PluginScreen} 构建 {@link PluginStore#list()} 的真实卡片，
 *       只呈现当前引擎真实存在的 {@code openleaf.text-audit} 与本地 JSON 插件。</li>
 * </ol>
 * 安装 / 默认禁用 / 启用 / 运行 / 移除的真实路径仍由 {@link PluginActivity} 承载，本类不复制其逻辑。
 */
final class ExtensionScreen {
    private ExtensionScreen() { }

    /** 把两个真实分区渲染进「扩展」一级页的内容容器。 */
    static void render(Activity a, LinearLayout body) {
        body.addView(Ui.tokenText(a, "扩展", UiTokens.Typography.PAGE_TITLE, UiTokens.Role.PRIMARY));
        Ui.gap(body, UiTokens.spacingDp(UiTokens.Spacing.LG));
        showDiscoverBoundary(a, body);
        Ui.gap(body, UiTokens.spacingDp(UiTokens.Spacing.LG));
        showInstalled(a, body);
    }

    /** 发现：只有真实边界文案与真实来源入口；无在线市场、无虚构插件、无假权限勾选。 */
    static void showDiscoverBoundary(Activity a, LinearLayout body) {
        body.addView(Ui.text(a, "发现", 18, Ui.color(a, UiTokens.Role.PRIMARY)));
        Ui.gap(body, 6);
        body.addView(Ui.text(a,
                "当前内置目录 / 本地 JSON，无在线市场；暂无可在线发现的扩展来源。"
                        + "本地导入、阅读、播放与数据设置均可正常使用。",
                14, Ui.color(a, UiTokens.Role.SECONDARY)));
        Ui.gap(body, 8);
        body.addView(Ui.secondaryToken(a, "插件源：内置目录 / 本地 JSON",
                () -> a.startActivity(new Intent(a, PluginActivity.class)),
                "打开真实插件源面板：内置目录或本地 JSON；查看用途和访问范围后确认安装"));
    }

    /** 已安装：逐条复用 PluginScreen 视图构建真实清单，详情含用途/开发者/访问范围/容量边界。 */
    static void showInstalled(Activity a, LinearLayout body) {
        body.addView(Ui.text(a, "已安装", 18, Ui.color(a, UiTokens.Role.PRIMARY)));
        Ui.gap(body, 6);
        try {
            List<PluginStore.Installed> entries = new PluginStore(a).list();
            int enabled = 0;
            for (PluginStore.Installed entry : entries) if (entry.enabled) enabled++;
            body.addView(Ui.text(a, entries.size() + " 个已安装 · " + enabled + " 个已启用", 13,
                    Ui.color(a, UiTokens.Role.SECONDARY)));
            if (entries.isEmpty()) {
                Ui.gap(body, 12);
                LinearLayout empty = PluginScreen.card(a, PluginScreen.SOFT);
                empty.addView(PluginScreen.heading(a, "还没有扩展", 17));
                Ui.gap(empty, 4);
                empty.addView(PluginScreen.text(a,
                        "从插件源添加内置「文本体检」样例，或导入本地 JSON 清单；安装后默认禁用。",
                        14, PluginScreen.MUTED));
                body.addView(empty);
            }
            for (PluginStore.Installed entry : entries) installedCard(a, body, entry);
        } catch (IOException e) {
            Ui.gap(body, 12);
            LinearLayout failure = PluginScreen.card(a, PluginScreen.SURFACE);
            failure.addView(PluginScreen.text(a, "扩展清单无法读取。没有运行任何插件。", 14, PluginScreen.INK));
            body.addView(failure);
        }
        Ui.gap(body, 12);
        body.addView(Ui.secondaryToken(a, "管理插件（启用 / 运行 / 移除）",
                () -> a.startActivity(new Intent(a, PluginActivity.class)),
                "打开插件管理：启用、运行或移除已安装的扩展"));
    }

    /** 单条已安装卡片：状态与来源真实，详情文案全部来自 PluginScreen 单一真源。 */
    private static void installedCard(Activity a, LinearLayout body, PluginStore.Installed entry) {
        Ui.gap(body, 12);
        LinearLayout card = PluginScreen.card(a, PluginScreen.SURFACE);
        card.addView(PluginScreen.heading(a, entry.manifest.name, 18));
        Ui.gap(card, 4);
        card.addView(PluginScreen.state(a,
                entry.source.label + " · " + (entry.enabled ? "已启用" : "已禁用"), entry.enabled));
        Ui.gap(card, 8);
        card.addView(PluginScreen.text(a, "用途：" + entry.manifest.description, 14, PluginScreen.INK));
        Ui.gap(card, 4);
        card.addView(PluginScreen.text(a, "开发者：" + PluginScreen.developer(entry.source), 13, PluginScreen.MUTED));
        card.addView(PluginScreen.text(a, "ID：" + entry.manifest.id, 13, PluginScreen.MUTED));
        body.addView(card);
        Ui.gap(body, 8);
        body.addView(PluginScreen.accessCard(a));
        Ui.gap(body, 8);
        body.addView(PluginScreen.boundaryCard(a));
    }
}
