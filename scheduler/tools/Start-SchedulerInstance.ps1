<#
.SYNOPSIS
    启动一个带调度能力（ETL）的 SlackerDB 实例（本地开发/验收环境）。

.DESCRIPTION
    调度能力已经内置在 dbserver 里，不再是插件 JAR。本脚本一条命令把"能跑调度器"的环境搭起来：
      1) 准备 scheduler.home / projects（含 default/ 父项目与 demo 模板）；
      2) 解析 HOP：给了 -HopRunScript 就用真的；没给就生成一个 **桩脚本**，
         只用于验证"链路通"，不会真的执行 HOP 工作流（能力里会如实显示 hop.ready）；
      3) 配置齐备（portX + home + projectHome）后启动 dbserver —— 不传 plugins_dir。

    注意：home / projectHome 指定的目录必须已经存在，服务端不会自动创建（配错即拒绝启动）。

.PARAMETER HopRunScript
    真实的 hop-run.bat / hop-run.sh 绝对路径。不传则使用桩脚本。

.EXAMPLE
    pwsh -File dbserver-scheduler/tools/Start-SchedulerInstance.ps1
    pwsh -File dbserver-scheduler/tools/Start-SchedulerInstance.ps1 -HopRunScript "C:\hop\hop-run.bat"
#>
param(
    [int]$Port = 4410,
    [int]$PortX = 8410,
    [string]$DataDir = "dbserver-scheduler/target/m2db",
    [string]$SchedulerHome = "dbserver-scheduler/target/sched-home",
    [string]$Projects = "dbserver-scheduler/target/sched-projects",
    [string]$HopRunScript = "",
    [switch]$NoStart
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $repo

function Ensure-Dir([string]$path) {
    if (-not (Test-Path $path)) { New-Item -ItemType Directory -Force -Path $path | Out-Null }
}

# 1) 目录与模板（服务端不自动创建，所以这里显式建好）
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

# 2) HOP 运行脚本
if ([string]::IsNullOrWhiteSpace($HopRunScript)) {
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

# 3) 启动
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
