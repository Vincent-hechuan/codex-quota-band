# 构建与验证记录

> 本文件按时间保留构建、产物和验证证据。三端当前候选、版本差异和未决项请先看
> [current-status.md](current-status.md)，不要从单个“通过”条目推断已经完成联合验收或可以发布。

> 2026-07-29 用户已确认 Windows、Android 与当前手环候选均验收通过。此确认不改变本文件中的
> 构建证据，也不自动执行提交、推送或 GitHub Release；正式发布前仍需完成三端产品版本统一。

## 0.5.2 正式本地候选构建（2026-07-29）

- **版本升级原因**：修复手环首页“可用重置”数值错误继承周额度颜色的问题。可用重置大于零应固定为绿色，零次应为红色；`0.5.1` 已安装，故三端统一升级到 `0.5.2 / versionCode 502`，使 RPK 能以正常升级方式安装。
- **统一版本**：Windows Cargo、Android `versionName` 与手环 `versionName` 均为 `0.5.2`；Android 与手环 `versionCode` 均为 `502`。根目录跨端版本契约测试 2/2 通过。
- **Windows**：`cargo test --workspace` 70/70 通过；安装器为 `windows-native/dist/Codex-Quota-Setup-0.5.2.exe`（2,937,894 bytes，SHA-256 `927E0E15B44BD0FE2223487098F75E2F856D66C3E722E2513388178121B679F2`）。未使用商业 Windows 代码签名。
- **Android**：`:app:testDebugUnitTest`、`:app:lintDebug` 和 `:app:assembleRelease` 通过；release APK 为 `android-app/app/build/outputs/apk/release/app-release.apk`（1,764,942 bytes，SHA-256 `1064E8008BAC936DE83A47D4857EBD8A55CFA4982331F331336A980D9BB2F35C`）。`adb` 已确认验收手机覆盖升级到 `versionCode=502`、`versionName=0.5.2`；release 签名沿用本机正式身份。
- **手环**：`npm run build:release` 成功，构建后 24/24 测试通过（含可用重置独立颜色回归测试）；RPK 为 `band-app/dist/com.codex.quota.android.release.0.5.2.rpk`（49,571 bytes，SHA-256 `639659D745B34354D0BA554C50B1665F81E0E349B18147D6A2D163B0387EDB5B`）。manifest 已确认包名 `com.codex.quota.android`、`versionName=0.5.2`、`versionCode=502`；沿用与 Android release APK 匹配的本机发布签名。
- **真机验收**：Windows 安装器、Android release APK 与手环 RPK 均已正常升级；已有配对与同步正常。手环已确认可用重置大于零保持绿色。零次为红色由自动化回归测试覆盖。未提交、推送或发布。

## 0.5.1 正式本地候选构建（2026-07-29）

- **版本升级原因**：`0.5.0` 三端正式包已验收，但随后修复手环错误码 `1001` 中遗留的“需要 AstroBox”提示。AstroBox 不覆盖相同 `versionCode` 的 RPK，故三端统一升级到 `0.5.1 / versionCode 501`，使所有用户都能正常更新。
- **统一版本**：Windows Cargo、Android `versionName` 与手环 `versionName` 均为 `0.5.1`；Android 与手环 `versionCode` 均为 `501`。根目录跨端版本契约测试 2/2 通过。
- **Windows**：`cargo test --workspace` 70/70 通过；安装器为 `windows-native/dist/Codex-Quota-Setup-0.5.1.exe`（2,938,212 bytes，SHA-256 `C945B1F9C1428068793B1564C4F5D9CB6D1084C5A840B17F6E78AF655CF63D01`）。未使用商业 Windows 代码签名。
- **Android**：`:app:testDebugUnitTest` 86/86、`:app:lintDebug` 和 `:app:assembleRelease` 通过；release APK 为 `android-app/app/build/outputs/apk/release/app-release.apk`（1,764,942 bytes，SHA-256 `F23A71800B3EBBAA4FAE336FC317EC4771A8218354A4984F1F7739D9FE88A3FA`）。`adb` 已确认验收手机覆盖升级到 `versionCode=501`、`versionName=0.5.1`；`apksigner` 验证 APK Signature Scheme v2，证书 SHA-256 仍为 `E1F89EB5F025F61AD8F2209E5101A4D8B44BC31517C1A8FE25D5DA67652C2D7E`。
- **手环**：`npm run build:release` 成功，构建后 23/23 测试通过；RPK 为 `band-app/dist/com.codex.quota.android.release.0.5.1.rpk`（49,517 bytes，SHA-256 `147EF15BFCA904DC488C3A2679D33ECEAB48FC1581DCCFCA70B80647734B0CBF`）。manifest 已确认包名 `com.codex.quota.android`、`versionName=0.5.1`、`versionCode=501`；沿用与 Android release APK 匹配的本机发布签名。
- **待验收**：Windows 安装器与手环 RPK 的正常升级、已有配对和手环同步复验。未提交、推送或发布。

## 0.5.0 正式本地候选构建（2026-07-29）

- **统一版本**：Windows Cargo、Android `versionName`、手环 `versionName` 均为 `0.5.0`；Android 与手环 `versionCode` 均为 `500`。根目录跨端版本契约测试 2/2 通过。
- **Windows**：`cargo test --workspace` 70/70 通过；安装器为 `windows-native/dist/Codex-Quota-Setup-0.5.0.exe`（2,935,984 bytes，SHA-256 `B3CFA6C5EF39F3628DCF9ADC23CBCE7588FDB663F6F056643491C73E3CFA00EA`）。未使用商业 Windows 代码签名。
- **Android**：新增手环重新授权入口与节点发现修正后，`:app:testDebugUnitTest` 86/86、`:app:lintDebug` 和 `:app:assembleRelease` 通过；release APK 为 `android-app/app/build/outputs/apk/release/app-release.apk`（1,764,942 bytes，SHA-256 `43502DDFBB6E61BCC5EE8542586D5DA4258751BE4D76C896C3959D979AA87EDF`）。`aapt` 确认 `versionCode=500`、`versionName=0.5.0`；`apksigner` 验证 APK Signature Scheme v2，签名主体为 `CN=Vincent, OU=CodexQuota, O=Vincent, C=CN`，RSA 4096。密钥及密码只保存在本机的忽略路径，不在仓库或本文件中记录。
- **手环**：旧 debug RPK 使用 Android debug 证书，不能与新的 Android release APK 建立 Wearable 数据通道；已新增本机 `scripts/prepare-release-signing.ps1`，从同一份忽略的 Android release 身份派生忽略的 `sign/release/` PEM 材料。最初 release RPK（49,520 bytes，SHA-256 `DDA003B4EEE2E9A25B619D43B70B8FF3E3CB6FAE084A3B5E373D48FD4E7BE063`）已在真机完成重新授权和同步验收。随后修复了错误码 `1001` 中残留的“需要 AstroBox”文案；最新 `npm run build:release` 构建和 23/23 测试通过，RPK 为 `band-app/dist/com.codex.quota.android.release.0.5.0.rpk`（49,508 bytes，SHA-256 `BF008081C53AE234E1A5D29CA501ED6B9A8040438AADBAA417E7DCB5795C6B56`），待覆盖导入确认。构建日志确认小米工具链使用该 release 证书；其 SHA-256 为 `E1F89EB5F025F61AD8F2209E5101A4D8B44BC31517C1A8FE25D5DA67652C2D7E`，与 APK 证书一致。
- **已完成真机步骤**：Android 已卸载旧 debug APK 并安装本 release APK；Windows 已安装本正式安装器；两端已于 2026-07-29 重新扫码配对成功。开始菜单中发现的旧错误编码快捷方式仅是 2026-07-25 遗留目录，已移至回收站；本次安装器写入的“Codex额度”快捷方式正常。
- **真机验收**：Windows 正式包与 Android release APK 已重装、扫码配对；用户于 2026-07-29 通过 AstroBox 导入最初的 release RPK，在 Android 设置页点击“检查手环连接”后获得“手环已授权，正在同步”，并确认手环最终成功同步。两页纵向 swiper、额度与重置显示此前已确认正常。最新 RPK 尚待一次覆盖导入确认。未提交、推送或发布。

