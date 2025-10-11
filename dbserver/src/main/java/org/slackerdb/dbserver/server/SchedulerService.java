package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

public class SchedulerService {
    private final Logger logger;
    private final DBInstance dbInstance;
    private final Javalin managementApp;
    private final ScheduleWorker scheduleWorker = new ScheduleWorker();

    private class JobInfo
    {
        // 任务名称
        String      taskName;
        // 脚本名称
        String      taskScript;
        // 运行策略 RUN_ONCE 运行一次;INTERVAL 定时间隔运行; CRONTAB 定时作业运行"
        String      taskRunPolicy = "RUN_ONCE";
        // 任务失败策略. STOP 失败后停止作业; CONTINUE 失败后继续运行;
        String      taskFailPolicy = "CONTINUE";
        // 每次运行间隔时间(秒) 针对INTERVAL来处理, 默认没有间隔
        int         taskInterval = 0;
        //  任务并行策略. PARALLEL 并行运行; SERIAL_DISCARD 串行抛弃; SERIAL_DELAY 串行推迟; SERIAL_CATCHUP 串行补录
        String      taskParallelPolicy = "SERIAL_DISCARD";
        // 任务定时作业Crontab描述
        String      taskCrontabExpr;
        // 任务脚本类型  可以为COMMAND，SQL
        String      taskScriptType;
        // 任务描述
        String      taskDescription;
        // 上次运行时间
        long        lastRunTime = 0;
        // 是否正在运行
        boolean     isRunning = false;
    }

    // 工作目录, 默认为当前目录
    String                              workDirectory = new File("").toString();
    // 外部环境信息
    HashMap<String, String>             contextInfo = new HashMap<>();
    // 最大进程数量
    int                                 maxProcesses = 0;
    // 任务信息
    HashMap<String, JobInfo>            jobInfo = new HashMap<>();
    // 当前状态
    String                              status = "CREATED";

    private class ScheduleWorker extends Thread
    {
        @Override
        @SuppressWarnings("BusyWait")
        public void run()
        {
            while (true)
            {
                if (status.equalsIgnoreCase("RUNNING"))
                {
                    // TODO

                }
                else if (status.equalsIgnoreCase("STOPPING"))
                {
                    while (true) {
                        boolean allJobStopped = true;
                        for (String jobTaskName : jobInfo.keySet()) {
                            JobInfo jobInfoObj = jobInfo.get(jobTaskName);
                            if (jobInfoObj.isRunning) {
                                allJobStopped = false;
                                break;
                            }
                        }
                        if (allJobStopped)
                        {
                            status = "STOPPED";
                            break;
                        }
                        else
                        {
                            try {
                                Thread.sleep(1000);
                            }
                            catch (InterruptedException ignored)
                            {
                                this.interrupt();
                                break;
                            }
                        }
                    }
                }
                else
                {
                    try {
                        Thread.sleep(1000);
                    }
                    catch (InterruptedException ignored)
                    {
                        this.interrupt();
                        break;
                    }
                }
            }
        }

    }

