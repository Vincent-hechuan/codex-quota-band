# CodexQuota 项目 Agent 规范

本文件是给未来 AI Agent 的工作入口。开始任何修改前先读本文件；需要了解产品取舍时再读
`CONTEXT.md` 和 `docs/current-status.md`，需要了解实现细节时读 `docs/architecture.md`，需要执行构建或验收时读
`docs/development-guide.md`、`docs/build-verification.md` 和 `docs/device-acceptance.md`。

## 1. 项目定位与当前版本

CodexQuota 是一个 Android + Windows + 小米手环 10 的本地额度和任务状态看板：

`ChatGPT Windows Hook → Windows 原生托盘程序 → 局域网加密同步 → Android Codex额度 → 小米运动健康/Wearable SDK → 手环 RPK`

- 当前产品版本：`0.6.1`，包含手机端手环连接状态的异步刷新修复；Android 内部安装序号为 `602`，用于在不丢失本地数据的情况下覆盖此前候选。
- 三端当前验收状态、临时版本差异和未决冲突以 `docs/current-status.md` 为唯一摘要；不要从单端
  构建记录推断三端已经一致。
- 新架构只面向 Android；不为 iPhone 增加兼容层，也不把旧 AstroBox 桥接重新放回日常链路。
- AstroBox 只在首次安装或升级手环 RPK 时临时使用；日常手环连接、健康同步和普通手机通知由小米运动健康保持。
- Windows、Android APK、手环 RPK 的产品版本号必须一致；协议兼容性由协议版本判断，不能只看产品版本。

完整产品决策以 `CONTEXT.md` 为准；若新需求与其冲突，先向用户确认，不要自行扩大范围。

## 2. 不可违反的产品与隐私边界

- 只传输额度摘要、重置信息、连接状态、同步时间和经过裁剪的任务状态/短标题。
- 不读取或传输提示词、回复、工具参数、命令、文件路径、完整日志、Cookie、密码或账号内容。唯一例外是：Windows 可在本机读取 Codex 访问令牌，仅向官方额度接口发起低频确认；令牌只驻留进程内存，不写入日志、缓存、诊断或局域网同步，也不传给 Android 或手环。
- 任务状态只来自已验证的官方 Hook：`PreToolUse/UserPromptSubmit → 处理中`、
  `PermissionRequest → 需要授权`、`Stop → 等待查看`。不要把“等待查看”改写成“已完成”。
- Android 任务移除是本机任务板隐藏，不删除 ChatGPT 对话；任务出现新活动后允许自动恢复。
- 默认通知时机是“仅在 ChatGPT 失焦时”；处理中静默，等待查看和需要授权可提醒；手机和手环开关独立。
- 不承诺 Android 被系统杀死后通知必达，不新增常驻前台服务或状态栏通知来掩盖后台限制。
- 默认只在可信局域网运行，不新增云端中转、公网暴露、遥测、广告或自动崩溃上报。
- 不把缓存冒充实时数据，不猜测缺失的额度、重置次数或重置后的百分比。

## 3. 代码边界与关键目录

| 目录 | 责任 | 主要技术 |
| --- | --- | --- |
| `windows-native/` | Windows 托盘、Hook、额度采集、配对、TLS 1.3 WSS `/pair`/`/sync` | Rust 2024、Tokio、Rustls、Windows API |
| `android-app/` | 手机看板、配对客户端、WSS 重连、通知决策、手环桥接 | Kotlin、Jetpack Compose、Android SDK、Xiaomi Wearable SDK |
| `band-app/` | 小米手环 10 快应用和 212×520 页面 | Vela/AIoT UX、JavaScript |
| `contract/` | 配对、额度、任务、同步流 JSON 契约 | JSON Schema |
| `docs/` | 架构、ADR、安全、构建和真机验收证据 | Markdown |
| `src/`、`astrobox-plugin/` | 0.4.0 以前的 Electron/AstroBox 历史实现 | legacy，只作回溯，不进入新架构主流程 |

Android 运行时的主要入口是 `CodexQuotaApplication`、`SyncWebSocketClient`、
`SyncStreamSession`、`RuntimeStateRepository`、`TaskAlertCoordinator` 和
`XiaomiWearableBridge`。Windows 的托盘入口是
`windows-native/src/bin/codex_quota_windows.rs`；Hook 归并和标题索引逻辑在
`windows-native/src/hook.rs`。手环状态公共逻辑在 `band-app/src/common/`。

## 4. 开始任务时的固定流程

1. 在仓库根目录执行 `git status --short`，保留所有用户已有的修改和未跟踪文件。
2. 阅读 `README.md`、`CHANGELOG.md`；涉及安全、构建或设备时补读对应文档。
3. 先定位现有实现和测试，再决定最小修改范围。不要为“看起来更规范”顺手重写无关模块。
4. 涉及手环 UI 时，先给用户看 212×520 预览；只有用户确认后才能改 RPK 源码和正式构建。
5. 先写/补测试，再实现；变更完成后运行与变更直接相关的自动测试，并说明真机仍需验证的部分。
6. 交付时说明改动、测试、真机状态、产物和 Git/发布状态。用户明确说“验收通过”之前不得提交、推送、创建 Release 或上传构建产物。

## 5. 常用命令

根目录历史/通用测试：

```powershell
npm install
npm test
npm run test:plugin
npm run build:plugin
```

Windows 原生：

```powershell
Set-Location windows-native
cargo test --workspace
.\scripts\build-installer.ps1
.\scripts\test-installer.ps1
```

正在运行托盘程序时，使用独立 `CARGO_TARGET_DIR` 做测试，避免 EXE 文件锁造成假失败。

Android（PowerShell）：

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA 'codex-quota-dev\jdk-17'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
..\spikes\android-background-probe\gradlew.bat -p . :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

手环：

```powershell
Set-Location band-app
npm install
npm run build
```

版本、产物 SHA-256 和最近验证结果以 `docs/build-verification.md` 为准；不要把截图里的实时额度当成测试固定值。

## 6. 真机验收和发布规则

- 自动测试不能代替 Windows、Android、手环三端真机验收。
- 重点验收：首次二维码配对、Hook 事件和任务标题、失焦通知、锁屏/后台连接、手环提醒、离线缓存、重连、任务本机移除、RPK 页面可读性。
- 用户已配置应用加锁、自启动和电池无限制；测试应使用短时、可重复的场景，不擅自安排长时间测试。
- 预发布包留在本地；只有用户明确回复“验收通过”后，才可以进入提交、推送和 GitHub Release 流程。
- 用户明确要求对本地候选进行真机验收时，Agent 负责覆盖安装 Windows 安装包和 Android APK，并将对应 RPK 复制到已通过 ADB 连接的手机 `Download` 目录；手环 RPK 的实际安装仍由用户通过 AstroBox 完成。除非用户另有说明，后续候选沿用此交付方式。

## 7. 处理不确定性

- 不要根据旧对话猜测新的产品语义；如果会改变用户体验、数据边界、协议或日常连接方式，先询问。
- 不要删除、重置或覆盖混合工作区中的文件。删除行为必须有明确范围和可恢复性说明。
- 发现文档与代码不一致时，优先修正文档中的事实描述；若代码才是错误来源，先报告再实现修复。
