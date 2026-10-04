"""Official mainland API routes checked on 2026-10-04; models are suggestions, not entitlements."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Provider:
    id: str
    name: str
    base_url: str
    default_model: str
    models: list[str]
    model_hint: str
    doc_url: str


PROVIDERS: dict[str, Provider] = {
    "deepseek": Provider(
        "deepseek",
        "DeepSeek",
        "https://api.deepseek.com",
        "deepseek-flash",
        ["deepseek-flash", "deepseek-v4-pro"],
        "使用 DeepSeek 开放平台 API Key；默认关闭思考。可输入已授权的纯文本模型 ID。",
        "https://api-docs.deepseek.com/",
    ),
    "qwen": Provider(
        "qwen",
        "阿里云百炼 / 通义千问",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "qwen-plus",
        ["qwen-plus", "qwen-flash"],
        "使用中国站北京地域的百炼 API Key；模型需先开通。旧官方域名仍可用，其他地域 Key 不通用。",
        "https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions",
    ),
    "kimi": Provider(
        "kimi",
        "Kimi / 月之暗面",
        "https://api.moonshot.cn/v1",
        "kimi-k2.6",
        ["kimi-k2.6"],
        "使用国内 Kimi 开放平台 API Key；K2.6 关闭思考支持普通多轮。K3 / K2.7 的保留思考暂未适配。",
        "https://platform.kimi.com/docs/api/models-overview",
    ),
    "zhipu": Provider(
        "zhipu",
        "智谱 GLM",
        "https://open.bigmodel.cn/api/paas/v4",
        "glm-4.5-flash",
        ["glm-4.5-flash", "glm-5.2"],
        "使用智谱开放平台按量 API Key；默认关闭思考。GLM-5.3 强制思考，Coding Plan Key 入口不同。",
        "https://docs.bigmodel.cn/cn/guide/develop/openai/introduction",
    ),
    "doubao": Provider(
        "doubao",
        "豆包 / 火山方舟",
        "https://ark.cn-beijing.volces.com/api/v3",
        "",
        [],
        "必须填写已开通的 Model ID 或控制台 ep- 接入点 ID，使用对应方舟 API Key；Coding Plan Key 不通用。",
        "https://docs.volcengine.com/docs/ark/base-url-and-authentication?lang=zh",
    ),
    "minimax": Provider(
        "minimax",
        "MiniMax",
        "https://api.minimax.cn/v1",
        "MiniMax-M2.7",
        ["MiniMax-M2.7"],
        "使用国内 MiniMax 开放平台 Key；模型会思考，输出预算不足时请重试。本版只适配纯文本聊天。",
        "https://platform.minimax.cn/docs/api-reference/text-openai-api",
    ),
    "qianfan": Provider(
        "qianfan",
        "百度千帆 / 文心",
        "https://qianfan.baidubce.com/v2",
        "ernie-4.5-turbo-128k",
        ["ernie-4.5-turbo-128k", "ernie-4.5-turbo-32k"],
        "使用千帆 v2 的 API Key，先开通模型；不是旧版 AK / SK 或 access_token。",
        "https://cloud.baidu.com/doc/qianfan-docs/s/Fm9l6ocai",
    ),
    "hunyuan": Provider(
        "hunyuan",
        "腾讯混元（原平台）",
        "https://api.hunyuan.cloud.tencent.com/v1",
        "hunyuan-turbos-latest",
        ["hunyuan-turbos-latest"],
        "使用原混元平台 API Key；官网已提示迁移 TokenHub，新开通服务请选择腾讯 TokenHub。",
        "https://cloud.tencent.com/document/product/1729/111007",
    ),
    "tokenhub": Provider(
        "tokenhub",
        "腾讯 TokenHub / 混元",
        "https://tokenhub.tencentmaas.com/v1",
        "hy3",
        ["hy3"],
        "使用国内广州 TokenHub API Key；与原混元 Key 不通用，默认关闭思考。",
        "https://cloud.tencent.com/document/product/1823/130079",
    ),
    "siliconflow": Provider(
        "siliconflow",
        "硅基流动",
        "https://api.siliconflow.cn/v1",
        "deepseek-ai/DeepSeek-V4-Flash",
        ["deepseek-ai/DeepSeek-V4-Flash"],
        "使用硅基流动中国站 API Key；模型 ID 包含组织名，按模型广场填写已授权名称。",
        "https://docs.siliconflow.cn/docs/api/chat-completions-post",
    ),
    "spark": Provider(
        "spark",
        "讯飞星火",
        "https://spark-api-open.xf-yun.com/v1",
        "4.0Ultra",
        ["4.0Ultra"],
        "输入对应 Ultra 模型的 HTTP APIPassword；本入口适配非思考模型，X2 等推理模型使用其他地址。",
        "https://www.xfyun.cn/doc/spark/HTTP调用文档.html",
    ),
    "demo": Provider(
        "demo",
        "本地演示（不调用 AI）",
        "",
        "demo-local",
        ["demo-local"],
        "仅在后端允许时可用；无需 API Key，返回带演示标识的固定示例，不代表模型回答。",
        "",
    ),
}
