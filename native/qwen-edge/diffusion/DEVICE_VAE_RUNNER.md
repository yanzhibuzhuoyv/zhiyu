# Research VAE 设备端守护（2026-10-11）

仅用于 `qwen-edge-vae-probe` 独立 decoder 调查。没有接入 APK、JNI、主采样 smoke 或通用后台服务。此前 tile256 r2 的 USB 断开、无 native 回执保留为未知；本工具不改旧报告。

## 部署合同

由协调者在手机创建一个全新 `/data/local/tmp/zhiyu_qwen_research_[A-Za-z0-9_]+` 目录，每目录只启动一次。在目录中放：

- 已编译 ARM64 probe，固定名 `image_smoke`（测试时可用明确非模型 fixture 替换）。
- `device_vae_runner.sh`，固定名 `vae_guardian.sh`，保持 LF。
- 本地共享版还需同目录固定名 `device_guard_common.sh`；wrapper加载时校验它的固定SHA。原VAE接口与17项host行为保持，旧远端单文件不回写。当前源码/新增部署合同见 [两阶段守护](DEVICE_IMAGE_STAGES.md)；下面四项历史实机证据仍归属旧单文件SHA。
- VAE 固定名 `vae.safetensors`，也可引用另一个满足上述 owned 模式的物理目录。

`scripts/qwen_device_vae_runner.py` 不运行 ADB；调用 `launch_command(directory, vae_path, mode, pattern, budget)` 获取 shell 命令，再由协调者发送一次。mode 为 `full` / `tile256`；pattern 为 `zero` / `sine`；budget 为整数 1..900 秒。正常研究 VAE 900 秒，短 fixture 可 2 秒。

外层 `nohup setsid -w /system/bin/sh ... supervise ...` 脱离终端，stdin 为 `/dev/null`。`launch.log` 使用 noclobber；启动命令返回的 PID 仅代表启动请求，**不能证明子任务已经启动或成功**。supervise 创建独占 `run.lock`，拒绝已有 output/log/identity/receipt。目录、输入及可执行文件验证物理路径，不接受符号链接或 traversal。没有删除/覆盖旧文件的操作。

## 守护与回执

设备 guardian 在单独 session 中等待 `setsid -w timeout -k 5 BUDGET ... worker ...`。worker **先核实实际 PGID=自身 PID，而且 PGID/session 与 guardian 不同**，再检查当前 HAL 温度和内存，最后启动 native。native 五字段 PID、Linux starttime、PGID、session、argv0 被持久化；还要求 native group/session 继承已核实的 worker。这不依赖猜测 Android Toybox 的实际进程拓扑。

guardian 约每秒检查一次，`dumpsys thermalservice` 自身另设 5 秒有界 timeout。仅解析 `Current temperatures from HAL` 的 battery/type2，忽略缓存温度；framework thermal status ≥3、当前 battery ≥44°C、MemAvailable <393216kB 任一触发停止。读数缺失/非法视为 unknown，禁止继续假称安全。耗时的 dumpsys 会使间隔延长，不能宣称每秒硬实时。

guardian 只对**所有五字段仍精确匹配**的正 native PID 发 TERM，最多等 5 秒，再重新核验后 KILL；不发负 PID/进程组信号。身份不可读、变化或停止无法确认时报告 `unconfirmed`。设备 timeout 的组操作仅限启动前已隔离的 worker/native；guardian 回执 writer 在另一 session 中。此方案不证明 OEM 不会杀 guardian；独立回执缺失仍必须报告未知。

文件：`launch.log`、`native.log`、`guardian.identity`、`native.identity`、`native.receipt`、`guardian.receipt`、`output.png`，停止时另有独占 `stop.request`。guardian 先持久化停止请求再等最多2秒读取 native 身份；worker 在启动 native 前拒绝已有请求，防止读数异常时尚未启动的任务随后运行。native worker direct wait 写 native receipt；TERM 中断 wait 后仅明确二次 wait 返回才确认为 native wait 码，否则 `wait_confirmed=0`。timeout wrapper 的 124/137 **单独存储**，不能冒称 native exit/signal。

