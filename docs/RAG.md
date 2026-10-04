# 知语 RAG 后端

## 默认检索和存储

后端默认使用中文 BM25 关键词检索，无需 API Key、模型下载、FAISS 或外部数据库。中文使用单字和相邻双字组合，英文使用大小写不敏感的词；这是词法检索，不是语义向量检索。没有关键词匹配时返回空结果，聊天仍可使用普通 AI 回答。

`RagService(data_dir, embedding_model=None)` 将文档元数据、文字块和可选语义向量保存在 `data_dir/rag.sqlite3`。每个数据库操作显式检查当前 `owner`；服务调用者必须从已验证的登录会话取 owner，不接受客户端自报 owner。文件名只用于展示，不用于保存或读取文件路径。

相同 owner、清理后的文件名和 SHA-256 内容摘要相同的重复上传返回原文档，包含并发重复上传。相同名字、不同内容会保留为独立文档，可在知识库页面删除旧版本。删除文档会通过 SQLite 外键级联删除文字块和缓存向量。新库不会自动读取旧项目或共享旧知识库。

接口返回格式：

```python
document = {"id": "…", "name": "笔记.md", "size": 123,
            "chunks": 2, "created_at": "2026-10-04T00:00:00+00:00"}
result = {"document_id": "…", "name": "笔记.md", "text": "匹配文字块…", "score": 0.42}
```

`score` 只用于当前查询排序，不是回答真实性的置信度。聊天需要将来源视作不可信参考资料，保留原始来源引用。

## 文档支持和限制

支持 `.txt`、`.md`、`.pdf`、`.docx`。TXT/Markdown 严格解码 UTF-8、带 BOM 的 UTF-16 或 GB18030；包含替代字符、二进制控制字符、空白内容和无法识别的编码会被拒绝。PDF 使用 `pypdf`，不接受加密 PDF；扫描版 PDF 没有文字层，需要先 OCR。DOCX 使用 `python-docx`，包括正文段落和表格单元格。

