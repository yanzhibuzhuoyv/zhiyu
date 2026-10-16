# 蒸馏全流程：自动等 GPU 空闲 -> 教师信号 -> 学生特征 -> 训适配器 -> 出图对比
$root = 'C:\Users\32202\Desktop\99999\qwen-image-edge'
$py   = "$root\.venv\Scripts\python.exe"
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONUTF8 = '1'
$log  = "$root\distill_chain.log"

function Say($m) { "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m | Tee-Object -FilePath $log -Append }

Say "=== 蒸馏流水线启动，等待 GPU 空闲（需 >6000 MiB）==="
$waited = 0
while ($true) {
    $free = & nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits 2>$null
    if ($free -and [int]$free -gt 6000) { break }
    Start-Sleep -Seconds 30
    $waited += 30
    if ($waited % 300 -eq 0) { Say "  等待中... 已等 $([int]($waited/60)) 分钟，当前空闲 ${free} MiB" }
}
Say "=== GPU 空闲 (${free} MiB)，开始 ==="

Say "--- [1/6] 教师信号 3000 条 ---"
& $py -X utf8 -u "$root\tools\gen_teacher.py" --limit 3000 --batch 16 --out "$root\distill\teacher" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern '待处理|完成|/3000|Error|Traceback' | Select-Object -Last 8

Say "--- [2/6] 学生特征 3000 条（Qwen3-1.7B-Base）---"
& $py -X utf8 -u "$root\tools\gen_student_feats.py" --limit 3000 --batch 16 --out "$root\distill\student" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern '待处理|完成|/3000|Error|Traceback' | Select-Object -Last 8

Say "--- [3/6] 训练对齐适配器 ---"
& $py -X utf8 -u "$root\tools\train_adapter.py" --epochs 60 --out "$root\distill\adapter" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern 'epoch|基线|最佳|shard|维度|Error|Traceback' | Select-Object -Last 25

Say "--- [4/6] 用学生+适配器生成 Tier A 的 embeds ---"
& $py -X utf8 -u "$root\tools\make_student_embeds.py" --tier A --out "$root\distill\embeds_student" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern '提示词|完成|Error|Traceback' | Select-Object -Last 6

Say "--- [5/6] 学生 embeds 出图 ---"
& $py -X utf8 -u "$root\tools\gen_from_embeds.py" --embeds "$root\distill\embeds_student" --layers 36 --size 512 --out "$root\student_out" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern 'L 36|VRAM|Error|Traceback' | Select-Object -Last 8

Say "--- [6/6] 生成 教师 vs 学生 对照图 ---"
& $py -X utf8 -u "$root\tools\sheet_student_vs_teacher.py" --out "对比_学生vs教师.png" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-Object -Last 4

Say "=== 蒸馏流水线结束 ==="
