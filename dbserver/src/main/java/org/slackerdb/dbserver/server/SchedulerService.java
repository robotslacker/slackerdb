package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Logger;
import io.javalin.Javalin;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SchedulerService {
    private final Logger logger;
    private final DBInstance dbInstance;
    private final Javalin managementApp;

    public SchedulerService(
            DBInstance dbInstance,
            Javalin managementApp,
            Logger logger
    )
    {
        this.logger = logger;
        this.dbInstance = dbInstance;
        this.managementApp = managementApp;

        // setScheduler 设置外部数据源信息
        this.managementApp.post("/scheduler/setScheduler", ctx -> {
        });

        // scheduleJob 编排任务
        this.managementApp.post("/scheduler/scheduleJob", ctx -> {
        });

        // startJob 启动作业
        this.managementApp.post("/scheduler/startJob", ctx -> {
        });

        // getTasks 获取当前作业情况
        this.managementApp.get("/scheduler/getTasks", ctx -> {
        });

        // getTaskHistory 获取作业历史情况
        this.managementApp.get("/scheduler/getTaskHistory", ctx -> {
        });

        // shutdownJob 终止作业
        this.managementApp.post("/scheduler/shutdownJob", ctx -> {
        });

        // abortJob 放弃作业
        this.managementApp.post("/scheduler/abortJob", ctx -> {
        });

    }
}
