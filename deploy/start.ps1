# KECE 本地开发启动器 (Windows PowerShell)
#
# 不再 docker compose 起整套容器：复用已在运行的原生 MySQL(3306) 与 judge-mysql/redis 容器，
# 在后台拉起后端(mvnw)/判题机(judger.exe)/前端(npm run dev)三个本地进程，日志写入仓库 .logs/。
# 已运行的进程/容器会被自动跳过，因此可安全重复执行。

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir   = (Resolve-Path "$ScriptDir\..").Path
$LogDir    = Join-Path $RootDir ".logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Test-Port([int]$Port) {
    return $null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

# ---- 0. 工具链（优先读取环境变量与系统 PATH，回退到用户目录默认路径） ----
if (-not $env:JAVA_HOME) {
    $defaultJdk = Join-Path $env:USERPROFILE ".jdks\jdk-17.0.20.1+1"
    if (Test-Path $defaultJdk) { $env:JAVA_HOME = $defaultJdk }
}
if (-not $env:GOROOT) {
    $defaultGo = Join-Path $env:USERPROFILE "go-sdk\go"
    if (Test-Path $defaultGo) { $env:GOROOT = $defaultGo }
}
if (-not $env:GOPROXY)  { $env:GOPROXY  = "https://goproxy.cn,direct" }
if ($env:GOROOT)        { $env:PATH = "$env:GOROOT\bin;$env:PATH" }
if ($env:JAVA_HOME)     { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }

# ---- 1. 基础设施：judge-mysql / redis 容器（原生 MySQL 不在此启动，见下方提示） ----
if (-not (Test-Port 3306)) {
    Write-Warning "3306 无监听：业务 MySQL 未启动，后端将无法连库。请先启动本机 MySQL 服务。"
}

if (docker ps -a --format "{{.Names}}" | Select-String -Quiet "^kece-judge-mysql$") {
    if (-not (docker ps --format "{{.Names}}" | Select-String -Quiet "^kece-judge-mysql$")) {
        docker start kece-judge-mysql | Out-Null
    }
} else {
    docker run -d --name kece-judge-mysql --restart unless-stopped `
      -p 3307:3306 -e MYSQL_ROOT_PASSWORD=judge123 mysql:8.0 `
      --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci | Out-Null
}

if (docker ps -a --format "{{.Names}}" | Select-String -Quiet "^kece-redis$") {
    if (-not (docker ps --format "{{.Names}}" | Select-String -Quiet "^kece-redis$")) {
        docker start kece-redis | Out-Null
    }
} else {
    docker run -d --name kece-redis --restart unless-stopped -p 6379:6379 redis:7-alpine | Out-Null
}

# ---- 2. 判题机（先编译再后台启动） ----
if (Get-Process -Name judger -ErrorAction SilentlyContinue) {
    Write-Host "判题机已在运行，跳过" -ForegroundColor Yellow
} else {
    Push-Location "$RootDir\judger"
    try {
        go build -o judger.exe ./cmd/judger
        if (-not (Test-Path "judger.exe")) { throw "判题机编译失败，未生成 judger.exe" }
    } finally { Pop-Location }

    $env:REDIS_ADDR           = if ($env:REDIS_ADDR)           { $env:REDIS_ADDR }           else { "127.0.0.1:6379" }
    $env:WORKER_CONCURRENCY   = if ($env:WORKER_CONCURRENCY)   { $env:WORKER_CONCURRENCY }   else { "8" }
    $env:WORKSPACE_DIR        = if ($env:WORKSPACE_DIR)        { $env:WORKSPACE_DIR }        else { (Join-Path $env:USERPROFILE "judge-workspace") }
    $env:JUDGE_MYSQL_HOST     = if ($env:JUDGE_MYSQL_HOST)     { $env:JUDGE_MYSQL_HOST }     else { "127.0.0.1" }
    $env:JUDGE_MYSQL_PORT     = if ($env:JUDGE_MYSQL_PORT)     { $env:JUDGE_MYSQL_PORT }     else { "3307" }
    $env:JUDGE_MYSQL_USER     = if ($env:JUDGE_MYSQL_USER)     { $env:JUDGE_MYSQL_USER }     else { "root" }
    $env:JUDGE_MYSQL_PASSWORD = if ($env:JUDGE_MYSQL_PASSWORD) { $env:JUDGE_MYSQL_PASSWORD } else { "judge123" }

    $env:TESTCASE_DIR         = if ($env:TESTCASE_DIR)         { $env:TESTCASE_DIR }         else { "$RootDir\backend\testcases" }

    Start-Process -FilePath "$RootDir\judger\judger.exe" -WorkingDirectory "$RootDir\judger" `
      -RedirectStandardOutput (Join-Path $LogDir "judger.log") `
      -RedirectStandardError  (Join-Path $LogDir "judger.err.log") -WindowStyle Hidden
    Write-Host "判题机已启动（日志：.logs\judger.log）" -ForegroundColor Green
}

# ---- 3. 后端 ----
if (Test-Port 8080) {
    Write-Host "后端已在运行 (8080)，跳过" -ForegroundColor Yellow
} else {
    $BackendCmd = "set `"JAVA_HOME=$env:JAVA_HOME`" & set `"PATH=$env:JAVA_HOME\bin;%PATH%`" & call `"$RootDir\backend\mvnw.cmd`" spring-boot:run"
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c `"$BackendCmd`"" `
      -WorkingDirectory "$RootDir\backend" `
      -RedirectStandardOutput (Join-Path $LogDir "backend.log") `
      -RedirectStandardError  (Join-Path $LogDir "backend.err.log") -WindowStyle Hidden
    Write-Host "后端已启动 (8080)…" -ForegroundColor Green
}

# ---- 4. 前端 ----
if (Test-Port 5173) {
    Write-Host "前端已在运行 (5173)，跳过" -ForegroundColor Yellow
} else {
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c npm run dev" `
      -WorkingDirectory "$RootDir\frontend" `
      -RedirectStandardOutput (Join-Path $LogDir "frontend.log") `
      -RedirectStandardError  (Join-Path $LogDir "frontend.err.log") -WindowStyle Hidden
    Write-Host "前端已启动 (5173)…" -ForegroundColor Green
}

Write-Host ""
Write-Host "后端  http://localhost:8080/api/v1" -ForegroundColor Cyan
Write-Host "前端  http://localhost:5173"        -ForegroundColor Cyan
Write-Host "日志  $LogDir"
Write-Host "停止  .\deploy\stop.ps1"
