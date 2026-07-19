import assert from "node:assert/strict";
import test from "node:test";
import { startDesktopService } from "../src/desktop/desktop-service.js";

test("the desktop service preserves startup choice and controls pairing, revocation, and shutdown", async () => {
  const events = [];
  const now = new Date("2026-07-18T02:00:00Z");
  const server = {
    port: 17_321,
    beginPairing(pairing) {
      events.push(["beginPairing", pairing]);
    },
    async close() {
      events.push(["close"]);
    },
  };
  const tokenStore = {
    async revokeAll() {
      events.push(["revokeAll"]);
    },
  };

  await using service = await startDesktopService({
    serverFactory: async (options) => {
      events.push(["startServer", options.pairing]);
      return server;
    },
    serverOptions: {},
    tokenStore,
    loginItem: {
      setEnabled(enabled) {
        events.push(["setLoginItem", enabled]);
      },
    },
    lanAddresses: () => ["192.168.31.8"],
    clock: () => now,
    randomInteger: () => 42,
  });

  assert.equal(events.some(([event]) => event === "setLoginItem"), false);
  const pairing = service.beginPairing();
  assert.deepEqual(pairing, {
    code: "000042",
    expiresAt: "2026-07-18T02:05:00.000Z",
    endpoints: ["http://192.168.31.8:17321"],
  });
  assert.equal(events.at(-1)[0], "beginPairing");
  assert.equal(events.at(-1)[1].code, "000042");

  await service.revokeAll();
  assert.deepEqual(events.at(-1), ["revokeAll"]);

  await service.setLoginStartup(false);
  assert.deepEqual(events.at(-1), ["setLoginItem", false]);

  await service.close();
  assert.deepEqual(events.at(-1), ["close"]);
});
