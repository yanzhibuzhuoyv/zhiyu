# 细节纠正与线稿保全验证

2026-10-13，独立rig2d。依据[本轮方案](DETAIL_REFINEMENT_2026-10-13.plan.md)继续漏翼/细丝/线稿研究。新增可见笔画候选工具与跨语言静态保留验证；追加纠正点的三种模型方案实测退化，未作为默认。完整Live2D与真实手机全双工仍未完成。

## 真实纠正点实验

固定SAM2.1 tiny/CPU2线程、原512²彩图和线稿，每张只编码一次；17部位，分别测试仅补点、多候选加前一轮logits、单候选加logits，每方案最多2轮，每轮最多一正一负点。误差点由GT推导，是理想人工纠正上限；GT mask没有给模型。总25.682s、进程峰WorkingSet1,207,799,808B，实际exit0。

表中是最终通过原0.8质量与全部正负点条件的部位并集，不是原始所有候选的整体质量：

| 方法 | 彩图召回 / IoU | 线稿墨迹召回 / IoU |
| --- | --- | --- |
| 原部位方案 | 97.003% /95.376% | 15.666% /9.331% |
| 追加点，多候选 | 27.456% /27.394% | 0% /0% |
| 追加点+logits，多候选 | 39.149% /38.957% | 15.626% /10.242% |
| 追加点+logits，单候选 | 4.459% /4.457% | 0% /0% |

