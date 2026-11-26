import org.openleaf.reader.core.ContentContracts;
import org.openleaf.reader.core.ImportUseCase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 迁移与恢复夹具：验证资产身份/版本分离、检查点续跑幂等、编辑覆盖后的版本变化。
 * 全部为纯 JVM，无需 Android SDK。
 */
public final class ImportResumeTest {
    private static int passed = 0;

    public static void main(String[] args) {
        test("SHA-256 hex 一致且稳定", () -> {
            String a = ContentContracts.hashHex("hello".getBytes(StandardCharsets.UTF_8));
            String b = ContentContracts.hashHex("hello".getBytes(StandardCharsets.UTF_8));
            String c = ContentContracts.hashHex("world".getBytes(StandardCharsets.UTF_8));
            require(a.equals(b), "相同内容哈希应一致");
            require(!a.equals(c), "不同内容哈希应不同");
            require(a.length() == 64, "hex 应为 64 字符，实际 " + a.length());
        });

        test("Revision 按内容指纹比较而非 ID", () -> {
            String h1 = ContentContracts.hashHex("v1".getBytes(StandardCharsets.UTF_8));
            String h2 = ContentContracts.hashHex("v2".getBytes(StandardCharsets.UTF_8));
            ContentContracts.Revision r1 = new ContentContracts.Revision("asset-A", h1, 2, 100);
            ContentContracts.Revision r1Again = new ContentContracts.Revision("asset-B", h1, 2, 200);
            ContentContracts.Revision r2 = new ContentContracts.Revision("asset-A", h2, 2, 300);
            require(r1.sameContent(r1Again), "不同资产位置、相同内容应视为同一版本");
            require(!r1.sameContent(r2), "同一资产、不同内容不是同一版本");
        });

        test("编辑覆盖返回类型化结果并登记新版本", () -> {
            String oldHash = ContentContracts.hashHex("old".getBytes(StandardCharsets.UTF_8));
            String newHash = ContentContracts.hashHex("new".getBytes(StandardCharsets.UTF_8));
            ContentContracts.Revision before = new ContentContracts.Revision("asset-A", oldHash, 3, 100);
            ContentContracts.EditOutcome outcome = ContentContracts.EditOutcome.covered("asset-A",
                    new ContentContracts.Revision("asset-A", newHash, 3, 200));
            require(outcome.ok() && outcome.changesAsset(), "覆盖结果应成功且改变资产");
            require(outcome.kind == ContentContracts.EditKind.COVERED, "类型应为 COVERED");
            require(!before.sameContent(outcome.newRevision), "覆盖后版本指纹应变化");
            ContentContracts.EditOutcome copy = ContentContracts.EditOutcome.savedCopy("asset-B", "已另存副本");
            require(copy.ok() && !copy.changesAsset(), "另存副本不应改变当前资产");
            require(ContentContracts.EditOutcome.conflict("外部修改").kind == ContentContracts.EditKind.CONFLICT,
                    "冲突应为独立类型");
        });

        test("导入检查点：全部成功", () -> {
            List<ImportUseCase.Candidate> candidates = candidates(3);
            ContentContracts.JobRepository jobs = new ContentContracts.InMemoryJobRepository();
            jobs.create("job-1", "folder", "/books", ImportUseCase.toJobItems(candidates));
            ImportUseCase.Summary summary = ImportUseCase.run(candidates, jobs, "job-1",
                    candidate -> ImportUseCase.ItemResult.added(candidate.assetId, "book-" + candidate.assetId), null);
            require(summary.processed == 3 && summary.added == 3 && summary.failed == 0, "应全部入库");
            require(jobs.snapshot("job-1").state == ContentContracts.JobState.DONE, "任务应完成");
        });

        test("导入检查点：中断后续跑不重复入库", () -> {
            List<ImportUseCase.Candidate> candidates = candidates(4);
            ContentContracts.JobRepository jobs = new ContentContracts.InMemoryJobRepository();
            jobs.create("job-2", "folder", "/books", ImportUseCase.toJobItems(candidates));
            final int[] executed = {0};
            final int[] doneCount = {0};
            // 第一次：处理 2 项后取消（模拟进程中断）
            ImportUseCase.Progress cancelAfterTwo = new ImportUseCase.Progress() {
                public void onItemDone(int processed, int total, ImportUseCase.ItemResult result) { doneCount[0] = processed; }
                public boolean cancelled() { return doneCount[0] >= 2; }
            };
            ImportUseCase.Summary first = ImportUseCase.run(candidates, jobs, "job-2", candidate -> {
                executed[0]++;
                return ImportUseCase.ItemResult.added(candidate.assetId, "book-" + candidate.assetId);
            }, cancelAfterTwo);
            require(first.paused, "应处于暂停状态");
            require(executed[0] == 2, "第一次应只执行 2 项，实际 " + executed[0]);
            require(jobs.snapshot("job-2").state == ContentContracts.JobState.PAUSED, "任务应暂停");

            // 第二次：无取消地续跑，只执行尚未完成的 a3/a4；a1/a2 从检查点跳过
            final java.util.List<String> resumedIds = new java.util.ArrayList<>();
            ImportUseCase.Summary second = ImportUseCase.run(candidates, jobs, "job-2", candidate -> {
                if (candidate.assetId.equals("a1") || candidate.assetId.equals("a2"))
                    throw new AssertionError("已完成项 " + candidate.assetId + " 不应重复入库");
                resumedIds.add(candidate.assetId);
                return ImportUseCase.ItemResult.added(candidate.assetId, "book-" + candidate.assetId);
            }, null);
            require(second.added == 4 && second.failed == 0, "续跑应累计 4 项已入库");
            require(resumedIds.size() == 2 && resumedIds.contains("a3") && resumedIds.contains("a4"),
                    "续跑应只执行 a3/a4，实际 " + resumedIds);
            require(jobs.snapshot("job-2").state == ContentContracts.JobState.DONE, "续跑后任务完成");
        });

        test("导入检查点：重复项与失败项正确分类", () -> {
            List<ImportUseCase.Candidate> candidates = candidates(3);
            ContentContracts.JobRepository jobs = new ContentContracts.InMemoryJobRepository();
            jobs.create("job-3", "folder", "/books", ImportUseCase.toJobItems(candidates));
            ImportUseCase.Summary summary = ImportUseCase.run(candidates, jobs, "job-3", candidate -> {
                if (candidate.assetId.equals("a1")) return ImportUseCase.ItemResult.duplicate(candidate.assetId, "book-x");
                if (candidate.assetId.equals("a2")) return ImportUseCase.ItemResult.failed(candidate.assetId, "校验失败");
                return ImportUseCase.ItemResult.added(candidate.assetId, "book-" + candidate.assetId);
            }, null);
            require(summary.added == 1 && summary.duplicates == 1 && summary.failed == 1,
                    "应分别统计新增/重复/失败");
            require(jobs.items("job-3").get(1).state == ContentContracts.ItemState.FAILED, "失败项应持久化");
        });

        System.out.println("PASS: " + passed + " import resume checks");
    }

    private static List<ImportUseCase.Candidate> candidates(int n) {
        List<ImportUseCase.Candidate> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) list.add(new ImportUseCase.Candidate("a" + i, "book" + i + ".txt", "txt", 100));
        return list;
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
