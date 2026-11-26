package org.openleaf.reader;

import android.os.CancellationSignal;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Independently implemented static subset of Legado's JSON source format. No script execution. */
final class LegadoClient {
    static final int MAX_SOURCE_BYTES=1024*1024, MAX_RESPONSE=2*1024*1024, MAX_BOOKS=500, MAX_CHAPTERS=10000;
    static final class Source {
        final String name,url,searchUrl;
        private final JSONObject raw;
        final JSONObject search,info,toc,content;
        Source(JSONObject input)throws Exception {
            if(input.toString().getBytes(StandardCharsets.UTF_8).length>MAX_SOURCE_BYTES)throw new IOException("单个书源超过1 MiB");
            raw=new JSONObject(input.toString());
            name=required(raw,"bookSourceName",100);url=CatalogClient.checkedUrl(required(raw,"bookSourceUrl",4096));
            if(raw.optInt("bookSourceType",0)!=0)throw new IOException("只支持文字书源 bookSourceType=0");
            for(String key:new String[]{"jsLib","loginUrl","loginUi","loginCheckJs","coverDecodeJs","header"})
                if(!raw.optString(key,"").trim().isEmpty())throw new IOException("暂不支持书源字段 "+key+"（脚本、登录或自定义请求头）");
            searchUrl=required(raw,"searchUrl",4096);requestUrl(this,searchUrl,"test",1);
            search=rules(raw,"ruleSearch");info=rules(raw,"ruleBookInfo");toc=rules(raw,"ruleToc");content=rules(raw,"ruleContent");
            requireRule(search,"bookList");requireRule(search,"name");requireRule(search,"bookUrl");
            requireRule(toc,"chapterList");requireRule(toc,"chapterName");requireRule(toc,"chapterUrl");requireRule(content,"content");
            if(!content.optString("nextContentUrl","").isEmpty())throw new IOException("暂不支持分段正文 nextContentUrl，请使用单页正文规则");
        }
        JSONObject json()throws Exception{return new JSONObject(raw.toString());}
    }
    static final class Book {
        final String title,author,url,cover,intro,tocUrl;
        Book(String title,String author,String url,String cover,String intro,String tocUrl){this.title=title;this.author=author;this.url=url;this.cover=cover;this.intro=intro;this.tocUrl=tocUrl;}
    }
    static final class Chapter {
        final String title,url;
        Chapter(String title,String url){this.title=title;this.url=url;}
    }
    static final class Content {
        final String title,text,nextUrl;
        Content(String title,String text,String nextUrl){this.title=title;this.text=text;this.nextUrl=nextUrl;}
    }
    interface Transport {String get(String url,CancellationSignal signal)throws Exception;}
    private final Transport transport;
    LegadoClient(){this(LegadoClient::download);}
    LegadoClient(Transport transport){this.transport=transport;}

