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
set APP_NAME=SLACKERDB-proxy
set JAR_FILE=jlib\slackerdb-dbproxy-0.1.5-standalone.jar
set PID_FILE=pid\slackerdb-dbproxy.pid
set JAVA_OPTS=
set CONF_FILE=conf\dbproxy.conf

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
echo Starting %APP_NAME%...
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
%_SLACKERDB_JAVA% %JAVA_OPTS% -jar %JAR_FILE% --conf %CONF_FILE% stop
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
%_SLACKERDB_JAVA% %JAVA_OPTS% -jar %JAR_FILE% --conf %CONF_FILE% status
goto :eof
