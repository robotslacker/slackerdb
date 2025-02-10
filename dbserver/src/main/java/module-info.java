module dbserver {
    // 声明该模块对外暴露的包
    exports org.slackerdb.dbserver;

    // 如果需要，声明依赖其他模块
    requires io.netty.transport;
    requires duckdb.jdbc;
    requires jdk.management;
    requires ch.qos.logback.classic;
    requires org.slf4j;
    requires io.netty.codec;
    requires io.netty.common;
    requires io.netty.handler;
    requires io.netty.buffer;
    requires org.apache.commons.csv;
    requires java.sql;

    requires plsql;
    requires common;
}
