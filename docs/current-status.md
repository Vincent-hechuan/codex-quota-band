# 0.5.2 当前状态与主任务交接

更新时间：2026-07-29。本文是未来主任务的**当前事实入口**，用于避免 Windows、Android 与手环
各自的交接记录互相覆盖。它不替代产品决策：产品语义以根目录 `CONTEXT.md` 为准；历史构建和
真机证据以 `docs/build-verification.md`、`docs/device-acceptance.md` 为准。

## 1. 当前发布状态

- 产品代码、配置与本地正式候选产物现为统一的 `0.5.2 / versionCode 502`；Windows 安装器、Android release APK 与手环 release RPK 已完成自动验证。Android 已在验收手机完成正常覆盖升级；Windows 与手环待正常升级验收。尚未提交、推送或创建 GitHub Release。
- `0.5.1 / versionCode 501` 已完成三端构建与 Android 覆盖升级，但随后修复了手环“可用重置”数值错误继承周额度颜色的问题。因此按正常更新语义统一升级到 `0.5.2 / versionCode 502`，使大于零的可用重置固定为绿色、零次为红色，不以相同版本强行覆盖。

## 2. 运行链路与不可变边界

日常链路：`ChatGPT Windows Hook → Windows 原生托盘 → TLS 1.3 WSS → Android Codex额度 → 小米运动健康/Wearable SDK → 小米手环 10 RPK`。

- 只支持 Android；不为 iPhone 或旧 AstroBox 日常桥接增加兼容层。
- AstroBox 只用于侧载或升级 RPK；日常蓝牙、健康同步和普通通知由小米运动健康保持。
- 只同步额度摘要、重置摘要、连接状态、同步时间、短任务标题和任务状态；不读取或传输提示词、回复、
  命令、路径、日志、Cookie、密码或账号内容。Windows 可在本机读取 Codex 访问令牌，仅向官方额度接口低频确认；令牌不进入日志、缓存、诊断、局域网同步、Android 或手环。
- 任务状态固定为：`处理中`、`需要授权`、`等待查看`。`Stop` 只能表示“等待查看”，不能写成已完成、失败或终止。
- 默认通知为“仅在 ChatGPT 失焦时”；处理中静默；手机和手环渠道独立；不新增常驻前台服务。

## 3. 三端当前实现

| 组件 | 已实现 | 当前验证/限制 |
| --- | --- | --- |
| Windows | 单实例托盘、二维码配对、连接与诊断、Hook 安装/修复、上游额度确认、TLS 1.3 WSS、任务标题归并 | `0.5.2` workspace 测试 70/70 通过并生成新安装器；待用户正常覆盖升级。ChatGPT 仍需要用户在“设置 → 钩子 → 信任全部钩子”中完成信任。 |
| Android | Compose 首页/任务/设置、系统相机扫码、WSS 重连、任务本机隐藏、通知决策、Wearable Bridge、quota v1/v2/v3 协商 | `0.5.2` 单测 86/86、lint、release 构建与 v2 签名验证通过；已在验收手机覆盖升级，版本确认 `502 / 0.5.2`。设置页提供“检查手环连接”以重新请求 Wearable 权限。 |
| 手环 | 小米手环 10、Vela RPK、额度/重置摘要、最多 3 条任务、状态色、同步新鲜度 | `0.5.2` release 构建后测试 24/24 通过；可用重置大于零固定绿色、零次为红色。RPK 使用与 Android release APK 相同的本机发布签名，待通过 AstroBox 正常升级。 |

## 4. 后续对齐项

以下不是验收阻塞项，而是后续统一细调时应优先处理的项目。

1. **正式包升级验收**：代码与配置已统一为 `0.5.2 / versionCode 502`。Android 已覆盖升级；Windows 与手环待正常升级，并复验已有配对、额度、任务、缓存和两页纵向 swiper，特别确认可用重置颜色不再随周额度变色。
2. **额度新鲜度文案**：Android、Windows 与手环统一采用“已同步 / 缓存 / 待同步 / 离线”的用户可见语义；
   内部仍使用 `current / cached / unavailable` 协议状态。
3. **手环预览素材**：`docs/images/` 与 `docs/band-ui-preview/` 中有早期连续长页预览，不能作为
   当前双页 RPK 的验收依据。后续改手环 UI 前，需先按两页 `212×520` 布局生成新的预览。

## 5. 当前验收矩阵

| 场景 | 状态 |
| --- | --- |
| Windows → Android 二维码配对、WSS 同步 | 已验证 |
| Hook 标题/状态、任务本机移除 | 已验证 |
| ChatGPT 失焦、Android 后台/锁屏的手机与手环提醒 | 已验证（短时场景） |
| Android 首页、任务页、设置页 | 用户真机验收通过 |
| Windows 当前候选完整用户验收 | 用户确认通过 |
| 前一手环 `0.4.9` 安装、可读性、滑动手感和缓存切换 | 用户确认通过 |
| `0.5.0-rc.1` 候选验收 | 已完成，作为功能基线 |
| `0.5.0` 三端正式包验收 | 已完成，作为后续升级基线 |
| `0.5.1` 正式构建与 Android 覆盖升级 | 已完成，作为 `0.5.2` 升级基线 |
| `0.5.2` 正式构建与自动验证 | 已完成，Android release 签名已验证并覆盖升级 |
| `0.5.2` Windows 安装器覆盖升级 | 已通过；已有配对与同步正常 |
| `0.5.2` 手环 release RPK 正常升级与颜色/联动验收 | 已通过；同步正常，可用重置大于零为绿色 |
| Git 提交、推送和 GitHub Release | 尚未请求 |

## 6. 新主对话的工作顺序

1. 先读 `AGENTS.md`、`CONTEXT.md`、本文、`docs/architecture.md` 和 `docs/development-guide.md`。
2. 先以第 4 节为三端细调清单；产品语义或协议变化仍需先由用户确认。
3. 修改 Android/手环 UI 前先制作预览；手环预览必须为两页 212×520，并在用户确认后才改 RPK 源码。
4. 每次改动更新：实现事实写入 `docs/architecture.md`，构建/产物写入
   `docs/build-verification.md`，真机结果写入 `docs/device-acceptance.md`，本文件只维护当前摘要和
   未决冲突。
5. `CHANGELOG.md` 只记录已经发布的用户可见版本，不写本地候选条目。

## 7. 快速入口

- 产品语义与隐私边界：`CONTEXT.md`
- Agent 规则与命令：`AGENTS.md`
- 组件、协议与数据流：`docs/architecture.md`
- 当前和历史构建证据：`docs/build-verification.md`
- 真机验收与待测项：`docs/device-acceptance.md`
- Android quota v2/v3 接入细节：`docs/android-quota-v2-handoff.md`
- 跨端视觉语言：`docs/ui-design-system.md`
