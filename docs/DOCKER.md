# Docker 构建与测试

后端运行在 Linux 容器中，默认中文 BM25。Android App 是原生 APK，Docker 负责运行和验证后端。

## 2026-10-06 正文存储版部署

本机8791已升级为`sha256:418a7a857c088b43f1ee61ace24db037cec580f2b7f176318f02917c742f88ed`，health为ok/0.1.1/bm25、演示开启。本机`zhiyu-backend:docker-test`标签也已更新为这个摘要，下面普通启动命令继续使用正式正文读取版。需要固定当前摘要复现时：

```powershell
$env:ZHIYU_DOCKER_TEST_DEMO = 'true'
docker compose -p zhiyu-docker-test -f compose.test.yaml -f artifacts/compose.body-storage.override.yaml up -d --no-deps --no-build --wait backend
```

override仅是本机留证，不提交Git；其他机器先从当前源码构建。升级前保留完整测试卷及身份文件备份，见[正文格式、迁移和回退边界](BODY_STORAGE.md)。旧镜像不认识新压缩BLOB，不能直接换回旧缓存标签当作数据回退。最终全量后端两平台各481 passed/95.29%，工具各106 passed。新写千轮与20k副本迁移在先前d36d镜像分别通过，正文核心与最终镜像SHA相同，差异仅未接入生产的策略去重；没有重跑整个旧版`Test-Docker.ps1`，结果按各实际入口留证。

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

单测日志在 `artifacts/docker-unit-tests.log`，HTTP通过后生成 `artifacts/docker-validation.json` 与 `artifacts/companion-validation.json`，只记录容器、健康与测试结果，不写Key/token。2026-10-05的0.1.1逐消息FTS/权重版271测试/94.60%覆盖率，短回复真实万轮通过，见[验证报告](VALIDATION.md)。这些本地证据不提交Git。

测试成功后保留演示容器供查看：API文档 <http://127.0.0.1:8791/docs>，健康检查 <http://127.0.0.1:8791/health>。新版debug APK已内置模拟器地址`http://10.0.2.2:8791`，不需要普通用户填写服务器；选择“本地演示”并输入`demo-key`。演示回复明确标记，无云端请求。真实用户版本需构建配置自己的HTTPS服务。

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

2026-10-05恢复时再次出现相同socket错误，普通start/stop未成功；确认故障进程及绝对路径后保留重建0字节socket临时目录恢复。保留路径为`run.zhiyu-recovery-20261005-101000`、`run.zhiyu-recovery-20261005-101200`和`docker-secrets-engine.zhiyu-recovery-20261005-101200`，仍无reset/prune或数据卷删除。不能将临时恢复描述为Docker根因已永久解决。

真实厂商Key、容器内BGE、真机及公网HTTPS仍未验证。新版模拟器连接Docker实际结果见[VALIDATION.md](VALIDATION.md)。

2026-10-05 12:13再次启动报sailor-ingest.sock无法访问。仅停止已崩溃且路径位于Docker安装目录的进程，验证两个临时目录中仅有0字节socket后保留搬动，12:14重新启动引擎成功。保留`run.zhiyu-recovery-20261005-121405`及`docker-secrets-engine.zhiyu-recovery-20261005-121405`。没有工厂重置、prune或删除卷。这仍是临时恢复，后续重新检查在线状态。

逐消息FTS与权重版本完整驱动271项通过、94.60%覆盖率、30压力工具回归。千轮/万轮真实HTTP需单独执行，过程、版本摘要和只读存储测量见[长聊实测](LONG_CHAT_TEST.md)。

后来新增长字段工具16反例，Windows/LinuxDocker全部工具46 passed、ruff通过；新增`Test-LongFields.ps1`单独真实Docker exit0。当前Test-Docker.ps1已加入全scripts检查与此helper，新增步骤分别验证，未重复重建和运行原驱动。已有运行镜像时直接`./scripts/Test-LongFields.ps1`：独立network-none临时容器、只读脚本、loopback HTTP，输出每次新的artifacts/long-fields-*报告。双方16k及4条64k合成AI回复、原文中尾/分页/重启/超限检查与空间数据见[长字段实测](LONG_FIELDS_TEST.md)。

长字段耐久另用 `./scripts/Test-LongFieldsEndurance.ps1 -Turns 1000` 或 `-Turns 10000`，数据库放全新带scope/runID标签的磁盘卷，报告单独保存在host。结束后重新核对完整卷名和两项标签，仅移除本次测试卷；不挂现有8791卷。单轮长文本写入与检索使万轮运行耗时较长，普通一键测试不自动执行它。[耐久方法和执行证据](LONG_FIELDS_ENDURANCE.md)。新增13个工具反例后，Windows/Linux工具回归共59项、ruff通过；Test-Docker.ps1另加入9项PowerShell卷归属反例，使用模拟docker调用、无实际卷修改。新步骤分别验证，未据此声称整个更新后的驱动重新执行过。

修复大原文字节统计后工具回归共64项。`./scripts/Test-StatisticsTmpfs.ps1`已纳入一键测试：128MiB /tmp、另一512MiB /workdata临时fixture，224MiB原文让旧GROUP BY实际报FULL，新流式统计准确通过；没有挂现有数据卷。显式 `-KeepFailedVolume` 可让失败耐久测试保留自身专用卷供诊断，仍非零退出；成功照常删除。新完整万轮结果与旧6,696轮中断记录分别保存。

2026-10-06生产大库删除修复已构建为独立测试镜像 `zhiyu-backend:delete-fix-20261006`（固定SHA见耐久报告）；两平台新后端各275项/94.55%通过。新镜像执行 `./scripts/Test-LongFieldsEndurance.ps1 -Turns 1000 -Image zhiyu-backend:delete-fix-20261006` 完整通过，driver exit0、临时卷移除；两万条大库副本真实HTTP故障回滚与重试删除也通过。旧完整长字段万轮检查点通过、最终删除失败，报告保持failed；没有合并为新镜像完整万轮成功。原8791恢复命令本次被自动审批拒绝（仅blocked by policy），最终只读检查仍exited/health无法连接，未重试；上述验证使用独立network-none容器。诊断COPY卷核验归属与无挂载后移除，历史失败源卷保留。
