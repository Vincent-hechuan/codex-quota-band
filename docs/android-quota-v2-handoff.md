# 给 Android Agent 的接入说明（Windows 已就绪）

下面这段可以直接作为任务上下文转交。它描述已完成的 Windows 能力、可用的同步事实和产品意图；具体 Android 架构、UI 组织和测试策略请结合现有代码自主判断，不需要照搬 Windows 的实现方式。

> Windows 原生端已经能在本机低频读取 Codex 登录凭证，并查询周额度、重置卡和上游数据新鲜度。当前 Android 已使用 quota v2 接收重置卡发卡/到期时间；请在此基础上接入 quota v3 的 `upstreamFreshness`，把“手机已连接 Windows”与“Windows 最近是否已成功向 ChatGPT/Codex 确认额度”区分开来。`usage.status=current` 才表示当前额度已确认；`cached` 应明确是缓存；`unavailable` 则应明确尚未确认。具体实现请结合现有代码自主判断，并保持 v1/v2 兼容。Windows 不会也不应把 token、Cookie、原始响应、重置卡唯一 ID/标题/描述同步到 Android。

## 可用数据与含义

Android 只通过既有 TLS 1.3 WSS 同步接收下列 v2 白名单字段，并在本地转换为北京时间显示：

```json
{
  "protocolVersion": 2,
  "resetInventory": {
    "status": "cached",
    "availableCount": 1,
    "cachedAt": "2026-07-26T10:00:00.000Z",
    "items": [
      {
        "status": "available",
        "grantedAt": "2026-07-25T09:00:00.000Z",
        "expiresAt": "2026-07-31T19:49:39.737Z"
      }
    ]
  }
}
```

Android 明确声明支持 quota v3 后，v3 快照还会包含：

```json
{
  "upstreamFreshness": {
    "usage": {
      "status": "current",
      "lastAttemptAt": "2026-07-28T10:00:00.000Z",
      "lastSuccessAt": "2026-07-28T10:00:00.000Z"
    },
    "resetInventory": {
      "status": "cached",
      "lastAttemptAt": "2026-07-28T10:00:00.000Z",
      "lastSuccessAt": "2026-07-28T09:45:00.000Z"
    }
  }
}
```

`usage` 表示周额度上游确认状态，`resetInventory` 表示重置卡上游确认状态。状态为 `current`、`cached` 或
`unavailable`；时间均为 UTC RFC 3339。它们不包含 VPN、网络错误、响应体或任何凭证信息。

时间均为 UTC RFC 3339。`grantedAt` 可以为 `null`（旧缓存或来源未提供时），此时 UI 显示“发卡时间待同步”，
不得猜测。`expiresAt` 始终是来源实际提供的时间。

正式 schema：重置卡时间为 `contract/snapshot-v2.schema.json`；上游新鲜度为
`contract/snapshot-v3.schema.json`。v2 不再增加字段，避免破坏已经安装的严格解析客户端。

## 接入时值得注意的事实

- Client Hello 可以声明支持 quota v1/v2/v3；服务端选择双方最高版本。现有 `[1,2]` 客户端继续收到原样 v2，只有声明 `[1,2,3]` 才收到上游新鲜度。
- v2 和 v3 分别以上述 schema 为准。Android 可以按当前代码的会话和契约模式选择最合适的解码与协商实现，但不要把未协商的快照当成可信数据。
- `grantedAt` 允许为 `null`；这表示来源未提供或旧缓存，显示“发卡时间待同步”即可，不要推算。
- 所有时间是 UTC RFC 3339，面向用户显示时转换北京时间。短而清楚的双行呈现即可，例如“发卡 7月25日 17:00 / 到期 8月1日 03:49”。
- 协商到 v3 后，同步文案应依据 `upstreamFreshness.usage.status`，不能只看 WSS 是否连接：`current` 可表示已同步，`cached` 应显示缓存额度，`unavailable` 应表示等待额度确认；电脑离线仍由 `link.computer` 表达。
- 若需要发给手环，继续使用裁剪后的独立摘要，只包含用户需要的时间、数量和同步状态，避免引入卡片身份信息或任何凭证。

