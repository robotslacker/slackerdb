package org.slackerdb.dbsyncer.model;

/**
 * 在目标写线程（或 DDL 事务）内执行的动作。
 *
 * <p>与 {@link DdlWriter} 一起构成"源侧 DDL 翻译器"和"目标写入器"之间的最小契约，
 * 使 {@code source} 不需要依赖 {@code target}。</p>
 */
@FunctionalInterface
public interface DdlAction {
    void run() throws Exception;
}