## 0.5.0-rc.1 三端统一候选构建（2026-07-29）

本轮只构建并自动验证本地候选，未安装到 Windows、Android 或手环，未提交、推送或发布。Windows 构建使用独立 `CARGO_TARGET_DIR`，避免影响正在运行的托盘程序。

- **统一版本**：Windows Cargo、Android `versionName`、手环 `versionName` 均为 `0.5.0-rc.1`；Android 与手环 `versionCode` 均为 `500`。根目录跨端版本契约测试 2/2 通过。
- **Windows**：`cargo test --workspace` 68/68 通过；`scripts/build-installer.ps1` 成功生成 `windows-native/dist/Codex-Quota-Setup-0.5.0-rc.1.exe`（2,931,106 bytes，SHA-256 `DB56971D36ADEC5FF38B863D343E3B7BB0D0F80C7B59EB052A0BBE34E582E9A4`）。本轮未运行会临时安装的 installer smoke test。
- **Android**：`:app:testDebugUnitTest` 83/83 通过，`:app:lintDebug` 与 `:app:assembleDebug` 通过；`android-app/app/build/outputs/apk/debug/app-debug.apk` 为 19,858,852 bytes，SHA-256 `0B10DE3A78E3E1D9F8A51573598CC0D86AFC230D2A0E42FECB2FDF229BF73433`。`aapt dump badging` 已确认包名 `com.codex.quota.android`、`versionCode=500`、`versionName=0.5.0-rc.1`。
- **手环**：`band-app/npm run build` 成功，构建后 22/22 页面、状态、版本与分页测试通过；产物为 `band-app/dist/com.codex.quota.android.debug.0.5.0-rc.1.rpk`（51,068 bytes，SHA-256 `41A61B2D9C67D6EB9C4C728ADA87723FFDB8C23E804A124ECBBF678E45FF38D2`）。构建后的 manifest 已确认 `versionName=0.5.0-rc.1`、`versionCode=500`。

## 0.5.0-rc.1 Android 自动刷新与手环缓存语义修正（2026-07-29）

- 根因：自动刷新在 WebSocket 尚未完成协商或正在重连时会发送失败，但旧计时器仍将 45 秒窗口清零；而手环摘要只折叠了上游状态，没有将超过 60 秒的 `current` 确认降级为缓存，可能把旧额度显示为“已同步”。
- 修正：Android 仅在刷新请求实际写入已认证 WSS 后重新计时；请求未就绪时保留到期状态并在下一个 5 秒检查重试。Android 发给手环的脱敏摘要也按同一 60 秒规则降为 `partial`/“缓存”，不传输上游时间、错误详情或凭证。
- 自动验证：新增两条回归测试后，Android `:app:testDebugUnitTest` 85/85、`:app:lintDebug`、`:app:assembleDebug` 全部通过。
- 产物：`android-app/app/build/outputs/apk/debug/app-debug.apk`，19,910,563 bytes，SHA-256 `95DF012A72CB4A58BDB9DAE6805AE6702F2CE517021BFC92AC95DBB6E8213BA2`。已通过 USB 覆盖安装到授权设备 `c3f86dd8`，包版本确认 `0.5.0-rc.1 / versionCode 500`。
- 真机验收：前台连续 2 分钟可自行保持“已同步”；后台锁屏 2 分钟时，在未开启自启动/电池无限制时正确显示“缓存 1 分”，开启自启动和“不限制电池使用”后可保持同步。该差异符合“不新增前台服务、不承诺被系统杀死后后台持续运行”的产品边界。

### 本轮构建脚本修正

- Windows `scripts/build-installer.ps1` 现读取 `CARGO_TARGET_DIR`，以便在托盘程序运行时把候选二进制构建到独立目录；安装器产物仍写入项目 `dist/`。
- Windows 安装器接受标准 `major.minor.patch-prerelease` 版本，但将 Windows `VIProductVersion` 保持为所需的纯数字四段版本。`scripts/test-installer.ps1` 不再硬编码旧安装包名，而是从 `Cargo.toml` 读取当前版本。

## 0.5.0-rc.1 Windows 上游确认诊断候选（2026-07-29）

- 真机升级后，Windows 与 Android 已配对连接，但用户点击「立即确认」后额度源仍为“使用缓存”。本机无凭证探针确认 `auth.json` 存在、ChatGPT 额度端点可达（无凭证请求按预期返回 401），且确认尝试时间更新而额度缓存不更新；故障点已缩小到 Windows 本机凭证查询额度的阶段。
- 修正：Windows 连接诊断现在仅在用户显式点击「立即确认」后显示裁剪的本地错误码和修复方向：`AUTH_UNAVAILABLE`、`AUTH_REJECTED`、`NETWORK`、`RESPONSE_FORMAT`、`UPSTREAM_HTTP` 或 `LOCAL_WRITE`。错误码不含令牌、请求头、响应体、URL 参数或上游错误详情，也不进入 Android/手环同步载荷。
- 回归验证：新增安全错误码和诊断文案测试后，Windows `cargo test --workspace` 70/70 通过。新安装器为 `windows-native/dist/Codex-Quota-Setup-0.5.0-rc.1.exe`（2,931,955 bytes，SHA-256 `E9F05963E2CEA1E4F6CDE5B304363F7D8BAA8C3900F70BB36EC5ADF7B04C4990`）；尚待用户覆盖安装与真机确认。

## 0.5.0-rc.1 Windows 系统代理修正（2026-07-29）

- 根因确认：环境中配置了 HTTP/HTTPS 代理；经代理的无凭证额度探针返回预期 401，而强制直连在 8 秒超时。Windows 使用 `reqwest` 时关闭了默认特性且未启用 `system-proxy`，导致原生额度请求绕过用户已配置的代理并显示 `NETWORK`。
- 修正：为现有 Rustls reqwest 客户端启用 `system-proxy`，只复用 Windows 已配置的网络出口；不读取、记录或同步代理地址、凭证、令牌、响应内容或其他敏感数据。
- 回归验证：Windows `cargo test --workspace` 70/70 通过，重建安装器为 `windows-native/dist/Codex-Quota-Setup-0.5.0-rc.1.exe`（2,936,224 bytes，SHA-256 `C323915AA26041B105215936043F5A9215B3965A7C210868EF65C8DAD42FAA67`）。尚待用户覆盖安装和真机上游确认。

## 0.5.0-rc.1 Windows 文案统一复建（2026-07-29）

