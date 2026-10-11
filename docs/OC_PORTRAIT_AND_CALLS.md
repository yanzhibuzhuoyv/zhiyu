# OC 人物提取与实时通话实施记录

研究与首版处理器验证日期：2026-10-09。用户已明确改为单手机本地版，当前 Android OC 入口不再访问电脑后端。这里区分图像处理、待验证的原生体验和后续通话工程；不将透明 PNG 标记成 Live2D。

## 当前原生手机实现

`LocalMediaStore` 将图片库与表情包库存放到应用私有 `files/local_media/image` 和 `sticker`；兼容字段中的 `/api/media/assets/<id>` 只是内部标签，`MediaController.image` 读取本地文件，不发 HTTP。OC 的原图与主体通过 `purpose=portrait` 排除出日常聊天素材候选。同一分类、用途、外观版本、SHA 的重复导入复用元信息；不同外观版本保留独立元信息，原始像素文件仍按 SHA 共用。单条标注最多 16 KiB，单分类最多 20000 条；查询默认最多 500 条，调用可指定 1–1000，不一次解析全部标注到内存。

`LocalPortraitProcessor` 直接使用 APK 打包的 `assets/u2netp.onnx` 与 ONNX Runtime Android 1.30.0，校验与后端相同固定 SHA，CPU 会话 2 线程，tensor/result/session/options 按次关闭。不在手机启动或导入时下载模型。输入原始字节保持不变，JPEG EXIF 旋转和翻转在工作副本中处理。为控制合法 20 MP 输入的峰值内存，所有自动、手动和显示解码均先按采样限制为最长边 1536、工作像素不超过 4 MP；模型输入继续是 RGB 320×320。PNG 编码在写入时限制 5 MiB，不使用无界输出后再验证。

预览的 `coordinateSpace=working_pixels`，`originalWidth/originalHeight/bounds` 表示完整工作画布及其裁切范围，`sourceWidth/sourceHeight` 单独保留原图经过 EXIF 校正的尺寸。归一化笔画相对完整工作画布，不相对裁切后的主体。高分辨率原始文件仍原样保留；最终主体是工作副本分辨率，不宣称逐像素保留 20 MP 的抠图结果。旧后端预览默认 `original_pixels`，其坐标含义保持兼容。

自动分离只给显著前景预览。已有合理透明轮廓可复用；已知的模型／图像／IO 失败会持久记录原因，并显示 `method=manual_selection` 的空手工画布与“自动分离未完成，请用笔刷选中人物”。取消和 OOM 不伪装为分离成功。空手工画布不能直接确认人物；用户可以使用保留笔刷选出角色，也可以采用原图。

`SubjectRefineDialog` 显示淡色完整原图与按 bbox 对齐的当前主体，提供保留／擦除、连续拖动或点选、半径、撤销、重画和应用到预览。人工处理继承所有非零 alpha 区域，不固定翼数或道具数、不选最大连通区域、保留 alpha 上限不超过原图。应用只更新待确认主体；最终“使用这个人物／仍使用原图”才更新外观。`LocalVisualRepository` 在本地锁内检查会话归属、24 小时预览有效期、尚未确认、外观版本与预期主体 SHA；过期、已确认或陈旧编辑拒绝，原图及两次未配置生图机会不受影响。待确认预览可以在应用重启后恢复，也可以明确丢弃。

本地新增纯规则测试与两个隔离仪器类：`PortraitRefinementRulesTest`、`LocalPortraitInstrumentedTest`、`LocalVisualInstrumentedTest`。仪器类使用随机前缀的 ContextWrapper、缓存目录和独立 SharedPreferences，只清理自身测试空间，涵盖真实 Bitmap 的多翼／武器 union、连续笔刷、透明度、空图、4000×3000 JPEG 的有界工作图与原 SHA、真实打包 ONNX CPU 输出、外观确认、过期与并发 SHA 校验。此处先记录源码范围；原生编译和仪器通过结果以根任务的统一构建与隔离模拟器记录为准，不把后端 75 项通过当成 Android 已通过。

