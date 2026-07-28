# Rust Windows 运行时资源探针

这是一次性原型，不是正式 Windows 客户端。

它只回答一个问题：原生 Rust 进程同时维持 Hook 事件入口、任务归并、加密载荷计算、手机连接、低频心跳和真实 Win32 前台窗口检查时，是否能为正式产品的 `100 MB` 专用工作集与空闲平均 CPU `< 1%` 目标留下足够余量。

探针不读取真实额度、提示词、回复、命令、文件路径或账号数据，不修改 Codex/ChatGPT 配置，也不对局域网开放端口。所有测试连接只绑定回环地址。

运行完整 10 分钟验证：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\spikes\rust-windows-runtime-probe\measure.ps1
```

快速烟雾验证：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\spikes\rust-windows-runtime-probe\measure.ps1 -DurationSeconds 30
```

测量结果写入同目录的 `PROTOTYPE-report.json`。该文件和整个探针都应在结论吸收到正式实现后删除。

当前 10 分钟结果已包含 Tokio、真实 Win32 前台窗口调用、双 TCP、JSON
任务归并和 ChaCha20-Poly1305 加密。它仍不包含正式托盘 UI、完整局域网
加密握手、安装器或真实 Android 连接，不能替代正式候选包的 10 分钟与
4 小时验收。
