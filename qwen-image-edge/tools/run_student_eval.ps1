# 等 GPU 空闲 -> 用学生 embeds 出图 -> 生成 教师 vs 学生 对照
$root = 'C:\Users\32202\Desktop\99999\qwen-image-edge'
$py   = "$root\.venv\Scripts\python.exe"
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONUTF8 = '1'
$log  = "$root\student_eval.log"
function Say($m) { "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m | Tee-Object -FilePath $log -Append }

Say "=== 学生出图流程启动，等 GPU（需连续 3 次 >5500 MiB 空闲）==="
$waited = 0; $hits = 0
while ($true) {
    $free = & nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits 2>$null
    $os = Get-CimInstance Win32_OperatingSystem
    $ramfree = [int]($os.FreePhysicalMemory / 1MB)
    if ($free -and [int]$free -gt 5500 -and $ramfree -gt 6) { $hits++ } else { $hits = 0 }
    if ($hits -ge 3) { break }
    Start-Sleep -Seconds 30; $waited += 30
    if ($waited % 600 -eq 0) { Say "  等待中... 已等 $([int]($waited/60)) 分钟，GPU空闲 ${free} MiB / RAM可用 ${ramfree} GiB" }
}
Say "=== GPU 空闲 (${free} MiB) 且 RAM 充足 (${ramfree} GiB)，出图 ==="

& $py -X utf8 -u "$root\tools\gen_from_embeds.py" --embeds "$root\distill\embeds_student" `
    --layers 36 --size 512 --out "$root\student_out" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern 'VRAM|Error|Traceback|L 36' | Select-Object -Last 8

Say "--- 生成 教师 vs 学生 对照图 ---"
& $py -X utf8 -u "$root\tools\sheet_student_vs_teacher.py" --out "对比_学生vs教师.png" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-Object -Last 5

# 教师侧同 seed 的对照图（exp1_out 里的 L36_*.png 是 seed 42，一致）
Say "=== 结束 ==="
