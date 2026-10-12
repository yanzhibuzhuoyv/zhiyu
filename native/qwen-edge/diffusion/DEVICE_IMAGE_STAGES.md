# 研究设备守护：采样检查点与真实检查点解码

2026-10-11。本工具供协调者在独占研究目录运行，未接入 APK/JNI 或产品生图按钮；不执行 ADB、不自动调用 API、不降低步数/分辨率，不管理用户聊天或 Key。

## 两个固定阶段

| 阶段 | 固定原生参数 | 整数预算 |
| --- | --- | --- |
| `sample` | `JOB/image_smoke SOURCE/dit.gguf SOURCE/vae.safetensors SOURCE/conditions.f32 TOKENS SEED JOB/output.png --sample-only JOB/checkpoint.qwenlatent` | 1..3900秒 |
| `decode` | `JOB/image_smoke SOURCE/vae.safetensors full\|tile256 SAMPLE/checkpoint.qwenlatent JOB/output.png` | 1..900秒 |

这里 `SOURCE`、`JOB`、`SAMPLE` 均为 `/data/local/tmp/zhiyu_qwen_research_[A-Za-z0-9_]+` 的真实规范目录，不含子目录、`..`、空格或 shell 元字符。JOB 每次全新且只启动一次，与读取的数据源目录互不为祖先。SOURCE 中的模型/条件和 SAMPLE 中的检查点只有读取路径，守护不修改权限、不写入这些输入目录。模型实际 SHA/内容、源文件只读部署及原生内部 source-protection 由协调者另行核验；guard 的 regular/size 检查不能替代它们。

`TOKENS` 为1..498（原始序列去掉14个前导位置之后），条件字节数精确 `TOKENS * 4096 * 4`。`SEED` 为0..2147483647，这是守护显式采用的原生 int64 范围子集；Python helper 拒绝 bool、浮点和字符串，不接收任意 steps、size、GPU 或附加 argv。

`checkpoint.qwenlatent` 精确262288字节，包括128字节头、262144字节 F32LE payload及16字节 CRC/footer，**不是裸 float 文件**。guard 只检查 regular/无符号链接与大小；原生 reader 与外层检查仍需验证长度、版本、几何、阶段、CRC及 finite。采样阶段的 `output.png` 只是固定兼容占位参数，`--sample-only` 不创建 PNG，不把该参数的存在当作已出图。

## 新目录部署

由协调者部署以下三个固定文件到同一个全新 JOB：

- ARM64 采样器或真实检查点 decoder，按该阶段复制为 `image_smoke`，可执行、regular、无符号链接。
- 本目录 `device_image_stages.sh`，复制为 `image_guardian.sh`，LF。
- 本目录 `device_guard_common.sh`，保持同名、LF。

两个 shell wrapper 均固定 common SHA-256 `7be5698baf704fcb42cb2846c4c502744c283ef71217722fac9bd12ca66bc47a`，加载前检查 common 是同目录 regular、无符号链接且 SHA 匹配。生产 wrapper 自身路径必须为 owned 实体目录；没有自定义环境变量可指定 common 路径。host `__library` 分支仅使用 Bash 内建 `BASH_SOURCE` 定位测试源码，不能当成 Android 验收。

当前三个本地 shell SHA：

| 文件 | SHA-256 |
| --- | --- |
| `device_guard_common.sh` | `7be5698baf704fcb42cb2846c4c502744c283ef71217722fac9bd12ca66bc47a` |
| `device_image_stages.sh` | `acb5acd174b34c1d91c0b7f966753e7d5dd217b44fb40c26ec9aee13e25a7ae4` |
| `device_vae_runner.sh`（共享版） | `80dc0ce5b411a80063e0eade7f0cb1e3f0cb8a02e5b7b7068c32f1bb13a2e523` |

上传后仍须读取实际远端文件 SHA 与这些值比较，并验证对应 ELF/fixture SHA。新的 wrapper/common 组合需要新的 Android 夹具证据；旧单文件守护的四项实机证据不能自动迁移给它。

## 宿主接口

`scripts/qwen_device_image_stages.py` 只生成命令及解析回执：

```python
launch_sample(directory, source_directory, tokens, seed, budget=3900)
launch_decode(directory, source_directory, checkpoint_directory, mode, budget=900)
parse_receipt(text, kind)  # kind = native / guardian
parse_run(guardian_text, native_text_or_None)
```

