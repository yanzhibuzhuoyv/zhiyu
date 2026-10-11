# Qwen 本机背景生成：模型包与 Android 接入关口

2026-10-11。本文记录移植准备契约，不表示手机已经出图，也不改变 0.2.3 安装包。原模型、已有 OC、聊天、Key 和生成机会账本均不迁移、不删除。当前能力限定为 `text_to_image_background`；识图、参考图编辑、生成新人物姿态并保持原 OC 的能力没有因此获得。

## 最小替换位置与收费边界

- `LocalSceneStock.prepare` 已把背景与固定人物分别处理，随后在当前外观锁内核对日期、visualVersion、referenceId 和角色 packageSha，再写入图片库。本机生成空背景后可复用该人物合成与发布边界。
- `LocalGeneratedBackgrounds.get` 当前先读 Agnes Key、保留收费请求、调用 Agnes 生成，再审核背景。它的默认 `BackgroundContentGate` 还会调用 AgnesVisionClient；只替换 `client.generate` 仍会产生云端识图调用。因此本机路径应作为独立背景来源，不把现有带收费账本、Key 与审核器的完整对象直接改名复用。
- `LocalVisualRepository.generate` 是初次 OC 候选生成入口，具有两次机会、候选保留和外观版本保护。本阶段不替换它，避免把仅验证背景能力的原型放入恋人形象入口。独立识图、已有本机抠图和参考图编辑保持原入口。
- 本机背景生成失败或取消时，不能隐式调用 Agnes 或其他收费服务；可以保留已缓存图片、使用既有 Canvas 场景，并把来源分别标为本机模型、Canvas 或云生成。Canvas 不能记作模型出图成功。
- 提示词写“无人”不能证明图中没有人物。未来合成前仍须有可信的背景审核；纯本机审核尚未实现时，不自动发布未经审核的模型背景，也不偷偷调用现有收费视觉审核。用户确认收据或验证后的独立本机审核都是后续可评测入口，不能直接把已有云审核收据伪造成本机审核。

当前新增模块没有产品按钮、服务选择、自动下载、网络客户端、Native 调用或收费回退。

## 模型包 schema v1

根对象必须且只能有以下字段；每个嵌套对象也拒绝未知、重复或缺失字段。根 JSON 使用严格 UTF-8，最大 64 KiB。导出的实际文件字节与 SHA256 必须填入清单，不能用理论 4 bit 大小代替。

| 字段 | v1 约束 |
| --- | --- |
| `format_version` | `1` |
| `pack_id` | 1–64 个小写英文字母、数字、下划线或连字符 |
| `architecture` | `qwen-image-2.1-turbo` |
| `capabilities` | 仅 `['text_to_image_background']` |
| `files` | `student`、`adapter`、`dit`、`vae` 四个 role 各一次；每项只有 `role/path/bytes/sha256` |
| `runtime` | `llama_cpp_commit`、`stable_diffusion_cpp_commit` 为实际 40 位小写 commit SHA，`bridge_version=1` |
| `conditioning` | `adapter_format=zqadap1_f32_le`、`in_dim=6144`、`out_dim=4096`、`layers=[-1,-4,-7,-10,-13,-16]`、`drop_tokens=14` |
| `sampling` | `width=512`、`height=512`、`steps=8`，以及完整 9 个 sigma 边界 |

冻结 sigma 网格为 `[1,0.978453,0.95418,0.926626,0.89508,0.845148,0.704534,0.414568,0]`。模型原配置的 8 个正值加终点 0，表示 8 个积分区间；不能误认为 9 步或丢掉最后一个区间。

`files[].bytes` 为正整数，上限每文件 256 GiB；只接收精确可表示的 JSON 整数。`sha256` 为 64 位小写十六进制。路径只能是安全相对路径，不能有绝对路径、盘符、URL、反斜杠、空段、`.`、`..` 或冒号；四条路径忽略 ASCII 大小写后也不能重名。

`trustedSha256` 不在清单内，由调用端传给解析器，必须取自实际发布记录或固定受信配置。导入清单携带的“我可信”字段不构成信任依据。此阶段研究导出的 manifest 和权重不是已完成商业授权的发行包。

## Adapter 二进制

