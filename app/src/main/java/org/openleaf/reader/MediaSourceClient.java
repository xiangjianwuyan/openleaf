package org.openleaf.reader;

import android.os.CancellationSignal;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Data-only source adapter. All remotely supplied URLs pass the same HTTPS policy. */
final class MediaSourceClient {
    static final int MAX_JSON=2*1024*1024, MAX_ITEMS=500, MAX_PAGES=2000;
    static final class Source {
        final String id,name,kind,base,search,detail,pages,stream;
        Source(JSONObject json)throws Exception {
            id=required(json,"id",100);name=required(json,"name",100);kind=required(json,"kind",20);
            if(!kind.equals("music")&&!kind.equals("comics"))throw new IOException("kind 必须为 music 或 comics");
            base=CatalogClient.checkedUrl(required(json,"baseUrl",4096));
            search=endpoint(json,"search",true);detail=endpoint(json,"detail",kind.equals("comics"));
            pages=endpoint(json,"pages",kind.equals("comics"));stream=endpoint(json,"stream",kind.equals("music"));
            for(String template:new String[]{search,detail,pages,stream})if(!template.isEmpty())url(template,"test","1",1);
        }
        String url(String template,String query,String id,int page)throws Exception {
            String path=template.replace("{query}",encode(query)).replace("{id}",encode(id)).replace("{page}",String.valueOf(page));
            String resolved=CatalogClient.checkedUrl(new URI(base).resolve(path).toString());
            URI origin=new URI(base),target=new URI(resolved);
            if(!origin.getHost().equalsIgnoreCase(target.getHost())||port(origin)!=port(target))throw new IOException("接口地址必须与 baseUrl 同源");
            return resolved;
        }
        JSONObject json()throws Exception{return new JSONObject().put("id",id).put("name",name).put("kind",kind).put("baseUrl",base).put("search",search).put("detail",detail).put("pages",pages).put("stream",stream);}
        private static String endpoint(JSONObject j,String key,boolean mandatory)throws Exception {String s=j.optString(key,"").trim();if(mandatory&&s.isEmpty())throw new IOException("缺少接口 "+key);if(s.length()>4096||s.replace("{query}","").replace("{id}","").replace("{page}","").contains("{"))throw new IOException("接口模板无效："+key);return s;}
    }
    static final class Item {
        final String id,title,subtitle;
        Item(JSONObject j)throws Exception{id=required(j,"id",1000);title=required(j,"title",500);subtitle=j.optString("artist",j.optString("author",""));}
    }
    static final class Chapter {
        final String id,title;
        Chapter(JSONObject j)throws Exception{id=required(j,"id",1000);title=required(j,"title",500);}
    }
    static final class Detail {
        final String title,description;final List<Chapter> chapters;
        Detail(JSONObject j)throws Exception {title=required(j,"title",500);description=j.optString("description","");JSONArray a=j.getJSONArray("chapters");if(a.length()>MAX_PAGES)throw new IOException("章节超过2000项");chapters=new ArrayList<>();for(int i=0;i<a.length();i++)chapters.add(new Chapter(a.getJSONObject(i)));}
    }
    interface Transport {byte[] get(String url,int limit,CancellationSignal signal)throws Exception;}
    private final Transport transport;
    MediaSourceClient(){this(MediaSourceClient::download);}
    MediaSourceClient(Transport transport){this.transport=transport;}
    JSONObject request(String url,CancellationSignal signal)throws Exception {byte[] bytes=transport.get(url,MAX_JSON,signal);if(bytes.length>MAX_JSON)throw new IOException("响应超过2 MiB");return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
    List<Item> search(Source source,String query,int page,CancellationSignal signal)throws Exception{return items(request(source.url(source.search,query,"",page),signal));}
    Detail detail(Source source,String id,CancellationSignal signal)throws Exception{return new Detail(request(source.url(source.detail,"",id,1),signal));}
    List<String> pages(Source source,String id,CancellationSignal signal)throws Exception {String url=source.url(source.pages,"",id,1);return pageUrls(request(url,signal),url);}
    String stream(Source source,String id,CancellationSignal signal)throws Exception {JSONObject j=request(source.url(source.stream,"",id,1),signal);return CatalogClient.checkedUrl(required(j,"url",8192));}
    static List<Item> items(JSONObject j)throws Exception {JSONArray a=j.getJSONArray("items");if(a.length()>MAX_ITEMS)throw new IOException("搜索结果超过500项，请使用分页接口");ArrayList<Item> items=new ArrayList<>();for(int i=0;i<a.length();i++)items.add(new Item(a.getJSONObject(i)));return items;}
    static List<String> pageUrls(JSONObject j,String base)throws Exception {JSONArray a=j.getJSONArray("pages");if(a.length()==0||a.length()>MAX_PAGES)throw new IOException("图片页数须在1–2000之间");ArrayList<String> urls=new ArrayList<>();for(int i=0;i<a.length();i++){Object raw=a.get(i);String value=raw instanceof JSONObject?((JSONObject)raw).getString("url"):a.getString(i);urls.add(CatalogClient.checkedUrl(new URI(base).resolve(value).toString()));}return urls;}
    static List<Source> parsePack(String text)throws Exception {if(text.getBytes(StandardCharsets.UTF_8).length>256*1024)throw new IOException("源包超过256 KiB");JSONObject j=new JSONObject(text);if(j.getInt("schemaVersion")!=1||!"openleaf-media-sources".equals(j.getString("format")))throw new IOException("请选择 OpenLeaf 音乐/漫画 JSON 源包；Venera/XY 的 JS 源需适配服务转换");JSONArray a=j.getJSONArray("sources");if(a.length()>100)throw new IOException("源包超过100项");ArrayList<Source> result=new ArrayList<>();HashSet<String> ids=new HashSet<>();for(int i=0;i<a.length();i++){Source s=new Source(a.getJSONObject(i));if(!ids.add(s.id))throw new IOException("源包 id 重复："+s.id);result.add(s);}return result;}
    static String pack(List<Source> sources)throws Exception {JSONArray a=new JSONArray();for(Source s:sources)a.put(s.json());return new JSONObject().put("format","openleaf-media-sources").put("schemaVersion",1).put("sources",a).toString(2);}
    static byte[] download(String raw,int limit,CancellationSignal signal)throws Exception {
        String url=CatalogClient.checkedUrl(raw);
        for(int redirect=0;redirect<=5;redirect++){
            if(signal!=null)signal.throwIfCanceled();HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestProperty("User-Agent","OpenLeaf/1.0 SourceAdapter");
            if(signal!=null)signal.setOnCancelListener(connection::disconnect);
            try {int code=connection.getResponseCode();if(code==301||code==302||code==303||code==307||code==308){String next=connection.getHeaderField("Location");if(next==null)throw new IOException("重定向缺少地址");url=CatalogClient.checkedUrl(new URI(url).resolve(next).toString());continue;}
                if(code<200||code>=300)throw new IOException("HTTP "+code);if(connection.getContentLengthLong()>limit)throw new IOException("响应超过大小上限");
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1){if(signal!=null)signal.throwIfCanceled();if(bytes.size()+n>limit)throw new IOException("响应超过大小上限");bytes.write(buffer,0,n);}return bytes.toByteArray();}
            }finally{if(signal!=null)signal.setOnCancelListener(null);connection.disconnect();}
        }throw new IOException("重定向次数过多");
    }
    private static int port(URI u){return u.getPort()==-1?443:u.getPort();}
    private static String encode(String s)throws Exception{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}
    private static String required(JSONObject j,String key,int max)throws Exception {Object raw=j.get(key);if(!(raw instanceof String))throw new IOException(key+" 必须是字符串");String v=((String)raw).trim();if(v.isEmpty()||v.length()>max)throw new IOException(key+" 为空或过长");return v;}
}