- 修正：Windows 连接诊断的额度新鲜度状态统一为“已同步 / 缓存 / 待同步”，与 Android 和手环一致；首次 Hook 信任引导统一指向“ChatGPT → 设置 → 钩子 → 信任全部钩子”。
- 回归验证：Windows `cargo test --workspace` 70/70 通过，其中诊断状态文案回归测试通过；根目录跨端版本契约测试 2/2 通过。
- 新安装器：`windows-native/dist/Codex-Quota-Setup-0.5.0-rc.1.exe`（2,936,128 bytes，SHA-256 `DD6C74049EB6F3B914B4FFC0EEF46FFB86690722FE452FC1FA1A7C08D4B72EB0`）。尚待覆盖安装后确认诊断窗口文案。

## 0.4.0 Windows UI 与新手流程文档同步（2026-07-29）

本节只同步当前已验证的 Windows 行为和交付信息，本次文档更新没有重新构建、安装、提交或发布。

- 当前候选版本：Windows 原生 0.4.0；已安装路径为
  %LOCALAPPDATA%\Programs\CodexQuota\CodexQuota.exe。
- 当前本地安装器：
  windows-native/dist/Codex-Quota-Setup-0.4.0.exe；
  最近一次成功构建的 SHA-256 为
  AB8C37A152210FE60950761BEB94F85E09B18BE406317003186BBFDDC84B6C13。
- Windows 构建命令：在 windows-native 目录执行
  `cargo fmt --all`、`cargo test --workspace` 和
  `scripts/build-installer.ps1`。最近一次 Windows workspace 测试为 68 项全部通过；
  安装器完成页、已安装 EXE 启动、WSS 17322 监听和覆盖安装均已有本地验证。
- 可见行为：打包的高对比 Codex 托盘图标常驻通知区域，程序没有主窗口且保持单实例；托盘菜单包含刷新状态、二维码配对、连接与诊断、撤销配对、
  安装/修复 Hook、默认开机启动和退出。二维码窗口、配对教学窗口和诊断窗口均已验证不再默认落在屏幕左上角；
  配对教学支持拖动标题区并有明确关闭按钮。
- 安装完成页的「完成后启动」已验证：默认勾选后会启动已安装程序并打开二维码新手引导。
  已知问题仅包括测试版未签名可能触发 Windows 未知发布者提示，以及单实例程序不会重复打开第二份托盘实例。
- ChatGPT 信任不是安装器自动完成的步骤：用户必须在「ChatGPT → 设置 → 钩子 → 信任全部钩子」中确认
  `PreToolUse`、`PermissionRequest`、`UserPromptSubmit`、`Stop` 已开启。
- 排障顺序：同一可信局域网 → Windows 防火墙专用网络/VPN → 托盘「连接与诊断…」→
  「刷新当前状态」→「安装/修复任务 Hook…」→ ChatGPT Hooks 信任并重启 ChatGPT。

关键 Windows 入口：

- windows-native/src/bin/codex_quota_windows.rs：托盘、菜单、配对/教学/诊断窗口、开机启动和进程入口；
- windows-native/src/hook.rs：Hook 事件归并、任务状态和安全标题；
- windows-native/src/quota.rs：额度和重置卡摘要采集；
- windows-native/src/host.rs、windows-native/src/network.rs：配对、TLS 1.3 WSS /pair 和 /sync。

### 已完成的三端文案统一

- Hook 信任路径统一为「ChatGPT → 设置 → 钩子 → 信任全部钩子」。
- Android、Windows 与手环的额度新鲜度短标签统一为「已同步 / 缓存 / 待同步 / 离线」。
- Windows 桌面窗口的居中、拖动和关闭控件属于平台转译，不应作为 Android/手环布局复制目标；
  三端只需继续共享颜色语义、状态含义、圆角气质和层级。

## 0.4.0 Android UI 历史开发记录（2026-07-29）

本节保留 0.4.0 的构建和真机证据；不代表当前候选版本或发布状态。

- **版本与产物**：包名 `com.codex.quota.android`，`versionName 0.4.0`、`versionCode 400`。上一轮已验证的 debug APK 为 29,930,175 bytes，SHA-256 为 `BEA14C1727A86F7758311A2A01D0772F144E93C99AAD4F25D88BB252390119B2`。
- **已有验证结果**：Android 单元测试 83 项通过，`lintDebug` 与 `assembleDebug` 通过；已授权真机序列号为 `c3f86dd8`。首页、任务页、设置页及正常/缓存/离线状态已有截图证据。用户随后确认当前 Windows、Android 与手环候选均验收通过；正式发布仍须先统一版本并由用户另行要求。
- **自动刷新证据**：前台运行时曾验证旧缓存状态在自动刷新后更新为新额度并显示“已同步·刚刚”。实现为 45 秒刷新请求、5 秒新鲜度重评估、超过 60 秒转缓存；后台进程被系统回收的场景仍受 Android 生命周期限制。
- **推荐复验命令**：
  ```powershell
  Set-Location android-app
  $env:JAVA_HOME = Join-Path $env:LOCALAPPDATA 'codex-quota-dev\jdk-17'
  $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
  $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
  ..\spikes\android-background-probe\gradlew.bat -p . :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
  ```
- **关键入口**：`MainActivity.kt`、`CodexQuotaApplication.kt`、`ui/CodexQuotaApp.kt`、`ui/UiModels.kt`、`runtime/RuntimeStateRepository.kt`、`runtime/SyncWebSocketClient.kt`、`runtime/SyncStreamSession.kt`、`runtime/PairingClient.kt`、`runtime/XiaomiWearableBridge.kt`、`notifications/TaskAlertCoordinator.kt`。

### 当前已知限制与待统一问题

- Android 普通进程没有前台服务保证；自启动、应用加锁和电池策略只能降低被杀概率，不能把后台同步承诺为必达。
- 设置页“通知时机”的辅助说明仍需按所选模式进一步统一文案；设备连接检查目前是应用级动作，不是独立设备卡片。
- 深色模式、不同 OEM 状态栏和系统模糊能力仍可能产生细微材质差异；正式 Compose 采用半透明表面、描边和阴影，不依赖全屏实时模糊。
- `Stop` 事件仍映射为“等待查看”，当前产品没有“终止/失败”任务状态；如需改变必须另行确认产品语义。

## 0.4.9 手环主数字最大化与任务避让候选（2026-07-29）

- 首页“周额度 / 可用重置”标题改为亮白；周额度数字提升为 `96px`，`%` 调整为白色 `34px`，
  三位数字自动使用 `80px` 紧凑档以保持 212px 安全宽度。分割线移至 `280px`，重置区下移至
  `306px`，日期与底部安全区仍保留可读空间。
- 任务页状态点下移至标题首行的视觉中心；状态/时间行收至 `140px`，时间固定 `40px` 并在右侧
  留出分页点避让区，避免“14分”等文字与系统 `swiper` 指示点重叠。
- `band-app` 执行 `npm run build` 通过，构建后 22/22 项自动测试通过；覆盖最大化额度区、亮白标题、
  下移重置区、任务圆点对齐、时间避让与包版本升级。
- 本地候选产物：`band-app/dist/com.codex.quota.android.debug.0.4.9.rpk`；大小 `51,051` bytes，
  SHA-256：`FA1C01FEB10AFFA8D1003D2E7E1B21807BF0EBE908475B6F16631A293AEB5E67`。
- 按用户授权，手环包单独升级为 `0.4.9 / versionCode 50`，尚未与 Android、Windows 统一；此前已复制到
  已授权设备 `c3f86dd8` 的 `/sdcard/Download/com.codex.quota.android.debug.0.4.9.rpk`，设备端大小和
  SHA-256 回读匹配。用户随后确认当前手环候选验收通过；没有提交、推送或发布。

