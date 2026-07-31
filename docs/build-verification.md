# 构建与验证

本文件只保留正式发布包的验证结论和校验值。逐次开发、调试和设备环境记录不随公开仓库保留。

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
