#!/bin/bash

# checking java home ...
if [ -n "$SLACKERDB_JAVA_HOME" ]; then
    _SLACKERDB_JAVA="$SLACKERDB_JAVA_HOME/bin/java"
elif [ -n "$JAVA_HOME" ]; then
    _SLACKERDB_JAVA="$JAVA_HOME/bin/java"
else
    _SLACKERDB_JAVA="java"
fi

APP_NAME=jlib/slackerdb-dbproxy-0.1.7-standalone.jar
PID_FILE=pid/slackerdb-dbproxy.pid

usage(){
  echo ""
  echo "*********************************************************"
  echo "please use command: sh dbproxy.sh [start|stop|status]"
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
        ${_SLACKERDB_JAVA} -jar $APP_NAME \
            --daemon true \
            --conf conf/dbproxy.conf \
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
            ${_SLACKERDB_JAVA} -jar $APP_NAME \
                 --conf conf/dbproxy.conf \
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
        ${_SLACKERDB_JAVA} -jar $APP_NAME \
            --conf conf/dbproxy.conf \
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