构建通过 `preparePortraitAssets` 对显式本地模型核 SHA 后复制到 APK，权重没有加入源码仓库。`portrait-model-NOTICE.txt`、`portrait-model-APACHE-2.0.txt` 和 `portrait-runtime-MIT.txt` 随 APK 保留模型来源、固定哈希与上游完整许可。U-2-Net 仓库与作者预训练下载入口发布于 Apache-2.0 项目；rembg 的 ONNX release 未单独附该文件的模型授权书，声明保留这个边界，不能把 rembg 软件 MIT 自动替代模型许可。[U-2-Net 官方许可证](https://github.com/xuebinqin/U-2-Net/blob/master/LICENSE)、[Android 官方部署说明](https://onnxruntime.ai/docs/tutorials/mobile/deploy-android.html)

此前规划的 HTTP refine 接口随单手机范围变更而延期，未将尚不存在的后端路由测试留在正式测试目录。对应红测试副本保存在 ignored `artifacts/portrait-refine-backend-deferred-test.py`，纯后端笔刷和既有预览 API 测试保留。

## 已验证的独立后端 CPU 处理器

原导入入口只做图片解码、尺寸限制和元数据剥离，然后直接绑定参考图；复杂背景没有被处理。新处理器 `backend/app/portrait_subject.py` 在导入时运行一次本地 CPU 分割，分别返回完整规范化原图和透明主体预览。原图不覆盖、不发送到外部图像服务。

当前采用 **U2NetP**。它是显著前景分割，不能仅凭一个 mask 证明“这是人”或“这是用户想要的 OC”。上游提供通用 U2Net 和轻量版本，论文的轻量模型约 4.7 MB；其目标是显著物体检测。[U-2-Net 原始仓库](https://github.com/xuebinqin/U-2-Net)、[原始论文](https://arxiv.org/abs/2005.09007)

实现约束：

- 输入先经过现有 `normalize_image`，沿用 5 MiB、20 MP、最大边 8192 像素限制；动画要求另选静态图。
- 可传 `(left, top, right, bottom)`，坐标是完整规范化原图上的整数像素，右／下边界不包含在区域内。裁框外内容不会覆盖原图。
- CPU 输入准备阶段先将最长边限制到 1600，再按上游预处理缩放为 320×320；模型输出恢复到所选区域的像素坐标。
- 输出为独立透明 PNG、原图坐标中的主体边界、处理方法和固定 `needs_confirmation=true`。多人场景让用户框选或确认；不能自动把最大的对象认作恋人。
- 已有透明轮廓只有在可见覆盖率、边缘透明比例合理时复用；一个透明像素、整体半透明或仅内部透明洞不会被当作抠图完成。重新分割会乘上原 alpha，不把原透明区域改成不透明。
- 空主体、非有限数、错误输出形状、常量 mask、几乎全图的 mask 都明确报错，不假返回“成功”。
- 无网络下载分支，不执行模型附带 Python；只使用固定 ONNX 和 CPUExecutionProvider。启动或第一次处理仅读取显式配置的本地权重。

`PortraitProcessor(Path).extract(raw, crop_bounds=...)` 返回冻结的 `PortraitSubject`：`original`、`subject`、`bbox`、`method` 和 `needs_confirmation`。头像、图片库、OC 身份版本仍应在用户确认后更新。预览创建不能直接修改恋人性格或已有 OC 档案；确认需校验 owner、会话与预览对应的外观版本。

## 单翼、多翼和道具的人工修整

OC 可以有单侧翅膀、多对翅膀、分离的武器或透明光效，自动显著前景模型仍可能漏掉这些部分。新增 `backend/app/portrait_refinement.py` 提供与模型独立的保留／擦除笔刷：`refine_subject(original_raw, subject_raw, bbox, strokes)`。它将已有预览 alpha 按原图 bbox 还原成完整尺寸，再按用户笔画修整，不重新预测，不使用人体肢体数量、翼数或最大连通区域规则。

每条笔画结构为：

```json
{"action": "keep", "points": [[0.1, 0.3], [0.32, 0.4]], "radius": 0.01}
```

`action` 只接受 `keep`／`remove`；坐标是完整原图上的 0–1 归一化横纵值，笔刷半径相对完整原图短边为 0.002–0.15。连续点连线并补圆形端点，保留和擦除按提交顺序执行。最多 128 条笔画、每条最多 1024 点属于输入资源限制，不限制 OC 的翼数或部件数量。非有限数、布尔数值、越界坐标、未知字段和不匹配的预览 bbox 都明确拒绝。

预览以完整原图 RGB 重新合成；保留后的 alpha 上限为原图自身 alpha。重复编辑使用 alpha 上限而不是反复相乘，半透明翅膀不会越来越淡。所有 alpha 大于零的像素共同确定裁切边界，因此断开的武器、左右多翼和低透明度光效可以一起保留。擦除到完全没有可见内容时明确失败，原图始终保留。结果仍为 `method="manual_refinement"`、`needs_confirmation=true`，最终采用前继续校验用户身份、会话和外观版本。

新增测试先因模块不存在而红，随后 **45 项通过**，覆盖单翼、六对翼加独立武器、连续笔刷与圆端点、擦除后再次保留、原透明区域保护、重复编辑不渐淡、alpha=1 光效、输入边界和空主体。该验证是笔刷与像素规则的隔离测试，不代表自动模型已经学会识别所有 OC 构造，也不代表图片已经完成 Live2D 拆层。

## 权重、依赖及授权来源

| 选择 | 实际权重体积 | 适用范围与本轮结论 |
| --- | ---: | --- |
| U2NetP ONNX | 4,574,861 B（4.36 MiB） | 本轮 CPU 试用；通用显著前景，需确认，不保证复杂多人或动漫可靠性 |
| U2Net 人体分割 ONNX | 175,997,641 B（167.84 MiB） | 真人专用候选；没有据此宣称覆盖二次元 |
| ISNet Anime ONNX | 176,069,933 B（167.91 MiB） | 二次元专用候选；后续建立实际样本集再决定是否部署 |
| ISNet General Use ONNX | 178,648,008 B（170.37 MiB） | 较大通用模型，留作质量对照，当前没有下载 |
| MobileSAM | 上游检查点约 40 MB；完整 ONNX 部署大小需导出后确认 | 点／框提示可解多主体歧义；官方 ONNX 示例并不等同于开箱可用的全流程 CPU 包 |

前三类与通用 ISNet 的字节数来自官方发布元数据，未下载大权重。[rembg 模型发布元数据](https://api.github.com/repos/danielgatis/rembg/releases/tags/v0.0.0)。动漫模型源项目为 Apache-2.0，并提供 ONNX 导出方法。[Anime Segmentation 原始仓库](https://github.com/SkyTNT/anime-segmentation)。MobileSAM 原始仓库支持点／框提示和 ONNX 导出，仓库标为 Apache-2.0；其 CPU 演示耗时不能代替本机验证。[MobileSAM 原始仓库](https://github.com/ChaoningZhang/MobileSAM)

本轮权重固定为：

```text
URL: https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx
SHA256: 309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8
MD5: 8e83ca70e441ab06c318d82300c84806
Bytes: 4574861
```

SHA256 是本轮对该小权重实物计算的结果，MD5 与上游下载器一致。推理前按固定 SHA256 校验字节，再由 ONNX Runtime 从同一份字节加载，避免校验后路径替换。[上游 U2NetP 会话与校验代码](https://github.com/danielgatis/rembg/blob/main/rembg/sessions/u2netp.py)

依赖单独固定在 `backend/requirements-portrait.in` 和 `.lock`：NumPy 2.5.3、ONNX Runtime 1.30.0、packaging 26.3、flatbuffers 25.12.19、protobuf 7.36.2；Pillow 由后端主锁提供。安装需要 `--require-hashes` 与仅二进制包；当前验证只向项目 `.venv-route` 新增 ONNX Runtime、flatbuffers、protobuf，没有修改全局 Python，也没有升级已有 NumPy／packaging。ONNX Runtime 的 CPU Python 包与 GPU 包独立，运行库采用 MIT。[官方 Python 安装说明](https://onnxruntime.ai/docs/get-started/with-python.html)、[运行库许可证](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)

U-2-Net 源仓库采用 Apache-2.0；rembg 包采用 MIT，但不同模型权重的授权需分别记录。当前部署使用 U-2-Net 作者模型经 rembg 发布的固定导出文件，保留来源与哈希；正式分发时带上相关许可证和通知，不把 rembg 的 MIT 自动视为所有权重的授权。尤其不要不指定模型就调用最新 rembg 的默认配置：其官方 README 当前默认 BRIA RMBG-2.0 约 1 GB，商业使用要求另行协议。[rembg 原始文档与模型授权提示](https://github.com/danielgatis/rembg)

## 实际验证与边界

先创建测试，再实现：模块不存在时测试收集失败；完成后 27 项隔离用例通过。审查复现了透明度捷径误判，新增 3 项测试先红后绿，目前 **30 passed，模块覆盖率 86.99%，Ruff 通过**。测试使用临时模型字节和合成像素，不触碰用户媒体卷。

固定实物权重实际跑了现有的 3 张生成 OC 素材：女性参考图、男性参考图和男性镜前自拍。全链路含规范化、CPU 载入／分割、PNG 编码约 **3.73–4.11 秒／张**，三张均得到 alpha 0–255 的独立透明预览。记录在 ignored `artifacts/portrait-smoke/results.json`；`*-subject-checker.png` 是只供检查的棋盘底合成图，实际导入产物仍是透明图。

人工检查镜前自拍：人物主体保留，房间背景已去除；手机道具部分半透明、发丝边缘偏软。因此它可以作为首版确认预览，不能称为精细人像抠发完成。当前没有真人复杂多人、遮挡、纯风景误判或正式二次元测试集的质量统计；不以这三张冒烟图宣称普遍成功率。通用模型和动漫专用模型后续用同一批原图 A/B，分别记录主体是否选对、头发／手部遗漏、背景残留、运行时间与峰值内存。

## Live2D 的分阶段路径

透明人物图解决前景提取，但没有眼睛、嘴巴、头发、身体等独立图层，也没有被遮挡部分的补画、网格和参数绑定。Cubism Editor 的标准路径是素材拆分、分层 PSD 导入、ArtMesh／变形器和动作参数，随后导出运行文件。[官方 PSD 导入说明](https://docs.live2d.com/en/cubism-editor-manual/psd-import/)

用户要求的是**根据每位用户上传的 OC 原图制作自己的角色**，不是用通用成品替代。后续流程为：保留原始文件／SHA → 确认角色主体和独立翅膀／武器／饰品 → 多部件分层及遮挡区域补画 → ArtMesh／rig 与参数 → 运行时嘴型、眨眼和动作。部件列表由该 OC 的实际造型决定，不设固定翼数、人体数量或必须具备某类器官。

本轮只完成这一流程的输入侧。模型准备记录应包含 `status=awaiting_layering`、原图 ID／SHA、确认主体 ID／SHA、工作坐标／bbox、源尺寸及 `visualVersion`。制作产物再绑定这些字段；外观／源 SHA 改变时旧模型不能静默用于新 OC。准备记录不是生成任务成功状态，也不能用空 `.moc3`、假 layer JSON 或整体摇摆照片宣称已经建模。

后续从各自原图拆层、补画和 rig，需要专门服务或制作工具。真正 Cubism 运行包包括 `.model3.json`、`.moc3`、纹理、动作／物理文件；SDK Runtime 负责加载已导出的产物，不具备从 PNG 编译 `.moc3` 的通用能力。Compiler／Editor 工具、Core 以及正式发行许可需按官方流程另行配置。本版没有打包 Cubism Core，也没有从用户图生成完整 Live2D。可选整体呼吸／摆动只能作为立绘动画试用，不能替代逐用户拆层制作。[官方模型导出说明](https://docs.live2d.com/en/cubism-editor-manual/export-moc3/)

Android 原生项目可优先评估 Cubism SDK for Java，官方说明兼容 Kotlin；Framework 用来显示 Editor 产物，并配合 Core 加载模型。Core 不在 Framework GitHub 仓库，需要官方 SDK。[官方 SDK 平台说明](https://www.live2d.com/en/sdk/about/)、[Java Framework](https://github.com/Live2D/CubismJavaFramework)、[官方模型加载说明](https://docs.live2d.com/cubism-sdk-manual/model-java/)

SDK 开发试用没有初始费用，正式发布须按实体类型与应用类别确认授权。用户能够导入任意模型的应用尤其应核对官方 Expandable Application 分类，不能只套“小型企业免费”一句。[官方 SDK 发布授权](https://www.live2d.com/en/sdk/license/)

## 实时通话与已绑定恋人的连续性

当前代码只有文本 Provider 的完整回答调用；Android Manifest 没有录音权限，也没有 ASR、TTS、实时音频会话或 Cubism 运行库。人物分离的通过不代表语音通话已经实现。

建议先实现可打断的语音渠道，同时保留用户已经绑定的 AI 公司、模型、性格及同一份原文记忆：麦克风持续采集 → 流式 ASR → 原恋人的文本生成 → 流式 TTS；采集和播放可以并行，用户开口时停止旧播放并取消过期生成。输送音频的全双工和端到端模型同时理解／产生语音是两层能力，需要分别测量，不能把串行 ASR→LLM→TTS 改名成已完成的端到端全双工。

火山引擎官方已经提供豆包端到端实时语音及 Android 对话 SDK，文档包含双向流式、打断与外部 RAG 的接入形式；较新实时语音接入文档还描述全双工 S2S。这是可评估的供应商能力，并非本项目已接通。[官方 Android 语音 SDK](https://docs.volcengine.com/docs/DoubaoVoice/End-to-endAndroidSDKinterfacedocumentation?lang=zh)、[实时语音接入说明](https://docs.volcengine.com/docs/DoubaoVoice/access-mustread?lang=zh)

语音服务需要自己的资源和鉴权配置；官方 Android 文档列出了 APPID、APPKEY／Token 和语音资源 ID，不能拿 DeepSeek 文本 Key 直接假装完成该服务鉴权。若直接使用豆包端到端 S2S，回答生成也交给豆包，可能改变用户已绑定的恋人模型；因此默认优先把 ASR／TTS 当语音渠道，端到端模式要单独设计成用户设定时明确选择的能力。

通话工程还需要：麦克风权限及关闭状态、回声消除与耳机／扬声器切换、播放队列和打断、断线重连、音色配置、音频时戳与嘴型同步。记忆只写入用户确认的完整识别原句和实际播放的 AI 原句；取消后尚未播放的回答不能被当作已经说过的话。预设性格和原文记忆规则继续沿用。

后续验证按真实链路记录：用户停顿到首个可听音频、打断到停止播放、重连是否重复说话、回声被误识别的次数、长通话资源增长和切换设备后的恢复。当前没有语音供应商凭据接入、可运行 Live2D 资产或完整实时通话测试，因此这部分是实施方案，不能列为已交付功能。
