package org.openleaf.reader;
import java.nio.charset.StandardCharsets;

/** Run against android.jar for signatures; cases below exercise only JDK XML/network-independent code. */
public final class CatalogProtocolTest {
    private static int passed;
    private interface Check {void run()throws Exception;}
    private static void test(String name,Check check)throws Exception{check.run();passed++;System.out.println("PASS "+name);}
    private static void require(boolean value){if(!value)throw new AssertionError();}
    private static CatalogClient.Feed parse(String xml)throws Exception{return CatalogClient.parse(xml.getBytes(StandardCharsets.UTF_8),"https://example.org/catalog/root.xml");}
    public static void main(String[] args)throws Exception{
        test("Atom namespace, metadata and relative acquisition",()->{
            CatalogClient.Feed feed=parse("<feed xmlns='http://www.w3.org/2005/Atom'><title>Test library</title><entry><title>Sample</title><author><name>OpenLeaf</name></author><link href='../books/sample.epub' type='application/epub+zip' rel='http://opds-spec.org/acquisition/open-access'/></entry></feed>");
            require(feed.title.equals("Test library")&&feed.entries.size()==1&&feed.entries.get(0).author.equals("OpenLeaf"));
            CatalogClient.Link link=feed.entries.get(0).links.get(0);require(link.url.equals("https://example.org/books/sample.epub")&&CatalogClient.acquisition(link)&&CatalogClient.extension(link).equals("epub"));
        });
        test("Only HTTPS links accepted",()->{
            CatalogClient.Feed feed=parse("<feed><entry><title>Links</title><link href='javascript:alert(1)'/><link href='file:///etc/passwd'/><link href='http://example.org/a'/><link href='https://example.org/opds' type='application/atom+xml'/></entry></feed>");
            require(feed.entries.size()==1&&feed.entries.get(0).links.size()==1&&CatalogClient.catalog(feed.entries.get(0).links.get(0)));
        });
        test("DTD and external entity are rejected",()->{
            boolean rejected=false;try{parse("<!DOCTYPE feed [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><feed><entry><title>&secret;</title><link href='a.epub'/></entry></feed>");}catch(Exception expected){rejected=true;}require(rejected);
        });
        test("UTF16 DTD cannot bypass preflight",()->{
            boolean rejected=false;String xml="<?xml version='1.0' encoding='UTF-16'?><!DOCTYPE feed [<!ENTITY x 'bad'>]><feed>&x;</feed>";
            try{CatalogClient.parse(xml.getBytes(StandardCharsets.UTF_16),"https://example.org/opds");}catch(Exception expected){rejected=true;}require(rejected);
        });
        test("Credentials and fragment URLs rejected",()->{
            int rejected=0;for(String url:new String[]{"https://user:pass@example.org/opds","https://example.org/opds#x","http://example.org/opds","https://"})try{CatalogClient.checkedUrl(url);}catch(Exception expected){rejected++;}require(rejected==4);
        });
        test("Bounded feed result count",()->{
            StringBuilder xml=new StringBuilder("<feed>");for(int i=0;i<501;i++)xml.append("<entry><title>Book ").append(i).append("</title><link href='b").append(i).append(".epub'/></entry>");xml.append("</feed>");require(parse(xml.toString()).entries.size()==500);
        });
        test("Feed bytes and nesting constrained",()->{
            int rejected=0;try{CatalogClient.parse(new byte[CatalogClient.MAX_FEED_BYTES+1],"https://example.org/opds");}catch(Exception expected){rejected++;}
            StringBuilder xml=new StringBuilder();for(int i=0;i<65;i++)xml.append("<x>");for(int i=0;i<65;i++)xml.append("</x>");try{parse(xml.toString());}catch(Exception expected){rejected++;}require(rejected==2);
        });
        test("No executable webpage treated as OPDS",()->{boolean rejected=false;try{parse("<html><body><script>text</script></body></html>");}catch(Exception expected){rejected=true;}require(rejected);});
        System.out.println("PASS: "+passed+" catalogue protocol checks (Atom path; OPDS2 JSON requires Android runtime)");
    }
}
