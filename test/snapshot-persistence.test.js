import assert from "node:assert/strict";
import { join } from "node:path";
import test from "node:test";
import { createSnapshotCache } from "../src/desktop/snapshot-cache.js";
import { openSnapshotFileStore } from "../src/desktop/snapshot-file-store.js";
import { temporaryDirectory } from "./support/temporary-directory.js";

test("the last contract-valid snapshot survives a desktop service restart", async () => {
  await using directory = await temporaryDirectory();
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
        remainingPercent: 46,
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
  const store = await openSnapshotFileStore({
    filePath: join(directory.path, "last-snapshot-v1.json"),
  });
  const firstCache = createSnapshotCache({
    clock: () => now,
    collector: async () => trusted,
    initialSnapshot: await store.load(),
    onTrustedSnapshot: (snapshot) => store.save(snapshot),
  });
  await firstCache.refresh();

  const restored = await store.load();
  assert.deepEqual(restored, trusted);

  const restartedCache = createSnapshotCache({
    clock: () => now,
    collector: async () => {
      throw new Error("fixture source unavailable after restart");
    },
    initialSnapshot: restored,
    onTrustedSnapshot: (snapshot) => store.save(snapshot),
  });
  await restartedCache.refresh();
  assert.deepEqual(restartedCache.getSnapshot(), {
    ...trusted,
    generatedAt: now.toISOString(),
    sourceStatus: "partial",
    link: { computer: "online", codex: "stale" },
  });
});
