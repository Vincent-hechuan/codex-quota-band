# Codex Quota for Xiaomi Smart Band 10

在小米手环 10 上查看 Codex 周额度和可用 Full reset 次数。

这是一个**本地优先、只读、开源的测试版项目**。Windows 托盘程序读取本机 Codex 额度事件，Android 手机上的 AstroBox 插件负责转发，小米手环 10 Vela 轻应用负责显示和离线缓存。

> 当前已验证组合：Windows 10/11 x64、小米 15、AstroBox NG 2.0.2、小米手环 10。其他 Android 手机或穿戴设备尚未验证。

## 下载

普通用户不需要下载源代码。请前往 [Releases](https://github.com/Vincent-hechuan/codex-quota-band/releases) 下载同一版本说明中的三个文件：

1. `Codex-Quota-Setup-*.exe`：Windows 托盘程序
2. `codex-quota-astrobox-*.abp`：AstroBox 手机插件
3. `com.codex.quota.*.rpk`：小米手环 10 轻应用

首个公开安装包发布前，Releases 页面可能暂时为空。

## 使用前准备

- Windows 电脑已经安装并使用 Codex，能够产生本地额度记录。
- Android 手机已安装 AstroBox，并能连接小米手环 10。
- 手机和电脑位于同一个可信局域网。
- 若手机或电脑启用了 VPN/代理，需要允许 AstroBox 和局域网地址绕过 VPN。

## 安装与配对

1. 在 Windows 上安装并启动 `Codex-Quota-Setup-*.exe`。应用没有主窗口，入口位于系统通知区域。
2. 在手机 AstroBox 中导入 `*.abp`，并至少打开一次「Codex 额度桥接」，使插件注册扫码入口。
3. 在 AstroBox 中把 `*.rpk` 安装到小米手环 10。
4. 在 Windows 托盘图标中选择「显示配对信息…」。
5. 用手机系统相机扫描二维码，选择在 AstroBox 中打开。插件会自动尝试电脑的可用私网地址并完成配对。
6. 打开手环上的「Codex 额度」。正常情况下会在数秒内显示周额度、同步时间和可用重置次数。

如果扫码入口失效，可以在 Windows 配对窗口展开高级信息，再在 AstroBox 插件中手动输入地址与 6 位配对码。

## 已知限制

- 当前 Windows 安装包没有商业代码签名，Windows 可能显示「未知发布者」。只从本仓库 Releases 下载，并核对发布页提供的 SHA-256。
- AstroBox 目前没有可验证的 Android Keystore 接口，因此只在插件进程内存中保存只读令牌。AstroBox 被系统终止、重启或插件升级后，需要重新扫码配对。
- 局域网链路目前使用 HTTP，只应在可信家庭/办公网络中使用，不要把 Windows 服务端口映射到公网。
- 这不是 OpenAI、Xiaomi 或 AstroBox 的官方产品。

## 隐私与只读边界

- 周额度只读取 `~/.codex/sessions` 中的 `rate_limits` 事件。
- Full reset 只读取 Codex AppX 的 Chromium HTTP 缓存，不读取 Cookie、登录数据或访问令牌。
- 跨设备只发送裁剪后的额度百分比、重置时间、可用重置数量和链路状态。
- 不发送对话、提示词、模型输出、项目名称、文件路径、终端内容或完整 Codex 会话。
- Windows 只持久化长期令牌的 SHA-256 哈希；手机令牌只驻 AstroBox 插件内存；手环不接收配对令牌。
- 托盘菜单可以随时撤销全部已配对设备。

详细设计见 [安全说明](docs/security.md) 和 [AstroBox 凭据存储 ADR](docs/adr-001-astrobox-credential-storage.md)。

## 从源码开发

要求 Node.js 24+、PowerShell、Rust/WASI 环境，以及小米 Vela 快应用工具链。

```powershell
npm install
npm test
npm run test:plugin
npm run build:win
npm run build:plugin

Set-Location band-app
npm install
npm run build
```

构建产物位于各组件的 `dist` 目录，但不会提交进 Git；公开安装包应作为 GitHub Release assets 上传。

## 组件版本

- Windows：0.3.0
- AstroBox 插件：0.2.0
- 小米手环 10 RPK：0.2.1

自动化验证和真机待办见 [构建与验证记录](docs/build-verification.md) 与 [真机验收清单](docs/device-acceptance.md)。

## License

[MIT](LICENSE)

