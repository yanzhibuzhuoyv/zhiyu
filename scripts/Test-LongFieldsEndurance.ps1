param(
    [ValidateSet(1000, 10000)][int]$Turns = 10000,
    [string]$Image = 'zhiyu-backend:docker-test',
    [switch]$KeepFailedVolume
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runId = [Guid]::NewGuid().ToString('N')
$runName = 'long-endurance-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $runId.Substring(0, 6)
$reportDirectory = Join-Path $projectRoot "artifacts/$runName"
$containerName = 'zhiyu-' + $runName
$volumeName = 'zhiyu-long-endurance-' + $runId
$volumeCreated = $false
$failure = $null
$evidence = [ordered]@{}

function Assert-TemporaryVolume {
    param([string]$Name, [string]$Run)
    $volumeJson = docker volume inspect $Name
    if ($LASTEXITCODE -ne 0) { throw '无法核验临时测试卷，停止清理。' }
    $volume = ($volumeJson | ConvertFrom-Json)[0]
    if ($volume.Name -cne $Name -or $Name -cne ('zhiyu-long-endurance-' + $Run) -or
        $volume.Labels.'com.zhiyu.test.scope' -cne 'long-fields-endurance' -or
        $volume.Labels.'com.zhiyu.test.run' -cne $Run) {
        throw '临时卷名称或标签不匹配，停止清理。'
    }
}

Push-Location $projectRoot
try {
    $imageId = docker image inspect --format '{{.Id}}' $Image
    if ($LASTEXITCODE -ne 0 -or $imageId -notmatch '^sha256:[a-f0-9]{64}$') {
        throw '请先构建本地后端运行镜像。'
    }
    if (-not (Test-Path -LiteralPath 'scripts/stress_long_fields.py')) {
        throw '缺少长字段耐久脚本。'
    }
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    $codeHashes = @(Get-ChildItem -LiteralPath "$projectRoot/scripts" -Filter '*long_fields*.py' |
        Sort-Object Name | ForEach-Object {
            @{ file = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
        })
    $evidence = [ordered]@{
        image = $imageId; container = $containerName; volume = $volumeName; runId = $runId
        requestedTurns = $Turns; startedAt = (Get-Date).ToString('o'); status = 'preparing'
        network = 'none (loopback HTTP only)'; database = 'new named volume, no shared data'
        memoryLimitBytes = 1073741824; scriptHashesBefore = $codeHashes
        reportDirectory = $reportDirectory; temporaryVolumeRemoved = $false
        keepFailedVolumeRequested = [bool]$KeepFailedVolume
    }
    $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $reportDirectory 'runtime.json') -Encoding utf8
    $createdName = docker volume create --label com.zhiyu.test.scope=long-fields-endurance `
        --label "com.zhiyu.test.run=$runId" $volumeName
    if ($LASTEXITCODE -ne 0 -or $createdName -cne $volumeName) { throw '创建临时测试卷失败。' }
    $volumeCreated = $true
    Assert-TemporaryVolume -Name $volumeName -Run $runId
    $evidence['status'] = 'running'
    $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $reportDirectory 'runtime.json') -Encoding utf8
    docker run --rm --name $containerName --network none --read-only --no-healthcheck `
        --label com.zhiyu.test.scope=long-fields-endurance --label "com.zhiyu.test.run=$runId" `
        --cap-drop ALL --security-opt no-new-privileges:true --pids-limit 128 --memory 1g `
        --tmpfs '/tmp:size=128m,mode=1777' `
        --mount "type=volume,src=$volumeName,dst=/data" `
        --mount "type=bind,src=$projectRoot/scripts,dst=/work/scripts,readonly" `
        --mount "type=bind,src=$reportDirectory,dst=/out" `
        --env PYTHONPATH=/srv --env ZHIYU_DATA_DIR=/tmp/bootstrap `
        $imageId python -u /work/scripts/stress_long_fields.py --turns $Turns `
        --report /out/report.json --data-dir /data/run --progress-every 100 --checkpoint-pause-seconds 5 `
        2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'run.log')
    $evidence['exitCode'] = $LASTEXITCODE
    if ($evidence.exitCode -ne 0) { throw '长字段耐久进程未通过。' }
    $reportPath = Join-Path $reportDirectory 'report.json'
    if (-not (Test-Path -LiteralPath $reportPath)) { throw '没有测试报告，不能判为通过。' }
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    if ($report.status -ne 'passed' -or $report.completed_turns -ne $Turns) {
        throw '报告状态或实际轮数不符合要求。'
    }
    foreach ($entry in $codeHashes) {
        if ((Get-FileHash -LiteralPath (Join-Path "$projectRoot/scripts" $entry.file) -Algorithm SHA256).Hash -cne $entry.sha256) {
            throw '运行期间测试源码改变，不能判为有效完成。'
        }
    }
    $evidence['scriptHashesUnchanged'] = $true
    $evidence['status'] = 'passed'
} catch {
    $failure = $_
    $evidence['status'] = 'failed'
    $evidence['failure'] = $_.Exception.Message
} finally {
    if ($volumeCreated) {
        try {
            Assert-TemporaryVolume -Name $volumeName -Run $runId
            if ($null -ne $failure -and $KeepFailedVolume) {
                $evidence['temporaryVolumeRetainedForDebug'] = $true
            } else {
                docker volume rm -- $volumeName | Out-Null
                if ($LASTEXITCODE -ne 0) { throw '临时卷仍在使用或清理失败。' }
                $evidence['temporaryVolumeRemoved'] = $true
            }
        } catch {
            $evidence['cleanupFailure'] = $_.Exception.Message
            $evidence['status'] = 'failed'
            if ($null -eq $failure) { $failure = $_ }
        }
    }
    $evidence['finishedAt'] = (Get-Date).ToString('o')
    if (Test-Path -LiteralPath $reportDirectory) {
        $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $reportDirectory 'runtime.json') -Encoding utf8
    }
    Pop-Location
}
if ($null -ne $failure) {
    Write-Output "未通过，证据保留于 $reportDirectory"
    throw $failure
}
Write-Output "长字段耐久通过：$reportDirectory/report.json"
