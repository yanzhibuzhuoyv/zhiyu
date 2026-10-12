# 学生版 Qwen：下一轮 APK 背景生成迁移

2026-10-12，Asia/Shanghai。用户已选择学生版，L36只留作对照。本文是按现有源码制定的实施顺序，未实现安装器、JNI或新版APK。正式包仍为0.2.3/code9；root已完成手机冻结学生条件→八步采样→检查点→独立VAE真实验证，完整512²PNG、两阶段native wait0，总包装32.11分钟，见 [拆分验证记录](QWEN_EDGE_CHECKPOINT_VALIDATION_2026-10-12.md)。此项不含同次新prompt编码或正式APK执行。

## 最小边界与现有入口

本轮只替换**当天库存的无人背景生成**，保留确认的OC主体与既有合成、图片发送。Qwen现有能力为`text_to_image_background`，不支持把参考人物重绘为新动作，也没有扩图、识图能力。人物生成入口`LocalVisualRepository.generate`仍是Agnes调用，不能因背景迁移就宣布Agnes全部替换；其两次机会和收费结果账本须保留。任意动作、扩图与最终人物审图按 [OC流水线约束](OC_DAILY_IMAGE_PIPELINE_2026-10-11.md) 分阶段完成，不增加已取消的2D/3D入口。

下表路径均相对于`android/app/src/main/java/com/zhiyu/app/`；新增类名为建议，尚不存在。

| 现有代码依据 | 下一轮具体改动 / 可复用边界 |
| --- | --- |
| `data/LocalSceneStock.kt:16,31`直接构造`LocalGeneratedBackgrounds`并调用`get` | 抽出小型`SceneBackgroundSource`接口；生产注入新增`data/local/qwen/LocalQwenBackgrounds`。云旧实现保持历史可读，不再作为本机失败的隐式回退。 |
| `data/LocalGeneratedBackgrounds.kt:17,18,32`先读Agnes Key、拼固定prompt、调用Agnes | 本机源不复用其收费生成调用；缓存、尺寸归一化逻辑可单独抽取。模型未安装、取消、资源不足返回明确状态；Canvas保留独立来源标记，不能记为Qwen成功。 |
| `data/LocalSceneStock.kt:40,45,51`角色渲染 / 原图合成 / profile提交锁 | 复用`CharacterSceneRenderer`、已确认抠图和`withCurrentProfile`，以原图/主体SHA与packageSha保护身份。缩放与透明度合成不是人物重绘，也不承诺输出与原图逐像素相等。 |
| `data/LocalSceneStockWorker.kt:12,27,38`普通Worker循环整个日库存 | Worker只负责队列、清理与短状态更新；重推理交给单任务持久协调器及受控服务，不把每次聊天回复串在生图后面。 |
| `data/LocalDailyWorker.kt:60,71,77`首次/换日修复及前台监听 | 保留当天创建、零点尝试与前台修复；扩充轨迹revision与slot任务快照，日期变化先失效旧任务，再排当天队列。 |
| `data/LocalReplyMedia.kt:36,57,70`时段过滤 / 外观锁 / 新鲜度过滤 | 继续发送前核日期、notBefore、外观，补齐轨迹revision、参考SHA、取景偏好版本与审核收据；旧任务不能靠既有slot tag绕过。 |
| `data/local/qwen/QwenModelPackManifest.kt` / `QwenModelPackVerifier.kt` / `QwenBackgroundContract.kt` | 复用严格清单、64KiB流式SHA、取消、路径、真实容量、提交快照校验；这些目前都未连接产品安装或库存。 |

## 模型安装：先做真的，再开放现有设置入口

新增`QwenModelPackInstaller`与安装状态存储；通过SAF读取四个文件和受信清单，复制进`filesDir/qwen_models/.staging/<operationId>`，输入始终只读。四文件实测总计**5,290,983,724字节**：student、adapter、DiT、VAE；不把它们塞进现有188MB APK，也不通过`readBytes()`同时装进RAM。

1. 信任锚来自内置发行记录，不能采用导入清单自报的可信SHA。准备代码的`QwenRuntimeLock`只锁两个upstream commit和`bridgeVersion=1`；新JNI必须额外绑定实际fork revision2、patch SHA与各GGML构建身份，不能拿相同upstream冒充已加载新桥。更改契约发布新受信manifest，保留旧研究清单原字节。
2. 复制时每64KiB检查取消、累计精确字节与SHA，拒绝重复role、路径穿越、长度增长、空读无进展、链接/目录冒充文件。复制完成后调用现有Verifier复核，再让native校验GGUF/adapter/VAE格式。
3. 暂存与版本目录位于同一私有文件系统；全部文件flush/fsync、落盘清单和完成标志后，原子rename为`packs/<manifestSha>`，最后原子更新active指针。任何失败或重启留下的staging均非ready；只清理本安装operation所属的残留，旧已激活版本仍可用。
4. 首装只需要一个私有副本加余量；若实现选择再复制一次，应按两份真实字节预算。升级空间应另外计入保留的旧包，不能只调用`copies=2`后忘记旧版本。逐块重新核可用空间；ENOSPC、取消、SAF授权失效不能破坏旧active。
5. active包应用内只读且不可原地覆盖；每个运行任务持有版本lease，升级/删除不能移走使用中的文件。不要再次指向SAF路径或外部用户可改文件。模型目录排除聊天媒体清理与备份。

