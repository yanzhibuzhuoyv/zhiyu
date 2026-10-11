# Qwen Image Edge 原生移植与设备实测

2026-10-11。原模型位于 `C:/Users/32202/Desktop/99999/qwen-image-edge`，本轮只读。源码、转换产物、模型包和日志位于本项目；模型和 APK 不进入 Git。

## 当前结论与边界

已打通真正的原生采样链，电脑学生/L36 两条路线均生成了 512×512 PNG。红米 Turbo 3（12GB、Android 16）学生整图测试完成 8 步采样，最后解码期间 ADB 返回 255，未得到 PNG，**整图未通过**。没有独立原生退出凭据，不能认定为 OOM、崩溃或 USB 中断；采样日志、原始失败报告都保留。

本轮选择：**学生路线作为本机背景生成候选**。完整权重比 L36 少约 43.74%，手机文本编码明显更快、内存更低；单组同 seed 背景图都基本符合提示词。这个选择尚不等于已替换 App 的 Agnes，也不等于所有提示词/OC 人物质量相同。手机独立 full 与 tile256 VAE 均已生成完整 PNG，切块降低内存但更慢；下一项是保存真实采样 latent、定位完整链退出并加速采样，暂不为一个尚未通过的入口增加产品按钮。

手机原安装的是 0.2.2。本轮通过 `adb install -r` 升级到正式 0.2.3/code9 并启动，保留原有绑定、聊天和 Key。该 APK 修复照片发送、通话形象并取消建模入口；**尚不包含本轮 Qwen 模型、JNI 或产品生图入口**。红米新增自动化验收 17 条独立用例通过：照片流程 5 条、照片界面 2 条、通话形象 10 条，使用合成图片/隔离仓库/fake transport，不代表实际云模型照片回答也已测试。

用户最新允许按效果、时间和空间比较 L36、L24 与学生路线。L36/L24 是 Qwen3-VL 文本编码器的层数，不是 32 层图像采样主干的版本；L24 删除了 L36 的最后 12 层。0.6B 学生是另一套 28 层/1024 维模型，需经过训练好的 adapter，不能冒称 L24 或直接替换 L36 权重。尚未擅自将 L24 设置为默认。

## 已实现的原生链

1. 锁定 `llama.cpp@23b0202a189c44a54625aadcb37a946dd1d6278d`。学生模块不修改上游；CMake 拒绝不同提交或 dirty checkout。实现逐 token 六层捕获、UTF-8 stdin tokenizer、标准 C++ runtime API、加载/计算取消与 RAII。
2. 学生按 HF 隐状态 `[-1,-4,-7,-10,-13,-16]` 拼为 `[1,T,6144]`。28 层 HF 的绝对位置为 `[28,25,22,19,16,13]`，对应 final norm 与 `l_out-24/21/18/15/12`。不把句向量或只含最后 token 的输出当作六层特征。
3. 独立 adapter 保留 LayerNorm→6144×4096 Linear→精确 GELU→4096×4096 Linear。先执行 adapter，再去掉 14 个模板 token，最后 BF16 nearest-even 舍入并以 F32 容器传递。固定 60B 头、维度、张量顺序、实际大小与 finite 检查。
4. 锁定 `stable-diffusion.cpp@2988060a4338a5dcd94721c964a8e87003c1ac8b` 与 `ggml@d25b121ce1d6ae0c7e62ad080460d9fc1e46dd93`。小补丁接受自有外部条件，关闭原内置文本编码器，采用 2.1 的 RGBA/64 latent VAE，不替换为旧版 VAE。
5. 冻结 512×512、Euler/CFG1、8 个积分区间：`[1,.978453,.95418,.926626,.89508,.845148,.704534,.414568,0]`。9 个边界不能被误写成 9 步。
6. 图像 CLI 对生成条件、原生输出尺寸/通道与有限值做检查；PNG 采用 exclusive create，拒绝覆盖已有输出。依赖上游的版本和实际 fork header/补丁均在配置前校验。
7. Android 模型清单、流式 SHA 验证及背景提交 CAS 已实现为准备模块，通过 27 项 JVM 测试。尚未有安装器、SAF 私有不可变目录的原子发布、JNI、后台库存接线或独立离线背景审核。

