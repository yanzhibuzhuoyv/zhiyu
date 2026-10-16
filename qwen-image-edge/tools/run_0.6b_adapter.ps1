# 0.6B 学生适配器：特征提取 -> 训练 -> 评估（用于更小的整包）
$root = 'C:\Users\32202\Desktop\99999\qwen-image-edge'
$py   = "$root\.venv\Scripts\python.exe"
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONUTF8 = '1'
$log  = "$root\distill_0.6b.log"
function Say($m) { "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m | Tee-Object -FilePath $log -Append }

Say "=== 0.6B 学生流程启动（复用 v3 教师信号）==="

Say "--- [1/4] 0.6B 学生特征 12000 条 ---"
& $py -X utf8 -u "$root\tools\gen_student_feats.py" `
    --student "$root\models\Qwen3-0.6B-Base" --corpus "$root\distill\corpus_v3.jsonl" `
    --limit 12000 --token-budget 2048 --batch 32 --out "$root\distill\v3_student_0.6b" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern '待处理|分桶|完成|ms/条|层数|取层|Error|Traceback' | Select-Object -Last 5

Say "--- [2/4] 训练 0.6B 适配器（40 轮）---"
& $py -X utf8 -u "$root\tools\train_adapter.py" --teacher "$root\distill\v3_teacher" `
    --student "$root\distill\v3_student_0.6b" --epochs 40 --out "$root\distill\v3_adapter_0.6b" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern 'epoch|基线|最佳|shard|维度|Error|Traceback' | Select-Object -Last 20

Say "--- [3/4] 生成 0.6B 学生的 embeds ---"
& $py -X utf8 -u "$root\tools\make_student_embeds.py" --tier A `
    --student "$root\models\Qwen3-0.6B-Base" `
    --adapter "$root\distill\v3_adapter_0.6b\adapter_best.pt" `
    --student-layers "-1,-4,-7,-10,-13,-16" `
    --out "$root\distill\v3_embeds_0.6b" 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern '提示词|完成|Error|Traceback' | Select-Object -Last 4

Say "--- [4/4] 泛化诊断 ---"
& $py -X utf8 -u "$root\tools\diag_generalization.py" `
    --student-embeds "$root\distill\v3_embeds_0.6b" --layer 36 2>&1 |
    Tee-Object -FilePath $log -Append | Select-String -Pattern '手写|全部|判定|达标' | Select-Object -Last 8

Say "=== 0.6B 流程结束 ==="
