import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.BookSearch;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class BookSearchTest {
    private static int passed;
    private interface Check {void run()throws Exception;}
    private static void test(String name,Check test)throws Exception{test.run();passed++;System.out.println("PASS "+name);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static BookParser.BookContent book(String... chapters){
        java.util.ArrayList<BookParser.Chapter> data=new java.util.ArrayList<>();
        for(int i=0;i<chapters.length;i++)data.add(new BookParser.Chapter("第"+(i+1)+"章",chapters[i]));
        return new BookParser.BookContent("中性搜索样本",data);
    }
    private static BookSearch.Result find(BookParser.BookContent book,String query,boolean sensitive,int scope,int limit){return BookSearch.find(book,query,sensitive,scope,limit,()->false);}
    public static void main(String[] args)throws Exception{
        test("Chinese matches retain chapter offsets",()->{
            BookParser.BookContent b=book("春天的阅读，阅读让人安静。","另一章也有阅读。");
            BookSearch.Result r=find(b,"阅读",true,-1,100);
            require(r.hits.size()==3,"wrong count");
            for(BookSearch.Hit hit:r.hits)require(b.chapters.get(hit.chapter).text.substring(hit.start,hit.end).equals("阅读"),"offset changed");
        });
        test("case insensitive search does not change UTF16 offsets",()->{
            BookParser.BookContent b=book("İ A a 中A");
            BookSearch.Result r=find(b,"a",false,-1,10);require(r.hits.size()==3,"wrong folded match count");
            require(r.hits.get(0).start==2&&r.hits.get(2).start==7,"lowercase expansion shifted index");
            require(find(b,"a",true,-1,10).hits.size()==1,"case-sensitive ignored");
        });
        test("literal metacharacters and spaces",()->{
            require(find(book("a.b a*b a.b"),"a.b",true,-1,10).hits.size()==2,"regex semantics leaked");
            require(find(book("a  b"),"  ",true,-1,10).hits.size()==1,"query was trimmed");
        });
        test("overlapping matches",()->{BookSearch.Result r=find(book("aaaa"),"aa",true,-1,10);require(r.hits.size()==3&&r.hits.get(2).start==2,"overlap missing");});
        test("scope restricts chapters and excludes artificial cross chapter match",()->{
            BookParser.BookContent b=book("abc","def abc");
            require(find(b,"abc",true,1,10).hits.size()==1,"scope ignored");
            require(find(b,"cdef",true,-1,10).hits.isEmpty(),"cross-chapter text joined");
        });
        test("truncation distinguishes exactly limit from excess",()->{
            BookSearch.Result exact=find(book("xx"),"x",true,-1,2),more=find(book("xxx"),"x",true,-1,2);
            require(exact.hits.size()==2&&!exact.truncated&&more.hits.size()==2&&more.truncated,"limit boundary wrong");
        });
        test("surrogate pairs and snippets stay valid",()->{
            String text=String.join("",Collections.nCopies(70,"😀"))+"关键词"+String.join("",Collections.nCopies(70,"🚀"));
            BookSearch.Hit h=find(book(text),"关键词",true,-1,10).hits.get(0);
            require(h.start==140&&h.snippetStart==50,"UTF16 identity not preserved");
            require(!Character.isLowSurrogate(h.snippet.charAt(0))&&!Character.isHighSurrogate(h.snippet.charAt(h.snippet.length()-1)),"snippet split surrogate");
            require(h.snippet.substring(h.start-h.snippetStart,h.end-h.snippetStart).equals("关键词"),"snippet highlight wrong");
        });
        test("newline query and full text match",()->{require(find(book("第一行\n第二行"),"行\n第",true,-1,5).hits.size()==1,"multiline match missing");});
        test("empty query and no results",()->{require(find(book("sample"),"",true,-1,5).hits.isEmpty()&&find(book("sample"),"missing",true,-1,5).hits.isEmpty(),"empty or absent query matched");});
        test("invalid scopes and lengths rejected",()->{
            int rejected=0;
            try{find(book("x"),"x",true,3,10);}catch(IllegalArgumentException e){rejected++;}
            try{find(book("x"),String.join("",Collections.nCopies(257,"x")),true,-1,10);}catch(IllegalArgumentException e){rejected++;}
            try{find(book("x"),"x",true,-1,0);}catch(IllegalArgumentException e){rejected++;}
            require(rejected==3,"invalid input accepted");
        });
        test("cancellation before and during scan",()->{
            boolean before=false,during=false;
            try{BookSearch.find(book("x"),"x",true,-1,10,()->true);}catch(CancellationException e){before=true;}
            AtomicInteger calls=new AtomicInteger();
            try{BookSearch.find(book(String.join("",Collections.nCopies(10000,"a"))),"z",true,-1,10,()->calls.incrementAndGet()>4);}catch(CancellationException e){during=true;}
            require(before&&during,"cancel not observed");
        });
        test("result immutable",()->{boolean rejected=false;try{find(book("aa"),"a",true,-1,10).hits.clear();}catch(UnsupportedOperationException e){rejected=true;}require(rejected,"results mutable");});
        System.out.println("PASS: "+passed+" book search checks");
    }
}
