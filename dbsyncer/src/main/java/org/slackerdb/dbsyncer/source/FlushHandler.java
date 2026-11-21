package org.slackerdb.dbsyncer.source;

/** 源线程在重连或 DDL 前要求 JobRuntime flush 缓冲批次的回调。 */
@FunctionalInterface
public interface FlushHandler {
    void flush() throws Exception;
}