## 兼容性要求

- 已安装的 Android 仍声明 `[1]`，Windows 会返回 quota v1，行为不变。
- 当前 Android 声明 `[1,2]`，Windows 返回保持兼容的 quota v2；支持上游新鲜度的 Android 可声明 `[1,2,3]`。
- 若 Windows 回 `quotaVersion: 1`，新版 Android 必须继续可用。
- 收到 `quotaVersion: 2` 却缺少 v2 必填字段，必须断开并按既有重连策略处理，不能尝试按 v1 宽松解析。

## 建议验收点

- v1 Windows 与 v2 Windows 均能正常连接、显示并在重连后保持一致。
- v2 能正确显示 `grantedAt` / `expiresAt`，而 `grantedAt:null` 不会被猜测。
- 已连接但 Windows 上游查询失败时，手机明确显示缓存或等待确认，不显示“已同步”。
- Android 诊断、日志和手环摘要不会含有 token、Cookie、原始接口响应或卡片身份信息。

## Windows 已完成的配合

- Windows 在启动后和每 15 分钟最多一次向 `rate-limit-reset-credits` 直接查询；不会随 5 秒 UI 检查直连。
- 同一低频周期还直接查询 `/wham/usage`。请求失败时保留最后可信值，但将对应 `upstreamFreshness`
  立即降为 `cached`；没有可信值则为 `unavailable`。
- HTTP 401 被安全归类为“凭证不可用”，不会把接口响应或令牌同步出去。
- Windows 只在 Android 声明支持 v2 时发送 `grantedAt`；v1 设备不会收到未知字段。

## Android 接入状态（2026-07-29）

- Android Client Hello 已声明 `supportedQuotaVersions: [1, 2, 3]`，任务版本仍为 `[1]`。
- Android 会保存当前连接的 `quotaVersion`，只按匹配的 Server Hello 解析快照；连接断开或尚未协商时拒绝快照。
- v1 继续保留 `id/title` 的兼容解析；v2 只生成无身份的重置卡对象，并把 `grantedAt` 转为可空的
  `grantedAtMs`。发卡时间缺失时显示“发卡时间待同步”。
- v3 已按 `contract/snapshot-v3.schema.json` 严格解析 `upstreamFreshness`；未知字段、缺少必填字段、
  未协商 v3 或版本不匹配的快照都会被拒绝。v1/v2 回退路径保持原行为。
- Android 将 WSS 最近收包时间与额度上游最近成功确认时间分开保存。顶部状态、额度卡和重置卡依据
  `upstreamFreshness` 显示“已同步”“缓存”或“待同步”，不会再因为 WSS 在线或任务刚更新就把额度写成
  “已同步”；电脑断开时仍明确显示“离线”。
- 顶部状态胶囊只使用受控短文案，最长时间显示封顶为 `99天+`；Compose 同时使用 `112dp` 最大宽度、
  单行和省略兜底，动态时长不得撑破胶囊圆角或挤占标题。
- Android 首页现在按 `Asia/Shanghai` 显示每张卡的发卡时间和到期时间；v1 或空值不会猜测发卡时间。
- `XiaomiWearableBridge` 已改为生成独立的手环摘要版本 `2`，契约见
  `contract/wearable-quota-v2.schema.json`；重置卡只下发状态、发卡时间和到期时间，不下发 v1 的卡片
  `id`、`title`、`description`。v3 上游状态只折叠为现有摘要的同步/缓存状态，不下发原始新鲜度对象；
  手环 RPK 源码未在本次接入中修改。
- Android 下拉刷新会发送闭合的 `refresh_request`，只包含 transport version、当前 connection ID 和
  固定 `quota` scope。Windows 校验已认证连接并使用 10 秒冷却，再立即查询上游并发布新快照。
- 真机联调已与当前 Windows 候选协商 quota v3：WSS 在线且上游额度为 `current` 时显示“已同步”，
  下拉后 Windows 的 `lastAttemptAt` 实际更新并返回新快照。缓存、不可用、离线和超长时长由单元测试覆盖。
