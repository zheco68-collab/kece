param(
    [ValidateSet("python", "cpp", "java", "mysql", "arkts")]
    [string]$Pool = "python",

    [int]$Slots = 2,

    [string]$NodeName = "",

    [string]$ControlUrl = "http://127.0.0.1:8080/api/v1",

    [string]$EnrollmentCode = "",

    [switch]$NoAutoRegister
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pyScript = Join-Path $scriptDir "run_local_judger.py"

$argsList = @(
    $pyScript,
    "--pool", $Pool,
    "--slots", $Slots,
    "--control-url", $ControlUrl
)

if ($NodeName) {
    $argsList += @("--node-name", $NodeName)
}

if ($EnrollmentCode) {
    $argsList += @("--enrollment-code", $EnrollmentCode)
}

if ($NoAutoRegister) {
    $argsList += @("--auto-register=False")
}

python @argsList
