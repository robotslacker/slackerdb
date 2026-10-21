@echo off
setlocal

REM dbcli launcher (Windows)
REM
REM IMPORTANT: keep this file pure ASCII.
REM cmd.exe reads a .bat using the OEM code page, so non-ASCII comments here can be mangled
REM and corrupt command parsing.
REM
REM Layout:
REM   distribution: <dist>\bin\dbcli.bat  -> root <dist>\    jar in jlib\
REM   source tree:  dbcli\bin\dbcli.bat   -> root dbcli\     jar in target\
REM So the script first moves to the "package root", then looks for the jar in jlib\ then target\.
REM
REM Usage: this launcher forwards every argument to dbcli. There is no start/stop/status
REM subcommand; a background run is --daemon true plus --pidfile:
REM
REM   bin\dbcli.bat --execute soak.sql --daemon true --pidfile pid\dbcli.pid
REM   bin\dbcli.bat --execute soak.sql                      (no --daemon: foreground)
REM
REM The background copy writes its own pid into --pidfile; how to look at or stop that
REM process is up to you (taskkill /PID <n>).
REM
REM Environment: SLACKERDB_JAVA_HOME -> JAVA_HOME -> java on PATH;
REM DBCLI_JAVA_OPTS is passed to java as extra JVM options.

cd /D %~dp0
cd ..

REM Pick java: SLACKERDB_JAVA_HOME -> JAVA_HOME -> java on PATH
if not "%SLACKERDB_JAVA_HOME%"=="" (
    set "_DBCLI_JAVA=%SLACKERDB_JAVA_HOME%\bin\java"
) else if not "%JAVA_HOME%"=="" (
    set "_DBCLI_JAVA=%JAVA_HOME%\bin\java"
) else (
    set "_DBCLI_JAVA=java"
)

REM The jar name is matched with a glob, so the version never has to be written here.
set "JAR_FILE="
for %%f in (jlib\slackerdb-dbcli-*.jar) do set "JAR_FILE=%%f"
if not defined JAR_FILE (
    for %%f in (target\slackerdb-dbcli-*-standalone.jar) do set "JAR_FILE=%%f"
)
if not defined JAR_FILE (
    echo [dbcli] jar not found. Expected jlib\slackerdb-dbcli-*.jar or target\slackerdb-dbcli-*-standalone.jar 1>&2
    exit /b 1
)

"%_DBCLI_JAVA%" %DBCLI_JAVA_OPTS% -jar "%JAR_FILE%" %*
exit /b %ERRORLEVEL%