## 0.4.8 手环光学校正与同步新鲜度候选（2026-07-29）

- 首页同步胶囊收窄为 `116×36px`；新鲜快照只显示“已同步”，不再重复时钟。摘要生成时间超过
  60 秒时，状态自动降为“缓存 + 相对分钟”；离线、暂停和上游明确缓存状态仍保持各自的真实语义。
- 周额度数字调整为 `78px`，白色 `%` 下沉并整体右移做光学校正；可用重置数字调整为 `58px`，
  “次”上移到与数字可见底部齐平。两个日期统一 `20px`，重置卡到期只显示月日。
- `band-app` 执行 `npm run build` 通过，构建后 21/21 项测试通过；覆盖 Vela 原生纵向 `swiper`、
  新尺寸、短到期日期、已同步无时间、超过一分钟降缓存和版本升级。
- 本地候选产物：`band-app/dist/com.codex.quota.android.debug.0.4.8.rpk`；大小 `51,057` bytes，
  SHA-256：`76E5F5478EAFC76F7DD20E5020F8231E8BCDCAC094525E5E9BB0912365DD7487`。
- 按用户授权，手环包单独升级为 `0.4.8 / versionCode 49`，尚未与 Android、Windows 统一；已复制到
  已授权设备 `c3f86dd8` 的 `/sdcard/Download/com.codex.quota.android.debug.0.4.8.rpk`，设备端大小和
  SHA-256 回读匹配。没有安装到手环、提交、推送或发布。真机需重点检查“次”的可见底部、212×520 下的
  日期清晰度，以及一分钟后缓存胶囊的转换。

## 0.4.0 Android 圆环中心数字精修（2026-07-29）

- 移除圆环内“剩余额度”说明，使百分比成为唯一中心信息；数字组向右、向上各微调 `4dp`，百分号维持
  小字号和底部对齐，以补偿数字与符号的视觉重心差异。
- Android `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全部通过；最新 Debug APK
  已覆盖安装到唯一已授权设备 `c3f86dd8` 并启动。真机证据：
  `android-app/device-ui-ring-centered-percent-raised.png`。
- Debug APK 为 `29,930,175` bytes，SHA-256：
  `BEA14C1727A86F7758311A2A01D0772F144E93C99AAD4F25D88BB252390119B2`；版本保持 `0.4.0`，
  未修改 Windows、手环、协议、通知或正式版本号，未提交、推送或发布。

## 0.4.0 Android 自动额度刷新与圆环校正（2026-07-29）

- 修复 Android 只有下拉刷新才请求上游额度的问题。已配对且会话协商完成后，应用级同步客户端每
  45 秒发送一次绑定当前 connection ID 的 `refresh_request(scope=quota)`；不把该调度放进 Compose 页面。
- 运行时仓库每 5 秒重算新鲜度：上游 `lastSuccessAt` 或 v1/v2 快照时间超过 60 秒，即使 WSS 仍在线也
  将状态降为“缓存”；心跳和任务事件不再延长“已同步”。
- 周额度圆环改为对称内缩圆形表面，修复内外层错位；百分号改为独立的小字号并与数值底部对齐。任务页
  改为标题行内的状态彩点，消除彩点与标题错位。
- 新增自动刷新周期与一分种超时降缓存测试；Android 单测、`:app:lintDebug`、`:app:assembleDebug`
  全部通过。真机启动时旧数据正确显示“缓存 7分”，首个自动周期后收到新额度（45% → 43%）并显示
  “已同步 刚刚”；未依赖用户下拉刷新。
- 真机证据：`android-app/device-ui-auto-refresh-initial.png`、
  `android-app/device-ui-auto-refresh-after.png`、`android-app/device-ui-task-dot-baseline.png`。
  APK SHA-256 待本轮最终回读后补入；版本保持 `0.4.0`，未修改 Windows、手环、协议、通知、
  正式版本号，未提交、推送或发布。

## 0.4.0 Android 任务层级与重置日期精修（2026-07-29）

- 周额度卡的重置日期由半粗标题降为正文级常规字重，避免与“周额度”和中心百分比争夺层级。
- 任务页参考已确认的手环布局：每条任务左侧保留状态彩点，分组标题恢复中性；底部拆为左侧状态、
  右侧相对时间，处理中和等待查看使用辅助灰，需要授权使用错误红。首页任务摘要保留活动信息，
  本轮没有删除同步数据或改变任务协议。
- 新增任务状态文字强调规则测试；Android 单测 81 项、`:app:lintDebug`、`:app:assembleDebug`
  全部通过。
- Debug APK 为 `29,930,175` bytes，SHA-256：
  `A802CCC14C00F64F780164B6E817CFEAB79217BF537E181D1CF744AF77CA5734`。唯一已授权设备
  `c3f86dd8` 已覆盖安装并启动，版本仍为 `0.4.0`，最近 1,000 行 logcat 未发现运行时崩溃。
- 真机证据：`android-app/device-ui-reset-weight.png`、`android-app/device-ui-task-dot-layout.png`。
  本轮未修改 Windows、手环、协议、通知、版本号，也未提交、推送或发布。

## 0.4.0 Android 同步时间与周额度层级精修（2026-07-29）

- 修复同步胶囊的相对时间在 Compose 初次组合后冻结的问题：根页面维护唯一的应用级当前时间，
  每个整分钟更新一次，并传递给首页、任务页和设置页。网络同步时间仍来自真实快照；本改动不改变
  协议、刷新请求、通知或数据语义。
- 首页将同步状态收敛到顶部胶囊，移除周额度卡中重复的“同步于 ……”和“额度已同步”文案。
  周额度中心数字调整为 `52sp`、较小负字距，并与“剩余额度”紧邻，提升数字视觉重心。
- `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全部通过；新增整分钟刷新延迟单测。
- 唯一已授权设备 `c3f86dd8` 已覆盖安装并启动。整分钟调度由新增单元测试覆盖；跨分钟真机观察期间
  Windows 同时发布了新快照，顶部状态从“已同步 9分”更新为“已同步 刚刚”。首页视觉证据：
  `android-app/device-ui-clock-and-ring.png`、`android-app/device-ui-clock-tick.png`。
- Debug APK 为 `29,930,175` bytes，SHA-256：
  `94BE16FC444C711EFDF3FEF246FDD8AB546CE758D42152B43EE975C91C735395`；版本保持 `0.4.0`，
  未修改 Windows、手环、协议或正式版本号，未提交、推送或发布。

## 0.4.7 手环两页 UI 本地候选（2026-07-29）

- 已按确认的 212×520 预览更新手环 RPK：首页保留“周额度 / 可用重置 / 重置日期”，
  `剩余额度` 为唯一最大数字；`%` 拆为更小的白色单位，重置次数保持同基线的 `1 次`。
- 第二页移除“任务”标题，使系统时间和同步胶囊与首页同高。任务仍按
  “需要授权 → 处理中 → 等待查看”优先级排序，但移除状态分组标题；标题为白色，状态和相对时间
  使用统一灰色，每行只保留一个红/蓝/绿状态点。需要授权例外地保留红色状态文字。仅改变手环本地展示，
  不修改 Android/Windows 通信、协议或隐私边界。
