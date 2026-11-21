#!/bin/bash

# checking java home ...
if [ -n "$SLACKERDB_JAVA_HOME" ]; then
    _SLACKERDB_JAVA="$SLACKERDB_JAVA_HOME/bin/java"
elif [ -n "$JAVA_HOME" ]; then
    _SLACKERDB_JAVA="$JAVA_HOME/bin/java"
else
    _SLACKERDB_JAVA="java"
fi

APP_NAME=jlib/slackerdb-dbserver-0.2.2-standalone.jar
PID_FILE=pid/slackerdb-dbserver.pid

# ---------------------------------------------------------------------------
# JVM 参数：这里只放 JVM 自身的参数，不做内存探测、不推算 heap。
# 数据库运行参数（memory_limit / threads / 端口 / 闸门等）由 conf/dbserver.conf
# 与程序默认值决定，脚本不覆盖。
#
# 默认值面向 >=32G、结果集有界（LIMIT 10000）的常规部署：堆 8g、Direct 2g。
# 需要调整时用环境变量覆盖，例如：
#   export SLACKERDB_JAVA_OPTS="-Xms16g -Xmx16g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxDirectMemorySize=4g"
# 或直接修改下面这一行。注意堆 + memory_limit 要留出 OS/页缓存余量（见 conf 注释）。
# ---------------------------------------------------------------------------
if [ -z "$SLACKERDB_JAVA_OPTS" ]; then
    SLACKERDB_JAVA_OPTS="-Xms8g -Xmx8g \
-XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
-XX:MaxDirectMemorySize=2g \
-Xlog:gc*:file=logs/gc-%p.log:time,uptime,level,tags:filecount=5,filesize=20M \
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs"
fi
JAVA_OPTS="$SLACKERDB_JAVA_OPTS"

# 控制命令（stop/status）只需要很小的堆，避免白白占住大内存
CTRL_OPTS="-Xms64m -Xmx256m"

usage(){
  echo ""
  echo "*********************************************************"
  echo "please use command: sh dbserver.sh [start|stop|status]"
  echo "*********************************************************"
  echo ""
  exit 1
}

getPid(){
    local pid_file="${PID_FILE}"

    # 检查 PID 文件是否存在
    if [ ! -f "$pid_file" ]; then
        echo 0
        return
    fi

    # 读取 PID 并验证是否为数字
    local pid
    pid=$(tr -d ' ' < "$pid_file" 2>/dev/null) # 去除空格
    if ! [[ "$pid" =~ ^[0-9]+$ ]]; then
        echo 0
        return
    fi

    if kill -0 "$pid" >/dev/null 2>&1; then
        # 进程存在
        echo ${pid}
        return
    else
        # 进程不存在
        echo 0
        return
    fi
}

start(){
    pid=$(getPid)
    if [ ${pid} -ne 0 ]; then
        echo ""
        echo "*********************************************"
        echo "${APP_NAME} is already running, Pid is ${pid} ."
        echo "*********************************************"
        echo ""
    else
        mkdir -p pid
        mkdir -p logs
        echo "JVM options: ${JAVA_OPTS}"
        ${_SLACKERDB_JAVA} ${JAVA_OPTS} -jar $APP_NAME \
            --daemon true \
            --conf conf/dbserver.conf \
            --pid  ${PID_FILE} \
            start > /dev/null
        # 等待3秒的进程启动时间
        sleep 3
        pid=$(getPid)
        if [ ${pid} -ne 0 ]; then
            echo ""
            echo "*********************************************"
            echo "${APP_NAME} is running, Pid is ${pid}."
            echo "*********************************************"
            echo ""
        else
            echo ""
            echo "*********************************************"
            echo "${APP_NAME} is not running."
            echo "*********************************************"
            echo ""
        fi
    fi
}

stop(){
    pid=$(getPid)
    if [ ${pid} -ne 0 ]; then
        timeout 30 \
            ${_SLACKERDB_JAVA} ${CTRL_OPTS} -jar $APP_NAME \
                 --conf conf/dbserver.conf \
                 stop > /dev/null
    fi
    pid=$(getPid)
    if [ ${pid} -ne 0 ]; then
        kill -9 $pid
        echo ""
        echo "*********************************************"
        echo "${APP_NAME} stoped."
        echo "*********************************************"
        echo ""
    else
        echo ""
        echo "*********************************************"
        echo "${APP_NAME} is not running."
        echo "*********************************************"
        echo ""
    fi
}


status(){
    pid=$(getPid)
    if [ ${pid} -ne 0 ]; then
        ${_SLACKERDB_JAVA} ${CTRL_OPTS} -jar $APP_NAME \
            --conf conf/dbserver.conf \
            status
        echo ""
        echo "*********************************************"
        echo "${APP_NAME} is running, Pid is ${pid}."
        echo "*********************************************"
        echo ""
    else
        echo ""
        echo "*********************************************"
        echo "${APP_NAME} is not running."
        echo "*********************************************"
        echo ""
    fi
}

SCRIPT_PATH="$(cd "$(dirname "$0")" && pwd)"
cd ${SCRIPT_PATH}/.. || exit 255
case "$1" in
    "start")
     start
     ;;
    "stop")
     stop
     ;;
    "status")
     status
     ;;
    *)
    usage
    ;;
esac
