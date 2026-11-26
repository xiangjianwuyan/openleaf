package org.openleaf.reader;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.openleaf.reader.core.FontLibrary;
import org.openleaf.reader.core.FontLibrary.Entry;

/**
 * 字体管理卡片视图工厂（0.29.0 P2-3，薄 UI 层，不含业务逻辑）。
 *
 * <p>全部用现有 {@link Ui} 工厂拼装，不新增 layout XML / 字符串资源 / 控件库。
 * 卡片的预览 TextView 与正文 TextView 完全独立：这里的 setTypeface 只作用于预览卡片。
 * Typeface 由调用方在 worker 预加载后传入，本类不在 UI 线程加载字体。
 */
final class FontManagerUi {

    /**
     * 构造一张收藏卡片：展示名与全局/本书标记、原创示例文本预览、行内操作按钮。
     * 损坏条目灰化预览并禁用选择类操作（重命名/删除仍可用，便于用户清理）。
     */
    View buildCard(Context c, Entry entry, Typeface face, String globalId, String bookFontId,
                          Runnable onPick, Runnable onGlobal, Runnable onRename, Runnable onDelete) {
        boolean usable = FontLibrary.selectable(entry);
        String name = entry == null ? "" : entry.name;

        LinearLayout card = Ui.column(c);
        Ui.pad(card, 12);
        card.setBackground(Ui.outline(c, Ui.LINE, 10));
        card.setContentDescription("字体卡片-" + name + (usable ? "" : "（字体不可用）"));

        LinearLayout head = Ui.row(c);
        StringBuilder title = new StringBuilder(name);
        if (entry != null && globalId != null && globalId.equals(entry.id)) title.append("   ● 全局默认");
        if (entry != null && bookFontId != null && bookFontId.equals(entry.id)) title.append("   ◆ 本书");
        TextView label = Ui.title(c, title.toString(), 15);
        label.setContentDescription("字体名称-" + name);
        head.addView(label, Ui.weight());
        if (!usable) {
            TextView badge = Ui.text(c, "字体不可用", 12, Ui.MUTED);
            badge.setContentDescription("字体不可用-" + name);
            head.addView(badge);
        }
        card.addView(head);

        Ui.gap(card, 6);
        card.addView(previewView(c, usable ? face : null, usable),
                new LinearLayout.LayoutParams(-1, -2));

        Ui.gap(card, 8);
        LinearLayout firstRow = Ui.row(c);
        Button pick = Ui.button(c, "本书使用", onPick, "本书使用-" + name);
        pick.setEnabled(usable);
        Button global = Ui.button(c, "设为全局默认", onGlobal, "设为全局默认-" + name);
        global.setEnabled(usable);
        firstRow.addView(pick, Ui.weight());
        firstRow.addView(global, Ui.weight());
        card.addView(firstRow);

        Ui.gap(card, 4);
        LinearLayout secondRow = Ui.row(c);
        Button rename = Ui.button(c, "重命名", onRename, "重命名-" + name);
        Button delete = Ui.button(c, "删除", onDelete, "删除字体-" + name);
        secondRow.addView(rename, Ui.weight());
        secondRow.addView(delete, Ui.weight());
        card.addView(secondRow);

        card.setOnClickListener(v -> {
            if (usable) onPick.run();
        });
        return card;
    }

    /** 独立预览 TextView：使用原创示例文本，损坏条目用系统默认字形 + 灰化。 */
    TextView previewView(Context c, Typeface face, boolean usable) {
        TextView preview = Ui.text(c, FontLibrary.SAMPLE_TEXT, 13, usable ? Ui.INK : Ui.MUTED);
        preview.setContentDescription("字体预览-" + (usable ? "可用" : "不可用"));
        if (face != null) preview.setTypeface(face);
        if (!usable) preview.setAlpha(0.5f);
        return preview;
    }
}
