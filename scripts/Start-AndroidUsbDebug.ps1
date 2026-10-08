param(
    [ValidatePattern('^[A-Za-z0-9._-]+$')][string]$Serial = '',
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$ApkPath = '',
    [switch]$RestartApp
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw '找不到 adb，请指定 -AdbPath。' }
if ($ApkPath -and !(Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw '找不到 APK，请先完成 debug 构建。' }

function Invoke-TaskAdb {
    param([string[]]$Arguments)
    # Windows PowerShell 5.1 treats normal native stderr as an error record.
    # Capture it locally and use the actual exit status to determine failure.
    $ErrorActionPreference = 'Continue'
    $result = @(& $AdbPath @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "ADB 操作失败：$($result -join ' ')" }
    return $result
}

# Do not send credentials or expose the backend on a LAN interface.
$health = Invoke-RestMethod 'http://127.0.0.1:8791/health' -TimeoutSec 10
if ($health.status -ne 'ok') { throw '8791 后端尚未就绪。' }
$catalog = @(Invoke-RestMethod 'http://127.0.0.1:8791/api/providers' -TimeoutSec 10)
if (!($catalog | Where-Object { $_.id -eq 'deepseek' })) { throw '8791 未返回预期的知语服务商目录。' }

$deviceLines = Invoke-TaskAdb @('devices', '-l')
$devices = @($deviceLines | ForEach-Object {
    if ("$_" -match '^(\S+)\s+(device|unauthorized|offline)\b(.*)$') {
        [PSCustomObject]@{ Serial = $matches[1]; State = $matches[2]; Details = $matches[3] }
    }
})
$physicalDevices = @($devices | Where-Object { $_.Serial -notmatch '^emulator-|:' })
if ($Serial) { $physicalDevices = @($physicalDevices | Where-Object { $_.Serial -eq $Serial }) }
if ($physicalDevices.Count -eq 0) { throw '未识别 USB 手机，请开启 USB 调试并允许电脑授权。' }
if ($physicalDevices.Count -ne 1) { throw '存在多台 USB 手机，请用 -Serial 指定目标。' }
$device = $physicalDevices[0]
if ($device.State -ne 'device') { throw "手机状态为 $($device.State)，请解锁手机并允许 USB 调试。" }
$Serial = $device.Serial

$mappings = Invoke-TaskAdb @('-s', $Serial, 'reverse', '--list')
$existing = @($mappings | Where-Object { "$_" -match '\s+tcp:8791\s+' })
if ($existing.Count -gt 0) {
    if (@($existing | Where-Object { "$_" -notmatch '\s+tcp:8791\s+tcp:8791\s*$' }).Count -gt 0) {
        throw '手机 8791 已有其他端口映射，拒绝覆盖。'
    }
} else {
    Invoke-TaskAdb @('-s', $Serial, 'reverse', '--no-rebind', 'tcp:8791', 'tcp:8791') | Out-Null
}
if ($ApkPath) {
    # An upgrade preserves the existing Key, unique binding and app data. Never uninstall or clear.
    Invoke-TaskAdb @('-s', $Serial, 'install', '-r', (Resolve-Path -LiteralPath $ApkPath).Path) | Out-Null
}
if ($RestartApp) { Invoke-TaskAdb @('-s', $Serial, 'shell', 'am', 'force-stop', 'com.zhiyu.app') | Out-Null }
Invoke-TaskAdb @('-s', $Serial, 'shell', 'am', 'start', '-n', 'com.zhiyu.app/.MainActivity') | Out-Null
Write-Output "已连接 USB 手机 $Serial → 电脑后端 8791。重新插线或重启 ADB 后请重新运行此脚本。"
