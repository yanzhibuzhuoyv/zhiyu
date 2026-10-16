# pget_curl.ps1 —— 用 curl 做并发分块下载（curl 已验证能稳定跟随 302 到 CDN）
#   - 每块独立重试，块大小不对就不组装（避免错位）
#   - 组装后可选 SHA256 校验
param(
  [Parameter(Mandatory=$true)][string]$Url,
  [Parameter(Mandatory=$true)][string]$Out,
  [int]$Workers = 16,
  [string]$Sha256 = '',
  [int]$MaxRounds = 8
)
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
function Log($m){ $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Host $s }

$curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
if (-not $curl) { Log 'curl.exe 不存在'; exit 1 }

# 总长度
$len = 0
$hdr = & $curl -sIL --max-time 30 $Url 2>&1
foreach ($line in $hdr) {
  if ($line -match '^content-length:\s*(\d+)') { $len = [int64]$Matches[1] }
}
if ($len -le 0) { Log '无法获取总长度'; exit 1 }
Log ("{0}  总长 {1:N2} GiB  并发 {2}" -f (Split-Path $Out -Leaf), ($len/1GB), $Workers)

$partDir = "$Out.parts"
New-Item -ItemType Directory -Force -Path $partDir | Out-Null

for ($round = 1; $round -le $MaxRounds; $round++) {
  $have = if (Test-Path $Out) { (Get-Item $Out).Length } else { 0 }
  if ($have -ge $len) { break }
  $remain = $len - $have
  $chunk = [int64][Math]::Ceiling($remain / $Workers)
  Log ("第 {0} 轮：剩 {1:N2} GiB，{2} 并发 × {3:N0} MiB" -f $round, ($remain/1GB), $Workers, ($chunk/1MB))

  # 生成任务清单
  $tasks = @()
  for ($i = 0; $i -lt $Workers; $i++) {
    $s = $have + $i * $chunk
    if ($s -ge $len) { continue }
    $e = [Math]::Min($s + $chunk - 1, $len - 1)
    $tasks += [pscustomobject]@{ i = $i; start = $s; end = $e; need = $e - $s + 1
                                 dest = (Join-Path $partDir ("p{0:D3}.part" -f $i)) }
  }
  $sw = [Diagnostics.Stopwatch]::StartNew()

  # 并发跑 curl（每块内部自带重试）
  $tasks | ForEach-Object -Parallel {
    $t = $_
    $range = "{0}-{1}" -f $t.start, $t.end
    & curl.exe -sL --fail --retry 6 --retry-delay 2 --retry-all-errors `
        --max-time 1800 --connect-timeout 20 -r $range -o $t.dest $using:Url 2>$null
  } -ThrottleLimit $Workers

  $sw.Stop()
  $got = ($tasks | Where-Object { Test-Path $_.dest } | ForEach-Object { (Get-Item $_.dest).Length } | Measure-Object -Sum).Sum
  Log ("第 {0} 轮结束：{1:N2} / {2:N2} GiB  用时 {3:N1} 分钟  均速 {4:N2} MB/s" -f $round, ($got/1GB), ($remain/1GB), $sw.Elapsed.TotalMinutes, ($got/1MB/[Math]::Max($sw.Elapsed.TotalSeconds,1)))

  # 严格校验每块大小，不全就不组装
  $bad = $tasks | Where-Object {
    $a = if (Test-Path $_.dest) { (Get-Item $_.dest).Length } else { 0 }
    $a -ne $_.need
  }
  if ($bad) {
    Log ("有 {0}/{1} 块不完整，本轮不组装，下一轮重试这些块" -f $bad.Count, $tasks.Count)
    $bad | ForEach-Object {
      Log ("   p{0:D3}: {1:N0}/{2:N0} MiB" -f $_.i,
           $(if(Test-Path $_.dest){(Get-Item $_.dest).Length/1MB}else{0}), ($_.need/1MB))
      Remove-Item $_.dest -Force -ErrorAction SilentlyContinue
    }
    continue
  }

  Log '全部块完整，按序组装...'
  $fs = New-Object IO.FileStream($Out, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::Read)
  $tasks | Sort-Object i | ForEach-Object {
    $in = [IO.File]::OpenRead($_.dest); $in.CopyTo($fs); $in.Close()
    Remove-Item $_.dest -Force
  }
  $fs.Close()
  Log ("主文件 {0:N3} / {1:N2} GiB" -f ((Get-Item $Out).Length/1GB), ($len/1GB))
}

Remove-Item $partDir -Recurse -Force -ErrorAction SilentlyContinue
$final = (Get-Item $Out).Length
if ($final -ne $len) { Log ("!! 未完成 {0:N3}/{1:N2} GiB，重跑本脚本可继续" -f ($final/1GB),($len/1GB)); exit 1 }
if ($Sha256) {
  Log '校验 SHA256...'
  $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $Out).Hash.ToLower()
  if ($h -eq $Sha256.ToLower()) { Log '✅ SHA256 匹配，文件正确' }
  else { Log "❌ SHA256 不匹配 期望 $Sha256 实际 $h"; exit 2 }
}
Log ("完成 {0}  {1:N2} GiB" -f $Out, ($final/1GB))
