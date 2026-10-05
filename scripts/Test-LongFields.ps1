param(
    [string]$Image = 'zhiyu-backend:docker-test'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runName = 'long-fields-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6)
$reportDirectory = Join-Path $projectRoot "artifacts/$runName"
$containerName = 'zhiyu-' + $runName

Push-Location $projectRoot
try {
    $imageId = docker image inspect --format '{{.Id}}' $Image
    if ($LASTEXITCODE -ne 0 -or $imageId -notmatch '^sha256:[a-f0-9]{64}$') {
        throw '请先运行 Test-Docker.ps1 构建本地后端镜像。'
    }
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    $evidence = [ordered]@{
        image = $imageId
        container = $containerName
        startedAt = (Get-Date).ToString('o')
        network = 'none (only loopback HTTP)'
        database = 'temporary SQLite on /tmp tmpfs; no existing data volume'
        reportDirectory = $reportDirectory
    }
    $evidence | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $reportDirectory 'runtime.json') -Encoding utf8
    docker run --rm --name $containerName --network none --read-only --no-healthcheck `
        --cap-drop ALL --security-opt no-new-privileges:true --pids-limit 128 --memory 1g `
        --tmpfs '/tmp:size=512m,mode=1777' `
        --mount "type=bind,src=$projectRoot/scripts,dst=/work/scripts,readonly" `
        --mount "type=bind,src=$reportDirectory,dst=/out" `
        --env PYTHONPATH=/srv --env ZHIYU_DATA_DIR=/tmp/bootstrap `
        $imageId python /work/scripts/smoke_long_fields.py --report /out/report.json `
        2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'run.log')
    $runExitCode = $LASTEXITCODE
    $evidence['exitCode'] = $runExitCode
    $evidence['finishedAt'] = (Get-Date).ToString('o')
    $evidence | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $reportDirectory 'runtime.json') -Encoding utf8
    if ($runExitCode -ne 0) { throw "长字段冒烟失败，证据保留于 $reportDirectory" }
    $reportPath = Join-Path $reportDirectory 'report.json'
    if (-not (Test-Path -LiteralPath $reportPath)) { throw '脚本没有写出报告，不能判为通过。' }
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    if ($report.status -ne 'passed') { throw "报告未通过，证据保留于 $reportDirectory" }
    Write-Output "长字段冒烟通过：$reportPath"
} finally {
    Pop-Location
}
