package org.openleaf.reader;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.CancellationSignal;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.List;

public final class LegadoInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            JSONObject rules = new JSONObject()
                    .put("bookSourceName", "OpenLeaf fixture").put("bookSourceUrl", "https://books.example/")
                    .put("searchUrl", "/find?q={{key}}&page={{page}}")
                    .put("ruleSearch", new JSONObject().put("bookList", "@css:.book").put("name", "a@text").put("bookUrl", "a@href"))
                    .put("ruleBookInfo", new JSONObject().put("intro", ".intro@text").put("tocUrl", "a.toc@href"))
                    .put("ruleToc", new JSONObject().put("chapterList", "a.chapter").put("chapterName", "text")
                            .put("chapterUrl", "@href").put("nextTocUrl", "a.next@href"))
                    .put("ruleContent", new JSONObject().put("content", "article@text"));
            LegadoClient.Source source = LegadoClient.parseSources(rules.toString()).get(0);
            require(LegadoClient.parseSources(LegadoClient.exportSources(Arrays.asList(source))).get(0).url.equals(source.url));
            LegadoClient client = new LegadoClient((url, signal) -> {
                switch (url) {
                    case "https://books.example/find?q=%E9%9D%92%20%26&page=2": return "<div class=book><a href='/story'>青简样书</a></div>";
                    case "https://books.example/story": return "<div class=intro>测试简介</div><a class=toc href='/toc/first'>目录</a>";
                    case "https://books.example/toc/first": return "<a class=chapter href='../chapter/1'>第一章</a><a class=next href='second'>下一页</a>";
                    case "https://books.example/toc/second": return "<a class=chapter href='../chapter/2'>第二章</a>";
                    case "https://books.example/chapter/2": return "<article><p>正文第一段</p><p>正文第二段</p><script>hidden()</script></article>";
                    default: throw new AssertionError(url);
                }
            });
            LegadoClient.Book book = client.search(source, "青 &", 2, null).get(0);
            book = client.detail(source, book, null);
            require(book.intro.equals("测试简介"));
            List<LegadoClient.Chapter> chapters = client.toc(source, book, null);
            require(chapters.size() == 2 && chapters.get(1).title.equals("第二章"));
            require(client.content(source, chapters.get(1), null).text.equals("正文第一段\n正文第二段"));
            JSONObject jsonRules = new JSONObject(rules.toString()).put("ruleSearch", new JSONObject()
                    .put("bookList", "$.books[*]").put("name", "$.title").put("bookUrl", "$.href"));
            LegadoClient.Source json = new LegadoClient.Source(jsonRules);
            require(new LegadoClient((url, s) -> "{\"books\":[{\"title\":\"JSON 样书\",\"href\":\"/story\"}]}")
                    .search(json, "sample", 1, null).get(0).title.equals("JSON 样书"));
            reject(() -> new LegadoClient.Source(new JSONObject(rules.toString()).put("loginUrl", "@js:test")));
            reject(() -> new LegadoClient.Source(new JSONObject(rules.toString()).put("searchUrl", "http://books.example/search")));
            reject(() -> new LegadoClient.Source(new JSONObject(rules.toString()).put("searchUrl", "/find,{\"method\":\"POST\"}")));
            reject(() -> new LegadoClient((url, s) -> "<div class=book><a href='file:///x'>坏地址</a></div>").search(source, "x", 1, null));
            CancellationSignal cancelled = new CancellationSignal();
            cancelled.cancel();
            reject(() -> client.search(source, "青 &", 2, cancelled));
            result.putString("stream", "\nPASS: Legado JSON round-trip, CSS search/detail/paged TOC/content, JSONPath search, rejected script/HTTP/POST/file URLs, cancellation\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private interface Operation { void run() throws Exception; }
    private static void reject(Operation action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    private static void require(boolean condition) { if (!condition) throw new AssertionError("Unexpected fixture result"); }
}
