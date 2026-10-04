# AI 服务商适配

官网核对日期：2026-10-04。地址与参考模型来自下面列出的官方文档。**本轮没有真实 API Key，所有调用验证均使用 `httpx.MockTransport`，不代表这些账户或模型已经在线实测。**模型列表是界面建议，不是完整可用模型列表，也不代表用户拥有调用权限；以服务商控制台的开通状态为准。

## API Key 进入应用

用户选择服务商、输入对应平台 Key 与已开通模型，后端执行一次真实、可能计费的 `chat/completions` 请求验证调用权限。验证请求只有一条“只回复 OK”的消息，`max_tokens=8`，关闭可关闭的思考模式。输入与输出都可能计费，登录界面需要说明这一点。

Key 的形状不会被当成有效登录：只检查必要的空值、长度和 Header 安全字符，再以实际服务商响应为准。有效 Key 但选错地域、模型、权限或套餐，也会导致验证失败。验证成功只证明该次模型调用可用，不代表以后持续有余额或权限。对于不能关闭思考的模型，8 token 可能只产生思考内容；格式有效且确有文本思考的成功响应可完成验证，聊天仍要求最终答案。

适配层不写数据库、不输出 Key、不记录聊天正文；它仅在请求的 `Authorization` Header 中发送 Key。API Key 是服务商调用凭证，不构成独立账号注册。主应用负责设备本地密钥保存、后端会话及用户数据隔离。

## 固定服务地址与参考模型

所有地址固定在后端 catalog 中，均为官方国内入口。本版不接受客户端提供 Base URL。`doubao` 的默认模型故意留空，必须由用户填写真实 Model ID 或推理接入点 ID。

