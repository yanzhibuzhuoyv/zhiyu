# OC 与独立素材库

OC 指用户自己的原创角色。视觉档案描述这个角色的外观，和已绑定的恋人性格分开保存。导入参考图、选择 OC 样例或编辑外观不会解除性格锁，也不会改写聊天记忆。

后台有两套独立素材注册库 `images.sqlite3`、`stickers.sqlite3`，以及独立的 `visual.sqlite3`、`daily.sqlite3`。文件位于素材目录的 `assets/image`、`assets/sticker`，按规范化后的 SHA256 去重。不会把图片或表情包加入原文 RAG。

设置 `ZHIYU_MEDIA_DIR` 可把这些文件放在外置目录或独立 Docker 卷；未设置时使用 `ZHIYU_DATA_DIR/media`。挂载 `/media` 时，要让容器用户 UID 10001 拥有该目录的写权限。备份时需一起保留聊天身份文件 `identity.secret`，否则同一个 Key 会得到新的 owner。

## 导入素材

普通 API 用户只能上传自己的 OC 参考图，不能写共享素材库。共享图片和表情包由本机管理员通过清单导入，文件必须在清单所在目录中。

在 `backend` 目录运行：

```powershell
.\.venv-route\Scripts\python.exe -m app.media_cli --media-dir 'C:\ZhiyuMedia' 'C:\MediaInputs\manifest.json'
```

清单为 JSON 数组，每项有 `kind`、`file`、`metadata`：

```json
[
  {
    "kind": "image",
    "file": "cat.png",
    "metadata": {
      "category": "cat",
      "tags": ["cat", "afternoon"],
      "source_label": "作者提供的猫照片",
      "license": "作者授权应用使用",
      "contains_person": false,
      "angle": "close-up",
      "location": "window",
      "lighting": "daylight",
      "action": "sleeping",
      "time_slot": "afternoon"
    }
  },
  {
    "kind": "sticker",
    "file": "happy.gif",
    "metadata": {
      "category": "object",
      "tags": ["happy"],
      "source_label": "作者提供的表情包",
      "license": "作者授权应用使用"
    }
  }
]
```

`category` 可为 `portrait/selfie/pov/landscape/cat/food/object`。`time_slot` 可为 `any/morning/afternoon/evening/night`。当前表情情境标签为 `happy/comfort/affection`；成熟或疏离性格使用频率更低，焦虑和俏皮性格更高。只用规则筛选已有候选，不在每句分享前调用分类模型。没有匹配素材时不发媒体。

素材可包含 `source_url`、`oc_template_id`、`gender`、`hair` 与镜头变化信息。需要有明确来源及许可。含人物、手、第一人称身体线索，或带性别相关头发/影子的景物，都必须绑定当前 OC 身份，或先作为等待选择的样例模板。不能将任意一张人物图自动当作用户的 OC。

一个样例 OC 分组使用相同 `oc_template_id`，至少一张 `category=portrait` 且 `tags` 包含 `reference` 的参考图；组内其他视角由管理员确认是同一角色。可填写 `gender=female/male/other/unspecified`、`hair=long/short/other/unspecified`。样例默认不属于任何用户，选择后才复制私有元数据，并绑定该用户、新视觉版本与参考图 hash；文件内容仍去重。

## API

所有以下接口需要登录产生的 Bearer Token，不能把 API Key 放进媒体 URL。

| 接口 | 行为 |
| --- | --- |
| `GET/PUT /api/conversations/{id}/visual` | 读取/编辑外观档案 |
| `POST /api/conversations/{id}/visual/reference` | multipart 字段 `file`，导入私有参考图 |
| `POST /api/conversations/{id}/visual/generate` | `{request_id,description}`，两次生成机会 |
| `GET /api/media/templates` | 可选样例目录 |
| `GET /api/media/templates/{id}/reference` | 样例参考图 |
| `POST /api/conversations/{id}/visual/template` | `{template_id}`，显式采用样例 |
| `GET /api/conversations/{id}/daily-scene` | 当前北京日期的虚拟生活轨迹 |
| `GET /api/media/assets/{id}` | 读取已鉴权素材文件 |

Visual 响应包含 `visual_id/conversation_id/mode/description/gender/hair/visual_version/reference_id/reference_sha256/reference_url/updated_at`，以及 `generation={configured,limit:2,used,pending,remaining}`。只编辑文字说明会保留视觉身份；模式、性别或发型改变会使旧人物素材失效，需要新的参考图。人格保持原来的绑定。

消息有兼容性默认字段 `media_attachments:[]`、`origin:"reply"`，主动消息的 `origin` 为 `proactive`。附件记录 `id/kind/url/mime_type/sha256/day_id/visual_version/source_label`，可在历史中恢复，并且不能指向任意外部 URL。媒体文件默认 `no-store`，私有 OC 文件不能被其他 owner 读取。

