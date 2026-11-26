import org.openleaf.reader.core.FuzzySearch;
import java.util.List;

/** 容错搜索回归：西文错字/转置、中文 bigram 召回、排序与稀疏门控。 */
public final class FuzzySearchTest {
    private static int passed;

    public static void main(String[] args) {
        tokens();
        exactMatch();
        latinTypo();
        chineseBigram();
        ranking();
        noMatch();
        immutable();
        System.out.println("PASS: " + passed + " fuzzy search checks");
    }

    private static void tokens() {
        eq("[hello, world]", FuzzySearch.tokenize("Hello, World!").toString());
        eq("[cafe]", FuzzySearch.tokenize("Café").toString());
        check(FuzzySearch.tokenize("中文").contains("中文"), "中文 bigram");
        check(FuzzySearch.tokenize("中文").contains("中"), "中文单字");
        check(FuzzySearch.tokenize("中文").contains("文"), "中文单字2");
        passed++;
    }

    private static void exactMatch() {
        FuzzySearch.Index index = FuzzySearch.index(
                new FuzzySearch.Document("1", "三体", "刘慈欣", ""),
                new FuzzySearch.Document("2", "The Hobbit", "Tolkien", ""));
        List<FuzzySearch.Hit> hits = FuzzySearch.search(index, "三体", 5);
        check(!hits.isEmpty() && "1".equals(hits.get(0).document.id), "中文精确命中");
        hits = FuzzySearch.search(index, "hobbit", 5);
        check(!hits.isEmpty() && "2".equals(hits.get(0).document.id), "西文精确命中");
        passed++;
    }

    private static void latinTypo() {
        FuzzySearch.Index index = FuzzySearch.index(
                new FuzzySearch.Document("hobbit", "The Hobbit", "J R R Tolkien", ""),
                new FuzzySearch.Document("dune", "Dune", "Frank Herbert", ""),
                new FuzzySearch.Document("lotr", "The Lord of the Rings", "Tolkien", ""));
        check(contains(index, "hobbitt", "hobbit"), "尾部多字母 hobbitt→Hobbit");
        check(contains(index, "hobit", "hobbit"), "少字母 hobit→Hobbit");
        check(contains(index, "teh", "hobbit"), "相邻转置 teh→the(Hobbit 的 the)");
        passed++;
    }

    private static void chineseBigram() {
        FuzzySearch.Index index = FuzzySearch.index(
                new FuzzySearch.Document("1", "三体全集", "刘慈欣", ""),
                new FuzzySearch.Document("2", "红楼梦", "曹雪芹", ""));
        // 错一字：三体→山体，bigram "体" 仍命中
        check(contains(index, "山体", "1"), "中文错一字召回");
        check(contains(index, "红楼", "2"), "中文 bigram 精确");
        passed++;
    }

    private static void ranking() {
        FuzzySearch.Index index = FuzzySearch.index(
                new FuzzySearch.Document("title", "Shelf", "Someone", ""),
                new FuzzySearch.Document("author", "Other Book", "Shelf Author", ""));
        List<FuzzySearch.Hit> byRelevance = FuzzySearch.search(index, "shelf", 5, FuzzySearch.Sort.RELEVANCE);
        check(byRelevance.size() == 2, "标题与作者都应命中");
        // 标题字段权重 1.0 > 作者字段 0.85，第一命中应来自标题字段
        check("title".equals(byRelevance.get(0).document.id), "标题命中应排在作者命中之前");
        List<FuzzySearch.Hit> byNewest = FuzzySearch.search(index, "shelf", 5, FuzzySearch.Sort.NEWEST);
        check(!byNewest.isEmpty() && "author".equals(byNewest.get(0).document.id), "NEWEST 按加入顺序倒序");
        passed++;
    }

    private static void noMatch() {
        FuzzySearch.Index index = FuzzySearch.index(new FuzzySearch.Document("1", "Dune", "Herbert", ""));
        check(FuzzySearch.search(index, "", 5).isEmpty(), "空查询");
        check(FuzzySearch.search(index, "completely unrelated words here", 5).isEmpty(), "无关查询不召回");
        check(FuzzySearch.search(null, "dune", 5).isEmpty(), "null 索引");
        passed++;
    }

    private static void immutable() {
        FuzzySearch.Index index = FuzzySearch.index(new FuzzySearch.Document("1", "Dune", "Herbert", ""));
        List<FuzzySearch.Hit> hits = FuzzySearch.search(index, "dune", 5);
        hits.clear();
        // 修改返回结果不应影响后续查询（结果列表与索引解耦）
        check(!FuzzySearch.search(index, "dune", 5).isEmpty(), "结果列表与索引解耦");
        passed++;
    }

    private static boolean contains(FuzzySearch.Index index, String query, String docId) {
        for (FuzzySearch.Hit hit : FuzzySearch.search(index, query, 20))
            if (docId.equals(hit.document.id)) return true;
        return false;
    }

    private static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
