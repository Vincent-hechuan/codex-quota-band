import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import { writeResetCacheFixture } from "./support/chromium-cache-fixture.js";
import { temporaryDirectory } from "./support/temporary-directory.js";
import { collectSnapshot } from "../src/core/snapshot.js";

test("weekly rate limit becomes a privacy-filtered snapshot", async () => {
  await using fixture = await temporaryDirectory();
  const sessions = join(fixture.path, "sessions", "2026", "07", "18");
  await mkdir(sessions, { recursive: true });
  await writeFile(
    join(sessions, "rollout.jsonl"),
    [
      JSON.stringify({
        timestamp: "2026-07-18T01:00:00Z",
        type: "message",
        payload: {
          text: "PRIVATE CONVERSATION",
          access_token: "SECRET TOKEN",
        },
      }),
      JSON.stringify({
        timestamp: "2026-07-18T01:02:03Z",
        type: "event_msg",
        payload: {
          type: "token_count",
          rate_limits: {
            limit_id: "codex",
            limit_name: null,
            primary: {
              used_percent: 29,
              window_minutes: 10_080,
              resets_at: 1_784_903_536,
            },
            secondary: null,
            credits: { has_credits: false, balance: "0" },
            plan_type: "plus",
          },
        },
      }),
      "",
    ].join("\n"),
    "utf8",
  );

  const snapshot = await collectSnapshot(
    {
      codexSessionsPath: join(fixture.path, "sessions"),
      resetCachePath: null,
    },
    new Date("2026-07-18T02:00:00Z"),
  );

  assert.equal(snapshot.protocolVersion, 1);
  assert.equal(snapshot.windows.length, 1);
  assert.equal(snapshot.windows[0].windowMinutes, 10_080);
  assert.equal(snapshot.windows[0].remainingPercent, 71);
  assert.equal(snapshot.windows[0].status, "current");
  assert.equal(snapshot.resetInventory.status, "missing");

  const wire = JSON.stringify(snapshot);
  assert.equal(wire.includes("PRIVATE CONVERSATION"), false);
  assert.equal(wire.includes("SECRET TOKEN"), false);
  assert.equal(wire.includes("access_token"), false);
  assert.equal(wire.includes("plan_type"), false);
  assert.equal(wire.includes("credits"), false);
});

test("expired reset credits are removed from a privacy-filtered cached inventory", async () => {
  await using fixture = await temporaryDirectory();
  const sessions = join(fixture.path, "sessions");
  const cache = join(fixture.path, "Cache_Data");
  await mkdir(sessions, { recursive: true });
  await writeFile(
    join(sessions, "rollout.jsonl"),
    `${JSON.stringify({
      timestamp: "2026-07-18T01:20:00Z",
      payload: {
        rate_limits: {
          limit_id: "codex",
          primary: {
            used_percent: 30,
            window_minutes: 10_080,
            resets_at: 1_784_903_536,
          },
          secondary: null,
        },
      },
    })}\n`,
    "utf8",
  );
  await writeResetCacheFixture(cache, {
    responseDate: "Sat, 18 Jul 2026 01:30:00 GMT",
    body: {
      available_count: 3,
      total_earned_count: 9,
      credits: [
        {
          id: "expired-reset",
          title: "Full reset",
          status: "available",
          expires_at: "2026-07-17T23:59:59Z",
          profile_user_id: "PRIVATE PROFILE",
          description: "PRIVATE DESCRIPTION",
        },
        {
          id: "later-reset",
          title: "Full reset",
          status: "available",
          expires_at: "2026-08-01T00:00:00Z",
          profile_user_id: "PRIVATE PROFILE",
        },
        {
          id: "next-reset",
          title: "Full reset",
          status: "available",
          expires_at: "2026-07-26T00:00:00Z",
          profile_user_id: "PRIVATE PROFILE",
        },
      ],
    },
  });

  const snapshot = await collectSnapshot(
    { codexSessionsPath: sessions, resetCachePath: cache },
    new Date("2026-07-18T02:00:00Z"),
  );

  assert.equal(snapshot.sourceStatus, "ok");
  assert.equal(snapshot.resetInventory.status, "cached_derived");
  assert.equal(snapshot.resetInventory.availableCount, 2);
  assert.equal(snapshot.resetInventory.cachedAt, "2026-07-18T01:30:00.000Z");
  assert.deepEqual(
    snapshot.resetInventory.items.map((item) => item.id),
    ["next-reset", "later-reset"],
  );

  const wire = JSON.stringify(snapshot);
  assert.equal(wire.includes("PRIVATE PROFILE"), false);
  assert.equal(wire.includes("PRIVATE DESCRIPTION"), false);
  assert.equal(wire.includes("profile_user_id"), false);
  assert.equal(wire.includes("description"), false);
  assert.equal(wire.includes("expired-reset"), false);
});

