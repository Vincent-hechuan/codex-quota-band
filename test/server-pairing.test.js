import assert from "node:assert/strict";
import test from "node:test";
import { startQuotaServer } from "../src/server/quota-server.js";

test("a one-time pairing code grants read-only snapshot access without secret logs", async () => {
  const logs = [];
  const now = new Date("2026-07-18T02:00:00Z");
  const snapshot = {
    protocolVersion: 1,
    generatedAt: now.toISOString(),
    sourceStatus: "ok",
    limitsCollectedAt: "2026-07-18T01:59:58.000Z",
    windows: [],
    resetInventory: {
      status: "cached",
      availableCount: 4,
      cachedAt: "2026-07-18T01:30:00.000Z",
      items: [],
    },
    link: { computer: "online", codex: "ok" },
  };
  await using server = await startQuotaServer({
    host: "127.0.0.1",
    port: 0,
    pairing: {
      code: "123456",
      expiresAt: new Date("2026-07-18T02:05:00Z"),
      maxAttempts: 3,
    },
    clock: () => now,
    snapshotProvider: async () => snapshot,
    logger: (event) => logs.push(event),
  });

  const unauthorized = await fetch(`${server.baseUrl}/v1/snapshot`);
  assert.equal(unauthorized.status, 401);

  const paired = await fetch(`${server.baseUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "123456" }),
  });
  assert.equal(paired.status, 201);
  const { token } = await paired.json();
  assert.match(token, /^[A-Za-z0-9_-]{43}$/);

  const replay = await fetch(`${server.baseUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "123456" }),
  });
  assert.equal(replay.status, 410);

  const authorized = await fetch(`${server.baseUrl}/v1/snapshot`, {
    headers: { authorization: `Bearer ${token}` },
  });
  assert.equal(authorized.status, 200);
  assert.deepEqual(await authorized.json(), snapshot);

  const forbiddenWrite = await fetch(`${server.baseUrl}/v1/reset`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}` },
  });
  assert.equal(forbiddenWrite.status, 404);

  const logText = JSON.stringify(logs);
  assert.equal(logText.includes("123456"), false);
  assert.equal(logText.includes(token), false);
  assert.equal(logText.includes(JSON.stringify(snapshot)), false);
});

test("starting a new pairing session immediately invalidates the previous code", async () => {
  const now = new Date("2026-07-18T02:00:00Z");
  await using server = await startQuotaServer({
    host: "127.0.0.1",
    port: 0,
    pairing: {
      code: "123456",
      expiresAt: new Date("2026-07-18T02:05:00Z"),
      maxAttempts: 3,
    },
    clock: () => now,
    snapshotProvider: async () => ({}),
  });

  server.beginPairing({
    code: "654321",
    expiresAt: new Date("2026-07-18T02:05:00Z"),
    maxAttempts: 3,
  });

  const oldCode = await fetch(`${server.baseUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "123456" }),
  });
  assert.equal(oldCode.status, 401);

  const newCode = await fetch(`${server.baseUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "654321" }),
  });
  assert.equal(newCode.status, 201);
});

test("the pairing endpoint rejects non-JSON and fields outside its closed request contract", async () => {
  const now = new Date("2026-07-18T02:00:00Z");
  await using server = await startQuotaServer({
    host: "127.0.0.1",
    port: 0,
    pairing: {
      code: "123456",
      expiresAt: new Date("2026-07-18T02:05:00Z"),
      maxAttempts: 3,
    },
    clock: () => now,
    snapshotProvider: async () => ({}),
  });

  const wrongType = await fetch(`${server.baseUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "text/plain" },
    body: JSON.stringify({ code: "123456" }),
  });
  assert.equal(wrongType.status, 415);

  const extraField = await fetch(`${server.baseUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "123456", deviceName: "unexpected" }),
  });
  assert.equal(extraField.status, 400);

  const exactRequest = await fetch(`${server.baseUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "123456" }),
  });
  assert.equal(exactRequest.status, 201);
});

test("expired pairing codes and brute-force attempts are rejected", async () => {
  const now = new Date("2026-07-18T02:00:00Z");
  await using expiredServer = await startQuotaServer({
    host: "127.0.0.1",
    port: 0,
    pairing: {
      code: "123456",
      expiresAt: new Date("2026-07-18T01:59:59Z"),
      maxAttempts: 3,
    },
    clock: () => now,
    snapshotProvider: async () => ({}),
  });
  const expired = await fetch(`${expiredServer.baseUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "123456" }),
  });
  assert.equal(expired.status, 410);

  await using limitedServer = await startQuotaServer({
    host: "127.0.0.1",
    port: 0,
    pairing: {
      code: "123456",
      expiresAt: new Date("2026-07-18T02:05:00Z"),
      maxAttempts: 3,
    },
    clock: () => now,
    snapshotProvider: async () => ({}),
  });
  for (const code of ["000000", "000001", "000002"]) {
    const rejected = await fetch(`${limitedServer.baseUrl}/v1/pair`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code }),
    });
    assert.equal(rejected.status, 401);
  }
  const lockedOut = await fetch(`${limitedServer.baseUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "123456" }),
  });
  assert.equal(lockedOut.status, 429);
});

test("the Windows endpoint refuses a snapshot outside the closed public contract", async () => {
  const now = new Date("2026-07-18T02:00:00Z");
  await using server = await startQuotaServer({
    host: "127.0.0.1",
    port: 0,
    pairing: {
      code: "123456",
      expiresAt: new Date("2026-07-18T02:05:00Z"),
      maxAttempts: 3,
    },
    clock: () => now,
    snapshotProvider: async () => ({
      protocolVersion: 1,
      accessToken: "must-never-leave-windows",
    }),
  });
  const paired = await fetch(`${server.baseUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "123456" }),
  });
  const { token } = await paired.json();

  const response = await fetch(`${server.baseUrl}/v1/snapshot`, {
    headers: { authorization: `Bearer ${token}` },
  });
  assert.equal(response.status, 500);
  assert.equal((await response.text()).includes("must-never-leave-windows"), false);
});
