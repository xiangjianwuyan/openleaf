package org.openleaf.reader;

import android.os.CancellationSignal;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.SAXParserFactory;

/** Bounded OPDS 1/2 catalogue reader; accepts data only, never site scripts. */
final class CatalogClient {
    static final int MAX_FEED_BYTES=2*1024*1024,MAX_ENTRIES=500;
    static final long MAX_BOOK_BYTES=org.openleaf.reader.core.BookParser.MAX_IMPORT_BYTES;
    static final class Link {
        final String url,type,rel,title;
        Link(String url,String type,String rel,String title){this.url=url;this.type=type;this.rel=rel;this.title=title;}
    }
    static final class Entry {
        final String title,author;final List<Link> links;
        Entry(String title,String author,List<Link> links){this.title=title;this.author=author;this.links=Collections.unmodifiableList(new ArrayList<>(links));}
    }
    static final class Feed {
        final String title,url;final List<Entry> entries;
        Feed(String title,String url,List<Entry> entries){this.title=title;this.url=url;this.entries=Collections.unmodifiableList(entries);}
    }
    interface Progress {void bytes(long bytes,long total);}
    private CatalogClient(){}
    static String checkedUrl(String value)throws IOException{
        try{
            URI uri=new URI(value.trim());
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getHost().isEmpty()||uri.getUserInfo()!=null||uri.getFragment()!=null)
                throw new IOException("只支持不含账号和片段的 HTTPS 地址");
            if(uri.getPort()!=-1&&(uri.getPort()<1||uri.getPort()>65535))throw new IOException("地址端口无效");
            return uri.toASCIIString();
        }catch(java.net.URISyntaxException|NullPointerException error){throw new IOException("网址无效",error);}
    }
    private static String resolve(String base,String href)throws IOException{
        try{return checkedUrl(new URI(base).resolve(href).toString());}
        catch(java.net.URISyntaxException invalid){throw new IOException("目录链接无效",invalid);}
    }
    private static HttpURLConnection connect(String raw,CancellationSignal signal)throws Exception{
        String address=checkedUrl(raw);
        for(int redirect=0;redirect<=5;redirect++){
            if(signal!=null)signal.throwIfCanceled();
            HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent","OpenLeaf/0.7 OPDS");
            int code;
            try{code=connection.getResponseCode();}catch(Exception error){connection.disconnect();throw error;}
            if(code==301||code==302||code==303||code==307||code==308){
                String next=connection.getHeaderField("Location");connection.disconnect();
                if(next==null)throw new IOException("目录重定向缺少目标地址");
                address=resolve(address,next);continue;
            }
            if(code<200||code>=300){connection.disconnect();throw new IOException("服务器返回 HTTP "+code+"；首版不支持登录或付费获取");}
            return connection;
        }
        throw new IOException("重定向次数过多");
    }
    static Feed load(String address,CancellationSignal signal)throws Exception{
        HttpURLConnection connection=connect(address,signal);
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[16384];int n;
            while((n=input.read(buffer))!=-1){if(signal!=null)signal.throwIfCanceled();if(bytes.size()+n>MAX_FEED_BYTES)throw new IOException("目录超过 2 MiB 上限");bytes.write(buffer,0,n);}
            String finalUrl=connection.getURL().toString();byte[] content=bytes.toByteArray();
            return parse(content,finalUrl);
        }finally{connection.disconnect();}
    }
    static Feed parse(byte[] content,String base)throws Exception{
        if(content.length>MAX_FEED_BYTES)throw new IOException("目录超过 2 MiB 上限");
        base=checkedUrl(base);
        String text=new String(content,StandardCharsets.UTF_8).trim();
        if(text.startsWith("\uFEFF"))text=text.substring(1).trim();
        if(text.startsWith("{"))return jsonFeed(new JSONObject(text),base);
        return atomFeed(content,base);
    }
    private static Feed jsonFeed(JSONObject json,String base)throws Exception{
        ArrayList<Entry> entries=new ArrayList<>();
        for(String collection:new String[]{"navigation","publications","groups"}){
            JSONArray array=json.optJSONArray(collection);if(array==null)continue;
            if(collection.equals("groups")){
                for(int i=0;i<array.length()&&entries.size()<MAX_ENTRIES;i++){
                    JSONObject group=array.optJSONObject(i);if(group==null)continue;
                    JSONArray nav=group.optJSONArray("navigation"),pub=group.optJSONArray("publications");
                    addJsonEntries(entries,nav,base);addJsonEntries(entries,pub,base);
                }
            }else addJsonEntries(entries,array,base);
        }
        if(entries.isEmpty())throw new IOException("没有可浏览的 OPDS 目录项；普通网页或书源脚本不受支持");
        return new Feed(json.optJSONObject("metadata")==null?"在线目录":json.getJSONObject("metadata").optString("title","在线目录"),base,entries);
    }
    private static void addJsonEntries(List<Entry> result,JSONArray array,String base)throws Exception{
        if(array==null)return;
        for(int i=0;i<array.length()&&result.size()<MAX_ENTRIES;i++){
            JSONObject item=array.optJSONObject(i);if(item==null)continue;
            JSONObject metadata=item.optJSONObject("metadata");String title=metadata==null?item.optString("title","未命名"):metadata.optString("title","未命名");
            String author="";if(metadata!=null){Object value=metadata.opt("author");if(value instanceof String)author=(String)value;else if(value instanceof JSONObject)author=((JSONObject)value).optString("name","");else if(value instanceof JSONArray){JSONArray authors=(JSONArray)value;ArrayList<String> names=new ArrayList<>();for(int k=0;k<authors.length();k++){JSONObject a=authors.optJSONObject(k);if(a!=null)names.add(a.optString("name",""));}author=android.text.TextUtils.join(", ",names);}}
            ArrayList<Link> links=new ArrayList<>();JSONArray raw=item.optJSONArray("links");
            if(raw!=null)for(int j=0;j<raw.length();j++){JSONObject link=raw.optJSONObject(j);if(link==null)continue;String href=link.optString("href","");try{String type=link.optString("type","");String rel=link.opt("rel") instanceof JSONArray?link.getJSONArray("rel").join(" ").replace("\"",""):link.optString("rel","");links.add(new Link(resolve(base,href),type,rel,link.optString("title",title)));}catch(IOException ignored){}}
            if(!links.isEmpty())result.add(new Entry(title,author,links));
        }
    }
    private static Feed atomFeed(byte[] bytes,String base)throws Exception{
        String guard=new String(bytes,StandardCharsets.ISO_8859_1).replace("\u0000","").toUpperCase(Locale.ROOT);
        if(guard.contains("<!DOCTYPE")||guard.contains("<!ENTITY"))throw new IOException("目录禁止 DTD 和外部实体");
        SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);factory.setValidating(false);
        try{factory.setFeature("http://xml.org/sax/features/external-general-entities",false);}catch(Exception ignored){}
        try{factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);}catch(Exception ignored){}
        XMLReader reader=factory.newSAXParser().getXMLReader();
        reader.setEntityResolver((publicId,systemId)->{throw new org.xml.sax.SAXException("外部实体被禁止");});
        AtomHandler handler=new AtomHandler(base);reader.setContentHandler(handler);reader.setErrorHandler(handler);
        // Mandatory parser-level rejection: also covers non-ASCII XML encodings.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler",handler);
        reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
        if(handler.entries.isEmpty())throw new IOException("没有可浏览的 OPDS 目录项；请提供 OPDS 1 / 2 地址");
        return new Feed(handler.feedTitle.length()==0?"在线目录":handler.feedTitle.toString().trim(),base,handler.entries);
    }
    private static final class AtomHandler extends DefaultHandler2{
        @Override public void startDTD(String name,String publicId,String systemId)throws org.xml.sax.SAXException{throw new org.xml.sax.SAXException("目录禁止 DTD");}
        final String base;final ArrayList<Entry> entries=new ArrayList<>();final StringBuilder feedTitle=new StringBuilder();
        StringBuilder title,author;ArrayList<Link> links;int depth,entryDepth=-1,titleDepth=-1,authorDepth=-1;
        AtomHandler(String base){this.base=base;}
        String name(String local,String qualified){return local==null||local.isEmpty()?qualified:local;}
        @Override public void startElement(String uri,String local,String q,Attributes attrs)throws org.xml.sax.SAXException{
            if(++depth>64)throw new org.xml.sax.SAXException("目录 XML 嵌套过深");String tag=name(local,q);
            if(tag.equals("entry")){entryDepth=depth;title=new StringBuilder();author=new StringBuilder();links=new ArrayList<>();}
            if(tag.equals("title"))titleDepth=depth;
            if(entryDepth>0&&tag.equals("name"))authorDepth=depth;
            if(entryDepth>0&&tag.equals("link")&&links.size()<32){try{String href=attrs.getValue("href");if(href!=null)links.add(new Link(resolve(base,href),value(attrs,"type"),value(attrs,"rel"),value(attrs,"title")));}catch(IOException ignored){}}
        }
        static String value(Attributes a,String key){String result=a.getValue(key);return result==null?"":result;}
        @Override public void characters(char[] text,int start,int length){if(titleDepth>0){if(entryDepth>0)title.append(text,start,length);else feedTitle.append(text,start,length);}if(authorDepth>0&&entryDepth>0)author.append(text,start,length);}
        @Override public void endElement(String uri,String local,String q){String tag=name(local,q);if(depth==titleDepth)titleDepth=-1;if(depth==authorDepth)authorDepth=-1;if(tag.equals("entry")&&entryDepth==depth){if(entries.size()<MAX_ENTRIES&&!links.isEmpty())entries.add(new Entry(title.toString().trim(),author.toString().trim(),links));entryDepth=-1;}depth--;}
    }
    static String extension(Link link){
        String type=link.type.toLowerCase(Locale.ROOT).split(";",2)[0].trim();
        if(type.equals("application/epub+zip"))return "epub";
        if(type.equals("application/pdf"))return "pdf";
        if(type.equals("text/plain"))return "txt";
        if(type.equals("application/vnd.comicbook+zip")||type.equals("application/x-cbz"))return "cbz";
        String path;try{path=new URI(link.url).getPath().toLowerCase(Locale.ROOT);}catch(Exception ignored){return null;}
        for(String extension:new String[]{"epub","pdf","txt","cbz"})if(path.endsWith("."+extension))return extension;
        return null;
    }
    static boolean acquisition(Link link){return link.rel.contains("opds-spec.org/acquisition")||extension(link)!=null;}
    static boolean catalog(Link link){String type=link.type.toLowerCase(Locale.ROOT);return type.contains("atom+xml")||type.contains("opds+json")||type.contains("opds-publication+json");}
}
