# 分割路线与许可的实际核查

2026-10-13，只核查官方原始资料，没有下载、导出或打包任何新权重。本文件是技术选型依据，不代表相关模型已经在手机运行。

## 原先 BiSeNet 路线仍有缺口

face-parsing.PyTorch 代码为 MIT；其 README 明示使用 CelebAMask-HQ 训练，并提供预训练模型。数据集官方条款限非商业研究，包含衍生数据的限制。因此不能从“代码MIT”推导这些预训练权重可以随收费App发布；当前没有取得额外权重授权，不将其写入 assets。

来源：[代码许可证](https://github.com/zllrunning/face-parsing.PyTorch/blob/master/LICENSE)、[模型训练说明](https://github.com/zllrunning/face-parsing.PyTorch/blob/master/README.md)、[数据集条款](https://github.com/switchablenorms/CelebAMask-HQ/blob/master/README.md)。

## 两条可比较的候选路线

| 路线 | 已核事实 | 在本项目的缺口 |
|---|---|---|
| SAM2 点/框提示分割 | 官方将模型checkpoint、训练和demo代码列为Apache2.0；支持promptable图像/视频分割 | 不是自动19类面部解析器；未做端侧导出、手机内存/速度、线稿/翅膀/武器与多层身份验收 |
| MediaPipe分割与已有笔刷 | 官方提供Android类别mask/confidence mask；selfie多类为背景、头发、身体皮肤、脸部皮肤、衣物、其他配饰 | 类别粒度不足以直接得到眼白/虹膜/眼睑/嘴；不同具体模型卡与发行NOTICE仍需固定；未测试非真人OC |

来源：[SAM2官方仓库许可与模型说明](https://github.com/facebookresearch/sam2#license)、[MediaPipe模型类别](https://developers.google.cn/edge/mediapipe/solutions/vision/image_segmenter)、[Android接口](https://developers.google.cn/edge/mediapipe/solutions/vision/image_segmenter/android)。没有采用第三方博客的商用或手机性能结论，也不把官方其他设备的跑分移植成红米实测。

技术上建议先比较“粗分割/交互提示＋保留原图＋按层人工修正”，不让自动人像分类器直接删除翅膀、武器等OC组成。SAM2等候选要先在独立样本和手机资源约束下评估，结果出来后再选；本轮没有替换原项目分割实现。

## 当前几何拟合的明确事实

新诊断 `artifacts/front-diagnosis-2026-10-13-r1/front-hole-diagnosis.json`：默认合成彩图正面5,802个洞全部落在源图背景，人物源像素漏采为0、图外为0。属于代理轮廓与输入轮廓不一致，不能用降低2%阈值解决。

后续先在确定性、有界的轮廓拟合试验中同时检查源人物保留率和代理覆盖背景率，防止通过缩小代理裁掉人物来制造成功。解析轮廓评分只是研究，不替代真实投影、分层露缝、线稿拓扑、OC身份或商用模型验收。
