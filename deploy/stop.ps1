# KECE 停止脚本：支持按 $env:JUDGE_NODE_NAME 仅停止单个专池节点，或停止全部本地开发进程。
# judge-mysql / redis 容器与原生 MySQL 保留（它们是常驻基础设施，随 Docker 重启自动拉起）。

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir   = (Resolve-Path "$ScriptDir\..").Path
$LogDir    = Join-Path $RootDir ".logs"

if ($env:JUDGE_NODE_NAME) {
    $PidFile = Join-Path $LogDir "judger-$($env:JUDGE_NODE_NAME).pid"
    if (Test-Path $PidFile) {
        $TargetPid = Get-Content $PidFile -ErrorAction SilentlyContinue
        if ($TargetPid) { Stop-Process -Id ([int]$TargetPid) -Force -ErrorAction SilentlyContinue }
        Remove-Item -Force $PidFile -ErrorAction SilentlyContinue
        Write-Host "已停止专池判题节点: $($env:JUDGE_NODE_NAME)" -ForegroundColor Green
    } else {
        Write-Warning "未找到节点 PID 文件: $PidFile"
    }
    return
}

Get-ChildItem -Path $LogDir -Filter "judger-*.pid" -ErrorAction SilentlyContinue | ForEach-Object {
    $TargetPid = Get-Content $_.FullName -ErrorAction SilentlyContinue
    if ($TargetPid) { Stop-Process -Id ([int]$TargetPid) -Force -ErrorAction SilentlyContinue }
    Remove-Item -Force $_.FullName -ErrorAction SilentlyContinue
}

foreach ($Port in 8080, 5173) {
    Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
}

Write-Host "已停止后端(8080)/专池判题节点/前端(5173)。容器与原生 MySQL 保留。" -ForegroundColor Green
