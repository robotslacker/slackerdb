@echo off
setlocal

REM switch to script directory
cd /D %~dp0
cd ..

REM set java primary is SLACKERDB_JAVA_HOME fallback to JAVA_HOME or default java
if not "%SLACKERDB_JAVA_HOME%"=="" (
    set _SLACKERDB_JAVA="%SLACKERDB_JAVA_HOME%\bin\java"
) else if not "%JAVA_HOME%"=="" (
    set _SLACKERDB_JAVA="%JAVA_HOME%\bin\java"
) else (
    set _SLACKERDB_JAVA=java
)

REM # Settings
set APP_NAME=SLACKERDB-SERVER
set JAR_FILE=jlib\slackerdb-dbserver-0.2.2-standalone.jar
set PID_FILE=pid\slackerdb-dbserver.pid
set CONF_FILE=conf\dbserver.conf

REM ---------------------------------------------------------------------------
REM JVM options only. Database runtime parameters (memory_limit / threads / ports /
REM gates) live in conf\dbserver.conf and the program defaults; this script does not
REM probe memory and does not derive heap from it.
REM
REM Default targets a >=32G host with bounded result sets (LIMIT 10000): heap 8g,
REM direct 2g. Override with SLACKERDB_JAVA_OPTS when needed, e.g.
REM   set SLACKERDB_JAVA_OPTS=-Xms16g -Xmx16g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxDirectMemorySize=4g
REM Keep heap + memory_limit within about 85%% of physical memory.
REM ---------------------------------------------------------------------------
if not defined SLACKERDB_JAVA_OPTS set "SLACKERDB_JAVA_OPTS=-Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxDirectMemorySize=2g -Xlog:gc*:file=logs/gc-%%p.log:time,uptime,level,tags:filecount=5,filesize=20M -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs"
set "JAVA_OPTS=%SLACKERDB_JAVA_OPTS%"

REM control commands (stop/status) only need a small heap
set "CTRL_OPTS=-Xms64m -Xmx256m"

:: === read parameter ===
if "%1"=="" (
    echo Usage: %0 ^<start^|stop^|status^>
    exit /b 1
)

if "%1"=="start" (
    call :start_app
    exit /b
) else if "%1"=="stop" (
    call :stop_app
    exit /b
) else if "%1"=="status" (
    call :status_app
    exit /b
) else (
    echo unknown command: %1
    exit /b 1
)

:: === start app ===
:start_app
if exist %PID_FILE% (
    echo
    goto :eof
)
if not exist logs mkdir logs
echo Starting %APP_NAME%...
echo JVM options: %JAVA_OPTS%
%_SLACKERDB_JAVA% %JAVA_OPTS% -jar %JAR_FILE% --conf %CONF_FILE% --daemon true --pid %PID_FILE% start
goto :eof

:: === stop app ===
:stop_app
if not exist %PID_FILE% (
    echo %APP_NAME% does not exist!
    goto :eof
)
set /p PID=<%PID_FILE%
echo stop %APP_NAME% (PID=%PID%) ...
%_SLACKERDB_JAVA% %CTRL_OPTS% -jar %JAR_FILE% --conf %CONF_FILE% stop
if not exist %PID_FILE% (
    goto :eof
)
taskkill /F /PID %PID% >nul 2>&1
if %errorlevel%==0 (
    echo %APP_NAME% stop successful.
    del %PID_FILE%
) else (
    echo Stopped. Maybe process does not exist!
)
goto :eof

:: === status ===
:status_app
if not exist %PID_FILE% (
    echo %APP_NAME% does not exist!
    goto :eof
)
set /p PID=<%PID_FILE%
%_SLACKERDB_JAVA% %CTRL_OPTS% -jar %JAR_FILE% --conf %CONF_FILE% status
goto :eof
