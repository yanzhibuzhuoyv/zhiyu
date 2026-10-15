# rea-reverse 实际通话与记忆源码核查

日期：2026-10-15。研究对象为 `C:/Users/32202/Desktop/99999/rea-reverse/Aiyu-Source/app/src/main/java`，对照知语当前 Android 工作树。只读研究，未运行第三方 APK、脚本、Frida、token 模块，未调用网络或读取用户 Key；未复制反编译实现进入生产。

## 结论

确有值得借鉴的**流式文本到句级语音流水线**；其通话录放并行、原生 ASR、AEC、取消旧回答等思路，知语本轮已有实现。AiYu 的记忆路径没有发现优于知语的向量索引：实际是日期/置顶字符串列表注入，以及遍历全列表做字符相似度筛选。不能把 REA 工具的反编译覆盖率当作 AiYu 性能、稳定性或检索正确率证据。

最明确的新差距：知语 `CallReplySource.kt:29` 仍等待 `send(chat,text)` 返回完整回答后才发出一次文本；AiYu 能边收 SSE 边按句交给 TTS。其收益是缩短首句等待，并不缩短 RAG 本身。建议自主实现规范化、有界、可取消的流式传输，沿用知语现有 epoch、审核和原文事务门；不要复制其无界队列或弱取消方式。

## 具体代码证据

以下路径前缀均为研究对象的 `app/src/main/java/`。反编译 Java 在协程控制流存在明显 `goto` 和不可达片段，关键流式事实另核对原 smali；这些是静态证据，没有宣称实际运行延迟。

| 主题 | 文件与方法/行 | 实际行为 | 采用建议 |
| --- | --- | --- | --- |
| 流式首句 | `com/shuoshuo/aiyu/l70.java:185`，`invokeSuspend` | 请求带 `stream=true`；后续构建 `/chat/completions`。 | 可以借鉴接口能力，不复制请求代码。 |
| SSE 到句级 TTS | `com/shuoshuo/aiyu/i70.java:113–135` 与 `:88–98`，`invokeSuspend` | 读取 `data: ` 行，取 `choices[0].delta.content`，累积文本并更新字幕；当前 delta 含句末标点时将缓冲整体交给 `h70`。smali `smali/smali/com/shuoshuo/aiyu/i70.smali:276–483` 独立确认读行、DONE、delta、标点与调度。 | 值得自主实现；按规范 SSE 事件解析，并精确切句，防止单个 delta 多句/半句被混在一起。 |
| 合成预取与有序播放 | `VoiceCallActivity.java:792–815` 的 `N()`、`:1117–1125` 的 `R()`；`m70.java`、`e70.java` | 合成可受信号量限制（默认 2）；将 deferred 放进 FIFO，播放按队首等待，完成再取下一段。 | 可以借鉴“合成与播放重叠”；知语已通过核心 text/audio 有界队列覆盖这一层。AiYu FIFO 本身无长度预算，不宜复制。 |
| 连听与端点 | `t70.java:68`、`:82–132` 的 `h()/k()`；`s70.java:80–169` | Sherpa 在线识别，16 kHz，VOICE_COMMUNICATION 输入，持续喂 PCM；输出 partial，端点 final 后新建识别 stream。端点规则含 1.2s/2.4s 静音及 20s 总长条件。 | 知语已有本地连续识别、40ms 采样帧、800ms 默认端点。参数不能直接当准确率或低延迟结论。 |
| 打断 | `VoiceCallActivity.java:80–134` 的 `A()`，`:1140–1146` 的 `T()`；`q60.java:29–41`、`n60.java:26–34` | partial 回调在 AI 讲话且文本长度大于 2 时立即停止/释放播放器、清队列、取消 AI job；final 回调经 `y()` 开新 AI job。 | 不采用固定长度大于 2 的判据；短“停/别/嗯”可能重要，回声或 ASR 修订也可能误判。知语 epoch、stamp 和独立资源所有权更可靠。 |
| AEC/降噪 | `t70.java:112–126`；`:137–155` 的 `l()` | 启用 AcousticEchoCanceler/NoiseSuppressor，但 stop 段只显式 stop/release AudioRecord，没看到对应 effect release；NS 返回对象未保存。 | 不搬用资源管理。知语已有 AEC 状态和关闭所有权；可另测可选降噪，但必须有设备实测支持，不能仅 available 就宣称消回声成功。 |
| 语音场景提示 | `VoiceCallActivity.java:360–366` 的 `E()`；`l70.java:186–189` | 追加“当前语音通话”系统语境；默认关闭模型 thinking 参数（可设置禁用此参数）。普通用户文本在 Java 此处出现两次 add。 | 通话可独立请求短句/自然口语，是否关闭思考应尊重用户/provider 选择；不要重复注入同一句用户话。Java 重复项若修 AiYu 本身还需 smali 确认，不作为知语缺陷。 |
| 通话历史折叠 | `VoiceCallActivity.java:169–228` 的 `C()` | 通话结束时把逐条消息改成一条通话文本附件，清掉内段列表。 | 不采用：不符合知语逐句保留、独立作者/时间/ID、不融合原文要求。展示层可以折叠，存储原文不能照此改写。 |
| 记忆上下文 | `VoiceCallActivity.java:310–345` 的 `E()` | 读取记忆列表；默认 7 天或选择全库，拼入系统文本；近期历史按用户条数取默认 20。 | 不是语义向量检索；时间过滤不能代替已存在的语义优先 RAG。 |
| 模型指令式查找 | `VoiceCallActivity.java:750–789` 的 `M()` | 模型输出 add/search 特殊标签；search 遍历 `vt.d()` 全列表，字符相似度过阈值后取至多 3 条，未见按得分排序。 | 不用此法替换知语 RAG，避免先回答再查记忆、无来源断言和长库线性扫描。 |
| 记忆底层 | `vt.java:24–31`、`:100–117`、`:181–183`；`j3/c.java:101` 后的 `K()` | 按 persona 的 SharedPreferences newline 字符串列表；写入重写整个列表；相似度函数展开字符编辑矩阵、匹配块和局部子串比对，没有 embedding/ANN 调用。 | 没有可迁入的检索加速。全量重写字符串和字符比较在大库上反而风险更高。 |
| 记忆管理 UI | `MemoryManageActivity.java:631–710` 的列表过滤，尤其 `:676/:690/:697/:705` | UI 搜索用字符串包含（忽略大小写）过滤，含置顶与暂存分组。 | 用户已要求记忆管理不面向用户，不迁入该 UI。 |

