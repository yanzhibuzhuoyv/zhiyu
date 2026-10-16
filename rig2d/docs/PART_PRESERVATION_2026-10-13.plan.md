# 原前景保全与分层草案：实施范围

从当前首个未完成项继续：单个头部代理候选会裁掉7,345像素；分割模型漏掉非人类附属物时不能把它当背景丢弃。本轮在独立纯JVM模块加入处理分层mask的核心，不改v1/golden/v2 schema或知语Android，不引入新模型权重。

用户场景：头发和多个翅膀/武器实例独立保留；模型subject漏分可由明确保留mask补回；未知、低置信度与重叠冲突像素留在静态原图层；不能通过删除小连通分量、选最大人像或缩框改善分数。

设计：有界不可变bit mask、原ARGB副本与尺寸参与的像素hash、稳定实例ID+已有19类kind+有限confidence；语义candidate与有效前景求交。合格candidate重叠时不猜胜者，冲突像素归保留层；合格不重叠部位可形成草案。每张源图最多63候选，为第64个保留层留资源预算。保留mask可以补回模型漏分，但原透明像素没有原颜色时不能被伪造为人物。

完整原前景定义为本次提供的subject与人工/可信保留mask的并集，再与源alpha相交。它并不证明自动背景分割准确；未在两mask中的翼仍然可能漏掉，原图始终只读，需模型/笔刷和真实图验收。

TDD验收：先stub+真实JVM RED；实现后验证半透明ARGB重组与提供前景完全一致、12个wing实例、单像素武器、冲突/低置信度/空candidate、unknown种类/NaN/错尺寸/大数组/ID重复/引用别名等；有效mask空间1536最长边、4MP上限。独立合成示例读取另一个owner生成的模型漏分夹具，必须标注NOT_MODEL/NOT_REAL_OC。

不得称为自动语义识别、完整Live2D或发布ready。真实模型适配器、完整渲染与侧视身份仍是后续。遵循既有TDD技能与用户AGENTS；本目录无Git，保留逐次源码/RED/GREEN与SHA，不初始化仓库。

选型核查：现有源没有mask/part assembler可复用。MediaPipe官方支持类别与confidence mask，但输出类别随模型变化；SAM2官方提供提示分割而不是这里的19类自动绑定。本轮只预留mask入口，不新增依赖，见[MediaPipe Android](https://developers.google.cn/edge/mediapipe/solutions/vision/image_segmenter/android)、[SAM2官方](https://github.com/facebookresearch/sam2)。