| ID / 服务商 | Base URL | 界面默认模型 | 官方文档与必要差异 |
| --- | --- | --- | --- |
| `deepseek` / DeepSeek | `https://api.deepseek.com` | `deepseek-flash` | [首次调用](https://api-docs.deepseek.com/)；`deepseek-v4-pro` 也列为参考。官网已推荐 `deepseek-flash`，不沿用旧 `deepseek-chat`。对参考模型发送 `thinking.type=disabled`。 |
| `qwen` / 百炼、通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` | [OpenAI Chat API](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)；固定北京国内旧域名，官网确认仍正常使用。官方推荐迁移业务空间专属域名，本版尚不接受动态 Workspace 域名。Key 需匹配地域与业务空间。参考模型关闭 `enable_thinking`。 |
| `kimi` / 月之暗面 | `https://api.moonshot.cn/v1` | `kimi-k2.6` | [模型参数](https://platform.kimi.com/docs/api/models-overview)、[快速开始](https://platform.kimi.com/docs/get-api-key)；K2.6 支持 `thinking.type=disabled`，适合本版纯文本历史。K3 与 K2.7 Code 强制保留完整思考消息，本版不推荐且明确拒绝这几个已知未适配 ID。国内与国际 Key 不通用。 |
| `zhipu` / 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4.5-flash` | [OpenAI 兼容](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction)、[模型概览](https://docs.bigmodel.cn/cn/guide/start/model-overview)、[思考模式](https://docs.bigmodel.cn/cn/guide/capabilities/thinking-mode)；当前官网仍列 GLM-4.5-Flash。参考模型关闭 `thinking.type`；GLM-5.3 强制思考，不作默认推荐。Coding Plan 使用独立地址，本版面向按量平台。 |
| `doubao` / 豆包、火山方舟 | `https://ark.cn-beijing.volces.com/api/v3` | 用户填写 | [地址与鉴权](https://docs.volcengine.com/docs/ark/base-url-and-authentication?lang=zh)、[兼容 OpenAI](https://docs.volcengine.com/docs/ark/compatible-with-openai-sdk?lang=zh)；API Key 可调用已开通模型 ID 或其推理接入点，必须先开通授权。本版对 `doubao-seed-*` 与 `ep-*` 发送关闭思考配置，旧接入点的实际模型能力仍需实测。Coding / Agent Plan 有独立入口，Key 不混用。 |
| `minimax` / MiniMax | `https://api.minimax.cn/v1` | `MiniMax-M2.7` | [OpenAI SDK](https://platform.minimax.cn/docs/api-reference/text-openai-api)；官网现已迁移 `minimax.cn`，不继续使用旧 `minimaxi.com` API 域名。本版没有猜测思考关闭参数。纯文本聊天可返回 `content` 内联 `<think>`，展示时移除前置思考标签，只保留最终回答。不适配工具调用或需要保留完整思考消息的工作流。 |
| `qianfan` / 百度千帆 | `https://qianfan.baidubce.com/v2` | `ernie-4.5-turbo-128k` | [兼容调用](https://cloud.baidu.com/doc/qianfan-docs/s/Fm9l6ocai)、[模型列表](https://cloud.baidu.com/doc/qianfan-docs/s/7m95lyy43)；使用 v2 API Key，不是旧 AK / SK、OAuth access_token。模型名是 API 参数值，不是模型展示名称。 |
| `hunyuan` / 腾讯原混元 | `https://api.hunyuan.cloud.tencent.com/v1` | `hunyuan-turbos-latest` | [原平台兼容调用](https://cloud.tencent.com/document/product/1729/111007)；保留原用户入口。官网已提示向 TokenHub 迁移，原平台不再新增模型能力和新购服务。 |
| `tokenhub` / 腾讯 TokenHub | `https://tokenhub.tencentmaas.com/v1` | `hy3` | [语言模型概览](https://cloud.tencent.com/document/product/1823/130079)、[Chat 协议参数](https://cloud.tencent.com/document/product/1823/135872)；国内广州入口，使用 TokenHub 自有 Key，与原混元 Key 不通用。参考混元模型发送 `thinking.type=disabled`。 |
| `siliconflow` / 硅基流动 | `https://api.siliconflow.cn/v1` | `deepseek-ai/DeepSeek-V4-Flash` | [Chat API](https://docs.siliconflow.cn/docs/api/chat-completions-post)；这是官网当前示例 ID。模型 ID 包含组织名；中国站与国际站账号、Key、域名需对应。该参考模型使用 `enable_thinking=false`。 |
| `spark` / 讯飞星火 | `https://spark-api-open.xf-yun.com/v1` | `4.0Ultra` | [普通 HTTP API](https://www.xfyun.cn/doc/spark/HTTP调用文档.html)；Header 凭证是对应模型的 HTTP **APIPassword**，不是单独 WebSocket APIKey。Ultra 支持 System Message，适合 RAG。X2 等推理模型有不同 `/agent/v1` 入口，本版不作推荐。 |

## 调用与错误行为

依赖 `httpx.AsyncClient`，不需要任何厂商 SDK。所有请求固定追加 `/chat/completions`，发送 `model`、纯文本 `messages`、`max_tokens` 和 `stream=false`；只为官网确认的服务商与模型组合添加思考开关。未来模型可由用户输入，但不是自动承诺适配；模型需要保留思考消息、工具结果或多模态输入时需要先扩展消息存储与调用协议。

本版采用非流式回答，暂不支持工具调用、图片、语音、视频、Coding Plan 和任意自定义 API 地址。超时为连接与连接池 10 秒、读写 90 秒；不自动重试付费聊天请求，避免重复计费。

- 每个请求强制禁止跟随重定向，包括注入的客户端默认允许重定向时；自建客户端关闭 `trust_env`，避免把用户 Key 隐式交给环境代理。
- 服务商响应、异常原文、请求正文与 Key 不会拼进返回错误。401、403、402、429、网络错误、超时及其他上游错误映射为固定的中文提示。
- 非 JSON、非法消息结构、空回答会显式失败；`reasoning_content` 不会被当作最终答案。MiniMax 只有 `<think>` 没有最终文本同样失败。
- `ProviderClient.aclose()` 只关闭自身创建的客户端；测试或应用注入的客户端由调用方管理。

## 演示与验证结果

`demo` 是单独的本地模式，不调用 AI，也不检验真实 API Key；只有主应用显式传入 `allow_demo=True` 时可用。catalog 默认隐藏演示入口。所有回答都含“本地演示”标识，RAG 场景仅展示已经传入的上下文节选，不把固定模板声称为模型生成。真实服务商的无效 Key 或网络错误不会退回演示。

测试覆盖固定地址、请求 Header / Body、厂商思考字段、真实验证请求、模型兼容限制、无效 Key、上游错误脱敏、超时、禁止重定向、非法 JSON、空回复、文本分块、MiniMax 内联思考、输入验证与客户端生命周期。使用模拟传输，不需要 Key 或余额。

后续接入真实 Key 时，至少逐家确认首次验证、两轮纯文本、RAG System Message、余额不足、模型权限和相应地域；不能只凭一次成功或模拟测试就宣布全部服务商在线可用。
