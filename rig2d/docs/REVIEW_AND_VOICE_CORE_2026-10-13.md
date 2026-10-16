# rig2d 独立排错与语音核心验证

用户提供目录，要求继续 Live2D/全双工并检查错误，后期接知语；确认若全双工不存在就继续做。实际目录已有契约和Python探针，没有麦克风/语音流实现。本轮修已有错误并建立独立 core，不改知语Android/APK/Key/聊天。

## 可检查的产出

- `src/main/kotlin/rig2d/voice/`：PCM/ports、可替换VAD、端点与pre-roll、按句分块、独立回复流水线、持续采集session、epoch/取消/释放。
- `build/libs/rig2d-0.1.1-research.jar`：纯JVM17库，coroutines1.10.2；不包含设备适配器或模型，不是可安装App。
- `./gradlew.bat duplexFixture`：不录麦克风的合成复演；实际输出41帧采集与播放重叠、1次打断、5类port释放。原trace在 `artifacts/duplex-fixture-2026-10-13-r1.jsonl`。
- [安卓适配准备](VOICE_PORTING_2026-10-13.md)。真实声学、云接口和OC视频成品不在本次已通过范围。

## 找到并修复的错误

| 范围 | 实际问题与修复 |
|---|---|
| v2契约 | 混合结果首项不是最高权重主视角；nearest允许NaN/带外角度；proxy法线/版本/reference漏校验；已校验对象仍别名引用外部列表；逐层margin通过但view摘要错误；v2资源ID不受安全长度/字符限制 |
| 图像输入 | width/height解包反了、边界允许越界像素、NaN解析重复定义、定位失败引用已删除键、overlay像素/世界单位混淆；补有限数/资源/输入格式与独占输出验证 |
| 投影/纹理 | 镜像UV与原参数化不一致；经纬边界膨胀的颜色与mask规则不同；Lp法线错误；render世界z与源图row符号相反使上下倒置；来源链/错色校验漏检 |
| 探针门禁 | 硬自检失败仍给进入阶段的结论、退出码仍0；现任何硬失败或来源失败都会拦截，线稿面积不能冒充拓扑验收 |
| 语音生命周期 | 已取得资源却在LAZY任务未执行前停止/取消时泄漏；新增exactly-once release/completion兜底与factory取消回滚 |
| 语音打断 | 实际player.stop失败后继续识别下一句；现在立即typed RELEASE、结束session并关闭资源 |

v1三个冻结源和golden保留。原宿主golden只证明自身fixture，不代表Android任意源图逐像素相同；真实像素回归仍要移植前保存设备基线。

## 实际测试与覆盖

| 执行 | 结果 | 日志（artifacts） |
|---|---|---|
| 原始基线 | 95 tests /8 suites全部通过，20s；43原源SHA与旧回执一致并备份 | `baseline-jvm-2026-10-13-r1.log`、`review-2026-10-13-baseline/baseline.json` |
| 契约RED | 首7项7失败，加入ID边界后8项8失败 | `contract-red-2026-10-13-r1.log`、`contract-voice-red-2026-10-13-r2.log` |
| 契约GREEN | 原95＋新增8＝103通过 | `contract-green-2026-10-13-r1.log` |
| 语音RED | 21项未实现失败；补5边界后26项未实现失败 | `contract-voice-red-2026-10-13-r2.log`、`voice-red-2026-10-13-r3.log` |
| 首次语音GREEN尝试 | Nothing返回类型编译错误，未执行测试；仅补显式Unit，不算业务RED | `voice-green-2026-10-13-r1.log` |
| 初语音GREEN | 26项通过 | `voice-green-2026-10-13-r2.log` |
| 独立生命周期RED | 3项真实业务失败，修复后同断言GREEN | `voice-lifecycle-red-2026-10-13-r1.log` |
| 最终统一构建 | **132项 /12 suites，0失败/错误/跳过**；test、coverage、JAR、fixture成功12s | `integrated-green-2026-10-13-r1.log` |
| Python RED→GREEN | 三轮RED保留；最终30项通过，root独立复跑0.328s | `probe-audit-2026-10-13-r1/red*.log`、`green-final.log`、`probe-independent-green-2026-10-13-r1.log` |

