import org.openleaf.reader.core.DownloadPolicy;

/** 下载断点续传策略测试（纯 JVM）。 */
public class DownloadResumeTest {
    static int passed = 0;
    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); passed++; }

    public static void main(String[] args) {
        rangeBasics();
        rangeRejects();
        identityCheck();
        eligibility();
        fileNameSafety();
        hashCheck();
        System.out.println("PASS DownloadResumeTest (" + passed + " checks)");
    }

    static void rangeBasics() {
        DownloadPolicy.RangeDecision d200 = DownloadPolicy.rangeDecision(200, null, 100, 1000);
        check(!d200.resume && d200.from == 0, "200 应从头下载");
        DownloadPolicy.RangeDecision ok = DownloadPolicy.rangeDecision(206, "bytes 100-999/1000", 100, 1000);
        check(ok.resume && ok.from == 100, "206 起点匹配应续传");
        check(ok.reason.contains("100"), "原因应说明起点");
    }

    static void rangeRejects() {
        check(!DownloadPolicy.rangeDecision(206, "bytes 50-999/1000", 100, 1000).resume, "起点不符应拒绝");
        check(!DownloadPolicy.rangeDecision(206, "bytes 100-999/900", 100, 1000).resume, "总数不符应拒绝");
        check(!DownloadPolicy.rangeDecision(206, null, 100, 1000).resume, "无Content-Range应拒绝");
        check(!DownloadPolicy.rangeDecision(206, "bogus", 100, 1000).resume, "格式错误应拒绝");
        check(!DownloadPolicy.rangeDecision(500, "bytes 100-999/1000", 100, 1000).resume, "非2xx应拒绝");
        check(!DownloadPolicy.rangeDecision(206, "bytes -/1000", 100, 1000).resume, "无起始偏移应拒绝");
        check(!DownloadPolicy.rangeDecision(206, "bytes abc-999/1000", 100, 1000).resume, "非数字起点应拒绝");
    }

    static void identityCheck() {
        check(DownloadPolicy.identityMatches("\"abc\"", "\"abc\"", null, null), "ETag一致应通过");
        check(!DownloadPolicy.identityMatches("\"abc\"", "\"def\"", null, null), "ETag不同应拒绝");
        check(DownloadPolicy.identityMatches(null, null, "Mon, 01 Jan 2024", "Mon, 01 Jan 2024"), "Last-Modified一致应通过");
        check(!DownloadPolicy.identityMatches(null, null, "A", "B"), "Last-Modified不同应拒绝");
        check(!DownloadPolicy.identityMatches(null, null, null, null), "无可验证标识应拒绝");
        check(!DownloadPolicy.identityMatches("  ", "x", null, null), "空白标识应拒绝");
    }

    static void eligibility() {
        check(DownloadPolicy.resumeEligible(100, 1000, "\"abc\"", null), "有ETag且长度合理应可续");
        check(!DownloadPolicy.resumeEligible(0, 1000, "\"abc\"", null), "零字节不可续");
        check(!DownloadPolicy.resumeEligible(-1, 1000, "\"abc\"", null), "负长度不可续");
        check(!DownloadPolicy.resumeEligible(1200, 1000, "\"abc\"", null), "超预算不可续");
        check(!DownloadPolicy.resumeEligible(100, 1000, null, null), "无标识不可续");
        check(DownloadPolicy.resumeEligible(100, 1000, null, "Mon, 01 Jan 2024"), "仅Last-Modified也可续");
    }

    static void fileNameSafety() {
        check("a_b".equals(DownloadPolicy.safeFileName("a/b", 80)), "斜杠应替换");
        check("download".equals(DownloadPolicy.safeFileName("..", 80)), "..应拒绝");
        check(DownloadPolicy.safeFileName("x", 10).length() <= 10, "长度应受限");
    }

    static void hashCheck() {
        byte[] bytes = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = org.openleaf.reader.core.ContentContracts.hashHex(bytes);
        check(DownloadPolicy.hashMatches(hash, bytes), "正确哈希应通过");
        check(!DownloadPolicy.hashMatches("bad", bytes), "非法哈希应拒绝");
    }
}
