const SOURCE_STATUSES = ["ok", "partial", "unavailable", "paused"];
const WINDOW_STATUSES = ["current", "pending_sync", "unknown"];
const RESET_STATUSES = ["cached", "cached_derived", "missing", "unavailable"];
const COMPUTER_STATUSES = ["online", "offline", "paused"];
const CODEX_STATUSES = ["ok", "unavailable", "stale", "format_changed"];

function sanitizeSnapshotForBand(input, now = new Date()) {
  if (!input || input.protocolVersion !== 1) {
    throw new Error("unsupported snapshot protocol");
  }

  const nowMilliseconds = now.getTime();
  const windows = Array.isArray(input.windows)
    ? input.windows.map((window) => sanitizeWindow(window, nowMilliseconds)).filter(Boolean)
    : [];
  const rawItems = Array.isArray(input.resetInventory?.items)
    ? input.resetInventory.items
    : [];
  const items = rawItems
    .map(sanitizeResetItem)
    .filter((item) => item && Date.parse(item.expiresAt) > nowMilliseconds)
    .sort((left, right) => Date.parse(left.expiresAt) - Date.parse(right.expiresAt));
  const rawCount = integerAtLeast(input.resetInventory?.availableCount, 0);
  const availableCount = rawItems.length > 0 ? items.length : rawCount;
  const inputResetStatus = enumValue(
    input.resetInventory?.status,
    RESET_STATUSES,
    "unavailable",
  );
  const resetStatus =
    items.length !== rawItems.length && ["cached", "cached_derived"].includes(inputResetStatus)
      ? "cached_derived"
      : inputResetStatus;

  return {
    protocolVersion: 1,
    generatedAt: timestamp(input.generatedAt) ?? now.toISOString(),
    sourceStatus: enumValue(input.sourceStatus, SOURCE_STATUSES, "unavailable"),
    limitsCollectedAt: timestamp(input.limitsCollectedAt),
    windows,
    resetInventory: {
      status: resetStatus,
      availableCount,
      cachedAt: timestamp(input.resetInventory?.cachedAt),
      items,
    },
    link: {
      computer: enumValue(input.link?.computer, COMPUTER_STATUSES, "offline"),
      codex: enumValue(input.link?.codex, CODEX_STATUSES, "unavailable"),
    },
  };
}

function createBandView(snapshot, now = new Date()) {
  const sanitized = sanitizeSnapshotForBand(snapshot, now);
  const status = statusPresentation(sanitized);
  const hasVisibleData =
    sanitized.windows.length > 0 || Number.isInteger(sanitized.resetInventory.availableCount);
  const syncTimeText = hasVisibleData ? formatDateTime(sanitized.generatedAt) : "";
  const syncStatusTimeText = hasVisibleData
    ? formatSyncTime(sanitized.generatedAt, now)
    : "";
  const windows = sanitized.windows.map((window) => ({
    id: window.id,
    label: windowLabel(window),
    remainingPercent:
      window.status === "current" && Number.isInteger(window.remainingPercent)
        ? window.remainingPercent
        : null,
    remainingText:
      window.status === "current" && Number.isInteger(window.remainingPercent)
        ? `${window.remainingPercent}%`
        : "--",
    resetText: `${formatDate(window.resetsAt)}重置`,
    tone: window.status === "current" ? quotaTone(window.remainingPercent) : "unknown",
  }));
  const primaryQuota =
    windows.find((window) => window.label === "周额度") ?? windows[0] ?? null;
  const quotaRemainingPercent = primaryQuota?.remainingPercent ?? null;
  const quotaToneValue = primaryQuota?.tone ?? "unknown";
  return {
    sourceStatus: sanitized.sourceStatus,
    statusText: status.text,
    statusTone: status.tone,
    syncTimeText,
    statusTimeText: syncStatusTimeText
      ? status.cache
        ? `上次${syncStatusTimeText}`
        : syncStatusTimeText
      : "--:--",
    windows,
    quotaRemainingPercent,
    quotaRemainingText: primaryQuota?.remainingText ?? "--",
    quotaResetText: primaryQuota?.resetText ?? "暂无额度数据",
    quotaTone: sanitized.link.computer === "offline" ? "offline" : quotaToneValue,
    resetCountText: Number.isInteger(sanitized.resetInventory.availableCount)
      ? String(sanitized.resetInventory.availableCount)
      : "--",
    resetHintText: sanitized.resetInventory.items.length
      ? `最近到期 ${formatDate(sanitized.resetInventory.items[0].expiresAt)}`
      : ["missing", "unavailable"].includes(sanitized.resetInventory.status)
        ? "暂无重置数据"
        : "暂无可用重置",
  };
}

