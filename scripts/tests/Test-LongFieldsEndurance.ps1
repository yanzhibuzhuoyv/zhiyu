$ErrorActionPreference = 'Stop'
$driver = Join-Path (Split-Path -Parent $PSScriptRoot) 'Test-LongFieldsEndurance.ps1'
$driverTokens = $null
$driverErrors = $null
$driverAst = [System.Management.Automation.Language.Parser]::ParseFile($driver, [ref]$driverTokens, [ref]$driverErrors)
if ($driverErrors.Count -ne 0) { throw 'Driver 语法无效。' }
$definition = $driverAst.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-TemporaryVolume'
}, $true)
if ($null -eq $definition) { throw '缺少临时卷归属校验。' }
. ([scriptblock]::Create($definition.Extent.Text))

# Override only this test process's docker command; no Docker mutation occurs.
function docker {
    param([Parameter(ValueFromRemainingArguments = $true)][object[]]$DockerArgs)
    if ($DockerArgs.Count -ne 3 -or $DockerArgs[0] -ne 'volume' -or $DockerArgs[1] -ne 'inspect') {
        throw '归属校验调用了非只读命令。'
    }
    $global:LASTEXITCODE = $script:inspectExitCode
    if ($script:inspectExitCode -eq 0) { $script:volumeFixture | ConvertTo-Json -Depth 5 }
}

$run = '1234567890abcdef1234567890abcdef'
$name = 'zhiyu-long-endurance-' + $run
$validLabels = @{ 'com.zhiyu.test.scope' = 'long-fields-endurance'; 'com.zhiyu.test.run' = $run }
$script:inspectExitCode = 0
$script:volumeFixture = @{ Name = $name; Labels = $validLabels }
Assert-TemporaryVolume -Name $name -Run $run
$checked = 1

$cases = @(
    @{ Name = 'zhiyu-docker-test_test-data'; Labels = $validLabels },
    @{ Name = $name; Labels = @{ 'com.zhiyu.test.scope' = 'another-task'; 'com.zhiyu.test.run' = $run } },
    @{ Name = $name; Labels = @{ 'com.zhiyu.test.scope' = 'long-fields-endurance'; 'com.zhiyu.test.run' = 'another-run' } },
    @{ Name = $name; Labels = @{} },
    @{ Name = $name; Labels = $null }
)
foreach ($case in $cases) {
    $script:volumeFixture = $case
    $rejected = $false
    try { Assert-TemporaryVolume -Name $name -Run $run } catch { $rejected = $true }
    if (-not $rejected) { throw '错误的临时卷归属被接受。' }
    $checked++
}
$script:volumeFixture = @{ Name = $name; Labels = $validLabels }
foreach ($wrongRun in @('another-run', '')) {
    $rejected = $false
    try { Assert-TemporaryVolume -Name $name -Run $wrongRun } catch { $rejected = $true }
    if (-not $rejected) { throw '错误的运行ID被接受。' }
    $checked++
}
$script:inspectExitCode = 1
$rejected = $false
try { Assert-TemporaryVolume -Name $name -Run $run } catch { $rejected = $true }
if (-not $rejected) { throw 'Docker inspect 失败仍被接受。' }
$checked++
$global:LASTEXITCODE = 0
Write-Output "$checked 临时卷归属反例/正常检查通过；无实际Docker修改。"
