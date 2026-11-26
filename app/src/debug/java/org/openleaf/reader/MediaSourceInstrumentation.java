package org.openleaf.reader;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.CancellationSignal;
import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic adapter round trips. No external media sites or personal data. */
public final class MediaSourceInstrumentation extends Instrumentation {
    private int passed;
    private final StringBuilder report=new StringBuilder();
    interface Check {void run()throws Exception;}
    private void check(String name,Check test)throws Exception{test.run();passed++;report.append("PASS ").append(name).append('\n');}
    private static void require(boolean condition)throws Exception{if(!condition)throw new Exception("assertion failed");}
    private static void rejects(Check test)throws Exception{try{test.run();}catch(Exception expected){return;}throw new Exception("expected rejection");}
    static JSONObject source(String kind)throws Exception{return new JSONObject().put("id",kind+"-fixture").put("name","Fixture "+kind).put("kind",kind).put("baseUrl","https://adapter.example/api/").put("search","search?q={query}&page={page}").put("detail","detail?id={id}").put("pages","pages?id={id}").put("stream","stream?id={id}");}
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        MediaSourceClient.Source comic=new MediaSourceClient.Source(source("comics"));MediaSourceClient.Source music=new MediaSourceClient.Source(source("music"));
        ArrayList<String> requests=new ArrayList<>();
        MediaSourceClient fake=new MediaSourceClient((url,limit,signal)->{if(signal!=null)signal.throwIfCanceled();requests.add(url);String json;if(url.contains("/search?")){if(url.endsWith("page=2"))json="{\"items\":[],\"hasMore\":false}";else json="{\"items\":[{\"id\":\"comic/1\",\"title\":\"合成漫画\",\"author\":\"Fixture\"}],\"hasMore\":true}";}else if(url.contains("/detail?"))json="{\"title\":\"合成漫画\",\"description\":\"原创测试\",\"chapters\":[{\"id\":\"comic/1/chapter/2\",\"title\":\"第二章\"}]}";else if(url.contains("/pages?"))json="{\"pages\":[\"../images/page-1.png\",{\"url\":\"https://cdn.example/page-2.png\"}]}";else if(url.contains("/stream?"))json="{\"url\":\"https://media.example/test.wav\"}";else throw new java.io.IOException("Unexpected fixture route");return json.getBytes(StandardCharsets.UTF_8);});
        check("music/comic source pack roundtrip",()->{List<MediaSourceClient.Source> restored=MediaSourceClient.parsePack(MediaSourceClient.pack(Arrays.asList(comic,music)));require(restored.size()==2&&restored.get(1).kind.equals("music")&&restored.get(0).pages.equals(comic.pages));});
        check("search request encodes query and IDs without injection",()->{List<MediaSourceClient.Item> items=fake.search(comic,"中文 &?/#",1,new CancellationSignal());require(items.size()==1&&items.get(0).id.equals("comic/1"));require(requests.get(requests.size()-1).contains("q=%E4%B8%AD%E6%96%87%20%26%3F%2F%23&page=1"));});
        check("pagination changes HTTP request and reads empty last page",()->{require(fake.search(comic,"test",2,null).isEmpty());require(requests.get(requests.size()-1).endsWith("page=2"));});
        check("comic search to detail to chapter pages pipeline",()->{MediaSourceClient.Item item=fake.search(comic,"test",1,null).get(0);MediaSourceClient.Detail detail=fake.detail(comic,item.id,null);require(detail.chapters.size()==1);List<String> urls=fake.pages(comic,detail.chapters.get(0).id,null);require(urls.get(0).equals("https://adapter.example/images/page-1.png")&&urls.get(1).equals("https://cdn.example/page-2.png"));require(requests.get(requests.size()-1).endsWith("id=comic%2F1%2Fchapter%2F2"));});
        check("music search to stream resolution pipeline",()->{MediaSourceClient.Item item=fake.search(music,"test",1,null).get(0);require(fake.stream(music,item.id,null).equals("https://media.example/test.wav"));});
        check("cancelled request never reaches fixture service",()->{CancellationSignal cancel=new CancellationSignal();cancel.cancel();int before=requests.size();rejects(()->fake.search(comic,"test",1,cancel));require(before==requests.size());});
        check("transport errors propagate for UI retry",()->{MediaSourceClient failed=new MediaSourceClient((u,l,s)->{throw new java.io.IOException("offline");});rejects(()->failed.search(music,"x",1,null));});
        check("oversized JSON rejected even with custom transport",()->{MediaSourceClient huge=new MediaSourceClient((u,l,s)->new byte[MediaSourceClient.MAX_JSON+1]);rejects(()->huge.search(comic,"x",1,null));});
        check("API templates cannot escape configured origin",()->{rejects(()->new MediaSourceClient.Source(source("music").put("search","https://other.example/search?q={query}")));rejects(()->new MediaSourceClient.Source(source("music").put("stream","http://adapter.example/play")));});
        check("HTTP and script image URLs rejected",()->{rejects(()->MediaSourceClient.pageUrls(new JSONObject("{\"pages\":[\"http://unsafe.example/p.png\"]}"),comic.base));rejects(()->MediaSourceClient.pageUrls(new JSONObject("{\"pages\":[\"javascript:alert(1)\"]}"),comic.base));});
        check("invalid media URL rejected after stream resolution",()->{MediaSourceClient invalid=new MediaSourceClient((u,l,s)->"{\"url\":\"file:///sdcard/a.mp3\"}".getBytes(StandardCharsets.UTF_8));rejects(()->invalid.stream(music,"1",null));});
        check("unsupported scripts and duplicate IDs rejected before import",()->{rejects(()->MediaSourceClient.parsePack("class Source {}"));JSONObject pack=new JSONObject(MediaSourceClient.pack(Arrays.asList(music,music)));rejects(()->MediaSourceClient.parsePack(pack.toString()));});
        check("result and chapter limits enforced",()->{JSONArray many=new JSONArray();for(int i=0;i<501;i++)many.put(new JSONObject().put("id",""+i).put("title","x"));rejects(()->MediaSourceClient.items(new JSONObject().put("items",many)));rejects(()->MediaSourceClient.pageUrls(new JSONObject("{\"pages\":[]}"),comic.base));});
        check("player local PCM prepare/pause/seek/release",()->{java.io.File audio=new java.io.File(getTargetContext().getCacheDir(),"media-source-fixture.wav");try{writeWave(audio);android.media.MediaPlayer player=new android.media.MediaPlayer();try{player.setDataSource(audio.getAbsolutePath());player.prepare();require(player.getDuration()>0);player.start();player.pause();player.seekTo(20);player.stop();}finally{player.release();}}finally{audio.delete();}});
        result.putString("stream","\nPASS: "+passed+" media source checks\n"+report);finish(-1,result);
    }catch(Throwable error){result.putString("stream","\nFAIL after "+passed+" checks\n"+report+android.util.Log.getStackTraceString(error));finish(0,result);}}
    private static void writeWave(java.io.File target)throws Exception{int samples=8000;java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(44+samples*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+samples*2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples*2);for(int i=0;i<samples;i++)b.putShort((short)0);try(java.io.FileOutputStream out=new java.io.FileOutputStream(target)){out.write(b.array());}}
}