两套上游带有不同版本 GGML；未来 JNI 必须隔离动态符号，不将两个静态实现不加区分地链接进同一 `.so`。目前独立 CLI 不存在此冲突，但不等于该产品关口已经完成。

## 实际模型字节

学生研究包 `artifacts/model-packs/qwen-edge/`：

| 文件 | 实际字节 |
|---|---:|
| `student-q4_K_M.gguf` | 396,704,544 |
| `adapter.fp32.bin` | 167,854,140 |
| `dit-q4_K.gguf` | 4,050,916,384 |
| `vae-bf16.safetensors` | 675,508,656 |
| 合计 | **5,290,983,724** |

DiT 实际 297 tensors、7,115,124,736 参数；229 个 Q4_K、68 个 BF16。转换 145.055 秒、峰值 RSS 1,421,008,896B。不能用理想 4bit 算术下界代替量化文件体积。

清单 SHA `9232afcbed6d188a9bdc58c0b8841b35bb12773aaee9fc745dc8027641c656e6`。Adapter SHA `202d50b933e7db976e981d42da2a326ba91d634639f58a1bc366d3309d9d845a`；DiT SHA `14be42ddc6e324682dd8e073ab32a0a4b3b8cd93657d6ec419dae3cc5f22ef78`。研究 receipt 记录导出时的状态，是溯源记录，不能作为自签的导入信任根或商业授权证明。

L36 新增实际导出：BF16 text-only GGUF **15,143,379,744B/398 tensors**，不携带视觉塔；Q4_K_M GGUF **4,677,718,816B**，36 层均保留。因此 L36 文生图组合约 **9,404,143,856B**，不需要学生 adapter。L24 本轮尚未量化或实机执行。L36 并未因此具备图片编辑/识图能力。

## 数值验证

学生三个公开合成提示词 41/48/228 tokens：HF tokenizer 与 native tokenizer ID 逐字节相同。F16 native 六层对 HF FP32 每层 mean cosine 最低 0.99999982；Q4 对 HF BF16 为约 0.9680–0.9929。量化不是无损。

Adapter 六个 F16/Q4 输入的 same-input PyTorch 对照：最低 token cosine 0.99999756、最大 relative L2 0.00023148。原 BF16 学生和 Q4 学生的整条输出差异不混入 adapter 精度结论。

红米真实学生 case1：load 0.343544s、forward 0.954492s、process peak 564,887,552B。真实 adapter 0.85942s；同手机输入对 PyTorch 最低 cosine **0.9999999805**、relative L2 **0.000035326**。手机学生与桌面同 Q4 的逐层 mean cosine 0.99846–0.99927，存在平台数值差异，不声称字节相同。

L36 原生直接捕获 `l_out-35`，即未做 final RMS norm 的最终隐状态；这与原项目 norm passthrough hook 一致。不能取 `result_norm`，也不调用 0.6B adapter。主合成提示词的桌面 forward 1.74608s，含加载完整包装器 7.86410s，Windows peak working set 8,243,310,592B。已有 A01 NF4 缓存对照 mean cosine 0.93535，精度/实现不同，不能将差异都归因于量化。

新增原始 BF16 L36 HF 参考已完成：CPU4GiB/GPU5GiB 加磁盘 offload，39.6059s 完整执行、forward10.2867s、family sampled peak5,320,790,016B。与主例逐字节相同的41token，输出27×4096。桌面 L36 Q4 对它 mean cosine **0.95027**、relative L2 **0.31801**；红米 L36 Q4 为 **0.95120/0.31609**，存在量化/实现数值差异，不能称无损。

