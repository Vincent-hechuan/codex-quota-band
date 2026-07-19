import assert from "node:assert/strict";
import test from "node:test";
import { snapshotSummary } from "../src/desktop/snapshot-summary.js";

function snapshot(window, sourceStatus = "ok") {
  return {
    sourceStatus,
    windows: window ? [window] : [],
    resetInventory: { availableCount: 4 },
  };
}

test("the Windows tray names the actual dynamic quota window", () => {
  assert.equal(
    snapshotSummary(
      snapshot({
        name: "weekly",
        windowMinutes: 10_080,
        remainingPercent: 43,
        status: "current",
      }),
    ),
    "周额度 43% · 重置 4 次",
  );
  assert.equal(
    snapshotSummary(
      snapshot({
        name: "five_hour",
        windowMinutes: 300,
        remainingPercent: 80,
        status: "current",
      }),
    ),
    "5 小时额度 80% · 重置 4 次",
  );
  assert.equal(
    snapshotSummary(
      snapshot(
        {
          name: "custom",
          windowMinutes: 90,
          remainingPercent: 20,
          status: "current",
        },
        "partial",
      ),
    ),
    "90 分钟额度 20% · 重置 4 次 · partial",
  );
  assert.equal(snapshotSummary(snapshot(null)), "额度未知 · 重置 4 次");
});
