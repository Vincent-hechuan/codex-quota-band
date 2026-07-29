const SOURCE_STATUSES = ["ok", "partial", "unavailable", "paused"];
const WINDOW_STATUSES = ["current", "pending_sync", "unknown"];
const RESET_STATUSES = ["cached", "cached_derived", "missing", "unavailable"];
const COMPUTER_STATUSES = ["online", "offline", "paused"];
const CODEX_STATUSES = ["ok", "unavailable", "stale", "format_changed"];
const MAX_WINDOWS = 8;
const MAX_RESET_ITEMS = 3;
const MAX_RESET_COUNT = 99;

function sanitizeSnapshotForBand(input, now = new Date()) {
  if (!input || (input.protocolVersion !== 1 && input.protocolVersion !== 2)) {
    throw new Error("unsupported snapshot protocol");
  }
  assertExactKeys(input, ["protocolVersion", "generatedAt", "sourceStatus", "limitsCollectedAt", "windows", "resetInventory", "link"]);
  if (!Array.isArray(input.windows) || input.windows.length > MAX_WINDOWS) throw new Error("invalid quota windows");
  const nowMilliseconds = now.getTime();
  const windows = input.windows.map((window) => sanitizeWindow(window, nowMilliseconds)).filter(Boolean);
  const resetInventory = sanitizeResetInventory(input.resetInventory, input.protocolVersion, nowMilliseconds);

  return {
    protocolVersion: 2,
    generatedAt: requireTimestamp(input.generatedAt, "generated time"),
    sourceStatus: requireEnum(input.sourceStatus, SOURCE_STATUSES, "source status"),
    limitsCollectedAt: nullableTimestamp(input.limitsCollectedAt, "limits collection time"),
    windows,
    resetInventory,
    link: sanitizeLink(input.link),
  };
}

function sanitizeResetInventory(value, version, nowMilliseconds) {
  assertExactKeys(value, ["status", "availableCount", "cachedAt", "items"]);
  if (!Array.isArray(value.items) || value.items.length > MAX_RESET_ITEMS) throw new Error("invalid reset list");
  const availableCount = nullableInteger(value.availableCount, 0, MAX_RESET_COUNT, "reset count");
  const items = value.items
    .map((item) => sanitizeResetItem(item, version))
    .filter((item) => Date.parse(item.expiresAt) > nowMilliseconds)
    .sort((left, right) => Date.parse(left.expiresAt) - Date.parse(right.expiresAt));
  return {
    status: requireEnum(value.status, RESET_STATUSES, "reset status"),
    availableCount,
    cachedAt: nullableTimestamp(value.cachedAt, "reset cache time"),
    items,
  };
}

function sanitizeResetItem(item, version) {
  const expected = version === 1
    ? ["id", "title", "status", "expiresAt"]
    : ["status", "grantedAt", "expiresAt"];
  assertExactKeys(item, expected);
  const grantedAt = version === 1 ? null : nullableTimestamp(item.grantedAt, "grant time");
  if (version === 1) {
    if (!validText(item.id, 256) || !validText(item.title, 128)) throw new Error("invalid legacy reset card");
  }
  if (item.status !== "available") throw new Error("invalid reset card status");
  return { status: "available", grantedAt, expiresAt: requireTimestamp(item.expiresAt, "expiry time") };
}

function sanitizeWindow(window, nowMilliseconds) {
  assertExactKeys(window, ["id", "name", "windowMinutes", "remainingPercent", "resetsAt", "status"]);
  if (!validText(window.id, 128) || !validText(window.name, 64)) throw new Error("invalid quota window");
  const minutes = requireInteger(window.windowMinutes, 1, 100_000_000, "window minutes");
  const resetsAt = requireTimestamp(window.resetsAt, "quota reset time");
  const remaining = nullableInteger(window.remainingPercent, 0, 100, "remaining percentage");
  const expired = Date.parse(resetsAt) <= nowMilliseconds;
  return {
    id: window.id,
    name: window.name,
    windowMinutes: minutes,
    remainingPercent: expired ? null : remaining,
    resetsAt,
    status: expired ? "pending_sync" : requireEnum(window.status, WINDOW_STATUSES, "window status"),
  };
}

