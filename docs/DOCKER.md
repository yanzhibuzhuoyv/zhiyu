# Docker 构建与测试

后端运行在 Linux 容器中，默认中文 BM25。Android App 是原生 APK，Docker 负责运行和验证后端。

## 2026-10-08：0.1.3 真机登录与媒体后端

当前原手机后端镜像为 `sha256:11589af0ea1c37614a06a326d3d41d108a8fb0e6134f6f8fc19cee11fef63e40`，原 `zhiyu-docker-test_test-data` 与身份文件保持，新独立 `test-media` 卷承载图库。实际健康、真实 Key 重新验证和手机原会话恢复通过，见 [登录验证](LOGIN_VALIDATION.md)。下方镜像/服务记录是历史。

本轮首次构建在 `/media` 已存在时失败，Dockerfile 已改用 `mkdir -p /data /media`；真实 red/green 构建与 UID 10001 的目录使用验证已完成。测试后端使用 `restart: unless-stopped`，并对当前容器原位更新该策略，未打断正在使用的聊天。它在 Docker 正常运行时处理容器退出/引擎重启，不能启动已关闭或自身故障的 Docker Desktop，也不恢复拔线丢失的 ADB 映射；本轮没有为验证策略而重启用户正在使用的引擎。[Docker 官方说明](https://docs.docker.com/engine/containers/start-containers-automatically/)

## 2026-10-07 10:10：后台记忆第一版

本机8791运行 `sha256:2ab853fb3244d3ceb7f604d9a7cdfbb09cd2df343215d79f7846454712c071ad`，容器 `zhiyu-docker-test-backend-1` healthy，`/health` 为 ok / 0.1.1 / bm25。当前自动标注 mode=semantic、演示开启；聊天先保存完整双方原文和待处理任务并返回 pending，后台随后处理。demo 没有语义模型，最终 metadata 为 unavailable，没有冒充识别成功。

```powershell
docker compose -p zhiyu-docker-test -f compose.test.yaml -f artifacts/compose.memory-background.override.yaml up -d --no-build --wait backend
```

这个 override 固定本机已构建镜像摘要，未提交 Git；其他电脑先构建源码。实际核验容器全部45个应用 Python 文件 SHA 与工作区一致，UID/GID=10001、只读根目录、cap_drop ALL、no-new-privileges、端口仅绑定127.0.0.1。继续使用原卷 `zhiyu-docker-test_test-data`；停止状态下完整三文件（chat.sqlite3、rag.sqlite3、identity.secret）一致备份至 `zhiyu-background-predeploy-f02dd20417dd41aa9eb58d21a24d1e7c`，源与备份 SHA 全部一致。实际复制以UID10001完成，原权限未改。证据为本机 `artifacts/memory-background-predeploy.json` 与 `memory-background-deployment.json`，旧备份均保留。

54次实际 HTTP 请求全部符合预期状态，14,827字符含换行、引号、emoji、组合字符的合成用户原话完整保存，两次聊天22.958 / 11.619毫秒均返回 pending。双方四条消息的完整内容、作者、ID、时间和来源经过分页、层级/归档操作和同Key重新连接后逐条一致。固定preset、永久保护、冷查原文区间与恢复、stale 409、跨Key读/改/删/查404、16001字符422且无写入、注销旧token401，以及仅清理自己的会话均通过；没有云调用。日志为 `artifacts/memory-background-final-http.json` / `.log`。第一次harness把12,548字符误断言为至少14,000，发送前失败且已清理；保留 `-first.json` / `-first.log` 和原harness，修正合成长文本后通过，应用未因此修改。

demo后台完成很快，metadata读取时已全部 unavailable，因此这份HTTP烟测不证明 pending/running 归档竞争、真实云等待时间、语义准确率或拟人效果。受阻识别期间仍可继续聊天及保护活跃索引由可控provider专项验证；新版真实官方短对话验证另见[后台记忆实测](MEMORY_ATTENTION_EVAL.md)，Android19项实际模拟器联测见[Android记录](ANDROID.md)。旧千轮与两万条容量证明未重跑。

本次启动前 Docker Desktop 报 `docker-secrets-engine/engine.sock` 无法访问。检查仅零字节socket目录并停止已核验的Docker进程后，保留旧目录为 `C:/Users/32202/AppData/Local/Docker/run.zhiyu-recovery-20261007-095519` 与 `C:/Users/32202/AppData/Local/docker-secrets-engine.zhiyu-recovery-20261007-095519`，重建空运行目录后引擎29.7.2恢复。本机证据为 `artifacts/docker-socket-recovery-20261007.json`；未reset/prune或删除任何数据卷，这次恢复不证明Docker根因已永久修复，下次仍须核验易失状态。

## 2026-10-06晚间：语义标签与冷归档版（历史）

当时8791运行`sha256:1c69b81f21422a094bea093813f3597736a7e1a4ce0f5a22a362848f696b1495`，health为ok，演示与自动标注开启，mode=semantic，现已由页首镜像替代。demo没有真实语义模型，返回unavailable；普通用户仍只选公司和Key。两平台各718/95.18%，新版APK63unit与28模拟器联测通过。以下命令仅为历史复现：

```powershell
docker compose -p zhiyu-docker-test -f compose.test.yaml -f artifacts/compose.memory-tiers.override.yaml up -d --no-build --wait backend
```

override在本机artifacts留证，其他电脑先构建源码。docker-test/unit-test标签已指向当前运行/测试镜像。22:06升级前完整原应用卷三文件SHA一致备份`zhiyu-memory-predeploy-5bf379457ca149df9e6373dbdb92a2d4`，包括身份文件；21:43的`zhiyu-memory-predeploy-8d234629f3de4457bfa28c907eda69b0`及旧正文备份保留。首次helper以root但cap_drop ALL无法读取0600身份文件，失败日志保留；随后仅准备新备份目录所有权，实际复制使用运行UID10001后通过，原数据权限未改。

两万条冷归档/千轮及28项安卓联测用较早冻结0eb镜像；最终镜像只修更正链与节选选取，核心存储、冷目录与负载helper SHA同。最终两平台全量回归、小型真实HTTP另通过，不称重跑这三份证明。[完整证据与局限](VALIDATION.md)。

## 2026-10-06 17:08正文存储版部署（历史）

17:08本机8791曾运行`sha256:418a7a857c088b43f1ee61ace24db037cec580f2b7f176318f02917c742f88ed`，health为ok/0.1.1/bm25、演示开启；现已由页首镜像取代。下面body-storage override属于历史复现入口，当前使用页首memory-tiers override：

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
