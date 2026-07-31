# Codex额度 Windows 原生核心

这是 0.6.1 原生 Windows 端的本地候选实现。托盘、额度采集、加密局域网服务、配对持久化和只读任务 Hook 已接入；本补丁版本仍未发布。

## 当前能力

- 将官方 Hook 的 `UserPromptSubmit`、`PermissionRequest` 和 `Stop` 分别归并为
  `running`、`needs_authorization` 和 `waiting_for_review`。
- 安装器会自动写入或修复任务 Hook；托盘也提供「安装/修复任务 Hook」，始终以不覆盖其他用户 Hook 的方式合并到用户配置。
- 托盘使用项目自己的高对比 Codex 图标，不使用 Windows 系统信息提示图标。
- Hook 写入后，建议重启 ChatGPT 桌面端；在「ChatGPT → 设置 → 钩子 → 信任全部钩子」中确认 `PreToolUse`、`PermissionRequest`、`UserPromptSubmit` 和 `Stop`。`/hooks` 仅用于备用排障。
- 使用 `session_id` 将后续轮次归并到同一任务。
- 配对窗口只展示二维码和有效期；二维码内部仍包含一次性验证码，Android 扫码后自动提交，用户不需要手动输入验证码或局域网地址。
- 只输出白名单活动摘要，不传输工具参数、命令、路径、输出或完整日志。
- 使用本地 `session_index.jsonl` 中的 `thread_name` 解析任务标题；标题缺失时暂显示“任务”，
  后续索引刷新或 Hook 事件到达后可补齐。原始 prompt 不进入任务缓存或序列化结果。
- 手机看板保留全部处理中/需要授权任务和最近 10 条等待查看；手环按优先级保留 3 条。
- 支持只影响本地看板的隐藏、等待查看记录删除和清空；隐藏任务有新活动时自动恢复。
- 实现已确认的通知时机、通知类型、手机/手环渠道、前台抑制和重连抑制规则。
- 输出与 Android 严格解析器一致的额度 Snapshot v1、任务 Task Sync v1 和加密同步流
  v1 白名单消息；已接入 TLS 1.3 WebSocket `/pair` 与 `/sync` 服务，支持一次性短码、
  Bearer 令牌鉴权、版本协商、快照和心跳。
- 生成可持久化的 Windows TLS 身份密钥，并从公钥计算稳定 SHA-256 指纹；重启后从
  私钥恢复同一身份，非法密钥会被拒绝。
- TLS 私钥可使用 Windows DPAPI 绑定到当前 Windows 用户后再落盘；磁盘文件不是可直接
  导入的明文私钥，解密后的系统缓冲区会在释放前清零。
- 从持久身份构造仅启用 TLS 1.3 的 Rustls 服务配置，并声明 WebSocket 所需的
  HTTP/1.1 ALPN。网络监听和连接生命周期已经由原生服务层负责；DPAPI 身份文件存储、
  Windows 托盘启动、真实额度采集和安装器候选包均已接入。用户授权的三端真机验收已完成；
  提交、推送和发布仍须用户另行明确授权。

## 本地测试

本项目使用 Rust GNU-LLVM Windows 工具链，以避免依赖 Visual Studio 或浏览器运行时。

```powershell
Set-Location windows-native
cargo test
```

`cargo test` 还包含一个真实 TLS/WebSocket 往返测试；另有仅用于本地验收的
`dev_wss_probe`，会生成一次性配对深链并等待 Android 真机完成 `PAIR_OK`/`SYNC_OK`。
这不能替代 Hook 安装、真实 ChatGPT 事件、长期后台存活或手环通知的真机验收。
