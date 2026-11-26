import org.openleaf.reader.core.FontLibrary;
import org.openleaf.reader.core.FontLibrary.Entry;
import org.openleaf.reader.core.FontResolver;
import org.openleaf.reader.core.FontResolver.Kind;
import org.openleaf.reader.core.FontResolver.Result;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 0.29.0 P2-2 优先级矩阵与零回归回归；纯 JVM、无 JUnit、断言始终启用。 */
public final class FontResolutionTest {
    private static int passed;

    public static void main(String[] args) {
        test("R0 MODE_SYSTEM 绝对短路（含非法 mode clamp）", FontResolutionTest::systemShortCircuit);
        test("R1 按书有效字体最高优先（含 legacy）", FontResolutionTest::bookWins);
        test("R2/R3 按书失效清键并回落全局", FontResolutionTest::invalidBookFallsBack);
        test("R4 legacy 双键回退（library 空与 valid legacy 条目）", FontResolutionTest::legacyFallback);
        test("R5/R6 MODE_USER 与 MODE_BUILTIN 降级", FontResolutionTest::builtinFallback);
        test("ZR5 无按书键时与 0.27.0 三态逐字一致", FontResolutionTest::zeroRegressionThreeState);
        test("Map 重载按 bookId 取键", FontResolutionTest::mapOverload);
        test("ZR6 清除按书键后与从未设置一致", FontResolutionTest::clearedBookKey);
        test("collectBookKeysReferencing 精确、字典序、不误删", FontResolutionTest::referenceScan);
        System.out.println("FontResolutionTest: " + passed + " checks passed");
    }

