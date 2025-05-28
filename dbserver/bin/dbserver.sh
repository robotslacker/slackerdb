#!/bin/bash

# Service control file template.
# Needs to be changed according to the actual environment

# app Name
APP_NAME="SLACKERDB-SERVER"
# JAR file path
# shellcheck disable=SC2154
JAR_NAME=slackerdb-dbserver-${project.version}-standalone.jar
JAR_PATH="jlib/${JAR_NAME}"
# Java options
JAVA_OPTS=""
# PID file path
PID_FILE="pid/dbserver.pid"
# conf file
CONF_FILE="conf/dbserver.conf"

# get script parent directory and cd.
script_dir="$(dirname "$(readlink -f "$0")")"
parent_dir="$(dirname "$script_dir")"
cd "$parent_dir" || exit 255
W
# check JAR file
if [ ! -f "$JAR_PATH" ]; then
    echo "Error: $JAR_PATH not found!"
    exit 1
fi

# check JAVA_HOME or java command
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="java"
else
    echo "Error: JAVA_HOME is not set and 'java' command not found in PATH."
    exit 1
fi

# startup
start() {
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        echo "$APP_NAME is already running (PID: $(cat "$PID_FILE"))"
    else
        echo "Starting $APP_NAME..."
        "$JAVA_CMD" "$JAVA_OPTS" -jar "$JAR_PATH" --conf "$CONF_FILE" --daemon true --pid "$PID_FILE" start
        echo "$APP_NAME started (PID: $(cat "$PID_FILE"))"
    fi
}

# stop
stop() {
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        echo "Stopping $APP_NAME (PID: $(cat "$PID_FILE"))..."
        if ! timeout 10 "$JAVA_CMD" "$JAVA_OPTS" -jar "$JAR_PATH" --conf "$CONF_FILE" stop; then
            awk '{print "kill -9 "$1}|' < "$PID_FILE" |sh
        fi
        echo "$APP_NAME stopped"
    else
        echo "$APP_NAME is not running"
    fi
}

# check status
status() {
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        echo "$APP_NAME is running (PID: $(cat "$PID_FILE"))"
        timeout 10 "$JAVA_CMD" "$JAVA_OPTS" -jar "$JAR_PATH" --conf "$CONF_FILE" status
    else
        echo "$APP_NAME is not running"
    fi
}

# check command line parameter
case "$1" in
    start)
        start
        ;;
    stop)
        stop
        ;;
    status)
        status
        ;;
    restart)
        stop
        start
        ;;
    *)
        echo "Usage: $0 {start|stop|status|restart}"
        exit 1
        ;;
esac
