# 首版实际验证

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

## 尚未执行的外部验证

没有使用用户的真实 API Key，不能据此宣称 10 家厂商在线调用都已实测。适配使用官方文档核验与模拟响应验证，接入真实 Key 后仍需确认其区域、模型授权、余额和多轮行为。

未向公网发布服务，未进行真机安装；Docker 定义已提供，本机无 Docker 可执行文件，未实跑镜像。旧 RAG 原私人索引只读统计了 2 文档/2 块，没有擅自导入新库。
