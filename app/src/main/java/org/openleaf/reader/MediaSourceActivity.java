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
    private LinearLayout root,body;private TextView status;private EditText query;private Spinner sourcePicker;
    private CancellationSignal signal;private int generation,searchPage=1;private String lastQuery="";private boolean more;
    private OpenLeafPlayer player;private boolean prepared,foreground;private SeekBar seek;private TextView time;private Button play;
    // T03 视频模式（与音频/漫画图片互斥）：复用 OpenLeafPlayer，PlayerView 只在本页承载视频。
    private OpenLeafPlayer videoPlayer;private PlayerView videoView;private TextView videoStatus;private Button videoTracks;
    private Uri currentVideo;private String videoStatusOverride;private int videoGeneration;private boolean videoMode;
    private static final String TX_VIDEO_READY="视频已就绪 · 共 %d 条音轨";
    private static final String TX_SUBTITLE_OK="已加载字幕：%s";
    private static final String TX_SUBTITLE_FAIL="字幕文件无法读取（%s），视频仍可播放。";
    private static final String TX_TRACKS="音轨：%s（共 %d 条，点此处切换）";
    private static final String TX_DECODE_FAIL="本机无法解码该格式（%s），可换用其他文件。";
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
    private VideoCtx videoCtx;private Button videoDownload;
    private static final class VideoCtx {
        final String sourceId,itemId,title,streamUrl;
        VideoCtx(String sourceId,String itemId,String title,String streamUrl){
            this.sourceId=sourceId;this.itemId=itemId;this.title=title;this.streamUrl=streamUrl;
        }
    }
    private List<String> pages=Collections.emptyList();private int imagePage;private Bitmap bitmap;private String pageTitle="";
    private final Runnable ticker=new Runnable(){public void run(){if(player!=null&&prepared){try{long duration=player.duration(),position=player.position();seek.setProgress(duration==0?0:(int)(1000L*position/duration));time.setText(clock((int)position)+" / "+clock((int)duration));}catch(IllegalStateException ignored){}}handler.postDelayed(this,500);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);root=Ui.column(this);Ui.mount(this,root);LinearLayout top=Ui.row(this);top.addView(Ui.button(this,"返回",this::finish));top.addView(Ui.title(this,"音乐与漫画源",20),Ui.weight());root.addView(top);
        LinearLayout tools=Ui.row(this);tools.addView(Ui.button(this,"添加源",this::addSource),Ui.weight());tools.addView(Ui.button(this,"导入",()->document(false)),Ui.weight());tools.addView(Ui.button(this,"导出",()->document(true)),Ui.weight());tools.addView(Ui.button(this,TX_PICK_VIDEO,this::pickLocalVideo),Ui.weight());root.addView(tools);
        LinearLayout dlTools=Ui.row(this);dlTools.addView(Ui.button(this,TX_DL_MANAGE+"视频",this::showDownloads,"已下载视频"),Ui.weight());root.addView(dlTools);
        sourcePicker=new Spinner(this);root.addView(sourcePicker);LinearLayout search=Ui.row(this);query=new EditText(this);query.setSingleLine(true);query.setHint("搜索歌曲或漫画");search.addView(query,Ui.weight());search.addView(Ui.button(this,"搜索",()->{searchPage=1;lastQuery=query.getText().toString().trim();search();}));root.addView(search);
        status=Ui.text(this,"",13,Ui.MUTED);Ui.pad(status,10);root.addView(status);ScrollView scroll=new ScrollView(this);body=Ui.column(this);Ui.pad(body,12);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        try{sources.addAll(MediaSourceClient.parsePack(getSharedPreferences("media_sources",0).getString("pack","{\"format\":\"openleaf-media-sources\",\"schemaVersion\":1,\"sources\":[]}")));}catch(Exception e){status.setText("配置读取失败："+message(e));}refreshSources();
    }
    private void refreshSources(){ArrayList<String> labels=new ArrayList<>();for(MediaSourceClient.Source s:sources)labels.add((s.kind.equals("music")?"音乐 · ":"漫画 · ")+s.name);sourcePicker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));body.removeAllViews();status.setText(sources.isEmpty()?"添加接口地址或导入 OpenLeaf JSON 源包开始使用。":"已保存 "+sources.size()+" 个源；选择源后搜索。");body.addView(Ui.text(this,"源支持搜索、歌曲播放地址，或漫画详情、章节和图片。VeneraNext / XY-Music 的脚本需先通过适配服务转成此接口。",14,Ui.MUTED));body.addView(Ui.button(this,"移除当前源",()->{MediaSourceClient.Source s=selected();if(s!=null)new AlertDialog.Builder(this).setTitle("移除 "+s.name+"？").setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->{cancel();sources.remove(s);save();refreshSources();}).show();}));}
    private MediaSourceClient.Source selected(){int i=sourcePicker.getSelectedItemPosition();return i>=0&&i<sources.size()?sources.get(i):null;}
    private void save(){try{getSharedPreferences("media_sources",0).edit().putString("pack",MediaSourceClient.pack(sources)).apply();}catch(Exception e){status.setText(message(e));}}
    private void addSource(){LinearLayout form=Ui.column(this);Ui.pad(form,16);EditText name=new EditText(this),url=new EditText(this);name.setHint("源名称");url.setHint("https://your-adapter.example/api/");url.setSingleLine(true);form.addView(name);form.addView(url);Spinner kind=new Spinner(this);kind.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"音乐接口","漫画接口"}));form.addView(kind);TextView help=Ui.text(this,"默认接口：search?q={query}&page={page}、detail?id={id}、pages?id={id}、stream?id={id}。自定义路径可用源包导入。",12,Ui.MUTED);form.addView(help);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("添加媒体源").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{if(sources.size()>=100)throw new IOException("最多100个源");String base=url.getText().toString().trim();if(!base.endsWith("/"))base+="/";JSONObject j=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name.getText().toString()).put("kind",kind.getSelectedItemPosition()==0?"music":"comics").put("baseUrl",base).put("search","search?q={query}&page={page}").put("detail","detail?id={id}").put("pages","pages?id={id}").put("stream","stream?id={id}");sources.add(new MediaSourceClient.Source(j));save();refreshSources();dialog.dismiss();}catch(Exception e){url.setError(message(e));}}));dialog.show();}
    private interface Work {void run(int token,CancellationSignal signal)throws Exception;}
    private void task(String label,Work work){cancel();stopVideo();int token=generation;CancellationSignal active=new CancellationSignal();signal=active;status.setText(label);body.removeAllViews();if(bitmap!=null){bitmap.recycle();bitmap=null;}body.addView(Ui.button(this,"停止加载",()->{cancel();status.setText("加载已取消");}));worker.execute(()->{try{work.run(token,active);}catch(Exception e){runOnUiThread(()->{if(valid(token)){status.setText("加载失败："+message(e));body.removeAllViews();body.addView(Ui.button(this,"重试搜索",this::search));}});}});}
    private boolean valid(int token){return !isDestroyed()&&token==generation;}
    private void cancel(){generation++;if(signal!=null)signal.cancel();signal=null;}
    private void search(){MediaSourceClient.Source s=selected();if(s==null){status.setText("请先添加源");return;}if(lastQuery.isEmpty()){status.setText("请输入搜索关键词");return;}stopAudio();pages=Collections.emptyList();task("正在搜索…",(token,active)->{String url=s.url(s.search,lastQuery,"",searchPage);JSONObject response=client.request(url,active);List<MediaSourceClient.Item> found=MediaSourceClient.items(response);boolean next=response.optBoolean("hasMore",false);runOnUiThread(()->{if(!valid(token))return;more=next;body.removeAllViews();status.setText(found.size()+" 项 · 第 "+searchPage+" 页");for(MediaSourceClient.Item item:found){Button button=Ui.button(this,item.title+(item.subtitle.isEmpty()?"":"\n"+item.subtitle),()->{if(s.kind.equals("music"))resolveTrack(s,item);else details(s,item);});button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);body.addView(button);}LinearLayout nav=Ui.row(this);Button prev=Ui.button(this,"上一页",()->{searchPage--;search();}),nextButton=Ui.button(this,"下一页",()->{searchPage++;search();});prev.setEnabled(searchPage>1);nextButton.setEnabled(more);nav.addView(prev,Ui.weight());nav.addView(nextButton,Ui.weight());body.addView(nav);});});}
    private void details(MediaSourceClient.Source s,MediaSourceClient.Item item){task("正在加载漫画详情…",(token,active)->{MediaSourceClient.Detail detail=client.detail(s,item.id,active);runOnUiThread(()->{if(!valid(token))return;body.removeAllViews();status.setText(detail.title+" · "+detail.chapters.size()+" 章");body.addView(Ui.button(this,"返回搜索",this::search));if(!detail.description.isEmpty())body.addView(Ui.text(this,detail.description,14,Ui.MUTED));for(MediaSourceClient.Chapter chapter:detail.chapters)body.addView(Ui.button(this,chapter.title,()->loadPages(s,chapter,()->details(s,item))));});});}
    private Runnable chapterBack;
    private void loadPages(MediaSourceClient.Source s,MediaSourceClient.Chapter chapter,Runnable back){task("正在加载章节…",(token,active)->{List<String> loaded=client.pages(s,chapter.id,active);runOnUiThread(()->{if(!valid(token))return;pages=loaded;imagePage=0;pageTitle=chapter.title;chapterBack=back;showImage();});});}
    private void showImage(){String address=pages.get(imagePage);task("正在读取图片…",(token,active)->{byte[] bytes=MediaSourceClient.download(address,12*1024*1024,active);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>60000||bounds.outHeight>60000)throw new IOException("图片格式或尺寸无效");BitmapFactory.Options options=new BitmapFactory.Options();int sample=1;while(bounds.outWidth/sample>1800||bounds.outHeight/sample>3600)sample*=2;options.inSampleSize=sample;Bitmap result=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(result==null)throw new IOException("无法解码图片");runOnUiThread(()->{if(!valid(token)){result.recycle();return;}body.removeAllViews();if(bitmap!=null)bitmap.recycle();bitmap=result;status.setText(pageTitle+" · "+(imagePage+1)+" / "+pages.size());LinearLayout nav=Ui.row(this);Button prev=Ui.button(this,"上一页",()->{imagePage--;showImage();}),next=Ui.button(this,"下一页",()->{imagePage++;showImage();});prev.setEnabled(imagePage>0);next.setEnabled(imagePage+1<pages.size());nav.addView(prev,Ui.weight());nav.addView(Ui.button(this,"章节",()->{pages=Collections.emptyList();chapterBack.run();}),Ui.weight());nav.addView(next,Ui.weight());body.addView(nav);ImageView image=new ImageView(this);image.setContentDescription("图片内容");image.setAdjustViewBounds(true);image.setImageBitmap(result);body.addView(image,new LinearLayout.LayoutParams(-1,-2));});});}
    private void resolveTrack(MediaSourceClient.Source s,MediaSourceClient.Item item){task("正在获取播放地址…",(token,active)->{String url=client.stream(s,item.id,active);runOnUiThread(()->{if(!valid(token))return;if(looksVideo(url))startVideo(android.net.Uri.parse(url),item.title,new VideoCtx(s.id,item.id,item.title,url));else startAudio(item.title,url);});});}
    private void startAudio(String title,String url){stopAudio();stopVideo();body.removeAllViews();status.setText(title+" · 缓冲中");body.addView(Ui.button(this,"返回搜索",this::search));body.addView(Ui.title(this,title,22));seek=new SeekBar(this);seek.setMax(1000);seek.setEnabled(false);body.addView(seek);time=Ui.text(this,"00:00 / 00:00",13,Ui.MUTED);body.addView(time);LinearLayout controls=Ui.row(this);play=Ui.button(this,"播放",()->{if(player==null||!prepared)return;if(player.isPlaying()){player.pause();play.setText("播放");}else{player.play();play.setText("暂停");}});play.setEnabled(false);controls.addView(play,Ui.weight());controls.addView(Ui.button(this,"停止",()->{stopAudio();status.setText("已停止");}),Ui.weight());body.addView(controls);body.addView(Ui.text(this,"仅前台播放，离开本页暂停。",12,Ui.MUTED));
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
        try{startActivityForResult(intent,8303);}catch(ActivityNotFoundException absent){status.setText(TX_NO_COMPONENT);}
    }
    private static boolean looksVideo(String url){
        if(url==null)return false;String lower=url.toLowerCase(Locale.ROOT);int q=lower.indexOf('?');if(q>=0)lower=lower.substring(0,q);
        return lower.endsWith(".mp4")||lower.endsWith(".mkv")||lower.endsWith(".webm")||lower.endsWith(".m4v")||lower.endsWith(".mov")||lower.endsWith(".avi")||lower.endsWith(".ts");
    }
    private void startVideo(android.net.Uri uri,String title,VideoCtx ctx){
        stopAudio();stopVideo();body.removeAllViews();videoMode=true;videoCtx=ctx;
        videoStatus=Ui.text(this,"",13,Ui.MUTED);body.addView(videoStatus);
        body.addView(Ui.title(this,title==null?"视频":title,20));
        try{
            videoView=new PlayerView(this);videoView.setUseController(true);
            body.addView(videoView,new LinearLayout.LayoutParams(-1,620));
        }catch(LinkageError|RuntimeException missing){videoView=null;videoStatus.setText(TX_NO_COMPONENT);return;}
        videoTracks=Ui.button(this,"音轨",this::showVideoTracks);body.addView(videoTracks);
        body.addView(Ui.button(this,TX_PICK_SUBTITLE,()->openSubtitlePicker(8304)));
        if(ctx!=null){
            boolean saved=getSharedPreferences(MediaDownloads.PREFS,0).contains(MediaDownloads.key(ctx.sourceId,ctx.itemId));
            videoDownload=Ui.button(this,saved?TX_DL_SAVED:TX_DL_TO_LOCAL,this::downloadCurrentVideo,"下载视频到本地");
            videoDownload.setEnabled(!saved);
            body.addView(videoDownload);
        }
        body.addView(Ui.button(this,"返回搜索",this::search));
        final int token=++videoGeneration;currentVideo=uri;videoStatusOverride=null;
        try{
            OpenLeafPlayer candidate=new OpenLeafPlayer(this);videoPlayer=candidate;
            videoView.setPlayer(candidate.underlying());
            candidate.setListener(new OpenLeafPlayer.Listener(){
                public void onPrepared(){if(isDestroyed()||token!=videoGeneration||videoPlayer!=candidate)return;
                    java.util.List<String> labels=candidate.trackLabels();
                    if(videoStatusOverride!=null){videoStatus.setText(videoStatusOverride);videoStatusOverride=null;}
                    else videoStatus.setText(String.format(Locale.ROOT,TX_VIDEO_READY,labels.size()));
                    if(labels.size()>1)videoTracks.setText(String.format(Locale.ROOT,TX_TRACKS,labels.get(0),labels.size()));
                    if(foreground)candidate.play();}
                public void onCompletion(){}
                public void onError(String reason){if(isDestroyed()||token!=videoGeneration||videoPlayer!=candidate)return;videoStatus.setText(String.format(Locale.ROOT,TX_DECODE_FAIL,reason));}
                public void onTracksChanged(){if(isDestroyed()||token!=videoGeneration||videoPlayer!=candidate)return;java.util.List<String> labels=candidate.trackLabels();if(labels.size()>1)videoTracks.setText(String.format(Locale.ROOT,TX_TRACKS,labels.get(0),labels.size()));}
            });
            candidate.setMediaItem(uri,null);
        }catch(LinkageError|RuntimeException missing){videoStatus.setText(TX_NO_COMPONENT);}
    }
    private void openSubtitlePicker(int request){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{startActivityForResult(intent,request);}catch(ActivityNotFoundException absent){if(videoStatus!=null)videoStatus.setText(TX_NO_COMPONENT);}
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
        if(videoPlayer==null)return;java.util.List<String> labels=videoPlayer.trackLabels();if(labels.isEmpty())return;
        new AlertDialog.Builder(this).setTitle("选择音轨").setItems(labels.toArray(new String[0]),(dialog,which)->{
            if(isDestroyed()||videoPlayer==null||which<0||which>=labels.size())return;
            videoPlayer.selectTrack(which);
            if(videoTracks!=null)videoTracks.setText(String.format(Locale.ROOT,TX_TRACKS,labels.get(which),labels.size()));
        }).setNegativeButton("取消",null).show();
    }
    private void stopVideo(){
        videoGeneration++;videoMode=false;currentVideo=null;videoStatusOverride=null;
        videoCtx=null;videoDownload=null;
        if(videoView!=null){videoView.setPlayer(null);videoView=null;}
        if(videoPlayer!=null){videoPlayer.release();videoPlayer=null;}
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
        if(store.contains(key)){if(videoStatus!=null)videoStatus.setText(TX_DL_EXISTS);return;}
        final String fileName=DownloadPolicy.safeFileName(ctx.title,60)+videoExt(ctx.streamUrl);
        final java.util.List<DownloadUseCase.Request> requests=
                java.util.Collections.singletonList(new DownloadUseCase.Request(ctx.streamUrl,fileName,"",DownloadPolicy.UNKNOWN_LENGTH));
        final ContentContracts.JobRepository jobs=ServiceRegistry.of(this).jobs();
        final String jobId="download-"+System.currentTimeMillis();
        jobs.create(jobId,"download","media-video",DownloadUseCase.toJobItems(requests));
        jobs.updateState(jobId,ContentContracts.JobState.RUNNING,0,0,0,0);
        if(videoStatus!=null)videoStatus.setText(String.format(Locale.ROOT,TX_DL_DOWNLOADING,fileName));
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
                                if(videoStatus!=null)videoStatus.setText(TX_DL_DONE);
                                if(videoDownload!=null){videoDownload.setText(TX_DL_SAVED);videoDownload.setEnabled(false);}
                                return;
                            }
                        }
                    }catch(IOException broken){/* 落盘索引失败按下载失败处理，不误报成功 */}
                }
                String why=summary.failed>0?"失败 "+summary.failed+" 项":"未取得本地文件";
                if(videoStatus!=null)videoStatus.setText(String.format(Locale.ROOT,TX_DL_FAIL,why));
                if(videoDownload!=null)videoDownload.setEnabled(true);
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
                        startVideo(android.net.Uri.fromFile(file),title,null);
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
        if(req==8303||req==8304){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
            if(req==8303)startVideo(uri,"本地视频",null);else loadVideoSubtitle(uri);return;}
        if(req==EXPORT){try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("不能写入");out.write(MediaSourceClient.pack(sources).getBytes(StandardCharsets.UTF_8));status.setText("源包已导出");}catch(Exception e){status.setText(message(e));}}else if(req==IMPORT){task("正在读取源包…",(token,active)->{byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("不能读取源包");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){active.throwIfCanceled();if(out.size()+n>256*1024)throw new IOException("源包超过256 KiB");out.write(b,0,n);}bytes=out.toByteArray();}List<MediaSourceClient.Source> parsed=MediaSourceClient.parsePack(new String(bytes,StandardCharsets.UTF_8));runOnUiThread(()->{if(!valid(token))return;new AlertDialog.Builder(this).setTitle("导入 "+parsed.size()+" 个媒体源？").setMessage("同 id 的现有源保留。配置仅在搜索或播放时访问接口。").setNegativeButton("取消",(d,w)->refreshSources()).setPositiveButton("导入",(d,w)->{HashSet<String> known=new HashSet<>();for(MediaSourceClient.Source s:sources)known.add(s.id);ArrayList<MediaSourceClient.Source> added=new ArrayList<>();for(MediaSourceClient.Source s:parsed)if(known.add(s.id))added.add(s);if(sources.size()+added.size()>100){status.setText("合并后超过100个源，本次未导入");return;}sources.addAll(added);save();refreshSources();}).show();});});}}
    @Override protected void onStart(){super.onStart();foreground=true;}
    @Override protected void onStop(){foreground=false;if(player!=null&&prepared&&player.isPlaying()){player.pause();play.setText("播放");}if(videoPlayer!=null&&videoPlayer.isPlaying()){videoPlayer.pause();if(videoStatus!=null)videoStatus.setText(TX_LEFT);}super.onStop();}
    @Override protected void onDestroy(){cancel();stopAudio();stopVideo();worker.shutdownNow();if(bitmap!=null){body.removeAllViews();bitmap.recycle();bitmap=null;}super.onDestroy();}
    private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private static String clock(int ms){return String.format(Locale.ROOT,"%02d:%02d",ms/60000,(ms/1000)%60);}
}
