# 表情第二版验证 · 2026-10-08

当前改动基于 `3b8357d`。后端与素材改变，安卓消息附件协议、主应用业务代码及 0.1.3 APK 保持兼容。

## 后端与真实模型

| 检查 | 实测结果 | 本机证据 |
| --- | --- | --- |
| 完整后端回归 | 867 passed，252.81 秒，94.528% 行覆盖率 | artifacts/sticker-v2-full-tests-final.log / sticker-v2-full-coverage-final.json |
| 独立受影响回归 | 57 passed，代码审查无 C/H/M 修复项 | artifacts/sticker-change-review.md |
| 静态检查 | Ruff 与 git diff --check 通过 | 同次工作区检查 |
| 官方 DeepSeek Flash | 10 个合成场景完成，每场景一次正常回答调用 | artifacts/sticker-live-v2/report.json |
| 真实选择 | 未索要表情的 10 场景中 4 次有效配图，6 次没有有效选择标记而保留文字 | 同上 |
| 外置图库更新 | QQ 18 项真实导入；停用旧 Fluent 公共来源 8 项 | artifacts/sticker-v2-computer-import.json |

首次完整回归被会话中断，日志只到部分进度，保留 `sticker-v2-full-tests.log`，不计为通过。上表是重新完成的全量。现存一条 Starlette/httpx 弃用警告未被忽略或改写。

真实测试使用官方 `deepseek-flash`，关闭思考，与当前正常聊天适配一致；不强制 JSON 正文。用户之前提供并授权测试的凭据仅在进程内用于官方 API，文件前后 SHA 一致，不保存 Key、token、请求头或思考正文。测试只有合成对话，不读取真实 RAG 或私人聊天库。

这 10 次非流式完整回答耗时为 0.673—1.367 秒，包含本次候选说明与可选选择。6 次文字回答未输出有效选择标记，不表示模型明确选择了 `none`，也不构成控制协议 10/10 成功。没有同时测无表情基线或首 token，不能据此计算额外延迟、宣称总是如此快或证明长期人格/选图正确率。生日原话核对场景保持文字回答，未凭表情虚构生日事实。成熟场景可以在合适的庆祝语境使用表情，频率由独立冷却限制，而不是一律禁止。

## 素材原图与分发边界

电脑当前主力是 18 张 QQ 风格反应图：10 张整张软萌仓鼠、7 张真实猫反应图、1 张举爪小狗。原图共 3,797,041 字节，来源为 [公开网络表情合集](https://github.com/JackEasons/emoticon) 的固定提交 `dca72aa6f131c5fdd4d933ef634fb622f3e06641`。每张保留原 URL、文件名、SHA、尺寸和准确中文画面/语境说明，没有裁剪或改图。

合集不是所有图片的权利人；这 18 张的原作者及重分发授权未确认，只是本机未发布开发预览，不进入公共仓库、正式发行 APK 或可分发素材包。运营清单、逐张来源和预览在 `artifacts/reaction-stickers-v2/local-qq-preview/`；清单 SHA 为 `c4be3a903db04b4a1d3dae6ea660bafdc7be2be206dade54e653b34d9b213695`。

另备 20 张 [Magory 原作者白猫基础表情](https://magory.itch.io/cat-emoticons)，作者明确 CC0。这组未作为当前主力导入，避免退回普通 emoji 风格。原作者已切好的 PNG、README、CC0 全文及来源单独保留，可分发包为 `artifacts/reaction-stickers-v2/licensed-base-cc0.zip`，183,024 字节，SHA `07526233970ccc218f92fa0c21387e8d547215824cea65f15ffbd6a66fae2ace`。

两组共 38 条 metadata 均通过 `AssetMetadata` 校验；18 张 QQ 原字节 SHA 与下载源一致，所有选图可解码。导入后按既有规则去除嵌入信息并规范化，入库文件 SHA 可能与原下载图不同。验证记录为 `artifacts/reaction-stickers-v2/verification.json`。

旧 Fluent 候选已停用，原文件与旧聊天附件保留。图片库、OC 绑定、用户完整原文及通知权限开关均不因换表情而清空。

## 电脑原生显示

独立 AVD `zhiyu_media_test / emulator-5580` 新增定向 instrumentation 实际 **1 项通过，9.794 秒，exit 0**，最新 test APK 构建成功。普通分享“今天回家路上看到一朵小云像土豆，跟你分享一下。”未索要表情，显示本轮新仓鼠耳机图 `3196c3295af54ac0ab3a1c3adde2b3ac`。测试按新图 ID 定位，并检查该节点屏幕区域内的 ImageView 实际解码与有效布局，没有加载失败或控制标记泄漏。

这是确定性渲染 fixture：四条暖场各一次回复，目标分享再一次回复，云调用 0；真实语义效果由上面的 10 次独立 DeepSeek 调用观察。不能把 fixture 当真实模型效果，也不与旧 101 单元或 22 项原生测试重复累计。截图 `artifacts/qq-sticker-ui.png` 已实际查看，完整报告及逐轮计数在 `qq-sticker-ui-report.md`、`qq-sticker-ui-events.jsonl`、`qq-sticker-ui-final-proof.json`。

测试使用只读源库 backup 的隔离副本，原 6 条合成消息逐字段保留，副本新增 10 条双方消息；原 owner、公司/model、人格与会话 ID 保留，副本 updated_at 随正常发送变化。设备不清 Key、绑定或历史，不操作物理手机。仅更新 test APK，主应用业务代码与旧 0.1.3 APK SHA 保持不变。结束将 AVD 的 `tcp:8791` 恢复到电脑 `8801`；核验 helper 身份后停止隔离 `8802`，主测试服务健康。此前 UI 的 ANR 仅选择 Wait 恢复，未重试历史被拒绝的补充重启动作。

本机预览页面为 `http://127.0.0.1:8823/local-qq-preview/gallery.html`，已经在应用内浏览器实际打开并确认原图显示。服务和 AVD 状态属于易失状态，恢复时仍需重验。