## 对照知语现有实现

- `data/duplex/AndroidDuplexPortsFactory.kt:34–61`：先 warm ASR/TTS，再打开通信路由与麦克风；取消交付时仍回滚全部资源。
- `data/duplex/AndroidDuplexCapture.kt:33–67`：持续连听、有界帧/健康状态/权限与路由检查；`:69–106` 显式释放 AEC 与 AudioRecord。
- `data/duplex/AndroidDuplexModels.kt:30–110`：分别持有 ASR/TTS 本地模型；TTS 通过既有语音分块与设置输出。
- `data/duplex/CallReplySource.kt:17–42`：最终文本才入现有聊天，严格递增 stamp，取消被收集 job，过期回复不能播放。目前它不是网络 SSE。
- `ui/DuplexVoiceController.kt:37–140`：后台会话、UI epoch、独占串行 start/stop、重复 stop 共用释放 job。

因此没有理由替换当前录放、取消或存储实现；实际需继续的技术点是**LLM 网络增量输出**和**真人环境的打断/回声验证**。

## 建议最小自主实现及验收

1. 先做兼容当前 provider 的规范 SSE 解码器，支持 CRLF/跨网络边界/注释与多 data 行/[DONE]/finish_reason。对总字节、事件、文本分别设预算；HTTP 错误或流提前断开明确失败，不把截断当成功。JSON 普通响应保留原路径。
2. 让通话网关能提供增量 Flow，但保留最终原文提交与取消门。进入语音的只能是可公开自然语言；思考流、工具/日历/图片协议、尚未审核的技术自述不能提前朗读。分块跨边界也要正确识别。不因 SSE 绕开当前完整回答 guard。
3. 使用既有 SentenceChunker 和有界 TTS/audio 流水线；不得新建每句无限协程。旧 stamp 即使网络迟到也不能播、写回新会话或取消新播放器。
4. 用延迟 MockWebServer 对比完整回复与 SSE：首段 TTS 必须在服务端最终 DONE 之前开始；同时看首音等待、总时长、最大缓冲，覆盖打断、半句断流、emoji、长无标点与一次 delta 多句。这个对照能证明改善首句等待，不能证明真实供应商的手机延迟。
5. 如当前完整答案审核无法安全增量化，先保留完整答案路径；将“规范 SSE 支持”和“可提前播报的经过审核语音”分开验收，不为了借鉴而削弱原来的回答约束。

本报告没有做性能实验，也没有生产改动；不能将上述建议写成已接入/已测成功。
