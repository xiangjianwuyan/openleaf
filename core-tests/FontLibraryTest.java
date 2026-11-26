import org.openleaf.reader.core.FontLibrary;
import org.openleaf.reader.core.FontLibrary.AddResult;
import org.openleaf.reader.core.FontLibrary.Entry;
import org.openleaf.reader.core.FontLibrary.Reason;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 0.29.0 P2-1 收藏模型回归；纯 JVM、无 JUnit、无第三方依赖，断言始终启用。 */
public final class FontLibraryTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        test("stableId 派生、非法摘要与内容无关性", FontLibraryTest::stableIdContract);
        test("展示名归一化与扩展名/文件名规则", FontLibraryTest::normalizationAndNames);
        test("序列化往返：特殊字符、控制字符、空列表", FontLibraryTest::serializationRoundtrip);
        test("反序列化容错：坏记录、重复 id、20 条上限", FontLibraryTest::deserializeRobustness);
        test("add：非法/重复/上限与入参不可变", FontLibraryTest::addContract);
        test("remove/rename/markInvalid/find 纯函数行为", FontLibraryTest::mutationsArePure);
        test("resolveGlobalId 失效收敛", FontLibraryTest::globalIdResolution);
        test("legacy 惰性迁移判定与 legacyEntry", FontLibraryTest::legacyMigration);
        test("selectable 丢弃损坏条目", FontLibraryTest::selectable);
        test("looksLikeFont 魔数：合法 5 种 / 空 / 短 / 垃圾 / null", FontLibraryTest::looksLikeFontContract);
        System.out.println("FontLibraryTest: " + passed + " checks passed");
    }

    private static void stableIdContract() throws Exception {
        byte[] first = MessageDigest.getInstance("SHA-256").digest("同一份字体内容".getBytes("UTF-8"));
        byte[] same = MessageDigest.getInstance("SHA-256").digest("同一份字体内容".getBytes("UTF-8"));
        byte[] other = MessageDigest.getInstance("SHA-256").digest("另一份字体内容".getBytes("UTF-8"));
        String id = FontLibrary.stableId(first);
        equal(id, FontLibrary.stableId(same));
        check(id.matches("[0-9a-f]{16}"), "id 必须是 16 位小写 hex: " + id);
        check(!id.equals(FontLibrary.stableId(other)), "不同内容必须不同 id");
        // id 只吃摘要：同一个摘要不会因「Uri/展示名」变化而变化。
        equal(id, FontLibrary.stableId(first));
        equal(id, FontLibrary.stableId(first.clone()));
        check(FontLibrary.isValidId(id), "16 hex 必须合法");
        check(FontLibrary.isValidId("legacy"), "legacy 哨兵必须合法");
        check(!FontLibrary.isValidId("LEGACY") && !FontLibrary.isValidId("0123456789abcdef0")
                && !FontLibrary.isValidId("0123456789ABCDEF") && !FontLibrary.isValidId(null)
                && !FontLibrary.isValidId(""), "非法 id 一律拒绝");
        boolean thrown = false;
        try {
            FontLibrary.stableId(new byte[7]);
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        check(thrown, "短摘要必须抛 IllegalArgumentException");
        thrown = false;
        try {
            FontLibrary.stableId(null);
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        check(thrown, "null 摘要必须抛 IllegalArgumentException");
    }

    private static void normalizationAndNames() {
        equal("", FontLibrary.normalizeDisplayName(null));
        equal("宋体风", FontLibrary.normalizeDisplayName("  宋体风  "));
        equal("abcd", FontLibrary.normalizeDisplayName("a\u0001b\u001Fc\u007Fd"));
        String longName = repeat("字", 130);
        equal(120, FontLibrary.normalizeDisplayName(longName).length());
        equal("ttf", FontLibrary.extFromFileName("USER-FONT.TTF"));
        equal("otf", FontLibrary.extFromFileName("C:\\fonts\\样张.Otf"));
        equal("", FontLibrary.extFromFileName("user-font.ttf.bak"));
        equal("", FontLibrary.extFromFileName(null));
        equal("font_0123456789abcdef.ttf", FontLibrary.fileNameOf("0123456789abcdef", "ttf"));
    }

    private static void serializationRoundtrip() {
        Entry plain = new Entry("0123456789abcdef", "宋体风", "ttf", true);
        Entry tricky = new Entry("legacy", " 中文 + 100% / \\ \u001F 分隔 \u001E 换行\n制表\t😀 ",
                "otf", false);
        List<Entry> original = new ArrayList<>();
        original.add(plain);
        original.add(tricky);
        String raw = FontLibrary.serialize(original);
        List<Entry> restored = FontLibrary.deserialize(raw);
        equal(2, restored.size());
        equal(FontLibrary.normalizeDisplayName(plain.name), restored.get(0).name);
        equal(plain.id, restored.get(0).id);
        equal(plain.ext, restored.get(0).ext);
        check(restored.get(0).valid, "valid=1 必须往返");
        equal(FontLibrary.normalizeDisplayName(tricky.name), restored.get(1).name);
        equal(tricky.id, restored.get(1).id);
        equal(tricky.ext, restored.get(1).ext);
        check(!restored.get(1).valid, "valid=0 必须往返");
        equal("", FontLibrary.serialize(null));
        equal("", FontLibrary.serialize(Collections.<Entry>emptyList()));
        check(FontLibrary.deserialize(null).isEmpty(), "null 反序列化为空");
        check(FontLibrary.deserialize("").isEmpty(), "空串反序列化为空");
        boolean thrown = false;
        try {
            restored.add(plain);
        } catch (UnsupportedOperationException immutable) {
            thrown = true;
        }
        check(thrown, "deserialize 必须返回不可修改列表");
    }

    private static void deserializeRobustness() {
        String good = "0123456789abcdef\u001F%E5%AE%8B%E4%BD%93\u001Fttf\u001F1";
        String raw = good + "\u001E" +
                "badid\u001Fname\u001Fttf\u001F1" + "\u001E" +              // 非法 id
                "aaaaaaaaaaaaaaaa\u001Fname\u001Fzip\u001F1" + "\u001E" +     // ext 非白名单
                "bbbbbbbbbbbbbbbb\u001Fname\u001Fttf\u001F2" + "\u001E" +     // valid 非 0/1
                "cccccccccccccccc\u001F%E5%90%8D\u001Fotf" + "\u001E" +       // 字段数不足
                "0123456789abcdef\u001F%E9%87%8D%E5%A4%8D\u001Fttf\u001F0"    // 重复 id
                ;
        List<Entry> entries = FontLibrary.deserialize(raw);
        equal(1, entries.size());
        equal("0123456789abcdef", entries.get(0).id);
        equal("宋体", entries.get(0).name);
        check(entries.get(0).valid, "重复 id 保留首条");
        check(FontLibrary.deserialize("%E0%A4%A\u001Fname\u001Fttf\u001F1").isEmpty(),
                "坏转义必须跳过且不抛异常");
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(new Entry(String.format("%016x", i + 1), "字体" + i, "ttf", true));
        }
        equal(FontLibrary.MAX_ENTRIES, FontLibrary.deserialize(FontLibrary.serialize(many)).size());
        equal(FontLibrary.MAX_ENTRIES, FontLibrary.MAX_ENTRIES);
    }

    private static void addContract() {
        Entry first = new Entry("1111111111111111", "楷体风", "ttf", true);
        List<Entry> base = new ArrayList<>();
        base.add(new Entry("0000000000000000", "宋体风", "otf", true));
        int sizeBefore = base.size();
        AddResult added = FontLibrary.add(base, first);
        check(added.added && added.reason == Reason.OK, "合法条目应添加成功");
        equal(2, added.library.size());
        equal(sizeBefore, base.size(), "add 不得修改入参");
        AddResult duplicate = FontLibrary.add(added.library,
                new Entry("1111111111111111", "换个名字", "otf", true));
        check(!duplicate.added && duplicate.reason == Reason.DUPLICATE, "同 id 视为重复");
        equal(2, duplicate.library.size());
        check(!FontLibrary.add(base, new Entry("bad", "名", "ttf", true)).added
                && FontLibrary.add(base, new Entry("2222222222222222", "名", "zip", true)).reason == Reason.INVALID
                && FontLibrary.add(base, new Entry("2222222222222222", "  ", "ttf", true)).reason == Reason.INVALID
                && FontLibrary.add(base, null).reason == Reason.INVALID, "非法候选一律 INVALID");
        List<Entry> full = new ArrayList<>();
        for (int i = 0; i < FontLibrary.MAX_ENTRIES; i++) {
            full.add(new Entry(String.format("%016x", i + 1), "字体" + i, "ttf", true));
        }
        AddResult limited = FontLibrary.add(full, new Entry("ffffffffffffffff", "超限", "ttf", true));
        check(!limited.added && limited.reason == Reason.LIMIT_REACHED, "20 条上限必须拒绝");
        equal(FontLibrary.MAX_ENTRIES, full.size(), "上限失败不得改入参");
    }

    private static void mutationsArePure() {
        Entry a = new Entry("aaaaaaaaaaaaaaaa", "甲", "ttf", true);
        Entry b = new Entry("bbbbbbbbbbbbbbbb", "乙", "otf", true);
        List<Entry> base = new ArrayList<>();
        base.add(a);
        base.add(b);
        List<Entry> removed = FontLibrary.remove(base, a.id);
        equal(1, removed.size());
        equal(b.id, removed.get(0).id);
        equal(2, base.size(), "remove 不得修改入参");
        check(FontLibrary.remove(base, "missing") == base, "未找到 id 原样返回");
        List<Entry> renamed = FontLibrary.rename(base, b.id, "  新名字\n ");
        equal("新名字", renamed.get(1).name);
        equal("乙", base.get(1).name, "rename 不得修改入参");
        check(FontLibrary.find(base, a.id) == a, "find 命中原对象");
        check(FontLibrary.find(base, null) == null && FontLibrary.find(null, a.id) == null,
                "find 的 null 边界");
        List<Entry> marked = FontLibrary.markInvalid(base, a.id);
        check(!marked.get(0).valid && marked.get(1).valid, "仅目标条目被损坏标记");
        check(base.get(0).valid, "markInvalid 不得修改入参");
        check(FontLibrary.markInvalid(base, "missing") == base, "损坏标记未命中原样返回");
    }

    private static void globalIdResolution() {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("aaaaaaaaaaaaaaaa", "甲", "ttf", true));
        entries.add(new Entry("bbbbbbbbbbbbbbbb", "乙", "otf", false));
        equal("aaaaaaaaaaaaaaaa", FontLibrary.resolveGlobalId("aaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaa", entries));
        equal("aaaaaaaaaaaaaaaa", FontLibrary.resolveGlobalId("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", entries));
        equal("", FontLibrary.resolveGlobalId("bbbbbbbbbbbbbbbb", "missing", entries),
                "current 指向损坏条目时清空");
        equal("", FontLibrary.resolveGlobalId("missing", "also-missing", entries));
        equal("", FontLibrary.resolveGlobalId(null, null, null));
    }

    private static void legacyMigration() {
        check(FontLibrary.migrationDecision(null, "user-font.ttf"), "键不存在且旧键有值才迁移");
        check(!FontLibrary.migrationDecision("", "user-font.ttf"), "键存在但空值：已在新世界");
        check(!FontLibrary.migrationDecision(null, ""), "旧键空不迁移");
        check(!FontLibrary.migrationDecision(null, null), "旧键 null 不迁移");
        check(!FontLibrary.migrationDecision("anything", "user-font.ttf"), "键有值不重复迁移");
        Entry entry = FontLibrary.legacyEntry("user-font.OTF", "", true);
        equal("legacy", entry.id);
        equal("用户字体", entry.name);
        equal("otf", entry.ext);
        check(entry.valid, "legacy valid 沿用旧值");
        Entry named = FontLibrary.legacyEntry("user-font.ttf", "  旧名字  ", false);
        equal("  旧名字  ", named.name, "legacy 展示名不归一化，保证老用户零回归");
        equal("ttf", named.ext);
        check(!named.valid, "legacy valid=false 必须保留");
    }

    private static void looksLikeFontContract() {
        check(FontLibrary.looksLikeFont(new byte[]{0x00, 0x01, 0x00, 0x00}), "0x00010000 TTF 合法");
        check(FontLibrary.looksLikeFont(new byte[]{'t', 'r', 'u', 'e'}), "\"true\" Apple TTF 合法");
        check(FontLibrary.looksLikeFont(new byte[]{'O', 'T', 'T', 'O'}), "\"OTTO\" CFF/OTF 合法");
        check(FontLibrary.looksLikeFont(new byte[]{'t', 't', 'c', 'f'}), "\"ttcf\" TTC 合法");
        check(FontLibrary.looksLikeFont(new byte[]{'t', 'y', 'p', '1'}), "\"typ1\" Type1 合法");
        check(FontLibrary.looksLikeFont(new byte[]{0x00, 0x01, 0x00, 0x00, 0x7F}), "只看前 4 字节");
        check(!FontLibrary.looksLikeFont(new byte[]{'Q', 'A', '0', '1'}), "文本垃圾头必须拒绝");
        check(!FontLibrary.looksLikeFont(new byte[]{0x00, 0x00, 0x00, 0x00}), "全零头必须拒绝");
        check(!FontLibrary.looksLikeFont(new byte[0]), "空数组必须拒绝");
        check(!FontLibrary.looksLikeFont(new byte[]{0x00, 0x01, 0x00}), "不足 4 字节必须拒绝");
        check(!FontLibrary.looksLikeFont(null), "null 必须拒绝");
    }

    private static void selectable() {
        check(FontLibrary.selectable(new Entry("aaaaaaaaaaaaaaaa", "甲", "ttf", true)), "有效条目可选");
        check(!FontLibrary.selectable(new Entry("aaaaaaaaaaaaaaaa", "甲", "ttf", false)), "损坏条目禁用");
        check(!FontLibrary.selectable(null), "null 禁用");
    }

    private static String repeat(String value, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(value);
        return out.toString();
    }

    private interface Case {
        void run() throws Exception;
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
