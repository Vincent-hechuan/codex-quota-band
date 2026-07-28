# Xiaomi Wearable 通知探针（一次性原型）

这个目录只回答一个问题：

> 小米运动健康保持连接小米手环 10 时，第三方 Android App 能否通过小米 Wearable SDK 的 `NotifyApi.sendNotify()` 向手环发送原生消息？

它不是 Codex Quota 的正式 Android 客户端，不读取 Codex 数据，不连接 Windows，不连接蓝牙，也不修改现有 RPK。

## 探针来源

`dist/XMS-Wearable-Official-Demo-1.0.apk` 是从小米 Vela 官方文档提供的 `interconnect_dev_test_demo.zip` 中原样提取的 `app-debug.apk`，没有重新编译或修改。

- 官方文档：<https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html>
- 官方示例包：<https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip>
- APK 大小：`4,812,567` 字节
- APK SHA-256：`522B863C38F5502EF607C3D6B79FD13138BE194249A00D58A2E7CCB32A33C503`
- 已确认包含 APK v2 或更新格式的签名块
- 包名：`com.xiaomi.xms.wearable.demo`
- 版本：`1.0`
- SDK：`xms-wearable-lib_1.4_release.aar`
- 配套 RPK：`dist/com.xiaomi.xms.wearable.demo.debug.1.0.0.rpk`
- RPK 大小：`17,007` 字节
- RPK SHA-256：`ACF401F14B445EF4ABA437F3940D59E51DF3A0F1F52B592722BA9F9725BFCBE7`

官方源码的 Android Manifest 没有申请短信、联系人、定位、相册或直接蓝牙权限。Wearable 授权通过小米运动健康提供的服务完成。

## 测试前

小米官方示例要求先在手环安装与 Android Demo 包名和签名配套的 RPK，否则申请权限会返回 `APP not installed`。

1. 临时使用 AstroBox 连接手环。
2. 安装 `dist/com.xiaomi.xms.wearable.demo.debug.1.0.0.rpk`。
3. 确认手环应用列表出现 `interconnect-demo`。
4. 让 AstroBox 断开手环。
5. 使用小米运动健康重新连接手环，确认设备页显示已连接。
6. 先用微信或其他已启用的应用发一条普通通知，确认手环能够收到。
7. 保持小米运动健康运行，不要让 AstroBox 重新连接。

## 安装与授权

1. 把 `dist/XMS-Wearable-Official-Demo-1.0.apk` 发送到 Android 手机并安装。
2. 打开 `XMS Wearable Demo`。
3. 在底部进入 `Home`。
4. 点击 `Permission Demo`。
5. 页面应显示一个当前设备节点；如果没有节点，记录页面文字并停止测试。
6. 点击 `requestPermissions`。
7. 在小米运动健康出现的授权页面同意 `DEVICE_MANAGER` 和 `NOTIFY`。
8. 点击 `checkPermissionGranted`，确认两个权限均为 `true`。

## 发送测试消息

1. 返回主页面，点击底部 `Notifications`。
2. 标题输入：`Codex`
3. 正文输入：`任务已完成`
4. 点击 `sendNotification`。
5. 记录手机页面最终显示的状态：
   - `sendNotify success`
   - `sendNotify failed:...`
   - 没有响应
6. 同时观察手环是否出现原生消息提醒。

## 测试后复验

1. 检查小米运动健康仍显示手环已连接。
2. 再发送一条普通手机通知，确认手环仍能收到。
3. 在小米运动健康手动同步一次设备数据，确认同步没有被探针破坏。

## 结果判定

| 结果 | 判定 |
| --- | --- |
| 授权成功、手环收到消息、小米运动健康仍连接 | 路线通过，可以开发自己的轻量 Android 伴侣 App |
| `APP not installed` | 手环未安装配套 RPK，或安装的 RPK 包名/签名与 APK 不匹配 |
| 找不到设备节点 | 当前小米运动健康或 Band 10 没有向旧版 SDK 暴露设备 |
| 授权失败 | SDK、账号区域、签名或小米运动健康版本不兼容 |
| `sendNotify success`，但手环无消息 | SDK 服务接受请求，但 Band 10/当前固件不支持实际通知 |
| 手环收到消息，但小米运动健康断开 | 路线不满足“不抢连接”的核心要求 |

测试完成后可卸载 `XMS Wearable Demo`。在结论明确前，不把这个 APK 作为正式依赖或发布产物。

## 2026-07-23 真机结果

设备：小米手环 10、Android 手机、小米运动健康。

- 只安装 APK 时，`requestPermissions` 返回 `APP not installed`。
- 安装官方配套 RPK、重新由小米运动健康连接手环后，权限申请成功。
- `NotifyApi.sendNotify()` 返回发送成功。
- 手环实际收到消息，并产生振动。

结论：小米 Wearable SDK 的原生通知路线已在小米手环 10 上通过核心真机验证。正式开发前还需确认发送后小米运动健康持续在线、普通手机通知继续转发、健康数据仍可同步；这三项通过后，才能确认该路线不会抢占日常主连接。

## 2026-07-24 `MessageApi` 直连验证

在同一台 Android 真机上继续使用官方 Demo 与官方配套 RPK：

- 小米 Wearable SDK 发现节点 `2024822414`，名称为「小米手环10」。
- `NodeApi.launchWearApp()` 返回 `launch app success`，证明手机可以直接唤起手环快应用。
- `MessageApi.sendMessage()` 发送 `CODEX_SPIKE_1952` 后返回 `send message success`。

这证明 Android → 小米运动健康 → 手环 RPK 的数据通道已经被官方 SDK 接受；它与 `NotifyApi.sendNotify()` 的普通通知通道不同。官方 RPK 是否按预期渲染自定义数据，仍需在手环端打开配套快应用进行一次人工确认。
