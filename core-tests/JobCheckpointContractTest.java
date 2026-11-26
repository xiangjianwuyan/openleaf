import org.openleaf.reader.core.ContentContracts;
import org.openleaf.reader.core.ImportUseCase;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务检查点契约：锁定 {@code JobRepository.checkpoint(...)} 的 default 实现
 * 与「updateItem + updateState 两次调用」语义等价。
 *
 * <p>为什么必须入库：checkpoint 是批量导入每个文件都要写的路径，SQLite 侧用单事务覆盖了
 * default 实现（少一次 fsync）。两边一旦语义漂移，任务中心的 processed/added/duplicates/failed
 * 会**静默算错**——不报错、不崩溃，只是数字不对。只有常驻用例能守住这条契约。
 *
 * <p>本用例只依赖接口，不依赖 android.*，因此纯 JVM 可跑；调用点一律用
 * {@code ContentContracts.JobRepository} 静态类型，这样一旦 default 方法被从接口上移除，
 * 本文件会直接编译失败（而不是悄悄改用某个实现的同名方法）。
 */
public final class JobCheckpointContractTest {
    private static int passed = 0;

    public static void main(String[] args) {
        test("checkpoint 与 updateItem+updateState 在成功项上等价", () -> {
            ContentContracts.JobRepository single = repository("job-a");
            ContentContracts.JobRepository pair = repository("job-a");

            single.checkpoint("job-a", 0, ContentContracts.ItemState.DONE, "book-0", null,
                    ContentContracts.JobState.RUNNING, 1, 1, 0, 0);
            pair.updateItem("job-a", 0, ContentContracts.ItemState.DONE, "book-0", null);
            pair.updateState("job-a", ContentContracts.JobState.RUNNING, 1, 1, 0, 0);

            assertItemEquals(single, pair, "job-a", 0);
            assertSnapshotEquals(single, pair, "job-a");
        });

        test("checkpoint 与 updateItem+updateState 在失败项上等价", () -> {
            ContentContracts.JobRepository single = repository("job-b");
            ContentContracts.JobRepository pair = repository("job-b");

            single.checkpoint("job-b", 1, ContentContracts.ItemState.FAILED, null, "不能导入空文件",
                    ContentContracts.JobState.RUNNING, 2, 1, 0, 1);
            pair.updateItem("job-b", 1, ContentContracts.ItemState.FAILED, null, "不能导入空文件");
            pair.updateState("job-b", ContentContracts.JobState.RUNNING, 2, 1, 0, 1);

            assertItemEquals(single, pair, "job-b", 1);
            assertSnapshotEquals(single, pair, "job-b");
            require("不能导入空文件".equals(single.items("job-b").get(1).error), "失败原因应原样持久化");
            require(single.items("job-b").get(1).bookId == null, "失败项不应关联书籍");
        });

        test("checkpoint 与 updateItem+updateState 在重复项上等价", () -> {
            ContentContracts.JobRepository single = repository("job-c");
            ContentContracts.JobRepository pair = repository("job-c");

            single.checkpoint("job-c", 2, ContentContracts.ItemState.DUPLICATE, "book-exists", null,
                    ContentContracts.JobState.RUNNING, 3, 1, 1, 1);
            pair.updateItem("job-c", 2, ContentContracts.ItemState.DUPLICATE, "book-exists", null);
            pair.updateState("job-c", ContentContracts.JobState.RUNNING, 3, 1, 1, 1);

            assertItemEquals(single, pair, "job-c", 2);
            assertSnapshotEquals(single, pair, "job-c");
            require("book-exists".equals(single.items("job-c").get(2).bookId), "重复项应指向已有书籍");
        });

        test("四个计数分项彼此独立，不会被 checkpoint 合并", () -> {
            ContentContracts.JobRepository jobs = repository("job-d");
            jobs.checkpoint("job-d", 0, ContentContracts.ItemState.DONE, "b0", null,
                    ContentContracts.JobState.RUNNING, 1, 1, 0, 0);
            jobs.checkpoint("job-d", 1, ContentContracts.ItemState.DUPLICATE, "b1", null,
                    ContentContracts.JobState.RUNNING, 2, 1, 1, 0);
            jobs.checkpoint("job-d", 2, ContentContracts.ItemState.FAILED, null, "编码无法识别",
                    ContentContracts.JobState.RUNNING, 3, 1, 1, 1);
            jobs.checkpoint("job-d", 3, ContentContracts.ItemState.DONE, "b3", null,
                    ContentContracts.JobState.RUNNING, 4, 2, 1, 1);

            ContentContracts.JobRepository.JobSnapshot snapshot = jobs.snapshot("job-d");
            require(snapshot.processed == 4, "processed 应为 4，实际 " + snapshot.processed);
            require(snapshot.added == 2, "added 应为 2，实际 " + snapshot.added);
            require(snapshot.duplicates == 1, "duplicates 应为 1，实际 " + snapshot.duplicates);
            require(snapshot.failed == 1, "failed 应为 1，实际 " + snapshot.failed);
            require(snapshot.state == ContentContracts.JobState.RUNNING, "中途应为 RUNNING");
        });

        test("同一 seq 重复 checkpoint 是覆盖而非追加", () -> {
            ContentContracts.JobRepository jobs = repository("job-e");
            jobs.checkpoint("job-e", 0, ContentContracts.ItemState.FAILED, null, "读取中断",
                    ContentContracts.JobState.RUNNING, 1, 0, 0, 1);
            jobs.checkpoint("job-e", 0, ContentContracts.ItemState.DONE, "book-0", null,
                    ContentContracts.JobState.RUNNING, 1, 1, 0, 0);

            require(jobs.items("job-e").size() == 5, "同一 seq 不应追加新行（仍应 5 项），实际 "
                    + jobs.items("job-e").size());
            ContentContracts.JobItem item = jobs.items("job-e").get(0);
            require(item.state == ContentContracts.ItemState.DONE, "状态应被覆盖为 DONE");
            require(item.error == null, "重试成功后 error 必须清空，实际 " + item.error);
            require("book-0".equals(item.bookId), "成功后应记录书籍 ID");
        });

        test("终态迁移：DONE / PAUSED / CANCELLED 各自落库", () -> {
            ContentContracts.JobRepository done = repository("job-f");
            done.checkpoint("job-f", 0, ContentContracts.ItemState.DONE, "b0", null,
                    ContentContracts.JobState.DONE, 1, 1, 0, 0);
            require(done.snapshot("job-f").state == ContentContracts.JobState.DONE, "应为 DONE");

            ContentContracts.JobRepository paused = repository("job-g");
            paused.checkpoint("job-g", 0, ContentContracts.ItemState.DONE, "b0", null,
                    ContentContracts.JobState.PAUSED, 1, 1, 0, 0);
            require(paused.snapshot("job-g").state == ContentContracts.JobState.PAUSED, "应为 PAUSED");

            ContentContracts.JobRepository cancelled = repository("job-h");
            cancelled.checkpoint("job-h", 0, ContentContracts.ItemState.PENDING, null, null,
                    ContentContracts.JobState.CANCELLED, 0, 0, 0, 0);
            require(cancelled.snapshot("job-h").state == ContentContracts.JobState.CANCELLED, "应为 CANCELLED");
        });

        test("整批混合结果：checkpoint 驱动的仓库与两次调用驱动完全一致", () -> {
            ContentContracts.JobRepository single = repository("job-i");
            ContentContracts.JobRepository pair = repository("job-i");

            // 0: DONE, 1: DUPLICATE, 2: FAILED, 3: FAILED→重试后 DONE, 4: 未处理
            runSingle(single, "job-i");
            runPair(pair, "job-i");

            ContentContracts.JobRepository.JobSnapshot s = single.snapshot("job-i");
            ContentContracts.JobRepository.JobSnapshot p = pair.snapshot("job-i");
            require(s.processed == p.processed, "processed 应一致：" + s.processed + " vs " + p.processed);
            require(s.added == p.added, "added 应一致：" + s.added + " vs " + p.added);
            require(s.duplicates == p.duplicates, "duplicates 应一致：" + s.duplicates + " vs " + p.duplicates);
            require(s.failed == p.failed, "failed 应一致：" + s.failed + " vs " + p.failed);
            require(s.state == p.state, "任务状态应一致");
            require(s.processed == 4 && s.added == 2 && s.duplicates == 1 && s.failed == 1,
                    "混合结果计数应为 4/2/1/1，实际 " + s.processed + "/" + s.added + "/" + s.duplicates + "/" + s.failed);
            for (int seq = 0; seq < 5; seq++) assertItemEquals(single, pair, "job-i", seq);
            require(single.items("job-i").size() == 5, "不应丢项或多出项");
            require(single.items("job-i").get(3).error == null, "重试成功的项 error 应为空");
            require(single.items("job-i").get(4).state == ContentContracts.ItemState.PENDING, "未处理项应保持 PENDING");
        });

        System.out.println("PASS: " + passed + " job checkpoint contract checks");
    }

