# 知语 · 原生 Android AI 恋人

Kotlin + Jetpack Compose 原生应用，FastAPI + SQLite 后端。选择 AI 公司、输入自己的 API Key 后进入同一段持续聊天；成功验证后本次安装固定公司与模型。选择页显示“恋人仅此唯一，此择不可挽回”。聊天记忆直接召回已保存的较早原话，资料导入使用弹窗。

可选“我们调好的恋人”：温柔体贴、俏皮亲昵、成熟稳重，支持女友、男友或不限定，以及名字和称呼；成功绑定后整份预设不可修改。“自行调教”可更新自己的恋人设定，继续原聊天；不启用设定时不增加性格预设。后端把恋人设定作为系统消息使用，把旧聊天和资料当作低信任参考。默认中文 BM25，支持可选本地 BGE 文档检索。

## 本机启动

已安装 Python 3.11+（SQLite >=3.43并支持FTS5）与 [uv](https://docs.astral.sh/uv/) 后，在项目根目录执行：

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

- 普通进入页只要求公司与 API Key；后端地址由构建配置提供。调试默认是模拟器访问 Docker 测试服务的 `http://10.0.2.2:8791`。
- 本地直接启动8790、真机局域网地址可由开发人员在 debug“开发设置”调整；真机还需后端监听局域网接口。
- release 通过 `-PzhiyuBackendUrl=https://你的域名` 配置 HTTPS 后端，配置非 HTTPS 会拒绝构建；未配置的包提示服务尚未配置。部署需证书及正式签名。
- 新版记忆管理调试 APK 位于 `artifacts/ZhiYu-0.1.1-memory-debug.apk`；旧包保留，界面和构建说明见 [ANDROID.md](docs/ANDROID.md)。

## 知识库

聊天“更多设置→资料”弹窗导入 txt / md / pdf / docx，单个最大 5 MiB。扫描 PDF 暂需先 OCR。相关内容参与回答并展示来源；聊天长期记忆独立于文档 RAG。不同服务商和 Key 的资料、会话分别隔离。

复用了 `../rag-system` 的中文检索逻辑并保留 MIT 声明；原工程、索引和 `.env` 保持原状。[旧索引导入与语义模式说明](docs/RAG.md)。导入工具先 `--dry-run`，再使用当前连接的令牌导入指定身份。

## 服务商与模型

[服务商适配说明](docs/PROVIDERS.md)列出已核验的官方 API 地址和差异。首次选择时可展开模型设置，成功验证后固定；实际调用取决于 Key 的授权、区域、余额和厂商支持。豆包需填写已授权模型 ID 或 `ep-` 接入点 ID。

Key、绑定与自调设定由 Android Keystore 加密保存。退出仅清除 Key/token，不解除绑定；旧安装升级沿用已有连接及原恋人设定。绑定依赖本次安装，清数据、卸载或换设备不会保留本机绑定。API Key 是后端数据身份，更换不同 Key 不自动迁移旧聊天。后端只短期将 Key 保存在进程内存；重启后设备自动重新鉴权。`identity.secret` 与 SQLite 须一起备份。

双方每条原话均独立入库，正文只存一份并可无损压缩；按作者、相关性与时间权重并列召回，保留最新用户修订，不做记忆融合。三层标注已持久保存并参与排序；Docker默认使用所选AI额外识别一次本人资料、偏好、愿望和承诺，严格核对原话出处，失败仍保留本轮聊天。安卓“记忆”弹窗可调整层级、归档和恢复，冷归档保留完整原文并支持回查，长期/永久及未解决承诺受到保护。没有自动删除或生产批量冷却任务。附近记录的联系须由原文支持，不能仅凭时间接近认定。

历史每批最多100条；原文与索引仍会增长。语义标注不等于语义向量检索，当前聊天候选仍为FTS/Bloom，未实现Engram。[三层与语义识别](docs/MEMORY_TIERS.md) · [冷归档容量实测](docs/MEMORY_COLD_PROOF.md) · [正文存储](docs/BODY_STORAGE.md) · [记忆与恋人设定](docs/CONVERSATION_MEMORY.md) · [存储与维护](docs/STORAGE.md)。

## 测试与部署

```powershell
cd backend
uv sync --frozen --extra dev
uv run --no-sync pytest --cov=app --cov-report=term-missing
uv run --no-sync ruff check app tests
```

Docker 部署：`docker compose up --build -d --wait`，默认绑定电脑回环端口，关闭演示。独立容器测试：`.\scripts\Test-Docker.ps1`；构建、重启/重建恢复与测试环境用法见 [DOCKER.md](docs/DOCKER.md)。

已有镜像时可单独运行`.\scripts\Test-LongFields.ps1`验证双方16k和合成64k回复的保存/索引/分页/重启，使用无网络临时容器。[长字段实测](docs/LONG_FIELDS_TEST.md)。

双方长字段耐久使用`.\scripts\Test-LongFieldsEndurance.ps1 -Turns 1000`或`-Turns 10000`，普通用户消息2k–4k字符、合成AI回复4k–8k字符，穿插16k/64k边界。使用独立磁盘卷和固定镜像，不调用云端AI。[负载、执行状态与证据](docs/LONG_FIELDS_ENDURANCE.md)。

[首版架构与范围](docs/ARCHITECTURE.md) · [实际验证结果](docs/VALIDATION.md)

本轮新增语义标签、三层加权与可逆冷归档；Windows/Linux后端各718项通过、覆盖率95.18%，Android 63项单元测试、28项API36模拟器联测通过。另从零千轮长字段完整流程通过，两万条长消息副本在全部原文一致的条件下，压缩及冷归档后整理副本主库540.20→224.29MiB；工作文件不会因释放页立即缩小。这是合成库的全冷场景，保留重要活跃记忆的真实用户库不保证同一比例。[实际验证、镜像差异与限制](docs/VALIDATION.md)。本机8791测试服务已启用新版，普通用户只选公司和Key。

此前万轮保存/检索检查点通过但最终删除失败的原报告仍保留failed；独立删除修复和后续完整千轮报告分别留证，不合并为新镜像从零万轮通过。[历史耐久记录](docs/LONG_FIELDS_ENDURANCE.md)。不做记忆融合；合成测试不代表真实云模型回复正确率。
