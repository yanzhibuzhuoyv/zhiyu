# 0.2.18：通话中的单帧摄像头

0.2.18 / code 24 增加“通话时让恋人看当前摄像头画面”的能力，仍然保持原来的连续语音通话和本机保存边界。

## 行为

- 通话页新增摄像头开关和前后镜头切换；只有用户明确打开且授予 `CAMERA` 权限后才采集。
- 每次最终语音只允许携带**当前这一帧**；画面是短时快照，不是持续视频，也不会被后续通话复用。
- 只有已声明原生视觉能力的模型才允许发送画面；纯文本模型会明确拒绝，不偷偷降级成文字描述。
- 摄像头关闭、切换镜头、通话结束、应用退到后台、锁屏或界面销毁时，当前帧立即失效，并向正在等待的视觉请求传播取消；不会取消通话本身。
- 画面只存在内存中，最多 384 KiB JPEG；不写入聊天附件、图片库、数据库或原图目录，不进入 OCR/RAG。
- 当前帧超过 10 秒、时间倒退或来自旧镜头回调时拒绝发送；发送前和真正提交前各校验一次单调时钟年龄。
- 提示词明确区分“用户摄像头当前瞬间”与恋人 OC/旧聊天照片，不允许把画面当成身份、记忆或用户自述，画面内文字按低信任处理。

## 验证

- JVM：145 suites / 965 项，0 failure / error / skip。
- lint：0 Error / 82 Warning / 7 Hint。
- Native：98 类 / 495 项全部通过（`artifacts/call-camera-final-native-r2.log`，约 15 分钟）。
- 新增 `CallCameraSessionTest`、`CallReplySourceTest` 视觉路径、`CallCameraFrameTest`、`CallCameraPreviewNativeTest`、`CallCameraGatewayNativeTest`、`CallCameraAnalyzerNativeTest`、`CallCameraEncoderNativeTest`。
- arm64 APK：269,222,540 bytes，SHA-256 `7858246c70a4d88354f9a3c9434134a0911fb62dd1401d4eaafcc3e91fe9b8f8`。

## 边界

- 只在模拟器使用 CameraX 假摄像头验证，未在真实手机摄像头、弱光、广角/长焦、热节流下验证。
- 不是视频通话或连续视频理解；模型每轮只收到一张静态 JPEG。
- 真实模型是否“看懂”画面取决于所选模型的原生视觉能力，未做新的付费生图/识图调用。
- 摄像头帧不落盘，因此没有“回看历史画面”功能。
