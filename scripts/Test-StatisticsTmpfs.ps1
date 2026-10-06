param([string]$Image = 'zhiyu-backend:docker-test')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runId = [Guid]::NewGuid().ToString('N')
$stamp = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $runId.Substring(0, 6)
$reportDirectory = Join-Path "$projectRoot/artifacts" "statistics-tmpfs-$stamp"
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$imageId = docker image inspect --format '{{.Id}}' $Image
if ($LASTEXITCODE -ne 0) { throw '先构建本项目运行镜像。' }
$codeHashes = @('smoke_long_fields.py', 'check_statistics_tmpfs.py') | ForEach-Object {
    @{ file = $_; sha256 = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $_) -Algorithm SHA256).Hash }
}
$runtime = [ordered]@{ image=$imageId; status='running'; runId=$runId; tmpfsBytes=134217728;
    fixtureData='separate disposable 512MiB tmpfs'; sharedVolumeMounted=$false; sourceHashesBefore=$codeHashes }
$failure = $null
try {
    docker run --rm --name "zhiyu-statistics-tmpfs-$stamp" `
        --label com.zhiyu.test.scope=statistics-tmpfs --label "com.zhiyu.test.run=$runId" `
        --network none --read-only --cap-drop ALL --user 10001:10001 --memory 1g `
        --security-opt no-new-privileges `
        --tmpfs /tmp:size=128m,mode=1777 --tmpfs /workdata:size=512m,mode=1777 `
        --mount "type=bind,src=$PSScriptRoot,dst=/work/scripts,readonly" `
        --mount "type=bind,src=$reportDirectory,dst=/out" `
        $imageId python -u /work/scripts/check_statistics_tmpfs.py --data-dir /workdata/fixture --report /out/report.json `
        2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'run.log')
    $runtime['exitCode'] = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw '临时排序空间反例未通过。' }
    $report = Get-Content -LiteralPath (Join-Path $reportDirectory 'report.json') -Raw | ConvertFrom-Json
    if ($report.status -ne 'passed' -or -not $report.old_query_reproduced_full -or
        -not $report.new_statistics_without_full_text_sort -or -not $report.originals_unchanged) {
        throw '统计夹具没有完整的旧查询失败、新统计通过证据。'
    }
    foreach ($entry in $codeHashes) {
        if ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $entry.file) -Algorithm SHA256).Hash -cne $entry.sha256) {
            throw '夹具运行期间源码改变。'
        }
    }
    $runtime['sourceHashesUnchanged'] = $true
    $runtime['status'] = 'passed'
} catch {
    $failure = $_
    $runtime['status'] = 'failed'
    $runtime['failure'] = $_.Exception.Message
} finally {
    $runtime['finishedAt'] = (Get-Date).ToString('o')
    $runtime | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $reportDirectory 'runtime.json') -Encoding utf8
}
if ($null -ne $failure) { throw $failure }
Write-Output "统计临时空间反例通过：$reportDirectory/report.json"
