# pget.ps1 —— 并发分块 + 可续传 + 绕过系统代理（本机 VPN 会拖慢国内站 5 倍）
# 用法：
#   pwsh -File pget.ps1 -Url <url> -Out <file> [-Seed <已有部分文件>] [-Workers 16] [-Sha256 <期望值>]
param(
  [Parameter(Mandatory=$true)][string]$Url,
  [Parameter(Mandatory=$true)][string]$Out,
  [string]$Seed = '',
  [int]$Workers = 16,
  [string]$Sha256 = '',
  [int]$MaxRounds = 6
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
[System.Net.ServicePointManager]::DefaultConnectionLimit = 64
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12

function Log($m) { $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Host $s }

# ---- 探测总长度（Range 0-0，同时拿 Content-Range）----
$noProxy = New-Object System.Net.WebProxy     # 空代理 = 直连
function NewReq($u, $s, $e) {
  $r = [System.Net.HttpWebRequest]::Create($u)
  $r.UserAgent = 'dsh'; $r.Timeout = 600000; $r.ReadWriteTimeout = 600000
  $r.Proxy = $script:noProxy                     # 关键：绕过 VPN 系统代理
  if ($null -ne $s) { $r.AddRange([int64]$s, [int64]$e) }
  return $r
}

$len = 0
try {
  $r = NewReq $Url 0 0
  $resp = $r.GetResponse()
  $cr = $resp.Headers['Content-Range']; $resp.Close()
  if ($cr -match '/(\d+)$') { $len = [int64]$Matches[1] }
} catch { Log "长度探测失败: $($_.Exception.Message)"; exit 1 }
if ($len -le 0) { Log '无法确定文件长度'; exit 1 }
Log ("目标 {0}  总长 {1:N2} GiB  并发 {2}  代理=直连" -f (Split-Path $Out -Leaf), ($len/1GB), $Workers)

# ---- 从 seed 续传 ----
if ($Seed -and (Test-Path $Seed) -and -not (Test-Path $Out)) {
  Move-Item -LiteralPath $Seed -Destination $Out -Force
  Log ("已接管已有部分: {0:N2} GiB" -f ((Get-Item $Out).Length/1GB))
}
$have = if (Test-Path $Out) { (Get-Item $Out).Length } else { 0 }
if ($have -ge $len) { Log '文件已完整' } else { Log ("续传起点 {0:N3} GiB / {1:N2} GiB  剩 {2:N2} GiB" -f ($have/1GB), ($len/1GB), (($len-$have)/1GB)) }

$partDir = "$Out.parts"
New-Item -ItemType Directory -Force -Path $partDir | Out-Null

for ($round = 1; $round -le $MaxRounds; $round++) {
  $have = if (Test-Path $Out) { (Get-Item $Out).Length } else { 0 }
  if ($have -ge $len) { break }

  $remain = $len - $have
  $chunk = [int64][Math]::Ceiling($remain / $Workers)
  Log ("第 {0} 轮：{1} 并发 × {2:N0} MiB" -f $round, $Workers, ($chunk/1MB))
  $sw = [Diagnostics.Stopwatch]::StartNew()

  0..($Workers - 1) | ForEach-Object -Parallel {
    $i = $_
    $start = $using:have + $i * $using:chunk
    if ($start -ge $using:len) { return }
    $end = [Math]::Min($start + $using:chunk - 1, $using:len - 1)
    $need = $end - $start + 1
    $dest = Join-Path $using:partDir ("p{0:D3}.part" -f $i)

    $got = 0
    if (Test-Path $dest) {
      $got = (Get-Item $dest).Length
      if ($got -gt $need) { Remove-Item $dest -Force; $got = 0 }
      if ($got -eq $need) { return }
    }
    for ($t = 1; $t -le 5; $t++) {
      try {
        $r = [System.Net.HttpWebRequest]::Create($using:Url)
        $r.UserAgent='dsh'; $r.Timeout=600000; $r.ReadWriteTimeout=600000
        $r.Proxy = New-Object System.Net.WebProxy
        $r.AddRange([int64]($start + $got), [int64]$end)
        $resp = $r.GetResponse()
        $mode = if ($got -gt 0) { [IO.FileMode]::Append } else { [IO.FileMode]::Create }
        $fs = New-Object IO.FileStream($dest, $mode, [IO.FileAccess]::Write, [IO.FileShare]::Read)
        $resp.GetResponseStream().CopyTo($fs)
        $fs.Close(); $resp.Close()
        if ((Get-Item $dest).Length -eq $need) { return }
        $got = (Get-Item $dest).Length
      } catch {
        Start-Sleep -Seconds (2 * $t)
        if (Test-Path $dest) { $got = (Get-Item $dest).Length }
      }
    }
  } -ThrottleLimit $Workers

  $sw.Stop()
  $parts = Get-ChildItem $partDir -Filter 'p*.part' -ErrorAction SilentlyContinue
  $gotAll = ($parts | Measure-Object Length -Sum).Sum
  Log ("第 {0} 轮结束：{1:N2} / {2:N2} GiB  用时 {3:N1} 分钟  均速 {4:N2} MB/s" -f $round, ($gotAll/1GB), ($remain/1GB), $sw.Elapsed.TotalMinutes, ($gotAll/1MB/[Math]::Max($sw.Elapsed.TotalSeconds,1)))

  # === 关键：追加前必须校验每个分片大小，否则短分片会导致整个文件错位 ===
  $bad = @()
  for ($i = 0; $i -lt $Workers; $i++) {
    $s = $have + $i * $chunk
    if ($s -ge $len) { continue }
    $e = [Math]::Min($s + $chunk - 1, $len - 1)
    $need = $e - $s + 1
    $dest = Join-Path $partDir ("p{0:D3}.part" -f $i)
    $actual = if (Test-Path $dest) { (Get-Item $dest).Length } else { 0 }
    if ($actual -ne $need) { $bad += ("p{0:D3}={1:N1}/{2:N1}MiB" -f $i, ($actual/1MB), ($need/1MB)) }
  }
  if ($bad.Count -gt 0) {
    Log ("有 {0} 个分片不完整，本轮不追加，下一轮重试：{1}" -f $bad.Count, ($bad -join ', '))
    continue
  }

  Log '全部分片完整，追加...'
  $fs = New-Object IO.FileStream($Out, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::Read)
  $parts | Sort-Object Name | ForEach-Object {
    $in = [IO.File]::OpenRead($_.FullName); $in.CopyTo($fs); $in.Close()
    Remove-Item $_.FullName -Force
  }
  $fs.Close()
  $now = (Get-Item $Out).Length
  Log ("主文件 {0:N3} / {1:N2} GiB" -f ($now/1GB), ($len/1GB))
}

Remove-Item $partDir -Recurse -Force -ErrorAction SilentlyContinue
$final = (Get-Item $Out).Length
if ($final -ne $len) { Log ("!! 未完成 {0:N3} / {1:N2} GiB —— 再次运行可继续" -f ($final/1GB), ($len/1GB)); exit 1 }

if ($Sha256) {
  Log '校验 SHA256...'
  $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $Out).Hash.ToLower()
  if ($h -eq $Sha256.ToLower()) { Log '✅ SHA256 匹配' }
  else { Log "❌ SHA256 不匹配  期望 $Sha256  实际 $h"; exit 2 }
}
Log ("完成：{0}  {1:N2} GiB" -f $Out, ($final/1GB))
