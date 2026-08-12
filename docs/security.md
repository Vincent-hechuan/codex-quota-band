# 安全说明

## 当前 Android 新架构（始于 0.5.2）

当前架构不沿用旧 AstroBox 架构的明文 HTTP/Bearer 传输。实现要求 Windows 与 Android
首次配对后固定 Windows 身份，配对和日常同步都通过 TLS 1.3 加密连接完成；局域网发现只提供候选
地址，不能建立信任。具体决策见 `docs/adr-003-encrypted-lan-pairing.md` 和
`docs/adr-004-tls-sync-stream.md`。

加密同步流只允许额度 Snapshot v1 与任务 Task Sync v1 白名单字段。连接标识和连接内单调
序列用于拒绝旧连接、重放与乱序消息；协议不兼容时停止同步，不得回退到明文端点。
当前已完成协议、两端严格解析/序列化、Android 会话顺序保护、Windows TLS 1.3 WebSocket
服务和 Android 自动重连客户端。2026-07-24 已用 Android 真机完成一次性配对与真实 WSS
往返验证；Windows 原生主程序从 ChatGPT/Codex Chromium 缓存读取 `/wham/usage`，并使用本机
`.codex/auth.json` 中的 access token 低频查询官方额度接口。令牌仅用于 Windows
内存中的敏感授权头，使用后清零；不写入日志、缓存、诊断、二维码、手机或手环，也不经局域网同步；401 仅归类为凭证不可用。随后已完成手机后台/锁屏、
通知和手环提醒的阶段性真机验收。

手环日常链路同样由 Android 端负责：Android 使用小米官方 Wearable SDK 查询连接状态，并
通过 `MessageApi` 向已安装且签名匹配的 RPK 发送额度摘要。AstroBox 只负责首次侧载或升级
RPK，不保存 Android 配对令牌，也不参与日常消息转发。手环请求只允许 `quota_request` 和
额度快照/错误响应，未知消息、控制指令和原始任务内容都会被忽略。

以下网络边界和已知限制中，标明“历史架构”的内容不适用于当前 Android 新架构。

## 数据最小化

跨设备载荷只允许协商的 Snapshot 白名单字段：协议版本、生成时间、额度窗口、Full reset 数量、发卡/到期时间和链路状态。v1 不含发卡时间；仅当 Android 明确协商 quota v2 时，Windows 才发送不含卡片 ID、标题和描述的 `grantedAt`/`expiresAt`。解析器与手环各执行一次白名单裁剪。

严禁进入载荷或日志的数据包括：对话、提示词、模型输出、项目名称/路径/文件、终端输出、Codex 完整会话、ChatGPT Cookie、访问令牌、刷新令牌和账号资料。

## 网络边界

- Windows 服务只接受回环、RFC1918、IPv4 链路本地、IPv6 ULA/链路本地来源。
- 未携带有效 Bearer 凭据的快照请求返回 401。
- 6 位配对码有效 5 分钟，最多尝试 3 次，成功后立即失效。
- 当前扫码引导使用 Android `codexquota://pair` DeepLink；载荷只允许协议版本、固定类型、1–8 个私网数字 IPv4 WSS 地址和 6 位码，拒绝未知字段、公网地址、域名、路径与额外凭据。旧版 AstroBox `plugdata` 仅属于历史架构。
- 二维码不包含长期 Bearer 令牌或 ChatGPT/Codex 登录凭据。DeepLink 即使被其他本机应用观察到，也只暴露与屏幕手动配对等价、5 分钟且单次有效的临时材料。
- 手机内置扫码只识别 `codexquota://pair`，完整载荷仍由严格协议解析器复核；相机画面只在本机实时分析，不保存、不上传。
- 手动模式的 UDP 局域网公告只包含协议版本、电脑公钥指纹、私网 WSS 候选地址和过期时间，不包含 6 位配对码、长期令牌或账号内容。手机必须先让用户核对由公钥指纹生成的 `XXXX-XXXX` 安全校验码，确认一致后才提交配对码。
- 长期令牌由 32 字节系统随机数生成；Windows 只持久化 SHA-256 哈希，可从托盘全部撤销。
- 请求体限制为 4 KiB，Snapshot 响应在手机桥接层限制为 64 KiB。
- 日志只记录时间、事件名与 HTTP 状态码，不记录配对码、令牌、请求体或完整快照。

## 已知限制

- 局域网链路使用 TLS 1.3 WSS；MVP 仍要求可信局域网，不提供公网中继或不受信任网络穿透。
- 当前 Windows 安装包未代码签名，公开测试版可能触发 Windows「未知发布者」提示。用户应只从本仓库 Releases 下载并核对 SHA-256；正式大规模分发前应增加可信代码签名。
- 旧版 AstroBox API 3 未提供 Android Keystore/StrongBox 凭据接口；该限制只影响 0.3.x 历史插件。当前 Android 应用使用 Android Keystore 保存配对凭据，日常不依赖 AstroBox。
- Vela 官方构建工具链存在仅限开发依赖的 npm 审计告警；RPK 生产包不包含 node_modules，`npm audit --omit=dev` 为 0。

## 撤销与恢复

Windows 托盘中的“撤销所有已配对设备”会清空全部长期令牌哈希。手机丢失、插件包被复制或局域网凭据疑似泄露时，应立即撤销并重新配对。
