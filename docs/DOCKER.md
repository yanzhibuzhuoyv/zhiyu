# Docker 构建与测试

后端运行在 Linux 容器中，默认中文 BM25。Android App 是原生 APK，Docker 负责运行和验证后端。

## 一键测试

Windows 上启动 Docker Desktop 的 Linux 引擎，在项目根目录运行：

```powershell
.\scripts\Test-Docker.ps1
```

需要 Docker Compose、Python 3.11+ 和 uv。脚本构建两个镜像：`runtime` 只包含运行依赖与应用，`test` 另含锁定测试依赖和测试代码。测试容器以 UID 10001、只读根目录、无网络运行；旧索引导入脚本只读挂载，私人数据与虚拟环境不进入镜像。

HTTP 联调使用独立的 `zhiyu-docker-test` 项目、`127.0.0.1:8791` 端口与 `zhiyu-docker-test_test-data` 数据卷。所有测试命令显式指定 `-p zhiyu-docker-test`，避免 `COMPOSE_PROJECT_NAME` 环境变量改变目标项目。先验证默认演示关闭，再显式开启本地演示。只使用随机演示 Key，测试成功后删除自己创建的文档与会话，不读取用户云端 Key。

检查内容包括：

- Linux 自动测试与覆盖率至少 80%，ruff 静态检查。
- 默认鉴权、11 个真实服务商入口、演示关闭。
- TXT、MD、PDF、DOCX 导入，中文检索、来源、两轮历史与 RAG 开关。
- 不同 Key 的文档与会话隔离，文件超过 5 MiB 与无 Content-Length 请求超过 6 MiB 的拒绝。
- 重启和 `--force-recreate` 重建后，旧 token 被拒绝，同一 Key 恢复原消息和文档，不同 Key 仍隔离。
- UID 10001、只读根目录、删除 capabilities、只发布回环地址、数据卷没有测试 Key 原文。
- 文档/会话删除与退出连接。

单测日志在 `artifacts/docker-unit-tests.log`，HTTP 通过后生成 `artifacts/docker-validation.json`，其中只记录容器、镜像、健康检查与测试结果，未写入 Key/token。这些本地证据不提交 Git。

测试成功后保留演示容器供查看：API 文档 <http://127.0.0.1:8791/docs>，健康检查 <http://127.0.0.1:8791/health>。Android 模拟器的 debug App 可以填 `http://10.0.2.2:8791`，服务商选“本地演示”，Key 填 `demo-key`。演示回复会明确标注，不调用云端模型。

停止容器并保留数据卷：

```powershell
docker compose -p zhiyu-docker-test -f compose.test.yaml down
```

再次启动演示环境：

```powershell
$env:ZHIYU_DOCKER_TEST_DEMO = 'true'
docker compose -p zhiyu-docker-test -f compose.test.yaml up -d --wait backend
```

不要把测试卷当作正式知识库；正式环境使用独立的 `compose.yaml` 与 `zhiyu-data` 卷。

## 正常运行

```powershell
docker compose config --quiet
docker compose up --build -d --wait
```

默认端口 `127.0.0.1:8790`，演示关闭。若本机 Python 服务占用 8790，应先停止该服务或改 Compose 的主机端口。`/data` 中的 SQLite 文件和 `identity.secret` 在命名卷中保留，备份与恢复须包含整个数据目录；丢失身份盐会导致同一 Key 无法关联原数据。

测试配置固定了本次 Python 基础镜像摘要；正常运行的默认标签为 `python:3.13-slim`，可以通过 Dockerfile 的 `PYTHON_IMAGE` 构建参数选择明确版本/摘要。首次构建需下载基础镜像和锁定依赖。

## 此机 Docker Desktop 故障记录

2026-10-04 测试期间曾出现主机内存不足，之后 Docker Desktop 4.88.1 在启动时先后报告 `sailor-ingest.sock` 和 Secrets Engine `engine.sock` 无法访问。日志与用户截图均指向 Windows 临时 AF_UNIX socket；未证实内存不足是否为其直接原因。[Docker 的反馈仓库有同类报告](https://github.com/docker/desktop-feedback/issues/554)，该报告提供的是社区临时目录恢复方法。

本次先确认 Docker 后端退出，再保留并重建仅包含临时 socket 的运行目录，Linux 引擎恢复。没有恢复出厂设置、Docker prune 或删除用户镜像和数据卷；原临时目录保留为 `run.zhiyu-recovery-*` 和 `docker-secrets-engine.zhiyu-recovery-*`。此操作仅针对已经确认的本机故障，没有加入日常测试脚本。

首次 Docker Hub 拉取因网络超时失败，随后复用了此机已有的 `mirror.gcr.io/library/python:3.13-slim` 缓存，并使用摘要 `sha256:7ce4b6dfe35e55397b7cda544f8a13f191b7ae28dc5aad71fe664dbc9bc2623f` 构建。依赖仍按 lock 中的 SHA256 校验，没有关闭 TLS 校验。

真实厂商 Key 在线调用、容器内真实 BGE 模型推理、安卓连接该 Docker 端口尚未包含在本轮 Docker 测试；首版本机 BGE/安卓验证详见 [VALIDATION.md](VALIDATION.md)。
