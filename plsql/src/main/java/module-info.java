module plsql {
    // 声明该模块对外暴露的包
    exports org.slackerdb.plsql;

    // 如果需要，声明依赖其他模块
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires org.slf4j;
    requires org.antlr.antlr4.runtime;
    requires java.sql;
}
