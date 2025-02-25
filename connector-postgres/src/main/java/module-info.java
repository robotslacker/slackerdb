module connector_postgres {
    // 如果需要，声明依赖其他模块
    requires duckdb.jdbc;
    requires common;
    requires java.sql;
    requires ch.qos.logback.classic;
    requires org.antlr.antlr4.runtime;
}
