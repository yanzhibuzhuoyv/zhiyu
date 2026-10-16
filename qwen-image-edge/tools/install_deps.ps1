# 安装 Python 依赖（绕过系统代理 + 修正 setuptools 版本约束）
$ErrorActionPreference = 'Continue'
$root = 'C:\Users\32202\Desktop\99999\qwen-image-edge'
$py   = Join-Path $root '.venv\Scripts\python.exe'
$log  = Join-Path $root 'deps_install.log'
function Log($m) { $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Host $s; Add-Content -Path $log -Value $s }

Log '=== 安装依赖（--proxy "" 绕过 VPN）==='
Log '1) setuptools 降到 <82（torch 2.11 要求）'
& $py -m pip install --proxy "" -q "setuptools<82" -i https://mirrors.aliyun.com/pypi/simple 2>&1 | ForEach-Object { Log $_ }

Log '2) 核心库'
& $py -m pip install --proxy "" "transformers==5.19.0" "diffusers==0.41.0" "accelerate==1.15.0" safetensors pillow numpy psutil -i https://mirrors.aliyun.com/pypi/simple 2>&1 | ForEach-Object { Log $_ }

Log '3) 量化库'
& $py -m pip install --proxy "" bitsandbytes torchao -i https://mirrors.aliyun.com/pypi/simple 2>&1 | ForEach-Object { Log $_ }

Log '4) 验证'
& $py -c @"
import torch, diffusers, transformers
print('torch       ', torch.__version__)
print('cuda        ', torch.cuda.is_available())
if torch.cuda.is_available():
    p = torch.cuda.get_device_properties(0)
    print('gpu         ', p.name, round(p.total_memory/2**30,1), 'GiB')
print('diffusers   ', diffusers.__version__)
print('transformers', transformers.__version__)
for n in ['QwenImage21Pipeline','ErnieImagePipeline','ZImagePipeline']:
    print(' has', n, hasattr(diffusers, n))
try:
    import bitsandbytes as bnb; print('bitsandbytes', bnb.__version__)
except Exception as e: print('bitsandbytes FAIL:', type(e).__name__, e)
try:
    import torchao; print('torchao     ', torchao.__version__)
except Exception as e: print('torchao FAIL:', type(e).__name__, e)
"@ 2>&1 | ForEach-Object { Log $_ }
Log '=== 依赖安装结束 ==='
