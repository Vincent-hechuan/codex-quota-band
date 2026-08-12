# 构建与验证

本文件只保留正式发布包的验证结论和校验值。逐次开发、调试和设备环境记录不随公开仓库保留。

## 0.6.4 正式发布包

三端产品版本声明统一为 `0.6.4`；安卓手机和手环 `versionCode` 均为 `606`。手环协议和布局不变，仅同步两分钟新鲜度阈值；现有 `0.6.3` RPK 与新版 Windows、安卓手机兼容，不强制老用户重装。

| 组件 | 自动验证 | 当前本地产物 | SHA-256 | 真机状态 |
| --- | --- | --- | --- | --- |
| Windows | Rust workspace 80/80，`cargo fmt --check`，Release 静态运行库构建、图标检查与最小系统 PATH 独立启动 | `Codex-Quota-Setup-0.6.4.exe`（3,030,027 bytes） | `AC05240729B09AD5AF1C2E52D35BF179D598C02BEBDDA005FF95BA37717497F3` | 已覆盖安装；安装后 EXE 与构建文件散列一致；手机停止 52 秒期间仍由 Windows 在第 45 秒完成官方确认 |
| 安卓手机 | 单元测试 113/113，Lint，Release 构建 | `CodexQuota-0.6.4.apk`（24,614,917 bytes） | `26A0103B95AB79C054EA504B60B8D1A7DE429106678A3857673601E13AF959F1` | 已保留数据覆盖安装；系统报告 `0.6.4` / `606`，加密连接与官方确认已自动恢复 |
| 小米手环 10 | RPK Release 构建，28/28 | `com.codex.quota.android.release.0.6.4.rpk`（50,070 bytes） | `7008DFDC6F162F9FBD98AB8BA5FEDE8E1CD19909211A9BAFB621D90DF1038A5F` | 业务页面和协议兼容；用户已确认现有 RPK 同步正常，因此不要求覆盖安装 |

现场隔离验证中，手机 App 停止前最后确认时间为 `18:40:48Z`；手机停止后 Windows 于 `18:41:33Z` 独立完成下一次确认，证明官方额度实时性不再依赖 Android 后台定时任务。恢复手机 App 后 WSS 自动重连，并于 `18:41:58Z` 再次确认成功。根目录契约测试 43/43。三个附件的 GitHub 远端大小和 SHA-256 已与本地逐项核对；`0.6.4` 已正式发布。

## 0.6.3 正式发布包

三端产品版本声明已统一为 `0.6.3`；安卓手机和手环 `versionCode` 均为 `605`。以下文件已完成自动验证、候选交付与三端真机联动验收。

| 组件 | 自动验证 | 当前本地产物 | SHA-256 | 真机状态 |
| --- | --- | --- | --- | --- |
| Windows | Rust workspace 79/79，`cargo fmt --check`，Release 静态运行库构建，应用图标检查，最小系统 PATH 独立启动，PE 导入表检查 | `Codex-Quota-Setup-0.6.3.exe`（3,033,046 bytes） | `4694A051465E23264B3A2B31D310C31088B70079112E5169267B8551E742B2FF` | 已覆盖安装；安装后 EXE 与构建文件散列一致，托盘与联动验收通过 |
| 安卓手机 | 单元测试 113/113，Lint，Release 构建，包内版本检查 | `app-release.apk`（24,614,921 bytes） | `0381A9A778C39C182A0A3208CCF148BEC8C8427E9D83B8AFB113192BF41D99F6` | 已保留数据覆盖安装；系统报告 `0.6.3` / `605`，启动、同步与提醒验收通过 |
| 小米手环 10 | RPK Release 构建，28/28 | `com.codex.quota.android.release.0.6.3.rpk`（50,064 bytes） | `A32C6AC984812E5281A412BAF21A4D9C1F8A9486A35542107311477BB65D46C1` | RPK 已安装；额度、任务、连接与提醒联动验收通过 |
| 根目录契约 | Node 测试 43/43 | — | — | — |

Windows 成品的导入表只包含 Windows 10/11 自带的系统 DLL/API Set；不包含 `libunwind.dll`、`libgcc.dll`、`libwinpthread.dll`、`VCRUNTIME*.dll` 或 `MSVCP*.dll`。独立启动检查会清空开发环境变量，只保留 Windows 系统目录，防止开发者电脑上的工具链掩盖缺失依赖。

安卓 APK 已覆盖安装到真机，系统报告 `versionName=0.6.3`、`versionCode=605`；安装后应用正常启动，未清除原有配对和设置数据。Windows 安装包已覆盖安装并启动；安装后的主程序与本次构建文件 SHA-256 一致。手环 RPK 已完成安装，Windows、安卓手机和小米手环 10 的本轮真机联动验收均已通过。

`0.6.3` 已完成三端真机验收并正式发布。

## 0.6.2 正式发布包

三端产品版本统一为 `0.6.2`；安卓手机和手环 `versionCode` 均为 `604`。以下文件已完成自动验证与三端真机验收。

