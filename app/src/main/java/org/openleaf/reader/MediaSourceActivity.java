package org.openleaf.reader;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.*;
import android.view.*;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import org.json.JSONObject;
import org.openleaf.reader.application.ServiceRegistry;
import org.openleaf.reader.core.ContentContracts;
import org.openleaf.reader.core.DownloadPolicy;
import org.openleaf.reader.core.DownloadUseCase;
import org.openleaf.reader.core.MediaDownloads;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Search, chapter browsing and foreground playback for user-configured JSON adapters. */
@UnstableApi
public final class MediaSourceActivity extends Activity {
    private static final int IMPORT=8301,EXPORT=8302;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final MediaSourceClient client=new MediaSourceClient();
    private final ArrayList<MediaSourceClient.Source> sources=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout root,body,browseControls;private TextView status,screenTitle;private EditText query;private Button sourcePicker,topBack;
    private CancellationSignal signal;private int generation;private String lastQuery="";
    private MediaSourceClient.Source selectedSource;
    // A session-only snapshot of the last page requested from each configured source.
    private final Map<String,SearchResult> searchResults=new HashMap<>();
    private SearchResult pendingSearch;
    private static final class SearchResult {
        final int page;
        List<MediaSourceClient.Item> items;
        boolean hasMore,cancelled;
        String error;
        SearchResult(int page){this.page=page;}
        String summary(){
            if(cancelled)return "已取消";
            if(error!=null)return "搜索失败";
            if(items==null)return "搜索中…";
            return items.isEmpty()?"本页无结果":items.size()+" 项结果";
        }
    }
    private OpenLeafPlayer player;private boolean prepared,foreground;private SeekBar seek;private TextView time;private Button play;
    // T03 视频模式（与音频/漫画图片互斥）：复用 OpenLeafPlayer，PlayerView 只在本页承载视频。
    private OpenLeafPlayer videoPlayer;private PlayerView videoView;private TextView videoStatus;private Button videoTracks;
    private Uri currentVideo;private String videoStatusOverride;private int videoGeneration,subtitleGeneration,selectedVideoTrack;
    private enum VideoOrigin { SOURCE, LOCAL, DOWNLOADED }
    private VideoOrigin videoOrigin;
    private static final String TX_VIDEO_READY="视频已就绪 · 共 %d 条音轨";
    private static final String TX_SUBTITLE_OK="已加载字幕：%s";
    private static final String TX_SUBTITLE_FAIL="字幕文件无法读取（%s），视频仍可播放。";
    private static final String TX_TRACKS="音轨 · %s（%d 条）";
    private static final String TX_VIDEO_FAIL="视频无法播放（%s）。可返回来源，或换一个文件。";
    private static final String TX_NO_COMPONENT="播放组件未就绪，请检查应用安装是否完整。";
    private static final String TX_LEFT="已暂停播放（离开页面自动暂停）。";
    private static final String TX_PICK_VIDEO="本地视频";
    private static final String TX_PICK_SUBTITLE="选择字幕";
    // 1B 视频落盘
    private static final String TX_DL_TO_LOCAL="下载到本地";
    private static final String TX_DL_SAVED="已下载";
    private static final String TX_DL_DOWNLOADING="正在下载 %s …";
    private static final String TX_DL_DONE="已保存到本地，可在「已下载」中打开。";
    private static final String TX_DL_FAIL="下载失败：%s。不自动重试，可到任务中心查看或在播放页重试。";
    private static final String TX_DL_EXISTS="这条视频已下载，可在「已下载」中打开。";
    private static final String TX_DL_MANAGE="已下载";
    private static final String TX_DL_EMPTY="还没有已下载的视频。在视频播放页点「下载到本地」即可保存。";
    private static final String TX_DL_LIMIT="仅前台下载（离开页面或杀进程会中断）；单文件上限 512 MiB；失败不自动重试。";
    private VideoCtx videoCtx;private Button videoDownload;private TextView downloadStatus;
    private final Set<String> downloadingVideos=new HashSet<>();
    private static final class VideoCtx {
        final String sourceId,sourceName,itemId,title,streamUrl;
        VideoCtx(String sourceId,String sourceName,String itemId,String title,String streamUrl){
            this.sourceId=sourceId;this.sourceName=sourceName;this.itemId=itemId;this.title=title;this.streamUrl=streamUrl;
        }
    }
    private List<String> pages=Collections.emptyList();private int imagePage;private Bitmap bitmap;private String pageTitle="";
    private final Runnable ticker=new Runnable(){public void run(){if(player!=null&&prepared){try{long duration=player.duration(),position=player.position();seek.setProgress(duration==0?0:(int)(1000L*position/duration));time.setText(clock((int)position)+" / "+clock((int)duration));}catch(IllegalStateException ignored){}}handler.postDelayed(this,500);}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);root=Ui.column(this);Ui.mount(this,root);
        LinearLayout top=Ui.row(this);topBack=Ui.button(this,"返回",this::onBackPressed);top.addView(topBack);
        screenTitle=Ui.title(this,"媒体来源",20);top.addView(screenTitle,Ui.weight());root.addView(top);
        browseControls=Ui.column(this);Ui.pad(browseControls,10);root.addView(browseControls);
        LinearLayout files=Ui.row(this);
        files.addView(Ui.button(this,TX_PICK_VIDEO,this::pickLocalVideo),Ui.weight());
        files.addView(Ui.button(this,"已下载视频",this::showDownloads),Ui.weight());browseControls.addView(files);
        Ui.gap(browseControls,10);
        sourcePicker=Ui.button(this,"选择接口来源",this::showSourceChooser);browseControls.addView(sourcePicker);
        LinearLayout search=Ui.row(this);query=new EditText(this);query.setSingleLine(true);query.setHint("搜索歌曲或漫画");
        search.addView(query,Ui.weight());search.addView(Ui.primary(this,"搜索",this::submitSearch));browseControls.addView(search);
        status=Ui.text(this,"",13,Ui.MUTED);Ui.pad(status,10);browseControls.addView(status);
        ScrollView scroll=new ScrollView(this);body=Ui.column(this);Ui.pad(body,12);scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        try{
            sources.addAll(MediaSourceClient.parsePack(getSharedPreferences("media_sources",0).getString("pack","{\"format\":\"openleaf-media-sources\",\"schemaVersion\":1,\"sources\":[]}")));
            refreshSources();
        }catch(Exception e){refreshSources();status.setText("配置读取失败："+message(e));}
    }
    private void refreshSources(){
        if(!sources.contains(selectedSource))selectedSource=sources.isEmpty()?null:sources.get(0);
        showSourceResults();
    }
    private String sourceLabel(MediaSourceClient.Source source){return (source.kind.equals("music")?"音乐 · ":"漫画 · ")+source.name;}
    private void updateSourcePicker(){
        sourcePicker.setText(selectedSource==null?"管理接口来源":"选择接口来源 · "+sourceLabel(selectedSource));
    }
    private void showSourceChooser(){
        LinearLayout list=Ui.column(this);Ui.pad(list,12);
        ScrollView scroll=new ScrollView(this);scroll.addView(list);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("接口来源").setView(scroll).setNegativeButton("关闭",null).create();
        LinearLayout tools=Ui.row(this);
        tools.addView(Ui.button(this,"添加源",()->{dialog.dismiss();addSource();}),Ui.weight());
        tools.addView(Ui.button(this,"导入",()->{dialog.dismiss();document(false);}),Ui.weight());
        tools.addView(Ui.button(this,"导出",()->{dialog.dismiss();document(true);}),Ui.weight());
        list.addView(tools);Ui.gap(list,8);
        if(sources.isEmpty())list.addView(Ui.text(this,"尚未配置接口来源。",14,Ui.MUTED));
        else if(!lastQuery.isEmpty())list.addView(Ui.text(this,"搜索记录："+lastQuery,13,Ui.MUTED));
        for(MediaSourceClient.Source source:sources){
            SearchResult result=searchResults.get(source.id);
            Button entry=Ui.button(this,(source==selectedSource?"当前 · ":"")+sourceLabel(source)+"\n"+(result==null?"尚未搜索":result.summary()),()->{
                dialog.dismiss();
                if(source==selectedSource)return;
                cancel();stopAudio();selectedSource=source;showSourceResults();
            });
            entry.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);list.addView(entry);Ui.gap(list,6);
        }
        if(selectedSource!=null){
            MediaSourceClient.Source source=selectedSource;
            list.addView(Ui.button(this,"移除当前源",()->{
                dialog.dismiss();new AlertDialog.Builder(this).setTitle("移除 "+source.name+"？").setNegativeButton("取消",null)
                    .setPositiveButton("移除",(d,w)->{cancel();stopAudio();sources.remove(source);searchResults.remove(source.id);save();refreshSources();}).show();
            }));
        }
        dialog.show();
    }
    private void save(){try{getSharedPreferences("media_sources",0).edit().putString("pack",MediaSourceClient.pack(sources)).apply();}catch(Exception e){status.setText(message(e));}}
    private void addSource(){LinearLayout form=Ui.column(this);Ui.pad(form,16);EditText name=new EditText(this),url=new EditText(this);name.setHint("源名称");url.setHint("https://your-adapter.example/api/");url.setSingleLine(true);form.addView(name);form.addView(url);Spinner kind=new Spinner(this);kind.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"音乐接口","漫画接口"}));form.addView(kind);TextView help=Ui.text(this,"默认接口：search?q={query}&page={page}、detail?id={id}、pages?id={id}、stream?id={id}。自定义路径可用源包导入。",12,Ui.MUTED);form.addView(help);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("添加媒体源").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{if(sources.size()>=100)throw new IOException("最多100个源");String base=url.getText().toString().trim();if(!base.endsWith("/"))base+="/";JSONObject j=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name.getText().toString()).put("kind",kind.getSelectedItemPosition()==0?"music":"comics").put("baseUrl",base).put("search","search?q={query}&page={page}").put("detail","detail?id={id}").put("pages","pages?id={id}").put("stream","stream?id={id}");sources.add(new MediaSourceClient.Source(j));save();refreshSources();dialog.dismiss();}catch(Exception e){url.setError(message(e));}}));dialog.show();}
    private interface Work {void run(int token,CancellationSignal signal)throws Exception;}
    private interface Failure {void show(Exception error);}
    private void task(String label,Work work){
        task(label,work,error->{
            status.setText("加载失败："+message(error));clearBody();
            body.addView(Ui.button(this,"返回搜索结果",this::showSourceResults));
        },()->{
            status.setText("加载已取消");
            body.addView(Ui.button(this,"返回搜索结果",this::showSourceResults));
        });
    }
    private void task(String label,Work work,Failure failure,Runnable cancelled){
        cancel();stopAudio();stopVideo();int token=generation;CancellationSignal active=new CancellationSignal();signal=active;
        status.setText(label);clearBody();
        body.addView(Ui.button(this,"停止加载",()->{cancel();clearBody();cancelled.run();}));
        worker.execute(()->{try{work.run(token,active);}catch(Exception error){runOnUiThread(()->{if(valid(token))failure.show(error);});}});
    }
    private void clearBody(){
        body.removeAllViews();
        if(body.getParent() instanceof ScrollView)((ScrollView)body.getParent()).scrollTo(0,0);
        if(bitmap!=null){bitmap.recycle();bitmap=null;}
    }
    private void returnToSources(){cancel();stopAudio();stopVideo();showSourceResults();}
    @Override public void onBackPressed(){if(videoOrigin!=null)leaveVideo();else super.onBackPressed();}
    private boolean valid(int token){return !isDestroyed()&&token==generation;}
    private void cancel(){
        generation++;if(signal!=null)signal.cancel();signal=null;
        if(pendingSearch!=null){pendingSearch.cancelled=true;pendingSearch=null;}
    }
    private void submitSearch(){
        if(selectedSource==null){status.setText("请先添加或导入接口来源");return;}
        String term=query.getText().toString().trim();
        if(term.isEmpty()){status.setText("请输入搜索关键词");return;}
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(query.getWindowToken(),0);
        query.clearFocus();
        if(!term.equals(lastQuery)){searchResults.clear();lastQuery=term;}
        search(1);
    }
    private void search(int page){
        MediaSourceClient.Source source=selectedSource;
        if(source==null||lastQuery.isEmpty())return;
        String term=lastQuery;
        SearchResult result=new SearchResult(page);
        task("正在搜索 · "+source.name,(token,active)->{
            JSONObject response=client.request(source.url(source.search,term,"",page),active);
            List<MediaSourceClient.Item> found=MediaSourceClient.items(response);
            boolean next=response.optBoolean("hasMore",false);
            runOnUiThread(()->{
                if(!valid(token))return;
                pendingSearch=null;result.items=found;result.hasMore=next;showSourceResults();
            });
        },error->{pendingSearch=null;result.error=message(error);showSourceResults();},this::showSourceResults);
        searchResults.put(source.id,result);pendingSearch=result;
    }
    private void showSourceResults(){
        clearBody();updateSourcePicker();
        MediaSourceClient.Source source=selectedSource;
        if(source==null){
            status.setText("尚未配置接口来源");
            body.addView(Ui.text(this,"添加接口地址或导入 OpenLeaf JSON 源包，即可搜索歌曲、漫画或接口实际返回的视频。也可以直接打开本地视频。",14,Ui.MUTED));
            Ui.gap(body,10);body.addView(Ui.button(this,"添加接口来源",this::addSource));
            return;
        }
        SearchResult result=searchResults.get(source.id);
        if(result==null){status.setText("仅搜索所选接口来源");return;}
        status.setText(sourceLabel(source)+" · "+result.summary()+" · 第 "+result.page+" 页");
        if(result.error!=null||result.cancelled){
            if(result.error!=null)body.addView(Ui.text(this,"加载失败："+result.error,14,Ui.MUTED));
            body.addView(Ui.button(this,"重试当前来源",()->search(result.page)));return;
        }
        if(result.items==null)return;
        for(MediaSourceClient.Item item:result.items){
            Button button=Ui.button(this,item.title+(item.subtitle.isEmpty()?"":"\n"+item.subtitle),()->{
                if(source.kind.equals("music"))resolveTrack(source,item);else details(source,item);
            });
            button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);body.addView(button);Ui.gap(body,6);
        }
        if(result.page>1||result.hasMore){
            LinearLayout nav=Ui.row(this);
            Button prev=Ui.button(this,"上一页",()->search(result.page-1)),next=Ui.button(this,"下一页",()->search(result.page+1));
            prev.setEnabled(result.page>1);next.setEnabled(result.hasMore);nav.addView(prev,Ui.weight());nav.addView(next,Ui.weight());body.addView(nav);
        }
    }
    private void details(MediaSourceClient.Source s,MediaSourceClient.Item item){task("正在加载漫画详情…",(token,active)->{MediaSourceClient.Detail detail=client.detail(s,item.id,active);runOnUiThread(()->{if(!valid(token))return;body.removeAllViews();status.setText(detail.title+" · "+detail.chapters.size()+" 章");body.addView(Ui.button(this,"返回搜索",this::returnToSources));if(!detail.description.isEmpty())body.addView(Ui.text(this,detail.description,14,Ui.MUTED));for(MediaSourceClient.Chapter chapter:detail.chapters)body.addView(Ui.button(this,chapter.title,()->loadPages(s,chapter,()->details(s,item))));});});}
    private Runnable chapterBack;
    private void loadPages(MediaSourceClient.Source s,MediaSourceClient.Chapter chapter,Runnable back){task("正在加载章节…",(token,active)->{List<String> loaded=client.pages(s,chapter.id,active);runOnUiThread(()->{if(!valid(token))return;pages=loaded;imagePage=0;pageTitle=chapter.title;chapterBack=back;showImage();});});}
    private void showImage(){String address=pages.get(imagePage);task("正在读取图片…",(token,active)->{byte[] bytes=MediaSourceClient.download(address,12*1024*1024,active);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>60000||bounds.outHeight>60000)throw new IOException("图片格式或尺寸无效");BitmapFactory.Options options=new BitmapFactory.Options();int sample=1;while(bounds.outWidth/sample>1800||bounds.outHeight/sample>3600)sample*=2;options.inSampleSize=sample;Bitmap result=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(result==null)throw new IOException("无法解码图片");runOnUiThread(()->{if(!valid(token)){result.recycle();return;}body.removeAllViews();if(bitmap!=null)bitmap.recycle();bitmap=result;status.setText(pageTitle+" · "+(imagePage+1)+" / "+pages.size());LinearLayout nav=Ui.row(this);Button prev=Ui.button(this,"上一页",()->{imagePage--;showImage();}),next=Ui.button(this,"下一页",()->{imagePage++;showImage();});prev.setEnabled(imagePage>0);next.setEnabled(imagePage+1<pages.size());nav.addView(prev,Ui.weight());nav.addView(Ui.button(this,"章节",()->{pages=Collections.emptyList();chapterBack.run();}),Ui.weight());nav.addView(next,Ui.weight());body.addView(nav);ImageView image=new ImageView(this);image.setContentDescription("图片内容");image.setAdjustViewBounds(true);image.setImageBitmap(result);body.addView(image,new LinearLayout.LayoutParams(-1,-2));});});}
    private void resolveTrack(MediaSourceClient.Source source,MediaSourceClient.Item item){
        task("正在获取播放地址 · "+source.name,(token,active)->{
            String url=client.stream(source,item.id,active);
            runOnUiThread(()->{
                if(!valid(token))return;
                if(looksVideo(url))startVideo(Uri.parse(url),item.title,VideoOrigin.SOURCE,new VideoCtx(source.id,source.name,item.id,item.title,url));
                else startAudio(item.title,url);
            });
        },error->{
            status.setText(source.name+" · 获取播放地址失败");clearBody();
            body.addView(Ui.text(this,"加载失败："+message(error),14,Ui.MUTED));
            body.addView(Ui.button(this,"重试获取地址",()->resolveTrack(source,item)));
            body.addView(Ui.button(this,"返回搜索结果",this::showSourceResults));
        },this::showSourceResults);
    }
    private void startAudio(String title,String url){stopAudio();stopVideo();body.removeAllViews();status.setText(title+" · 缓冲中");body.addView(Ui.button(this,"返回搜索",this::returnToSources));body.addView(Ui.title(this,title,22));seek=new SeekBar(this);seek.setMax(1000);seek.setEnabled(false);body.addView(seek);time=Ui.text(this,"00:00 / 00:00",13,Ui.MUTED);body.addView(time);LinearLayout controls=Ui.row(this);play=Ui.button(this,"播放",()->{if(player==null||!prepared)return;if(player.isPlaying()){player.pause();play.setText("播放");}else{player.play();play.setText("暂停");}});play.setEnabled(false);controls.addView(play,Ui.weight());controls.addView(Ui.button(this,"停止",()->{stopAudio();status.setText("已停止");}),Ui.weight());body.addView(controls);body.addView(Ui.text(this,"仅前台播放，离开本页暂停。",12,Ui.MUTED));
        OpenLeafPlayer active=new OpenLeafPlayer(this);player=active;
        active.setListener(new OpenLeafPlayer.Listener(){
            public void onPrepared(){if(player!=active||isDestroyed())return;prepared=true;seek.setEnabled(true);play.setEnabled(true);status.setText(title);if(foreground){active.play();play.setText("暂停");}handler.post(ticker);}
            public void onCompletion(){if(player==active)play.setText("重新播放");}
            public void onError(String reason){if(player==active){stopAudio();status.setText("播放失败，可重新搜索获取地址。");}}
            public void onTracksChanged(){}
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int value,boolean user){if(user&&player!=null&&prepared)player.seekTo((long)(player.duration()*value/1000L));}public void onStopTrackingTouch(SeekBar b){}});
        active.setMediaItem(android.net.Uri.parse(url),null);}
    private void stopAudio(){handler.removeCallbacks(ticker);prepared=false;if(player!=null){player.release();player=null;}if(play!=null)play.setEnabled(false);if(seek!=null)seek.setEnabled(false);}

    // ---------------- T03 视频模式 ----------------
    private void pickLocalVideo(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{startActivityForResult(intent,8303);}catch(ActivityNotFoundException absent){(videoStatus!=null?videoStatus:status).setText("系统视频文件选择器不可用");}
    }
    private static boolean looksVideo(String url){
        if(url==null)return false;String lower=url.toLowerCase(Locale.ROOT);int q=lower.indexOf('?');if(q>=0)lower=lower.substring(0,q);
        return lower.endsWith(".mp4")||lower.endsWith(".mkv")||lower.endsWith(".webm")||lower.endsWith(".m4v")||lower.endsWith(".mov")||lower.endsWith(".avi")||lower.endsWith(".ts");
    }
    private void startVideo(Uri uri,String title,VideoOrigin origin,VideoCtx ctx){
        cancel();stopAudio();stopVideo();clearBody();videoOrigin=origin;videoCtx=ctx;
        browseControls.setVisibility(View.GONE);screenTitle.setText("视频播放");topBack.setText("返回来源");
        body.addView(Ui.title(this,title==null?"视频":title,22));Ui.gap(body,10);
        LinearLayout viewing=Ui.column(this);viewing.setBackground(Ui.outline(this,Ui.PANEL,12));Ui.pad(viewing,10);body.addView(viewing);
        viewing.addView(Ui.title(this,"播放器",16));Ui.gap(viewing,8);
        FrameLayout surface=new FrameLayout(this);surface.setBackgroundColor(android.graphics.Color.BLACK);
        int widthDp=getResources().getConfiguration().screenWidthDp;
        int playerHeight=Math.min(320,Math.max(144,(widthDp-44)*9/16));
        viewing.addView(surface,new LinearLayout.LayoutParams(-1,Ui.dp(this,playerHeight)));
        videoStatus=Ui.text(this,"正在准备视频…",13,Ui.INK);Ui.gap(viewing,8);viewing.addView(videoStatus);

        Ui.gap(body,12);
        LinearLayout actions=Ui.column(this);actions.setBackground(Ui.outline(this,Ui.BG,12));Ui.pad(actions,12);body.addView(actions);
        actions.addView(Ui.title(this,"来源与播放设置",16));
        String source=ctx!=null?"接口来源 · "+ctx.sourceName:origin==VideoOrigin.DOWNLOADED?"来源 · 已下载视频":"来源 · 本地文件";
        actions.addView(Ui.text(this,source,14,Ui.MUTED));Ui.gap(actions,10);
        LinearLayout tracks=Ui.row(this);
        videoTracks=Ui.button(this,"音轨",this::showVideoTracks);videoTracks.setEnabled(false);tracks.addView(videoTracks,Ui.weight());
        Button subtitles=Ui.button(this,TX_PICK_SUBTITLE,()->openSubtitlePicker(8304));subtitles.setEnabled(false);tracks.addView(subtitles,Ui.weight());
        actions.addView(tracks);Ui.gap(actions,6);
        if(ctx!=null){
            String key=MediaDownloads.key(ctx.sourceId,ctx.itemId);
            boolean saved=getSharedPreferences(MediaDownloads.PREFS,0).contains(key),downloading=downloadingVideos.contains(key);
            videoDownload=Ui.button(this,saved?TX_DL_SAVED:TX_DL_TO_LOCAL,this::downloadCurrentVideo);
            videoDownload.setEnabled(!downloading);actions.addView(videoDownload);
            Ui.gap(actions,6);downloadStatus=Ui.text(this,saved?TX_DL_EXISTS:downloading?"正在下载…":TX_DL_LIMIT,12,Ui.MUTED);actions.addView(downloadStatus);
            actions.addView(Ui.button(this,"返回搜索结果",this::returnToSources));
        }else if(origin==VideoOrigin.DOWNLOADED){
            actions.addView(Ui.button(this,"打开其他已下载视频",this::showDownloads));
        }else{
            actions.addView(Ui.button(this,"选择其他本地视频",this::pickLocalVideo));
        }
        final int token=++videoGeneration;currentVideo=uri;videoStatusOverride=null;selectedVideoTrack=0;
        try{
            videoView=new PlayerView(this);videoView.setUseController(true);
            surface.addView(videoView,new FrameLayout.LayoutParams(-1,-1));
            OpenLeafPlayer candidate=new OpenLeafPlayer(this);videoPlayer=candidate;
            videoView.setPlayer(candidate.underlying());
            candidate.setListener(new OpenLeafPlayer.Listener(){
                public void onPrepared(){if(!currentVideoPlayer(token,candidate))return;
                    updateVideoTracks(candidate.trackLabels());subtitles.setEnabled(true);
                    if(videoStatusOverride!=null){videoStatus.setText(videoStatusOverride);videoStatusOverride=null;}
                    else videoStatus.setText(String.format(Locale.ROOT,TX_VIDEO_READY,candidate.trackLabels().size()));
                    if(foreground)candidate.play();}
                public void onCompletion(){if(currentVideoPlayer(token,candidate))videoStatus.setText("播放结束 · 可在播放器内重播");}
                public void onError(String reason){if(currentVideoPlayer(token,candidate)){
                    videoStatusOverride=null;videoStatus.setText(String.format(Locale.ROOT,TX_VIDEO_FAIL,reason));videoTracks.setEnabled(false);
                }}
                public void onTracksChanged(){if(currentVideoPlayer(token,candidate))updateVideoTracks(candidate.trackLabels());}
            });
            candidate.setMediaItem(uri,null);
        }catch(LinkageError|RuntimeException missing){
            releaseVideoPlayer();videoStatus.setText(TX_NO_COMPONENT);
        }
    }
    private boolean currentVideoPlayer(int token,OpenLeafPlayer candidate){return !isDestroyed()&&token==videoGeneration&&videoPlayer==candidate;}
    private void updateVideoTracks(List<String> labels){
        videoTracks.setEnabled(labels.size()>1);
        if(labels.size()>1){
            if(selectedVideoTrack>=labels.size())selectedVideoTrack=0;
            videoTracks.setText(String.format(Locale.ROOT,TX_TRACKS,labels.get(selectedVideoTrack),labels.size()));
        }else videoTracks.setText(labels.isEmpty()?"未检测到音轨":"音轨 · "+labels.get(0));
    }
    private void leaveVideo(){
        boolean wasDownload=videoOrigin==VideoOrigin.DOWNLOADED;
        returnToSources();
        if(wasDownload)showDownloads();
    }
    private void openSubtitlePicker(int request){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{subtitleGeneration=videoGeneration;startActivityForResult(intent,request);}catch(ActivityNotFoundException absent){if(videoStatus!=null)videoStatus.setText(TX_NO_COMPONENT);}
    }
    @UnstableApi private void loadVideoSubtitle(android.net.Uri subtitle){
        if(videoPlayer==null||currentVideo==null||subtitle==null)return;
        String name=subtitle.getLastPathSegment();if(name==null)name="字幕";
        try{
            videoPlayer.setSubtitle(subtitle,getContentResolver().getType(subtitle));
            videoStatusOverride=String.format(Locale.ROOT,TX_SUBTITLE_OK,name);
            videoPlayer.setMediaItem(currentVideo,null);
        }catch(RuntimeException failure){videoStatus.setText(String.format(Locale.ROOT,TX_SUBTITLE_FAIL,"未知错误（"+failure.getClass().getSimpleName()+"）"));}
    }
    @UnstableApi private void showVideoTracks(){
        OpenLeafPlayer candidate=videoPlayer;
        if(candidate==null)return;List<String> labels=candidate.trackLabels();if(labels.size()<2)return;
        int token=videoGeneration;
        new AlertDialog.Builder(this).setTitle("选择音轨").setSingleChoiceItems(labels.toArray(new String[0]),selectedVideoTrack,(dialog,which)->{
            dialog.dismiss();if(!currentVideoPlayer(token,candidate)||which<0||which>=labels.size())return;
            selectedVideoTrack=which;candidate.selectTrack(which);updateVideoTracks(candidate.trackLabels());
        }).setNegativeButton("取消",null).show();
    }
    private void releaseVideoPlayer(){
        if(videoView!=null){videoView.setPlayer(null);videoView=null;}
        if(videoPlayer!=null){videoPlayer.release();videoPlayer=null;}
    }
    private void stopVideo(){
        videoGeneration++;currentVideo=null;videoStatusOverride=null;
        releaseVideoPlayer();
        videoCtx=null;videoDownload=null;downloadStatus=null;videoStatus=null;videoTracks=null;
        if(videoOrigin!=null){
            videoOrigin=null;browseControls.setVisibility(View.VISIBLE);screenTitle.setText("媒体来源");topBack.setText("返回");
        }
    }

    // ---------------- 1B 视频落盘：复用 DownloadUseCase + AssetDownloader + jobs ----------------
    private static String videoExt(String url){
        if(url==null)return ".mp4";
        String lower=url.toLowerCase(Locale.ROOT);int q=lower.indexOf('?');if(q>=0)lower=lower.substring(0,q);
        for(String ext:new String[]{".mp4",".mkv",".webm",".m4v",".mov",".avi",".ts"})if(lower.endsWith(ext))return ext;
        return ".mp4";
    }
    private void downloadCurrentVideo(){
        final VideoCtx ctx=videoCtx;
        if(ctx==null)return;
        final String key=MediaDownloads.key(ctx.sourceId,ctx.itemId);
        final android.content.SharedPreferences store=getSharedPreferences(MediaDownloads.PREFS,0);
        if(store.contains(key)){showDownloads();return;}
        if(!downloadingVideos.add(key))return;
        final String fileName=DownloadPolicy.safeFileName(ctx.title,60)+videoExt(ctx.streamUrl);
        final java.util.List<DownloadUseCase.Request> requests=
                java.util.Collections.singletonList(new DownloadUseCase.Request(ctx.streamUrl,fileName,"",DownloadPolicy.UNKNOWN_LENGTH));
        final ContentContracts.JobRepository jobs=ServiceRegistry.of(this).jobs();
        final String jobId="download-"+System.currentTimeMillis();
        jobs.create(jobId,"download","media-video",DownloadUseCase.toJobItems(requests));
        jobs.updateState(jobId,ContentContracts.JobState.RUNNING,0,0,0,0);
        downloadStatus.setText(String.format(Locale.ROOT,TX_DL_DOWNLOADING,fileName));
        if(videoDownload!=null)videoDownload.setEnabled(false);
        worker.execute(()->{
            final String[] saved=new String[1];
            final DownloadUseCase.Summary summary=DownloadUseCase.run(requests,jobs,jobId,
                    new AssetDownloader(this),new DownloadUseCase.Progress(){
                        public void onItemDone(int processed,int total,DownloadUseCase.Result result){
                            if(result.success)saved[0]=result.localPath;
                        }
                        public boolean cancelled(){return false;}
                    });
            runOnUiThread(()->{
                if(isDestroyed())return;
                downloadingVideos.remove(key);
                boolean isCurrent=videoCtx!=null&&key.equals(MediaDownloads.key(videoCtx.sourceId,videoCtx.itemId));
                if(summary.succeeded>0&&saved[0]!=null){
                    try{
                        java.io.File root=getFilesDir().getCanonicalFile();
                        java.io.File file=new java.io.File(saved[0]).getCanonicalFile();
                        String prefix=root.getCanonicalPath()+java.io.File.separator;
                        if(file.getPath().startsWith(prefix)){
                            String rel=file.getPath().substring(prefix.length()).replace(java.io.File.separatorChar,'/');
                            if(MediaDownloads.safeRelative(rel)&&MediaDownloads.inside(root,file)){
                                store.edit().putString(key,MediaDownloads.encode(
                                        new MediaDownloads.Entry(rel,ctx.title,file.length()))).apply();
                                if(isCurrent){downloadStatus.setText(TX_DL_DONE);videoDownload.setText(TX_DL_SAVED);videoDownload.setEnabled(true);}
                                return;
                            }
                        }
                    }catch(IOException broken){/* 落盘索引失败按下载失败处理，不误报成功 */}
                }
                String why=summary.failed>0?"失败 "+summary.failed+" 项":"未取得本地文件";
                if(isCurrent){downloadStatus.setText(String.format(Locale.ROOT,TX_DL_FAIL,why));videoDownload.setEnabled(true);}
            });
        });
    }
    private void showDownloads(){
        final android.content.SharedPreferences store=getSharedPreferences(MediaDownloads.PREFS,0);
        final java.io.File root=getFilesDir();
        LinearLayout list=Ui.column(this);Ui.pad(list,12);
        final AlertDialog[] holder=new AlertDialog[1];
        int count=0;
        for(java.util.Map.Entry<String,?> item:new java.util.HashMap<>(store.getAll()).entrySet()){
            final String key=item.getKey();
            MediaDownloads.Entry entry=MediaDownloads.decode(item.getValue() instanceof String?(String)item.getValue():null);
            if(entry==null||!MediaDownloads.safeRelative(entry.relPath)){store.edit().remove(key).apply();continue;}
            final java.io.File file=new java.io.File(root,entry.relPath);
            if(!MediaDownloads.inside(root,file)||!file.isFile()){store.edit().remove(key).apply();continue;}
            count++;
            final String title=entry.title.isEmpty()?file.getName():entry.title;
            LinearLayout row=Ui.row(this);
            Button play=Ui.button(this,title+" · "+(entry.bytes/1048576)+" MB",
                    ()->{if(!isDestroyed()){
                        if(holder[0]!=null)holder[0].dismiss();
                        startVideo(Uri.fromFile(file),title,VideoOrigin.DOWNLOADED,null);
                    }},"播放已下载视频 "+title);
            row.addView(play,Ui.weight());
            row.addView(Ui.button(this,"删除",()->new AlertDialog.Builder(this).setTitle("删除已下载视频？")
                    .setMessage(title+"\n删除后需要重新下载。")
                    .setNegativeButton("取消",null)
                    .setPositiveButton("删除",(d,w)->{
                        if(file.delete()||!file.exists()){/* 已删除或本就不存在 */}
                        store.edit().remove(key).apply();
                        if(holder[0]!=null)holder[0].dismiss();
                        showDownloads();
                    }).show(),"删除已下载视频 "+title));
            list.addView(row);
        }
        if(count==0)list.addView(Ui.text(this,TX_DL_EMPTY,14,Ui.MUTED));
        else list.addView(Ui.text(this,TX_DL_LIMIT,12,Ui.MUTED));
        ScrollView scroller=new ScrollView(this);scroller.addView(list);
        holder[0]=new AlertDialog.Builder(this).setTitle(TX_DL_MANAGE+"（"+count+"）").setView(scroller)
                .setNegativeButton("关闭",null).show();
    }
    private void document(boolean export){if(export&&sources.isEmpty()){status.setText("没有可导出的源");return;}try{Intent i=new Intent(export?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");if(export)i.putExtra(Intent.EXTRA_TITLE,"openleaf-media-sources.json");startActivityForResult(i,export?EXPORT:IMPORT);}catch(ActivityNotFoundException e){status.setText("系统文件选择器不可用");}}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;android.net.Uri uri=data.getData();
        if(req==8303||req==8304){
            if(req==8304&&(subtitleGeneration!=videoGeneration||videoPlayer==null))return;
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
            if(req==8303)startVideo(uri,TX_PICK_VIDEO,VideoOrigin.LOCAL,null);else loadVideoSubtitle(uri);return;}
        if(req==EXPORT){try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("不能写入");out.write(MediaSourceClient.pack(sources).getBytes(StandardCharsets.UTF_8));status.setText("源包已导出");}catch(Exception e){status.setText(message(e));}}else if(req==IMPORT){task("正在读取源包…",(token,active)->{byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("不能读取源包");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){active.throwIfCanceled();if(out.size()+n>256*1024)throw new IOException("源包超过256 KiB");out.write(b,0,n);}bytes=out.toByteArray();}List<MediaSourceClient.Source> parsed=MediaSourceClient.parsePack(new String(bytes,StandardCharsets.UTF_8));runOnUiThread(()->{if(!valid(token))return;new AlertDialog.Builder(this).setTitle("导入 "+parsed.size()+" 个媒体源？").setMessage("同 id 的现有源保留。配置仅在搜索或播放时访问接口。").setNegativeButton("取消",(d,w)->refreshSources()).setPositiveButton("导入",(d,w)->{HashSet<String> known=new HashSet<>();for(MediaSourceClient.Source s:sources)known.add(s.id);ArrayList<MediaSourceClient.Source> added=new ArrayList<>();for(MediaSourceClient.Source s:parsed)if(known.add(s.id))added.add(s);if(sources.size()+added.size()>100){status.setText("合并后超过100个源，本次未导入");return;}sources.addAll(added);save();refreshSources();}).show();});});}}
    @Override protected void onStart(){super.onStart();foreground=true;}
    @Override protected void onStop(){foreground=false;if(player!=null&&prepared&&player.isPlaying()){player.pause();play.setText("播放");}if(videoPlayer!=null&&videoPlayer.isPlaying()){videoPlayer.pause();if(videoStatus!=null)videoStatus.setText(TX_LEFT);}super.onStop();}
    @Override protected void onDestroy(){cancel();stopAudio();stopVideo();worker.shutdownNow();if(bitmap!=null){body.removeAllViews();bitmap.recycle();bitmap=null;}super.onDestroy();}
    private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private static String clock(int ms){return String.format(Locale.ROOT,"%02d:%02d",ms/60000,(ms/1000)%60);}
}
