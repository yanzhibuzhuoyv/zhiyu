# 蒸馏 v3 全流程（修掉语料长度覆盖与语法问题后重跑）
$root = 'C:\Users\32202\Desktop\99999\qwen-image-edge'
$py   = "$root\.venv\Scripts\python.exe"
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONUTF8 = '1'
$log  = "$root\distill_v3_chain.log"
function Say($m) { "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m | Tee-Object -FilePath $log -Append }

Say "=== 蒸馏 v3 流程启动（语料 corpus_v3，12000 条）==="

Say "--- [1/6] 教师信号（打包 L36, GPU, token 预算分桶）---"
& $py -X utf8 -u "$root\tools\gen_teacher.py" --corpus "$root\distill\corpus_v3.jsonl" `
    --limit 12000 --token-budget 2048 --batch 32 --out "$root\distill\v3_teacher" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern '待处理|分桶|完成|ms/条|Error|Traceback' | Select-Object -Last 5

Say "--- [2/6] 学生特征（Qwen3-1.7B-Base, GPU, token 预算分桶）---"
& $py -X utf8 -u "$root\tools\gen_student_feats.py" --corpus "$root\distill\corpus_v3.jsonl" `
    --limit 12000 --token-budget 2048 --batch 32 --out "$root\distill\v3_student" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern '待处理|分桶|完成|ms/条|层数|Error|Traceback' | Select-Object -Last 5

Say "--- [3/6] 训练适配器（40 轮）---"
& $py -X utf8 -u "$root\tools\train_adapter.py" --teacher "$root\distill\v3_teacher" `
    --student "$root\distill\v3_student" --epochs 40 --out "$root\distill\v3_adapter" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern 'epoch|基线|最佳|shard|维度|Error|Traceback' | Select-Object -Last 20

Say "--- [4/6] 学生 embeds（Tier A，含 L01-L04 长提示词）---"
& $py -X utf8 -u "$root\tools\make_student_embeds.py" --tier A --adapter "$root\distill\v3_adapter\adapter_best.pt" `
    --out "$root\distill\v3_embeds" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern '提示词|完成|Error|Traceback' | Select-Object -Last 4

Say "--- [5/6] 出图 ---"
& $py -X utf8 -u "$root\tools\gen_from_embeds.py" --embeds "$root\distill\v3_embeds" `
    --layers 36 --size 512 --out "$root\student_v3_out" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern 'seq=|Error|Traceback' | Select-Object -Last 6

Say "--- [6/6] 生成对照图 ---"
& $py -X utf8 -u "$root\tools\sheet_student_vs_teacher.py" --student-dir "student_v3_out" `
    --ids "A01,A05,A09,A13,A14,A15,A16,L01,L02,L03,L04" --out "对比_蒸馏v3_vs_教师.png" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-Object -Last 3

Say "=== 蒸馏 v3 流程结束 ==="