调用返回命令，再由协调者执行一次。外层 `nohup setsid -w`、stdin `/dev/null` 与 `launch.log` noclobber 保持；返回 launch PID 只证明提交启动请求。supervise 先排除已有 output/log/identity/receipt/stop.request/checkpoint，再通过独占 `mkdir run.lock` 拒绝重入，不删除或覆盖旧文件。新阶段允许的 argv 由固定 sample/decode 分支构造，无通用命令/任意参数接口。

## 共享守护与结果边界

共享 common 的身份、监控与清理逻辑同时服务 VAE probe、新采样和新 decoder。worker 要求实际 PGID等于自身 PID，且与 guardian 的 PGID/session不同；捕获 native 的正 PID、Linux starttime、PGID、session和精确 `JOB/image_smoke` argv0。只对五字段仍全部精确匹配的正 native PID发送 TERM；最多5秒后再次验证才 KILL，不对未知身份或负 PID发信号。timeout 自己的 worker 组操作仅限启动前已核实的隔离组。

当前 HAL battery ≥44°C、thermal status ≥3、MemAvailable <393216KiB触发停止；HAL 缺失/非法直接 fail closed。dumpsys 自身最多5秒，不能称为每秒硬实时。guardian 先写停止请求，worker 启动 native 前再次检查；early TERM/INT 不被 monitor 初始化重置。身份文件/停止请求的链接类型拒绝读取或清理；身份读取失败、USB不可达、OEM终止 guardian 或缺少完整回执仍为未确认。

原生 direct shell `wait` 与 timeout wrapper的124/137各自记录。TERM中断首次 wait 后仅明确的二次 wait结果才计为确认；缺失或二次 wait127不冒称 native exit。保留 stop reason，即使 native恰好退出0也不能把取消/热停止改写为正常完成。

新回执 `version=2`，记录 `stage/source_directory/checkpoint_path/tokens/seed/budget_seconds`；decode另有 `decode_mode`。decode tokens/seed是字面值 `from_checkpoint`，解析结果为 `None`，因为 shell没有读取并验证检查点头。真实 seed/token/header证明由外层针对实际检查点另行记录。解析拒绝重复字段、未知字段、截断、非法数值和两个回执的任务快照不匹配。

`execution_success` 仅说明执行回执一致正常完成；返回的 `latent_verified=false`、`png_verified=false` 始终不变。它不会因 CLI打印 marker、文件有正确大小、decoder退出0或 fixture写了任意字节就宣布检查点/图像成功。外层需对真实采样 marker、完整检查点及最终 PNG 解码尺寸/通道分别验收。

## 本轮 host 证据

- `artifacts/qwen-device-stages-red-r1.log`：初始缺实现模块，导入失败；没有声称已执行24条行为断言。
- `artifacts/qwen-device-stages-first-behavior-r1.log`：第一版24项GREEN，尚采用旧条件路径，不是最终部署合同。
- `artifacts/qwen-device-stages-final-input-red-r3.log`：最终 SOURCE条件合同及树隔离用例取得真实失败。该阶段同时发现 Windows/MSYS 的 `ln -s` 默认行为不能提供可靠原生符号链接夹具；不把此日志所有失败都归因于 Android产品漏洞。
- `artifacts/qwen-device-stages-green-r4.log`：中间48项有2项失败，因 MSYS 对无 shebang文本 fixture的 `-x` 判定；修正 fixture为可执行脚本，未修改 Android生产可执行权限检查。
- `artifacts/qwen-device-stages-green-r5.log`：最终**48/48 GREEN，6.806秒**；其中新增31项阶段合同和原VAE17项全部通过。覆盖固定argv、源条件精确大小、输出隔离、checkpoint拒绝覆盖、回执和wait区分、取消、身份及资源failclosed。
- `artifacts/qwen-device-stages-bash-syntax-r1.json`：三个 shell `bash -n`均exit0；不证明 Android mksh/Toybox运行。
- `artifacts/qwen-device-stages-source-audit-r1.json`：当前源码及未改原17测试SHA，旧守护原字节另留 `artifacts/qwen-device-vae-runner-4913f5af-history.sh`。

本机 Windows 创建原生 symlink返回1314；最终链接分支测试明确用 `qwen_is_link` 文件类型 stub，目录物理检查的临时文件用例也明确覆写该检查。它们验证决策和“不读取/不发信号”，不证明真实 Android符号链接、setsid拓扑或热阈值触发。手机要另做短 success/failure/timeout/earlycancel 与真实文件类型拒绝夹具，随后再运行真实采样和检查点解码。

历史 VAE证据及旧SHA完整保留在 [DEVICE_VAE_RUNNER.md](DEVICE_VAE_RUNNER.md)。本代理没有执行手机/模型/API操作；协调者的新实机结果需单独记录，不能用这里的host GREEN代替。
