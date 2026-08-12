# 更新日志 / Changelog

本文件记录每个公开版本对用户可见的变化。版本发布日期以 GitHub Release 为准。

This file records user-visible changes in each public version. Release dates follow GitHub Releases.

> `0.3.0` 之前，Windows、AstroBox 和手环 RPK 的版本号并非始终同步；以下早期条目会注明对应组件。
>
> Before `0.3.0`, Windows, AstroBox, and band RPK versions were not always synchronized. Early entries identify the affected component.

## [0.6.3] - 2026-08-13

### 新增 / Added

- 安卓手机提供统一“连接电脑”页面：二维码改为 App 内置扫描，不再依赖不同品牌的系统相机；无法扫码时可输入 Windows 显示的 6 位配对码。
- 手动配对自动发现同一局域网中的候选电脑，并要求核对双端 `XXXX-XXXX` 安全校验码；发现公告不包含配对码、长期令牌或账号内容。

### 改进 / Changed

- 安卓“需要授权”和“等待查看”使用无声、请求振动的独立通知通道；实际振动和悬浮方式由手机系统通知设置决定。手机 App 自身前后台不再额外覆盖“从不 / 失焦 / 始终”的通知时机设置。
- 安卓设置页的提醒分组精简为通知时机、手机通知、手环通知和系统通知设置；通知时机副标题随当前选项变化，分类级调整统一交给系统设置。
- 手机连接页改用与首页、设置页一致的背景、卡片、字体、间距和线性图标，扫码与手动配对不再使用独立的临时视觉样式。
- 手机连接页补充 Windows 托盘入口引导，帮助首次使用者找到“连接手机”。
- 手动配对改为六个独立数字位，焦点从最左侧开始并逐位推进；连接按钮跟随内容与键盘位置，不再贴近屏幕底部。
- Windows 配对窗口直接展示二维码、6 位配对码、安全校验码和 5 分钟有效期，不再要求普通用户输入 IP 地址；窗口保持单实例，需要新凭据时由用户明确点击“刷新配对码”。
- Windows 配对窗口收敛标题、正文和辅助文字层级，放大二维码、主要操作指引与 6 位配对码；底部按钮使用抗锯齿圆角绘制。
- Windows 主程序和安装界面嵌入统一的 CodexQuota 图标，资源管理器和快捷方式不再显示通用程序图标。

### 修复 / Fixed

- 修复 Windows 已取得官方额度缓存后，仍每 5 秒递归读取全部历史 Codex 会话文件的问题，显著降低后台 CPU、磁盘读取和瞬时内存占用。
- 修复正式版压缩后 ML Kit 扫码组件无法通过反射初始化，导致授权相机后返回设置页、再次进入稳定闪退的问题。
- 修复扫码页退出时相机初始化仍可能继续执行的问题，并在扫码组件不可用时安全降级到手动配对提示。
- 配对完成并建立加密同步连接后立即请求当前额度确认，减少首页从“缓存”切换为“已同步”的等待。
- 修复 Windows 配对窗口底部说明、有效期和按钮文字叠压，以及重复点击托盘入口产生多个窗口并暗中作废旧配对码的问题。

### 验证 / Validation

- 三端产品版本统一为 `0.6.3`；安卓手机和手环内部安装序号均为 `605`。
- Windows、安卓手机和小米手环 10 已完成自动验证、覆盖安装和三端真机联动验收。
- Windows 安装包已通过最小系统 PATH 独立启动与 PE 导入表检查，普通用户不需要另行寻找 DLL 或安装开发工具。

## [0.6.2] - 2026-08-04

### 新增 / Added

- Windows 托盘右键菜单直接显示 5 小时额度和周额度。

### 改进 / Changed

- 统一三端颜色语义：已同步为绿色、缓存为黄色、离线为红色、处理中为蓝色；缓存状态不再覆盖额度本身的红黄绿阈值颜色。
- 额度 50% 起统一使用绿色；可用重置统一为 0 次红色、1 次及以上绿色、未知灰色。
- 手机首页和任务页使用彩色圆点表达任务状态，标题、阶段和时间保持主题自适应的中性文字；任务页补齐安全阶段摘要。
- 手环任务页只显示任务状态和相对时间，不显示具体工具阶段；缓存与离线时间统一使用分、小时、天、周。

