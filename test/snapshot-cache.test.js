import assert from "node:assert/strict";
import test from "node:test";
import { createSnapshotCache } from "../src/desktop/snapshot-cache.js";

test("refresh failures and pause retain the last trusted snapshot with explicit status", async () => {
  const now = new Date("2026-07-18T02:00:00Z");
  const trusted = {
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
      },
    ],
    resetInventory: {
      status: "cached",
      availableCount: 4,
      cachedAt: "2026-07-17T16:17:57.000Z",
      items: [],
    },
    link: { computer: "online", codex: "ok" },
  };
  let shouldFail = false;
  const cache = createSnapshotCache({
    clock: () => now,
    collector: async () => {
      if (shouldFail) throw new Error("fixture source unavailable");
      return trusted;
    },
  });

  await cache.refresh();
  shouldFail = true;
  await cache.refresh();
  assert.deepEqual(cache.getSnapshot(), {
    ...trusted,
    generatedAt: now.toISOString(),
    sourceStatus: "partial",
    link: { computer: "online", codex: "stale" },
  });

  cache.pause();
  assert.deepEqual(cache.getSnapshot(), {
    ...trusted,
    generatedAt: now.toISOString(),
    sourceStatus: "paused",
    link: { computer: "paused", codex: "stale" },
  });
});
