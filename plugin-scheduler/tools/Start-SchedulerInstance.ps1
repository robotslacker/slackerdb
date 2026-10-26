<#
.SYNOPSIS
    启动一个带 plugin-scheduler 的 SlackerDB 实例（M2 桶 A 的本地开发/验收环境）。

.DESCRIPTION
    一条命令把"能跑调度器"的环境搭起来：
      1) 确保插件 jar 已构建，并拷到一个干净的 plugin 目录；
      2) 准备 scheduler.home / projects（含 default/ 父项目与 demo 模板）；
      3) 解析 HOP：给了 -HopRunScript 就用真的；没给就生成一个 **桩脚本**，
         只用于验证"链路通"，不会真的执行 HOP 工作流（能力里会如实显示 hop.ready）；
      4) 用正确的 --plugin-scheduler.* 参数启动 dbserver（仅本地测试环境）。

.PARAMETER HopRunScript
    真实的 hop-run.bat / hop-run.sh 绝对路径。不传则使用桩脚本。

.EXAMPLE
    pwsh -File plugin-scheduler/tools/Start-SchedulerInstance.ps1
    pwsh -File plugin-scheduler/tools/Start-SchedulerInstance.ps1 -HopRunScript "C:\hop\hop-run.bat"
#>
param(
    [int]$Port = 4410,
    [int]$PortX = 8410,
    [string]$DataDir = "plugin-scheduler/target/m2db",
    [string]$SchedulerHome = "plugin-scheduler/target/sched-home",
    [string]$Projects = "plugin-scheduler/target/sched-projects",
    [string]$HopRunScript = "",
    [string]$PluginDir = "plugin-scheduler/target/scheduler-plugin",
    [switch]$NoStart
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $repo

function Ensure-Dir([string]$path) {
    if (-not (Test-Path $path)) { New-Item -ItemType Directory -Force -Path $path | Out-Null }
}

# 1) 插件 jar
$pluginJar = "plugin-scheduler\target\slackerdb-plugin-scheduler-0.2.2.jar"
if (-not (Test-Path $pluginJar)) {
    Write-Host "[m2] 构建 plugin-scheduler ..."
    mvn -B -pl plugin-scheduler -am -DskipTests package | Out-Host
}
Ensure-Dir $PluginDir
Copy-Item $pluginJar $PluginDir -Force

# 2) 目录与模板
Ensure-Dir $DataDir
Ensure-Dir $SchedulerHome
Ensure-Dir (Join-Path $Projects "default\conf")
Ensure-Dir (Join-Path $Projects "demo\conf")

$defaultTemplate = Join-Path $Projects "default\conf\defaultSchedulerTask.json"
if (-not (Test-Path $defaultTemplate)) {
    @'
{
  "tasks": []
}
'@ | Set-Content -Path $defaultTemplate -Encoding utf8
}

$demoTemplate = Join-Path $Projects "demo\conf\defaultSchedulerTask.json"
if (-not (Test-Path $demoTemplate)) {
    @'
{
  "tasks": [
    {
      "taskName": "load_fact",
      "taskScript": "load_fact.hwf",
      "taskScriptType": "HOP",
      "taskRunPolicy": "CRONTAB",
      "taskCrontabExpr": "0 0 3 * * ?",
      "taskFailPolicy": "STOP",
      "taskStartupOrder": 10
    }
  ]
}
'@ | Set-Content -Path $demoTemplate -Encoding utf8
}

# demo 模板里引用的工作流必须真实存在，否则调度器在调用 hop-run 之前就会失败（fail fast，合理）
$demoWorkflow = Join-Path $Projects "demo\load_fact.hwf"
if (-not (Test-Path $demoWorkflow)) {
    @'
{
  "name": "load_fact",
  "filename": "load_fact.hwf",
  "paths": [],
  "description": "demo 占位工作流：接真 HOP 时替换成真实 .hwf"
}
'@ | Set-Content -Path $demoWorkflow -Encoding utf8
}

# 3) HOP 运行脚本
$usingStub = $false
if ([string]::IsNullOrWhiteSpace($HopRunScript)) {
    $usingStub = $true
    $binDir = Join-Path $SchedulerHome "hop\bin"
    Ensure-Dir $binDir
    $HopRunScript = Join-Path $binDir "hop-run.cmd"
    @'
@echo off
rem ============================================================
rem  Stub hop-run: proves the scheduler -> HOP call chain only.
rem  It does NOT run a real workflow. For real HOP use -HopRunScript.
rem  (Comments kept ASCII on purpose: the file is written as ASCII.)
rem ============================================================
echo [hop-run-stub] %*
rem ping instead of timeout: timeout fails without a console
ping -n 2 127.0.0.1 > nul
exit /b 0
'@ | Set-Content -Path $HopRunScript -Encoding ascii
    Write-Host "[m2] 未提供 -HopRunScript，使用桩脚本：$HopRunScript（HOP 任务只会走通链路，不真跑）"
}

$hopAbsolute = (Resolve-Path $HopRunScript).Path

# 4) 启动
$serverJar = "dbserver\target\slackerdb-dbserver-0.2.2-standalone.jar"
if (-not (Test-Path $serverJar)) {
    throw "找不到 $serverJar，请先 mvn -pl dbserver -am -DskipTests package"
}

$args = @(
    "start",
    "--port", $Port,
    "--portX", $PortX,
    "--data", "m2",
    "--data_dir", (Resolve-Path $DataDir).Path,
    "--plugins_dir", (Resolve-Path $PluginDir).Path,
    "--plugin-scheduler.home", (Resolve-Path $SchedulerHome).Path,
    "--plugin-scheduler.projectHome", (Resolve-Path $Projects).Path,
    "--plugin-scheduler.hop.runScript", $hopAbsolute
)

Write-Host "[m2] 启动 dbserver: $($args -join ' ')"
if ($NoStart) {
    Write-Host "[m2] -NoStart：只准备环境，不启动进程"
    return
}

java -jar $serverJar @args