    /** 用 checkpoint 驱动一整批混合结果。 */
    private static void runSingle(ContentContracts.JobRepository jobs, String jobId) {
        jobs.checkpoint(jobId, 0, ContentContracts.ItemState.DONE, "b0", null,
                ContentContracts.JobState.RUNNING, 1, 1, 0, 0);
        jobs.checkpoint(jobId, 1, ContentContracts.ItemState.DUPLICATE, "b1", null,
                ContentContracts.JobState.RUNNING, 2, 1, 1, 0);
        jobs.checkpoint(jobId, 2, ContentContracts.ItemState.FAILED, null, "此 EPUB 含 DRM",
                ContentContracts.JobState.RUNNING, 3, 1, 1, 1);
        jobs.checkpoint(jobId, 3, ContentContracts.ItemState.FAILED, null, "操作被取消",
                ContentContracts.JobState.RUNNING, 4, 1, 1, 2);
        jobs.checkpoint(jobId, 3, ContentContracts.ItemState.DONE, "b3", null,
                ContentContracts.JobState.RUNNING, 4, 2, 1, 1);
        jobs.checkpoint(jobId, 4, ContentContracts.ItemState.PENDING, null, null,
                ContentContracts.JobState.DONE, 4, 2, 1, 1);
    }

    /** 用 updateItem + updateState 两次调用驱动同一批结果，逐次对齐上一条路径。 */
    private static void runPair(ContentContracts.JobRepository jobs, String jobId) {
        jobs.updateItem(jobId, 0, ContentContracts.ItemState.DONE, "b0", null);
        jobs.updateState(jobId, ContentContracts.JobState.RUNNING, 1, 1, 0, 0);
        jobs.updateItem(jobId, 1, ContentContracts.ItemState.DUPLICATE, "b1", null);
        jobs.updateState(jobId, ContentContracts.JobState.RUNNING, 2, 1, 1, 0);
        jobs.updateItem(jobId, 2, ContentContracts.ItemState.FAILED, null, "此 EPUB 含 DRM");
        jobs.updateState(jobId, ContentContracts.JobState.RUNNING, 3, 1, 1, 1);
        jobs.updateItem(jobId, 3, ContentContracts.ItemState.FAILED, null, "操作被取消");
        jobs.updateState(jobId, ContentContracts.JobState.RUNNING, 4, 1, 1, 2);
        jobs.updateItem(jobId, 3, ContentContracts.ItemState.DONE, "b3", null);
        jobs.updateState(jobId, ContentContracts.JobState.RUNNING, 4, 2, 1, 1);
        jobs.updateItem(jobId, 4, ContentContracts.ItemState.PENDING, null, null);
        jobs.updateState(jobId, ContentContracts.JobState.DONE, 4, 2, 1, 1);
    }