### 修复 / Fixed

- 修复手环实际可以同步任务，但手机首页仍错误显示“未连接”的状态刷新问题。
- 修复手环中英文混排任务标题在两行省略时泄漏单个裁切字符的问题，标题改为稳定的单行省略。
- Windows 正式安装包使用静态运行库构建，并在仅保留 Windows 系统目录的 PATH 下执行启动检查，避免普通用户因缺少 `libunwind.dll` 等开发环境运行库而无法启动。

### 验证 / Validation

- 三端产品版本统一为 `0.6.2`；Android 与手环内部安装序号均为 `604`。
- 三端正式产物已完成自动验证；Windows 安装包已通过最小系统 PATH 独立启动和非系统 DLL 依赖检查。
- 正式产物校验值已写入构建文档，三端真机验收已经通过。

## [0.6.1] - 2026-07-31

### 修复 / Fixed

- 修复手机端 Wearable 状态异步结果乱序时，手环实际已连接却显示“未连接”的问题。
- 手环连接短暂滞后时，手机端进行有限重试；不新增常驻服务或后台轮询。
- 保持 Windows、安卓手机和手环的产品版本为 `0.6.1`。

## [0.6.0] - 2026-07-29

### 新增 / Added

- 在 Windows、安卓手机和小米手环 10 上显示 Codex 5 小时额度、周额度和重置时间。
- 手机端加入检查更新入口；只有用户确认后才会打开 GitHub 下载页面。

### 修复 / Fixed

- 统一三端额度状态颜色和时间显示，并明确区分“已同步”“缓存”与“离线”。
- 改进手机后台同步提示与手环页面可读性。

## [0.3.1] - 2026-07-21

### 修复 / Fixed

- Codex 重装或本地网络缓存暂时缺失时，继续更新周额度，同时保留上一份仍未到期的可用重置数据。
  After Codex is reinstalled or its local network cache is temporarily unavailable, keep updating the weekly quota while retaining the last unexpired reset-credit data.
- 所有旧重置都已经到期时，不再继续显示过期数量。
  Do not retain reset-credit counts after every cached credit has expired.
- 将过长的「部分数据缓存」状态缩短为「缓存」，避免同步胶囊文字被裁切。
  Replace the long partial-data status with the compact 「缓存」 (Cached) label so it fits the status pill.

### 文档 / Documentation

- 增加“重装 Codex 后重置次数显示 `--`”的恢复步骤。
  Add recovery steps for missing reset credits after reinstalling Codex.
- 将 README 预览中的「未同步」修正为实际存在的「离线」状态。
  Rename the nonexistent “Not synced” preview to the actual “Offline” state.
- 根据当时真机验证结果，补充 Android 与 iPhone 的兼容性说明；这是 `0.3.1` 历史分支事实，不构成当前支持范围。
  Document Android and iPhone compatibility based on then-current device testing; this is a `0.3.1` historical fact and not part of the current support scope.

### 验证 / Validation

- Windows、AstroBox 插件和小米手环 10 RPK `0.3.1` 已完成自动化测试与真机验证。
  The Windows app, AstroBox plugin, and Xiaomi Smart Band 10 RPK `0.3.1` passed automated and real-device verification.

## [0.3.0] - 2026-07-20

### 新增 / Added

- 首个公开测试版本，包含 Windows 托盘程序、AstroBox 插件和小米手环 10 快应用。
  First public test release with the Windows tray app, AstroBox plugin, and Xiaomi Smart Band 10 quick app.
- 支持二维码自动配对、周额度与重置次数显示、系统时间、离线缓存和额度分色。
  Add QR pairing, weekly quota and reset-credit display, system time, offline cache, and quota color states.
- Windows 新增隔离的二维码配对窗口、可复制的高级手动信息和高对比托盘图标；AstroBox 通过 DeepLink 直接接收一次性配对载荷。
  Add an isolated QR pairing window, copyable advanced manual details, a high-contrast tray icon, and direct AstroBox DeepLink pairing.
- 三个公开组件首次统一版本号，并加入自动化版本契约检查。
  Unify all three public component versions and add an automated version-contract check.

## [0.2.1] - 手环 RPK 早期开发版 / Band RPK development build