    private static void systemShortCircuit() {
        List<Entry> library = new ArrayList<>();
        library.add(entry("aaaaaaaaaaaaaaaa", true));
        library.add(FontLibrary.legacyEntry("user-font.ttf", "旧字体", true));
        for (int mode : new int[]{FontResolver.MODE_SYSTEM, -1, 3, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            Result r = FontResolver.resolveEffective(mode, "aaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaa",
                    library, true);
            equal(new Result(Kind.SYSTEM, null, false), r);
            check(r.fontId == null, "SYSTEM 必须 null fontId");
            check(!r.clearBookKey, "SYSTEM 不得清按书键");
        }
        Map<String, String> prefs = new LinkedHashMap<>();
        prefs.put(FontResolver.bookKeyName("book-A"), "aaaaaaaaaaaaaaaa");
        equal(new Result(Kind.SYSTEM, null, false), FontResolver.resolveEffective(
                FontResolver.MODE_SYSTEM, "aaaaaaaaaaaaaaaa", "book-A", prefs, library, true));
        equal("aaaaaaaaaaaaaaaa", prefs.get(FontResolver.bookKeyName("book-A")), "解析不得修改 prefs");
    }

    private static void bookWins() {
        List<Entry> library = new ArrayList<>();
        library.add(entry("aaaaaaaaaaaaaaaa", true));
        library.add(entry("bbbbbbbbbbbbbbbb", true));
        Result r = FontResolver.resolveEffective(FontResolver.MODE_USER, "aaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbb", library, false);
        equal(new Result(Kind.FONT, "bbbbbbbbbbbbbbbb", false), r);
        Result builtinMode = FontResolver.resolveEffective(FontResolver.MODE_BUILTIN, "aaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbb", library, false);
        equal(new Result(Kind.FONT, "bbbbbbbbbbbbbbbb", false), builtinMode);
        List<Entry> withLegacy = new ArrayList<>();
        withLegacy.add(FontLibrary.legacyEntry("user-font.otf", "旧字体", true));
        equal(new Result(Kind.FONT, FontLibrary.LEGACY_ID, false),
                FontResolver.resolveEffective(FontResolver.MODE_USER, "", FontLibrary.LEGACY_ID,
                        withLegacy, true));
        // 按书键是 legacy 但库为空且旧文件不可用时不命中 R1，标记清键后回落。
        equal(new Result(Kind.BUILTIN, null, true),
                FontResolver.resolveEffective(FontResolver.MODE_USER, "", FontLibrary.LEGACY_ID,
                        Collections.<Entry>emptyList(), false));
    }

    private static void invalidBookFallsBack() {
        List<Entry> library = new ArrayList<>();
        library.add(entry("aaaaaaaaaaaaaaaa", true));
        library.add(entry("cccccccccccccccc", false));
        Result deleted = FontResolver.resolveEffective(FontResolver.MODE_USER, "aaaaaaaaaaaaaaaa",
                "dddddddddddddddd", library, false);
        equal(new Result(Kind.FONT, "aaaaaaaaaaaaaaaa", true), deleted);
        Result invalid = FontResolver.resolveEffective(FontResolver.MODE_USER, "aaaaaaaaaaaaaaaa",
                "cccccccccccccccc", library, false);
        equal(new Result(Kind.FONT, "aaaaaaaaaaaaaaaa", true), invalid);
        Result noGlobal = FontResolver.resolveEffective(FontResolver.MODE_USER, "",
                "cccccccccccccccc", library, false);
        equal(new Result(Kind.BUILTIN, null, true), noGlobal);
        Result emptyBookKey = FontResolver.resolveEffective(FontResolver.MODE_USER,
                "aaaaaaaaaaaaaaaa", "", library, false);
        equal(new Result(Kind.FONT, "aaaaaaaaaaaaaaaa", false), emptyBookKey);
    }

    private static void legacyFallback() {
        Result emptyLibrary = FontResolver.resolveEffective(FontResolver.MODE_USER, "", "",
                Collections.<Entry>emptyList(), true);
        equal(new Result(Kind.FONT, FontLibrary.LEGACY_ID, false), emptyLibrary);
        Result nullLibrary = FontResolver.resolveEffective(FontResolver.MODE_USER, null, null, null, true);
        equal(new Result(Kind.FONT, FontLibrary.LEGACY_ID, false), nullLibrary);
        List<Entry> withValidLegacy = new ArrayList<>();
        withValidLegacy.add(entry("aaaaaaaaaaaaaaaa", true));
        withValidLegacy.add(FontLibrary.legacyEntry("user-font.ttf", "旧字体", true));
        equal(new Result(Kind.FONT, FontLibrary.LEGACY_ID, false),
                FontResolver.resolveEffective(FontResolver.MODE_USER, "", "", withValidLegacy, true));
        List<Entry> invalidLegacy = new ArrayList<>();
        invalidLegacy.add(entry("aaaaaaaaaaaaaaaa", true));
        invalidLegacy.add(FontLibrary.legacyEntry("user-font.ttf", "旧字体", false));
        equal(new Result(Kind.FONT, "aaaaaaaaaaaaaaaa", false),
                FontResolver.resolveEffective(FontResolver.MODE_USER, "aaaaaaaaaaaaaaaa", "",
                        invalidLegacy, true));
        List<Entry> legacyOnlyInvalid = new ArrayList<>();
        legacyOnlyInvalid.add(FontLibrary.legacyEntry("user-font.ttf", "旧字体", false));
        equal(new Result(Kind.BUILTIN, null, false),
                FontResolver.resolveEffective(FontResolver.MODE_USER, "", "", legacyOnlyInvalid, true));
    }

    private static void builtinFallback() {
        equal(new Result(Kind.BUILTIN, null, false), FontResolver.resolveEffective(
                FontResolver.MODE_BUILTIN, "aaaaaaaaaaaaaaaa", "", null, true));
        equal(new Result(Kind.BUILTIN, null, false), FontResolver.resolveEffective(
                FontResolver.MODE_USER, "", "", null, false));
        List<Entry> library = new ArrayList<>();
        library.add(entry("cccccccccccccccc", false));
        equal(new Result(Kind.BUILTIN, null, false), FontResolver.resolveEffective(
                FontResolver.MODE_USER, "cccccccccccccccc", "", library, false));
    }

    private static void zeroRegressionThreeState() {
        // 0.27.0：SYSTEM → null；BUILTIN → DEFAULT；USER 有旧文件 → 旧字体文件；否则 → DEFAULT。
        equal(new Result(Kind.SYSTEM, null, false), FontResolver.resolveEffective(
                FontResolver.MODE_SYSTEM, "", "", null, false));
        equal(new Result(Kind.BUILTIN, null, false), FontResolver.resolveEffective(
                FontResolver.MODE_BUILTIN, "", "", null, false));
        equal(new Result(Kind.BUILTIN, null, false), FontResolver.resolveEffective(
                FontResolver.MODE_USER, "", "", null, false));
        equal(new Result(Kind.FONT, FontLibrary.LEGACY_ID, false), FontResolver.resolveEffective(
                FontResolver.MODE_USER, "", "", null, true));
        List<Entry> library = new ArrayList<>();
        library.add(entry("aaaaaaaaaaaaaaaa", true));
        equal(new Result(Kind.FONT, "aaaaaaaaaaaaaaaa", false), FontResolver.resolveEffective(
                FontResolver.MODE_USER, "aaaaaaaaaaaaaaaa", "", library, false));
        equal(new Result(Kind.SYSTEM, null, false), FontResolver.resolveEffective(
                FontResolver.MODE_SYSTEM, "aaaaaaaaaaaaaaaa", "", library, true));
    }

    private static void mapOverload() {
        Map<String, String> prefs = new LinkedHashMap<>();
        prefs.put(FontResolver.bookKeyName("book-A"), "aaaaaaaaaaaaaaaa");
        prefs.put(FontResolver.bookKeyName("book-B"), "bbbbbbbbbbbbbbbb");
        prefs.put("text_encoding_book-A", "UTF-8");
        List<Entry> library = new ArrayList<>();
        library.add(entry("aaaaaaaaaaaaaaaa", true));
        library.add(entry("bbbbbbbbbbbbbbbb", true));
        Result a = FontResolver.resolveEffective(FontResolver.MODE_USER, "bbbbbbbbbbbbbbbb",
                "book-A", prefs, library, false);
        equal(new Result(Kind.FONT, "aaaaaaaaaaaaaaaa", false), a);
        Result b = FontResolver.resolveEffective(FontResolver.MODE_USER, "aaaaaaaaaaaaaaaa",
                "book-B", prefs, library, false);
        equal(new Result(Kind.FONT, "bbbbbbbbbbbbbbbb", false), b);
        Result absent = FontResolver.resolveEffective(FontResolver.MODE_USER, "aaaaaaaaaaaaaaaa",
                "book-Z", prefs, library, false);
        equal(new Result(Kind.FONT, "aaaaaaaaaaaaaaaa", false), absent);
        Result nullId = FontResolver.resolveEffective(FontResolver.MODE_USER, "aaaaaaaaaaaaaaaa",
                null, prefs, library, false);
        equal(new Result(Kind.FONT, "aaaaaaaaaaaaaaaa", false), nullId);
        equal("font_book_book-A", FontResolver.bookKeyName("book-A"));
        equal("font_book_", FontResolver.bookKeyName(null));
    }

    private static void clearedBookKey() {
        List<Entry> library = new ArrayList<>();
        library.add(entry("aaaaaaaaaaaaaaaa", true));
        Map<String, String> prefs = new LinkedHashMap<>();
        prefs.put(FontResolver.bookKeyName("book-A"), "missing");
        Result before = FontResolver.resolveEffective(FontResolver.MODE_USER, "aaaaaaaaaaaaaaaa",
                "book-A", prefs, library, false);
        check(before.clearBookKey, "失效按书键必须要求清键");
        prefs.remove(FontResolver.bookKeyName("book-A"));
        Result after = FontResolver.resolveEffective(FontResolver.MODE_USER, "aaaaaaaaaaaaaaaa",
                "book-A", prefs, library, false);
        Result never = FontResolver.resolveEffective(FontResolver.MODE_USER, "aaaaaaaaaaaaaaaa",
                "", library, false);
        equal(never, after);
        check(!after.clearBookKey, "清除后不再清键");
    }

    private static void referenceScan() {
        Map<String, String> prefs = new LinkedHashMap<>();
        prefs.put(FontResolver.bookKeyName("book-Z"), "bbbbbbbbbbbbbbbb");
        prefs.put(FontResolver.bookKeyName("book-A"), "aaaaaaaaaaaaaaaa");
        prefs.put(FontResolver.bookKeyName("book-B"), "aaaaaaaaaaaaaaaa");
        prefs.put("font_global_id", "aaaaaaaaaaaaaaaa");
        prefs.put("font_library", "raw");
        prefs.put("text_encoding_book-A", "aaaaaaaaaaaaaaaa");
        List<String> refs = FontResolver.collectBookKeysReferencing(prefs, "aaaaaaaaaaaaaaaa");
        equal(2, refs.size());
        equal("font_book_book-A", refs.get(0));
        equal("font_book_book-B", refs.get(1));
        equal(6, prefs.size(), "扫描不得修改入参 Map");
        equal(Collections.singletonList("font_book_book-Z"),
                FontResolver.collectBookKeysReferencing(prefs, "bbbbbbbbbbbbbbbb"));
        check(FontResolver.collectBookKeysReferencing(prefs, "missing").isEmpty(), "无引用返回空");
        check(FontResolver.collectBookKeysReferencing(null, "aaaaaaaaaaaaaaaa").isEmpty(), "null Map 返回空");
        check(FontResolver.collectBookKeysReferencing(prefs, null).isEmpty(), "null id 返回空");
        check(FontResolver.collectBookKeysReferencing(prefs, "").isEmpty(), "空 id 返回空");
    }

    private static Entry entry(String id, boolean valid) {
        return new Entry(id, "字体", "ttf", valid);
    }

    private interface Case {
        void run();
    }

    private static void test(String name, Case body) {
        try {
            body.run();
            passed++;
        } catch (Throwable failure) {
            throw new IllegalStateException("FAIL " + name + ": " + failure, failure);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void equal(Object expected, Object actual) {
        equal(expected, actual, "值不相等");
    }

    private static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(message + "：expected=" + expected + " actual=" + actual);
        }
    }
}