上传限制为 5 MiB，提取文字最多 100 万字符，PDF 最多 500 页。PDF 单个解压流最多 4 MiB、页面内容流总量最多 16 MiB，并限制页面树深度和表单递归；使用 [pypdf 官方配置](https://pypdf.readthedocs.io/en/stable/user/security.html) 的作用域保护，过度压缩内容会明确拒绝。后端最多同时解析 2 份文件。DOCX 解压后最多 32 MiB、1024 个 ZIP 条目。文字块最多 512 字符，按中文标点和换行递归划分，最多重叠 80 字符。单份文档最多 4096 块，每位用户最多 20000 块。检索最多返回 20 块，查询最多 16000 字符。

当前实现面向个人及小规模知识库，默认查询在内存中建立当前用户的 BM25 索引。大规模部署需要独立解析工作进程、请求限流和适合规模的搜索引擎；仅限制文件字节数无法保证第三方 PDF 解析器在所有恶意压缩输入上具有恒定 CPU/内存开销。

用户数据错误抛 `ValueError`，API 对应 HTTP 400。存储错误抛 `RagStorageError`，语义推理错误抛 `RagSemanticError`，API 对应 HTTP 503。错误响应不应包含 SQL、文档内容、API Key 或内部文件路径。

## 可选的真实 BGE 语义检索

管理员先准备完整的可信本地模型目录，再安装可选依赖并设置后端的 `ZHIYU_EMBEDDING_MODEL`。请确认此环境变量与 `.env.example` 配置一致。

```powershell
cd backend
uv pip install --python .venv/Scripts/python.exe -e ".[semantic]"
$env:ZHIYU_EMBEDDING_MODEL = 'C:\models\bge-small-zh-v1.5'
```

模型只从已有本地目录加载，使用 `local_files_only=True`、`trust_remote_code=False` 和 CPU，绝不自动下载大模型。目录或依赖缺失会明确报错，不会悄悄退回假向量。未配置模型时，健康检查模式为 `bm25`；模型标识含 BGE 时为 `bge-hybrid`，其他实际 SentenceTransformer 模型为 `semantic-hybrid`。

BGE 中文模型查询带官方检索指令，BGE M3 不添加该指令。文档和查询生成真实、归一化的神经网络嵌入向量，按余弦相似度与 BM25 合并排序。当前权重是词法 0.45、语义 0.55，余弦小于 0.35 的语义部分不参与得分；这些值是初始策略，应根据实际语料评测后调整。

第一次语义查询会为当前用户的已有文字块生成向量，并保存在 SQLite；后续查询复用。向量按模型目录、文件大小、修改时间和配置摘要隔离。切换模型会建立新缓存，删除文档同时删除缓存。模型实例上的推理使用锁，避免同一实例并发执行。

本轮在独立 `.venv-semantic` 环境复用电脑已有 PyTorch 与已缓存 `BAAI/bge-small-zh-v1.5`，没有下载模型。真实推理验证了中文换说法检索、2 个向量的 SQLite 持久化、身份隔离与重复查询缓存。默认服务依然是无需大依赖的 BM25；启用 BGE 请按上述 semantic 依赖和模型目录配置。常规自动测试另用模型替身验证错误边界。相关用法依据 [SentenceTransformer 官方 API](https://www.sbert.net/docs/package_reference/sentence_transformer/model.html) 和 [BAAI BGE 中文模型说明](https://huggingface.co/BAAI/bge-small-zh-v1.5)。

## 旧项目只读迁移

旧项目 `rag-system/rag_index/metadata.json` 包含来源和文字块。本轮只统计到 2 个来源、2 个块，没有将私人旧数据灌入新库。

先进行预检查，命令只显示数量，不显示文档名或正文，不读取登录令牌，不发送网络请求：

```powershell
backend/.venv/Scripts/python.exe scripts/import_legacy_rag.py `
  --source ../rag-system/rag_index --dry-run
```

确认希望迁移后，用当前用户已经验证的后端登录令牌进行导入。这里需要登录返回的会话令牌，不是 AI 公司的 API Key。令牌可以保存在 `ZHIYU_TOKEN` 环境变量；交互终端未设置变量时，工具使用隐藏输入。

```powershell
$env:ZHIYU_TOKEN = '<当前用户的会话令牌>'
backend/.venv/Scripts/python.exe scripts/import_legacy_rag.py `
  --source ../rag-system/rag_index --api-url http://127.0.0.1:8790
Remove-Item Env:ZHIYU_TOKEN
```

工具只读取元数据 JSON，绝不跟随其中的来源路径、读取原 `.env`、修改原索引、复制原模型或连接匿名共享库。按来源合并文字块，移除可识别的重叠部分，生成带稳定来源摘要的 `.md` 名称，并通过 `/api/documents` 上传到当前令牌所属的 owner。相同旧索引再次导入保持幂等。

由于旧索引保存的是分块结果，迁移得到的是索引中的可检索文字，不保证恢复原文件的排版、附件或分块时已丢失的标点。原 FAISS 向量不迁移，由新配置决定检索方式。远程地址要求 HTTPS，HTTP 仅允许本机；工具拒绝重定向，以免将登录令牌转发到另一地址。

## 复用许可与验证

`backend/app/vendor_rag` 复用了本机旧 `rag-system` 的零依赖 BM25 和中文递归分块实现，原 MIT 许可保存在同目录的 `LICENSE`。适配去除了旧配置和文档加载器依赖，修复重叠后块大小上限，加入单字中文检索和倒排词频以避免逐词扫描每个全文块。

针对性测试：

```powershell
cd backend
.venv/Scripts/python.exe -m pytest tests/test_rag.py tests/test_rag_semantic.py tests/test_rag_import.py `
  --cov=app.rag --cov=app.rag_parser --cov=app.rag_semantic --cov=app.vendor_rag --cov-report=term-missing
```

测试覆盖持久化、中文检索、租户隔离、并发幂等、删除、路径安全、编码、损坏/空白/加密 PDF、DOCX 表格、语义缓存及迁移只读和登录要求。
