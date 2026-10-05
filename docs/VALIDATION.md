# 实际验证记录

## 最新0.1.1：AI恋人单页、长期记忆与体积优化

2026-10-05（北京时间）。以本节为当前版本证据，后文保留首版历史。

| 验证 | 实际结果 |
| --- | --- |
| 后端Windows与Linux容器 | 最新逐消息FTS/加权原文版 **271 passed，覆盖率94.60%**；memory_index/conversation_memory/source_codec/companion/schemas100%，ranking98%，ruff通过 |
| Docker实际构建与运行 | 新0.1.1镜像构建、UID10001/只读root/隔离卷、健康检查、TXT/MD/PDF/DOCX/413/身份隔离、restart和force-recreate后原Key恢复均通过 |
| 长聊真实HTTP | 60轮日常后检索到最初纪念日原文；关闭文档RAG仍有memory_refs；验证的是检索与传递，无真实云AI回复正确率结论 |
| 真实10,000轮 | **20,000消息、16/16原文探针、20/200页全部哈希核对通过**，10,247次HTTP/0意外失败，同Key恢复/隔离/最新修订/长文中尾/多原文同次召回均通过。全量p50=303.711ms/p95=358.640ms，总约50分钟；演示云调用0 |
| 万轮空间与内存 | 主库42,430,464B（40.46MiB），聊天索引18,010,112B（17.18MiB），RSS97,956kB；只清理自身3会话后恢复基线6会话8消息/8片段，空闲页可复用。见LONG_CHAT_TEST.md，不能推成真实AI长回复的同等体积 |
| 双方长字段Docker HTTP | **36条user16k、32条AI16k＋4条AI64k**；6/6中尾原文探针、134条分页/重启全hash、16001→422无写与2162片段全覆盖通过。原文3.15MiB/索引4.68MiB/临时主库8.87MiB；合成回复非真实云生成，见LONG_FIELDS_TEST.md |
| 测试工具回归 | 新增16长字段反例，连同30压力工具回归，Windows与LinuxDocker均46 passed；所有项目scripts按后端ruff配置通过。新增Docker长字段helper单独真实exit0；原完整驱动记录仍单独保留 |
| 权重试验 | 六组权重、16合成场景/24期望命中；默认和另一组均全通过，不称最优。通用共享时间词在音乐/心情、海边/流星、食物/感冒中保持多条原文、不融合；关联未确认 |
| 历史分页真实HTTP | 124消息按100+24读取，原顺序、无重复缺失、跨Key404；客户端100条窗口单元回归通过 |
| 恋人设定真实HTTP | 首创preset固定，改性格/名字/模式/关闭409；custom可调整、自调→preset拒绝；同Key重新验证恢复档案 |
| 无损来源 | API与历史引用一致、删除原文档后当时引用仍可读；旧TEXT/新BLOB/重启与损坏边界回归通过 |
| 合成库体积 | 加入聊天FTS之前来源压缩阶段：160轮每轮5段重复资料2,195,456B→147,456B，原消息相同。只说明该重复引用场景，不代表新版一般聊天整体比例 |
| Android单元 | **39 passed**：输入边界、身份/人格固定、自调最新档案重开恢复、旧连接升级、单聊天与100条分页等 |
| Android构建 | debug、debugAndroidTest、release R8实际编译通过；release未配置公网地址、未签名发布 |
| 真实API36模拟器→Docker8791 | **8项Keystore通过＋5项UI通过**：首次取消不绑定/成功确认、单页引用与资料弹窗、退出重验保留公司/model、preset只读、自调修改/取消及退出Activity重建后保持新内容 |
| 独立审查 | 两个Android HIGH已复现修复；后端审查无CRITICAL/HIGH，文档边界小修3回归通过；见REVIEW.md |

首轮instrumentation共13项，8个存储和4个UI通过，唯一失败为测试将密码框圆点与原Key比较；语义InputText实际为key-draft。修为主动显示测试Key后断言，重编测试APK，受影响UI套件5项全部绿色；没有忽略失败或把adb退出码0当成JUnit通过。

证据：`artifacts/docker-unit-tests.log`、`docker-validation.json`、`companion-validation.json`、`companion-instrumentation.log`（首轮真实失败保留）、`companion-ui-final.log`（5项最终绿色）。`scripts/Test-Docker.ps1`已纳入新长聊烟测；`smoke_companion.py`只使用UUID演示身份，明确不继承环境代理，清理自身测试文档和会话，无Key/token写进报告。

交付APK：`artifacts/ZhiYu-0.1.1-debug.apk`，18,663,244B，SHA256 `992E9F1EF4F9F918250DDBAC80AA55EB636225EA2A10C285FADB818506D7217F`。默认是模拟器本地Docker地址，普通页面无需填后端；真机或发布需构建配置有效服务。

恢复10:05时Docker再次在启动前报临时socket error1920，正常start/stop失败；保留并重建仅0字节socket目录后10:11引擎及新版8791恢复，再通过长聊真实HTTP。未reset/prune/删除其他数据卷，临时恢复不代表Docker根因已永久解决。当前在线状态下次仍须重验。

本轮没有真实云Key、真机、公网HTTPS、正式签名或容器BGE验证。绑定只适用于本次安装，清数据/卸载/换设备不保留；Key变化不自动迁移历史。聊天原文与FTS持续保存必然增长，分页只降低客户端负担，检索聚合成本随命中数增加；固定字符预算不等于所有模型精确token限制。[存储维护与限制](STORAGE.md)。

