import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import { startQuotaServer } from "../src/server/quota-server.js";
import { openHashedTokenStore } from "../src/server/token-store.js";
import { temporaryDirectory } from "./support/temporary-directory.js";

test("paired access survives a service restart and can be revoked without persisting secrets", async () => {
  await using directory = await temporaryDirectory();
  const tokenFile = join(directory.path, "authorized-devices.json");
  const now = new Date("2026-07-18T02:00:00Z");
  const snapshot = {
    protocolVersion: 1,
    generatedAt: now.toISOString(),
    sourceStatus: "ok",
    limitsCollectedAt: now.toISOString(),
    windows: [],
    resetInventory: {
      status: "missing",
      availableCount: null,
      cachedAt: null,
      items: [],
    },
    link: { computer: "online", codex: "ok" },
  };

  const firstStore = await openHashedTokenStore({ filePath: tokenFile });
  const firstServer = await startQuotaServer({
    host: "127.0.0.1",
    port: 0,
    pairing: {
      code: "123456",
      expiresAt: new Date("2026-07-18T02:05:00Z"),
      maxAttempts: 3,
    },
    clock: () => now,
    snapshotProvider: async () => snapshot,
    tokenStore: firstStore,
  });

  const paired = await fetch(`${firstServer.baseUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "123456" }),
  });
  const { token } = await paired.json();
  await firstServer.close();

  const persisted = await readFile(tokenFile, "utf8");
  assert.equal(persisted.includes(token), false);
  assert.equal(persisted.includes("123456"), false);

  const secondStore = await openHashedTokenStore({ filePath: tokenFile });
  await using secondServer = await startQuotaServer({
    host: "127.0.0.1",
    port: 0,
    pairing: {
      code: "654321",
      expiresAt: new Date("2026-07-18T02:05:00Z"),
      maxAttempts: 3,
    },
    clock: () => now,
    snapshotProvider: async () => snapshot,
    tokenStore: secondStore,
  });

  const authorized = await fetch(`${secondServer.baseUrl}/v1/snapshot`, {
    headers: { authorization: `Bearer ${token}` },
  });
  assert.equal(authorized.status, 200);

  await secondStore.revokeAll();
  const revoked = await fetch(`${secondServer.baseUrl}/v1/snapshot`, {
    headers: { authorization: `Bearer ${token}` },
  });
  assert.equal(revoked.status, 401);
});
