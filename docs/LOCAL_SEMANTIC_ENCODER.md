# 手机中文语义编码器与校准记录

日期：2026-10-09。以下模型质量与校准均使用人工构造的文本和公开模型，不读取私人对话或 API Key。手机检索、后台索引及最终包的证据应另以 root 的实际运行日志为准；本文不能代替整库测试。

## 已实现的端内编码

`BgeLocalSemanticEncoder` 使用 ONNX Runtime Android 1.30.0 在手机 CPU 上计算真正的 512 维向量，CLS 池化后做 L2 归一化。一个进程最多保留一个模型会话，CPU 两线程。模型、词表和配置随 APK 打包；启动或请求时没有权重下载、云 embedding、API Key 或聊天上传。

模型来自 [BAAI/bge-small-zh-v1.5 的固定版本](https://huggingface.co/BAAI/bge-small-zh-v1.5/tree/7999e1d3359715c523056ef9478215996d62a620)。官方模型卡给出 CLS、归一化及中文查询前缀的用法，本实现据此导出；本地 INT8 产物不是 BAAI 发布的官方量化版本。[官方用法](https://huggingface.co/BAAI/bge-small-zh-v1.5/blob/7999e1d3359715c523056ef9478215996d62a620/README.md)

文档无额外前缀；每个查询分段添加 `为这个句子生成表示以用于检索相关文章：`。`WordPieceTokenizer` 保持官方 BERT 配置的大小写与重音，返回原始字符串的 UTF16 偏移。增加的 CLS/SEP 和查询前缀不占原话的 source span；原话里的字面 `[CLS]` 等仍是原文，不能混淆二者。

长文不取前 512 token，不将整条原话平均成一个向量。每段同时最多 510 内容 token、8192 原始 UTF16 单元，加 CLS/SEP 后最多 512 token；原始消息完整保存，每个向量只指向其中一段原文。分词会正常清理 BERT 不编码的控制字符、分隔空白，原文仍保留这些字节。超长未知词按官方 WordPiece 成为 UNK；这不等于模型理解其中每个字符。检索证据不可据此宣称完整语义理解。

官方 `tokenize()` 仍将超长 ASCII 单词保留为一个带完整原偏移的 UNK；应用 `chunks()` 对跨度超过8192的 token，按不劈 UTF16 代理对的源窗口拆开，再分别真实分词/编码。跨巨大空白也会分成独立证据，不能用一段超宽 source span 随后只把前8192字符交给模型。tokenStart/endExclusive 表示应用源窗口重新分词后的连续 token 序列，不声称是一次性全文 WordPiece 的编号。17,000个ASCII前缀+末尾生日真实CPU测试：源片为 `[0,8192)` / `[8192,16384)` / `[16384,17010)`，余弦 .17019/.17019/.47534，生日尾片最高且全文尾部在证据中。产物 `source-window-tail-proof.json`；这不是手机执行结果。

长查询扣除前缀预算后独立分段，最多 8 段，调用方取各段与候选的最大相似度。超限会抛 `SemanticInputTooLong`，触发明确的补查原因，不悄悄只编码查询开头。输入上限为 2,097,152 个 UTF16 code units，分词与分段为惰性序列，不持有整条长文的全部 token/向量列表。

增量接口：

```kotlin
fun documentChunkCount(text: String): Int
suspend fun encodeDocument(text: String): FloatArray // >510 内容 token 显式失败
suspend fun encodeQuery(query: String): FloatArray // 超过单段预算显式失败
suspend fun encodeDocumentChunks(text: String, startChunk: Int = 0, maxChunks: Int = 8): List<SemanticChunk>
suspend fun encodeQueryChunks(query: String): List<SemanticChunk>
```

每批只编码 1～8 段，`SemanticChunk` 含全局 chunkIndex、tokenStart/endExclusive 和原话 textStart/endExclusive。缓存仅一个消息游标；取消、ORT 异常或其他失败都会先清掉游标再原样抛出。这样 iterator 已前移但本段尚未提交时，重试不会跳过原话。调用方仍要使用原话 SHA、revision 和 job.nextChunk 做数据库 CAS，不把编码器游标当已提交索引。

模型身份同时含原版本、INT8 SHA、词表/config/tokenizer config SHA、保留大小写/重音、CLS/L2、真实查询前缀 SHA、512 维和分段版本。当前分段后缀为 `chunk510-source8192-v2`；身份改变应单独重建可恢复索引，不能混算新旧向量。

## 固定产物、构建与许可

| 产物 | 字节 | SHA-256 |
| --- | ---: | --- |
| 原始 model.safetensors | 95,827,648 | `354763b9b1357bc9c44f62c6be2276321081ed2567773608c0d0785b61d5a026` |
| 本地 FP32 ONNX | 94,842,390 | `337c3703dc2038fac54fdc89906ccc796ce0033003adc70c48caa3a1f609d8a7` |
| APK INT8 ONNX | 24,006,596 | `a20c8811f4c52f72f31783c615b6a6ea2eaac6b8b52eea5a107e004f4cc632f7` |
| vocab.txt | 109,540 | `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c` |
| config.json | 776 | `3853a7979202c348751b753e36f579c41d8da7d36af617d3d907e1fc9b441f2a` |
| tokenizer_config.json | 367 | `e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a` |

以上字节数来自实际构建产物，SHA 是构建门。固定 manifest SHA 为 `60fc9061b2d9460a879707aede1fa3283241c3f425311bc462227c49c5d9d292`。本轮校准只追加独立报告，未重写 manifest 或改变固定 APK 模型。

`backend/prepare_semantic_model.py` 仅显式读取固定 public-model cache，SHA 校验后用本地 PyTorch 导出 opset 17 FP32，再用 ORT dynamic QInt8 per-channel 量化常量 B 的 MatMul 与 Gather。输出只能放在 ignored `artifacts`。ORT 官方建议对 transformer 使用动态量化，并验证量化后的准确性；量化并不保证无损。[官方量化文档](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html)

APK 需要 `assets/semantic/` 下的 int8.onnx、vocab.txt、config.json、tokenizer_config.json、manifest.json 五个文件，Gradle 的 prepare task 对固定 SHA 校验。导出环境为 torch 2.12.0、transformers 5.10.2、onnxruntime 1.30.0、onnx 1.23.2、numpy 2.5.3、ml_dtypes 0.5.4；新增的 onnx 与 ml_dtypes 只安装到项目 `.venv-route`，wheel 哈希在 `backend/requirements-semantic-export.lock`。运行 APK 不需要这些 Python 包。

额外固定原始 tokenizer.json SHA `48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26` 与 special_tokens_map.json SHA `b6d346be366a7d1d48332dbc9fdf3bf8960b5d879522b7799ddba59e76237ee3`，供桌面官方分词参考与 golden fixtures；手机使用独立纯 WordPiece，不打包这两个 JSON。

固定模型卡声明 MIT；APK 原作者与完整许可位于 `android/app/src/main/assets/semantic-model-NOTICE.txt` 和 `semantic-model-MIT.txt`。后者逐字保存 [FlagEmbedding 上游 MIT LICENSE](https://github.com/FlagOpen/FlagEmbedding/blob/master/LICENSE)，保留 Copyright (c) 2022 staoxiao。已有 ORT 完整 MIT 位于 `portrait-runtime-MIT.txt`，同一运行时共享，不能只保留 NOTICE 而漏许可证。

## 真实质量、保留集与校准

最初 6 例烟测：FP32 top1 5/6，INT8 top1 6/6；不能因这个小差异宣称量化提升通用准确率。PyTorch→FP32 最大绝对误差 2.086e-7；PyTorch→INT8 向量余弦最小 0.99234，均值 0.99441。1456 内容 token 的末尾事实分三段，原文尾部保留，真实相似度最高的是末段，但最高分约 0.327；分段不丢内容和当前检索阈值接受是两项不同验证。原始报告 `artifacts/semantic-models/quality-report.json`。

另外真实动态窗口3/17/512总token分别对原始PyTorch模型检查：FP32最大绝对误差均≤1.584e-7，INT8向量余弦为 .99185/.99384/.99625，有限单位向量检查通过。`window-quality-report.json` 保存证据；这仅验证导出窗口数值，与检索质量分开。

独立保留集 v1 的 8 条自然同义查询在 FP32/INT8 中 top1 都为 8/8，正确候选分数仅 0.419～0.507。冻结 0.60 服务阈值时，8/8 均被回退，不是服务语义命中 8/8。保留输入 SHA `69fb11cd084184f449a95b9e420735385fce31308bfe32ac5120b05a92a41b60`；报告 `heldout-report-v1.json`。发现该问题后，本保留集已被开发者看见，不能再作为新配置未见的泛化证明。

随后单独构造 calibration-v1：12 条有原文答案查询、6 条主题相关但属性未提及的查询、6 条无关查询。文档与表述不同于已见保留集；没有读取 root 的未知保留集。编码之前固定输入与选择规则，输入 SHA `7b9261f4a086463a95926917748c9fe0e838106c6d404afe8978f405396e2cc8`。所有查询使用同一真实中文前缀，原文无前缀；完整 24×12 原始余弦矩阵和 861 个 threshold/gap 组合在 `calibration-report-v1.json`，脚本为 `backend/semantic_calibration_benchmark.py`。

以下计数仅为这 24 条校准数据上的结果；“误接纳”指允许只使用语义结果停止补查，而原文没有所问答案，不能直接等同于最终模型编造答案。“误回退”指有答案却不被该门槛接受。

| threshold / gap | 有答案原始 top1 | 误接纳（12 无答案） | 误回退（12 有答案） |
| --- | ---: | ---: | ---: |
| .60 / 0 | 12/12 | 0/12 | 10/12 |
| .56 / 0 | 12/12 | 0/12 | 6/12 |
| .52 / 0 | 12/12 | 2/12 | 3/12 |
| .42 / 0 | 12/12 | 6/12 | 0/12 |

预先固定损失 `4×误接纳+误回退` 的校准最优为 .56/gap0，损失6；不是概率或经过泛化验证的安全线。top-gap 没有解决“相关但缺属性”：旧车票价未记载的查询 margin≈.224，高于正确问车票存放地点的 margin≈.217。语义向量擅长相关度，不能证明属性确实说过。

root 决定采用双门槛继续验证：≥.56 的相关结果可进入 semantic-only；.42～.56 保留独立原文候选，同时做精确/全文补查 semantic_then_lexical；低于 .42 走纯补查。这解决中等相关候选完全消失的问题。候选带作者、时间、消息 ID 与原文片段，提示词仍须要求只确认原文有的属性；“我有印象但细节想不起来，能再和我说说吗”可以自然表达，不能把命中主题编成生日日期等具体事实。

12 个校准文档无法代表百万片索引：库增长、8 段查询取 max 与多个相近主题，都会增加偶然高分机会。后续必须在未见保留集与真实完整大库中分别报告 gold ID 进入 top8、路由比例、误回退、错误事实率及延时。余弦分数不是概率；本文没有声称五千万字整库语义测试完成。

## 测试与性能证据

纯 JVM 首批18/18、当前实际21/21通过：官方分词golden的ID/UTF16偏移、补充汉字/emoji/控制字符/字面特殊token、长尾/惰性分段、向量归一化、失败与取消后的相同span重试、窗口边界；本次再加超宽UNK、巨大空白、不能劈代理对3项实际RED→GREEN。无Gradle，直接Kotlin编译器/JUnit；新RED `artifacts/semantic-source-window-red-test.log`（21项3失败）、GREEN `semantic-source-window-final-compile.log` / `semantic-source-window-final-test.log`（21项全部通过）。

`LocalSemanticEncoderInstrumentedTest` 11项真实Android用例现已在root的隔离模拟器r8实际通过：APK模型存在与身份、ORT有限单位向量/复算、自然同义排序、三片窗口原话尾部、末尾唯一事实、查询限制、多片查询、无效批界、取消重试、真实510内容token CPU基准、超宽UNK后生日尾片。独审逐个核对class/test的start/end状态和当前11个方法名，包含于 `artifacts/native-semantic-lifecycle-media-r8.log` 单次67/67通过。测试不打开任何聊天库或读取provider Key；本代理没有运行ADB/Gradle，仅复核root实际日志。

桌面真实 INT8 CPU，510 内容 token + CLS/SEP，10 次 warm 推理最小 32.88ms / 均值 33.80ms / 最大 35.02ms。手机用例输出 `semantic_cpu_benchmark` 的 first_call_ms 和 warm min/mean/max；首次调用可能复用进程 session，故不声称 isolated cold start。桌面速度不是手机或模拟器速度，外推所需时间也不是完成全库索引的证据。100,000 片至少含 100,000 次实际推理，还包括分词、数据库提交和前台让步，需要实测。

r8隔离x86_64模拟器真实510内容token/512总token CPU warm10次：最小36.50ms、均值42.84ms、最大48.95ms；first_call45.19ms可能复用会话。SUPPORTED_ABIS同时列出arm64-v8a不代表在红米手机或原生arm64 CPU测过。root未见保留集8/8进入top8（7个top1、1个top3），5次semantic-only/3次semantic_then_lexical，provider调用0；不因这8例改阈值。此为32文档小库检索证据，不是最终对话回答正确率或五千万字整库结果。

独审还解包r8 x86_64与arm64 APK，模型/词表/config/tokenizer config/manifest全部固定SHA匹配，3份完整模型/运行时许可与源码asset逐字节相同；记录在 `artifacts/native-encoder-media-final-evidence.json`。随后五千万字长测已按用户要求中止，不自动恢复。root报告停止时2764条消息ready、对应27,640,000原文字，已编码55,296/100,000分片、编码失败0；完整50M原文在编码前已完成重启校验，最终召回和云回答未执行，全量语义索引尚未完成，不能报告整库通过。冻结主库/WAL/SHM总607,465,472B（约579.3MiB），包含全量原文和部分向量，不能当作完整语义库空间或压缩后结果；本代理没有读取该库。

2026-10-09 定向整理 `prepare_semantic_model.py` 和三个 `semantic_*.py` 的 import/换行格式，两个 zip 增加显式 `strict=False` 保持原默认语义；不改变公开模型、固定输入、前缀或参数，也未重跑入口重写旧质量报告。Ruff 与 py_compile 通过；新 source SHA 与原输入/manifest 不变证据位于 `artifacts/vector-cache-compression/source-freeze.json`。派生缓存实验已整理为 [NATIVE_VECTOR_CACHE_RESEARCH.md](NATIVE_VECTOR_CACHE_RESEARCH.md)：实际 56 个公共合成向量、32 查询，float16 纯载荷减半且本批 top8/路由无变化，int8 有候选集合变化。Android 生产仍为 float32，原话完整无损；桌面样本不能保证手机性能或总空间直接减半。
