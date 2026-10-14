# 知语 · 原生 Android AI 恋人

当前开发版本为 **0.2.7 手机独立版**：本机保存独立原话、资料与素材，直接通过 HTTPS 调用选择的 AI 公司，无需电脑后端、Docker 或 USB。API Key 登录、固定恋人设定和一个持续聊天页面保持；云模型仍需联网。安装与升级边界见 [Android 本地版](docs/ANDROID.md)。

0.2.7 新增手机“动态形象”工坊：确认 OC 后手动修正眼口、头颈、头发和附件，提供撤销/重做、原图对照、角色包存取，保存后接入通话页的 GLES 网格动画和现有口型。前视角局部动画已在电脑 AVD 验证，完整自动分层与真正全双工仍未完成。详见 [制作、升级和验证](docs/PLANAR_ANDROID_2026-10-14.md)。

0.2.6 新增带图片的主动日常分享：按固定性格每天加权伪随机选择 0–7 次，关联当天真实保存的 OC 轨迹事项，只发送对应的已就绪照片；缺图等待，过时跳过，重启不重抽、不重复发送。原离线学生模型包继续使用。详见 [分享机制、安装与验证](docs/DAILY_PHOTO_SHARE_2026-10-13.md)。

0.2.3 修复已导入／生成形象却在视频页提示未设定；照片提交后即在聊天显示，识图与回复随后继续，回复失败仍保留原图。按最新要求移除 2D／3D 建模，历史模型使用已经保存的图片；照片局部动画保留。[修复与测试](docs/CALL_CHARACTER_FIX_2026-10-11.md)。

OC 使用导入或生成的已确认图片、前景提取与手动修正。固定主体用于后台生活图库，照片局部眼口网格与离线声音接入轮流通话。完整自动 Cubism 拆层绑骨和全双工尚未完成。[实现与边界](docs/MOBILE_FEATURE_PARITY.md)

## 手机本地版当前状态

Kotlin Android 是当前手机产品的唯一功能实现；Python 后端明确冻结为 legacy，只修安全、兼容和契约正确性。手机使用 BGE INT8 语义主路径与 FTS4 补查，旧后端使用 FTS5/BM25，两侧 schema、迁移及排名不等价。共享 JSON 生成两端带 ID 的行为规则与 `ok/not_searched/partial` 检索状态；状态只描述执行过程，不证明用户从未说过。旧后端 A/B 结果不作为手机召回等价证明。[共享契约及本轮验证](docs/NATIVE_RETRIEVAL_CONTRACT_V4.md)。

真正中文 BGE INT8 语义检索、逐条无损原话存档、后台三层标注及更正指针、日轨迹和两套本机素材库已接入。0.2.2 接入持久两次 OC 生成、固定主体生活图库、图片聊天／后续追问、2D／3D 编辑和离线轮流语音／角色视频通话。生日年度提醒及已定约定的取消／改期静默处理；独立原文证据满足要求才修改本 App 日历。见 [API 实测](docs/AGNES_API_TRIAL.md)、[OC 制作](docs/OC_CREATOR_V4.md) 和 [离线声音](docs/LOCAL_VOICE_NATIVE.md)。

当前修复验证见 [0.2.3 记录](docs/CALL_CHARACTER_FIX_2026-10-11.md)，此前功能集成见 [0.2.2 记录](docs/NATIVE_V4_VALIDATION_2026-10-11.md)。此前一万轮／两万条长原话、五千万 Unicode／十万真实语义分片测试已完成，原文和重启校验通过；严格全汉字补测按用户要求停止，不计为通过。本轮没有重跑长测或替换 RAG 算法，大库召回仍约20.66–21.56秒。测试只操作隔离模拟器，不等于红米实测；同签名调试包供覆盖试用，正式收费发行另做。

## 0.1.x 电脑后端版历史记录

以下保留此前实现与测试背景。旧包、USB 后端连接及服务端身份方式不适用于当前手机本地包；当前验证结果以新的本地报告为准。

表情已改为同一次聊天回答中的语义选择，日常分享也可自然配图，成熟/疏离性格少发。电脑试用库改用 QQ 风格软萌仓鼠与真实猫狗反应图，旧圆脸样例退出候选但历史保留。[当前表情行为](docs/STICKERS.md)、[测试与素材来源](docs/STICKERS_VALIDATION.md)。