function sanitizeLink(value) {
  assertExactKeys(value, ["computer", "codex"]);
  return {
    computer: requireEnum(value.computer, COMPUTER_STATUSES, "computer status"),
    codex: requireEnum(value.codex, CODEX_STATUSES, "Codex status"),
  };
}

function createBandView(snapshot, now = new Date()) {
  const sanitized = sanitizeSnapshotForBand(snapshot, now);
  const status = statusPresentation(sanitized);
  const hasVisibleData = sanitized.windows.length > 0 || Number.isInteger(sanitized.resetInventory.availableCount);
  const windows = sanitized.windows.map((window) => ({
    label: windowLabel(window),
    remainingText: window.status === "current" && Number.isInteger(window.remainingPercent) ? `${window.remainingPercent}%` : "--",
    resetText: `${formatDate(window.resetsAt)}重置`,
    compactResetText: window.status === "current" ? `${formatTime(window.resetsAt)}重置` : "待同步",
    remainingPercent: window.status === "current" && Number.isInteger(window.remainingPercent) ? window.remainingPercent : 0,
    tone: window.status === "current" ? quotaTone(window.remainingPercent) : "unknown",
  }));
  const primaryQuota = windows.find((window) => window.label === "周额度") ?? null;
  const fiveHourQuota = windows.find((window) => window.label === "5小时额度") ?? null;
  const nearest = sanitized.resetInventory.items[0] ?? null;
  return {
    statusText: status.text,
    statusTone: status.tone,
    statusTimeText: hasVisibleData ? formatSyncTime(sanitized.generatedAt, now) : "",
    quotaRemainingText: primaryQuota?.remainingText ?? "--",
    quotaResetText: primaryQuota?.resetText ?? "暂无额度数据",
    quotaTone: sanitized.link.computer === "offline" ? "offline" : primaryQuota?.tone ?? "unknown",
    weeklyProgressPercent: primaryQuota?.remainingPercent ?? 0,
    fiveHourRemainingText: fiveHourQuota?.remainingText ?? "--",
    fiveHourResetText: fiveHourQuota?.compactResetText ?? "暂无数据",
    fiveHourTone: sanitized.link.computer === "offline" ? "offline" : fiveHourQuota?.tone ?? "unknown",
    resetCountText: Number.isInteger(sanitized.resetInventory.availableCount) ? String(sanitized.resetInventory.availableCount) : "--",
    resetTone: resetTone(sanitized.resetInventory.availableCount),
    resetExpiryText: nearest
      ? `${formatDate(nearest.expiresAt)}到期`
      : ["missing", "unavailable"].includes(sanitized.resetInventory.status) ? "暂无重置数据" : "暂无可用重置",
  };
}

function errorStatusText(code) {
  return ({ pairing_required: "需要配对", pairing_revoked: "配对已撤销", private_network_required: "局域网不可用", windows_unreachable: "电脑未连接", windows_response_error: "电脑响应异常", snapshot_too_large: "额度数据异常", unsupported_snapshot_protocol: "请更新应用" })[code] ?? "同步失败";
}

function statusPresentation(snapshot) {
  if (snapshot.sourceStatus === "paused") return { text: "同步已暂停", tone: "warning" };
  if (snapshot.link.computer === "offline") return { text: "离线", tone: "danger" };
  if (snapshot.link.codex === "format_changed") return { text: "数据格式变化", tone: "danger" };
  if (snapshot.link.codex === "stale") return { text: "缓存", tone: "warning" };
  if (snapshot.sourceStatus === "unavailable") return { text: "额度不可用", tone: "danger" };
  if (snapshot.sourceStatus === "partial") return { text: "缓存", tone: "warning" };
  return { text: "已同步", tone: "healthy" };
}

