# 实际提示分割适配与评估方案

从保全核心之后继续：选择官方SAM2.1 tiny进行独立桌面CPU实跑；不是把模拟mask当模型输出，不改变Android/v1/golden/v2 schema。代码commit固定2b90b9f5ceec907a1c18123530e92e794ad901a4；权重156008466B及官方LFS SHA已实际下载核对。项目隔离venv复用已装Torch2.12.0+cpu，仅本地增加Hydra/iopath依赖；不升级系统Torch。

接口：工作RGB uint8/HWC受1536边长/4MP约束；每帧最多63部位，XY像素点与0/1标签、可选XYXY框；验证所有请求后才编码。每帧按工作像素SHA绑定输出；predictor一次编码、逐部位解码、finally释放图像缓存，串行锁隔离可变状态，取消只保证不提交结果，不宣称能抢占Torch内核。

原官方接口返回float32二值mask和预测mask IoU质量score。适配器验证shape/有限数/二值/候选数量，不把score当语义类别准确率。优先选择满足正负点约束的候选，再按质量score排序；空mask、几乎全frame、低质量、正负点不满足明确标为rejected，原预测保留用于诊断，不伪装ready。输入图/模型输出不共享可变数组。

TDD：先stub与模拟predictor测试实际RED，再实现安全适配。实际模型另用公开NASA astronaut照片（无分割GT，不虚构IoU）、已知GT的彩色/线稿十二翼复杂背景合成样本，必要时公开有翼插画。GT只评分、可模拟理想人工点/框，不直接作为模型mask或声称自动定位。

官方来源：[固定代码](https://github.com/facebookresearch/sam2/commit/2b90b9f5ceec907a1c18123530e92e794ad901a4)、[predictor](https://github.com/facebookresearch/sam2/blob/2b90b9f5ceec907a1c18123530e92e794ad901a4/sam2/sam2_image_predictor.py)、[checkpoint许可](https://github.com/facebookresearch/sam2#license)、[NASA样本公共领域说明](https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut)。SAM2是提示分割，不是自动19类解析；此轮不宣称完整角色、Android速度或Live2D发布。
