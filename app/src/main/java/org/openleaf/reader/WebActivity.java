package org.openleaf.reader;

import android.app.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import org.json.JSONArray;

public final class WebActivity extends Activity {
    private WebView web;private TextView address;private ProgressBar progress;private LinearLayout root;private String failedUrl="";
    @Override public void onCreate(Bundle state){super.onCreate(state);root=Ui.column(this);Ui.mount(this,root);LinearLayout bar=Ui.row(this);bar.addView(Ui.button(this,"返回",this::finish));address=Ui.text(this,"网页阅读",13,Ui.GREEN);address.setMaxLines(1);address.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);bar.addView(address,Ui.weight());bar.addView(Ui.button(this,"收藏",this::bookmark));root.addView(bar);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);root.addView(progress,new LinearLayout.LayoutParams(-1,Ui.dp(this,3)));
        TextView note=Ui.text(this,"静态网页模式 · 已关闭脚本与第三方 Cookie",12,Ui.MUTED);note.setPadding(Ui.dp(this,12),Ui.dp(this,6),Ui.dp(this,12),Ui.dp(this,6));root.addView(note);
        try{web=new WebView(this);}catch(Exception e){root.addView(Ui.text(this,"系统 WebView 不可用，请安装或启用 Android System WebView。",16,Ui.INK));return;}
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setDomStorageEnabled(false);settings.setGeolocationEnabled(false);settings.setSupportMultipleWindows(false);settings.setBuiltInZoomControls(true);settings.setDisplayZoomControls(false);settings.setSafeBrowsingEnabled(true);settings.setSaveFormData(false);CookieManager.getInstance().setAcceptCookie(false);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return block(request.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return block(Uri.parse(url));}
            @Override public void onPageStarted(WebView view,String url,Bitmap icon){failedUrl="";address.setText(url);progress.setVisibility(View.VISIBLE);}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){root.removeView(view);view.destroy();if(web==view)web=null;Toast.makeText(WebActivity.this,"网页进程已退出，请重新打开网页",Toast.LENGTH_LONG).show();finish();return true;}
            @Override public void onPageFinished(WebView view,String url){progress.setVisibility(View.GONE);}
            @Override public void onReceivedSslError(WebView view,android.webkit.SslErrorHandler handler,android.net.http.SslError error){handler.cancel();Toast.makeText(WebActivity.this,"网页证书无效，已停止加载",Toast.LENGTH_LONG).show();}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame()){failedUrl=request.getUrl().toString();address.setText("加载失败："+failedUrl);progress.setVisibility(View.GONE);Toast.makeText(WebActivity.this,"无法加载网页，请检查网络或网址",Toast.LENGTH_LONG).show();}}
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){if(request.isForMainFrame())Toast.makeText(WebActivity.this,"网站返回 HTTP "+response.getStatusCode(),Toast.LENGTH_LONG).show();}
        });
        web.setWebChromeClient(new WebChromeClient(){@Override public void onProgressChanged(WebView v,int value){progress.setProgress(value);}});web.setDownloadListener((url,userAgent,disposition,mime,length)->Toast.makeText(this,"首版不提供网页下载，请使用浏览器下载后导入",Toast.LENGTH_LONG).show());root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=Ui.row(this);controls.addView(Ui.button(this,"后退",()->{if(web.canGoBack())web.goBack();}),Ui.weight());controls.addView(Ui.button(this,"刷新",()->{if(!failedUrl.isEmpty()){String url=failedUrl;failedUrl="";if(!block(Uri.parse(url)))web.loadUrl(url);}else web.reload();}),Ui.weight());controls.addView(Ui.button(this,"字号",()->{int zoom=web.getSettings().getTextZoom();web.getSettings().setTextZoom(zoom>=160?100:zoom+20);}),Ui.weight());root.addView(controls);
        String url=getIntent().getStringExtra("url");if(url==null||block(Uri.parse(url))){finish();return;}if(state==null||web.restoreState(state)==null)web.loadUrl(url);
    }
    private boolean block(Uri uri){boolean bad=!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null;if(bad)Toast.makeText(this,"只允许打开 HTTPS 网页",Toast.LENGTH_SHORT).show();return bad;}
    private void bookmark(){if(web==null)return;String url=web.getUrl();if(url==null||block(Uri.parse(url)))return;try{JSONArray a=new JSONArray(getSharedPreferences("web",0).getString("bookmarks","[]"));for(int i=0;i<a.length();i++)if(url.equals(a.getString(i))){Toast.makeText(this,"已在收藏中",Toast.LENGTH_SHORT).show();return;}if(a.length()>=200){Toast.makeText(this,"收藏已达 200 条，请先在网络阅读页移除部分收藏",Toast.LENGTH_LONG).show();return;}a.put(url);getSharedPreferences("web",0).edit().putString("bookmarks",a.toString()).apply();Toast.makeText(this,"已收藏",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"收藏失败",Toast.LENGTH_SHORT).show();}}
    @Override protected void onSaveInstanceState(Bundle out){if(web!=null)web.saveState(out);super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onPause(){if(web!=null)web.onPause();super.onPause();}
    @Override protected void onResume(){super.onResume();if(web!=null)web.onResume();}
    @Override protected void onDestroy(){if(web!=null){web.stopLoading();root.removeView(web);web.destroy();web=null;}super.onDestroy();}
}