日期：2026-10-04。项目位于 `C:/Users/32202/Desktop/99999/zhiyu`。

| 验证 | 实际结果 |
| --- | --- |
| Python 自动测试 | **121 passed**；app 语句覆盖率 **92%**，1080 条语句覆盖；API、鉴权、隔离、持久化、服务商 MockTransport、中文检索、解析与旧索引导入 |
| 静态检查 | `ruff check app tests` 通过 |
| 真实 HTTP | 健康检查、显式演示登录、上传、检索、带来源聊天、历史保存通过；无 Content-Length 的超限请求实际返回 413 |
| 真实 BGE | 复用本机缓存 BAAI/bge-small-zh-v1.5，`mode=bge-hybrid`；中文换说法检索、2 个 SQLite 向量、身份隔离、缓存重用均通过；未下载模型 |
| Android 单元测试 | **16 passed**：6 个输入规则、10 个凭据/会话协调测试 |
| Android 模拟器 instrumentation | **3 passed**：2 个 Keystore 加密测试、1 个真实后端 Compose 登录/聊天/引用/知识库列表/退出流程 |
| 手动模拟器检查 | Android 系统选择器从 Downloads 导入 demo-guide.md 成功；界面显示来源文档，确认删除后显示 0 份；后端重启后出现 401→自动验证→原会话恢复 |
| APK | debug 构建、实际安装与运行通过；release R8 构建通过，产物未签名 |
| 审查 | 独立审查发现的 HIGH 上传问题已修复；最终复核见 REVIEW.md |
| Docker Linux 自动测试 | **121 passed**，app 覆盖率 **92.41%**；无网络、UID10001、只读测试容器，ruff 通过 |
| Docker 真实 HTTP | 默认演示关闭与鉴权、TXT/MD/PDF/DOCX、中文检索、两轮聊天来源、RAG 开关、身份隔离、超限文件/chunked 请求、删除/退出全部通过 |
| Docker 持久化与运行 | `restart` 与 `--force-recreate` 后旧 token 401、同 Key 恢复全部消息/文档/检索、其他 Key 仍隔离；健康检查 healthy、UID10001、只读根目录、数据卷无测试 Key 原文 |

debug 可安装文件：`artifacts/ZhiYu-0.1.0-debug.apk`，约 17.8 MiB；release 未签名：`android/app/build/outputs/apk/release/app-release-unsigned.apk`，约 1.7 MiB。release 需要用户的发布签名和 HTTPS 后端后才能正式分发。

## 可复现命令

```powershell
cd backend
uv sync --frozen --extra dev
uv run --no-sync pytest --cov=app --cov-report=term-missing
uv run --no-sync ruff check app tests
# 另一个终端在项目根目录运行 .\scripts\Start-Backend.ps1 -Demo
.venv/Scripts/python.exe ../scripts/smoke_backend.py
```

```powershell
cd android
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-23'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
# emulator-5554 就绪，演示后端已启动，smoke_backend.py 已导入演示指南后：
.\gradlew.bat :app:connectedDebugAndroidTest
```

真实语义烟测工具为 `scripts/smoke_semantic.py`，需要 semantic 依赖和本地模型目录。此机单独 `.venv-semantic` 复用了已有系统 PyTorch；普通安装应使用 `uv sync --extra semantic`，不要把此机的测试环境当作可移植部署环境。

测试存在 1 条 Starlette 对旧 httpx TestClient 的弃用提醒；测试均成功，不影响生产后端的 httpx 服务商调用。Android 构建有旧 Compose 测试入口/图标的弃用提醒，无编译错误。

## Docker 本轮实际验证

2026-10-04 23:13（北京时间），`scripts/Test-Docker.ps1` 整体退出码 **0**，包括构建、121 自动测试、ruff、真实 HTTP 与两种恢复验证。复跑时设置冲突环境变量 `COMPOSE_PROJECT_NAME=zhiyu-isolation-check`，测试仍只操作 `zhiyu-docker-test`，冲突项目下没有创建容器。Docker Desktop 4.88.1，Linux Engine 29.7.2，Python 3.13.15。运行镜像只含生产依赖，实际 `pip check` 无依赖冲突，未包含 pytest 或测试源代码。Docker 构建内容改为白名单，额外 `.venv-semantic` 与私人数据不会进入上下文。两份 SQLite 的 `PRAGMA integrity_check` 均为 ok。

当前测试服务为 `http://127.0.0.1:8791`，独立项目与数据卷；运行镜像本次 ID 为 `sha256:1f6a0beb4b26c37dea996178d75f86ea40676aade68d60ddfe7fc05b0ea56343`，本地大小约60.1MiB。实际证据位于 `artifacts/docker-unit-tests.log` 与 `artifacts/docker-validation.json`。Docker Desktop 临时 socket 故障的处理和重跑步骤见 [DOCKER.md](DOCKER.md)。Android APK 没有因本轮后端容器测试而重新构建。

## 尚未执行的外部验证

没有使用用户的真实 API Key，不能据此宣称 10 家厂商在线调用都已实测。适配使用官方文档核验与模拟响应验证，接入真实 Key 后仍需确认其区域、模型授权、余额和多轮行为。

未向公网发布服务，未进行真机安装。本轮 Docker 使用默认 BM25 与显式演示模型；容器内真实 BGE 推理和安卓连接 Docker 端口未实测。旧 RAG 原私人索引只读统计了 2 文档/2 块，没有擅自导入新库。