function errorStatusText(code) {
  const messages = {
    pairing_required: "需要配对",
    pairing_revoked: "配对已撤销",
    private_network_required: "局域网不可用",
    windows_unreachable: "电脑未连接",
    windows_response_error: "电脑响应异常",
    snapshot_too_large: "额度数据异常",
    unsupported_snapshot_protocol: "请更新应用",
  };
  return messages[code] ?? "同步失败";
}

function sanitizeWindow(window, nowMilliseconds) {
  if (!window || typeof window.id !== "string" || window.id.length === 0) return null;
  const minutes = integerAtLeast(window.windowMinutes, 1);
  const resetsAt = timestamp(window.resetsAt);
  if (minutes === null || resetsAt === null) return null;
  const isExpired = Date.parse(resetsAt) <= nowMilliseconds;
  const remaining = integerBetween(window.remainingPercent, 0, 100);
  return {
    id: window.id.slice(0, 128),
    name: typeof window.name === "string" ? window.name.slice(0, 64) : "custom",
    windowMinutes: minutes,
    remainingPercent: isExpired ? null : remaining,
    resetsAt,
    status: isExpired
      ? "pending_sync"
      : enumValue(window.status, WINDOW_STATUSES, "unknown"),
  };
}

function sanitizeResetItem(item) {
  const expiresAt = timestamp(item?.expiresAt);
  if (
    !item ||
    typeof item.id !== "string" ||
    typeof item.title !== "string" ||
    item.status !== "available" ||
    expiresAt === null
  ) {
    return null;
  }
  return {
    id: item.id.slice(0, 256),
    title: item.title.slice(0, 128),
    status: "available",
    expiresAt,
  };
}

function statusPresentation(snapshot) {
  if (snapshot.sourceStatus === "paused") {
    return { text: "同步已暂停", tone: "warning", cache: true };
  }
  if (snapshot.link.computer === "offline") {
    return { text: "离线", tone: "danger", cache: true };
  }
  if (snapshot.link.codex === "format_changed") {
    return { text: "数据格式变化", tone: "danger", cache: true };
  }
  if (snapshot.link.codex === "stale") {
    return { text: "显示缓存", tone: "warning", cache: true };
  }
  if (snapshot.sourceStatus === "unavailable") {
    return { text: "额度不可用", tone: "danger", cache: true };
  }
  if (snapshot.sourceStatus === "partial") {
    return { text: "部分数据缓存", tone: "warning", cache: true };
  }
  return { text: "已同步", tone: "healthy", cache: false };
}

function windowLabel(window) {
  if (window.name === "weekly" || window.windowMinutes === 10_080) return "周额度";
  if (window.name === "five_hour" || window.windowMinutes === 300) return "5 小时额度";
  return `${window.windowMinutes} 分钟额度`;
}

function quotaTone(value) {
  if (!Number.isInteger(value)) return "unknown";
  if (value < 20) return "danger";
  if (value <= 50) return "warning";
  return "healthy";
}

function formatClock(value = new Date()) {
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return { timeText: "--:--", dateText: "日期不可用" };
  const pad = (number) => String(number).padStart(2, "0");
  const weekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];
  return {
    timeText: `${pad(date.getHours())}:${pad(date.getMinutes())}`,
    dateText: `${date.getMonth() + 1}月${date.getDate()}日 ${weekdays[date.getDay()]}`,
  };
}

function formatDateTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "--";
  const pad = (number) => String(number).padStart(2, "0");
  return `${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function formatSyncTime(value, now) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "--:--";
  const pad = (number) => String(number).padStart(2, "0");
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  const current = now instanceof Date ? now : new Date(now);
  const sameDay = !Number.isNaN(current.getTime())
    && date.getFullYear() === current.getFullYear()
    && date.getMonth() === current.getMonth()
    && date.getDate() === current.getDate();
  return sameDay ? time : `${date.getMonth() + 1}月${date.getDate()}日 ${time}`;
}

function formatDate(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "--";
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

function timestamp(value) {
  if (typeof value !== "string" || Number.isNaN(Date.parse(value))) return null;
  return new Date(value).toISOString();
}

function enumValue(value, allowed, fallback) {
  return allowed.includes(value) ? value : fallback;
}

function integerAtLeast(value, minimum) {
  return Number.isInteger(value) && value >= minimum ? value : null;
}

function integerBetween(value, minimum, maximum) {
  return Number.isInteger(value) && value >= minimum && value <= maximum ? value : null;
}

module.exports = {
  createBandView,
  errorStatusText,
  formatClock,
  sanitizeSnapshotForBand,
};
