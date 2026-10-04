$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Force -Path "$projectRoot/artifacts" | Out-Null
    docker info --format '{{.ServerVersion}} {{.OSType}}'
    if ($LASTEXITCODE -ne 0) { throw '请先启动 Docker Desktop 的 Linux 引擎。' }
    docker compose -p zhiyu-docker-test -f compose.test.yaml --profile tests config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 配置验证失败。' }
    docker compose -p zhiyu-docker-test -f compose.test.yaml --profile tests build
    if ($LASTEXITCODE -ne 0) { throw 'Docker 镜像构建失败。' }
    docker compose -p zhiyu-docker-test -f compose.test.yaml --profile tests run --rm --no-deps tests | Tee-Object -FilePath artifacts/docker-unit-tests.log
    if ($LASTEXITCODE -ne 0) { throw 'Linux 容器自动测试失败。' }
    docker compose -p zhiyu-docker-test -f compose.test.yaml --profile tests run --rm --no-deps tests ruff check --no-cache app tests /workspace/scripts/smoke_docker.py
    if ($LASTEXITCODE -ne 0) { throw '静态检查失败。' }
    uv sync --directory backend --frozen --extra dev
    if ($LASTEXITCODE -ne 0) { throw 'HTTP 联调工具依赖安装失败。' }
    & "$projectRoot/backend/.venv/Scripts/python.exe" scripts/smoke_docker.py
    if ($LASTEXITCODE -ne 0) { throw 'Docker HTTP 与持久化联调失败。' }
} finally {
    Pop-Location
}