| 组件 | 自动验证 | 当前本地产物 | SHA-256 |
| --- | --- | --- | --- |
| Windows | Rust workspace 74/74，`cargo fmt --check`，Release 构建，最小系统 PATH 独立启动检查，PE 导入表检查 | `Codex-Quota-Setup-0.6.2.exe`（2,944,298 bytes） | `F58C2D547C9D3CC80DCC9F1D92F6F43FE61EAAE503D626FA9DAB3FAF5D53D3C9` |
| 安卓手机 | 单元测试 107/107，Lint，Debug/Release 构建 | `app-release.apk`（1,781,326 bytes） | `8D3894449C29843B7B52BAD1CA108A53ED91BA171165E68FC3C093A722599BA2` |
| 小米手环 10 | RPK Release 构建，28/28 | `com.codex.quota.android.release.0.6.2.rpk`（50,070 bytes） | `B234D9B0DB93F52B60E06F51D9A6E1874E72584F3F8153BFD3C64B8CDEBF3F5F` |
| 根目录契约 | Node 测试 42/42 | — | — |

Windows 成品的导入表只包含 Windows 10/11 自带的系统 DLL/API Set，不包含 `libunwind.dll`、`libgcc.dll`、`libwinpthread.dll`、`VCRUNTIME*.dll` 或 `MSVCP*.dll`。安装目录仅有主程序和卸载程序；覆盖安装后主程序与本次构建文件的 SHA-256 一致，并已成功启动。

安卓 APK 已覆盖安装到真机，系统报告 `versionName=0.6.2`、`versionCode=604`。手环 RPK 已完成安装，Windows、安卓手机和小米手环 10 的本轮真机验收均已通过。

## 0.6.0 正式发布包

三端产品版本统一为 `0.6.0`；安卓手机和手环 `versionCode` 均为 `600`。

| 组件 | 自动验证 | 当前本地产物 | SHA-256 |
| --- | --- | --- | --- |
| Windows | Rust workspace 72/72，`cargo fmt --check` | `Codex-Quota-Setup-0.6.0.exe`（2,938,619 bytes） | `D6D36A2CC54D808F08B5B33690DEE051C9A78755737D23AABA9BFA0676C17AD3` |
| 安卓手机 | 单元测试 95/95，Lint，Release 构建 | `app-release.apk`（1,764,942 bytes） | `90D9C16CE67144086B8DD20AAFD24D1A38026C9F5512A9D64F9204E0DCCE5BB9` |
| 小米手环 10 | RPK Release 构建，27/27 | `com.codex.quota.android.release.0.6.0.rpk`（50,257 bytes） | `6E267BE8BA9BD2A18668A9FB2EBE36AC4E6EE9D5614458E574C842A89761858E` |
| 根目录契约 | Node 测试 42/42 | — | — |

安卓 APK 使用固定发布签名；签名材料和小米 Wearable SDK 二进制均不随仓库分发。

## 真机结论

Windows 安装、手机与电脑配对、额度和任务同步、手机后台缓存语义、手环数据同步、两页纵向切换、5 小时额度和周额度进度条均已通过人工验收。

`0.6.0` 已于 2026-07-29 在 GitHub Releases 发布。

## 0.6.1 正式发布包

`0.6.1` 包含手机到手环断线重连修复，以及 Android 端 Wearable 异步刷新结果乱序导致的错误“手环未连接”状态修复。小米服务短暂滞后时，手机会最多进行三次短暂重试。产品版本保持 `0.6.1`；Android 内部安装序号为 `602`，仅用于在不卸载、不丢失配对数据的情况下覆盖此前本地候选。

| 组件 | 自动验证 | 当前本地产物 | SHA-256 |
| --- | --- | --- | --- |
| Windows | Rust workspace 72/72，`cargo fmt --check`，安装包构建 | `Codex-Quota-Setup-0.6.1.exe`（2,937,972 bytes） | `08FB93CC2353681863BDD5C6B9D596C31A83C02EF54502A5576F8027ADBF2B86` |
| 安卓手机 | 单元测试 100/100，Lint，Release 构建 | `app-release.apk`（1,764,942 bytes） | `F71030E84C23BA1FF6DD7C791D2B5B37F0C2E374B60DA8F932EB5BCCD1C9AB71` |
| 小米手环 10 | RPK Release 构建，27/27 | `com.codex.quota.android.release.0.6.1.rpk`（50,263 bytes） | `84E7FD0AD63AF214B300D17DBDAC409434DAE8F7AA36D5545B8FCDEE95FAC7EE` |
| 根目录契约 | Node 测试 42/42 | — | — |

Windows 安装包的隔离安装/卸载 smoke test 没有执行：脚本检测到当前用户已有 Codex额度安装，按保护规则拒绝覆盖。Windows 与 Android 的覆盖安装已按用户授权成功完成；手环 RPK 没有重建、传输或安装，本次 Release 沿用已验收的 0.6.1 RPK。