验收安装器与native之后才在现有外观/素材设置中提供模型导入状态；未就绪时不展示能点击却必然失败的生成按钮。不需要新增服务器地址或重新登录、更改绑定。

## JNI和进程：保持已验证拆分，不合并两个GGML

现有`android/app/build.gradle.kts:14,144,148`打包三ABI、Sherpa静态ONNX AAR与ONNX Runtime；没有Qwen `externalNativeBuild`。现有native目录目前构建独立CLI，不是可直接`System.loadLibrary`的JNI实现。

建议最小安全组合是两个**不导出、同应用UID**的独立服务进程：`:qwen_text`只加载student/adapter桥，`:qwen_image`只加载diffusion桥。前者输出完整条件并退出释放；后者采样写检查点后退出，重新启动同类进程只加载VAE。不要用`isolatedProcess=true`直接读私有模型；若以后采用隔离UID，需要另设计只读FD授权。

- 新增`QwenTextService`、`QwenImageService`、`QwenNativeCoordinator`；Binder传受控taskId/包版本和短参数或FD，不传几十MB tensor/5GB权重。只接当前应用授权的调用；服务自己从包注册表解析路径，不让外部任意argv/路径直接进入JNI。
- 文本桥复用`native/qwen-edge/student/student_runtime.hpp`的tokenizer/六层提取以及`adapter/adapter.cpp`；**同一提示词经真实tokenizer**，保留14个前导位置直到adapter统一裁一次，最终1..498token、每token4096有限F32。不要用桌面冻结conditions替代产品prompt链。
- 图像桥复用`diffusion/smoke.cpp`采样和`vae_probe.cpp`严格reader/独立解码；冻结512²、Euler8/CFG1/CPU4/sigmas。先sample-only持久检查点，再释放全部DiT状态，确认进程退出后启动VAE；不要同时保留DiT、VAE、student的高峰内存。
- 两个GGML版本不进入同一DSO/进程的全局符号空间。各JNI DSO隐藏非JNI符号、使用唯一库名与SONAME，静态依赖不导出`ggml_*`；不打包两个同名`libggml.so`。用`llvm-readelf/nm`核查导出和依赖，并测试语音/ONNX与Qwen交替运行。单DSO强行链接全部库不是最小改动。
- 首版Qwen仅声明已验收arm64；保留其他ABI已有聊天/语音，不在armeabi-v7a/x86_64伪称生成可用。NDK/JNI编译启用PIC、固定原生源码身份，检查target36的ELF/ZIP页对齐；Gradle不能借用机器上的研究CLI文件作为产品可执行入口。
- `LATENT_CHECKPOINT.md`的262288B只说明格式；任务记录还须绑定prompt、conditions、modelPack、seed、stage与实际采样完成回执的SHA。CRC有效的合成文件或旧任务checkpoint不能用于恢复当前库存。

## 资源、调度与恢复

新增`QwenSceneJobStore`，区别于`LocalImageJobStore`的收费unknown语义。持久状态至少包括`queued/encoding/sampling/latent_ready/decoding/candidate_ready/reviewing/approved/rejected/cancelled/stale/resource_paused`；完成stage用原子状态CAS，不从“有文件”猜成功。

只允许一项重推理，先做一个当天slot验收，再按队列逐项扩展。手机研究CPU采样耗时已达几十分钟量级，不能把五个slot并发启动、每次foreground repair重新采样或承诺零点即时全部出图。换日后建立任务、停发旧图；图像晚完成保持待用/过期处理，不改聊天自然回复。