### 改进 / Changed

- 根据真机反馈撤回外缘进度环，恢复经过验证的无卡片、大字和水平分割线布局。
  Remove the outer progress ring after device feedback and restore the validated card-free, large-type layout with a divider.
- 在顶部加入系统时间，并将同步状态压缩为固定尺寸的彩色胶囊。
  Add the system clock at the top and compress sync status into a fixed-size colored pill.
- 简化重置区域：突出彩色数量，保留白色“次”和短到期日期。
  Simplify the reset section with a colored count, white unit, and short expiry date.
- 将页面字体收敛为少量固定字号，并显著减小 RPK 体积。
  Reduce the page to a small fixed type scale and significantly shrink the RPK size.

## [0.2.0] - 早期开发版 / Development build

### 手环 RPK / Band RPK

- 试验沿屏幕外缘从 12 点方向开始的额度进度环，并将周额度提升为第一视觉层级。
  Experiment with a quota ring beginning at 12 o'clock and promote the weekly quota to the primary visual level.
- 加入系统时间、日期以及正常、低额度、离线三种渐变和分色状态。
  Add system time, date, and separate gradient/color states for normal, low-quota, and offline modes.
- 将公共状态逻辑改为 CommonJS，绕过 Vela 工具链生成无效 `exports` 的问题。
  Move shared state logic to CommonJS to avoid invalid `exports` generated by the Vela toolchain.

### Windows

- 将托盘图标改为随安装包携带的高对比 PNG，并增加空图像检测。
  Replace the runtime tray placeholder with a packaged high-contrast PNG and add empty-image validation.

## [0.1.3] - 手环 RPK 早期开发版 / Band RPK development build

### 改进 / Changed

- 根据三种真机尺寸原型确定“无卡片分区”方向。
  Select the card-free layout after comparing three device-sized prototypes.
- 删除标题、卡片、进度条、手动刷新和冗余说明，只保留同步状态、周额度、重置日期和可用重置次数。
  Remove titles, cards, progress bars, manual refresh, and redundant copy, keeping only the sync state, weekly quota, reset date, and reset credits.
- 放大主数字和辅助文字，并固定状态区域高度，避免离线切换时页面跳动。
  Enlarge primary and supporting text and fix the status area height to prevent layout shifts when going offline.

## [0.1.2] - 手环 RPK 早期开发版 / Band RPK development build

### 改进 / Changed

- 压缩顶部标题区，将周额度、重置次数和最近到期信息移到首屏。
  Compress the header and move weekly quota, reset count, and nearest expiry onto the first screen.
- 提高辅助文字字号；真机测试仍发现整体过密、灰色文字可读性不足，因此继续迭代。
  Increase supporting text size; device testing still found the page too dense and low-contrast, leading to further iteration.

## [0.1.1] - 手环 RPK 早期开发版 / Band RPK development build

### 修复 / Fixed

- 修复 RPK 打开后停留在启动图的问题；根因是 Vela 工具链错误转换独立 ES 模块。
  Fix the RPK remaining on its launch image due to incorrect standalone ES-module conversion by the Vela toolchain.
- 切换到小米手环 10 原生 `212×520` 画布，首次在真机上读取并显示 Windows 实时快照。
  Switch to the native `212×520` canvas and display a live Windows snapshot on the real band for the first time.

## [0.1.0] - 首个端到端候选 / First end-to-end candidate

### 新增 / Added

- Windows 托盘程序读取经过隐私裁剪的 Codex 周额度和可用重置摘要，并通过只读 Snapshot v1 服务提供给手机。
  Add a Windows tray app that exposes privacy-filtered weekly quota and reset-credit summaries through the read-only Snapshot v1 service.
- AstroBox 插件完成手机、Windows 与手环之间的安全转发，支持一次性配对码和可撤销令牌。
  Add secure forwarding between Android, Windows, and the band with one-time pairing codes and revocable tokens.
- 支持当前用户安装、开机启动、托盘摘要和手动配对流程。
  Add per-user installation, login startup, tray summaries, and manual pairing.

### 已知问题 / Known issue

- 初始 RPK 在部分真机上停留在启动图，该问题在 `0.1.1` 修复。
  The initial RPK could remain on its launch image on real devices; this was fixed in `0.1.1`.