- `band-app` 执行 `npm run build` 通过；构建后的 20 项手环测试全部通过，包含 Vela 原生
  纵向 `swiper`、双圆点位置、两页同高胶囊、两行标题截断、分组优先级和状态色断言。
- 本地候选产物：`band-app/dist/com.codex.quota.android.debug.0.4.7.rpk`；
  大小 `50,583` bytes，SHA-256：
  `2E2C643DD0B14F3FF2477AEC3B5FF161EB60504EA42B6CD3EA3178126AC44FBC`。
- 为覆盖既有手环候选包，按用户明确授权将手环包单独升为 `0.4.7 / versionCode 48`；
  尚未与 Android、Windows 统一版本。已通过授权 ADB 设备 `c3f86dd8` 复制到
  `/sdcard/Download/com.codex.quota.android.debug.0.4.7.rpk`，设备端 SHA-256 和大小均已回读匹配。
  本轮没有安装到手环、提交、推送或发布。真机只需重点确认第二页的三任务极限高度、顶部弧形区
  可读性以及原生上下翻页手感。

## 0.4.0 Android 主动额度刷新与文案修正（2026-07-29）

- 普通 UI 将“已确认/待确认”改为“已同步/待同步”；内部仍保留 quota v3 的
  `current/cached/unavailable` 精确语义。“缓存”时间继续表示上一次成功同步额度距今多久。
- 同步流增加闭合的手机 `refresh_request`，只含 transport version、当前 connection ID 和固定
  `quota` scope；Windows 拒绝未知字段、错误连接 ID 和未认证请求，并使用 10 秒全局冷却。
- Android 下拉刷新不再重启 WSS，而是在已协商连接上请求 Windows 立即查询额度；查询完成后 Windows
  重新收集额度并发布快照。冷却期内回送当前快照，避免刷新动画等待超时。
- Android 单测 79 项、lint、debug APK 构建通过；Windows `cargo test --workspace` 83 项通过，
  包括真实 TLS/WSS 刷新请求往返；Node 同步契约定向测试通过。
- 真机验证：下拉前 Windows 上游 `lastAttemptAt` 为 01:14:15，下拉后更新为 01:15:15，状态为
  `current`；手机随即显示“已同步 刚刚 / 同步于 01:15”。
- Debug APK 为 `29,930,175` bytes，SHA-256：
  `965AB3D9ECFA2BA82FF1F451BCA37813E16E8A2F99B83EB723652A2A613F3E08`。Windows 安装器为
  `2,930,009` bytes，SHA-256：
  `E2EAE2F5F92B4CB6A07C26D45D63237AADE224A3D4B01C5E4AA45AFC312FA8DF`。
- 最终截图：`android-app/device-ui-refresh-final.png`。Windows 本地候选和 Android debug APK
  均已覆盖安装；版本仍为 `0.4.0`，未提交、推送或发布。

## 0.4.0 Android quota v3 上游确认接入（2026-07-29）

- Android Client Hello 现声明 quota `[1,2,3]`，任务协议仍为 `[1]`。客户端保存 Server Hello 的
  quota version，v3 严格按 `contract/snapshot-v3.schema.json` 解析；未协商、版本不匹配、缺字段和
  未知字段都会被拒绝，断线会清空协商状态。
- Android 将 WSS 最近收包时间与额度上游最近成功确认时间分离。顶部状态、额度圆环和重置卡依据
  `upstreamFreshness` 显示“已确认”“缓存”或“待确认”；电脑断开显示“离线”，任务更新不再刷新额度
  确认时间。v1/v2 回退保持兼容。
- 同步胶囊使用闭合短文案，最长时间封顶为 `99天+`；实现同时设置 `112dp` 最大宽度、单行与省略兜底。
  单测覆盖四种最长文案均不超过 8 个 Unicode code point，避免动态字段再次撑破圆角。
- Android `:app:testDebugUnitTest` 78 项、`:app:lintDebug`、`:app:assembleDebug` 全部通过。
  Windows `cargo test --workspace` 全部通过（78 项，包含真实 TLS/WSS 往返）；quota v3 的 Node 契约
  定向测试通过。根目录完整 `npm test` 的 41 项中 36 项通过，5 项失败均来自本轮开始前已存在的
  手环测试与当前未提交手环实现不一致，本次未修改手环源码来掩盖该问题。
- 最终 Debug APK 大小为 `29,930,175` bytes，SHA-256：
  `484B0819772840EF55466CE93B4D91A555E35E78E412B9697B032901D3300389`；版本保持 `0.4.0`。
- `adb devices` 仅识别到一台已授权设备 `c3f86dd8`；APK 已覆盖安装并启动。真机与当前 Windows
  候选协商 v3 后显示“已确认”，首页、任务页和设置页的动态胶囊均完整、留白正常且未溢出。
- 真机截图：
  `android-app/device-ui-quota-v3-final-home.png`（最终首页）、
  `android-app/device-ui-quota-v3-tasks.png`（任务页）、
  `android-app/device-ui-quota-v3-settings.png`（设置页）。
- Android 诊断只新增传输时间和脱敏上游状态；手环桥接只把 v3 状态折叠为现有同步/缓存摘要，
  未下发原始新鲜度对象、凭证、响应或卡片身份。本轮未修改手环 RPK、正式版本号，也未提交、推送或发布。

## 0.4.0 Android 深色模式重构（2026-07-28）

- 深色模式改为独立的背景、三级表面、文字、描边和状态色体系；修复页面根层未提供深色内容色，
  导致标题和部分卡片正文在深色背景上回退为黑色的问题。
- 离线/缓存时额度圆环、百分比、状态线和同步胶囊统一使用提亮的缓存灰色；实时额度仍按既有
  红/黄/绿阈值显示，没有改变额度语义、数据源或同步逻辑。
- Android `:app:testDebugUnitTest` 69 项、`:app:lintDebug`、`:app:assembleDebug` 全部通过；
  版本保持 `0.4.0`。
- 最终 Debug APK 大小为 `29,930,175` bytes，SHA-256：
  `6D6EFAF9D8EB32E2FB4883A0F3510D967D892F08248A3F92A5BF0DE021BBA2D3`。
- `adb devices` 只识别到一台已授权设备 `c3f86dd8`；Debug APK 已覆盖安装并启动。
- 真机检查覆盖深色离线首页、已同步首页、任务页和设置页；标题、辅助文字、状态色、开关、
  额度圆环、玻璃表面和底部导航均保持清晰可读。
- 截图证据：`android-app/device-ui-dark-home.png`、`android-app/device-ui-dark-synced-home.png`、
  `android-app/device-ui-dark-tasks.png`、`android-app/device-ui-dark-settings.png`。
- 本轮只修改 Android UI 设计系统、Compose 表现和 UI 设计文档；未修改协议、同步、通知、
  Windows 或手环代码，未提交、推送或发布。

## 0.4.0 Windows 原生增量候选验证（2026-07-26）

