import org.openleaf.reader.core.EpubCfi;

/** EPUB 简化 CFI 核心测试（纯 JVM）。 */
public class EpubCfiTest {
    static int passed = 0;
    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); passed++; }

    public static void main(String[] args) {
        roundTrip();
        simpleForm();
        rejectBad();
        versionGuard();
        ordering();
        fallback();
        pathDepth();
        System.out.println("PASS EpubCfiTest (" + passed + " checks)");
    }

    static void roundTrip() {
        String cfi = EpubCfi.create(3, new int[]{2, 1, 4}, 128);
        EpubCfi.Location loc = EpubCfi.parse(cfi);
        check(loc != null, "合法CFI应可解析");
        check(loc.spineIndex == 3, "spine应正确");
        check(loc.offset == 128, "偏移应正确");
        check("2/1/4".equals(loc.pathString()), "结构路径应正确");
    }

    static void simpleForm() {
        String cfi = EpubCfi.createSimple(0, 50);
        EpubCfi.Location loc = EpubCfi.parse(cfi);
        check(loc != null && loc.spineIndex == 0 && loc.offset == 50, "简化形式应可解析");
    }

    static void rejectBad() {
        check(EpubCfi.parse(null) == null, "null应拒绝");
        check(EpubCfi.parse("") == null, "空串应拒绝");
        check(EpubCfi.parse("garbage") == null, "乱串应拒绝");
        check(EpubCfi.parse("ocfcfi:v1:s-1/0/0") == null, "负spine应拒绝");
        check(EpubCfi.parse("ocfcfi:v1:s0/0/-5") == null, "负偏移应拒绝");
        check(EpubCfi.parse("ocfcfi:v1:s0/a/0") == null, "非数字路径应拒绝");
        check(EpubCfi.parse("othercfi:v1:s0/0/0") == null, "错误scheme应拒绝");
    }

    static void versionGuard() {
        check(EpubCfi.parse("ocfcfi:v99:s0/0/0") == null, "未知版本应拒绝");
        check(EpubCfi.create(-1, new int[]{0}, 0).isEmpty(), "负spine生成应返回空");
        check(EpubCfi.create(0, new int[]{0}, -1).isEmpty(), "负偏移生成应返回空");
    }

    static void ordering() {
        String a = EpubCfi.create(0, new int[]{1}, 10);
        String b = EpubCfi.create(0, new int[]{1}, 20);
        String c = EpubCfi.create(1, new int[]{0}, 0);
        check(EpubCfi.compare(a, b) < 0, "同章偏移小的在前");
        check(EpubCfi.compare(b, c) < 0, "spine小的在前");
        check(EpubCfi.compare(a, a) == 0, "相同位置相等");
        check(EpubCfi.compare("bad", a) == 0, "非法CFI比较返回相等（安全默认）");
    }

    static void fallback() {
        EpubCfi.Location f1 = EpubCfi.fallback("bad", 5);
        check(f1.spineIndex == 0 && f1.offset == 0, "非法CFI回退到首章");
        EpubCfi.Location f2 = EpubCfi.fallback(EpubCfi.create(9, new int[]{1}, 5), 3);
        check(f2.spineIndex == 2, "越界spine回退到末章");
        check(EpubCfi.fallback(null, 0).spineIndex == 0, "零章节安全回退");
    }

    static void pathDepth() {
        int[] deep = new int[50];
        for (int i = 0; i < deep.length; i++) deep[i] = i;
        String cfi = EpubCfi.create(0, deep, 0);
        EpubCfi.Location loc = EpubCfi.parse(cfi);
        check(loc != null && loc.path.length <= 32, "超深路径应被限界");
        check(EpubCfi.shortText(cfi).contains("第1节"), "摘要应含spine信息");
        check(EpubCfi.shortText("bad").contains("无效"), "非法摘要应有提示");
    }
}