Kotlin + Jetpack Compose 原生应用，FastAPI + SQLite 后端。选择 AI 公司、输入自己的 API Key 后进入同一段持续聊天；成功验证后本次安装固定公司与模型。选择页显示“恋人仅此唯一，此择不可挽回”。聊天记忆直接召回已保存的较早原话，资料导入使用弹窗。

0.1.3 新增透明描边气泡、头像与背景、OC 图片导入/样例、独立图库与表情包库、北京日期虚拟生活轨迹，以及可关闭的主动联系通知。记忆管理入口已从用户界面移除，后台仍保留每条完整原话。焦虑型未回复时逐步增加联系频率，疏离型最多一次或不发；新预设只用于首次选择，已有绑定保持原样。调试包 `artifacts/ZhiYu-0.1.3-media-debug.apk`；[素材与接入](docs/MEDIA.md)、[本轮电脑验证](docs/MEDIA_VALIDATION.md)。中转生图尚未配置，两次生成机会会保留。

可选“我们调好的恋人”：温柔体贴、俏皮亲昵、成熟稳重，支持女友、男友或不限定，以及名字和称呼；成功绑定后整份预设不可修改。“自行调教”可更新自己的恋人设定，继续原聊天；不启用设定时不增加性格预设。后端把恋人设定作为系统消息使用，把旧聊天和资料当作低信任参考。默认中文 BM25，支持可选本地 BGE 文档检索。

新增“温柔相伴·试用版”`warm_v2`：稳定的温柔底色，也能表达不同意见和暂时妥协；当前互动不会自动改成永久性格。电脑直接打开 <http://127.0.0.1:8798/trial>，选择公司、输入自己的Key试用；安卓包为 `artifacts/ZhiYu-0.1.1-warm-v2-debug.apk`。[试用方式、真实对照与限制](docs/COMPANION_PRESET_TRIAL.md)。旧预设及已绑定初版均保留。

USB真机修复包为`artifacts/ZhiYu-0.1.2-usb-debug.apk`；启动电脑后端并开启USB调试后，运行`.\scripts\Start-AndroidUsbDebug.ps1 -ApkPath .\artifacts\ZhiYu-0.1.2-usb-debug.apk -RestartApp`覆盖安装、建立连接。保留原Key与绑定，默认不再使用模拟器专用地址。[USB联调说明](docs/ANDROID.md)。

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

- 普通进入页只要求公司与 API Key；后端地址由构建配置提供。0.1.2调试默认 `http://127.0.0.1:8791`，USB手机通过ADB端口转发访问电脑Docker测试服务。
- 使用 `scripts/Start-AndroidUsbDebug.ps1` 建立连接；模拟器同样可用ADB reverse。局域网地址可在debug“开发设置”调整，需另行配置后端监听。
- release 通过 `-PzhiyuBackendUrl=https://你的域名` 配置 HTTPS 后端，配置非 HTTPS 会拒绝构建；未配置的包提示服务尚未配置。部署需证书及正式签名。
- 新版后台记忆调试 APK 位于 `artifacts/ZhiYu-0.1.1-background-memory-debug.apk`；旧包保留，界面和构建说明见 [ANDROID.md](docs/ANDROID.md)。

## 知识库

聊天“更多设置→资料”弹窗导入 txt / md / pdf / docx，单个最大 5 MiB。扫描 PDF 暂需先 OCR。相关内容参与回答并展示来源；聊天长期记忆独立于文档 RAG。不同服务商和 Key 的资料、会话分别隔离。

复用了 `../rag-system` 的中文检索逻辑并保留 MIT 声明；原工程、索引和 `.env` 保持原状。[旧索引导入与语义模式说明](docs/RAG.md)。导入工具先 `--dry-run`，再使用当前连接的令牌导入指定身份。

## 服务商与模型

[服务商适配说明](docs/PROVIDERS.md)列出已核验的官方 API 地址和差异。首次选择时可展开模型设置，成功验证后固定；实际调用取决于 Key 的授权、区域、余额和厂商支持。豆包需填写已授权模型 ID 或 `ep-` 接入点 ID。

Key、绑定与自调设定由 Android Keystore 加密保存。退出仅清除 Key/token，不解除绑定；旧安装升级沿用已有连接及原恋人设定。绑定依赖本次安装，清数据、卸载或换设备不会保留本机绑定。API Key 是后端数据身份，更换不同 Key 不自动迁移旧聊天。后端只短期将 Key 保存在进程内存；重启后设备自动重新鉴权。`identity.secret` 与 SQLite 须一起备份。