- 本节只记录 Windows 原生客户端的增量实现和验证；小米手环 RPK 的后续 UI 预览、实现与验收由独立对话处理，本轮未修改手环源码。
- Windows `cargo test --workspace`：60 项通过；`cargo fmt --check` 通过。验证覆盖配对、TLS 1.3 WSS `/pair` 与 `/sync`、Hook 归并、额度采集和撤销配对后的连接关闭。
- Windows 直接重置卡采集：本机接口字段结构验证为 HTTP 200；解析测试覆盖仅保留可用卡 `grantedAt`/`expiresAt`、拒绝 401 的响应详情、脱敏本地缓存，以及 quota v1/v2 协商。直连请求在启动后及每 15 分钟最多一次，不跟随 5 秒缓存检查重复发起。
- 撤销手机配对时，Windows 会主动关闭现有已认证 `/sync` 会话；手机无需退出后台或重启 App 即可收到断开并显示离线。该行为不改变协议字段或业务状态语义。
- Windows 原生可见界面保持托盘优先：配对窗口只保留二维码和两步引导，连接与诊断只显示可验证状态，不根据“Hook 已写入”推断“已信任”。配对表面使用轻描边和 32px 大圆角，不引入实时模糊。
- Release EXE `--smoke-test` 退出码为 0；本地安装器构建与隔离 smoke test 通过。候选安装器：
  `windows-native/dist/Codex-Quota-Setup-0.4.0.exe`；SHA-256：
  `08EBB3C33C64082E6607AE6C039793E2D1D616FE9AFBF3EDCFAD528A2FEB45A6`。
- 重置卡直连改造后的 Setup 已实际覆盖安装到当前用户目录
  `%LOCALAPPDATA%\Programs\CodexQuota`；安装器、已安装 EXE `--smoke-test` 和托盘常驻启动均通过。
  当前安装器 SHA-256：`DCB73E39A6E0B8147B9BAD1C04F4DA2BDFB6E2869B2CA7CF85B153D6FAA85B0F`。
  已安装程序生成的本地重置卡摘要仅含 `schemaVersion`、`fetchedAt`、`availableCount`、卡片
  `grantedAt`/`expiresAt`，未发现 access token 或卡片 ID 字段。
- 以上为本地候选验证，不代表 Windows 最终验收或发布授权；未提交、推送或发布构建产物。

## 0.4.0 Android App 最终验收（2026-07-26）

- 用户已确认 Android 手机 App 验收通过。本记录只代表 Android App 的最终 UI 与交互验收，
  不代表 Windows 原生程序或小米手环 RPK 已获得发布授权。
