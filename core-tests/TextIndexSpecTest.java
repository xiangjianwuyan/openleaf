import org.openleaf.reader.core.PositionSync;
import org.openleaf.reader.core.TextIndexSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/** Q1 指纹口径：长度前缀 + 键排序 + SHA；位置修订号绝不进 spec。 */
public final class TextIndexSpecTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static int passed;

    private static void test(String name, Check body) throws Exception {
        body.run(); passed++; System.out.println("PASS " + name);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        test("same bytes different pref naming: only encoding/rule change specId", () -> {
            // 按书键/全局键只是读取来源不同，派生出的字段相同则 specId 相同。
            TextIndexSpec global = TextIndexSpec.of("book-A", SHA, 0, 0, "");
            TextIndexSpec perBook = TextIndexSpec.of("book-A", SHA, 0, 0, "");
            require(global.specId().equals(perBook.specId()), "same fields must yield same specId");
            require(!TextIndexSpec.of("book-A", SHA, 1, 0, "").specId().equals(global.specId()),
                    "encoding must change specId");
            require(!TextIndexSpec.of("book-A", SHA, 0, 1, "").specId().equals(global.specId()),
                    "ruleMode must change specId");
            require(!TextIndexSpec.of("book-A", SHA, 0, 3, "第[0-9]+章").specId().equals(global.specId()),
                    "custom regex must change specId");
            require(!TextIndexSpec.of("book-B", SHA, 0, 0, "").specId().equals(global.specId()),
                    "bookId must be part of identity");
        });
        test("EDIT-1 sha change invalidates specId", () -> {
            TextIndexSpec before = TextIndexSpec.of("book-A", SHA, 0, 0, "");
            String newSha = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
            TextIndexSpec after = TextIndexSpec.of("book-A", newSha, 0, 0, "");
            require(!before.specId().equals(after.specId()), "sha change must change specId");
            require(before.stale(after) && after.stale(before), "sha change must make both stale");
        });
        test("PositionSync revision churn does not touch specId", () -> {
            TextIndexSpec spec = TextIndexSpec.of("book-A", SHA, 2, 1, "");
            String id = spec.specId();
            String r1 = PositionSync.newRevision();
            String r2 = PositionSync.newRevision();
            require(!r1.equals(r2), "revision must churn");
            require(spec.specId().equals(id), "specId must not follow external position revision");
            require(TextIndexSpec.of("book-A", r1, 2, 1, "").specId().equals(id) == false,
                    "revision is not content identity");
        });
        test("canonical is order-independent and length-prefix disambiguated", () -> {
            Map<String, String> a = new LinkedHashMap<String, String>();
            a.put("bookId", "b"); a.put("contentSha", "c"); a.put("encoding", "0");
            Map<String, String> b = new LinkedHashMap<String, String>();
            b.put("encoding", "0"); b.put("contentSha", "c"); b.put("bookId", "b");
            require(TextIndexSpec.canonical(a).equals(TextIndexSpec.canonical(b)),
                    "field order must not affect canonical");
            Map<String, String> x = new LinkedHashMap<String, String>();
            x.put("a", "ab"); x.put("b", "c");
            Map<String, String> y = new LinkedHashMap<String, String>();
            y.put("a", "a"); y.put("b", "bc");
            require(!TextIndexSpec.canonical(x).equals(TextIndexSpec.canonical(y)),
                    "length prefix must separate a|bc from ab|c");
            require(TextIndexSpec.canonical(a).equals(TextIndexSpec.canonical("b", "c", 0, 0, ""))
                    == false, "different field sets must not collide");
        });
        test("illegal fields clamp and null sha is unprovided, never NPE", () -> {
            TextIndexSpec spec = TextIndexSpec.of(null, null, -9, 99, null);
            require(spec.encoding == 0 && spec.ruleMode == 0, "illegal fields must clamp to 0");
            require("".equals(spec.ruleCustom) && "".equals(spec.bookId), "null strings must normalize");
            require(!spec.hasContentSha(), "null sha must report unprovided");
            require(!spec.matches(null) && spec.stale(null) && !spec.stale(spec), "null match safety");
            TextIndexSpec same = TextIndexSpec.of(null, null, 0, 0, "");
            require(spec.matches(same) && !spec.stale(same), "two unprovided specs still match");
        });
        System.out.println("PASS: " + passed + " text index spec checks");
    }
}
