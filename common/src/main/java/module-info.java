module common {
    // 声明该模块对外暴露的包
    exports org.slackerdb.common.logger;
    exports org.slackerdb.common.exceptions;
    exports org.slackerdb.common.utils;

    // 如果需要，声明依赖其他模块
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires org.slf4j;
    requires org.apache.commons.io;
    requires spring.core;
    requires java.sql;
}
