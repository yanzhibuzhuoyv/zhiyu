# 学生版选择与采样／解码拆分验证

2026-10-12，Asia/Shanghai。用户评价 `pk/Qwen对比-20261011-01` 的真实学生／L36背景原图后明确选择学生版。后续按学生路线迁移，L36保留对照；这不代表人物参考编辑、扩图或任意动作已验收。

## 实现与真实验证

新研究CLI在真实八步采样之后、VAE之前保存 diffusion-normalized latent；`--sample-only` 保存后退出，另一个进程仅加载VAE并执行原始latent转换一次。可单独重试解码，不必重跑昂贵采样。格式与合同见 [LATENT_CHECKPOINT.md](../native/qwen-edge/diffusion/LATENT_CHECKPOINT.md)，阶段守护见 [DEVICE_IMAGE_STAGES.md](../native/qwen-edge/diffusion/DEVICE_IMAGE_STAGES.md)。upstream不变，fork revision2／patch SHA `9bf82bfd37dd1ffd5df28fd32e0a3948ae10ab8f086c886496c01203f300cc95`；新checkout是 `artifacts/qwen-native-runtime/sd-checkpoint-src`，旧fork与失败记录保留。

| 真实验证 | 结果 |
| --- | --- |
| 桌面原流程附加checkpoint | native0，538.380s，完整PNG／检查点均通过，峰值工作集7142457344B |
| 桌面独立检查点解码 | native0，包装38.261s，PNG与原流程／学生A图逐字节一致 |
| 红米独立真实检查点解码 | native directwait0／timeout0，decode201.752s、包装206.501s，完整512×512 RGBA |
| 红米守护合成短夹具 | 正常0、错误17、超时124／native143、正PID取消native143均符合预期；不是模型／图像验证 |

桌面使用27token条件（SHA `474bde4c28a8c6304f5cb965565bd43945fd29bde0653f75c54c932416ab7173`）、seed42、CPU4、Euler8/CFG1/512²。提示词：

```text
A watercolor lakeside garden at sunrise, pale pink flowers, no people, no text.
```

真实检查点262288B，SHA `1a6447313486de6afa7641e5efcbbc102d77c639a759a4058cc15c992a43c410`；同一严格reader验证CRC32、finite、阶段／形状／八步元数据。桌面PNG711480B，SHA `a2cccff19276c74faea7823cb2aee7ee40112c278fbe247f6481476a5c958be7`。红米此项读取**桌面已采样**的检查点，不执行手机DiT；PNG711638B，SHA `21506e83ac651bb481b94f253c270c3b71cde422e219d1342af66290f2b18269`，与桌面不同，不能声称跨架构像素等价。观测HWM2925891584B／电池最高34.0°C／thermal0／最小可用3787038720B，检查点SHA不变。

证据分别为 `artifacts/qwen-checkpoint-desktop-r1/run.json`、`qwen-desktop-latent-replay-r1/run.json`、`qwen-phone-checkpoint-decode-r1/run.json`、`qwen-stage-fixtures-r1/{success,failure,timeout,cancel}.json`。guard只证明执行状态，始终保留 `latent_verified=false/png_verified=false`，外层另验完整检查点／图像。

## 回归与审查

全量Qwen Python132/132通过（7.272s，`artifacts/qwen-runtime-python-20261012-r1-tests.log`）；host新fork四项CTest通过（condition19／VAE11／checkpoint18／无模型metadata1），prepare `--verify-only` 通过。独立审查发现host log/report可写进原模型树的P1，已在mkdir／spawn之前统一保护四类目的，取得4case RED后修复，8项checkpoint host回归GREEN。终审无剩余CRITICAL/HIGH限指定研究入口，`artifacts/qwen-checkpoint-stage-review-20261012-r1.md`。未声明80%覆盖率，尚未实测拔USB或热阈值触发。

新增真手机检查点合同18/18通过，`artifacts/qwen-phone-checkpoint-contract-20261012-r1.log`。另四种实际Android符号链接（common、native、条件输入、检查点输出）均在创建run.lock／native.identity／native.log前拒绝退出73，受保护检查点SHA未变；`artifacts/qwen-stage-real-filetypes-20261012-r1.json`。这四项补充是真文件类型，不能把Windows stub测试或小合同扩大为完整热停止／USB断线恢复验收。

## 手机完整拆分链（进行中）

2026-10-12用户重连红米，实际serial6659de32／transport1为device。核验原DiT／VAE／手机条件SHA后，新job进行CPU八步 `--sample-only`，保存后再启动独立VAE进程；尚未完成。脚本 `artifacts/qwen-phone-split-image-20261012-r1.py`，输出目录同名去掉`.py`。sample/decode手机目录分别 `/data/local/tmp/zhiyu_qwen_research_20261012_sample_r1` 和 `..._decode_r1`；sampling budget3900s／decode600s，44°C／thermal≥3／低内存／未知读数停止，launch与部署已即时落盘。手机条件SHA `70f165dc5aaf17b5396f354cdbda087f6f5a2f2a56309f58749fdfbcf3dcdc2c`，不能混称与桌面同字节输入。旧完整手机r1的ADB255根因仍未知。

## 产品边界

学生编码更轻，但DiT仍主导耗时与体积。GPU见 [QWEN_VULKAN_RESEARCH_2026-10-12.md](QWEN_VULKAN_RESEARCH_2026-10-12.md)：attention小图通过，Q4_K三种配置都创建管线失败，暂不用于完整GPU生图、不声称提速。正式APK仍0.2.3/code9；模型私有安装、JNI隔离、产品审核库存接线未完成，Agnes尚未替换。OC固定参考／动作全身／扩图偏好／每日轨迹同版本提示词与用户Key真实审图遵循 [OC_DAILY_IMAGE_PIPELINE_2026-10-11.md](OC_DAILY_IMAGE_PIPELINE_2026-10-11.md)，不能只靠提示词把T2I当参考编辑。本轮无Key／私人库／付费API／万轮及五千万重跑。