JaCoCo0.8.13，核心 **行91.93%（866/942）、指令87.64%、分支71.98%**。只排除合成demo驱动，不排除core或错误处理；未声称所有分支达到80%。报告 `build/reports/jacoco/test/html/index.html` 与XML。版本选择参考 [JaCoCo官方发布说明](https://github.com/jacoco/jacoco/releases/tag/v0.8.13) 的Java23/24支持。

没有Git，本轮未初始化仓库；以源快照、真实RED/GREEN日志、SHA回执和独审保留证据，没有虚构提交。

## 完整图像探针：没有通过验收

root独立跑两套合成夹具，固定384分辨率 /96网格 /512纹理 /2倍采样 /2膨胀。两个新目录及旧结果互不覆盖。图册明确是A阶段，B读数不被冒称对应图册像素。

| 指标 | 彩图 | 线稿 |
|---|---|---|
| 实际执行时间（两个任务并行） | 241.16s | 258.44s |
| baseline正面洞率 /硬阈值 | **7.6946% /2%** | **22.3796% /2%** |
| B后正面洞率 | 1.0026% | 10.3751% |
| B后±45°最坏洞率 | 23.7472% | 33.5317% |
| B后±90°最坏洞率 | 66.9054% | 79.3295%，不作为线稿目标角 |
| 本图像素来源 | true | true |
| 硬检查/正式结论 | false /`GATE_INVALID_MEASUREMENT`，退出2 | false /`GATE_INVALID_MEASUREMENT`，退出2 |

文件：`artifacts/probe-color-2026-10-13-r1/probe.json`、`artifacts/probe-lineart-2026-10-13-r1/probe.json`。面积阈值10%/25%、正面2%未降低。B后面积变小不能消除原正面硬失败，不能宣布OC身份或Live2D成品通过。当前没有真实彩图集合复跑。

root又单独执行正面诊断：5,802个洞全部对应背景，0对应人物、0图外；明确为`GEOMETRIC_FIT`，不是人物纹理被漏采。回执在 `artifacts/front-diagnosis-2026-10-13-r1/`。模型路线和官方许可见 [分割核查](SEGMENTATION_RESEARCH_2026-10-13.md)。

后续轮廓候选实际完整投影的正面洞率降至0.3242%，通过原几何研究门禁；但固定原ROI的完整工作图检查发现候选漏掉7,345个前景像素，实际保留94.9687%，低于缩略研究的95.0568%。因此未采用为默认或发布方案。另新增研究工具，先stub RED，后修独审输入边界，最终18项实际GREEN；详见 [轮廓研究与否决原因](CONTOUR_STUDY_2026-10-13.md)。原失败、默认和阈值保持可追溯。

## 独审和接下来

其他owner的probe与voice冻结源码分别经独审；已发现HIGH取得真实RED后修复，最终132全GREEN。审查文件在 `artifacts/contract-audit-2026-10-13.md`、`independent-probe-review-2026-10-13.md`、`independent-voice-review-2026-10-13.md`，不能将作者自检冒称独立审查。

后续仍需：解释/修正几何拟合覆盖与分割；线稿笔画连续性/断头率；真实彩图/OC多视角身份和全程露缝扫描；部件分割/补全模型及许可；完整渲染/发布；真实麦克风、ASR/TTS/network和AEC/设备延迟。准备完后再迁移，不能把当前库直接当手机全双工成品。

已知边界：B环数上限不是严格来源链累计距离，像素来自本图也可能扭曲笔画/身份；Energy VAD不是声学AEC；真实native取消和硬件口型提交需二次stamp检查；第三方接口付费请求幂等不由core保证。没有访问真实Key/私库，没有下载新权重或重跑知语万轮/50M。
