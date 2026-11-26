import org.openleaf.reader.core.OnlineChapter;
import java.util.*;

/** 重新解析后的进度映射与缓存复用测试。 */
public class OnlineReparseTest {
    static int passed = 0;
    static void check(boolean ok, String msg) { if(!ok) throw new AssertionError(msg); passed++; }
    static List<OnlineChapter.Entry> entries(String... urls) {
        List<OnlineChapter.Entry> r = new ArrayList<>();
        for (int i=0;i<urls.length;i++) r.add(new OnlineChapter.Entry(i, "第"+i+"章", urls[i]));
        return r;
    }
    public static void main(String[] a) {
        List<OnlineChapter.Entry> oldE = entries("u1","u2","u3","u4");
        // 新目录：插入一章 u0，删掉 u4，顺序略变
        List<OnlineChapter.Entry> newE = entries("u1","u0","u2","u3");
        check(OnlineChapter.remapOrder(oldE,newE,0)==0, "u1 应映射到新序号0");
        check(OnlineChapter.remapOrder(oldE,newE,1)==2, "u2 应映射到新序号2");
        check(OnlineChapter.remapOrder(oldE,newE,2)==3, "u3 应映射到新序号3");
        check(OnlineChapter.remapOrder(oldE,newE,3)==-1, "u4 被删除应返回-1");
        check(OnlineChapter.remapOrder(oldE,newE,-1)==-1, "非法序号返回-1");
        check(OnlineChapter.remapOrder(oldE,newE,99)==-1, "越界序号返回-1");
        Set<Integer> reusable = OnlineChapter.reusableCacheOrders(oldE,newE);
        check(reusable.equals(new HashSet<>(Arrays.asList(0,2,3))), "u1/u2/u3 缓存可复用");
        // 标题回退（无URL）
        List<OnlineChapter.Entry> noUrlOld = Arrays.asList(new OnlineChapter.Entry(0,"序章",null));
        List<OnlineChapter.Entry> noUrlNew = Arrays.asList(new OnlineChapter.Entry(0,"楔子",null), new OnlineChapter.Entry(1,"序章",null));
        check(OnlineChapter.remapOrder(noUrlOld,noUrlNew,0)==1, "无URL时按标题匹配");
        System.out.println("PASS OnlineReparseTest ("+passed+" checks)");
    }
}
