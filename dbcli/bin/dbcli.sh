#!/bin/sh
#
# dbcli 启动器（Linux / macOS）
#
# 布局约定：
#   发行包：<dist>/bin/dbcli.sh       -> 包根 <dist>/      jar 在 jlib/
#   开发树：dbcli/bin/dbcli.sh        -> 模块根 dbcli/     jar 在 target/
# 因此脚本先切到"包根"，再按 jlib/ -> target/ 的顺序找 jar。
#
# 用法：本脚本把所有参数原样透传给 dbcli。没有 start/stop/status 子命令，
# 后台运行就是给 dbcli 加 --daemon true 与 --pidfile：
#
#   bin/dbcli.sh --execute soak.sql --daemon true --pidfile pid/dbcli.pid
#   bin/dbcli.sh --execute soak.sql                      # 不带 --daemon 就是前台
#
# 后台副本的 pid 写在 --pidfile 里；怎么处置它由使用者决定（kill <n>）。
#
# 环境变量：SLACKERDB_JAVA_HOME -> JAVA_HOME -> PATH 上的 java；
# DBCLI_JAVA_OPTS 里的内容会作为 JVM 参数追加到 java 命令行。

SCRIPT_PATH=$(cd "$(dirname "$0")" && pwd)
cd "${SCRIPT_PATH}/.." || exit 1

# 选 java：SLACKERDB_JAVA_HOME -> JAVA_HOME -> PATH 上的 java
if [ -n "${SLACKERDB_JAVA_HOME}" ]; then
    _DBCLI_JAVA="${SLACKERDB_JAVA_HOME}/bin/java"
elif [ -n "${JAVA_HOME}" ]; then
    _DBCLI_JAVA="${JAVA_HOME}/bin/java"
else
    _DBCLI_JAVA=java
fi

# java 不可用时给一条可读错误（否则 exec 失败只会留下 shell 自己的一句报错）
case "${_DBCLI_JAVA}" in
    */*)
        if [ ! -x "${_DBCLI_JAVA}" ]; then
            echo "[dbcli] Cannot start java [${_DBCLI_JAVA}]. Set SLACKERDB_JAVA_HOME or JAVA_HOME to a JDK 17 or newer." 1>&2
            exit 1
        fi
        ;;
    *)
        if ! command -v "${_DBCLI_JAVA}" >/dev/null 2>&1; then
            echo "[dbcli] Cannot start java [${_DBCLI_JAVA}]: not found on PATH. Set SLACKERDB_JAVA_HOME or JAVA_HOME to a JDK 17 or newer." 1>&2
            exit 1
        fi
        ;;
esac

# jar 名不要硬编版本号：用 glob 取第一个匹配
JAR_FILE=""
for f in jlib/slackerdb-dbcli-*.jar; do
    if [ -f "$f" ]; then
        JAR_FILE="$f"
        break
    fi
done
if [ -z "${JAR_FILE}" ]; then
    for f in target/slackerdb-dbcli-*-standalone.jar; do
        if [ -f "$f" ]; then
            JAR_FILE="$f"
            break
        fi
    done
fi
if [ -z "${JAR_FILE}" ]; then
    echo "[dbcli] jar not found. Expected jlib/slackerdb-dbcli-*.jar or target/slackerdb-dbcli-*-standalone.jar" 1>&2
    exit 1
fi

# 唯一的执行路径：exec 把当前 shell 替换成 dbcli（退出码原样传给调用方）。
# java 与 jar 的可用性已在上面判过，因此这里不再有第二条启动路径。
# shellcheck disable=SC2086
exec "${_DBCLI_JAVA}" ${DBCLI_JAVA_OPTS} -jar "${JAR_FILE}" "$@"
