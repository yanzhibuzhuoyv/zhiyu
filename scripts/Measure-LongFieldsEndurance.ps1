param(
    [Parameter(Mandatory = $true)][string]$RuntimePath,
    [Parameter(Mandatory = $true)][string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$runtime = Get-Content -LiteralPath $RuntimePath -Raw | ConvertFrom-Json
if ($runtime.volume -cne ('zhiyu-long-endurance-' + $runtime.runId) -or
    $runtime.container -notmatch '^zhiyu-long-endurance-[0-9]{8}-[0-9]{6}-[a-f0-9]{6}$') {
    throw '运行记录不是本项目临时耐久测试。'
}
$containerJson = docker inspect $runtime.container
if ($LASTEXITCODE -ne 0) { throw '耐久容器已退出或不可读取。' }
$container = ($containerJson | ConvertFrom-Json)[0]
if ($container.Config.Labels.'com.zhiyu.test.scope' -cne 'long-fields-endurance' -or
    $container.Config.Labels.'com.zhiyu.test.run' -cne $runtime.runId -or
    $container.Name.TrimStart('/') -cne $runtime.container -or
    @($container.Mounts | Where-Object { $_.Type -eq 'volume' -and $_.Destination -eq '/data' -and $_.Name -ceq $runtime.volume }).Count -ne 1) {
    throw '容器或数据卷归属不匹配，停止只读采样。'
}
$pythonCode = @'
import sqlite3,json,pathlib,datetime,sys
sys.path.insert(0,'/work/scripts')
from smoke_long_fields import role_storage_counts
p=pathlib.Path('/data/run/chat.sqlite3')
db=sqlite3.connect(p.as_uri()+'?mode=ro',uri=True)
db.execute('BEGIN')
roles={r:{'count':n,'content_utf8_bytes':b} for r,n,b in role_storage_counts(db)}
tables=dict(db.execute('SELECT name,sum(pgsize) FROM dbstat GROUP BY name'))
r={'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'only_new_owned_endurance_volume','concurrent_read_only_sample':True,'messages':sum(v['count'] for v in roles.values()),'roles':roles,'sqlite':{k:db.execute('PRAGMA '+k).fetchone()[0] for k in ('page_size','page_count','freelist_count')},'index_bytes':sum(b for n,b in tables.items() if n.startswith(('memory_','sqlite_autoindex_memory_')))}
db.close()
r['files_bytes']={s or 'main':pathlib.Path(str(p)+s).stat().st_size if pathlib.Path(str(p)+s).exists() else 0 for s in ('','-wal','-shm')}
r['combined_app_harness_process']={k:v.strip() for k,v in (line.split(':',1) for line in pathlib.Path('/proc/1/status').read_text().splitlines()) if k in ('VmRSS','VmHWM','Threads')}
r['cgroup']={k:pathlib.Path('/sys/fs/cgroup',k).read_text().strip() for k in ('memory.current','memory.peak','memory.max')}
print(json.dumps(r))
'@
$result = docker exec $runtime.container python -c $pythonCode
if ($LASTEXITCODE -ne 0) { throw '只读采样失败。' }
$sample = $result | ConvertFrom-Json
if ($sample.scope -ne 'only_new_owned_endurance_volume') { throw '采样没有返回预期数据。' }
$outputParent = Split-Path -Parent ([System.IO.Path]::GetFullPath($OutputPath))
New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
$result | Set-Content -LiteralPath $OutputPath -Encoding utf8
Write-Output "只读采样保存：$OutputPath（当时 $($sample.messages) 条消息）"