    static List<Source> parseSources(String text)throws Exception {
        if(text.getBytes(StandardCharsets.UTF_8).length>MAX_SOURCE_BYTES)throw new IOException("书源文件超过1 MiB");
        JSONTokener tokenizer=new JSONTokener(stripBom(text));Object root=tokenizer.nextValue();
        if(tokenizer.nextClean()!=0)throw new IOException("书源文件包含多余内容");
        JSONArray list=root instanceof JSONArray?(JSONArray)root:root instanceof JSONObject?new JSONArray().put(root):null;
        if(list==null||list.length()>100)throw new IOException("请选择单个书源对象或最多100项的JSON数组");
        ArrayList<Source> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(int i=0;i<list.length();i++){Source source=new Source(list.getJSONObject(i));if(seen.add(source.url))result.add(source);}
        return result;
    }
    static String exportSources(List<Source> sources)throws Exception {JSONArray list=new JSONArray();for(Source source:sources)list.put(source.json());return list.toString(2);}
    private Object page(String url,CancellationSignal signal)throws Exception {
        check(signal);String body=transport.get(CatalogClient.checkedUrl(url),signal);check(signal);
        if(body==null||body.getBytes(StandardCharsets.UTF_8).length>MAX_RESPONSE)throw new IOException("书源响应为空或超过2 MiB");
        body=stripBom(body);String trimmed=body.trim();
        if(trimmed.startsWith("{")||trimmed.startsWith("[")){JSONTokener tokener=new JSONTokener(trimmed);Object value=tokener.nextValue();if(tokener.nextClean()!=0)throw new IOException("JSON响应包含多余内容");return value;}
        return Jsoup.parse(body,url);
    }
    List<Book> search(Source source,String keyword,int page,CancellationSignal signal)throws Exception {
        if(keyword==null||keyword.trim().isEmpty()||keyword.length()>256||page<1||page>10000)throw new IOException("搜索词或页码无效");
        String address=requestUrl(source,source.searchUrl,keyword,page);Object root=page(address,signal);
        List<Object> items=select(root,source.search.getString("bookList"));if(items.size()>MAX_BOOKS)throw new IOException("搜索结果超过500项，请使用分页规则");
        ArrayList<Book> books=new ArrayList<>();
        for(Object item:items){check(signal);String title=value(item,source.search,"name",false);String link=value(item,source.search,"bookUrl",false);if(title.isEmpty()||link.isEmpty())continue;
            String url=resolve(address,link);books.add(new Book(title,value(item,source.search,"author",false),url,optionalUrl(address,value(item,source.search,"coverUrl",false)),value(item,source.search,"intro",true),url));}
        return books;
    }
    Book detail(Source source,Book book,CancellationSignal signal)throws Exception {
        Object root=page(book.url,signal);String init=source.info.optString("init","");if(!init.isEmpty()){List<Object> list=select(root,init);if(list.isEmpty())throw new IOException("详情 init 规则未匹配");root=list.get(0);}
        String title=value(root,source.info,"name",false),author=value(root,source.info,"author",false),intro=value(root,source.info,"intro",true),cover=value(root,source.info,"coverUrl",false),toc=value(root,source.info,"tocUrl",false);
        return new Book(or(title,book.title),or(author,book.author),book.url,cover.isEmpty()?book.cover:resolve(book.url,cover),or(intro,book.intro),toc.isEmpty()?book.url:resolve(book.url,toc));
    }
    List<Chapter> toc(Source source,Book book,CancellationSignal signal)throws Exception {
        String address=or(book.tocUrl,book.url);ArrayList<Chapter> result=new ArrayList<>();Set<String> visited=new HashSet<>(),chapterUrls=new HashSet<>();
        for(int pages=0;pages<50;pages++){
            check(signal);if(!visited.add(address))throw new IOException("目录分页形成循环");Object root=page(address,signal);String rule=source.toc.getString("chapterList");boolean reverse=rule.startsWith("-");List<Object> rows=select(root,reverse?rule.substring(1):rule);if(reverse)Collections.reverse(rows);
            for(Object row:rows){check(signal);String title=value(row,source.toc,"chapterName",false),href=value(row,source.toc,"chapterUrl",false);if(title.isEmpty()||href.isEmpty())continue;String url=resolve(address,href);if(chapterUrls.add(url))result.add(new Chapter(title,url));if(result.size()>MAX_CHAPTERS)throw new IOException("章节超过10000项");}
            String next=value(root,source.toc,"nextTocUrl",false);if(next.isEmpty())return result;address=resolve(address,next);
        }
        throw new IOException("目录分页超过50页");
    }
    Content content(Source source,Chapter chapter,CancellationSignal signal)throws Exception {
        Object root=page(chapter.url,signal);String text=value(root,source.content,"content",true);if(text.trim().isEmpty())throw new IOException("正文规则未匹配到内容");
        if(text.length()>1000000)throw new IOException("单章正文超过100万字符");
        String title=value(root,source.content,"title",false),next=value(root,source.content,"nextChapterUrl",false);
        return new Content(or(title,chapter.title),text,optionalUrl(chapter.url,next));
    }
    static String requestUrl(Source source,String template,String keyword,int page)throws Exception {
        String value=template.trim();rejectDynamic(value);
        if(value.matches("(?s).*,\\s*\\{.*"))throw new IOException("暂不支持URL请求选项、POST或WebView");
        String encoded=URLEncoder.encode(keyword,"UTF-8").replace("+","%20");
        value=value.replace("{{key}}",encoded).replace("{{page}}",String.valueOf(page));
        if(value.contains("{{")||value.contains("}}"))throw new IOException("URL只支持 {{key}} 和 {{page}} 模板");
        return resolve(source.url,value);
    }
    private static JSONObject rules(JSONObject raw,String name)throws Exception {
        Object object=raw.opt(name);JSONObject result;
        if(object==null||object==JSONObject.NULL)result=new JSONObject();
        else if(object instanceof JSONObject)result=(JSONObject)object;
        else if(object instanceof String)result=new JSONObject((String)object);
        else throw new IOException(name+" 必须为规则对象");
        Iterator<String> keys=result.keys();while(keys.hasNext()){String key=keys.next();Object v=result.get(key);if(v==JSONObject.NULL)continue;if(!(v instanceof String))throw new IOException(name+"."+key+" 必须为字符串");validateRule((String)v);}
        return result;
    }
    private static void requireRule(JSONObject rules,String key)throws Exception {if(rules.optString(key,"").trim().isEmpty())throw new IOException("缺少规则 "+key);}
    private static void rejectDynamic(String value)throws IOException {
        String lower=value.toLowerCase(Locale.ROOT);if(lower.contains("@js:")||lower.contains("<js>")||lower.contains("javascript:")||lower.contains("@get:")||lower.contains("@put:")||lower.startsWith("js:"))throw new IOException("本版不执行JavaScript或变量规则");
    }
    private static void validateRule(String rule)throws IOException {
        if(rule.length()>4096)throw new IOException("规则超过4096字符");rejectDynamic(rule);
        if(rule.toLowerCase(Locale.ROOT).contains(":matches")||rule.contains("##")||rule.contains("&&")||rule.contains("||")||rule.contains("%%")||rule.startsWith("/")||rule.startsWith("@XPath:")||rule.startsWith("@xpath:")||rule.contains("{{"))throw new IOException("暂不支持XPath、正则替换、组合或动态规则："+shortRule(rule));
    }
    private static List<Object> select(Object root,String raw)throws Exception {
        String rule=raw.trim();validateRule(rule);if(rule.startsWith("@Json:"))rule=rule.substring(6);if(rule.startsWith("@json:"))rule=rule.substring(6);
        if(root instanceof JSONObject||root instanceof JSONArray){if(rule.isEmpty()||rule.equals("$"))return new ArrayList<>(Collections.singletonList(root));return jsonPath(root,rule);}
        if(!(root instanceof Element))return new ArrayList<>();
        Element base=(Element)root;if(rule.startsWith("@css:"))rule=rule.substring(5);if(rule.startsWith("@CSS:"))rule=rule.substring(5);
        if(rule.isEmpty())return new ArrayList<>(Collections.singletonList(base));
        ArrayList<Object> current=new ArrayList<>();current.add(base);
        // Legado's @ separates a descendant selector chain as well as its final extractor.
        for(String stage:rule.split("@")){
            if(stage.isEmpty())continue;ArrayList<Object> next=new ArrayList<>();
            String css=stage;Matcher index=Pattern.compile("^(.*)\\.(-?\\d+)$").matcher(css);Integer position=null;if(index.matches()){css=index.group(1);position=Integer.valueOf(index.group(2));}
            if(css.startsWith("class."))css="."+css.substring(6);else if(css.startsWith("id."))css="#"+css.substring(3);else if(css.startsWith("tag."))css=css.substring(4);
            for(Object element:current){Elements found;try{found=((Element)element).select(css);}catch(RuntimeException bad){throw new IOException("CSS规则无效："+shortRule(stage),bad);}if(position==null)next.addAll(found);else{int at=position<0?found.size()+position:position;if(at>=0&&at<found.size())next.add(found.get(at));}if(next.size()>MAX_CHAPTERS)throw new IOException("规则匹配超过10000项");}
            current=next;
        }
        return current;
    }
    private static String value(Object root,JSONObject rules,String key,boolean multiline)throws Exception {
        String raw=rules.optString(key,"").trim();if(raw.isEmpty())return "";validateRule(raw);
        if(root instanceof JSONObject||root instanceof JSONArray){List<Object> values=select(root,raw);ArrayList<String> text=new ArrayList<>();for(Object v:values)if(v!=null&&v!=JSONObject.NULL){if(v instanceof JSONObject||v instanceof JSONArray)throw new IOException("字段规则应匹配文本："+key);text.add(String.valueOf(v));}return String.join(multiline?"\n":" ",text).trim();}
        if(raw.startsWith("@css:")||raw.startsWith("@CSS:"))raw=raw.substring(5);
        String extractor="text",selector=raw;int split=raw.lastIndexOf('@');
        if(split>=0){selector=raw.substring(0,split);extractor=raw.substring(split+1);}else if(raw.equals("text")||raw.equals("textNodes")||raw.equals("ownText")||raw.equals("html")||raw.equals("all")){selector="";extractor=raw;}
        List<Object> nodes=select(root,selector);ArrayList<String> parts=new ArrayList<>();
        for(Object node:nodes){Element element=(Element)node;String text;
            if(extractor.equals("html")||extractor.equals("all"))text=multiline?plain(element.html()):element.text();
            else if(extractor.equals("textNodes")||extractor.equals("ownText"))text=element.ownText();
            else if(extractor.equals("text"))text=multiline?plain(element.html()):element.text();
            else if(extractor.matches("[A-Za-z_:][A-Za-z0-9_:.\\-]*"))text=element.attr(extractor);
            else throw new IOException("不支持字段提取："+extractor);
            if(!text.trim().isEmpty())parts.add(text.trim());
        }
        return String.join(multiline?"\n":" ",parts).trim();
    }
    private static String plain(String html) {
        Element body = Jsoup.parseBodyFragment(html).body();
        body.select("script, style, noscript, template").remove();
        StringBuilder text = new StringBuilder();
        org.jsoup.select.NodeTraversor.traverse(new org.jsoup.select.NodeVisitor() {
            @Override public void head(org.jsoup.nodes.Node node, int depth) {
                if (node instanceof org.jsoup.nodes.TextNode) {
                    text.append(((org.jsoup.nodes.TextNode) node).getWholeText().replaceAll("[\\s\\u00a0]+", " "));
                } else if (node.nodeName().equals("br")) text.append('\n');
            }
            @Override public void tail(org.jsoup.nodes.Node node, int depth) {
                switch (node.nodeName()) {
                    case "p": case "div": case "li": case "section": case "article":
                    case "h1": case "h2": case "h3": case "h4": case "h5": case "h6":
                        if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') text.append('\n');
                        break;
                    default: break;
                }
            }
        }, body);
        return text.toString().replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }
    private static List<Object> jsonPath(Object root,String path)throws Exception {
        String rule=path;if(rule.startsWith("$."))rule=rule.substring(2);else if(rule.startsWith("$"))rule=rule.substring(1);
        ArrayList<Object> current=new ArrayList<>();current.add(root);int offset=0;
        Pattern token=Pattern.compile("(?:\\.?([A-Za-z_][A-Za-z0-9_\\-]*)|\\[([0-9]+|\\*)\\]|\\[['\"]([^'\"]+)['\"]\\])");
        while(offset<rule.length()){
            Matcher matcher=token.matcher(rule);matcher.region(offset,rule.length());if(!matcher.lookingAt())throw new IOException("仅支持JSONPath字段、索引和[*]："+shortRule(path));
            String field=matcher.group(1)!=null?matcher.group(1):matcher.group(3),index=matcher.group(2);ArrayList<Object> next=new ArrayList<>();
            for(Object object:current){
                if(field!=null){
                    if(object instanceof JSONObject){Object v=((JSONObject)object).opt(field);if(v!=null&&v!=JSONObject.NULL)next.add(v);}
                }else if(object instanceof JSONArray){
                    JSONArray a=(JSONArray)object;
                    if(index.equals("*")){for(int i=0;i<a.length();i++){next.add(a.get(i));if(next.size()>MAX_CHAPTERS)throw new IOException("JSON规则匹配超过10000项");}}
                    else{int at;try{at=Integer.parseInt(index);}catch(NumberFormatException invalid){throw new IOException("JSONPath索引无效");}if(at<a.length())next.add(a.get(at));}
                }
                if(next.size()>MAX_CHAPTERS)throw new IOException("JSON规则匹配超过10000项");
            }
            current=next;offset=matcher.end();
        }
        // Legado list paths may end at the array itself instead of [*].
        if(current.size()==1&&current.get(0) instanceof JSONArray){JSONArray array=(JSONArray)current.get(0);current=new ArrayList<>();if(array.length()>MAX_CHAPTERS)throw new IOException("JSON列表超过10000项");for(int i=0;i<array.length();i++)current.add(array.get(i));}
        return current;
    }
    static String download(String raw,CancellationSignal signal)throws Exception {
        String address=CatalogClient.checkedUrl(raw);
        HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestProperty("User-Agent","OpenLeaf/0.11 LegadoStatic");
        if(signal!=null){check(signal);signal.setOnCancelListener(connection::disconnect);}
        try{
            int code=connection.getResponseCode();check(signal);
            // Refusing redirects keeps selector-relative URLs tied to the actual page origin.
            if(code>=300&&code<400)throw new IOException("书源发生重定向，请将规则地址更新为最终HTTPS地址");if(code<200||code>=300)throw new IOException("书源返回 HTTP "+code);
            if(connection.getContentLengthLong()>MAX_RESPONSE)throw new IOException("书源响应超过2 MiB");
            byte[] bytes;try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){check(signal);if(n>MAX_RESPONSE-out.size())throw new IOException("书源响应超过2 MiB");out.write(b,0,n);}bytes=out.toByteArray();}
            Charset charset=StandardCharsets.UTF_8;String type=connection.getContentType();if(type!=null){Matcher m=Pattern.compile("charset\\s*=\\s*['\"]?([^;\\s'\"]+)",Pattern.CASE_INSENSITIVE).matcher(type);if(m.find())try{charset=Charset.forName(m.group(1));}catch(Exception invalid){throw new IOException("响应编码不受支持");}}
            return new String(bytes,charset);
        }finally{if(signal!=null)signal.setOnCancelListener(null);connection.disconnect();}
    }
    private static String resolve(String base,String href)throws Exception {String value=href.trim();if(value.isEmpty())throw new IOException("书源链接为空");return CatalogClient.checkedUrl(new URI(base).resolve(value).toString());}
    private static String optionalUrl(String base,String value)throws Exception {return value.trim().isEmpty()?"":resolve(base,value);}
    private static String required(JSONObject object,String key,int max)throws Exception {Object raw=object.get(key);if(!(raw instanceof String))throw new IOException(key+" 必须为字符串");String value=((String)raw).trim();if(value.isEmpty()||value.length()>max)throw new IOException(key+" 为空或过长");return value;}
    private static String or(String value,String fallback){return value.isEmpty()?fallback:value;}
    private static String shortRule(String rule){return rule.length()>80?rule.substring(0,80)+"…":rule;}
    private static String stripBom(String text){return text.startsWith("\uFEFF")?text.substring(1):text;}
    private static void check(CancellationSignal signal){if(signal!=null)signal.throwIfCanceled();}
}
