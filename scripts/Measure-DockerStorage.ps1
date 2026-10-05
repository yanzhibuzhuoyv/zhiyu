param([string]$OutputPath)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $containerId = docker compose -p zhiyu-docker-test -f compose.test.yaml ps --quiet backend
    if ($LASTEXITCODE -ne 0 -or -not $containerId) { throw '测试后端未运行。' }
    $actualProject = docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' $containerId
    if ($LASTEXITCODE -ne 0 -or $actualProject -ne 'zhiyu-docker-test') {
        throw '容器不是本项目独立测试后端。'
    }
    $measurementCode = @'
import json
import sqlite3
from datetime import UTC, datetime
from pathlib import Path

path = Path('/data/chat.sqlite3')
with sqlite3.connect('file:/data/chat.sqlite3?mode=ro', uri=True, timeout=15) as db:
    counts = db.execute('SELECT role, count(*), sum(length(CAST(content AS BLOB))) FROM messages GROUP BY role').fetchall()
    pages = {name: db.execute('PRAGMA ' + name).fetchone()[0]
             for name in ('page_size', 'page_count', 'freelist_count')}
    integrity = db.execute('PRAGMA quick_check').fetchone()[0]
    conversation_count = db.execute('SELECT count(*) FROM conversations').fetchone()[0]
    source_bytes = db.execute('SELECT coalesce(sum(length(CAST(sources AS BLOB))),0) FROM messages').fetchone()[0]
    indexed_tables = {row[0] for row in db.execute("SELECT name FROM sqlite_schema WHERE name LIKE 'memory_%'")}
    memory_index = {'tables': sorted(indexed_tables), 'present': bool(indexed_tables)}
    if 'memory_fragments' in indexed_tables:
        memory_index['fragments'] = db.execute('SELECT count(*) FROM memory_fragments').fetchone()[0]
    try:
        table_bytes = {name: size for name, size in db.execute('SELECT name,sum(pgsize) FROM dbstat GROUP BY name')}
        allocation = {'available': True, 'tables_bytes': table_bytes,
                      'memory_index_bytes': sum(size for name, size in table_bytes.items()
                                                if name.startswith(('memory_', 'sqlite_autoindex_memory_')))}
    except sqlite3.OperationalError:
        allocation = {'available': False, 'reason': 'DBSTAT allocation query unavailable in this SQLite build'}
files = {suffix or 'main': Path(str(path) + suffix).stat().st_size
         if Path(str(path) + suffix).exists() else 0 for suffix in ('', '-wal', '-shm')}
cgroup = {}
for name in ('memory.current', 'memory.peak', 'memory.max'):
    location = Path('/sys/fs/cgroup') / name
    if location.exists():
        value = location.read_text().strip()
        cgroup[name] = int(value) if value.isdigit() else value
memory_stat = Path('/sys/fs/cgroup/memory.stat')
if memory_stat.exists():
    cgroup['components_bytes'] = {key: int(value) for key, value in
                                  (line.split() for line in memory_stat.read_text().splitlines())
                                  if key in ('anon', 'file', 'kernel')}
process_memory = {key: value.strip() for key, value in
                  (line.split(':', 1) for line in Path('/proc/1/status').read_text().splitlines())
                  if key in ('VmRSS', 'VmHWM', 'VmSize', 'Threads')}
result = {'checked_at': datetime.now(UTC).isoformat(), 'scope': 'isolated_docker_test_volume',
          'conversation_count': conversation_count,
          'messages': {role: {'count': count, 'content_utf8_bytes': byte_count}
                       for role, count, byte_count in counts},
          'source_storage_bytes': source_bytes, 'sqlite': pages, 'files_bytes': files,
          'chat_memory_index': memory_index, 'page_allocation': allocation,
          'quick_check': integrity, 'cgroup': cgroup, 'backend_process': process_memory,
          'measurement_mutates_database': False}
print(json.dumps(result, indent=2))
'@
    $measurement = $measurementCode | docker exec -i $containerId python -
    if ($LASTEXITCODE -ne 0) { throw '测试存储测量失败。' }
    if ($OutputPath) {
        $outputDirectory = Split-Path -Parent $OutputPath
        if ($outputDirectory) { New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null }
        $measurement | Set-Content -LiteralPath $OutputPath -Encoding utf8
    }
    $measurement
} finally {
    Pop-Location
}