输入支持 PNG、JPEG、WebP、GIF，最大 5 MiB、2000 万像素、动画 60 帧及 4000 万累计帧像素。图片经解码与重新编码，去掉 EXIF 和嵌入注释；静态图转 PNG，动画保留 GIF。哈希冷却减少连发同图，并优先变化角度、地点、光线、动作。缓存素材不会被描述为真人现场拍摄。

日轨迹使用 `Asia/Shanghai`，每次请求按日期确认独立 `day_id`。北京时间零点后的第一条请求即使用新的一天，重启和后台任务延迟不会沿用昨天；不需要依赖一个恰好在零点存活的 worker。`fictional:true` 表示这是 OC 虚拟日常。

## 后续中转生图接入

当前没有配置中转服务。生成接口明确返回 503，`X-ZhiYu-Error-Code:generation_unconfigured`，不保留任务、不扣次数；不会假装生图成功。将来在 `create_app(..., visual_generator=callback)` 接入异步 provider：回调接收外观描述与 `owner/conversation_id/request_id/visual_id`，返回图片 bytes，provider 也应使用 `request_id` 做幂等。

配额基于已有 HMAC owner 持久保存，跨会话最多两次成功生成；进行中的任务先占位以防并发超过次数。失败会释放占位，成功结果会持久化；同一请求编号可恢复原结果，不再调用 provider。生成参数变更不能复用旧编号。服务器被中断、尚不能确认结果的 pending 任务会保守保留，需运营端查证后处理，不能盲目重复计费。

客户端根据错误 header 区分 `generation_failed`、`generation_unconfigured`、`generation_pending`、`visual_conflict`。明确失败可产生新请求编号，网络结果不明或 pending 必须保留原编号。聊天只选择已缓存素材；在聊天前台不会等生图。

独立主动 AI 原话仍逐句保存作者、时间、ID 和 `origin`，不制造虚假的用户消息，也不融合成新的记忆。近期多条 AI 消息按原顺序进入提示，较早的主动消息可从 FTS 或压缩归档 Bloom 目录中恢复；检索预算未完成时不会把未找到原文说成“从来没有说过”。

## 主动联系与通知

`GET/PUT /api/conversations/{id}/proactive-preferences` 保存是否启用与安静时段，默认关闭、22:00—08:00 北京时间安静。`POST /api/conversations/{id}/proactive/check` 接收稳定 `request_id`，返回 `emitted/message/next_due_at/day_id/reason`。设备有通知权限且用户开启后，WorkManager 才会定期检查；任务输入只含会话 ID，API Key 仍从本机 Keystore 在执行时读取，不写入任务参数。

| 固定性格 | 每日上限 | 联系间隔 | 未回复处理 |
| --- | --- | --- | --- |
| 焦虑黏人 anxious | 12 | 3 小时→1.5 小时→45 分钟→30 分钟 | 逐步加快，保持日上限与安静时段 |
| 疏离克制 distant | 1 | 24 小时 | 一次未回复后暂停；没有既往互动可以不发 |
| 温柔体贴 gentle | 2 | 6 小时 | 连续 3 次后等待回复 |
| 温柔相伴 warm_v1/v2 | 3 | 4 小时 | 连续 4 次后等待回复 |
| 俏皮亲昵 playful | 4 | 3 小时 | 连续 4 次后等待回复 |
| 成熟稳重 steady | 1 | 12 小时 | 一次后等待回复 |

这是首版可验证策略，频率参数可继续调试。焦虑与疏离是新预设，不会覆盖旧用户已固定的性格。自调或无预设暂使用 gentle 的频率基线。检查不会每句做额外性格分类。

通知使用消息 ID 持久去重，同一条原文只入库一次；已在前台、关闭开关或没有通知权限时不生成主动消息。返回途中回到前台则不弹通知，原文仍在同一段聊天中。锁屏公开版本只显示收到新消息，不展示私密聊天内容。Android 的周期任务最短 15 分钟，省电及厂商后台限制可能延迟；本轮没有证明红米后台长时间存活或准点投递。

## 首批素材与许可

本机 `artifacts/media-seed` 包含 6 张 native imagegen 独立生成照片与 8 张 Microsoft Fluent Emoji 表情图，清单为 `operator-manifest.json`。照片为虚构样例，不是现实用户的照片；原生生成工具未提供模型版本，不能标称 image2/image2.5。这批运营素材不占应用用户的两次生成配额。

表情图来自官方 `microsoft/fluentui-emoji` 固定提交 `1ffb34c752ecf5d402f04cfb4b392c77f57c54bc`，完整 MIT 声明在 `licenses/microsoft-fluentui-emoji-MIT.txt`。分发原图、素材包或外置素材目录时必须同时保留该许可；本轮演示素材目录也保存了许可副本。新增素材必须提供真实来源与使用许可。