function windowLabel(window) {
  if (window.name === "five_hour" && window.windowMinutes === 300) return "5小时额度";
  return window.name === "weekly" || window.windowMinutes === 10_080 ? "周额度" : `${window.windowMinutes} 分钟额度`;
}
function quotaTone(value) { return !Number.isInteger(value) ? "unknown" : value < 20 ? "danger" : value <= 50 ? "warning" : "healthy"; }
function resetTone(value) { return !Number.isInteger(value) ? "unknown" : value === 0 ? "danger" : "healthy"; }
function formatElapsedAge(elapsedMilliseconds) {
  const elapsedMinutes = Math.floor(Math.max(0, Number(elapsedMilliseconds) || 0) / 60_000);
  if (elapsedMinutes < 1) return "刚刚";
  if (elapsedMinutes < 60) return `${elapsedMinutes}分`;
  if (elapsedMinutes < 24 * 60) return `${Math.floor(elapsedMinutes / 60)}小时`;
  if (elapsedMinutes < 7 * 24 * 60) return `${Math.floor(elapsedMinutes / (24 * 60))}天`;
  const elapsedWeeks = Math.floor(elapsedMinutes / (7 * 24 * 60));
  return elapsedWeeks > 99 ? "99周+" : `${elapsedWeeks}周`;
}
function formatClock(value = new Date()) { const date = new Date(value); const pad = (n) => String(n).padStart(2, "0"); return Number.isNaN(date.getTime()) ? { timeText: "--:--" } : { timeText: `${pad(date.getHours())}:${pad(date.getMinutes())}` }; }
function formatDateTime(value) { const date = new Date(value); const pad = (n) => String(n).padStart(2, "0"); return Number.isNaN(date.getTime()) ? "--" : `${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`; }
function formatSyncTime(value, now) { const date = new Date(value); const current = new Date(now); const pad = (n) => String(n).padStart(2, "0"); if (Number.isNaN(date.getTime())) return "--:--"; const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`; return date.getFullYear() === current.getFullYear() && date.getMonth() === current.getMonth() && date.getDate() === current.getDate() ? time : `${date.getMonth() + 1}月${date.getDate()}日 ${time}`; }
function formatTime(value) { const date = new Date(value); const pad = (n) => String(n).padStart(2, "0"); return Number.isNaN(date.getTime()) ? "--:--" : `${pad(date.getHours())}:${pad(date.getMinutes())}`; }
function formatDate(value) { const date = new Date(value); return Number.isNaN(date.getTime()) ? "--" : `${date.getMonth() + 1}月${date.getDate()}日`; }
function requireTimestamp(value, name) { if (typeof value !== "string" || value.length > 64 || !/^\d{4}-\d{2}-\d{2}T/.test(value) || Number.isNaN(Date.parse(value)) || Date.parse(value) < 0) throw new Error(`invalid ${name}`); return new Date(value).toISOString(); }
function nullableTimestamp(value, name) { return value === null ? null : requireTimestamp(value, name); }
function requireEnum(value, allowed, name) { if (!allowed.includes(value)) throw new Error(`invalid ${name}`); return value; }
function requireInteger(value, minimum, maximum, name) { if (!Number.isInteger(value) || value < minimum || value > maximum) throw new Error(`invalid ${name}`); return value; }
function nullableInteger(value, minimum, maximum, name) { return value === null ? null : requireInteger(value, minimum, maximum, name); }
function validText(value, maximum) { return typeof value === "string" && value.length > 0 && Array.from(value).length <= maximum && !/[\u0000-\u001f\u007f]/.test(value); }
function assertExactKeys(value, keys) { if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== keys.length || Object.keys(value).some((key) => !keys.includes(key))) throw new Error("unexpected summary fields"); }

module.exports = { createBandView, errorStatusText, formatClock, formatElapsedAge, sanitizeSnapshotForBand };