    /** 5 项候选（seq 0..4），供两条路径共用同一份初始数据。 */
    private static ContentContracts.JobRepository repository(String jobId) {
        List<ImportUseCase.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candidates.add(new ImportUseCase.Candidate("asset-" + i, "book" + i + ".txt", "txt", 128));
        }
        ContentContracts.JobRepository jobs = new ContentContracts.InMemoryJobRepository();
        jobs.create(jobId, "folder", "/books", ImportUseCase.toJobItems(candidates));
        return jobs;
    }

    private static void assertItemEquals(ContentContracts.JobRepository a, ContentContracts.JobRepository b,
                                         String jobId, int seq) {
        ContentContracts.JobItem left = a.items(jobId).get(seq);
        ContentContracts.JobItem right = b.items(jobId).get(seq);
        require(left.state == right.state, "seq " + seq + " 状态应一致：" + left.state + " vs " + right.state);
        require(eq(left.bookId, right.bookId), "seq " + seq + " bookId 应一致：" + left.bookId + " vs " + right.bookId);
        require(eq(left.error, right.error), "seq " + seq + " error 应一致：" + left.error + " vs " + right.error);
    }

    private static void assertSnapshotEquals(ContentContracts.JobRepository a, ContentContracts.JobRepository b,
                                             String jobId) {
        ContentContracts.JobRepository.JobSnapshot left = a.snapshot(jobId);
        ContentContracts.JobRepository.JobSnapshot right = b.snapshot(jobId);
        require(left.state == right.state, "任务状态应一致：" + left.state + " vs " + right.state);
        require(left.processed == right.processed, "processed 应一致");
        require(left.added == right.added, "added 应一致");
        require(left.duplicates == right.duplicates, "duplicates 应一致");
        require(left.failed == right.failed, "failed 应一致");
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void test(String name, Check check) {
        try {
            check.run();
            passed++;
        } catch (Throwable failure) {
            throw new AssertionError(name + " 失败: " + failure.getMessage(), failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
