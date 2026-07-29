# CodexQuota 开发与交付指南

本文是 `0.6.0` 本地候选的日常开发入口。产品取舍以根目录 `CONTEXT.md` 为准，Agent 工作规则以 `AGENTS.md` 为准，代码分层见 `docs/architecture.md`。

本指南覆盖 Windows、Android 与手环 RPK 三端。开始新的主任务前，先阅读
`docs/current-status.md`，其中列出临时版本差异和必须先由用户确认的跨端冲突。

## 开始前

```powershell
git status --short
Get-Content README.md -TotalCount 220
Get-Content CHANGELOG.md -TotalCount 120
```

涉及安全、构建或真机时，继续阅读 `docs/current-status.md`、`docs/security.md`、`docs/build-verification.md` 和
`docs/device-acceptance.md`。保留混合工作区中已有的修改、截图和未跟踪文件，不使用
`git reset --hard`、`git clean` 或无检查的 `git add -A`。

## 构建与测试

根目录历史组件和协议回溯：

```powershell
npm install
npm test
npm run test:plugin
npm run build:plugin
```

Windows 原生端：

```powershell
Set-Location windows-native
cargo test --workspace
cargo build --release --bin codex_quota_windows
.\scripts\build-installer.ps1
.\scripts\test-installer.ps1
```

`cargo build --release` 生成的是直接运行的候选 EXE，不会安装、更不会替换当前用户已安装程序；只有
`scripts\build-installer.ps1` 生成的 `Codex-Quota-Setup-*.exe` 才是可安装包。测试时可设置独立
`CARGO_TARGET_DIR`，将临时 EXE 与正在运行的托盘程序隔离。

托盘 EXE 正在运行时，使用独立临时目录执行测试，避免文件锁产生假失败：

```powershell
$env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA 'Temp\codex-quota-windows-test-target'
cargo test --workspace
```

Android：

```powershell
Set-Location android-app
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA 'codex-quota-dev\jdk-17'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
..\spikes\android-background-probe\gradlew.bat -p . :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

如需在没有真实 5 小时上游数据时检查 Android 排版，可在本地构建命令中临时加入
`-PcodexQuotaDemoFiveHour=true`。该参数只把手机界面显示为 68%，不会写入额度缓存或下发手环；
它默认关闭，正式候选必须在不带该参数的情况下重新构建。

手环 RPK：

```powershell
Set-Location band-app
npm install
npm run build
```

手环正式 RPK 必须使用与 Android release APK 相同的本机证书。先完成 `android-app/local.properties`
中的忽略签名配置，然后在 Windows 上执行：

```powershell
Set-Location band-app
.\scripts\prepare-release-signing.ps1
npm run build:release
```

该脚本只在被忽略的 `band-app/sign/release/` 写入派生 PEM，不会把私钥加入仓库。

## 变更后的最低验证

| 变更范围 | 自动验证 | 必要的真机确认 |
| --- | --- | --- |
| Windows Hook、任务、额度 | `cargo test --workspace` | 实际 Hook、任务标题、状态归并 |
| WSS、配对、安全 | Windows 测试 + Android 协议测试 | 二维码配对、重连和局域网变化 |
| Android UI/任务板 | Android 单测、lint、assemble | 三页布局、竖向滚动、移除二次确认 |
| 通知 | 通知/策略单测 | ChatGPT 失焦、后台/锁屏、通知栏和手环震动 |
| Wearable/RPK | Android 构建 + RPK 构建测试 | 小米运动健康连接保持、手环页面可读 |
| 安装器 | 构建脚本 + 安装器 smoke test | 当前用户安装、启动和卸载 |

自动测试不能替代用户验收。用户已于 2026-07-29 确认当前 Windows、Android 与手环候选均通过验收；
后续新增或改动的功能仍必须重新说明其真机验证范围。

## 交付边界

- 当前 `0.6.0` 只生成本地候选包；不要把本地候选写成已发布版本。
- Windows、Android APK、手环 RPK 的产品版本必须一致；协议版本单独维护在 `contract/`。
- 手环 UI 修改必须先给用户看 `212×520` 预览，确认后才改 RPK 源码。
- 用户已确认 `0.6.0` 三端候选验收通过；但提交、推送和 GitHub Release 仍须由用户另行要求。发布前展示版本、改动、测试、产物和 SHA-256。
- Debug APK/RPK 仅用于开发和真机验证；正式产物应使用固定发布签名，私钥不得进入仓库。

## 文档职责

- `CONTEXT.md`：产品范围、用户决策、隐私红线和已确认语义。
- `AGENTS.md`：未来 AI Agent 的工作规则和快速命令。
- `docs/architecture.md`：当前代码架构、协议和数据流。
- `docs/build-verification.md`：实际构建、测试和产物证据。
- `docs/device-acceptance.md`：公开的设备验收结论。
- `CHANGELOG.md`：用户可见的已发布变化；本地候选未获发布授权前不要把它当作正式发行说明。