test("five-hour and weekly windows are recognized independently of their slots", async () => {
  await using fixture = await temporaryDirectory();
  const sessions = join(fixture.path, "sessions");
  await mkdir(sessions, { recursive: true });
  await writeFile(
    join(sessions, "rollout.jsonl"),
    `${JSON.stringify({
      timestamp: "2026-07-18T01:20:00Z",
      payload: {
        rate_limits: {
          limit_id: "codex",
          primary: {
            used_percent: 0,
            window_minutes: 10_080,
            resets_at: 1_784_903_536,
          },
          secondary: {
            used_percent: 100,
            window_minutes: 300,
            resets_at: 1_784_903_536,
          },
        },
      },
    })}\n`,
    "utf8",
  );

  const snapshot = await collectSnapshot(
    { codexSessionsPath: sessions, resetCachePath: null },
    new Date("2026-07-18T02:00:00Z"),
  );

  assert.deepEqual(
    snapshot.windows.map(({ name, remainingPercent, status }) => ({
      name,
      remainingPercent,
      status,
    })),
    [
      { name: "weekly", remainingPercent: 100, status: "current" },
      { name: "five_hour", remainingPercent: 0, status: "current" },
    ],
  );
});

test("expired and malformed quota windows never become guessed percentages", async () => {
  await using fixture = await temporaryDirectory();
  const sessions = join(fixture.path, "sessions");
  await mkdir(sessions, { recursive: true });
  await writeFile(
    join(sessions, "rollout.jsonl"),
    `${JSON.stringify({
      timestamp: "2026-07-18T01:20:00Z",
      payload: {
        rate_limits: {
          primary: {
            used_percent: 30,
            window_minutes: 10_080,
            resets_at: 1_784_336_399,
          },
          secondary: {
            used_percent: 101,
            window_minutes: 300,
            resets_at: 1_784_903_536,
          },
        },
      },
    })}\n`,
    "utf8",
  );

  const snapshot = await collectSnapshot(
    { codexSessionsPath: sessions, resetCachePath: null },
    new Date("2026-07-18T02:00:00Z"),
  );

  assert.deepEqual(snapshot.windows, [
    {
      id: "codex:primary:10080",
      name: "weekly",
      windowMinutes: 10_080,
      remainingPercent: null,
      resetsAt: "2026-07-18T00:59:59.000Z",
      status: "pending_sync",
    },
  ]);
});

test("missing reset titles fall back safely while changed cache formats degrade to unavailable", async () => {
  await using fixture = await temporaryDirectory();
  const sessions = join(fixture.path, "sessions");
  const validCache = join(fixture.path, "valid-cache");
  const changedCache = join(fixture.path, "changed-cache");
  await mkdir(sessions, { recursive: true });
  await writeFile(
    join(sessions, "rollout.jsonl"),
    `${JSON.stringify({
      timestamp: "2026-07-18T01:20:00Z",
      payload: {
        rate_limits: {
          primary: {
            used_percent: 30,
            window_minutes: 10_080,
            resets_at: 1_784_903_536,
          },
        },
      },
    })}\n`,
    "utf8",
  );
  await writeResetCacheFixture(validCache, {
    responseDate: "Sat, 18 Jul 2026 01:30:00 GMT",
    body: {
      available_count: 1,
      credits: [
        {
          id: "untitled-reset",
          status: "available",
          expires_at: "2026-07-26T00:00:00Z",
        },
      ],
    },
  });
  await writeResetCacheFixture(changedCache, {
    responseDate: "Sat, 18 Jul 2026 01:30:00 GMT",
    body: { available_count: "1", credits: "changed" },
  });

  const valid = await collectSnapshot(
    { codexSessionsPath: sessions, resetCachePath: validCache },
    new Date("2026-07-18T02:00:00Z"),
  );
  const changed = await collectSnapshot(
    { codexSessionsPath: sessions, resetCachePath: changedCache },
    new Date("2026-07-18T02:00:00Z"),
  );

  assert.equal(valid.resetInventory.items[0].title, "Full reset");
  assert.deepEqual(changed.resetInventory, {
    status: "unavailable",
    availableCount: null,
    cachedAt: null,
    items: [],
  });
});
