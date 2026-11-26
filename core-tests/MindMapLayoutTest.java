import org.openleaf.reader.core.MindMapLayout;
import org.openleaf.reader.core.MindMapLayout.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 导图树形布局回归：稳定序 / 层级与兄弟间距 / 折叠压缩 / 200 上限 / 环防护；无第三方依赖。 */
public final class MindMapLayoutTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        test("空 / null 输入 → 空列表", MindMapLayoutTest::empty);
        test("先序稳定序：同级按下标顺序", MindMapLayoutTest::stableOrder);
        test("层级/兄弟间距 + w/h", MindMapLayoutTest::spacing);
        test("折叠节点：后代不输出且 y 被压缩", MindMapLayoutTest::collapse);
        test("200 节点上限截断", MindMapLayoutTest::upperBound);
        test("父链成环不死循环", MindMapLayoutTest::cycle);
        test("visibleCount：任意列表中折叠祖先的后代不可见", MindMapLayoutTest::visibleCount);
        System.out.println("PASS: " + passed + " MindMapLayout checks");
    }

    private static List<Node> sampleTree() {
        // 0 根, 1 根, 2/3 是 0 的孩子, 4 是 1 的孩子
        List<Node> src = new ArrayList<>();
        src.add(new Node("n0", "根一", -1));
        src.add(new Node("n1", "根二", -1));
        src.add(new Node("n2", "子一", 0));
        src.add(new Node("n3", "子二", 0));
        src.add(new Node("n4", "子三", 1));
        return src;
    }

    private static void empty() {
        equal(0, MindMapLayout.layout(null, 0, 0, 100, 30, 80, 40).size());
        equal(0, MindMapLayout.layout(Collections.emptyList(), 0, 0, 100, 30, 80, 40).size());
        equal(0, MindMapLayout.visibleCount(null));
    }

    private static void stableOrder() {
        List<Node> out = MindMapLayout.layout(sampleTree(), 10, 20, 100, 30, 80, 40);
        equal(5, out.size());
        equal("n0,n2,n3,n1,n4", ids(out)); // DFS 先序
        equal(-1, out.get(0).parent);
        equal(0, out.get(1).parent); // n2 的父是 out[0]
        equal(0, out.get(2).parent);
        equal(-1, out.get(3).parent);
        equal(3, out.get(4).parent);
    }

    private static void spacing() {
        List<Node> out = MindMapLayout.layout(sampleTree(), 10, 20, 100, 30, 80, 40);
        // n0: depth0 -> x=10, y=20 ; n2: depth1 -> x=110, y=50 ; n3: x=110,y=80
        // n1: depth0 -> x=10,  y=110 ; n4: depth1 -> x=110, y=140
        equal(10, out.get(0).x); equal(20, out.get(0).y);
        equal(110, out.get(1).x); equal(50, out.get(1).y);
        equal(110, out.get(2).x); equal(80, out.get(2).y);
        equal(10, out.get(3).x); equal(110, out.get(3).y);
        equal(110, out.get(4).x); equal(140, out.get(4).y);
        for (Node n : out) { equal(80, n.w); equal(40, n.h); }
    }

    private static void collapse() {
        List<Node> src = sampleTree();
        src.get(0).collapsed = true; // 折叠根一 → 子一/子二不输出
        List<Node> out = MindMapLayout.layout(src, 10, 20, 100, 30, 80, 40);
        equal("n0,n1,n4", ids(out));
        equal(3, out.size());
        equal(50, out.get(1).y); // 根二被压缩到原本 n2 的位置
        equal(80, out.get(2).y);
        equal(3, MindMapLayout.visibleCount(out)); // 布局输出恒等于 size
    }

    private static void upperBound() {
        List<Node> src = new ArrayList<>();
        for (int i = 0; i < 250; i++) src.add(new Node("n" + i, "节点" + i, -1));
        List<Node> out = MindMapLayout.layout(src, 0, 0, 100, 30, 80, 40);
        equal(MindMapLayout.MAX_NODES, out.size());
        equal("n0", out.get(0).id);
        equal("n199", out.get(out.size() - 1).id);
    }

    private static void cycle() {
        List<Node> src = new ArrayList<>();
        src.add(new Node("a", "A", 1));
        src.add(new Node("b", "B", 0));
        src.add(new Node("c", "C", -1));
        List<Node> out = MindMapLayout.layout(src, 0, 0, 100, 30, 80, 40);
        equal(3, out.size()); // 全部输出且不死循环
        check(out.size() <= MindMapLayout.MAX_NODES, "不应超上限");
    }

    private static void visibleCount() {
        List<Node> list = new ArrayList<>();
        list.add(new Node("root", "根", -1));
        list.get(0).collapsed = true;
        list.add(new Node("child", "子", 0));
        list.add(new Node("grand", "孙", 1));
        list.add(new Node("other", "另一根", -1));
        equal(2, MindMapLayout.visibleCount(list)); // 只有 root 与 other 可见
    }

    private static String ids(List<Node> nodes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(nodes.get(i).id);
        }
        return sb.toString();
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + "; got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void test(String name, ThrowingRunnable action) throws Exception {
        try { action.run(); passed++; System.out.println("OK " + name); }
        catch (Throwable failure) { throw new AssertionError("FAILED: " + name, failure); }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
