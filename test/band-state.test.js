import assert from "node:assert/strict";
import test from "node:test";
import quotaState from "../band-app/src/common/quota-state.cjs";

const {
  createBandView,
  errorStatusText,
  formatClock,
  sanitizeSnapshotForBand,
} = quotaState;

test("the watch clock exposes a zero-padded local time and Chinese date", () => {
  assert.deepEqual(formatClock(new Date(2026, 6, 18, 10, 9)), {
    timeText: "10:09",
    dateText: "7月18日 周六",
  });
});

test("the band view promotes the weekly quota into the hero section", () => {
  const now = new Date("2030-01-01T02:00:00.000Z");
  const view = createBandView({
    protocolVersion: 1,
    generatedAt: "2030-01-01T01:59:00.000Z",
    sourceStatus: "ok",
    limitsCollectedAt: "2030-01-01T01:59:00.000Z",
    windows: [
      {
        id: "codex:five-hour",
        name: "five_hour",
        windowMinutes: 300,
        remainingPercent: 99,
        resetsAt: "2030-01-02T00:00:00.000Z",
        status: "current",
      },
      {
        id: "codex:weekly",
        name: "weekly",
        windowMinutes: 10_080,
        remainingPercent: 67,
        resetsAt: "2030-01-08T00:00:00.000Z",
        status: "current",
      },
    ],
    resetInventory: { status: "cached", availableCount: 3, cachedAt: null, items: [] },
    link: { computer: "online", codex: "ok" },
  }, now);

  assert.equal(view.quotaRemainingPercent, 67);
  assert.equal(view.quotaRemainingText, "67%");
  assert.equal(view.quotaResetText, "1月8日重置");
  assert.equal(view.quotaTone, "healthy");
});

test("an offline band keeps cached quota and makes sync status concise", () => {
  const now = new Date("2030-01-01T02:00:00.000Z");
  const view = createBandView({
    protocolVersion: 1,
    generatedAt: "2030-01-01T01:59:00.000Z",
    sourceStatus: "ok",
    limitsCollectedAt: "2030-01-01T01:59:00.000Z",
    windows: [{
      id: "codex:weekly",
      name: "weekly",
      windowMinutes: 10_080,
      remainingPercent: 67,
      resetsAt: "2030-01-08T00:00:00.000Z",
      status: "current",
    }],
    resetInventory: { status: "cached", availableCount: 3, cachedAt: null, items: [] },
    link: { computer: "offline", codex: "ok" },
  }, now);

  assert.equal(view.quotaTone, "offline");
  assert.equal(view.quotaRemainingText, "67%");
  assert.equal(view.statusText, "离线");
  assert.match(view.statusTimeText, /^上次\d{2}:\d{2}$/);
});

test("the band renders only current dynamic windows and locally expires cached quota data", () => {
  const now = new Date("2026-07-18T02:00:00Z");
  const raw = {
    protocolVersion: 1,
    generatedAt: "2026-07-18T01:59:58.000Z",
    sourceStatus: "ok",
    limitsCollectedAt: "2026-07-18T01:59:58.000Z",
    windows: [
      {
        id: "codex:primary:10080",
        name: "weekly",
        windowMinutes: 10_080,
        remainingPercent: 61,
        resetsAt: "2026-07-24T14:32:16.000Z",
        status: "current",
        conversation: "must be removed",
      },
      {
        id: "expired-custom",
        name: "custom",
        windowMinutes: 60,
        remainingPercent: 99,
        resetsAt: "2026-07-18T01:00:00.000Z",
        status: "current",
      },
    ],
    resetInventory: {
      status: "cached",
      availableCount: 2,
      cachedAt: "2026-07-17T16:17:57.000Z",
      items: [
        {
          id: "expired",
          title: "Full reset",
          status: "available",
          expiresAt: "2026-07-18T01:30:00.000Z",
        },
        {
          id: "active",
          title: "Full reset",
          status: "available",
          expiresAt: "2026-07-26T23:27:31.735Z",
        },
      ],
    },
    link: { computer: "online", codex: "ok" },
    token: "must be removed",
  };

  const sanitized = sanitizeSnapshotForBand(raw, now);
  assert.equal(JSON.stringify(sanitized).includes("must be removed"), false);
  assert.equal("token" in sanitized, false);
  assert.equal(sanitized.resetInventory.status, "cached_derived");

  const view = createBandView(sanitized, now);
  assert.deepEqual(
    view.windows.map(({ label, remainingText }) => ({ label, remainingText })),
    [
      { label: "周额度", remainingText: "61%" },
      { label: "60 分钟额度", remainingText: "--" },
    ],
  );
  assert.equal(view.statusText, "已同步");
  assert.equal(view.statusTone, "healthy");
  assert.match(view.statusTimeText, /^\d{2}:\d{2}$/);
  assert.equal(view.windows.some(({ label }) => label.includes("5 小时")), false);
  assert.equal(view.resetCountText, "1");
  assert.equal(view.resetHintText, "最近到期 7月27日");
});

test("the band labels dynamic windows and applies the 20 and 50 percent color boundaries", () => {
  const now = new Date("2026-07-18T02:00:00Z");
  const base = {
    protocolVersion: 1,
    generatedAt: now.toISOString(),
    sourceStatus: "ok",
    limitsCollectedAt: now.toISOString(),
    resetInventory: {
      status: "missing",
      availableCount: null,
      cachedAt: null,
      items: [],
    },
    link: { computer: "online", codex: "ok" },
  };
  const windows = [
    ["five_hour", 300, 19],
    ["weekly", 10_080, 20],
    ["custom", 1_440, 50],
    ["custom", 90, 51],
  ].map(([name, windowMinutes, remainingPercent], index) => ({
    id: `window-${index}`,
    name,
    windowMinutes,
    remainingPercent,
    resetsAt: "2026-07-24T14:32:16.000Z",
    status: "current",
  }));

  const view = createBandView({ ...base, windows }, now);
  assert.deepEqual(
    view.windows.map(({ label, remainingText, tone }) => ({
      label,
      remainingText,
      tone,
    })),
    [
      { label: "5 小时额度", remainingText: "19%", tone: "danger" },
      { label: "周额度", remainingText: "20%", tone: "warning" },
      { label: "1440 分钟额度", remainingText: "50%", tone: "warning" },
      { label: "90 分钟额度", remainingText: "51%", tone: "healthy" },
    ],
  );
  assert.equal(view.resetHintText, "暂无重置数据");
});

test("transport failures have explicit band-facing messages", () => {
  assert.equal(errorStatusText("pairing_required"), "需要配对");
  assert.equal(errorStatusText("windows_unreachable"), "电脑未连接");
  assert.equal(errorStatusText("windows_response_error"), "电脑响应异常");
  assert.equal(errorStatusText("unsupported_snapshot_protocol"), "请更新应用");
  assert.equal(errorStatusText("unexpected"), "同步失败");
});
