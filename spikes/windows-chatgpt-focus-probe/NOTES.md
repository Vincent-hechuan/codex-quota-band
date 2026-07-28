# Windows ChatGPT 前台焦点 Spike

状态：通过

日期：2026-07-24

本机官方客户端：

- Windows 包名：`OpenAI.Codex`
- 包版本：`26.715.10079.0`
- 前台窗口进程：`ChatGPT.exe`

## 问题

能否在不读取窗口标题、聊天内容或 UI 内部结构的前提下，可靠判断承载 Codex 的官方 ChatGPT Windows 客户端是否为当前前台应用？

## 验证

探针使用以下只读 Win32 信息：

- `GetForegroundWindow`
- `GetWindowThreadProcessId`
- 前台进程可执行文件路径
- Windows 应用包安装身份

实际桌面存在两个由官方安装包中 `ChatGPT.exe` 承载的可见顶层窗口。测试依次观察 Chrome 与官方 ChatGPT 客户端：

| 当前前台应用 | 是否匹配官方 ChatGPT 安装包 |
| --- | --- |
| Google Chrome | 否 |
| ChatGPT Windows 客户端 | 是 |

焦点切换后已恢复原先的 Chrome 窗口。

## 关键发现

- 不能只按 `codex.exe` 进程名识别。该进程没有承载当前顶层主窗口。
- 不能只按 `ChatGPT.exe` 文件名识别，其他路径可能存在同名程序。
- 应验证前台进程属于官方 Windows 应用包身份，再判断为官方 ChatGPT 客户端。
- 仅靠稳定的系统窗口与包身份无法判断客户端内部当前显示普通 ChatGPT 对话还是 Codex 工作界面。
- 读取窗口标题或 UI 自动化可以尝试区分内部页面，但会扩大隐私和兼容性风险，没有必要用于通知失焦判断。

## 结论

“应用失焦”定义为整个官方 ChatGPT Windows 客户端失焦，第二项高风险技术路径通过。任务数据范围只覆盖能够产生本地官方 Hook 事件的代理任务；普通云端聊天、手机 ChatGPT、Remote、API、CLI 和其他电脑不读取也不同步。

不再尝试区分客户端内部页面，也不依赖不稳定的窗口标题或 UI 识别。
