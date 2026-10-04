# 知语 · 原生 Android AI 聊天

Kotlin + Jetpack Compose 原生应用，FastAPI + SQLite 后端。用自己的服务商 API Key 进入，可保存会话、上传知识文档、检索参考来源。首版默认中文 BM25，支持可选本地 BGE 语义检索。

## 本机启动

已安装 Python 3.11+ 与 [uv](https://docs.astral.sh/uv/) 后，在项目根目录执行：

```powershell
.\scripts\Start-Backend.ps1
```

首次启动按 `backend/uv.lock` 安装依赖。API 文档：<http://127.0.0.1:8790/docs>；健康检查：<http://127.0.0.1:8790/health>。

没有 Key 时可以显式开启本地演示：

```powershell
.\scripts\Start-Backend.ps1 -Demo
```

安卓端选择“本地演示”，Key 填 `demo-key`。演示回复在界面中标明，不调用云端模型。正常连接会发起一次极短请求验证 Key，可能产生少量费用，后续聊天按所选厂商计费。

## 安卓联调

- 模拟器填 `http://10.0.2.2:8790`。
- 真机与电脑同一 Wi-Fi，后端用 `-ListenAddress 0.0.0.0` 启动，App 填电脑局域网 IP 与端口；防火墙需允许此端口。
- debug 构建允许本地 HTTP；release 构建使用 HTTPS。公共部署应在反向代理上配置证书。
- 可安装的首版位于 `artifacts/ZhiYu-0.1.0-debug.apk`。原生源码、构建步骤与界面说明见 [ANDROID.md](docs/ANDROID.md)。

## 知识库

知识库页导入 txt / md / pdf / docx，单个最大 5 MiB。扫描 PDF 暂需先 OCR。开启会话的知识库开关后，相关内容参与回答并展示来源。不同服务商和 Key 的知识库、会话分别隔离。

复用了 `../rag-system` 的中文检索逻辑并保留 MIT 声明；原工程、索引和 `.env` 保持原状。[旧索引导入与语义模式说明](docs/RAG.md)。导入工具先 `--dry-run`，再使用当前连接的令牌导入指定身份。

## 服务商与模型

[服务商适配说明](docs/PROVIDERS.md)列出已核验的官方 API 地址和差异。模型默认值只是可编辑起点，实际调用取决于 Key 的授权、区域、余额和厂商支持。豆包可填写已授权模型 ID 或 `ep-` 接入点 ID。

Key 在设备端由 Android Keystore 加密保存，后端只短期保存在进程内存。服务器重启后需重新鉴权，设备可用保存的 Key 自动重新连接。身份盐文件 `backend/data/identity.secret` 与 SQLite 数据库须一起备份，丢失盐后同一 Key 无法关联原数据。不要将数据目录、Key 或 `.env` 提交到 Git。

## 测试与部署

```powershell
cd backend
uv sync --frozen --extra dev
uv run --no-sync pytest --cov=app --cov-report=term-missing
uv run --no-sync ruff check app tests
```

Docker 部署：`docker compose up --build -d`，默认绑定电脑回环端口。容器版本默认关闭演示。Docker 定义已提供，实际验证状态见 [VALIDATION.md](docs/VALIDATION.md)。

[首版架构与范围](docs/ARCHITECTURE.md) · [实际验证结果](docs/VALIDATION.md)
