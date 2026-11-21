package org.slackerdb.dbsyncer.source;

import org.slackerdb.dbsyncer.model.DdlAction;

/**
 * 源线程应用 DDL 的准入点。
 *
 * <p>DDL 会改目标表结构（{@code TargetTable} 与 DuckDB 表），而定时 flush 线程同时在读同一份结构；
 * 实现方必须在 DDL 期间独占目标写入（暂停 flush），并先提交 DDL 之前的行事件。</p>
 */
@FunctionalInterface
public interface DdlExecutor {

    void applyDdl(DdlAction action) throws Exception;
}
