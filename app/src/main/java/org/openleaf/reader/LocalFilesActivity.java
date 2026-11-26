package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-selected local document tree only; audio stays inside the app without background playback. */
public final class LocalFilesActivity extends Activity {
    private static final int REQUEST_TREE=8101, REQUEST_AUDIO=8102;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayList<Entry> files=new ArrayList<>();
    private final ArrayList<String> parentIds=new ArrayList<>(),parentNames=new ArrayList<>();
    private Uri tree;
    private String directoryId,directoryName="本地文件";
    private TextView path,status,audioTitle,audioTime;
    private Button up,play;
    private SeekBar seek;
    private FileAdapter adapter;
    private CancellationSignal query;
    private boolean busy,closed,prepared,seeking,foreground;
    private int requestGeneration,audioGeneration;
    private OpenLeafPlayer player;
    private AlertDialog dialog;

    private static final class Entry {
        final Uri uri; final String name,mime; final long size; final int flags;
        Entry(Uri uri,String name,String mime,long size,int flags){this.uri=uri;this.name=name;this.mime=mime;this.size=size;this.flags=flags;}
        boolean directory(){return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);}
        boolean audio(){String lower=name.toLowerCase(Locale.ROOT);return mime!=null&&mime.startsWith("audio/")||lower.matches(".*\\.(mp3|m4a|aac|ogg|opus|wav|flac)$");}
    }

    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        if(saved!=null){
            String value=saved.getString("tree");if(value!=null)tree=Uri.parse(value);
            directoryId=saved.getString("directoryId");directoryName=saved.getString("directoryName",directoryName);
            ArrayList<String> ids=saved.getStringArrayList("parents"),names=saved.getStringArrayList("parentNames");
            if(ids!=null&&names!=null&&ids.size()==names.size()){parentIds.addAll(ids);parentNames.addAll(names);}
        }
        build();
        if(tree!=null&&directoryId!=null)refresh();
        else {String previous=getSharedPreferences("local_files",0).getString("tree",null);if(previous!=null){tree=Uri.parse(previous);directoryId=DocumentsContract.getTreeDocumentId(tree);directoryName="已授权目录";refresh();}}
    }

    private void build(){
        LinearLayout root=Ui.column(this);Ui.mount(this,root);
        LinearLayout header=Ui.column(this);Ui.pad(header,12);root.addView(header);
        LinearLayout nav=Ui.row(this);nav.addView(Ui.button(this,"返回",this::onBackPressed));nav.addView(Ui.title(this,"本地文件与音频",20),Ui.weight());header.addView(nav);
        LinearLayout actions=Ui.row(this);actions.addView(Ui.button(this,"选择文件夹",this::chooseTree),Ui.weight());actions.addView(Ui.button(this,"选择音频",this::chooseAudio),Ui.weight());actions.addView(Ui.button(this,"新建文件夹",this::createDirectory),Ui.weight());header.addView(actions);
        path=Ui.text(this,"仅访问你授权的目录",13,Ui.MUTED);path.setMaxLines(2);path.setEllipsize(TextUtils.TruncateAt.MIDDLE);header.addView(path);
        LinearLayout tools=Ui.row(this);up=Ui.button(this,"上一级",this::parent);tools.addView(up,Ui.weight());tools.addView(Ui.button(this,"刷新",this::refresh),Ui.weight());header.addView(tools);
        status=Ui.text(this,"选择文件夹开始浏览；长按文件可重命名或删除。",12,Ui.GREEN);status.setMaxLines(3);header.addView(status);
        ListView list=new ListView(this);adapter=new FileAdapter();list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((p,v,position,id)->{if(!busy)open(files.get(position));});
        list.setOnItemLongClickListener((p,v,position,id)->{if(!busy)fileMenu(files.get(position));return true;});
        LinearLayout audio=Ui.column(this);Ui.pad(audio,12);root.addView(audio);
        audioTitle=Ui.text(this,"尚未播放音频",14,Ui.INK);audioTitle.setSingleLine();audioTitle.setEllipsize(TextUtils.TruncateAt.END);audio.addView(audioTitle);
        seek=new SeekBar(this);seek.setMax(1000);seek.setEnabled(false);audio.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){seeking=true;}
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser){}
            public void onStopTrackingTouch(SeekBar bar){seeking=false;if(player!=null&&prepared)try{player.seekTo((long)(player.duration()*bar.getProgress()/1000L));}catch(IllegalStateException error){audioError();}}
        });
        LinearLayout controls=Ui.row(this);play=Ui.button(this,"播放",this::togglePlayback);play.setEnabled(false);controls.addView(play,Ui.weight());controls.addView(Ui.button(this,"停止并释放",this::stopAudio),Ui.weight());audioTime=Ui.text(this,"00:00 / 00:00",12,Ui.MUTED);controls.addView(audioTime,Ui.weight());audio.addView(controls);
        audio.addView(Ui.text(this,"仅本页前台播放；离开应用会暂停，退出此页停止。",12,Ui.MUTED));updatePath();
    }

    private void chooseTree(){
        if(busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{startActivityForResult(intent,REQUEST_TREE);}catch(ActivityNotFoundException error){showError("系统没有文件夹选择器",error);}
    }
    private void chooseAudio(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{startActivityForResult(intent,REQUEST_AUDIO);}catch(ActivityNotFoundException error){showError("系统没有文件选择器",error);}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        try{
            if(request==REQUEST_TREE&&(data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            else if((data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0)getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }catch(SecurityException|IllegalArgumentException temporary){Toast.makeText(this,"该授权可能仅对本次使用有效",Toast.LENGTH_LONG).show();}
        if(request==REQUEST_TREE){
            tree=uri;directoryId=DocumentsContract.getTreeDocumentId(uri);directoryName="已授权目录";parentIds.clear();parentNames.clear();
            boolean persistent=false;for(android.content.UriPermission permission:getContentResolver().getPersistedUriPermissions())if(uri.equals(permission.getUri())&&permission.isReadPermission())persistent=true;
            if(persistent)getSharedPreferences("local_files",0).edit().putString("tree",uri.toString()).apply();refresh();
        }else if(request==REQUEST_AUDIO)startAudio(uri,"所选音频");
    }
    private void updatePath(){up.setEnabled(!busy&&!parentIds.isEmpty());path.setText(directoryId==null?"仅访问你授权的目录":directoryId);}
    private void refresh(){
        if(tree==null||directoryId==null||busy)return;
        if(query!=null)query.cancel();query=new CancellationSignal();final CancellationSignal signal=query;
        final int generation=++requestGeneration;final Uri selectedTree=tree;
        final Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(selectedTree,directoryId);
        busy=true;status.setText("正在读取目录…");updatePath();
        worker.execute(()->{
            try{
                ArrayList<Entry> found=new ArrayList<>();
                try(Cursor cursor=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS},null,null,null,signal)){
                    if(cursor==null)throw new java.io.IOException("文件提供器没有返回目录");
                    while(cursor.moveToNext()){
                        signal.throwIfCanceled();if(found.size()>=20000)throw new java.io.IOException("目录超过20000项，请选择更小的目录");
                        String id=cursor.getString(0),name=cursor.getString(1),mime=cursor.getString(2);
                        if(id!=null)found.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(selectedTree,id),name==null?"未命名":name,mime,cursor.isNull(3)?-1:cursor.getLong(3),cursor.isNull(4)?0:cursor.getInt(4)));
                    }
                }
                Collections.sort(found,(a,b)->a.directory()!=b.directory()?(a.directory()?-1:1):a.name.compareToIgnoreCase(b.name));
                main.post(()->{if(closed||generation!=requestGeneration)return;files.clear();files.addAll(found);adapter.notifyDataSetChanged();busy=false;status.setText(found.size()+" 项 · 点击打开，长按管理文件");updatePath();});
            }catch(Exception error){main.post(()->{if(closed||generation!=requestGeneration)return;busy=false;files.clear();adapter.notifyDataSetChanged();status.setText("无法读取："+message(error)+"。可重新选择目录授权。");updatePath();});}
        });
    }
    private void parent(){if(busy||parentIds.isEmpty())return;directoryId=parentIds.remove(parentIds.size()-1);directoryName=parentNames.remove(parentNames.size()-1);refresh();}
    private void open(Entry entry){
        if(entry.directory()){
            parentIds.add(directoryId);parentNames.add(directoryName);directoryId=DocumentsContract.getDocumentId(entry.uri);directoryName=entry.name;refresh();
        } else if (editableText(entry)) editText(entry);
        else if (entry.audio()) startAudio(entry.uri,entry.name);
        else systemOpen(entry);
    }
    private void systemOpen(Entry entry){
        try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(entry.uri,entry.mime==null?"application/octet-stream":entry.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
        catch(ActivityNotFoundException missing){showError("没有可打开此文件的应用",missing);}
    }
    private boolean editableText(Entry entry) {
        String name = entry.name.toLowerCase(Locale.ROOT);
        return !entry.directory() && (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown"));
    }
    private void editText(Entry entry) {
        startActivity(new Intent(this, LocalTextEditorActivity.class).setData(entry.uri).putExtra("title", entry.name));
    }
    private void fileMenu(Entry entry){
        if (editableText(entry)) {
            dialog = new AlertDialog.Builder(this).setTitle(entry.name)
                    .setItems(new String[]{"在青简中编辑", "用其他应用打开", "重命名", "删除原文件"}, (d, index) -> {
                        if (index == 0) editText(entry);
                        else if (index == 1) systemOpen(entry);
                        else if (index == 2) rename(entry);
                        else confirmDelete(entry);
                    }).show();
            return;
        }
        String[] choices=entry.directory()?new String[]{"打开文件夹","重命名"}:new String[]{"打开","重命名","删除原文件"};
        dialog=new AlertDialog.Builder(this).setTitle(entry.name).setItems(choices,(d,index)->{
            if(index==0)open(entry);else if(index==1)rename(entry);else confirmDelete(entry);
        }).show();
    }
    private static String validName(String value){String name=value.trim();if(name.isEmpty()||name.length()>200||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\")||name.indexOf('\0')>=0)return null;return name;}
    private interface Mutation {void apply()throws Exception;}
    private void mutate(String name,Mutation operation){
        if(busy)return;busy=true;status.setText(name);updatePath();
        worker.execute(()->{Exception failure=null;try{operation.apply();}catch(Exception error){failure=error;}final Exception outcome=failure;
            main.post(()->{if(closed)return;busy=false;if(outcome!=null)showError("操作失败；请检查写入授权",outcome);refresh();});});
    }
    private void rename(Entry entry){
        if((entry.flags&DocumentsContract.Document.FLAG_SUPPORTS_RENAME)==0){Toast.makeText(this,"此文件提供器不允许重命名",Toast.LENGTH_LONG).show();return;}
        EditText name=new EditText(this);name.setSingleLine(true);name.setText(entry.name);
        dialog=new AlertDialog.Builder(this).setTitle("重命名原文件").setMessage("将改变手机原文件名称；已有书架副本不会自动改名。").setView(name).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
            String value=validName(name.getText().toString());if(value==null){Toast.makeText(this,"名称无效",Toast.LENGTH_LONG).show();return;}
            mutate("正在重命名…",()->{if(DocumentsContract.renameDocument(getContentResolver(),entry.uri,value)==null)throw new java.io.IOException("重命名未完成");});
        }).show();
    }
    private void createDirectory(){
        if(tree==null||directoryId==null||busy){Toast.makeText(this,"请先选择目录",Toast.LENGTH_SHORT).show();return;}
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("文件夹名称");
        final Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,directoryId);
        dialog=new AlertDialog.Builder(this).setTitle("新建本地文件夹").setView(name).setNegativeButton("取消",null).setPositiveButton("创建",(d,w)->{
            String value=validName(name.getText().toString());if(value==null){Toast.makeText(this,"名称无效",Toast.LENGTH_LONG).show();return;}
            mutate("正在创建…",()->{if(DocumentsContract.createDocument(getContentResolver(),parent,DocumentsContract.Document.MIME_TYPE_DIR,value)==null)throw new java.io.IOException("创建未完成");});
        }).show();
    }
    private void confirmDelete(Entry entry){
        if(entry.directory())return;
        if((entry.flags&DocumentsContract.Document.FLAG_SUPPORTS_DELETE)==0){Toast.makeText(this,"此文件提供器不允许删除",Toast.LENGTH_LONG).show();return;}
        dialog=new AlertDialog.Builder(this).setTitle("删除手机原文件？").setMessage(entry.name+"\n"+DocumentsContract.getDocumentId(entry.uri)+"\n\n此操作不可撤销，不会删除已有书架副本；不会递归删除目录。")
            .setNegativeButton("取消",null).setPositiveButton("删除原文件",(d,w)->{stopAudio();mutate("正在删除…",()->{if(!DocumentsContract.deleteDocument(getContentResolver(),entry.uri))throw new java.io.IOException("删除未完成");});}).show();
    }

    private void startAudio(Uri uri,String title){
        stopAudio();final int generation=++audioGeneration;audioTitle.setText("准备播放："+title);
        // ExoPlayer 须在主线程 Looper 上构建；generation + isDestroyed 双检沿用 0.13.0 纪律。
        main.post(()->{
            if(closed||isDestroyed()||generation!=audioGeneration)return;
            OpenLeafPlayer candidate=new OpenLeafPlayer(LocalFilesActivity.this);
            candidate.setListener(new OpenLeafPlayer.Listener(){
                public void onPrepared(){if(closed||isDestroyed()||player!=candidate)return;prepared=true;seek.setEnabled(true);play.setEnabled(true);audioTitle.setText(title);if(foreground){candidate.play();play.setText("暂停");main.post(tick);}else play.setText("播放");}
                public void onCompletion(){if(player==candidate){play.setText("播放");main.removeCallbacks(tick);updateTime();}}
                public void onError(String reason){if(player==candidate)audioError();}
                public void onTracksChanged(){}
            });
            player=candidate;prepared=false;
            candidate.setMediaItem(uri,null);
        });
    }
    private final Runnable tick=new Runnable(){public void run(){if(closed||player==null||!prepared)return;updateTime();main.postDelayed(this,750);}};
    private void updateTime(){if(player==null||!prepared)return;try{int duration=(int)player.duration(),position=(int)player.position();if(!seeking)seek.setProgress(duration>0?(int)(position*1000L/duration):0);audioTime.setText(time(position)+" / "+time(duration));}catch(IllegalStateException error){audioError();}}
    private static String time(int ms){int seconds=Math.max(0,ms/1000);return String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);}
    private void togglePlayback(){if(player==null||!prepared)return;try{if(player.isPlaying()){player.pause();play.setText("播放");main.removeCallbacks(tick);}else{player.play();play.setText("暂停");main.post(tick);}}catch(IllegalStateException error){audioError();}}
    private void audioError(){stopAudio();audioTitle.setText("音频格式或文件不可播放，请重新选择。");}
    private void stopAudio(){audioGeneration++;main.removeCallbacks(tick);prepared=false;if(player!=null){player.release();player=null;}if(play!=null){play.setText("播放");play.setEnabled(false);}if(seek!=null){seek.setEnabled(false);seek.setProgress(0);}if(audioTime!=null)audioTime.setText("00:00 / 00:00");}
    @Override protected void onStart(){super.onStart();foreground=true;}
    @Override protected void onStop(){foreground=false;if(player!=null&&prepared)try{if(player.isPlaying()){player.pause();play.setText("播放");}}catch(IllegalStateException ignored){}main.removeCallbacks(tick);super.onStop();}
    @Override public void onBackPressed(){if(busy){Toast.makeText(this,"操作中，请等待完成",Toast.LENGTH_SHORT).show();return;}if(!parentIds.isEmpty())parent();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle state){if(tree!=null)state.putString("tree",tree.toString());state.putString("directoryId",directoryId);state.putString("directoryName",directoryName);state.putStringArrayList("parents",new ArrayList<>(parentIds));state.putStringArrayList("parentNames",new ArrayList<>(parentNames));super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){closed=true;requestGeneration++;if(query!=null)query.cancel();stopAudio();if(dialog!=null)dialog.dismiss();worker.shutdownNow();super.onDestroy();}
    private void showError(String title,Exception error){if(!closed&&!isFinishing())dialog=new AlertDialog.Builder(this).setTitle(title).setMessage(message(error)).setPositiveButton("知道了",null).show();}
    private static String message(Exception error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    private final class FileAdapter extends BaseAdapter{
        public int getCount(){return files.size();}public Entry getItem(int at){return files.get(at);}public long getItemId(int at){return at;}
        public View getView(int at,View recycled,ViewGroup parent){TextView label=recycled instanceof TextView?(TextView)recycled:Ui.text(LocalFilesActivity.this,"",15,Ui.INK);Entry entry=getItem(at);label.setMaxLines(3);Ui.pad(label,12);label.setText((entry.directory()?"▣ ":entry.audio()?"♪ ":"▤ ")+entry.name+"\n"+(entry.directory()?"文件夹":entry.size<0?"大小未知":(entry.size/1024)+" KB")+"  ·  长按管理");return label;}
    }
}