    public SchedulerService(
            DBInstance dbInstance,
            Javalin managementApp,
            Logger logger
    )
    {
        this.logger = logger;
        this.dbInstance = dbInstance;
        this.managementApp = managementApp;

        // 启动定时作业后台服务
        this.scheduleWorker.start();

        // setScheduler 设置外部数据源信息
        this.managementApp.post("/scheduler/setScheduler", ctx -> {
            JSONObject bodyObject;
            try {
                bodyObject = JSONObject.parseObject(ctx.body());
            } catch (JSONException ignored) {
                ctx.status(502).result("Rejected. Incomplete or format error in request.");
                return;
            }
            // 设置工作目录，默认为当前目录
            if (bodyObject.containsKey("workDirectory"))
            {
                this.workDirectory = bodyObject.getString("workDirectory");
            }
            // 设置最大进程数量，默认为不限制
            if (bodyObject.containsKey("maxProcesses"))
            {
                this.maxProcesses = bodyObject.getInteger("maxProcesses");
            }
            // 设置环境信息
            if (bodyObject.containsKey("contextInfo"))
            {
                JSONObject jsonObj = bodyObject.getJSONObject("contextInfo");
                for (String name : jsonObj.keySet())
                {
                    this.contextInfo.put(name, jsonObj.getString(name));
                }
            }
            // 设置任务信息
            if (bodyObject.containsKey("jobInfo"))
            {
                JSONArray jobInfoParameter = bodyObject.getJSONArray("jobInfo");
                for (Object obj : jobInfoParameter)
                {
                    String      jobTaskName;
                    JSONObject  jsonObj = (JSONObject) obj;
                    if (!jsonObj.containsKey("taskName"))
                    {
                        ctx.status(502).result("Rejected. Missing taskName.");
                        return;
                    }
                    else {
                        jobTaskName = jsonObj.getString("taskName");
                    }
                    JobInfo jobInfoObj;
                    if (this.jobInfo.containsKey(jobTaskName)) {
                        jobInfoObj = this.jobInfo.get(jobTaskName);
                    }
                    else {
                        jobInfoObj = new JobInfo();
                        jobInfoObj.taskName = jobTaskName;
                    }
                    if (jsonObj.containsKey("taskScript"))
                    {
                        jobInfoObj.taskScript = jsonObj.getString("taskScript");
                    }
                    if (jsonObj.containsKey("taskRunPolicy"))
                    {
                        jobInfoObj.taskRunPolicy = jsonObj.getString("taskRunPolicy");
                    }
                    if (jsonObj.containsKey("taskRunPolicy"))
                    {
                        jobInfoObj.taskRunPolicy = jsonObj.getString("taskRunPolicy");
                    }
                    if (jsonObj.containsKey("taskFailPolicy"))
                    {
                        jobInfoObj.taskFailPolicy = jsonObj.getString("taskFailPolicy");
                    }
                    if (jsonObj.containsKey("taskInterval"))
                    {
                        jobInfoObj.taskInterval = jsonObj.getInteger("taskInterval");
                    }
                    if (jsonObj.containsKey("taskParallelPolicy"))
                    {
                        jobInfoObj.taskParallelPolicy = jsonObj.getString("taskParallelPolicy");
                    }
                    if (jsonObj.containsKey("taskCrontabExpr"))
                    {
                        jobInfoObj.taskCrontabExpr = jsonObj.getString("taskCrontabExpr");
                    }
                    if (jsonObj.containsKey("taskScriptType"))
                    {
                        jobInfoObj.taskScriptType = jsonObj.getString("taskScriptType");
                    }
                    if (jsonObj.containsKey("taskScriptType"))
                    {
                        jobInfoObj.taskDescription = jsonObj.getString("taskDescription");
                    }
                    this.jobInfo.put(jobInfoObj.taskName, jobInfoObj);
                }
            }
        });

        // setScheduler 设置外部数据源信息
        this.managementApp.post("/scheduler/startScheduler", ctx -> {
            if (this.status.equalsIgnoreCase("CREATED") || this.status.equalsIgnoreCase("STOPPED") )
            {
                this.status = "RUNNING";
                ctx.json(Map.of("retCode", 0, "retMsg", "successful."));
            }
            else
            {
                ctx.json(Map.of("retCode", -1, "retMsg", "Start is not allowed now. [" + this.status + "]"));
            }
        });

        // setScheduler 设置外部数据源信息
        this.managementApp.post("/scheduler/stopScheduler", ctx -> {
            if (this.status.equalsIgnoreCase("RUNNING")) {
                this.status = "STOPPING";
                ctx.json(Map.of("retCode", 0, "retMsg", "successful."));
            }
            else
            {
                ctx.json(Map.of("retCode", -1, "retMsg", "Stop is not allowed now. [" + this.status + "]"));
            }
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

    public void stop()
    {
        // 关闭定时作业后台服务
        if (!this.scheduleWorker.isInterrupted()) {
            this.scheduleWorker.interrupt();
        }
    }
}
