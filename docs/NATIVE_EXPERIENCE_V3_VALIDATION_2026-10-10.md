# 0.2.1 手机本地体验与静默日历验证

2026-10-10。恢复依据：全局索引、完整项目交接、Git `0043301649d834738dd975f1ea5ff9d214448283`、既有差异与真实日志。仅使用自有隔离 AVD `zhiyu_local_20261009` / `emulator-5582`，没有操作 Redmi、旧 Docker、私人 RAG 或电脑旧聊天库。此前本地迁移仍有大量未提交内容，本轮没有重置或盲目提交整个工作树。

## 本轮实际改动

- 回复等待只显示“对方正在输入中”，去掉加载进度和旧副标题；保留水彩、透明气泡和用户背景优先。
- 每条有效消息显示秒级时间，第一条、相隔至少五分钟或跨日显示完整中间时间；无效时间不替换成现在。聊天字号14–26sp，本机保存，仍遵循系统字体缩放。
- 修复输入区域的 IME/insets 处理；本轮用真实软键盘验证消息和输入框可见，查看旧消息时保持位置。
- 进入已绑定聊天前置申请通知、粗定位及日历权限。拒绝不挡聊天、不循环弹窗；去掉固定22–08安静时段和主动联系/天气设置开关，性格频率和锁屏隐私策略保留。
- 初次成功绑定立即持久化当天生活轨迹；前台恢复、WorkManager 零点切换及补偿检查均在手机完成。目前是四套活动模板，未接动态 LLM 轨迹和同步产图。
- 按用户最新明确要求，前置日历授权后恋人可默默写自己的日记及明确生日/纪念日/已定约定。不再要求“写进日历”、不逐项确认，也不弹成功保存通知。只操作知语自己的 LOCAL 日历；原文记忆独立保留。
- 日历候选来自同一次回答，客户端核对连续原话、条件/否定/候选/疑问、日期及年份，失败保留自然回复；先解析日历再解析图片/表情，避免丢失配图。事件按原消息身份幂等，日记最多一条/天。
- 新增独立 Agnes 协议、严格 JSON、Keystore 凭据存储和固定官方 HTTPS 传输；取消、截止、资源上限、无重定向和无自动重试已测。没有将私人 Key 放进 APK。

## 最终执行结果

| 验证 | 实际结果 | 证据 |
| --- | --- | --- |
| 主 APK、测试 APK、完整 JVM 单元、lint | r9 BUILD SUCCESSFUL，1m50s；328 tests / 43 suites，失败/错误/跳过0 | `artifacts/native-experience-v3-build-r9.log`、JUnit XML |
| lint | 0 errors，47 warnings，1 hint；未加忽略或 baseline | `android/app/build/reports/lint-results-debug.xml` |
| 隔离 AVD 原生批次 | 33/33，105.87秒 | `artifacts/native-experience-v3-native-r3.log` |
| 另一次深色界面 | 1/1，3.141秒，不与33项当作独立唯一数量相加 | `artifacts/native-experience-v3-native-dark-r3.log` |
| 系统真实日历 | 2项：专用日历幂等写入、生日准备事项；查询包身份及事件类型，删除本测试UUID事项 | 上述33项内 `CompanionCalendarProviderTest` |
| 首次建立轨迹、换日和持久恢复 | bootstrap2 + repository4项通过 | 上述33项内对应测试 |
| Agnes 本机图片/凭据 | JVM25项，原生3项通过；无真实云请求 | 单元XML及上述33项 |
| 设备基线恢复 | IME 0→临时1→0，日历读写 false→临时grant→false，飞行模式保持1 | `artifacts/native-experience-v3-final-device-before.json` / `after.json` |
| APK身份 | 0.2.1 / code7 / min26 / target36 / arm64-v8a；同旧调试证书 | `artifacts/native-experience-v3-apk-badging-r9.txt` / `signature-r9.txt` |

浅/深实际 Compose 截图为 `artifacts/native-experience-v3-chat-light-r3.png` / `dark-r3.png`，已经人工查看；为合成消息真组件，不是正式私人聊天或完整系统栏截图。最初 UIAutomation 图片全空白，旧文件保留并排除证明；改为 Compose 根截图且检查像素多样性后通过。

## 测试先行与审查修复

失败日志完整保留。r1缺失模块是编译RED；r2捕获OkHttp5.5要求SDK37，改用已核验minCompileSdk36的5.4；r3补可控Call的新方法；r4捕获非法时区异常契约，修复后全量通过。后续日历断言RED涵盖条件/无问号疑问、缩进示例、显式年份、真实畸形中文数字“十二二”、否定/取消及裁掉“明年/今年/2025年”的证据；最终r9全量绿色。四字“二十二三”没有重现误读，不把它计为真实发现。

只读独立终审见 `artifacts/native-experience-v3-final-review.md`；源码两项HIGH、两项P2已关闭。终审没有代替主任务执行构建或接触凭据。

## 安装包

桌面：`C:/Users/32202/Desktop/知语-0.2.1-本地体验版.apk`。项目副本：`artifacts/ZhiYu-0.2.1-local-experience-arm64.apk`。两份均 **94,053,821 bytes**，SHA-256：

`2faa2a5024eac18ab7c8c89947a44160d0e81991a940de9e63d83828828a5e40`

证书 SHA-256：`2f6842228b821c4f62a9842747e94ba1f4ec8c57683f063587ae750da00b4243`，仍是调试签名；旧0.2.0包保留。APK、源文件和随包资产摘要见 `artifacts/native-experience-v3-final-proof.json`。本轮未真机覆盖安装或测移动网络登录；本地目录/记忆无需电脑，云聊天仍需网络与用户Key。

凭据精确泄漏检查覆盖321份文本和306个解压APK条目，UTF-8/UTF-16均无Key命中；只记录计数，不记录Key或其摘要。证据 `artifacts/native-experience-v3-credential-scan.json`。

## Agnes真实试调用与边界

本机两次官方 Image2.5生图/参考编辑HTTP200，13.993/16.225秒；一次Data URI识图HTTP200，5.778秒。四翼和武器大体保留，脸部/比例/伤疤漂移；识图把四翼说成四对八翼。不能用API成功或caption自动批准身份一致。独立报告见 [AGNES_API_TRIAL.md](AGNES_API_TRIAL.md)。这些电脑调用不是本轮原生云端稳定性或手机网络证明。

## 未完成与下一步

1. 静默日历仍是保守语言门，可能漏掉含“不忘/没问题”等句子的有效约定；原文不会丢失。没有年度RRULE、精确提醒、跨消息语义去重或已经写入事项的自动撤销。聊天落库与CalendarProvider是两个事务，异常中断可能漏写日历。实际礼物或线下动作未实现。
2. Agnes 尚未接生产生成按钮/独立凭据设置/后台库存和真人固定主体合成。两次机会不会因“未配置”扣除。真人与非真人2D/3D路线目前是设计文档，没有编辑器或固定角色渲染管线。
3. 用户发照片、OCR/视觉描述运行接口、预置声音、ASR/TTS、Live2D拆层rig和全双工未接入；研究不等于成品功能。
4. RAG本轮没有改变原文融合规则、BGE模型或检索算法；未实现分区向量索引和同库分区A/B。旧50M Unicode/万轮结果保持，大库语义召回20.66–21.56秒仍需优化；本轮没有重跑长测。
5. 旧电脑数据迁移、正式发行签名、更新认证与收费未完成；需要后续真实手机验证厂商后台唤醒和网络。
