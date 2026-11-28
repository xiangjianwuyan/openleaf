package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Local visual tokens and adaptive components for the plugin experience. */
final class PluginScreen {
    static final int PAPER = 0xFFF5F5EF;
    static final int SURFACE = 0xFFFFFDF8;
    static final int INK = 0xFF233129;
    static final int MUTED = 0xFF535D54;
    static final int SAGE = 0xFF315C48;
    static final int SOFT = 0xFFE5EEE5;
    static final int EDGE = 0xFFD9E0D6;
    static final int REMOVE = 0xFF87453E;

    // P1-A：真实访问范围/容量边界文案单一真源，安装确认页与扩展详情共用，照抄真实实现。
    static final String ACCESS_SCOPE =
            "只读你主动选择的 UTF-8 TXT / MD（最多 1 MiB）。不保留文件授权、不写入、不上传。";
    static final String DEFAULT_DISABLED =
            "安装默认禁用，需手动启用后才能运行。";
    static final String CAPACITY_BOUNDARY =
            "清单 ≤16 KiB · 最多 20 个 · schema 1 / txt-report · 标题前缀 1–8 · 标题候选 ≤20 · 文本 ≤1 MiB（UTF-8 TXT / MD）";

    private PluginScreen() { }

    /** 真实来源对应的开发者口径：内置目录随应用提供；本地 JSON 清单没有开发者字段，不编造。 */
    static String developer(PluginStore.Source source) {
        return source == PluginStore.Source.BUILTIN
                ? "内置目录（随应用提供）" : "本地 JSON（清单未声明开发者）";
    }

    /** 复用视图：真实访问范围卡（安装确认与扩展详情共用，避免两份文案漂移）。 */
    static LinearLayout accessCard(Context c) {
        LinearLayout access = card(c, SOFT);
        access.addView(heading(c, "访问范围", 16));
        access.addView(text(c, ACCESS_SCOPE, 14, INK));
        Ui.gap(access, 6);
        access.addView(text(c, DEFAULT_DISABLED, 13, MUTED));
        return access;
    }

    /** 复用视图：兼容与容量边界卡（当前本地 JSON 引擎的真实上限）。 */
    static LinearLayout boundaryCard(Context c) {
        LinearLayout boundary = card(c, SURFACE);
        boundary.addView(heading(c, "兼容与容量边界", 16));
        boundary.addView(text(c, CAPACITY_BOUNDARY, 14, INK));
        Ui.gap(boundary, 6);
        boundary.addView(text(c, "以上为当前本地 JSON 引擎的真实上限；无在线市场与自动更新。", 13, MUTED));
        return boundary;
    }

    static void mount(Activity activity, LinearLayout root) {
        Ui.mount(activity, root);
        root.setBackgroundColor(PAPER);
        SystemBars.apply(activity, PAPER);
    }

    static void center(LinearLayout host, View column, int maxDp) {
        // A cap applies to wide windows; MATCH_PARENT still honors system insets on narrow windows.
        int width = host.getResources().getConfiguration().screenWidthDp > maxDp + 40
                ? Ui.dp(host.getContext(), maxDp) : -1;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, -2);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        host.addView(column, params);
    }

    static TextView text(Context c, String content, int sp, int color) {
        TextView view = Ui.text(c, content, sp, color);
        view.setLineSpacing(Ui.dp(c, 2), 1f);
        return view;
    }

    static TextView heading(Context c, String content, int sp) {
        TextView view = text(c, content, sp, INK);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (android.os.Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }

    static LinearLayout card(Context context, int fill) {
        LinearLayout card = Ui.column(context);
        Ui.pad(card, 14);
        card.setBackground(outline(context, fill, 16));
        return card;
    }

    static TextView state(Context c, String label, boolean on) {
        TextView view = text(c, label, 13, on ? SAGE : MUTED);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setBackground(Ui.shape(on ? SOFT : 0xFFEDEFE9, 12, c));
        view.setPadding(Ui.dp(c, 10), Ui.dp(c, 3), Ui.dp(c, 10), Ui.dp(c, 3));
        view.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return view;
    }

    static Button button(Context c, String label, String description, int fill, int ink, Runnable action) {
        Button button = Ui.button(c, label, action, description);
        button.setTextSize(14);
        int min = Ui.dp(c, 48);
        button.setMinHeight(min);
        button.setMinimumHeight(min);
        button.setMinWidth(min);
        button.setMinimumWidth(min);
        button.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        button.setTextColor(new ColorStateList(new int[][]{
                {-android.R.attr.state_enabled}, new int[0]
        }, new int[]{MUTED, ink}));
        GradientDrawable background = fill == SURFACE ? outline(c, fill, 12) : Ui.shape(fill, 12, c);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, Ui.shape(EDGE, 12, c));
        states.addState(new int[0], new RippleDrawable(
                ColorStateList.valueOf(ink == Color.WHITE ? 0x33FFFFFF : 0x20315C48), background, null));
        button.setBackground(states);
        return button;
    }

    static ScrollView dialogBody(Context c, LinearLayout body) {
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 4));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    static AlertDialog show(AlertDialog.Builder builder) {
        return show(builder.create());
    }

    static AlertDialog show(AlertDialog dialog) {
        dialog.show();
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null) button.setTextColor(SAGE);
        }
        return dialog;
    }

    private static GradientDrawable outline(Context context, int fill, int radius) {
        GradientDrawable drawable = Ui.shape(fill, radius, context);
        drawable.setStroke(Ui.dp(context, 1), EDGE);
        return drawable;
    }
}
