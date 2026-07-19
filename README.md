<p align="right"><a href="README_EN.md">English</a></p>

# 小米手环 10 Codex 额度

在小米手环 10 上随时查看 **Codex 周额度、下次重置日期和可用重置次数**。

<p align="center">
  <img src="assets/icon.svg" alt="Codex Quota 图标" width="96">
</p>

当前版本：**0.3.0**

## 手环页面预览

<table>
  <tr>
    <th align="center">已同步</th>
    <th align="center">未同步</th>
    <th align="center">缓存</th>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/band-synced.png" alt="已同步状态" width="180"></td>
    <td align="center"><img src="docs/images/band-unsynced.png" alt="未同步状态" width="180"></td>
    <td align="center"><img src="docs/images/band-cached.png" alt="缓存状态" width="180"></td>
  </tr>
</table>

## 开始前

使用本项目之前，需要先在 Android 手机上安装 [AstroBox](https://astrobox.online/downloads/)，并使用 AstroBox 连接小米手环 10。AstroBox 的安装、权限设置和连接手环不属于本项目的安装流程，如果尚未完成，可以自行在网上搜索对应教程。

## 需要什么

- Windows 10/11 x64 电脑，并已登录和使用 Codex
- 小米手环 10
- 能够运行 [AstroBox](https://astrobox.online/downloads/)、并可连接手环的 Android 手机
- 手机与电脑连接同一个可信局域网

理论上，只要 Android 手机能正常运行 AstroBox 并连接小米手环 10，就可以使用；不同品牌和系统版本仍可能存在后台运行或 VPN 设置差异。iPhone 暂不支持。

## 下载

从 [Releases](https://github.com/Vincent-hechuan/codex-quota-band/releases) 下载同一版本下的三个文件：

| 安装位置 | 文件 |
| --- | --- |
| Windows 电脑 | `Codex-Quota-Setup-0.3.0.exe` |
| Android / AstroBox | `codex-quota-astrobox-0.3.0.abp` |
| 小米手环 10 | `com.codex.quota.debug.0.3.0.rpk` |

三个组件的版本号应当一致。首个公开测试包发布前，Releases 页面可能暂时为空。

## 安装

### 1. 安装 Windows 程序

1. 双击 `Codex-Quota-Setup-0.3.0.exe`。
2. 安装完成后，程序会常驻 Windows 通知区域；如果没看到，请点击任务栏右下角的 `^`。
3. 当前测试版没有商业代码签名，Windows 可能提示“未知发布者”。请只从本仓库下载，并核对 Release 页面提供的 SHA-256。

### 2. 安装 AstroBox 插件

1. 打开 Android 手机上的 AstroBox。
2. 点击底部的「插件」。
3. 点击右上角的 `+`，导入本地插件。
4. 选择下载好的 `codex-quota-astrobox-0.3.0.abp`。
5. 导入完成后重启 AstroBox。
6. 再次进入「插件」，应当可以看到「Codex 额度桥接」。
7. 手动打开一次「Codex 额度桥接」，让扫码入口完成注册。

### 3. 安装手环应用

1. 打开 AstroBox，进入已连接的小米手环 10 设备页面。
2. 找到「快应用数量」卡片。
3. 点击卡片右上角的齿轮图标。
4. 点击右上角的 `+`，导入本地 RPK 文件。
5. 选择 `com.codex.quota.debug.0.3.0.rpk`。
6. 等待安装完成，手环应用列表中会出现「Codex 额度」。

## 首次配对

1. 确保手机和电脑连接同一个 Wi-Fi 或可信局域网。
2. 右键 Windows 通知区域中的 Codex Quota 图标，选择「显示配对信息…」。
3. 用 Android 系统相机扫描电脑上的二维码。
4. 选择使用 AstroBox 打开，等待插件显示配对成功。
5. 打开手环上的「Codex 额度」，数据通常会在数秒内出现。

二维码和 6 位配对码只在短时间内有效。正常使用时不需要输入电脑地址；扫码失败时，才展开 Windows 配对窗口中的高级信息进行手动配对。

## 常见问题

### 扫码后只打开 AstroBox 插件列表

先手动打开一次「Codex 额度桥接」，返回后重新扫码。如果仍然无效，重新导入 ABP 再试。

### 插件找不到 Windows

- 确认手机和电脑位于同一局域网。
- 如果开启了 VPN/代理，让 AstroBox 和局域网地址绕过 VPN。
- 首次启动 Windows 程序时，允许它通过 Windows 防火墙的专用网络。
- 不要在公司访客 Wi-Fi、公共 Wi-Fi 或开启了客户端隔离的网络中配对。

### 手环显示离线或数据不更新

- 确认 AstroBox 仍在后台运行并连接手环。
- 关闭 Android 对 AstroBox 的省电限制，并允许后台运行、蓝牙和附近设备权限。
- 如果 AstroBox、手机或插件重启过，从 Windows 托盘重新生成二维码并配对。

### 与“小米运动健康”争抢连接

如果 AstroBox 经常断开，可暂时停止小米运动健康，先完成安装和配对；之后再根据手机系统的后台设置调整两者共存。

## 隐私说明

- 数据只在你的 Windows、Android 手机和手环之间传输，不经过本项目的云服务器。
- 只读取和显示额度摘要，不读取或发送对话、提示词、项目文件和终端内容。
- 不读取 ChatGPT/Codex Cookie、登录密码或访问令牌。
- Windows 托盘菜单可以随时撤销所有已配对设备。
- 只建议在可信局域网中使用，不要把 Windows 服务端口暴露到公网。

## 卸载

- Windows：在「设置 → 应用 → 已安装的应用」中卸载 Codex Quota。
- Android：在 AstroBox 中移除「Codex 额度桥接」插件。
- 手环：在 AstroBox 中卸载「Codex 额度」应用。

<details>
<summary>开发者构建与测试</summary>

要求 Node.js 24+、PowerShell、Rust/WASI 环境和小米 Vela 快应用工具链。

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

安全模型见 [docs/security.md](docs/security.md)。

</details>

## 说明

这是社区制作的开源项目，不是 OpenAI、小米或 AstroBox 的官方产品。

使用 [MIT License](LICENSE)。
