import org.openleaf.reader.core.ContentContracts;
import org.openleaf.reader.core.DownloadPolicy;
import org.openleaf.reader.core.DownloadUseCase;
import org.openleaf.reader.core.PendingWriteReconciler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** pending_write 收敛、下载策略与任务模型的纯 JVM 夹具。 */
public final class ArchitectureFollowupTest {
    private static int passed = 0;

    public static void main(String[] args) {
        String oldSha = ContentContracts.hashHex("before".getBytes(StandardCharsets.UTF_8));
        String newSha = ContentContracts.hashHex("after".getBytes(StandardCharsets.UTF_8));
        String thirdSha = ContentContracts.hashHex("unexpected".getBytes(StandardCharsets.UTF_8));

        test("pending_write：目标已是意图内容 → 补登记版本", () -> {
            PendingWriteReconciler.PendingWrite p =
                    new PendingWriteReconciler.PendingWrite("content://x", oldSha, newSha, 6, "book-1");
            PendingWriteReconciler.Resolution r = PendingWriteReconciler.resolve(p, newSha);
            require(r.decision == PendingWriteReconciler.Decision.COMMIT_VERSION, "应补登记版本");
            require(!r.needsUserAction(), "无需用户介入");
        });

        test("pending_write：目标仍是旧内容 → 未写入可放弃", () -> {
            PendingWriteReconciler.PendingWrite p =
                    new PendingWriteReconciler.PendingWrite("content://x", oldSha, newSha, 6, "book-1");
            PendingWriteReconciler.Resolution r = PendingWriteReconciler.resolve(p, oldSha);
            require(r.decision == PendingWriteReconciler.Decision.NOT_WRITTEN, "应判定未写入");
        });

        test("pending_write：第三种内容 → 冲突需用户确认", () -> {
            PendingWriteReconciler.PendingWrite p =
                    new PendingWriteReconciler.PendingWrite("content://x", oldSha, newSha, 6, "book-1");
            PendingWriteReconciler.Resolution r = PendingWriteReconciler.resolve(p, thirdSha);
            require(r.decision == PendingWriteReconciler.Decision.CONFLICT && r.needsUserAction(), "应需用户确认");
        });

        test("pending_write：非法指纹 → 丢弃", () -> {
            PendingWriteReconciler.PendingWrite p =
                    new PendingWriteReconciler.PendingWrite("content://x", oldSha, "not-a-hash", 6, null);
            require(PendingWriteReconciler.resolve(p, newSha).decision == PendingWriteReconciler.Decision.INVALID,
                    "非法指纹应丢弃");
            require(PendingWriteReconciler.resolve(null, newSha).decision == PendingWriteReconciler.Decision.NOTHING,
                    "空记录应无事可做");
        });

        test("下载配额：预算/剩余空间/未知 space", () -> {
            require(DownloadPolicy.quotaAllows(100, 50, 200, 1000), "未超预算应允许");
            require(!DownloadPolicy.quotaAllows(100, 150, 200, 1000), "超预算应拒绝");
            require(!DownloadPolicy.quotaAllows(0, 100, 200, 50), "超剩余空间应拒绝");
            require(DownloadPolicy.quotaAllows(0, 100, 200, DownloadPolicy.UNKNOWN_LENGTH), "未知空间不应拒绝");
            require(!DownloadPolicy.quotaAllows(-1, 100, 200, 1000), "负数已用应拒绝");
        });

        test("下载 Range：200 从头、206 校验起点与总数", () -> {
            DownloadPolicy.RangeDecision d200 = DownloadPolicy.rangeDecision(200, null, 100, 1000);
            require(d200.from == 0 && !d200.resume, "200 应从头");
            DownloadPolicy.RangeDecision ok = DownloadPolicy.rangeDecision(206, "bytes 100-999/1000", 100, 1000);
            require(ok.from == 100 && ok.resume, "206 起点一致应续传");
            DownloadPolicy.RangeDecision mismatch = DownloadPolicy.rangeDecision(206, "bytes 50-999/1000", 100, 1000);
            require(!mismatch.resume, "起点不一致应从头");
            DownloadPolicy.RangeDecision badTotal = DownloadPolicy.rangeDecision(206, "bytes 100-999/900", 100, 1000);
            require(!badTotal.resume, "总数不一致应从头");
        });

        test("下载文件名安全化与校验", () -> {
            require(DownloadPolicy.safeFileName("../../etc/passwd", 80).equals(".._.._etc_passwd"),
                    "路径分隔符应替换");
            require(DownloadPolicy.safeFileName("  ", 80).equals("download"), "空白名应回退");
            require(DownloadPolicy.safeFileName("a".repeat(100), 20).length() == 20, "应限制长度");
            byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
            String sha = ContentContracts.hashHex(bytes);
            require(DownloadPolicy.hashMatches(sha.toUpperCase(java.util.Locale.ROOT), bytes), "hex 大小写应兼容");
            require(!DownloadPolicy.hashMatches("bad", bytes), "非法 hex 应不匹配");
        });

        test("下载任务模型：成功/失败分类与续跑幂等", () -> {
            List<DownloadUseCase.Request> reqs = new ArrayList<>();
            reqs.add(new DownloadUseCase.Request("https://a/1.txt", "1.txt", "", 10));
            reqs.add(new DownloadUseCase.Request("https://a/2.txt", "2.txt", "", 20));
            ContentContracts.JobRepository jobs = new ContentContracts.InMemoryJobRepository();
            jobs.create("dl-1", "download", "src", DownloadUseCase.toJobItems(reqs));
            final int[] fetched = {0};
            DownloadUseCase.Summary first = DownloadUseCase.run(reqs, jobs, "dl-1", request -> {
                fetched[0]++;
                if (request.url.endsWith("2.txt")) return DownloadUseCase.Result.fail("校验失败");
                return DownloadUseCase.Result.ok("/cache/1.txt", "sha1");
            }, null);
            require(first.succeeded == 1 && first.failed == 1, "应一成一败");
            require(jobs.items("dl-1").get(1).state == ContentContracts.ItemState.FAILED, "失败应持久化");

            final List<String> resumed = new ArrayList<>();
            DownloadUseCase.Summary second = DownloadUseCase.run(reqs, jobs, "dl-1", request -> {
                if (request.url.endsWith("1.txt")) throw new AssertionError("已完成项不应重复下载");
                resumed.add(request.url);
                return DownloadUseCase.Result.ok("/cache/2.txt", "sha2");
            }, null);
            require(resumed.size() == 1 && resumed.get(0).endsWith("2.txt"), "只应重试失败项");
            require(second.succeeded == 2 && second.failed == 0, "续跑应全部成功");
            require(jobs.snapshot("dl-1").state == ContentContracts.JobState.DONE, "任务应完成");
        });

        System.out.println("PASS: " + passed + " architecture followup checks");
    }

    private interface Check { void run() throws Exception; }
    private static void test(String name, Check check) {
        try { check.run(); passed++; }
        catch (Throwable failure) { throw new AssertionError(name + " 失败: " + failure.getMessage(), failure); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
