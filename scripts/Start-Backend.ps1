param(
    [string]$ListenAddress = '127.0.0.1',
    [int]$Port = 8790,
    [switch]$Demo,
    [switch]$Reload
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$backendDirectory = Join-Path $projectDirectory 'backend'
Push-Location $backendDirectory
try {
    if (!(Test-Path -LiteralPath '.venv\Scripts\python.exe')) {
        & uv sync --frozen
        if ($LASTEXITCODE -ne 0) { throw '后端依赖安装失败' }
    }
    $env:ZHIYU_DEMO = if ($Demo) { 'true' } else { 'false' }
    $uvicornArguments = @('-m', 'uvicorn', 'app.main:app', '--host', $ListenAddress, '--port', "$Port")
    if (Test-Path -LiteralPath '.env') { $uvicornArguments += @('--env-file', '.env') }
    if ($Reload) { $uvicornArguments += '--reload' }
    & '.venv\Scripts\python.exe' @uvicornArguments
    if ($LASTEXITCODE -ne 0) { throw '后端启动失败，请查看上述错误' }
} finally {
    Pop-Location
}
