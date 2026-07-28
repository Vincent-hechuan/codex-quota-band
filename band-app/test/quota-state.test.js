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