手机 Q4 学生+adapter 对同一 BF16 L36 的 mean cosine 仅 **0.63524**；用原始 BF16 学生和原 adapter 在电脑复算也是 **0.64110**。这说明该例差距不能仅归因于手机量化。训练缓存使用 NF4 teacher，当前参考使用 BF16；现有单例还无法单独量化这些来源的贡献。Adapter 同输入 0.99999 的实现精度与“学生接近教师”是两件事，不能互相替代。编码余弦也不是图像质量分数。

红米 L36 的独立编码确实执行成功：原生 load3.11289s/forward7.91241s/total11.0253s，包装器11.7580s、采样到的 kernel HWM **4,868,460,544B**；独立远端 wait 退出码0。10项 teacher 契约亦在手机通过。此处只验证文本编码，不是 L36 手机整图。

## 电脑完整图片与失败记录

提示词：`A watercolor lakeside garden at sunrise, pale pink flowers, no people, no text.`，seed42，学生条件 27×4096，完整 CPU 推理。

| 运行 | 结果 | 原因/数据 |
|---|---|---|
| r1 | 未出图 | Windows MinGW 的空 processor 导致 GGML generic；运行 449 秒未完成首步，由 root 停止。 |
| r2 | 未出图 | 已启用 AVX2，完成 4 步；root 并行启动约 3.78GB AVD 造成可用 RAM 低于 512MiB，资源 guard 在 255 秒停止。属于测试调度错误，关闭 AVD 后重跑。 |
| r3 | **真实完整 PNG** | 503.755s；8 步采样 456.75s、VAE 46.43s；Windows peak working set **7,142,264,832B**。 |

r3 RGBA 512×512，711,480B，SHA `a2cccff19276c74faea7823cb2aee7ee40112c278fbe247f6481476a5c958be7`。真实查看为淡黄色水彩纸纹、湖边日出、粉色花和青绿草。只说明这一张背景基本符合提示词，不能泛化为真人/多翼/武器 OC 的身份一致性或多 seed 质量认证。

图片：`artifacts/qwen-native-runtime/q4-desktop-512-r3.png`；证据 `artifacts/qwen-native-image-smoke-q4-r3.json/.log`。CLI 未调用云服务。

## 同条件出图对比与选择

相同电脑 CPU4线程、同一 DiT/VAE、相同 prompt/seed42/27condition/Euler8。学生 r3 **503.755s**、peak7,142,264,832B；L36 Q4 r1 **501.422s**、peak7,127,121,920B。L36 图为 RGBA512²/616,151B，两图都实际打开查看：学生有显著日出与粉色花，L36 是清淡湖岸花草。两者构图不同；这不是人物一致性、多提示词/多seed的盲评验收，也不是“学生达到教师质量”的普遍结论。

L36 图 `artifacts/qwen-native-runtime/l36-q4-desktop-512-r1.png`；原始包装器报告的 scope 字符串沿用了 student，实际 command/condition SHA 指向 L36，路线以本段和对比记录为准，不修改历史报告。编码器变小没有显著缩短 DiT/VAE 主耗时，所以当前不继续为提速剪 L24；手机图像采样/解码与 GPU 加速才是关键。

用户要求将图放在 `C:/Users/32202/Desktop/pk` 并提供提示词评价。两张原图逐字节复制到 `pk/Qwen对比-20261011-01/A-seed42.png`（学生）和 `B-seed42.png`（L36），`提示词与参数.txt` 提供完整英文实入提示词、中文释义、seed/steps/CFG/sigma与路线映射。没有后期修改，不把诊断合成 latent 图混成真实 prompt 画质样本；后续新的实际生成图继续放在 pk。当前只暂选学生背景路线，用户评价尚未取得。

## 红米完整图片测试（未通过）

实际 ARM64/Android API26 静态 CLI，CPU 4 threads、mmap、flash attention；没有 NPU/Vulkan 加速。源码交叉编译后通过 readelf 核验 AArch64，动态依赖为系统 libc/libm/libdl。

