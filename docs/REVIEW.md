# 首版审查记录

日期：2026-10-04。

RAG 子 Agent 对父 Agent 后端做独立只读审查，发现 HIGH：仅检查 `Content-Length`，分块传输能够绕过大小限制；同时未登录 multipart 在依赖鉴权前解析。主 Agent 用两个先失败的回归测试复现，再加入原始 ASGI RequestGuard：实际字节最多 6 MiB、保护接口在读取/解析请求前鉴权。两项测试及真实 chunked HTTP 请求现均返回预期结果。上传文件本身限制 5 MiB。

主 Agent 继续检查文档解析、密钥生命周期、SQLite 查询和原生客户端。进一步限制 PDF 解压流/页面树/表单递归和总内容流大小，限制同时解析数量；压缩输入回归测试通过。会话并发锁使用弱引用回收，避免删除会话后长期保留锁。模型调用失败不会写入半个聊天轮次。

资源访问均根据服务器内的 Key 身份限定；API Key 不进入数据库、日志或错误回显，Pydantic 验证错误只返回字段名。服务商地址固定且不跟随重定向；设备端 Keystore AES-GCM 密文保存，随机 IV 和密文损坏明确失败已在真实模拟器验证。release 限制 HTTPS 且禁止备份。

本轮已发现的 HIGH 问题已修复并回归。最终复核未发现新增 CRITICAL/HIGH。审查与测试属于首版代码验证，没有替代真实厂商账户联调、公开部署或大规模负载测试；PDF 解析仍面向个人及小规模知识库，公共大规模服务需独立解析工作进程和部署层资源配额。

## Docker 测试配置复审

2026-10-04，独立 Docker 审查发现 HIGH：只依赖 Compose 顶层 `name`，`COMPOSE_PROJECT_NAME` 环境变量可以改变项目名，进而令测试脚本重建其他项目的同名 backend。已让 PowerShell、Python 与测试启停文档中的所有测试命令显式指定 `-p zhiyu-docker-test`；设置冲突环境变量重跑整套脚本实际通过，未在冲突项目下创建容器。独立复核确认修复，无未处理 CRITICAL/HIGH。[Compose 官方优先级](https://docs.docker.com/compose/how-tos/project-name/)说明 `-p` 高于环境变量。

HTTP 报告中的固定单测计数/覆盖率字段已移除，单测结果由实际日志留证；报告只记录本次 HTTP 测试。独立审查确认锁定测试依赖没有 editable/VCS/本地路径、测试和运行镜像分离、文档明确没有实测真实云端 Key、容器内 BGE 或安卓连接 Docker 端口。
