# 两个打包版本（L36 / L24）的端到端验收
#   用打包后的编码器独立加载 -> 编码 -> 出图 -> 与既有结果逐像素比对
$root = 'C:\Users\32202\Desktop\99999\qwen-image-edge'
$py   = "$root\.venv\Scripts\python.exe"
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONUTF8 = '1'
$log  = "$root\verify_e2e.log"
$ids  = 'A01,A05,A09,A15,L03'
function Say($m) { "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m | Tee-Object -FilePath $log -Append }

Say "=== 打包版本端到端验收（5 条提示词）==="

foreach ($cfg in @(@{L=36; tag=36}, @{L=24; tag=24})) {
    $L = $cfg.L
    Say "--- L$L : 用打包编码器独立编码 ---"
    & $py -X utf8 -u "$root\tools\encode_with_packed.py" `
        --packed "$root\models\te_packed\L$L" --layer-tag $cfg.tag --ids $ids `
        --out "$root\packed_embeds_$L" 2>&1 |
        Tee-Object -FilePath $log -Append |
        Select-String -Pattern '加载|完成|token=|层数|Error|Traceback|AttributeError' | Select-Object -Last 10

    if (Test-Path "$root\packed_embeds_$L\index.json") {
        Say "--- L$L : 出图 ---"
        & $py -X utf8 -u "$root\tools\gen_from_embeds.py" `
            --embeds "$root\packed_embeds_$L" --layers $cfg.tag --size 512 --ids $ids `
            --out "$root\packed_L${L}_out" 2>&1 |
            Tee-Object -FilePath $log -Append |
            Select-String -Pattern 'seq=|VRAM|Error|Traceback' | Select-Object -Last 8
    } else {
        Say "  L$L 编码失败，跳过出图"
    }
}

Say ""
Say "=== 逐像素比对（打包版 vs 原模型）==="
& $py -X utf8 -u "$root\tools\compare_packed_pixels.py" $ids 2>&1 |
    Tee-Object -FilePath $log -Append | Select-Object -Last 12

Say "=== 验收结束 ==="