普通Worker不足以承载长时间采样：需实现前台执行与可取消通知并选择与用途相符的服务类型。target34+长Worker须声明类型；Android16长Worker还受JobScheduler配额约束。首轮先在用户可见前台验证，再落实每日后台调度；不能为绕过限制宣称无限后台运行。[Android长Worker官方说明](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

纯本机模型计算不能随意冒用location/microphone/dataSync类型。可评估直接前台服务`specialUse`并说明本机ML用途；需相应permission、subtype与发布评审，尚未实机验收。调度仅创建待办，合法启动条件不满足时等待前台恢复。[Android服务类型官方说明](https://developer.android.com/develop/background-work/services/fgs/service-types)

研究shell的`dumpsys`/外部正PID守护不能复制进普通APK。产品改用BatteryManager广播温度、PowerManager thermal状态（API29+）、ActivityManager.MemoryInfo和StatFs；API26-28没有已验证thermal通道时明确不满足能力门禁。当前研究44°C/thermal≥3/低内存及预算是移植起点，必须在App UID下重新测量，不沿用ADB壳权限当产品证据。

取消由协程传播至JNI atomic cancel和当前stage；等待服务确认释放/退出后再启动下一任务。超时、binder death、系统杀进程分别持久记录；如需终止失联计算，仅能处理当前协调器创建且身份核实的服务进程，不能杀App主进程或按进程名扫杀。解码失败可复用**严格验证且仍当前**的latent；已取消/已过期任务不能被复用为当前成功。预算覆盖一次slot任务各阶段，不靠换taskId重置采样/重绘次数。

## 用户Key的prompt与真正审图：复用传输，不复用错误凭据

现状有两个缺口：`LocalGeneratedBackgrounds.kt:18`是硬编码prompt，`BackgroundContentGate`经AgnesVisionClient审图；`LocalDailyRepository`保存的是本机确定性日轨迹，不是用户Key生成的绘图计划。不能只替换`client.generate`就声称完全本机/已按用户Key审图。

新增`DailyImagePromptClient`复用`DirectProviderClient.complete`和已绑定用户Key，以当天slot、人格、镜头限制生成严格结构化prompt；JSON仅为内部数据，长度与token上限校验，不修改绑定、原图或轨迹身份。费用操作持久reserve，响应丢失标unknown且不自动重发；本机失败不调用Agnes付费生成。

新增`DailyImageReviewClient`复用`DirectChatMessage.photos`与`ChatPhotoImageProcessor`的真实图片上传。当前`DirectProviderClient.kt:54..59`每条最多一图、总5MiB、模型能力白名单；多图通过多条user消息，不能复用`LocalPhotoPromptHistory`“只取最近一张”作为参考图+候选审核。绑定模型不识图时保持待审核，或使用明确配置/验证过的辅助视觉路由；不改变恋人唯一绑定，不以OCR/文件名代替看图。

可复用`BackgroundApprovalStore`的同图并发claim、字节摘要、unknown不重发模式，但不能直接复用其现有“只绑定背景SHA+schema”的收据批准最终人物合成。新收据绑定候选实际字节、参考实际字节、prompt、轨迹revision、取景偏好、档案与审核路由版本。无人背景门禁与含人物最终图审核分开；approved/rejected/uncertain/unavailable四态，只有approved可发布。

初生成+一次持久化修订重绘，第二次仍不符则不发送；修订计数绑定谱系，重启/改taskId不能清零。将`QwenSceneSnapshot`扩充revision/slot/notBefore/framingPolicyVersion/modelPack/prompt/review身份，在`LocalSceneStock`现有短提交锁内做最终CAS，在`LocalReplyMedia.commit`发送前再次验SHA与时效。不扩图的最大取景限制需要新增真实数据与生成/库存/发送检查，现有VisualProfile没有该契约，不把设计当完成。

## 一轮可执行验收顺序

1. root真手机冻结条件→八步→检查点→独立VAE已完成，真实退出／PNG／峰值／温度已记录。接下来从安装器继续，不重复既有冻结prompt实验。本规划未执行ADB／模型／API。
2. 先TDD实现安装器与新任务契约：合成小包验证取消、低存储、错SHA、源变化、链接、重启、原子active、版本lease；迁移旧清单拒绝错误fork。再用四个真实文件实机导入，主聊天/Key/绑定不清除。
3. 编译JNI/服务但仅测试入口触发；核导出/依赖/ABI/页对齐。在真App UID执行一个冻结prompt，验证token→adapter→CPU8→split VAE全部是本次产物；取消/服务死亡/阶段恢复实测，语音与RAG交替运行不崩溃。GPU现有Q4_K管线失败，首版不启用。
4. 向现有库存接口注入本机源，用合成transport先验证收费claim、unknown不重发、一次修订与CAS拒收；然后在授权范围用真实用户Key生成prompt、送实际背景+参考/最终图审查。故意错场景、增加人物、超取景、翼/武器改变的样本不能伪造approved。
5. 固定已确认OC，测试多个scene/seed，人物主体不进入Qwen重绘；当天首次创建、跨零点、未来slot、外观/轨迹/偏好变更、审核中换图、断网、进程结束恢复均拒绝旧图。新人物动作/扩图未支持时明确受限，不加虚假入口。
6. 批准的真实图片与实际prompt放`C:/Users/32202/Desktop/pk`供用户评价；review无CRITICAL/HIGH后打新APK，核签名一致、install-r保数据、断USB无电脑可导入/产图/发送。测试完成才更新版本/安装和替换完成记录。

本规划只读取源代码和Android官方资料、写本文档；没有改生产代码、读取Key/私库、执行模型/API/ADB或修改安装包。