Root 转换器定义的 LE F32 文件为 `adapter.fp32.bin`。头采用 `<8s7I6i`，60 字节：magic 为 `ZQADAP1\0`；7 个无符号整数依次是 `version=1,in_dim=6144,out_dim=4096,hidden_dim=4096,layer_count=6,drop_tokens=14,scalar_type=1`；6 个有符号整数为上述隐藏层下标。

张量按 `norm.weight,norm.bias,net.0.weight,net.0.bias,net.2.weight,net.2.bias` 排列，共 41,963,520 个 float、167,854,080 字节 payload；预期总长度为 167,854,140 字节。清单仍以实际导出的长度、SHA 为准。Kotlin 文件完整性校验不解释 tensor；原生 loader 必须再次验证头、布局、大小与模型维度，不用摘要校验代替格式校验。

## 现有准备代码

`data/local/qwen/QwenModelPackManifest.kt` 严格解析并固定能力、隐藏层和采样网格；返回的文件、隐藏层和 sigma 集合不可修改。它复用现有纯文本严格 JSON 解析器，没有调用 Agnes 网络代码。

`QwenModelPackVerifier.kt` 要求运行时锁与实际 Native 实现一致、root 为明确 canonical 私有目录。逐路径检查普通文件、拒绝符号链接、使用 `NOFOLLOW_LINKS` 打开文件；用一个 64 KiB 缓冲流式读取，同时核实际长度与 SHA，再检查验证期间大小、文件标识、修改时间和路径是否变化。调用方取消检查在每文件及每次分块读取前执行，原取消异常直接透传；没有文件落盘、下载、改写源模型或误报 ready 的兜底。

文件验证结果不是永久的“文件从此安全”令牌。未来安装器须把导入内容写进新暂存目录，验证完原子发布到应用私有、不可变的版本目录；Native 应从该目录加载，不能把验证后的路径重新指向 SAF 原文件或用户可改写目录。当前尚未实现安装器/原子发布，因此这套准备校验不能独立称为完整安全安装流程。

`QwenBackgroundContract.kt` 只接受背景请求，不接参考图 bytes，也不写入 OC。请求保存 chat、日期、visualVersion、referenceId、referenceSha 和可选 characterPackageSha；生成后必须在现有角色/外观提交锁内重新调用 `requireCurrent`，任何变化均不发布旧结果。它是提交前保护条件，未自动连接库存 Worker。

空间预算读取清单真实总字节，按调用端需要的 1–2 份暂存/发布副本及预留字节计算；用 Long 并拒绝负数、溢出。内存判断接受已测目标设备峰值、当前实际可用内存与预留量。尚无有效目标设备测量时返回不满足；12 GB 标称 RAM 或电脑显存数不能当作 Android 可用内存与成功证据。这里没有把权重磁盘字节等同峰值 RSS。

## 验证记录与下一关口

先写 27 项 JVM 契约测试。Root 的 `artifacts/qwen-android-pack-red.log` 在生产接口不存在时确实于 `compileDebugUnitTestKotlin` 失败，未执行 27 条行为断言。生产实现完成后，Root 统一运行 GREEN：实际 XML 共 27 条（12+9+6），0 失败/错误/跳过，日志 `artifacts/qwen-android-pack-green.log`。这仍是准备模块的 JVM 测试，没有产品 Native 入口。

测试覆盖受信清单摘要、架构/能力/重复字段、四 role、路径、真实大小与 SHA、冻结层/网格、取消、同大小换内容、验证时增长、目录冒充权重、运行时锁、不可变返回值、字节容量溢出，以及跨日期/用户/外观/人物/角色档案拒收。测试使用合成小文件，不是实际 DiT 或手机图片质量验证；当前没有符号链接实际创建测试或 Android NIO 实测。

后续关口按证据推进：实际学生六层与 adapter 数值对齐 → 完整 DiT 量化文件和 2.1 VAE 导出 → 外部条件采样链实际 PNG → arm64 Native 与实际手机资源/取消验证 → 独立本机背景审核及原子安装 → 库存 CAS 接线。没有通过实际采样链与设备关口前，不增加可点击却必然失败的产品入口、不提高版本、不把研究原型塞入新版 APK。
