import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import Ajv from "ajv";
import addFormats from "ajv-formats";
import { collectSnapshot } from "../src/core/snapshot.js";
import { discoverCodexDataPaths } from "../src/desktop/data-paths.js";
import { startQuotaServer } from "../src/server/quota-server.js";

const now = new Date();
const pairingCode = "847261";
const logs = [];
const paths = await discoverCodexDataPaths({
  homeDirectory: process.env.USERPROFILE,
  localAppData: process.env.LOCALAPPDATA,
});

await using server = await startQuotaServer({
  host: "127.0.0.1",
  port: 0,
  pairing: {
    code: pairingCode,
    expiresAt: new Date(now.getTime() + 5 * 60 * 1_000),
    maxAttempts: 3,
  },
  snapshotProvider: () => collectSnapshot(paths, new Date()),
  logger: (event) => logs.push(event),
});

const paired = await fetch(`${server.baseUrl}/v1/pair`, {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ code: pairingCode }),
});
assert.equal(paired.status, 201);
const { token } = await paired.json();

const response = await fetch(`${server.baseUrl}/v1/snapshot`, {
  headers: { authorization: `Bearer ${token}` },
});
assert.equal(response.status, 200);
const snapshot = await response.json();

const schema = JSON.parse(
  await readFile(new URL("../contract/snapshot-v1.schema.json", import.meta.url), "utf8"),
);
const ajv = new Ajv({ allErrors: true, strict: true });
addFormats(ajv);
assert.equal(ajv.compile(schema)(snapshot), true);

const serialized = JSON.stringify(snapshot);
for (const forbidden of [
  "conversation",
  "prompt",
  "cookie",
  "access_token",
  "refresh_token",
  "tool_call",
  "projectPath",
]) {
  assert.equal(serialized.toLowerCase().includes(forbidden.toLowerCase()), false, forbidden);
}
const serializedLogs = JSON.stringify(logs);
assert.equal(serializedLogs.includes(pairingCode), false);
assert.equal(serializedLogs.includes(token), false);
assert.equal(serializedLogs.includes(serialized), false);

process.stdout.write(`${JSON.stringify({
  result: "REAL DATA ROUNDTRIP PASS",
  sourceStatus: snapshot.sourceStatus,
  windows: snapshot.windows.map(({ name, remainingPercent, resetsAt }) => ({
    name,
    remainingPercent,
    resetsAt,
  })),
  resetInventory: {
    status: snapshot.resetInventory.status,
    availableCount: snapshot.resetInventory.availableCount,
    cachedAt: snapshot.resetInventory.cachedAt,
  },
})}\n`);
