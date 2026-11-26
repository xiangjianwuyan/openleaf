package org.openleaf.reader.application;

import android.content.Context;

import org.openleaf.reader.PendingWriteStore;
import org.openleaf.reader.RevisionStore;
import org.openleaf.reader.SqliteJobRepository;
import org.openleaf.reader.core.ContentContracts;

import java.util.HashMap;
import java.util.Map;

/**
 * 手工注入容器（application 层）：唯一集中装配 Android 适配器的地方。
 * 目前暴露任务仓库/写入账本/版本库三类接口化能力；老代码仍可自行 new store，
 * 后续 PR 可把具体类替换为接口而不改调用方。不引入 DI 框架、不改 Gradle 模块。
 */
public final class ServiceRegistry {
    private static final Map<String, ServiceRegistry> INSTANCES = new HashMap<>();

    private final Context app;
    private final ContentContracts.JobRepository jobs;
    private final PendingWriteStore pendingWrites;
    private final RevisionStore revisions;

    private ServiceRegistry(Context context) {
        this.app = context.getApplicationContext();
        this.jobs = new SqliteJobRepository(app);
        this.pendingWrites = new PendingWriteStore(app);
        this.revisions = new RevisionStore(app);
    }

    public static ServiceRegistry of(Context context) {
        Context app = context.getApplicationContext();
        synchronized (ServiceRegistry.class) {
            ServiceRegistry found = INSTANCES.get(app.getPackageName());
            if (found == null) { found = new ServiceRegistry(app); INSTANCES.put(app.getPackageName(), found); }
            return found;
        }
    }

    public Context context() { return app; }
    public ContentContracts.JobRepository jobs() { return jobs; }
    public PendingWriteStore pendingWrites() { return pendingWrites; }
    public RevisionStore revisions() { return revisions; }
}
