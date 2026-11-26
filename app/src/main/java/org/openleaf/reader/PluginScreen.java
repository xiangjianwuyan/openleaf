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

    private PluginScreen() { }

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
