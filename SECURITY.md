# 安全报告

CodexQuota 处理本地额度摘要、配对凭据和 Windows 本机的额度确认。完整的安全模型与数据边界见 [docs/security.md](docs/security.md)。

请不要在公开 Issue、截图、日志或讨论中披露访问令牌、Cookie、密码、配对码、完整提示词、回复、文件路径或账户资料。

## 报告方式

本仓库公开发布前，维护者应在 GitHub 仓库的 **Security** 设置中启用私密漏洞报告（Private vulnerability reporting）。启用后，请使用该私密渠道报告可复现的安全问题；不要先公开披露。

报告应包含受影响版本、最小复现步骤、影响范围，以及已脱敏的必要证据。请不要附带任何真实凭据或个人数据。

## 范围

- Windows 本机访问令牌仅用于直连官方额度接口确认额度；不得进入日志、缓存、诊断、局域网同步、Android 或手环。
- Windows 与 Android 的日常同步使用固定身份的 TLS 1.3 WebSocket，仅接受可信局域网来源。
- 手环只接收经过白名单裁剪的额度和任务摘要。