- Android `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全部通过；版本保持
  `0.4.0`，未修改协议、同步、通知或隐私边界。
- Debug APK 已安装到唯一已授权真机 `c3f86dd8` 并启动检查。最终 APK SHA-256：
  `0F6F7E93B2BD537FDE6D8EFCA9866879DB7B3892941F6F457C615AA8F59F6782`；文件大小
  `29,930,175` bytes。
- 首页、任务页和设置页已按确认原型完成真机检查：周额度红/黄/绿圆环、合并后的电脑/手环状态胶囊、
  任务标题省略号、设置页扫码连接电脑/隐藏任务标题/导出诊断入口和无文字底部导航均可用。
- ChatGPT 状态卡已移除，避免呈现无法可靠监控的“运行中”状态；顶部同步胶囊作为唯一同步状态表达，
  周额度卡不再重复显示“实时”。
- 离线与缓存检查通过：页面背景、离线提示、周额度圆环和“缓存额度”说明统一使用灰阶，
  不把缓存数据冒充实时数据；恢复 Wi-Fi 后同步状态恢复。
- 截图证据：
  `android-app/device-ui-unified-radius.png`（正常状态）、
  `android-app/device-ui-offline-grey-ring.png`（离线/缓存状态）。
- APK 图标恢复为既有 `@drawable/codex_quota_icon` 资源；未改版本号、Windows、手环或协议文件。

## 0.4.0 Android quota v2 联调（2026-07-26）

- Android 单测、lint、debug APK 构建全部通过；新增 v1/v2 协商、严格解析、断线清空协商状态、
  发卡时间缺失和手环摘要脱敏测试。
- 本次 quota v2 debug APK SHA-256：`B5FAE1ECFC6ACB816F582124CD500B871927F7050F1D77D519FCB49810611865`，
  文件大小 `29,930,175` bytes，版本仍为 `0.4.0`。
- `adb devices` 仅识别到一台已授权设备 `c3f86dd8`；debug APK 已安装并启动。
- 联调初次使用的电脑端 EXE 是 03:35 的旧构建，收到 Android `[1,2]` Hello 后立即断开；使用当前
  Windows 源码构建的 v2 候选 EXE 后，手机成功协商 quota v2、同步周额度和重置卡，并显示
  `发卡 7月14日 01:26` / `到期 8月13日 01:26`。
- 真机截图：`android-app/device-ui-quota-v2-final.png`（同步首页）、
  `android-app/device-ui-quota-v2-reset-final.png`（每张卡的发卡/到期时间）。
- 当前手环 RPK 未修改；Android 已生成独立手环摘要版本 2，消费端需在手环 Agent 确认契约后再更新。

## 0.4.0 本地候选构建检查（2026-07-25）

- Windows 原生完整测试：50 项通过；为避免中断正在运行的托盘服务，测试使用独立临时
  Cargo 输出目录，不复用被运行实例锁定的 EXE。
- 修复 Windows 托盘图标误用系统蓝色信息图标的问题，改为嵌入项目 32×32 Codex 图标；
  release `--smoke-test`、安装器构建和安装器 smoke test 均通过。
- Windows Hook 任务标题在索引延迟写入时会自动刷新；若首次只能得到“任务”，后续无需
  新 Hook 事件也会根据本地 `session_index.jsonl` 更新真实标题，且不读取提示词。
- Android `testDebugUnitTest`、`lintDebug`、`assembleDebug`：通过。真机已确认
  固定「移除」按钮与二次确认；移除只隐藏本机任务和手环摘要，不删除 ChatGPT 对话，任务有
  新活动时会自动恢复。
- 手环 RPK `npm run build`：构建后 13 项测试通过；产物为
  `band-app/dist/com.codex.quota.android.debug.0.4.0.rpk`。
- Windows、Android、手环三个组件的公开版本均为 `0.4.0`。新构建的 Windows 原生 EXE
  `--smoke-test` 退出码为 0。
- Windows 原生安装器已由 `windows-native/scripts/build-installer.ps1` 构建，使用当前用户范围，
  创建开始菜单入口和卸载项；`windows-native/scripts/test-installer.ps1` 已在隔离临时目录
  完成安装、已安装 EXE 启动检查、卸载和注册表清理验证。根目录的 Electron 安装器不用于 0.4.0。
- 本轮增量验证：Windows `cargo test --workspace` 50 项通过，release EXE `--smoke-test` 退出码为 0；
  新安装器 SHA-256 为 `68C2FA01B04F416FECB57D2F3E06BF2BEC7FA4BF133FDA0CDD45C3B7C9D575DD`。
  现有正式安装占用卸载注册表项，因此没有强行覆盖它执行隔离安装器 smoke test。
- 安装器在写入前会只请求关闭本程序的旧托盘进程，避免 `CodexQuota.exe` 被自身锁定而安装失败。
- 配对窗口不再显示用户无法手动输入的临时码和局域网地址；二维码载荷仍包含真实的一次性验证码，Android 扫码后自动提交。
- 本轮 Android 增量验证：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 全部通过；debug APK
  已覆盖安装到真机 `c3f86dd8`，版本 `0.4.0`，SHA-256 为
  `26A864706B6427C77D81EBCD6EC50FA6B4E99580D265FDFD50380E8B6A1F0AE5`。
- 用户真机确认：新版配对窗口说明清晰、扫码配对成功；该条是 7 月 25 日的阶段性记录，Android UI
  最终验收结果以本文件上方的 7 月 26 日记录为准。
- 历史 AstroBox 插件协议测试：4 项通过；插件测试脚本统一使用已安装的
  `stable-x86_64-pc-windows-gnullvm` 工具链，避免本机 GNU 链接器缺少 `libgcc` 导致误报。
- 历史 AstroBox 插件 release 构建：通过；产物为
  `astrobox-plugin/dist/codex-quota-astrobox-0.3.1.abp`，SHA-256 为
  `DE5AF20B2F2E93D58DA36C293EC155603011230F8AA5A3CD6B14DE96B535D8F8`。
- 手环 RPK 本轮重新构建：通过；构建后 13 项测试通过，产物仍为
  `band-app/dist/com.codex.quota.android.debug.0.4.0.rpk`，SHA-256 为
  `D93BCB412CDFA0B39590E7D842AD2C629C45D67F6CEF92199CB18336DA3FE27A`。
- 当前候选的完整自动化收口：根目录 Node 测试 40/40；Windows Rust 工作区 50 项；历史 AstroBox
  协议测试 4/4；手环构建后测试 13/13；Android 单测、lint 和 APK 构建均通过。
- 安装完成页的启动勾选改由 NSIS 显式调用已安装 EXE，并传入 `--show-onboarding`；
  程序会直接前台显示二维码，并在二维码下方显示配对、同一局域网和 ChatGPT Hook 审阅步骤，
  不再弹出带信息图标的长篇引导框。托盘菜单也直接打开同一配对页面。
- 本机故障定位：手机位于 `192.168.101.x`、电脑位于 `192.168.3.x`，从手机到电脑
  `17322` 端口的探测超时；这是不同局域网导致的正常本地直连失败，不是 Hook 或二维码失效。
- 本轮 Windows `cargo test --workspace`：50 项通过；release `--smoke-test` 退出码为 0。
  更新安装器 SHA-256：`68C2FA01B04F416FECB57D2F3E06BF2BEC7FA4BF133FDA0CDD45C3B7C9D575DD`。

## 0.4.0 Android 新架构网络闭环（2026-07-24）

- Windows `cargo test`：50 项通过，包含 DPAPI 身份存储、TLS 1.3 + WebSocket `/pair`、`/sync` 的真实往返测试，以及 Chromium 缓存额度采集与可信缓存夹具。
- Android `testDebugUnitTest`、`lintDebug`、`assembleDebug`：通过；debug APK 已覆盖安装到
  真机 `c3f86dd8`，版本 `0.4.0`，`POST_NOTIFICATIONS` 保持已授权，未出现 `AndroidRuntime` 崩溃。
- 使用 `dev_wss_probe 192.168.3.2` 生成一次性深链，经 USB 交给 Android；Windows 端实际观测到
  `PAIR_OK` 和 `SYNC_OK`。手机通过固定 Windows 公钥指纹和长期令牌完成加密同步。
- 原生 Windows 主程序已接入 ChatGPT/Codex Chromium 缓存中的 `/backend-api/wham/usage` 和
  `rate-limit-reset-credits` 白名单摘要，后台每 5 秒刷新；损坏或暂时不可读时保留上一份数据并标记为部分/过期。
- 真实原生托盘进程的二维码窗口已检查通过，服务监听 `0.0.0.0:17322`，调试进程工作集约 35 MB；
  本行记录 7 月 24 日当时的阶段状态，后续已补充完成 Android 端重新扫码、真机同步、局域网发现、Hook、后台通知和手环提醒的阶段性验收，详见下节与 `docs/device-acceptance.md`。

### 0.4.0 Android 任务通知闭环（2026-07-25）

- 修复 Android 同步流只更新任务页面、没有接入生产通知分发器的问题；手机通知与小米
  Wearable SDK 手环通知现在由同一份任务状态转换触发，开关保持独立。
- 修复断线前已观察到「正在处理」的任务在重连首帧变为「等待查看」时被错误当作历史任务
  静默丢弃的问题；冷启动仍不会回放旧的等待查看通知。
- 「等待查看」和「需要授权」使用新的高优先级 Android 渠道，请求系统悬浮显示；等待查看
  默认静默，需要授权默认振动。悬浮通知与锁屏通知的最终权限由 Android 系统和用户决定，
  应用不能强制开启。
- 真机在 ChatGPT 失焦、手机锁屏 Dozing、Android App 后台运行时保持 WSS 连接。用户确认：
  手机收到锁屏通知并在解锁后保留于通知栏，手环收到通知并亮屏，两端均产生预期提醒。
- 真机后台前提为系统自启动、应用加锁、电池无限制；验证时应用处于活跃待机桶并已加入
  电池豁免白名单。没有新增前台服务或常驻状态通知。

### 0.4.0 Wearable SDK 直连 Spike（2026-07-24）

- Android APK 已接入小米官方 `xms-wearable-lib_1.4_release.aar`，`testDebugUnitTest`、`lintDebug` 和 `assembleDebug` 通过。
- 官方 Demo 在同一台真机上发现小米手环 10 节点 `2024822414`；`NodeApi.launchWearApp()` 返回成功。
- 官方 Demo 的 `MessageApi.sendMessage()` 发送 `CODEX_SPIKE_1952` 返回成功；此前 `NotifyApi.sendNotify()` 已验证手环实际收到并振动。
- 新版 RPK 已切换为包名 `com.codex.quota.android`、版本 `0.4.0`，并与 Android debug APK 使用同一 SHA-256 证书；等待通过 AstroBox 完成一次性侧载和手环端人工确认。

日期：2026-07-20

候选组合：Windows / AstroBox / 手环 RPK 统一为 0.3.0

## 自动化与本机验证

- Node 行为测试：37/37 通过。
- 手环构建后启动、状态与 UI 集成测试：7/7 通过。
- AstroBox Rust 协议测试：4/4 通过。
- Windows 真实 Codex 本地数据往返：通过。
- Windows 打包版 `--smoke-test`：退出码 0，并实际加载安装包内 32×32 PNG 托盘图标。
- NSIS 安装器使用 Unicode MUI 页面，完成页默认勾选“立即启动”；安装阶段会自动安装/修复任务 Hook。
- Android 设置页的扫码连接按钮只启动系统相机，不增加相机权限或内置扫码 SDK。
- Windows 打包版 `--diagnostic-service-test`：通过；只返回周额度与 Full reset 白名单摘要。
- Windows 与手环应用生产依赖审计：0 个已知漏洞。

诊断时的实时额度会随 Codex 使用而变化；验证只检查白名单字段和数据一致性，不把测试账号的实时额度作为固定期望值。

## 候选产物

| 产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `dist/Codex Quota Setup 0.3.0.exe` | 100,025,541 | `964084AC317710ECAC020AD1D309289777E0FFB489CD4313607B4BEA96B5711F` |
| `astrobox-plugin/dist/codex-quota-astrobox-0.3.0.abp` | 208,467 | `D5BCE87A7CB434E57C6D838486736094B4D2722CDB28F2576A904EFD487A3AD8` |
| `band-app/dist/com.codex.quota.debug.0.3.0.rpk` | 47,646 | `7D7BEA5F1D160728ECE2AFDB55DBB65854A3212D40FF927923062BDAEBBE0EFF` |

统一版本 0.3.0 已完成三组件真机验证：Windows 安装与托盘入口正常；AstroBox 能导入插件、重启后显示「Codex 额度桥接」并完成二维码配对；RPK 能从设备页的「快应用数量」入口安装到小米手环 10，并正常显示额度页面。

Windows 安装包是个人侧载候选包，当前未使用代码签名证书，Windows 会显示未知发布者提示。ABP 与 RPK 也仅用于当前 MVP 侧载，不应作为公开发布签名产物。

## 诊断验证说明

Windows GUI 子系统程序可能在 PowerShell 返回控制权后继续执行。旧验证命令曾在子进程完成前读取同名旧报告，造成额度“39% → 43%”的假象。额度候选事件连续扫描稳定、时间单调，产品解析器没有发生回跳。

现使用 `scripts/test-packaged.ps1`：为每轮生成唯一报告路径，以 `Start-Process -Wait -WindowStyle Hidden` 等待打包进程退出，再验证白名单字段并清理报告。连续两轮打包诊断均稳定返回 35%。临时调试探针已删除。

## 真实设备证据

用户在小米手环 10 上运行往返 Spike v0.0.3 后，页面显示“往返完成 Windows 已收到手环 ACK”。这证明 Phase 0 的真实传输链路已经闭环。

正式 AstroBox 0.1.0 已在 Android 真机导入并完成配对：插件检测到有效设备与订阅；Windows 授权库只有一个 64 位十六进制令牌哈希，不含 `token` 或 `Bearer` 明文。

手环 RPK 0.1.0 真机启动时停留在系统启动图。构建后复现确认 AIoT 工具链把独立本地 ES 模块错误转换为未声明的 CommonJS `exports`，异常发生在模板注册前。RPK 0.1.1 已移除该启动路径并改为 Band 10 原生 212×520 画布；真机确认能够打开、滚动并显示 Windows 快照，但 9–12px 的辅助文字在正常佩戴距离难以辨认。

RPK 0.1.2 压缩顶部标题区，将首屏重排为周额度、可用重置次数和最近到期，并把关键辅助文字提高到至少 13px；真机仍确认整体过小、信息过密、灰色文字在正常佩戴距离不可读。

RPK 0.1.3 在正式修改前先提供三种 212×520 原型，用户选择 B「无卡片分区」，并继续确认正常、1% 低额度与断线缓存三种预览。生产实现删除标题、卡片、进度条、手动刷新、缓存来源、隐私说明和逐条 Full reset；首屏只保留同步状态/时间、周额度/重置日期、可用重置次数/最近到期。必要说明文字至少 18px，主数字为 80px/68px；顶部状态固定高度，正常与异常切换不推动主体，且按真机照片的胶囊形安全区收窄。未来 UI 更新继续遵循“先看预览、确认后再改 RPK”。该包仍需真机侧载确认实际可读性，并继续完成 5 秒刷新、离线、蓝牙断开、AstroBox 后台终止与恢复等验收。

RPK 0.2.0 是用户确认预览后的正式 A2 布局：周额度提升为 86px 主视觉；额度进度沿屏幕外缘胶囊从 12 点开始顺时针填充；同步胶囊固定在第二层级；系统时间与日期位于第三层级；可用重置次数降为底部单行。正常、低额度和离线分别使用冷色、暖色与灰色背景/外环，离线仍保留可信缓存比例。公共状态逻辑改用 `.cjs`，避免 AIoT 工具链对独立具名 ES 导出生成无效 `exports` 绑定；构建产物入口和快照渲染测试均已通过。该包仍需小米手环 10 真机侧载确认圆环对齐、字号和触控返回行为。

RPK 0.2.1 根据 0.2.0 真机反馈撤回外缘进度环，恢复 v0.1.3 已验证的无卡片、大字和水平分割线结构，并加入顶部系统时间。同步状态缩为 138×40 彩色胶囊，文案直接显示“已同步 10:08”而不使用中点；周额度仍是第一视觉层级。重置区使用与“周额度”相同字号的“可用重置”标签，居中显示彩色数量与白色“次”，副标题缩为“7月27日到期”。正式页面只使用 20、30、68、76px 四档字号，已删除 0.2.0 的圆环几何逻辑和三张全屏渐变背景；RPK 从 91,727 字节降至 47,649 字节。该包仍需真机确认最终字重、间距与断线胶囊状态。

配对引导的 `.local` 自动发现已用一次性 AstroBox 0.0.2 Spike 真机验证并否决。测试手机能向 Windows 私网地址发出 A/AAAA mDNS 查询，Windows 的手写响应器与 `multicast-dns 7.2.5` 标准响应器都成功单播 A 记录；同一 ABP 访问数字 IP 可得到 HTTP 200，但访问 `codex-quota-spike.local` 始终由 AstroBox/WASI 返回 `DnsError: address not available`。

AstroBox DeepLink Spike 随后在 Android / AstroBox NG 2.0.2 上完成真机验证。官网旧参数 `source=openPlugin&pluginName=...&data=...` 只打开插件列表；Android 成品实际使用 `source=plugdata&name=...&payload=...`，测试载荷已成功到达指定插件。Windows 与 AstroBox 0.3.0 已把该路由正式化：桌面生成包含闭合版本化载荷的二维码，插件校验 1–8 个私网数字 IPv4 地址与 6 位码后依次尝试自动配对；手动地址仅保留为高级兜底。

Windows 0.2.0 将托盘图标从运行时 SVG Data URL 改为打包携带的高对比 PNG，并在源码及安装包烟雾测试中拒绝空图像；额度采集、配对和只读 Snapshot v1 协议未修改。

Windows 0.3.0 新增隔离的二维码配对窗口，默认不显示长地址，5 分钟到期自动关闭；生产 ASAR 已确认包含 `qrcode`、DeepLink 与配对窗口模块。AstroBox 0.3.0 包含 `register_deeplink_action` 权限、闭合载荷解析和扫码自动配对；正式 ABP 的 WASI release 构建通过。打包版烟雾与无副作用诊断均通过，三个公开组件的版本契约测试会拒绝版本号不一致。

当前执行 `npx astrobox-cli status` 无法连接本机 `127.0.0.1:10721`，因此当前环境没有可用的 AstroBox 桌面 CLI 服务，不能从终端自动推送 RPK。正式 ABP/RPK 需要通过 Android 手机上已经连接手环的 AstroBox 界面导入；验收步骤见 `docs/device-acceptance.md`。

## Windows 0.1.0 历史安装验证

候选安装器已在当前 Windows 用户下以静默模式实际安装，退出码 0。安装目录为 `%LOCALAPPDATA%\Programs\CodexQuota`，卸载项版本为 0.1.0。首次启动后：

- 托盘进程从正式安装目录运行。
- HKCU 登录启动项指向正式安装版，偏好文件记录 `openAtLogin: true`。
- 端口 17321 正常监听；无 Bearer 令牌的 `/v1/snapshot` 请求返回 401。
- 配对后授权库仅保存一个 64 位十六进制令牌哈希；未发现明文令牌或 `Bearer` 内容。
- 正常托盘实例运行期间，`npm run test:packaged` 仍通过，证明无副作用诊断不再被单实例锁拦截。
- 开始菜单快捷方式与当前用户卸载/静默卸载命令均存在。
- 正式后台实例 10 秒采样：3 个 Electron 进程合计工作集约 270.6 MB，14 逻辑处理器归一化 CPU 约 0.435%。CPU 定时轮询负载较低；Electron 内存占用是 MVP 已知成本，若未来公开发布可评估更轻量的原生托盘服务。
