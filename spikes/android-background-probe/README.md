# Android 后台探针

这是一次性真机探针，不是正式「Codex额度」Android App。

它只回答一个问题：不使用前台服务、不显示常驻状态通知时，普通 Android 应用进程在用户完成应用加锁、允许自启动和电池策略无限制后，锁屏期间能否持续接收本地连接事件并发送普通任务通知。

Android 13 及以上若未授予通知权限，事件仍会写入本地计数，但不会发出测试通知。

## 探针边界

- 不读取额度、提示词、回复、命令、文件或账号信息。
- 不接入公网服务。
- 不接入小米 Wearable SDK。
- 测试连接通过 USB ADB reverse 转发到电脑回环端口，不开放 Windows 局域网端口。
- 只持久化事件计数、连接次数、最后事件类型和时间。
- 没有 Android Service、Foreground Service、WakeLock、Alarm 或定时任务。

## 一次运行

1. 在手机开启开发者选项和 USB 调试，并通过 USB 连接电脑。
2. 运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\spikes\android-background-probe\run-device-test.ps1
```

3. 手机打开「Codex 后台探针」，允许通知，确认“连接：已连接”。
4. 把 App 设为后台加锁、允许自启动、电池策略无限制。
5. 返回桌面并锁屏，在约定的短时测试窗口内不要重新打开该 App，USB 保持连接。
6. 测试结束后运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\spikes\android-background-probe\collect-result.ps1
```

## 通过标准

- 电脑日志在约定的测试窗口内始终有一个客户端，或断线后能自动恢复。
- App 进程仍存在。
- 本地持久化事件计数持续增长，最后事件时间接近收集时间。
- “等待查看”和“需要授权”通知按事件出现。
- 系统通知栏没有常驻状态通知。

如果 Android 系统杀死进程且不能自行恢复，第四项门槛不通过。不得把结果包装成“偶尔漏消息”，也不得静默改用前台服务。