追加条件后很多候选不能满足全部点击或质量门槛，接受数量降低。此实验只否决本次纠正策略，不证明所有交互分割都无效。**不降低0.8，不覆盖旧好结果，不把新候选自动提交**；原SAM2 adapter和JVM生产代码未改。官方支持前次256²logits及多/单候选接口：[固定predictor](https://github.com/facebookresearch/sam2/blob/2b90b9f5ceec907a1c18123530e92e794ad901a4/sam2/sam2_image_predictor.py)。

## 线稿的新路径

`segmentation/ink_refinement.py` 接收RGB工作图、明确的候选支持域、笔画与背景采样点、可选人工保护。根据采样颜色计算距离和背景分离条件，再从全部正种子做8邻域重建。不会固定识别灰色，也不选择最大连通区域或删小点。重建机制参考[官方形态学文档](https://scikit-image.org/docs/stable/api/skimage.morphology.html#skimage.morphology.reconstruction)，本实现调用已安装SciPy的binary_propagation。

输入限制：uint8 RGB/1536最长边/4MP、bool同尺寸support和protection、两类各1–64个整数XY点、有限有界颜色阈值。前景背景样本分不开、采样越界/缺失、负点与保护冲突均明确报错。源/support复制，输出不可变bytes与RGB工作像素SHA。

选中笔画和未连接到种子的同色候选分别保存。后者不是被确认的前景，更不能默认当背景扔掉；只供后续交互检查。人工保护可留支持域之外的小片段，不自动赋予部位类别。`review_required`始终true、`identity_verified`始终false。同色背景线条仍可能混入，支持域外、太浅或没有颜色证据的细节仍可能遗漏。

## 实际原图评分

复用上轮真实模型的raw部位mask作为支持，不重新推理。包括低质量rawmask只代表候选范围，没有把它们改成accepted语义。正种子为原17部位提示、背景为原角点；仍是GT推导过的理想人工位置。提取器本身只接RGB/support/点，不接GT。线稿GT仍为可见墨迹14,892像素，不是填充区域。

下表取默认颜色距离32、分离12；所有3行在这个合成样例中FP均为0，**不代表真实图零误选**。

| 支持范围 | 选中召回 / IoU | 漏墨迹 | 另存待确认候选 | 提取耗时 |
| --- | --- | --- | --- | --- |
| 原raw部位并集 | **84.300%** | 2,338 | 810 | 0.0808s |
| raw范围向外1像素 | 88.940% | 1,647 | 535 | 0.0826s |
| 整帧，仅诊断 | 92.452% | 1,124 | 520 | 0.0814s |

整帧不作为默认扩大策略。支持范围依然决定漏线；另有浅色/与背景近似的墨迹因无法区分而不进入选中层。即使将810个候选显式保护，原raw范围仍有1,528个GT像素未覆盖，不能声称全部细丝/翼/武器保全。不同颜色距离16/32/48共9组、r1/r2合计18行原始计数和产物保留；r2才持久化不确定候选，r1不冒称已有该证据。

耗时只包含笔画提取，**不含之前约2.55s模型编码/分割、加载和写盘**，不是手机延迟。实际模型输出的画风和形状未改变，本轮没有生成人物隐藏区域或动画。

## 任务、测试与保证

| 任务/保证 | 实际验证 | 结果与边界 |
| --- | --- | --- |
| 新算法在笔画/背景采样下保留细线、彩色/浅色笔画、多个种子组件 | `python -m unittest segmentation.tests.test_ink_refinement -v` | stub14方法运行RED，49errors；最小实现同14GREEN |
| 同色不连通小片段另存，而不暗中确认或删除 | 同测试新增2方法 | 16方法RED，2AttributeError→新增独立候选bytes→16GREEN |
| 输入大小/类型/坐标/阈值/保护冲突校验、来源与输出无可变别名 | 同16方法 | 实际通过；不会证明任何真实OC语义或身份 |
| 实际Python笔画输出经JVM来源绑定后逐像素静态保留 | `gradlew test --tests rig2d.parts.InkDraftIntegrationTest` | 2/2通过；12554选中像素原ARGB一致，不赋semantic plane |
| 明确保护可以保留810未知候选 | 同JVM测试第二方法 | 13364静态像素重组一致；仅测试显式保护，不自动确认810 |
| 当前全套回归 | `gradlew test jacocoTestReport jar`；stdlib trace包裹`unittest discover segmentation/tests` | **181 JVM/18suites，29 Python，0failure/error/skip** |

新算法覆盖80/81可执行行（98.765%），旧adapter141/150（94.000%）；Python未测分支覆盖。JVM整体仍1023/1100行93.000%、1007/1337分支75.318%；不声称全库分支80%。TDD快照/RED/GREEN日志、有效覆盖和逐项保证已留档，没有Git仓库，未虚构checkpoint commit。

旧tools34、probe30源码SHA不变，沿用已有真实结果，本轮不重跑旧长测。JVM主源码无变化，0.1.3-research JAR仍256326B/SHA `2e8c885dbf3d493aef86329f42d0791a2d0422b95f8a50ccbaea8553c2f6a9a6`；Python笔画工具不在该JAR里。

## 审查与证据

独立审查16方法PASS，并重算r1/r2共18行mask/support/来源RGB与ARGB SHA/TP、FP、FN、种子和支持边界，r2候选与selected分离，35份产物检查前后SHA一致。另读取2个JVM源码与实际XML，没有跑Gradle或模型。范围内无未关闭CRITICAL/HIGH：[审查记录](../artifacts/independent-ink-review-2026-10-13-r1.md)。

两研究代理曾因额度中断，root接管算法与实验；detail_diagnosis恢复后完成以上独立审查。其初次只读诊断脚本有SyntaxError、无有效JSON/MD，保留失败文件，不算完成证据。

证据：`artifacts/detail-correction-eval-2026-10-13-r1/`、`artifacts/ink-eval-2026-10-13-r1/`、`r2/`，固定研究驱动同名前缀。所有GT评分只在固定实验驱动内，驱动不是产品任意文件导入器。最新run及覆盖归档在`artifacts/detail-validation-2026-10-13-r1/`，旧SAM2回执原字节保存历史。

## 下一步

先完善支持域不足、浅色/同色背景与不连接细节的交互保护，加入许可明确的真实OC线稿/彩图评估。不能用这张oracle合成图解锁完整角色发布。然后继续层序、网格、隐藏内容、多视角与动态露缝、renderer及发布。真实音频设备/ASR/TTS/AEC/手机全双工尚未验收。

知语Android/APK0.2.6、HEAD11b17c7/RAG未改，旧backend9tracked+23untracked原样。没有USB、API Key、付费API、真实录音或私人素材操作。
