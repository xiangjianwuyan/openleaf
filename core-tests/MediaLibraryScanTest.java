import org.openleaf.reader.core.AudioDsp;
import org.openleaf.reader.core.MediaLibraryScan;
import java.util.*;

/** 阶段 3-B Y-1 媒体库增量扫描 core 回归。 */
public final class MediaLibraryScanTest {
    private static int passed;

    public static void main(String[] args) {
        kindMapping();
        snapshotAndDiff();
        summaryAndCandidates();
        System.out.println("MediaLibraryScanTest: " + passed + " checks passed");
    }

    private static void kindMapping() {
        eq(MediaLibraryScan.Kind.BOOK, MediaLibraryScan.Kind.of("book"));
        eq(MediaLibraryScan.Kind.COMIC, MediaLibraryScan.Kind.of("COMIC"));
        eq(MediaLibraryScan.Kind.AUDIO, MediaLibraryScan.Kind.of("audio"));
        eq(MediaLibraryScan.Kind.VIDEO, MediaLibraryScan.Kind.of("video"));
        eq(MediaLibraryScan.Kind.BOOK, MediaLibraryScan.Kind.of(null));
        eq(MediaLibraryScan.Kind.BOOK, MediaLibraryScan.Kind.of("未知"));
        eq("comic", new MediaLibraryScan.Item("/a.cbz", 0, 0, MediaLibraryScan.Kind.COMIC, "", 0, "").kind);
        passed++;
    }

    private static void snapshotAndDiff() {
        MediaLibraryScan.Item a = new MediaLibraryScan.Item("/a.txt", 100, 10, MediaLibraryScan.Kind.BOOK, "A", 0, "");
        MediaLibraryScan.Item b = new MediaLibraryScan.Item("/b.epub", 200, 20, MediaLibraryScan.Kind.BOOK, "B", 0, "");
        MediaLibraryScan.Item bChanged = new MediaLibraryScan.Item("/b.epub", 201, 22, MediaLibraryScan.Kind.BOOK, "B", 0, "");
        MediaLibraryScan.Item c = new MediaLibraryScan.Item("/c.mp3", 300, 30, MediaLibraryScan.Kind.AUDIO, "C", 5000, "");

        AudioDsp.ScanDiff d = MediaLibraryScan.diff(Arrays.asList(a, b), Arrays.asList(a, bChanged, c));
        eq(Collections.singletonList("/c.mp3"), d.added);
        eq(Collections.singletonList("/b.epub"), d.modified);
        eq(Collections.emptyList(), d.removed);

        AudioDsp.ScanDiff removed = MediaLibraryScan.diff(Arrays.asList(a, b), Collections.singletonList(a));
        eq(Collections.singletonList("/b.epub"), removed.removed);
        eq(0, MediaLibraryScan.snapshot(null).size());
        passed++;
    }

    private static void summaryAndCandidates() {
        MediaLibraryScan.Item a = new MediaLibraryScan.Item("/a.txt", 0, 0, MediaLibraryScan.Kind.BOOK, "", 0, "");
        AudioDsp.ScanDiff d = MediaLibraryScan.diff(Collections.singletonList(a), Collections.emptyList());
        eq("新增 0 / 修改 0 / 删除 1", MediaLibraryScan.summary(d));
        eq("无差异", MediaLibraryScan.summary(null));

        for (String p : new String[]{"/a.txt", "b.cbz", "c.MP3", "d.mkv", "e.pdf"})
            yes(MediaLibraryScan.candidatePath(p), "应识别：" + p);
        for (String p : new String[]{null, "", "  ", "../evil.txt", "/a.exe", "/a/doc"}
                ) yes(!MediaLibraryScan.candidatePath(p), "应拒绝：" + p);
        passed++;
    }

    private static void yes(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
    private static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("期望 " + a + " 实际 " + b); }
}
