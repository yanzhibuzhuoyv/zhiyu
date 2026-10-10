# Agnes API 实测

凭据由用户指定文件在进程内读取，只发送到 Agnes 官方 HTTPS 主机；未复制进工程、APK、日志或报告。报告中的 `credential_source_unchanged=true` 表示凭据来源文件在调用前后未被修改。此处不记录 Key 或其哈希。

## 结果

| 调用 | 模型 | HTTP | 等待到完整结果 | 结果 |
| --- | --- | --- | --- | --- |
| 描述生成虚构成人 OC | agnes-image-2.5-flash | 200 | 13.993 秒 | PNG，864×1152，839065 bytes |
| 带前图参考，更换为雨天书店背景 | agnes-image-2.5-flash | 200 | 16.225 秒 | PNG，864×1152，1195293 bytes |
| Data URI 原图识别 | agnes-2.5-flash | 200 | 5.778 秒 | 中文描述；1478 token，包含121 reasoning token |

本次只有两次生图与一次识图，不是负载或长期稳定性测试，也没有测首个 token 或手机移动网络。不得把本机两次耗时当作所有用户的速度保证。HTTP 成功不等于内容准确。

图片保存在 [初始 OC](../artifacts/agnes-oc-probe-v3-r2/oc-reference.png) 和 [参考编辑](../artifacts/agnes-oc-probe-v3-r2/oc-bookstore-reference-edit.png)。完整非敏感指标分别见 [生图 JSON](../artifacts/agnes-oc-probe-v3-r2/report.json) 和 [识图 JSON](../artifacts/agnes-vision-probe-v3-r1/report.json)。测试输入是新生成的虚构人物，没有发送用户私人照片、聊天或记忆库。

## 人物一致性检查

肉眼比较，两张图都呈银发、金色眼睛、绿色开衫、黑裤、四只深色翅膀，并保留剑。参考编辑仍改变了脸部线条、身体比例、翅膀位置和剑的角度，伤疤也不一致；初图对“眉上小伤疤”执行不明显，第二张出现脸颊伤痕。雨线还绘入了室内，场景也有违常理之处。本次没有做像素一致或人物 embedding 相似评分，不宣称人物完全一致。

识图回答把实际四只翅膀说为“四对（共八只）”，数量错误。不能用该模型自动批准 OC、翼数或道具是否保留。识别输出属于可出错的辅助观察，不能替代原图或存成用户亲口说过的事实。

“人物不能变”的实现建议是 Agnes 生成无人背景、手机合成已确认的原 OC 透明主体，并保留原图与 SHA。需要新的姿势或镜头时，参考重绘只能作为待确认候选；完成确认之前不进入自动发图库存。这里尚未验证手机合成整条链路、OC 两次机会计数或后台预生成上线，API 可用不能替代这些验证。

## 本机连接排查

首次脚本绕过系统代理后 HTTPS 连接失败，未获得鉴权响应。本机 DNS 将 API 主机解析为回环地址，现有 HTTPS 代理可正常访问；脚本改为遵循用户已有系统 HTTPS 代理并保持 TLS 校验后成功。未修改系统 DNS、hosts 或代理配置。这是本机测试通路排查，不能证明或解释手机上的所有网络问题。

## 官方协议与复现

生图采用 [Agnes Image 2.5 Flash 官方文档](https://wiki.agnes-ai.com/en/docs/agnes-image-25-flash.md) 的官方端点、Bearer、`extra_body.image` 和 Base64 返回。识图采用 [Agnes 2.5 Flash 官方文档](https://agnes-ai.com/en/docs/agnes-25-flash) 的 Chat Completions 内容数组；文档展示公开 URL，本次已额外验证 Data URI 能调用成功。

脚本 [agnes_image_probe.py](../scripts/agnes_image_probe.py) 和 [agnes_vision_probe.py](../scripts/agnes_vision_probe.py) 只接受凭据文件路径，禁止重定向，响应有上限，错误不打印上游 body。输出目录必须不存在，避免覆盖旧证据。运行会真实调用 API，不能把它当作纯离线测试。

后续重点：后台生成和本机库存、OC 原主体合成、独立加密图像凭据、图片发送与观察隔离，最后做手机端网络和等待实测。完整 Live2D 与全双工仍是后续工程项。
