# 构建与验证

本文件只保留当前公开候选的验证结论和校验值。逐次开发、调试和设备环境记录不随公开仓库保留。

## 0.6.0 本地候选

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

本地候选尚未提交、推送或发布。正式发布时应重新计算并在 Release 页面提供三个安装包的 SHA-256。
