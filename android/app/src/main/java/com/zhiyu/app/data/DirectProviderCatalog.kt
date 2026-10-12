package com.zhiyu.app.data

import com.zhiyu.app.domain.Provider

/** Fixed official mainland endpoints; no relay, user URL or fake provider is selectable. */
object DirectProviderCatalog {
    private data class Entry(val provider: Provider, val base: String)
    private fun entry(id: String, name: String, base: String, model: String, models: List<String>, hint: String, docs: String) =
        Entry(Provider(id, name, model, models, hint, docs), base)
    private val entries = listOf(
        entry("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash", listOf("deepseek-flash", "deepseek-v4-pro"),
            "DeepSeek 开放平台 Key；默认关闭思考，Flash 支持识图，Pro 图片需另配识别服务。", "https://api-docs.deepseek.com/"),
        entry("qwen", "阿里云百炼 / 通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", listOf("qwen-plus", "qwen-flash"),
            "使用中国站北京地域百炼 Key，模型需开通；其他地域 Key 不通用。", "https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions"),
        entry("kimi", "Kimi / 月之暗面", "https://api.moonshot.cn/v1", "kimi-k2.6", listOf("kimi-k2.6"),
            "国内 Kimi Key；K2.6 默认关闭思考，K3 / K2.7 保留思考尚未适配。", "https://platform.kimi.com/docs/api/models-overview"),
        entry("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-flash", listOf("glm-4.5-flash", "glm-5.2"),
            "智谱按量 Key，默认关闭思考；Coding Plan 使用不同入口。", "https://docs.bigmodel.cn/cn/guide/develop/openai/introduction"),
        entry("doubao", "豆包 / 火山方舟", "https://ark.cn-beijing.volces.com/api/v3", "", emptyList(),
            "填写已开通 Model ID 或 ep- 接入点 ID；使用方舟按量 Key。", "https://docs.volcengine.com/docs/ark/base-url-and-authentication?lang=zh"),
        entry("minimax", "MiniMax", "https://api.minimax.cn/v1", "MiniMax-M2.7", listOf("MiniMax-M2.7"),
            "国内 MiniMax 按量 Key，纯文本聊天；模型思考需要充分输出预算。", "https://platform.minimax.cn/docs/api-reference/text-openai-api"),
        entry("qianfan", "百度千帆 / 文心", "https://qianfan.baidubce.com/v2", "ernie-4.5-turbo-128k", listOf("ernie-4.5-turbo-128k", "ernie-4.5-turbo-32k"),
            "使用千帆 v2 API Key，先开通模型；不是旧 AK/SK。", "https://cloud.baidu.com/doc/qianfan-docs/s/Fm9l6ocai"),
        entry("hunyuan", "腾讯混元（原平台）", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest", listOf("hunyuan-turbos-latest"),
            "原平台 API Key；新开通服务可选择 TokenHub。", "https://cloud.tencent.com/document/product/1729/111007"),
        entry("tokenhub", "腾讯 TokenHub / 混元", "https://tokenhub.tencentmaas.com/v1", "hy3", listOf("hy3"),
            "国内 TokenHub Key，与原混元 Key 不通用。", "https://cloud.tencent.com/document/product/1823/130079"),
        entry("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V4-Flash", listOf("deepseek-ai/DeepSeek-V4-Flash"),
            "中国站 Key，按模型广场填写已授权模型名。", "https://docs.siliconflow.cn/docs/api/chat-completions-post"),
        entry("spark", "讯飞星火", "https://spark-api-open.xf-yun.com/v1", "4.0Ultra", listOf("4.0Ultra"),
            "使用对应 Ultra 模型 HTTP APIPassword，推理模型入口不同。", "https://www.xfyun.cn/doc/spark/HTTP调用文档.html"),
    )
    fun providers(): List<Provider> = entries.map { it.provider.copy(models = it.provider.models.toList()) }
    fun endpoint(providerId: String): String = (entries.find { it.provider.id == providerId }
        ?: throw IllegalArgumentException("请选择支持的 AI 公司")).base + "/chat/completions"
}
