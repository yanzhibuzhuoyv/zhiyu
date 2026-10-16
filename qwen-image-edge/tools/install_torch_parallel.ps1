# 并发分块下载 torch CUDA 轮子（支持断点续传 + 可监控），然后安装全部依赖
# 背景：pip 单连接 74 kB/s；aliyun 分块被限流；改用 pytorch.org 多连接
# 改进：FileShare.Read 让文件大小可被监控；分块支持从已有字节续传

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

$root = 'C:\Users\32202\Desktop\99999\qwen-image-edge'
$log  = Join-Path $root 'torch_install.log'
$tmp  = Join-Path $root '_torch_dl'
$whl  = Join-Path $root 'torch-2.11.0+cu128-cp313-cp313-win_amd64.whl'
$py   = Join-Path $root '.venv\Scripts\python.exe'

function Log($m) { $line = "[$(Get-Date -Format 'HH:mm:ss')] $m"; Write-Host $line; Add-Content -Path $log -Value $line }

New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$urls = @(
  'https://download.pytorch.org/whl/cu128/torch-2.11.0%2Bcu128-cp313-cp313-win_amd64.whl',
  'https://mirrors.aliyun.com/pytorch-wheels/cu128/torch-2.11.0%2Bcu128-cp313-cp313-win_amd64.whl'
)

# ---- 1) 拿文件总长度 ----
$len = 0
foreach ($u in $urls) {
  try {
    $r = [System.Net.HttpWebRequest]::Create($u); $r.UserAgent='dsh'; $r.Timeout=45000
    $r.AddRange(0, 0)
    $resp = $r.GetResponse()
    $cr = $resp.Headers['Content-Range']
    $resp.Close()
    if ($cr -match '/(\d+)$') { $len = [int64]$Matches[1]; $url = $u; break }
  } catch { Log "取长度失败($u): $($_.Exception.Message)" }
}
if ($len -le 0) { Log '无法获取文件长度，放弃'; exit 1 }
Log ("目标 {0}  总长 {1:N1} MiB  源 {2}" -f (Split-Path $url -Leaf), ($len/1MB), ($url -split '/')[2])

# ---- 2) 并发分块下载（可持续传） ----
$workers = 8
$chunk = [int64][Math]::Ceiling($len / $workers)
Log "启动 $workers 并发 x $([int]($chunk/1MB)) MiB"
$sw = [Diagnostics.Stopwatch]::StartNew()

$jobs = 0..($workers - 1) | ForEach-Object -Parallel {
  $i = $_
  $start = $i * $using:chunk
  if ($start -ge $using:len) { return }
  $end = [Math]::Min($start + $using:chunk - 1, $using:len - 1)
  $need = $end - $start + 1
  $dest = Join-Path $using:tmp ("chunk_{0:D3}.part" -f $i)

  $have = 0
  if (Test-Path $dest) {
    $have = (Get-Item $dest).Length
    if ($have -gt $need) { Remove-Item $dest -Force; $have = 0 }   # 异常文件，重下
    if ($have -eq $need) { return }                                 # 已完成
  }

  for ($try = 1; $try -le 6; $try++) {
    try {
      $r = [System.Net.HttpWebRequest]::Create($using:url)
      $r.UserAgent = 'dsh'; $r.Timeout = 900000; $r.ReadWriteTimeout = 900000
      $r.AddRange($start + $have, $end)
      $resp = $r.GetResponse()
      $mode = if ($have -gt 0) { [IO.FileMode]::Append } else { [IO.FileMode]::Create }
      $fs = New-Object IO.FileStream($dest, $mode, [IO.FileAccess]::Write, [IO.FileShare]::Read)
      $resp.GetResponseStream().CopyTo($fs)
      $fs.Close(); $resp.Close()
      if ((Get-Item $dest).Length -eq $need) { return }
      $have = (Get-Item $dest).Length
    } catch {
      Start-Sleep -Seconds (2 * $try)
      if (Test-Path $dest) { $have = (Get-Item $dest).Length }
    }
  }
} -ThrottleLimit $workers

$sw.Stop()
$got = (Get-ChildItem $tmp -Filter 'chunk_*.part' | Measure-Object Length -Sum).Sum
Log ("分块结束: {0:N1} / {1:N1} MiB  用时 {2:N1} 分钟  均速 {3:N2} MB/s" -f ($got/1MB), ($len/1MB), $sw.Elapsed.TotalMinutes, ($got/1MB/$sw.Elapsed.TotalSeconds))

if ($got -ne $len) { Log "!! 不完整，缺 $([int](($len-$got)/1MB)) MiB —— 再跑一次本脚本可续传"; exit 1 }

# ---- 3) 合并 ----
Log '合并...'
$fs = [IO.File]::Create($whl)
Get-ChildItem $tmp -Filter 'chunk_*.part' | Sort-Object Name | ForEach-Object {
  $in = [IO.File]::OpenRead($_.FullName); $in.CopyTo($fs); $in.Close()
}
$fs.Close()
Log ("合并完成 {0:N1} MiB" -f ((Get-Item $whl).Length/1MB))

# ---- 4) 安装 ----
Log '安装 torch（本地轮子）...'
& $py -m pip install --no-deps $whl 2>&1 | ForEach-Object { Log $_ }
Log '安装 torch 依赖...'
& $py -m pip install -q filelock typing-extensions sympy networkx jinja2 fsspec setuptools -i https://mirrors.aliyun.com/pypi/simple 2>&1 | ForEach-Object { Log $_ }
Log '安装 diffusers / transformers / bitsandbytes / torchao ...'
& $py -m pip install "transformers==5.19.0" "diffusers==0.41.0" "accelerate==1.15.0" bitsandbytes torchao safetensors pillow numpy psutil -i https://mirrors.aliyun.com/pypi/simple 2>&1 | ForEach-Object { Log $_ }

# ---- 5) 验证 ----
Log '验证...'
& $py -c @"
import torch, diffusers, transformers
print('torch', torch.__version__)
print('cuda_available', torch.cuda.is_available())
if torch.cuda.is_available():
    print('gpu', torch.cuda.get_device_name(0), 'vram', round(torch.cuda.get_device_properties(0).total_memory/2**30,1), 'GiB')
print('diffusers', diffusers.__version__, '| transformers', transformers.__version__)
for n in ['QwenImage21Pipeline','ErnieImagePipeline','ZImagePipeline']:
    print(' ', n, hasattr(diffusers, n))
try:
    import bitsandbytes as bnb; print('bitsandbytes', bnb.__version__)
except Exception as e: print('bitsandbytes FAIL', e)
try:
    import torchao; print('torchao', torchao.__version__)
except Exception as e: print('torchao FAIL', e)
"@ 2>&1 | ForEach-Object { Log $_ }
Log '=== 完成 ==='