测试目录 `/data/local/tmp/zhiyu_qwen_research_20261011_phone_r1/`；权重和 CLI 均为本轮自有临时文件。8步实际耗时2314.81s，完整包装器2619.946s后返回255，最后记录为 VAE 张量加载完成，没有 PNG。该例 sampled kernel HWM **5,279,854,592B**、swap378,208,256B；进程已不在，未发现可归因于该 PID 的崩溃/LMKD证据。采样曲线/未采样瞬间不能用来排除所有 OOM。

主失败报告 `artifacts/qwen-phone-image-r1.json`，其 exit_code 是 ADB client 返回值，没有独立 native wait 凭据。原始测试误读 cached battery32.2°C，已修源并附加 HAL guard：381条当前值记录，电池最高42.6°C、framework最高0、可用内存最低4,510,150,656B，无阈值触发。不能将主报告旧温度字段当真实温升，也不将后加 guard 当完整起始温度曲线。原始日志不改写。

资源保护条件：可用 RAM 低于384MiB、framework severe/更高或当前电池达到44°C，核对 `/proc/PID/cmdline` 精确匹配本轮 CLI 后停止；不杀其它 App。修复监控异常/KeyboardInterrupt 的自有进程清理与错误报告、将 ADB255 和 native exit 区分，首4条回归 RED→GREEN，另补3条离线/缺失回执/身份读取失败，共7条。`pidof` 必须收到成功 ADB 命令和完整远端退出标记；读取失败不能冒充任务已不存在。USB不可用时明确记录unconfirmed，独立手机 watchdog/整图取消仍未实现，不能称该风险已全部解决。原整图CPU没有实际分段执行，也未开启空间分块；cached graph cut plan 日志不能作为已分段证明。

桌面图像 wrapper 同样补异常清理与失败报告；独立审查发现调用清理 helper 漏传 `known` 参数，新增真实无子进程的中断测试和 autospec 后形成3 RED，再修至4 GREEN。全量首轮 Windows venv 启动器后代继承 log 句柄，临时目录清理失败；夹具改为直接启动基础解释器，匹配无子 native CLI，最终全量通过。该测试不证明任意进程树的创建/终止竞态都已解决。

传输后 L36/学生/adapter/DiT/VAE 均读取手机实际 SHA 与桌面清单核对；首轮 adapter 文件名错误的失败日志保留，第二轮对实际 `adapter.bin` 验证。研究权重仍在上述临时目录，App 尚不能从那里直接加载。

## 手机 VAE 独立定位

新增 research CLI 不载入 DiT/TE，固定 Qwen2.1/64 latent/16倍空间/RGBA；输入为32×32×64、幅度≤.01的确定性正弦，先按原模型 mean/std 还原，再解码512²。工具初版把图实际使用128张量当注册总数，在读元数据后0.012s报错，native receipt1；这是工具自身缺陷，不能解释此前整图失败。真实 source decoder+post_quant 和合成 builder 都是134，分开 catalog/register/validate 后修复，失败记录保留。

红米 full r2 **成功**：独立 native exit0、ADB0，原生解码196.118s、包装197.059s；进程实际 HWM与采样均 **3,041,890,304B**，CPU workspace1944.25MB、decoder params482.81MB。电池29.2→最高33.8°C、framework0、无guard触发。生成并完整解码RGBA512²/712,323B，SHA `b84487c0d15ac61528a5bde8cc3a3c50a04afff98141621118f21f77591b892b`，日志 `qwen-phone-vae-full-r2.log/.json`。此结果证明独立 ARM64 decoder 可执行，不是新的提示词出图、OC一致性或完整链已通过；之前整图255原因仍未确定。

