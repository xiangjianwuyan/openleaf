package org.openleaf.reader;

import org.openleaf.reader.application.ServiceRegistry;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;
import android.view.ViewGroup;
import org.json.JSONArray;
import org.json.JSONObject;
import androidx.media3.common.util.UnstableApi;
import org.openleaf.reader.core.ContentContracts;
import org.openleaf.reader.core.DownloadPolicy;
import org.openleaf.reader.core.DownloadUseCase;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-supplied OPDS sources and declarative source packs. Never executes plugins or page scripts. */
@UnstableApi
public final class CatalogActivity extends Activity {
    private static final int IMPORT_PACK=801,EXPORT_PACK=802,MAX_PACK=256*1024,MAX_SOURCES=100;
    // 0.30.0 #9：单次批量上限可配；默认 20 与 0.29.0 逐字一致，"不限"用 200 兜底。
    private static final String KEY_BATCH_LIMIT="opds_batch_limit";
    private static final int DEFAULT_BATCH_LIMIT=20;
    private static final int UNLIMITED_BATCH_LIMIT=200;
    private static final int[] BATCH_LIMIT_OPTIONS={10,20,50,UNLIMITED_BATCH_LIMIT};
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<Source> sources=new ArrayList<>();
    private final ArrayList<CatalogClient.Entry> entries=new ArrayList<>();
    private final ArrayList<String> history=new ArrayList<>();
    private TextView status,heading;
    private Button batchButton,batchLimitButton;
    private volatile boolean batchRunning;
    private CancellationSignal batchSignal;
    private final java.util.LinkedHashSet<Integer> checked = new java.util.LinkedHashSet<>();
    private ListView list;
    private EntryAdapter adapter;
    private CancellationSignal signal;
    private int generation;
    private boolean loading;
    private String currentUrl;
    private AlertDialog dialog;
    private static final class Source{
        final String name,url,kind;
        Source(String name,String url,String kind){this.name=name;this.url=url;this.kind=kind;}
        JSONObject json()throws Exception{JSONObject value=new JSONObject();value.put("name",name);value.put("url",url);value.put("kind",kind);return value;}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout root=Ui.column(this);Ui.mount(this,root);
        LinearLayout top=Ui.row(this);top.addView(Ui.button(this,"返回",this::onBackPressed,"返回上一页"));top.addView(Ui.title(this,"书源与漫画目录",20),Ui.weight());root.addView(top);
        root.addView(Ui.button(this,"音乐源 / 漫画源接口",()->startActivity(new Intent(this,MediaSourceActivity.class))));
        TextView explanation=Ui.text(this,"添加你有权访问的 HTTPS OPDS 1/2 目录。源插件仅包含名称、类型和网址，不运行代码。",13,Ui.MUTED);Ui.pad(explanation,12);root.addView(explanation);
        LinearLayout tools=Ui.row(this);tools.addView(Ui.button(this,"添加源",this::addSource,"添加书源"),Ui.weight());tools.addView(Ui.button(this,"导入源包",this::pickPack,"导入书源包"),Ui.weight());tools.addView(Ui.button(this,"导出源包",this::exportPack,"导出书源包"),Ui.weight());root.addView(tools);
        batchLimitButton=Ui.button(this,"批量下载上限："+batchLimitLabel(),this::openBatchLimit,"批量下载上限");
        root.addView(batchLimitButton,new LinearLayout.LayoutParams(-1,-2));
        heading=Ui.title(this,"已添加目录",17);Ui.pad(heading,10);root.addView(heading);status=Ui.text(this,"",13,Ui.MUTED);Ui.pad(status,10);root.addView(status);
        list=new ListView(this);adapter=new EntryAdapter();list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((parent,v,index,id)->{if(currentUrl==null)open(sources.get(index).url,true);else chooseLink(entries.get(index));});
        list.setOnItemLongClickListener((parent,v,index,id)->{if(currentUrl!=null)return false;Source source=sources.get(index);dialog=new AlertDialog.Builder(this).setTitle("移除目录？").setMessage(source.name+"\n"+source.url+"\n不会删除已导入的书籍。").setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->{sources.remove(index);save();showSources();}).show();return true;});
        LinearLayout bottom=Ui.row(this);bottom.addView(Ui.button(this,"目录列表",this::showSources),Ui.weight());bottom.addView(Ui.button(this,"停止加载",()->{cancel();status.setText("加载已取消");}),Ui.weight());root.addView(bottom);
        LinearLayout batchRow=Ui.row(this);
        batchButton=Ui.button(this,"请先勾选条目",this::onBatchButton);batchButton.setEnabled(false);
        batchRow.addView(batchButton,Ui.weight());root.addView(batchRow);
        try{JSONArray stored=new JSONArray(getSharedPreferences("catalog",0).getString("sources","[]"));for(int i=0;i<stored.length();i++)sources.add(decode(stored.getJSONObject(i)));}
        catch(Exception error){status.setText("源配置读取失败，请重新添加："+message(error));}
        showSources();
        if(state!=null){ArrayList<String> saved=state.getStringArrayList("history");if(saved!=null)history.addAll(saved);String url=state.getString("url");if(url!=null)open(url,false);}
    }
    private static Source decode(JSONObject value)throws Exception{
        java.util.Iterator<String> keys=value.keys();while(keys.hasNext()){String key=keys.next();if(!key.equals("name")&&!key.equals("url")&&!key.equals("kind"))throw new IllegalArgumentException("源包只允许 name / url / kind 字段，不支持脚本");}
        String name=value.getString("name").trim(),url=CatalogClient.checkedUrl(value.getString("url")),kind=value.optString("kind","books");
        if(name.isEmpty()||name.length()>100||url.length()>4096)throw new IllegalArgumentException("目录名称或地址过长 / 为空");
        if(!kind.equals("books")&&!kind.equals("comics"))throw new IllegalArgumentException("目录类型只能是 books 或 comics");
        return new Source(name,url,kind);
    }
    private void addSource(){
        if(loading){toast("请先停止加载");return;}
        LinearLayout form=Ui.column(this);Ui.pad(form,16);EditText name=new EditText(this);name.setHint("目录名称");name.setSingleLine();form.addView(name);EditText url=new EditText(this);url.setHint("https://example.org/opds");url.setSingleLine();form.addView(url);
        Spinner kind=new Spinner(this);kind.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"电子书目录","漫画目录"}));form.addView(kind);
        dialog=new AlertDialog.Builder(this).setTitle("添加 OPDS 源").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{JSONObject value=new JSONObject();value.put("name",name.getText().toString());value.put("url",url.getText().toString());value.put("kind",kind.getSelectedItemPosition()==1?"comics":"books");Source source=decode(value);if(sources.size()>=MAX_SOURCES)throw new IllegalArgumentException("最多保存100个源");for(Source existing:sources)if(existing.url.equals(source.url)&&existing.kind.equals(source.kind))throw new IllegalArgumentException("这个目录已存在");sources.add(source);save();dialog.dismiss();showSources();}catch(Exception error){url.setError(message(error));}}));dialog.show();
    }
    private void save(){try{JSONArray values=new JSONArray();for(Source source:sources)values.put(source.json());getSharedPreferences("catalog",0).edit().putString("sources",values.toString()).apply();}catch(Exception error){toast("保存失败："+message(error));}}
    private void showSources(){cancel();clearChecked();history.clear();currentUrl=null;entries.clear();heading.setText("已添加目录 · "+sources.size());status.setText(sources.isEmpty()?"还没有目录。请添加 OPDS 地址，或导入声明式 JSON 源包。":"点击浏览目录；长按移除。下载完成后自动入库书库；不执行网站规则。");adapter.notifyDataSetChanged();}
    private void cancel(){generation++;loading=false;if(signal!=null)signal.cancel();signal=null;}
    private void open(String address,boolean remember){
        cancel();clearChecked();if(remember&&currentUrl!=null)history.add(currentUrl);currentUrl=address;entries.clear();adapter.notifyDataSetChanged();heading.setText("正在加载目录");status.setText(address);
        final int request=++generation;final CancellationSignal active=new CancellationSignal();signal=active;loading=true;
        worker.execute(()->{try{CatalogClient.Feed feed=CatalogClient.load(address,active);runOnUiThread(()->{if(isDestroyed()||request!=generation)return;loading=false;entries.addAll(feed.entries);heading.setText(feed.title);status.setText("显示 "+entries.size()+" 项（每页目录最多500项）\n"+feed.url);adapter.notifyDataSetChanged();});}
            catch(OperationCanceledException ignored){}
            catch(Exception error){runOnUiThread(()->{if(isDestroyed()||request!=generation)return;loading=false;heading.setText("目录加载失败");status.setText(message(error)+"\n请确认地址是 OPDS 目录，而非普通网页或脚本书源。");});}});
    }
    private void chooseLink(CatalogClient.Entry entry){
        ArrayList<CatalogClient.Link> supported=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        for(CatalogClient.Link link:entry.links){if(CatalogClient.catalog(link)){supported.add(link);labels.add("浏览子目录"+(link.title.isEmpty()?"":" · "+link.title));}else if(CatalogClient.acquisition(link)){supported.add(link);String extension=CatalogClient.extension(link);labels.add("下载到本地缓存（"+(extension==null?"文件":extension.toUpperCase(java.util.Locale.ROOT))+"）");}}
        if(supported.isEmpty()){toast("此项没有支持的 OPDS 或电子书 / 漫画获取链接");return;}
        dialog=new AlertDialog.Builder(this).setTitle(entry.title).setItems(labels.toArray(new String[0]),(d,index)->{
            CatalogClient.Link link=supported.get(index);if(CatalogClient.catalog(link)){open(link.url,true);return;}
            confirmDownload(link.url,entry.title,CatalogClient.extension(link));
        }).setNegativeButton("取消",null).show();
    }
    /** 下载接入任务模型：临时文件 + 校验后提交，逐项检查点，进程中断可续跑。 */
    private void confirmDownload(final String url, String title, final String extension){
        final String safeName=DownloadPolicy.safeFileName(title,60)+(extension==null||extension.isEmpty()?"":"."+extension);
        dialog=new AlertDialog.Builder(this).setTitle("下载到本地？")
                .setMessage(url+"\n\n仅下载你有权限保存的内容；青简不绕过登录、付费或 DRM。下载完成后自动加入书库。")
                .setNegativeButton("取消",null)
                .setPositiveButton("开始下载",(d,w)->startDownload(url,safeName)).show();
    }

    private void startDownload(final String url, final String fileName){
        final String jobId="download-"+System.currentTimeMillis();
        final ContentContracts.JobRepository jobs=ServiceRegistry.of(this).jobs();
        java.util.List<DownloadUseCase.Request> requests=new java.util.ArrayList<>();
        requests.add(new DownloadUseCase.Request(url,fileName,"",DownloadPolicy.UNKNOWN_LENGTH));
        jobs.create(jobId,"download","opds",DownloadUseCase.toJobItems(requests));
        jobs.updateState(jobId,ContentContracts.JobState.RUNNING,0,0,0,0);
        status.setText("正在下载 "+fileName+" …");
        final java.io.File downloadDir = new java.io.File(getFilesDir(), "downloads");
        final java.util.Set<String> before = new java.util.HashSet<>();
        java.io.File[] beforeFiles = downloadDir.listFiles();
        if (beforeFiles != null) for (java.io.File f : beforeFiles) before.add(f.getName());

        worker.execute(()->{
            final DownloadUseCase.Summary summary=DownloadUseCase.run(requests,jobs,jobId,new AssetDownloader(this),null);
            // 只导入本次新增的文件：对比下载前后的文件集合，避免误导入历史下载
            int imported = 0, duplicated = 0;
            String importError = null;
            if (summary.succeeded > 0) {
                try {
                    java.io.File[] files = downloadDir.listFiles();
                    if (files != null) {
                        BookStore store = new BookStore(this);
                        for (java.io.File file : files) {
                            if (!file.isFile() || before.contains(file.getName())
                                    || file.getName().endsWith(".part")) continue;
                            BookStore.ImportResult result = store.importUri(Uri.fromFile(file), new CancellationSignal());
                            if (result.duplicate) duplicated++; else imported++;
                            if (file.delete()) { /* 已入库，删除 downloads 暂存，避免重复堆积 */ }
                        }
                    }
                } catch (Exception error) { importError = shortError(error); }
            }
            final int added = imported, dups = duplicated;
            final String failure = importError;
            runOnUiThread(()->{
                StringBuilder message = new StringBuilder("下载完成：成功 ").append(summary.succeeded)
                        .append(" · 失败 ").append(summary.failed);
                if (added > 0 || dups > 0) message.append("\n已入库 ").append(added).append(" 本，重复 ").append(dups).append(" 本");
                // 文件确实已经下载到应用内私有目录，只是入库失败（例如格式不被识别）；
                // 原「可从「本地文件 → downloads」手动导入」是双重假话（没有导入入口，且该目录用户够不着）。
                else if (failure != null) message.append("\n自动入库失败：").append(failure)
                        .append("\n文件已下载，但暂时无法加入书库（该文件保留在应用内，暂不提供手动导入）");
                else message.append("\n文件已保存到 downloads 目录");
                status.setText(message.toString());
                toast(summary.failed==0?"下载并入库完成":"下载失败，请查看任务记录");
            });
        });
    }
    // ---------- OPDS 勾选批量下载（缺口 4 / Q5）----------

    private void clearChecked(){checked.clear();updateBatchButton();}

    private void updateBatchButton(){
        if(batchButton==null)return;
        if(batchRunning){batchButton.setText("停止批量下载");batchButton.setEnabled(true);return;}
        int n=checked.size();
        batchButton.setText(n==0?"请先勾选条目":"批量下载（"+n+"）");
        batchButton.setEnabled(n>0);
    }

    private void onBatchButton(){
        if(batchRunning){if(batchSignal!=null)batchSignal.cancel();status.setText("正在停止批量下载，已完成项保留");return;}
        confirmBatchDownload();
    }

    /** 读取批量上限：非法值一律回落 20，保证默认行为与 0.29.0 逐字一致。 */
    private int batchLimit(){
        int value=getSharedPreferences("reader",0).getInt(KEY_BATCH_LIMIT,DEFAULT_BATCH_LIMIT);
        for(int allowed:BATCH_LIMIT_OPTIONS)if(allowed==value)return value;
        return DEFAULT_BATCH_LIMIT;
    }

    private String batchLimitLabel(){
        int value=batchLimit();
        return value==UNLIMITED_BATCH_LIMIT?"不限（最多 "+UNLIMITED_BATCH_LIMIT+"）":value+" 项";
    }

    private void openBatchLimit(){
        int current=batchLimit();
        int checkedIndex=1;
        for(int i=0;i<BATCH_LIMIT_OPTIONS.length;i++)if(BATCH_LIMIT_OPTIONS[i]==current)checkedIndex=i;
        String[] labels={"10 项","20 项（默认）","50 项","不限（最多 "+UNLIMITED_BATCH_LIMIT+" 项）"};
        new AlertDialog.Builder(this).setTitle("单次批量下载上限")
                .setSingleChoiceItems(labels,checkedIndex,(dialog,which)->{
                    getSharedPreferences("reader",0).edit()
                            .putInt(KEY_BATCH_LIMIT,BATCH_LIMIT_OPTIONS[which]).apply();
                    if(batchLimitButton!=null)batchLimitButton.setText("批量下载上限："+batchLimitLabel());
                    dialog.dismiss();
                }).setNegativeButton("取消",null).show();
    }

    private void confirmBatchDownload(){
        if(checked.isEmpty()){toast("请先勾选条目");return;}
        int n=Math.min(checked.size(),batchLimit());
        dialog=new AlertDialog.Builder(this).setTitle("批量下载")
                .setMessage("将下载 "+n+" 项（已有同名文件会跳过）。仅下载你有权限保存的内容；不绕过登录、付费或 DRM。")
                .setNegativeButton("取消",null)
                .setPositiveButton("开始下载",(d,w)->startBatchDownload()).show();
    }

    private static CatalogClient.Link firstAcquisition(CatalogClient.Entry entry){
        for(CatalogClient.Link link:entry.links)if(CatalogClient.acquisition(link))return link;
        return null;
    }

    /** 复用既有 DownloadUseCase.run(List<Request>) + JobRepository 检查点，不新写编排类。 */
    private void startBatchDownload(){
        final String jobId="download-"+System.currentTimeMillis();
        final ContentContracts.JobRepository jobs=ServiceRegistry.of(this).jobs();
        final List<DownloadUseCase.Request> requests=new ArrayList<>();
        final List<String> skipped=new ArrayList<>();
        final java.util.Set<String> fileNames=new java.util.LinkedHashSet<>();
        int limit=Math.min(checked.size(),batchLimit()), used=0;
        for(Integer index:checked){
            if(used++>=limit)break;
            if(index==null||index<0||index>=entries.size())continue;
            CatalogClient.Entry entry=entries.get(index);
            CatalogClient.Link link=firstAcquisition(entry);
            if(link==null){skipped.add(entry.title+"：无获取链接");continue;}
            String extension=CatalogClient.extension(link);
            String fileName=DownloadPolicy.safeFileName(entry.title,60)
                    +(extension==null||extension.isEmpty()?"":"."+extension);
            if(!fileNames.add(fileName)){skipped.add(entry.title+"：同名文件跳过");continue;}
            requests.add(new DownloadUseCase.Request(link.url,fileName,"",DownloadPolicy.UNKNOWN_LENGTH));
        }
        if(requests.isEmpty()){toast("没有可下载的条目");return;}
        jobs.create(jobId,"download","opds",DownloadUseCase.toJobItems(requests));
        jobs.updateState(jobId,ContentContracts.JobState.RUNNING,0,0,0,0);
        batchRunning=true;batchSignal=new CancellationSignal();updateBatchButton();
        final CancellationSignal active=batchSignal;
        final int total=requests.size();
        status.setText("正在下载 0 / "+total+" …");
        final java.io.File downloadDir=new java.io.File(getFilesDir(),"downloads");
        final java.util.Set<String> before=new java.util.HashSet<>();
        java.io.File[] beforeFiles=downloadDir.listFiles();
        if(beforeFiles!=null)for(java.io.File f:beforeFiles)before.add(f.getName());
        worker.execute(()->{
            final DownloadUseCase.Summary summary=DownloadUseCase.run(requests,jobs,jobId,new AssetDownloader(this),
                    new DownloadUseCase.Progress(){
                public void onItemDone(int processed,int total,DownloadUseCase.Result result){
                    runOnUiThread(()->{if(isDestroyed()||isFinishing())return;status.setText("正在下载 "+processed+" / "+total+" …");});
                }
                public boolean cancelled(){return active.isCanceled();}
            });
            // 一次性扫 downloads 自动入库，避免逐项全目录扫描
            int imported=0,duplicated=0;String importError=null;
            if(summary.succeeded>0){
                try{
                    java.io.File[] files=downloadDir.listFiles();
                    if(files!=null){BookStore store=new BookStore(CatalogActivity.this);
                        for(java.io.File file:files){
                            if(!file.isFile()||before.contains(file.getName())||file.getName().endsWith(".part"))continue;
                            BookStore.ImportResult result=store.importUri(Uri.fromFile(file),new CancellationSignal());
                            if(result.duplicate)duplicated++;else imported++;
                            if(file.delete()){ /* 已入库，删除 downloads 暂存 */ }
                        }}
                }catch(Exception error){importError=shortError(error);} // §13.4：不把原始 getMessage() 给用户
            }
            final int added=imported,dups=duplicated;final String failure=importError;
            final List<String> details=new ArrayList<>(skipped);
            if(failure!=null)details.add("自动入库失败："+failure+"；文件已下载但暂时无法加入书库");
            runOnUiThread(()->{
                if(isDestroyed()||isFinishing())return; // 旋转/退出后不用旧 Activity 弹 Dialog
                batchRunning=false;batchSignal=null;updateBatchButton();
                StringBuilder message=new StringBuilder();
                if(summary.paused)message.append("已暂停：完成 ").append(summary.succeeded)
                        .append(" 项，已完成项保留，可在任务中心查看失败明细");
                else message.append("下载完成：成功 ").append(summary.succeeded)
                        .append(" · 失败 ").append(summary.failed).append(" · 跳过 ").append(skipped.size());
                if(added>0||dups>0)message.append("\n已入库 ").append(added).append(" 本，重复 ").append(dups).append(" 本");
                else if(summary.succeeded>0&&failure==null)message.append("\n文件已保存到 downloads 目录");
                status.setText(message.toString());
                // §16：结果 Dialog + 可复制失败明细，与拖拽结果同款
                LinearLayout box=Ui.column(CatalogActivity.this);Ui.pad(box,20);
                box.addView(Ui.text(CatalogActivity.this,message.toString(),14,Ui.INK));
                if(!details.isEmpty()){
                    TextView detail=Ui.text(CatalogActivity.this,"失败明细：\n"+String.join("\n",details),12,Ui.MUTED);
                    detail.setTextIsSelectable(true);box.addView(detail);
                }
                dialog=new AlertDialog.Builder(CatalogActivity.this).setTitle("批量下载结果")
                        .setView(box).setPositiveButton("知道了",null).show();
            });
        });
    }

    /** §13.4：已知异常类型 → 稳定中文短串，绝不把原始 getMessage() 暴露给用户。 */
    private static String shortError(Exception error){
        if(error instanceof SecurityException)return "授权失效";
        if(error instanceof java.io.FileNotFoundException)return "文件不可用";
        if(error instanceof IllegalArgumentException||error instanceof UnsupportedOperationException)return "格式不支持";
        if(error instanceof java.io.IOException)return "读取失败";
        return "文件不可用";
    }

    private void pickPack(){try{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain"});startActivityForResult(intent,IMPORT_PACK);}catch(android.content.ActivityNotFoundException error){toast("系统文件选择器不可用");}}
    private void exportPack(){if(sources.isEmpty()){toast("没有可导出的源");return;}try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"openleaf-source-pack.json"),EXPORT_PACK);}catch(android.content.ActivityNotFoundException error){toast("系统保存入口不可用");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==EXPORT_PACK){try{JSONObject pack=new JSONObject();pack.put("schemaVersion",1);JSONArray values=new JSONArray();for(Source source:sources)values.put(source.json());pack.put("sources",values);try(OutputStream output=getContentResolver().openOutputStream(uri)){if(output==null)throw new java.io.IOException("无法打开目标文件");output.write(pack.toString(2).getBytes(StandardCharsets.UTF_8));}toast("源包已导出");}catch(Exception error){toast("导出失败："+message(error));}return;}
        if(request!=IMPORT_PACK)return;
        worker.execute(()->{try{byte[] content;try(InputStream input=getContentResolver().openInputStream(uri);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){if(input==null)throw new java.io.IOException("无法读取源包");byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>MAX_PACK)throw new java.io.IOException("源包超过256 KiB");bytes.write(buffer,0,n);}content=bytes.toByteArray();}
            JSONObject pack=new JSONObject(new String(content,StandardCharsets.UTF_8));java.util.Iterator<String> keys=pack.keys();while(keys.hasNext()){String key=keys.next();if(!key.equals("schemaVersion")&&!key.equals("sources"))throw new IllegalArgumentException("未知源包字段，不支持执行插件代码");}if(pack.getInt("schemaVersion")!=1)throw new IllegalArgumentException("不支持的源包版本");JSONArray values=pack.getJSONArray("sources");if(values.length()>MAX_SOURCES)throw new IllegalArgumentException("源包最多100个目录");ArrayList<Source> parsed=new ArrayList<>();for(int i=0;i<values.length();i++)parsed.add(decode(values.getJSONObject(i)));
            runOnUiThread(()->{if(isDestroyed())return;dialog=new AlertDialog.Builder(this).setTitle("导入 "+parsed.size()+" 个目录配置？").setMessage("源包仅保存 HTTPS 地址和分类，不运行脚本。仅在你打开目录后联网；重复地址跳过。").setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->{applyPack(parsed);}).show();});
        }catch(Exception error){runOnUiThread(()->{if(!isDestroyed())toast("源包无效："+message(error));});}});
    }
    private void applyPack(List<Source> parsed){
        ArrayList<Source> additions=new ArrayList<>();java.util.HashSet<String> known=new java.util.HashSet<>();
        for(Source source:sources)known.add(source.kind+"\n"+source.url);
        for(Source source:parsed)if(known.add(source.kind+"\n"+source.url))additions.add(source);
        if(sources.size()+additions.size()>MAX_SOURCES){toast("导入后将超过100个目录，请先移除部分目录；本次未修改源配置");return;}
        sources.addAll(additions);save();showSources();toast("已添加 "+additions.size()+" 个目录，重复地址已跳过");
    }
    private final class EntryAdapter extends BaseAdapter{
        public int getCount(){return currentUrl==null?sources.size():entries.size();}
        public Object getItem(int index){return currentUrl==null?sources.get(index):entries.get(index);}
        public long getItemId(int index){return index;}
        public View getView(int index,View recycled,ViewGroup parent){LinearLayout row=recycled instanceof LinearLayout?(LinearLayout)recycled:Ui.column(CatalogActivity.this);row.removeAllViews();Ui.pad(row,12);row.setOrientation(LinearLayout.VERTICAL);if(currentUrl==null){Source source=sources.get(index);row.addView(Ui.title(CatalogActivity.this,source.name+" · "+(source.kind.equals("comics")?"漫画":"图书"),17));row.addView(Ui.text(CatalogActivity.this,source.url,12,Ui.MUTED));}else{CatalogClient.Entry entry=entries.get(index);CheckBox check=new CheckBox(CatalogActivity.this);check.setTag("check");check.setText("勾选下载");check.setChecked(checked.contains(index));check.setOnCheckedChangeListener((v,on)->{if(on)checked.add(index);else checked.remove(index);updateBatchButton();});row.addView(check);row.addView(Ui.title(CatalogActivity.this,entry.title.isEmpty()?"未命名":entry.title,17));row.addView(Ui.text(CatalogActivity.this,entry.author.isEmpty()?"点击选择浏览或获取链接":entry.author,13,Ui.MUTED));}return row;}
    }
    private static String message(Exception error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(currentUrl!=null){if(!history.isEmpty())open(history.remove(history.size()-1),false);else showSources();}else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("url",currentUrl);out.putStringArrayList("history",new ArrayList<>(history));super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){cancel();if(batchSignal!=null)batchSignal.cancel();if(dialog!=null)dialog.dismiss();worker.shutdownNow();super.onDestroy();}
}
