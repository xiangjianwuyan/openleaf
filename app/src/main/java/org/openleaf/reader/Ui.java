package org.openleaf.reader;

import org.openleaf.reader.core.UiTokens;

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

    // ---- v3 令牌重载：以下全部为追加；上面的常量与原 button/primary/mount/weight 签名一行未改 ----

    /** v3 主操作：ACCENT 实心 + ON_ACCENT 文字，命中区仍 ≥48dp（A11Y 锚点不变）。 */
    static Button primaryToken(Context c,String label,Runnable onClick,String desc){
        return tokenButton(c,label,onClick,desc,color(c, UiTokens.Role.ACCENT),
                color(c, UiTokens.Role.ON_ACCENT),UiTokens.radiusDp(UiTokens.Radius.BUTTON));
    }

    /** v3 次操作：SURFACE 底 + 1dp DIVIDER 描边 + PRIMARY 文字；描边仅辅助，不靠颜色 alone。 */
    static Button secondaryToken(Context c,String label,Runnable onClick,String desc){
        Button b=tokenButton(c,label,onClick,desc,color(c, UiTokens.Role.SURFACE),
                color(c, UiTokens.Role.PRIMARY),UiTokens.radiusDp(UiTokens.Radius.BUTTON));
        GradientDrawable bg=shape(color(c, UiTokens.Role.SURFACE),UiTokens.radiusDp(UiTokens.Radius.BUTTON),c);
        bg.setStroke(dp(c,1),color(c, UiTokens.Role.DIVIDER));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(
                color(c, UiTokens.Role.PRIMARY) & 0x18FFFFFF),bg,null));
        return b;
    }

    /** 令牌按钮公共构造：色/圆角全部来自 UiTokens，字号 14sp、命中区 48dp。 */
    static Button tokenButton(Context c,String label,Runnable onClick,String desc,int bg,int fg,int radiusDp){
        Button b=new Button(c);b.setText(label);b.setTextSize(UiTokens.textSizeSp(UiTokens.Typography.AUX));
        b.setTextColor(fg);b.setAllCaps(false);
        b.setTypeface(Typeface.create(UiTokens.fontFamily(UiTokens.Typography.BODY),Typeface.NORMAL));
        b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setMinWidth(dp(c,48));b.setMinimumWidth(dp(c,48));
        b.setPadding(dp(c,12),dp(c,6),dp(c,12),dp(c,6));b.setStateListAnimator(null);b.setElevation(0);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(
                color(c, UiTokens.Role.PRIMARY) & 0x18FFFFFF),shape(bg,radiusDp,c),null));
        b.setOnClickListener(v->onClick.run());if(desc!=null)b.setContentDescription(desc);return b;
    }

    /** 令牌文字：字级/字体/颜色全部来自 UiTokens。 */
    static TextView tokenText(Context c,String text,UiTokens.Typography t,UiTokens.Role role){
        TextView v=new TextView(c);v.setText(text);v.setTextSize(UiTokens.textSizeSp(t));
        v.setTextColor(color(c, role));v.setTypeface(Typeface.create(UiTokens.fontFamily(t),Typeface.NORMAL));
        v.setFontFeatureSettings("kern");return v;
    }

    /** 令牌卡片容器：底色/圆角来自 UiTokens。 */
    static LinearLayout tokenCard(Context c,UiTokens.Role bg,UiTokens.Radius radius){
        LinearLayout l=column(c);l.setBackground(shape(color(c, bg),UiTokens.radiusDp(radius),c));return l;
    }

    /** 封面占位：左侧 6dp、右侧 12dp 圆角，取自 UiTokens.Radius。 */
    static GradientDrawable coverShape(Context c,int color){
        GradientDrawable d=new GradientDrawable();d.setColor(color);
        float left=dp(c,UiTokens.radiusDp(UiTokens.Radius.COVER_LEFT));
        float right=dp(c,UiTokens.radiusDp(UiTokens.Radius.COVER_RIGHT));
        d.setCornerRadii(new float[]{left,left,right,right,right,right,left,left});return d;
    }

    /** 令牌根容器挂载：底色来自调用方（UiTokens 取值），SystemBars 同步。 */
    static void mount(Activity a,LinearLayout root,int bgColor){
        root.setBackgroundColor(bgColor);root.setFitsSystemWindows(true);a.setContentView(root);SystemBars.apply(a,bgColor);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        root.requestApplyInsets();
    }

    // ---- v4 方向令牌注入点：以下全部为追加；上方原常量与原方法签名一行未改 ----

    /** v4 取色统一入口：从 UiPrefs.direction(c) 读偏好，委托 UiTokens.color(direction, role)。 */
    static int color(Context c,UiTokens.Role role){return UiTokens.color(UiPrefs.direction(c),role);}

    /** v4 状态底：按方向取 warn/danger 底，禁止页面硬编码浅底。 */
    static int statusBackground(Context c,UiTokens.Status s){return UiTokens.statusBackground(UiPrefs.direction(c),s);}

    /** v4 状态前景：按方向取 warn/danger 字色。 */
    static int statusForeground(Context c,UiTokens.Status s){return UiTokens.statusForeground(UiPrefs.direction(c),s);}

    /** v4 危险实心按钮前景：A/B 白、C 深。 */
    static int onStatus(Context c){return UiTokens.onStatus(UiPrefs.direction(c));}
}