运行目录 `/data/local/tmp/zhiyu_qwen_research_20261011_vae_r2`，probe二进制SHA `d5bf50ce85db64ae4ada62b3066fe23570a60c4ededa9b1aa3a5da7f01138a39`。设备独立保存log和native wait receipt，再由桌面按精确cmdline监督和拉回PNG。full与tile256使用相同合成latent、同分辨率、分开全新输出，不自动失败回退；后者用于比较空间块的内存和时间，不能承诺图像相同。

tile256 r2在第5/9块后 **USB确实中断**，包装281.182s返回ADB255、`device not found`，cleanup记录unconfirmed。采样HWM1,550,020,608B、workspace486.06MB、电池最高35.5°C、framework0。设备重连transport9后，独立查询本轮native PID已不存在、exit文件不存在、输出未完成。缺少native receipt，仍不能确定进程最终signal；本次明确的通信故障也不能倒推先前整图255必定同一原因。失败JSON保留，下一轮需独立于USB的设备守护和回执再重试。

tile256 r3 **成功**：研究目录 `/data/local/tmp/zhiyu_qwen_research_20261011_vae_r3`，native直接wait0与外层timeout0、无监控错误，完整512² RGBA/772,665B，SHA `1b3fc0c21f41168e5ce6bbea28139198f5ecf635f9231992f86c1ef3f1d57ebc`。9/9块解码 **488.325s**，native总488.419s、含轮询包装498.920s；HWM **1,549,578,240B**、workspace486.06MB、电池最高35.7°C、framework0。自有模型与guardian进程完成后已核不存在。原始证据 `qwen-phone-vae-tile256-r3.json/.log/.png/.launch.log`，失败r2不覆盖。

同一正弦输入对照：full196.118s/3,041,890,304B，tile488.325s/1,549,578,240B，切块HWM降低 **49.06%**、时间约 **2.49倍**。完整RGBA像素并不等价：四通道uint8平均绝对差分别1.53/1.77/2.88/8.41，最大差74，相同像素约1.41%。这是合成decoder诊断的数值差，不能当人物质量评分或证明真实prompt图无接缝。对照 `qwen-vae-full-tile-comparison-r1.json`；诊断图不放入pk画质样本。切块不是免费提速，也尚未选为产品默认。

研究VAE设备守护用`nohup/setsid`、独立worker wait与guardian receipt，温度/内存读数未知时拒绝继续，精确五字段匹配后仅对自有native正PID停止。三项短fixture先验过；独立审查发现启动时TERM/INT会被monitor初始化覆盖，新增真实RED后修复。最终脚本SHA `4913f5afcd0ae1b49495727debaf5a7faf68c3a688a321faf62ac60123dd4116` 在手机新目录再验正常0、错误17、timeout124/native143，并追加真实自有guardian TERM取消native143/guardian_signal/cleanupconfirmed。独立重解析四份回执与随后四目录实际脚本/fixture SHA均一致；证据 `qwen-device-guard-final-deployment-audit-r1.json`。旧C635部署的tile正常执行不作为修补后的取消证据。未实际拔USB或触发热阈值，未证明OEM不会杀guardian；此工具**仅覆盖独立VAE，尚未覆盖整图sampler或App**。

## 红米照片与通话回归

测试包首轮 `INSTALL_FAILED_USER_RESTRICTED`，用户允许后安装成功。r2/r4 UI 首项挂起；初始 activity 启动结果102，完整 OEM 原因未定。随后不带 MAIN/LAUNCHER 的手动启动被 ActivityScenario 判为 Intent 不匹配；用匹配的 `MAIN/LAUNCHER/0x10008000` 恢复前台后，全部12项UI完成。没有改权限、清数据或降低断言。

证据 `qwen-phone-023-photo-flow-r3.log`：5/5、3.544s；`qwen-phone-023-ui-r4.log`：12/12、580.766s（包含挂起/恢复，**不当作正常运行性能**）。覆盖真实像素、提交即显示/清草稿、回答失败保留照片、导入/生成/抠图在通话可用、形象变更与旧档案兼容、没有建模选项。两日志17个独立方法全部通过，不把未完成r2批次算通过。

