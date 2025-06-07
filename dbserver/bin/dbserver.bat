@echo off
setlocal

:: === 配置部分 ===
set APP_NAME=SLACKERDB-SERVER
set JAR_FILE=slackerdb-dbserver-${project.version}-standalone.jar
set PID_FILE=app.pid
set JAR_PATH=jlib\%JAR_NAME%
set JAVA_OPTS=
set PID_FILE=pid\dbserver.pid
set CONF_FILE=conf\dbserver.conf

:: === 参数判断 ===
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
    echo 未知命令: %1
    exit /b 1
)

:: === 启动函数 ===
:start_app
if exist %PID_FILE% (
    echo
    goto :eof
)
echo Starting %APP_NAME%...
start "" java %JAVA_OPTS% -jar %JAR_FILE%
:: 等待 1 秒以确保进程启动
timeout /t 1 >nul
:: 获取 java.exe 进程的 PID
for /f "tokens=2" %%i in ('tasklist /fi "imagename eq java.exe" /fo csv ^| findstr /i "%JAR_FILE%"') do (
    echo %%i > %PID_FILE%
)
echo 启动完成
goto :eof

:: === 停止函数 ===
:stop_app
if not exist %PID_FILE% (
    echo 应用未在运行中
    goto :eof
)
set /p PID=<%PID_FILE%
echo 停止 %APP_NAME%（PID=%PID%）...
taskkill /F /PID %PID% >nul 2>&1
if %errorlevel%==0 (
    echo 停止成功
    del %PID_FILE%
) else (
    echo 停止失败，可能进程已不存在
)
goto :eof

:: === 状态函数 ===
:status_app
if not exist %PID_FILE% (
    echo 应用未在运行中
    goto :eof
)
set /p PID=<%PID_FILE%
tasklist /FI "PID eq %PID%" | findstr /i java.exe >nul
if %errorlevel%==0 (
    echo 应用正在运行中（PID=%PID%）
) else (
    echo 找不到 PID=%PID% 的进程，清理状态文件
    del %PID_FILE%
)
goto :eof
