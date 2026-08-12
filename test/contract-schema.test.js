import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import Ajv from "ajv";
import addFormats from "ajv-formats";
import { collectSnapshot } from "../src/core/snapshot.js";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { temporaryDirectory } from "./support/temporary-directory.js";

test("the public snapshot conforms to the closed version 1 contract", async () => {
  await using fixture = await temporaryDirectory();
  const sessions = join(fixture.path, "sessions");
  await mkdir(sessions, { recursive: true });
  await writeFile(
    join(sessions, "rollout.jsonl"),
    `${JSON.stringify({
      timestamp: "2026-07-18T01:02:03Z",
      payload: {
        rate_limits: {
          limit_id: "codex",
          primary: {
            used_percent: 29,
            window_minutes: 10_080,
            resets_at: 1_784_903_536,
          },
          secondary: null,
        },
      },
    })}\n`,
    "utf8",
  );
  const snapshot = await collectSnapshot(
    { codexSessionsPath: sessions, resetCachePath: null },
    new Date("2026-07-18T02:00:00Z"),
  );
  const schema = JSON.parse(
    await readFile(new URL("../contract/snapshot-v1.schema.json", import.meta.url), "utf8"),
  );
  const ajv = new Ajv({ allErrors: true });
  addFormats(ajv);
  const validate = ajv.compile(schema);

  assert.equal(validate(snapshot), true, JSON.stringify(validate.errors, null, 2));
  assert.equal(validate({ ...snapshot, conversation: "must be rejected" }), false);
  assert.equal(
    validate({
      ...snapshot,
      resetInventory: { ...snapshot.resetInventory, accessToken: "must be rejected" },
    }),
    false,
  );
});

test("the sync stream contract accepts negotiated quota v3 and keeps it closed", async () => {
  const load = async (name) =>
    JSON.parse(
      await readFile(new URL(`../contract/${name}`, import.meta.url), "utf8"),
    );
  const snapshotV1 = await load("snapshot-v1.schema.json");
  const snapshotV2 = await load("snapshot-v2.schema.json");
  const snapshotV3 = await load("snapshot-v3.schema.json");
  const taskV1 = await load("task-sync-v1.schema.json");
  const stream = await load("sync-stream-v1.schema.json");
  const ajv = new Ajv({ allErrors: true });
  addFormats(ajv);
  for (const schema of [snapshotV1, snapshotV2, snapshotV3, taskV1]) {
    ajv.addSchema(schema);
  }
  const validate = ajv.compile(stream);
  const hello = {
    type: "client_hello",
    transportVersion: 1,
    clientInstanceId: "client_0123456789",
    supportedQuotaVersions: [1, 2, 3],
    supportedTaskVersions: [1],
  };
  const quota = {
    protocolVersion: 3,
    generatedAt: "2026-07-28T10:00:00Z",
    sourceStatus: "partial",
    limitsCollectedAt: "2026-07-28T09:45:00Z",
    windows: [],
    resetInventory: {
      status: "cached",
      availableCount: 0,
      cachedAt: "2026-07-28T09:45:00Z",
      items: [],
    },
    link: { computer: "online", codex: "stale" },
    upstreamFreshness: {
      usage: {
        status: "cached",
        lastAttemptAt: "2026-07-28T10:00:00Z",
        lastSuccessAt: "2026-07-28T09:45:00Z",
      },
      resetInventory: {
        status: "current",
        lastAttemptAt: "2026-07-28T10:00:00Z",
        lastSuccessAt: "2026-07-28T10:00:00Z",
      },
    },
  };
  const frame = {
    type: "snapshot",
    transportVersion: 1,
    connectionId: "connection_0123456789",
    sequence: 1,
    generatedAtMs: 1785232800000,
    quota,
    tasks: {
      protocolVersion: 1,
      sequence: 1,
      generatedAtMs: 1785232800000,
      chatGptState: "running",
      chatGptFocused: false,
      tasks: [],
    },
  };

  assert.equal(validate(hello), true, JSON.stringify(validate.errors, null, 2));
  const refreshRequest = {
    type: "refresh_request",
    transportVersion: 1,
    connectionId: "connection_0123456789",
    scope: "quota",
  };
  assert.equal(
    validate(refreshRequest),
    true,
    JSON.stringify(validate.errors, null, 2),
  );
  assert.equal(validate({ ...refreshRequest, reason: "vpn failed" }), false);
  assert.equal(validate(frame), true, JSON.stringify(validate.errors, null, 2));
  assert.equal(validate({ ...frame, quota: { ...quota, token: "private" } }), false);
});

test("manual pairing discovery contains identity but no pairing secret", async () => {
  const schema = JSON.parse(
    await readFile(new URL("../contract/pairing-discovery-v1.schema.json", import.meta.url), "utf8"),
  );
  const validate = new Ajv({ allErrors: true }).compile(schema);
  const discovery = {
    protocolVersion: 1,
    type: "pairing_discovery",
    computerFingerprint: "ab".repeat(32),
    endpoints: ["wss://192.168.1.42:17322/pair"],
    expiresAtMs: 1784880300000,
  };

  assert.equal(validate(discovery), true, JSON.stringify(validate.errors, null, 2));
  assert.equal(validate({ ...discovery, code: "123456" }), false);
  assert.equal(validate({ ...discovery, token: "private" }), false);
});
