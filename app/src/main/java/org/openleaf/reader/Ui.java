package org.openleaf.reader;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    // A11Y-1: MUTED 次级文字需达 WCAG AA 4.5:1。0xFF706A65 对 PANEL(0xFFF2F1EE)=4.72:1、
    // 对面板底色(0xFFF7F3EA)=4.81:1（原 0xFF78716C 仅 4.25/4.33，低于阈值）；保持暖灰同色相。
    static final int BG=0xFFFAFAF9, INK=0xFF27272A, GREEN=0xFF44403C, MUTED=0xFF706A65;
    static final int PANEL=0xFFF2F1EE, LINE=0xFFE7E5E4;
    static int dp(Context c,float value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(android.view.Gravity.CENTER_VERTICAL);return l;}
    static TextView text(Context c,String text,int size,int color){TextView t=new TextView(c);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");return t;}
    static TextView title(Context c,String text,int size){TextView t=text(c,text,size,INK);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    static GradientDrawable shape(int color,int radius,Context c){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static GradientDrawable outline(Context c,int color,int radius){GradientDrawable d=shape(color,radius,c);d.setStroke(dp(c,1),LINE);return d;}
    static Button button(Context c,String label,Runnable onClick){
        Button b=new Button(c);b.setText(label);b.setTextSize(13);b.setTextColor(INK);b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setMinWidth(dp(c,48));b.setMinimumWidth(dp(c,48));
        b.setPadding(dp(c,14),dp(c,8),dp(c,14),dp(c,8));b.setStateListAnimator(null);b.setElevation(0);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18000000),shape(PANEL,9,c),null));
        b.setOnClickListener(v->onClick.run());return b;
    }
    static Button button(Context c,String label,Runnable onClick,String desc){Button b=button(c,label,onClick);b.setContentDescription(desc);return b;}
    static Button primary(Context c,String label,Runnable click){Button b=button(c,label,click);b.setTextColor(Color.WHITE);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF),shape(INK,9,c),null));return b;}
    static void pad(View v,int n){int p=dp(v.getContext(),n);v.setPadding(p,p,p,p);}
    static void mount(Activity a,LinearLayout root){root.setBackgroundColor(BG);root.setFitsSystemWindows(true);a.setContentView(root);SystemBars.apply(a,BG);root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});root.requestApplyInsets();}
    static LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1);}
    static void gap(LinearLayout l,int dp){View v=new View(l.getContext());l.addView(v,new LinearLayout.LayoutParams(1,Ui.dp(l.getContext(),dp)));}
}
