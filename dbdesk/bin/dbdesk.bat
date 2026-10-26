@echo off
setlocal

REM switch to the distribution root (bin/..)
cd /D "%~dp0"
cd ..

if not "%SLACKERDB_JAVA_HOME%"=="" (
    set "_SLACKERDB_JAVA=%SLACKERDB_JAVA_HOME%\bin\java"
) else if not "%JAVA_HOME%"=="" (
    set "_SLACKERDB_JAVA=%JAVA_HOME%\bin\java"
) else (
    set "_SLACKERDB_JAVA=java"
)

REM DbDesk is an independent management UI; it does not start or manage dbserver.
"%_SLACKERDB_JAVA%" -jar "jlib\slackerdb-dbdesk-0.2.2-standalone.jar" %*
