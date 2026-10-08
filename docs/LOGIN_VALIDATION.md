# 0.1.3 真机登录验证 · 2026-10-08

本轮用户要求先覆盖新版再测试。手机红米 Turbo 3，Android 16/API 36，应用 `com.zhiyu.app`；0.1.2/code3 已覆盖升级到 0.1.3/code4，APK SHA `384acc828b4f8116dcd8af6adc87f711fa79dd88319919927b3e6b07fbb35c09`。

## 失败定位与修复

旧包与新 0.1.3 都在加载 AI 公司之前显示无法连接后端。当时手机 reverse 为空、宿主 8791 未监听，Docker Desktop 因 `sailor-ingest.sock` 无法访问启动失败；尚未发起 API Key 验证。

核验两个运行目录只含零字节 socket、父目录不是重解析目录、Docker 进程在已知安装目录后，保留旧目录为 `run.zhiyu-recovery-20261008-215813` 与 `docker-secrets-engine.zhiyu-recovery-20261008-215813`，重建空运行目录，引擎恢复。没有 reset、prune、删除卷或修改私人 RAG 目录；这次恢复不证明 Docker 的 Windows socket 问题永久消失。

部署媒体版后端时实际构建又因基础镜像已有 `/media` 而失败。Dockerfile 改为 `mkdir -p /data /media`，相同构建真实从 exit 1 变为 exit 0。原 Compose 后端升级到上述新镜像并使用原数据卷，另建独立素材卷。

`compose.test.yaml` 为后端配置 `unless-stopped`，当前已运行容器原位更新并核验配置，避免每次正常引擎启动后仍因默认 `no` 策略保持退出。未重启用户正在使用的服务来做故障测试；不能据此保证引擎自身故障或关机时仍可用。

## 真实手机验证

- 覆盖升级前后的 `companion_binding` 与连接密文完全一致，未解密或打印 Key；证据 `artifacts/login-phone-upgrade-before.json`、`login-phone-upgrade-after.json`。
- 冷状态原应用卷 3 文件/42,479,648 字节只读备份到 `artifacts/login-backend-backup-20261008/original-data.tar.gz`，源 SHA 前后相同。原身份文件 SHA `593400a80f1c7a3859ae10a6f599b3dd8ab699922e10d15427e9ddd9c687e32c` 在运行的新后端再次核验一致。
- USB 重接后用已有脚本只恢复这台手机 `tcp:8791 -> tcp:8791`，正常启动 Activity。App 使用自己原有的加密凭据恢复：`/api/conversations` 401 → 真实 `/api/auth/login` 200 → 原会话列表与 `/history?limit=100` 200。没有手工写 Key、修改本机 session 或降级 demo。
- `artifacts/login-phone-ready.png` 实际截图已查看，显示 DeepSeek 聊天及 OC 设置页面；登录阶段日志只保留路径/状态，不保留请求体、请求头或 Key。证据 `login-phone-http-events.log`。
- 原手机后端媒体卷实际 CLI 导入 6 张照片、8 张旧 Fluent 和 18 张 QQ 图，随后停用 Fluent；最终 active 图片 6、表情 18、inactive 旧表情 8。QQ 批次授权未确认，仅本机未发布试用，不入公共 Git、正式 APK 或可分发素材包。导入证据 `login-phone-media-seed-import.json`、`login-phone-qq-import.json`；来源许可见 [表情验证](STICKERS_VALIDATION.md)。

这是实际手机公司目录、保存 Key 正常重验、原会话与历史恢复以及媒体库接入验证。没有替用户发送测试消息，没有运行会清真实凭据的 instrumentation；此前后端 867 项和旧 Android 测试仍属于各自已冻结范围，本轮不重复计数。

## 完成界限

本次 USB 调试登录与镜像构建故障已修复。当前包仍依赖电脑在线、Docker 运行及 USB 映射，拔线后不能继续访问后端；独立手机交付需受信任 HTTPS 服务与长期托管。本轮未部署新公网入口。

审查另列出的恢复按钮只重载目录、旧人格迁移先联网、上游鉴权和会话 401 分类、旧地址迁移及自定义构建测试属于后续改进候选，本轮没有冒称完成这些客户端改动。负责恢复按钮的子任务曾因账号额度退出且未写入源码；最终使用实际已安装 0.1.3 完成正常登录。
