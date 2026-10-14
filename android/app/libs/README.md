# 手机角色动画核心

`rig2d-0.2.0-studio.jar` 来自用户提供的同级 `../rig2d` 项目，复用其纯 JVM `rig2d.planar` 网格、契约与混合器。它不是 Cubism SDK，不含 Python、网页编辑器、分割模型、语音模型或 Android 采集器。

- 字节数：275804。
- SHA-256：`0b0d161e52f182d3e58bba9baae30bb044458f2aab4fb395172cfcea3610e115`。
- 配套 `src/main/assets/planar/contract.json` SHA-256：`5e659b2e83b3ed72db2b3d9eb5d024c2e49cc2055f3e33979ee715a7a9489483`。
- `verifyPlanarCore` 在构建前验证两项 SHA；修改库时必须同步契约与回归，不能仅换文件。

源实现和桌面测试在 `../rig2d`。Android 自己负责包读取、Bitmap 图层制作、GLES 绘制和人工编辑，使用同一数值核心；原生隐藏区域填充不是桌面的 OpenCV Telea，因此原生导出声明独立的 `rig2d-planar-native-package-1` 格式，不宣称像素派生等价。
