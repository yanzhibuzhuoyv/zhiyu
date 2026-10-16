# 编译 llama.cpp 的 llama-quantize（用于产出 Q4_K_M 等 K-quant GGUF）
$ErrorActionPreference = 'Continue'
$root = 'C:\Users\32202\Desktop\99999\qwen-image-edge'
$src  = "$root\tools\llamacpp-full"
$bld  = "$root\tools\llamacpp-build"
$log  = "$root\build_llamacpp.log"
$cmake = "$root\.venv\Scripts\cmake.exe"
function Say($m) { "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m | Tee-Object -FilePath $log -Append }

if (-not (Test-Path "$src\CMakeLists.txt")) {
    Say "克隆完整源码..."
    if (Test-Path $src) { Remove-Item $src -Recurse -Force -ErrorAction SilentlyContinue }
    & git clone --depth 1 https://github.com/ggml-org/llama.cpp $src 2>&1 | Select-Object -Last 3 | Tee-Object -FilePath $log -Append
} else { Say "源码已存在，跳过克隆" }

New-Item -ItemType Directory -Path $bld -Force | Out-Null

Say "配置 cmake（Ninja + msys64 gcc）..."
$env:PATH = "C:\msys64\ucrt64\bin;$env:PATH"
& $cmake -S $src -B $bld -G Ninja `
    -DCMAKE_BUILD_TYPE=Release `
    -DCMAKE_C_COMPILER="C:/msys64/ucrt64/bin/gcc.exe" `
    -DCMAKE_CXX_COMPILER="C:/msys64/ucrt64/bin/g++.exe" `
    -DGGML_NATIVE=OFF -DGGML_CCACHE=OFF -DLLAMA_CURL=OFF -DBUILD_SHARED_LIBS=OFF `
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF 2>&1 |
    Tee-Object -FilePath $log -Append | Select-Object -Last 12

Say "编译 llama-quantize（-j 6）..."
& $cmake --build $bld --target llama-quantize -j 6 2>&1 |
    Tee-Object -FilePath $log -Append | Select-Object -Last 15

$exe = Get-ChildItem $bld -Recurse -Filter 'llama-quantize*.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($exe) { Say "✅ 编译成功: $($exe.FullName)" } else { Say "❌ 未找到 llama-quantize.exe" }
