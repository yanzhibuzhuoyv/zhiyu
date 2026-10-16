# 完成可运行动态二维角色闭环

2026-10-14 用户调整验收：不要求全自动完整生成，允许系统先给建议、用户修正；人工纠错必须可撤销、可对照、可保存并在失败时保留。该工作流已在电脑工坊完成验证，见[人工修正报告](MANUAL_CORRECTION_2026-10-14.md)。原自动质量与多视角门槛不因此变为通过，后续优先按手机接入范围移植。

用户最新要求“继续，直到完成live 2D”，不再在小研究阶段结束。恢复90源/823证据验证，旧图像错误/失败保留。按其原rig2d自研路线完成动态二维网格角色及桌面可用编辑器，后期Android接入要求保持；旧v1/golden与v2多视角门槛冻结。

## 完成验收

1. 真正可运行编辑器：导入PNG/JPEG/大图，EXIF处理、限定大小、保存原图；自动建议眼口/头部位置，可人工调整、按层画保留/擦除、多个附属层（包含十二翼）不被最大连通区域规则删去。
2. 实际二维网格渲染：眨眼、口型、呼吸、头部轻转、发/翼/武器独立随动；原始纹理而非静态PNG截图。暂停/恢复、参数滑杆、鼠标/触摸注视、音频RMS口型输入及渲染丢失降级。浏览器实际像素与帧数据验证，截图/演示保存。
3. 用户照片绑定：中性状态重组源像素一致，生成的眼口下层补丁只用于动态暴露区，按层/修整后明示审批，原图不覆盖。自动分割不是准确性宣告，编辑器允许修正后真正发布二维资产。
4. 固化/重新导入：固定格式rig2d-planar-1、来源/纹理SHA、有限几何/参数/索引/路径/ZIP预算、原子active与revision CAS，坏包/中断/源变化不污染旧active；下载单包并重新打开后动画参数仍有效。
5. 纯JVM可移植网格混合器与动画/音频参数接口，和浏览器共用参数契约及资产预计算blendshape数据；同一组基准顶点/权重跨语言数值一致。资产文件是数据，不执行导入脚本。
6. TDD actual RED→GREEN、必要整体回归、独审C/H关闭、真实浏览器桌面/窄屏、重启重载与出错流程验证；最终运行说明与两交接更新。

## 兼容与真实边界

这是现有rig2d自研的二维网格角色，不输出Cubism专有.moc3。前视角小幅头动不能冒充原v2要求的±90真实侧脸、多视角身份或其发布门禁。新格式显式front_mesh/trueSideViews=false，与旧v2独立，不改变其阈值/测试或标为通过。用户原意的原生手机移植为后期范围；本轮先完成独立Live2D风格运行闭环，真实手机性能只在实际移植后声明。

自动补层基于原图内修补，遮挡后不存在的翼/发/嘴内细节需要用户确认或提供替换纹理，不能伪造原图。全双工另有真实音频验收，当前目标Live2D而不是冒称通话已经完成。

## 所有权和接口

- root：contract.json、studio/api.py与server、HTTP/安全/浏览器集成、Gradle与接收跨语言fixture、报告/交接/统一验证。
- asset agent：studio/assets.py、studio/storage.py及对应Python tests；先stub+tests交root真实RED，再GO。无Gradle/Android修改。
- runtime agent：studio/web/**及Node tests；纯函数/生命周期tests先stub actual RED，root GO后实现WebGL+编辑器。无资产/服务端/Gradle修改。
- portable agent：rig2d.planar纯JVM及tests，先stub compiled RED root GO，再实现；后独审其他owner，不代跑Gradle。

API统一：GET /api/health、/api/contract、/api/demo；POST /api/import {image:rawBase64,name}；GET /api/projects/{id} 返回snapshot {id,revision,width,height,name,definition,asset}。POST /api/projects/{id}/build {revision,definition}；POST /api/restore {package:rawBase64}；GET /api/projects/{id}/export下载ZIP；GET /api/projects/{id}/files/{path}只允许清单里的source.png/textures/ID.png。静态文件从studio/web提供，固定localhost，Origin/Host有界同源、JSON/文件大小限制，不读取Key/私库。

definition={anchors:{head:[x,y],neck:[x,y],eyeLeft:[x0,y0,x1,y1],eyeRight:[...],mouth:[...]},subjectMask:可选rawBase64 0/1(width*height),parts:[{id,name,role,z,polygon:[[x,y],...],mask:可选PNGrawBase64,pivot:[x,y],strength:0..1}]}。source透明alpha默认全部保留，不自动丢附属物；eye/mouth基于anchors生成可编辑动态层。资产asset={format,width,height,sourceSha,pixelSha,name,layers:[{id,name,role,z,texture,bounds:[x,y,w,h],vertices:[x,y,...],uv:[u,v,...],indices:[...],deltas:{parameter:[dx,dy,...]},phase,strength}],parameters,capabilities}；顶点为源工作图绝对像素XY，texture为纹理相对安全文件名，image/media URLs由server快照响应补为files路径。

动画只做vertices + sum(weight * precomputedDelta)，几何定义在资产单一来源；纹理静态，不在每帧执行编码/下载或模型推理。节点数/纹理预算遵循contract；neutral=全部参数default0，idle自动驱动可以关闭以验证源图。

已有FastAPI/Uvicorn/NumPy/Pillow/SciPy/OpenCV/Node复用，无云API/新权重/USB/真实录音/旧长测。研究先读取官方参数/WebGL/OpenCV文档，全部新源以命名空间分离，旧目录不改。