回归后只卸载 `com.zhiyu.app.test`，成功启动主App，设备再次核验versionCode9/versionName0.2.3；未卸载或清除主App数据。

## 验证与审查口径

- Python 小测试最终 r7 **92/92**，3.058s，在原75项之外加17个设备VAE守护/回执合同，包含early-cancel真实RED→GREEN。覆盖原审计、学生比较、GGUF 审计、上游锁、温度源、teacher 层数/源目录与 worker 保护、手机/桌面/转换器异常清理。保留 r4 的夹具错误记录。转换器额外4个保护回归初轮4fail/2error，审查后清理调用断言1RED，再12GREEN（含8已有GGUF）；不重跑实际145秒量化或改写历史报告。
- Native 学生 feature 15 项、runtime 8 项；adapter 23 项、外部条件 19 项、teacher pre-norm 10 项、VAE-only 合同11项、合成builder1项，共87个小检查，CTest 是7个可执行目标，不把目标数当用例数。真实手机已执行 adapter23、condition19、teacher10、VAE合同11；合成builder仅host构造元数据，不加载权重。
- Android 模型清单/验证/容量与 CAS 27 项 JVM 通过；合成小文件，不代表安装真实 5GB/9GB 包或产品 Native 出图。
- 独立代理已审查 adapter、Android 清单/CAS、teacher 源目录/worker 生命周期、手机图像监控及VAE CLI、最终桌面清理签名、量化包装器。各指定范围未遗留C/H，已修输出 TOCTOU、加载取消延迟、worker 绕过保护、监控失败冒称远端已停止及进入monitor前中断遗留converter；真实双 writer race 仅一个发布成功。纯参数块重构后host/ARM64重编并小测试，不重新跑模型，不把原图计为新版本的整图验收。不声称尚未实现的 App JNI 已获完整认证。
- 设备runner另经独立终审，early-signal HIGH已闭；17个host小测试/bash-n、四份Android回执重解析通过，最终源码SHA未变。OC下一阶段设计的绑定保护、实际审批对象、时区/时段与unknown收费恢复四个P2建议已补入规范；不把规范审查当产品已经实现。
- `native/qwen-edge/**` 固定LF，防止Windows checkout改变锁定patch或header字节导致构建验证失败；patch SHA与现有prepared fork read-only验证再次通过。
- 不生成覆盖率结论。旧后台 9 tracked 修改及既有研究文件保留，不混入本轮提交；RAG、万轮与五千万测试本轮不重跑。

## 产品接线前的剩余关口

独立decoder分块对照已完成。剩余为手机完整链最终PNG与资源验收（保存真实latent以便解码重试）、采样加速 → 用户评价/更多提示与seed质量对照 → 完整链可取消/断连保护 → 私有模型安装与 JNI 符号隔离 → 本机背景审核或明确用户确认 → `LocalSceneStock` 的日期/人物版本 CAS 发布。

`LocalGeneratedBackgrounds` 当前生成后会通过 AgnesVisionClient 审核；只换生成函数仍会付费联网。因此本机生成须使用独立审核路径，不隐式回落付费 API。仅文生图不能替代 OC 参考图编辑、识图或既有抠图；已确认人物仍按固定主体合成维护。

原 Qwen Image 2.1/Turbo 的 Research License 商业发行权限未获确认。此次只进行用户提供权重的本地研究，不把权重随收费 APK 分发。原 Agnes 路径保持，等待本机效果和产品接线关口完成再选择默认。

用户新增的固定OC参考、非全身扩图/不用扩图、带动作全身处理、同日轨迹提示词及用户Key真实视觉审核的后续约束见 [每日图片流水线](OC_DAILY_IMAGE_PIPELINE_2026-10-11.md)。这是下一阶段设计，不把当前仅T2I背景能力说成参考图人物编辑已经完成。
