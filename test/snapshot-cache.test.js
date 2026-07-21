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

test("a partial refresh keeps the last valid reset inventory while applying new weekly quota", async () => {
  const now = new Date("2026-07-21T13:51:00.000Z");
  const trusted = {
    protocolVersion: 1,
    generatedAt: "2026-07-21T13:50:00.000Z",
    sourceStatus: "ok",
    limitsCollectedAt: "2026-07-21T13:50:00.000Z",
    windows: [
      {
        id: "codex:primary:10080",
        name: "weekly",
        windowMinutes: 10_080,
        remainingPercent: 75,
        resetsAt: "2026-07-28T09:07:32.000Z",
        status: "current",
      },
    ],
    resetInventory: {
      status: "cached",
      availableCount: 2,
      cachedAt: "2026-07-20T09:42:00.000Z",
      items: [
        {
          id: "reset-1",
          title: "Full reset",
          status: "available",
          expiresAt: "2026-07-27T00:00:00.000Z",
        },
        {
          id: "reset-2",
          title: "Full reset",
          status: "available",
          expiresAt: "2026-08-01T00:00:00.000Z",
        },
      ],
    },
    link: { computer: "online", codex: "ok" },
  };
  const partial = {
    ...trusted,
    generatedAt: now.toISOString(),
    sourceStatus: "partial",
    limitsCollectedAt: now.toISOString(),
    windows: [{ ...trusted.windows[0], remainingPercent: 74 }],
    resetInventory: {
      status: "unavailable",
      availableCount: null,
      cachedAt: null,
      items: [],
    },
  };
  let refreshCount = 0;
  const cache = createSnapshotCache({
    clock: () => now,
    collector: async () => (refreshCount++ === 0 ? trusted : partial),
  });

  await cache.refresh();
  await cache.refresh();

  assert.deepEqual(cache.getSnapshot(), {
    ...partial,
    resetInventory: trusted.resetInventory,
  });
});

test("a partial refresh does not retain reset credits after their expiry", async () => {
  const now = new Date("2026-07-28T13:51:00.000Z");
  const trusted = {
    protocolVersion: 1,
    generatedAt: "2026-07-27T13:50:00.000Z",
    sourceStatus: "ok",
    limitsCollectedAt: "2026-07-27T13:50:00.000Z",
    windows: [],
    resetInventory: {
      status: "cached",
      availableCount: 1,
      cachedAt: "2026-07-27T09:42:00.000Z",
      items: [
        {
          id: "expired-reset",
          title: "Full reset",
          status: "available",
          expiresAt: "2026-07-28T09:00:00.000Z",
        },
      ],
    },
    link: { computer: "online", codex: "ok" },
  };
  const partial = {
    ...trusted,
    generatedAt: now.toISOString(),
    sourceStatus: "partial",
    resetInventory: {
      status: "unavailable",
      availableCount: null,
      cachedAt: null,
      items: [],
    },
  };
  let refreshCount = 0;
  const cache = createSnapshotCache({
    clock: () => now,
    collector: async () => (refreshCount++ === 0 ? trusted : partial),
  });

  await cache.refresh();
  await cache.refresh();

  assert.deepEqual(cache.getSnapshot(), partial);
});