双方每条原话均独立入库，正文只存一份并可无损压缩；按作者、相关性与时间权重并列召回，保留最新用户修订，不做记忆融合。三层标注已持久保存并参与排序；Docker默认使用所选AI在后台额外识别一次本人资料、偏好、愿望和承诺，完整原文与待办保存后先返回回复，严格核对原话出处，失败仍保留本轮聊天。同一次聊天优先回应当下，旧事只在有帮助时自然带出；应用尚未启用独立意图分类器。连续实测中分类比既有检索更贵，暂不接入前台跳过。基础类系统提示区分“无法确认”和“明确没发生”，AI猜测／用户问句不能证明用户事实，但模型仍有违反规则的案例。[连续实测与事实限制](docs/MEMORY_ROUTE_CONTINUOUS_EVAL.md) · [本地路由三轮](docs/MEMORY_ROUTE_EVAL.md) · [后台行为与边界](docs/MEMORY_ATTENTION.md)。记忆管理 API 保留供内部使用，Android 用户界面不再显示记忆选项。冷归档保留完整原文并支持回查，长期/永久及未解决承诺受到保护。没有自动删除或生产批量冷却任务。附近记录的联系须由原文支持，不能仅凭时间接近认定。

历史每批最多100条；原文与索引仍会增长。语义标注不等于语义向量检索；此段记录的旧后端聊天候选为FTS/Bloom，未实现Engram。[三层与语义识别](docs/MEMORY_TIERS.md) · [冷归档容量实测](docs/MEMORY_COLD_PROOF.md) · [正文存储](docs/BODY_STORAGE.md) · [记忆与恋人设定](docs/CONVERSATION_MEMORY.md) · [存储与维护](docs/STORAGE.md)。

新增原文检索顺序研究版：A 词项先查、语义补漏；B 语义先查、词项补漏。48道冻结合成题和千/万轮增长实验已完成，另有114次官方DeepSeek调用及逐题独审。两版检索耗时接近，换顺序仍不能消除模型把“未查到”说成“你没告诉过”的错误；研究代码尚未替换生产检索。[两版结果与失败记录](docs/MEMORY_RETRIEVAL_ORDER_EVAL.md)。

两版又分别测试完整回答的出处与范围校验：24道新题、8条长回复、24条预写挑战，首轮192次官方调用及另22次日期回归；保留草稿、审核、一次修订和再审。校验仍有漏判/误拦截，普通校验增加的等待中位约1.4秒，未接入前台。[校验实测与日期口径限制](docs/ANSWER_GUARD_AB_EVAL.md)。

新增单次来源分段对照：32道新题、128条普通及16条长回答，A/B各比较自由回答和JSON来源约束。代码标明原话作者/保存时间，但6次JSON只返回空白，自然段仍有“没跟我讲过”等范围错误，完整引文降低陪聊自然度；未替换默认聊天。[单次生成实测](docs/EVIDENCE_REPLY_AB_EVAL.md)。

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

已部署版本支持后台识别、旧事呈现和基础证据提示；此前Windows/Linux后端各757项通过、覆盖率分别95.07%/94.89%，本轮Windows/Linux脚本各612项通过，新增单次来源回答研究模块覆盖92.98%。Android沿用此前65项单元测试、19项API36模拟器专项联测，APK接口未改。此前连续实验另完成102次官方DeepSeek调用，技术完整结束与事实准确分开评价。此前千轮长字段完整流程通过，两万条长消息副本在全部原文一致的条件下，压缩及冷归档后整理副本主库540.20→224.29MiB；工作文件不会因释放页立即缩小。这是合成库的全冷场景，保留重要活跃记忆的真实用户库不保证同一比例。[实际验证、镜像差异与限制](docs/VALIDATION.md)。本机8791运行新版后端，普通用户只选公司和Key；本轮没有启用自动skip或修改APK。

此前万轮保存/检索检查点通过但最终删除失败的原报告仍保留failed；独立删除修复和后续完整千轮报告分别留证，不合并为新镜像从零万轮通过。[历史耐久记录](docs/LONG_FIELDS_ENDURANCE.md)。不做记忆融合；合成测试不代表真实云模型回复正确率。
