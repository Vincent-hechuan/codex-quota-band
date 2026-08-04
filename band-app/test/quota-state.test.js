import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";

const require = createRequire(import.meta.url);
const quotaState = require("../src/common/quota-state.cjs");

function v2Snapshot(overrides = {}) {
  return {
    protocolVersion: 2,
    generatedAt: "2030-01-01T00:00:00Z",
    sourceStatus: "ok",
    limitsCollectedAt: "2030-01-01T00:00:00Z",
    windows: [{ id: "weekly", name: "weekly", windowMinutes: 10080, remainingPercent: 34, resetsAt: "2030-01-08T00:00:00Z", status: "current" }],
    resetInventory: { status: "cached", availableCount: 1, cachedAt: "2030-01-01T00:00:00Z", items: [{ status: "available", grantedAt: null, expiresAt: "2030-01-04T03:49:00Z" }] },
    link: { computer: "online", codex: "ok" },
    ...overrides,
  };
}

test("band accepts the Android v2 summary without card identity and shows a compact expiry date", () => {
  const view = quotaState.createBandView(v2Snapshot(), new Date("2030-01-01T00:00:00Z"));
  assert.equal(view.resetCountText, "1");
  assert.equal(view.resetExpiryText, "1月4日到期");
});

test("available reset color is independent from weekly quota color", () => {
  const now = new Date("2030-01-01T00:00:00Z");
  const oneReset = quotaState.createBandView(v2Snapshot(), now);
  const noReset = quotaState.createBandView(
    v2Snapshot({
      resetInventory: { status: "cached", availableCount: 0, cachedAt: "2030-01-01T00:00:00Z", items: [] },
    }),
    now,
  );

  assert.equal(oneReset.quotaTone, "warning", "36% weekly quota remains yellow");
  assert.equal(oneReset.resetTone, "healthy", "one available reset stays green");
  assert.equal(noReset.resetTone, "danger", "zero available resets is red");
});

test("band quota becomes healthy at exactly fifty percent", () => {
  const view = quotaState.createBandView(
    v2Snapshot({
      windows: [{ id: "weekly", name: "weekly", windowMinutes: 10080, remainingPercent: 50, resetsAt: "2030-01-08T00:00:00Z", status: "current" }],
    }),
    new Date("2030-01-01T00:00:00Z"),
  );

  assert.equal(view.quotaTone, "healthy");
});

test("five-hour and weekly quotas stay independent in the band view", () => {
  const fiveHourReset = new Date(2030, 0, 1, 18, 20);
  const view = quotaState.createBandView(
    v2Snapshot({
      windows: [
        {
          id: "codex:primary:300",
          name: "five_hour",
          windowMinutes: 300,
          remainingPercent: 68,
          resetsAt: fiveHourReset.toISOString(),
          status: "current",
        },
        {
          id: "codex:weekly",
          name: "weekly",
          windowMinutes: 10_080,
          remainingPercent: 34,
          resetsAt: "2030-01-08T00:00:00Z",
          status: "current",
        },
      ],
    }),
    new Date("2030-01-01T00:00:00Z"),
  );

  assert.equal(view.fiveHourRemainingText, "68%");
  assert.equal(view.fiveHourResetText, "18:20重置");
  assert.equal(view.fiveHourTone, "healthy");
  assert.equal(view.quotaRemainingText, "34%");
  assert.equal(view.weeklyProgressPercent, 34);
});

test("five-hour quota distinguishes pending data from an absent window", () => {
  const pending = quotaState.createBandView(
    v2Snapshot({
      windows: [{
        id: "codex:primary:300",
        name: "five_hour",
        windowMinutes: 300,
        remainingPercent: null,
        resetsAt: "2030-01-01T05:00:00Z",
        status: "pending_sync",
      }],
    }),
    new Date("2030-01-01T00:00:00Z"),
  );
  const missing = quotaState.createBandView(v2Snapshot(), new Date("2030-01-01T00:00:00Z"));
  const fiveHourOnly = quotaState.createBandView(
    v2Snapshot({
      windows: [{
        id: "codex:primary:300",
        name: "five_hour",
        windowMinutes: 300,
        remainingPercent: 68,
        resetsAt: "2030-01-01T05:00:00Z",
        status: "current",
      }],
    }),
    new Date("2030-01-01T00:00:00Z"),
  );

  assert.equal(pending.fiveHourRemainingText, "--");
  assert.equal(pending.fiveHourResetText, "待同步");
  assert.equal(pending.quotaRemainingText, "--", "the five-hour window must not fill the weekly slot");
  assert.equal(missing.fiveHourRemainingText, "--");
  assert.equal(missing.fiveHourResetText, "暂无数据");
  assert.equal(fiveHourOnly.quotaRemainingText, "--", "a current five-hour window still must not fill the weekly slot");
});

test("cache ages use the shared minute, hour, day, and week units", () => {
  assert.equal(quotaState.formatElapsedAge(0), "刚刚");
  assert.equal(quotaState.formatElapsedAge(59 * 60_000), "59分");
  assert.equal(quotaState.formatElapsedAge(415 * 60_000), "6小时");
  assert.equal(quotaState.formatElapsedAge(3 * 24 * 60 * 60_000), "3天");
  assert.equal(quotaState.formatElapsedAge(9 * 24 * 60 * 60_000), "1周");
  assert.equal(quotaState.formatElapsedAge(800 * 24 * 60 * 60_000), "99周+");
});

test("band rejects private fields, invalid timestamps, and oversized reset lists", () => {
  const privateField = v2Snapshot();
  privateField.resetInventory.items[0].title = "must never reach the band";
  assert.throws(() => quotaState.sanitizeSnapshotForBand(privateField), /unexpected summary fields/);
  const oversized = v2Snapshot();
  oversized.resetInventory.items = Array.from({ length: 4 }, () => ({ status: "available", grantedAt: null, expiresAt: "2030-01-04T03:49:00Z" }));
  assert.throws(() => quotaState.sanitizeSnapshotForBand(oversized), /invalid reset list/);
  const invalidTime = v2Snapshot();
  invalidTime.resetInventory.items[0].expiresAt = "not-a-time";
  assert.throws(() => quotaState.sanitizeSnapshotForBand(invalidTime), /invalid expiry time/);
});

test("legacy v1 still renders but does not retain its card id or title", () => {
  const legacy = v2Snapshot({ protocolVersion: 1 });
  legacy.resetInventory.items = [{ id: "legacy-card-id", title: "Full reset", status: "available", expiresAt: "2030-01-04T03:49:00Z" }];
  const sanitized = quotaState.sanitizeSnapshotForBand(legacy);
  assert.equal(sanitized.protocolVersion, 2);
  assert.deepEqual(sanitized.resetInventory.items, [{ status: "available", grantedAt: null, expiresAt: "2030-01-04T03:49:00.000Z" }]);
});