读取两份完整 receipt 后调用 `parse_run(guardian_text, native_text_or_None)`；重复字段、截断、不匹配目录、非法值拒绝。结果的 `native_exit_code` 是 direct shell wait 规范码，不作额外 signal 解释。`execution_success` 仅表示两份执行回执一致成功；`png_verified` 始终 false，必须另行检查真实 PNG 解码、512×512 RGBA与 CLI success marker。三短 fixture 的非 PNG 输出不能当出图。

## 当前验证及仍需实机确认

本代理未运行 ADB、真模型、手机 shell、模拟器或大推理。真实 TDD 起点缺模块 import RED，随后 HAL 数值字符串比较回归失败后显式数值转换修复；先14个 host合同 GREEN（2.544秒），补 stop.request 顺序/拒绝启动2个回归后16个GREEN。独立审查发现 monitor 初始化会重置已捕获 early TERM/INT；再加1个回归真实RED，然后把初始化移到trap之前修复，最终**17/17 GREEN（2.763秒）**，`bash -n` exit0。证据位于 `artifacts/qwen-device-vae-runner-red-r1.log`、`qwen-device-vae-runner-green-r1.log`（中间失败）、`qwen-device-vae-runner-signal-race-red-r2.log`、`qwen-device-vae-runner-green-r5.log`。这些 host 测试不验证 Android setsid/timeout/watchdog。

协调者先在三个不同 owned 新目录执行明确非模型 fixture：full/zero 1秒 exit0；full/sine 1秒 exit17；tile256/zero 30秒但 budget2。先核回执身份、native0/17、timeout124或137与独立 native wait/未知分开、剩余 own PID 消失，再决定一次真实 tile256。USB 断开恢复与温度触发验收应独立记录，未实测前不宣称通过。

root已完成初版三项**实际 Android 合成生命周期测试**：success directwait0 / timeout0；failure directwait17 / timeout17；budget2超时 guardian124 + native directwait143（term_interrupted1、wait_confirmed1）。独立读取 `artifacts/qwen-device-guard-{success,failure,timeout}-r1.json` 证实当前HAL29.3°C / thermal0，三份 `png_verified=false`。证明该设备的短启动、分离session/group与执行回执，不是拔USB、真实PNG或温度触发验收。后续 stop.request 补丁另需协调者在新目录确认。

root 的实际 tile r3 部署 SHA256 `c63531f0006da8a136fb5dfec27654e5f85c93d2bdd3a2f6fb8537a64486bbd4`（含stop.request，早期取消修复之前）；没有覆盖远端文件，也不把它的正常执行证据移植给新版本的取消路径。

最终 early-cancel 修复版 shell SHA256 `4913f5afcd0ae1b49495727debaf5a7faf68c3a688a321faf62ac60123dd4116` 已由 root 在新目录完成三个短fixture r3（native0/17、timeout124/native143），另新增真实自有guardian正PID TERM取消 r1（native143、guardian_signal、cleanupconfirmed）。四份native与guardian回执由独立审查者再次解析，结论一致；随后读取四个远端目录的实际脚本/fixture SHA，均与最终版相同，证据 `artifacts/qwen-device-guard-final-deployment-audit-r1.json`。这些测试没有真实PNG、拔USB或温度阈值触发，不扩张验证范围。

研究参考：[官方 Toybox timeout 源码](https://raw.githubusercontent.com/landley/toybox/master/toys/other/timeout.c) 描述默认子进程组与超时代码；[官方 setsid 源码](https://raw.githubusercontent.com/landley/toybox/master/toys/other/setsid.c) 描述 `-w` 等待。当前 Android 二进制仍须用上述短 fixture 核实。
