package org.slackerdb.dbdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SlackerDB 控制台（DbDesk）启动入口。
 *
 * <p>形态：<b>本机 Web 应用</b>（T1 独立进程）。浏览器只是显示层，Java 侧仍在本机运行；
 * 它只通过 JDBC（pgwire）和 dbserver 管理口 HTTP/WS API 访问服务端，<b>不启动、不探测、
 * 不管理 dbserver 进程</b>。因此它既能管理远端实例，也不会与服务端版本节奏绑死。</p>
 *
 * <p>技术基线：仓库 0.2.2 起整仓 **Java 21**；本模块额外要求 **Spring Boot 4.1.x**（Vaadin 25 的前提），
 * 用自包含 BOM 导入，只在模块内生效（见 {@code pom.xml} 的说明）。</p>
 */
@SpringBootApplication
public class DbDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbDeskApplication.class, args);
    }
}
