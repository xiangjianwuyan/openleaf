package org.openleaf.reader.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 章节树 → 带坐标的导图布局。纯 JVM，无 Android 类型，无第三方依赖。
 *
 * <p>输入 {@code src} 中每个 Node 的 {@code parent} 是其在 {@code src} 中的下标（根为 -1）。
 * 输出按深度优先先序排列（稳定序：同级保持输入下标顺序），每个输出 Node 的 {@code parent}
 * 被重编号为「在返回列表中的下标」（-1 表示根，或父节点被折叠隐藏）。折叠节点的后代不进入
 * 返回列表，因此折叠会直接压缩后续兄弟节点的 y 坐标。</p>
 */
public final class MindMapLayout {

    /** 导图节点上限（与 §6 文案 25「章节超过 200，仅展示前 200」一致）。 */
    public static final int MAX_NODES = 200;

    private MindMapLayout() { }

    public static final class Node {
        public final String id;
        public final String title;
        /** 输入时：父节点在 src 中的下标；layout 输出时：父节点在返回列表中的下标；-1 = 根。 */
        public final int parent;
        public int x, y, w, h;
        public boolean collapsed;

        public Node(String id, String title, int parent) {
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.parent = parent;
        }
    }

    /**
     * 稳定的树形分层布局：x = rootX + depth*levelGapX，y = rootY + (可见先序序号)*siblingGapY。
     * 折叠节点的后代被跳过；输出最多 {@link #MAX_NODES} 个节点。负间距按 0 处理，不抛异常。
     */
    public static List<Node> layout(List<Node> src, int rootX, int rootY,
                                    int levelGapX, int siblingGapY, int nodeW, int nodeH) {
        List<Node> out = new ArrayList<>();
        if (src == null || src.isEmpty()) return out;
        int n = src.size();

        // 1) 归一化 parent：越界 / 自引用 / 成环的边一律断开为 -1（防死循环）。
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            int p = src.get(i).parent;
            parent[i] = (p >= 0 && p < n && p != i) ? p : -1;
        }
        for (int i = 0; i < n; i++) {
            boolean[] seen = new boolean[n];
            int cur = i;
            boolean cycle = false;
            while (cur >= 0) {
                if (seen[cur]) { cycle = (cur == i); break; }
                seen[cur] = true;
                cur = parent[cur];
            }
            if (cycle) parent[i] = -1;
        }

        // 2) 建子表（天然按下标升序 = 稳定序）与根表。
        @SuppressWarnings("unchecked")
        List<Integer>[] children = new List[n];
        for (int i = 0; i < n; i++) children[i] = new ArrayList<>();
        List<Integer> roots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (parent[i] < 0) roots.add(i);
            else children[parent[i]].add(i);
        }

        // 3) 先序遍历：折叠节点整段跳过；y 序号只给可见节点。记录 src 下标 → out 位置。
        int[] srcToOut = new int[n];
        for (int i = 0; i < n; i++) srcToOut[i] = -1;
        Deque<int[]> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) stack.push(new int[] { roots.get(i), 0 });
        int seq = 0;
        while (!stack.isEmpty() && out.size() < MAX_NODES) {
            int[] cur = stack.pop();
            int idx = cur[0], depth = cur[1];
            if (srcToOut[idx] >= 0) continue; // 环防护：已输出则跳过
            Node s = src.get(idx);
            Node copy = new Node(s.id, s.title, -1); // parent 稍后重编号
            copy.x = rootX + depth * Math.max(0, levelGapX);
            copy.y = rootY + (seq++) * Math.max(0, siblingGapY);
            copy.w = Math.max(0, nodeW);
            copy.h = Math.max(0, nodeH);
            copy.collapsed = s.collapsed;
            srcToOut[idx] = out.size();
            out.add(copy);

            if (s.collapsed) continue; // 折叠：后代不进入布局
            List<Integer> kids = children[idx];
            for (int k = kids.size() - 1; k >= 0; k--) {
                int child = kids.get(k);
                if (srcToOut[child] < 0) stack.push(new int[] { child, depth + 1 });
            }
        }

        // 4) parent 重编号为返回列表下标；父未进入列表则为 -1（parent 是 final，需重建 Node）。
        for (int i = 0; i < out.size(); i++) {
            int srcIdx = -1;
            for (int k = 0; k < n; k++) if (srcToOut[k] == i) { srcIdx = k; break; }
            int p = srcIdx >= 0 ? parent[srcIdx] : -1;
            int outParent = (p >= 0 && p < n) ? srcToOut[p] : -1;
            Node r = out.get(i);
            if (r.parent != outParent) {
                Node fixed = new Node(r.id, r.title, outParent);
                fixed.x = r.x; fixed.y = r.y; fixed.w = r.w; fixed.h = r.h; fixed.collapsed = r.collapsed;
                out.set(i, fixed);
            }
        }
        return out;
    }

    /** 折叠后可见节点数：任一祖先处于折叠态则不可见。对布局输出恒等于 size()。 */
    public static int visibleCount(List<Node> laidOut) {
        if (laidOut == null || laidOut.isEmpty()) return 0;
        int n = laidOut.size();
        boolean[] hidden = new boolean[n];
        for (int i = 0; i < n; i++) {
            int p = laidOut.get(i).parent;
            int guard = 0;
            while (p >= 0 && p < n && guard++ < n) {
                if (laidOut.get(p).collapsed) { hidden[i] = true; break; }
                p = laidOut.get(p).parent;
            }
        }
        int count = 0;
        for (boolean h : hidden) if (!h) count++;
        return count;
    }
}
