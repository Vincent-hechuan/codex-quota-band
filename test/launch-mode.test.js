import assert from "node:assert/strict";
import test from "node:test";
import { requiresSingleInstanceLock } from "../src/desktop/launch-mode.js";

test("headless verification modes can run beside the normal tray instance", () => {
  assert.equal(requiresSingleInstanceLock(["CodexQuota.exe"]), true);
  assert.equal(requiresSingleInstanceLock(["CodexQuota.exe", "--smoke-test"]), false);
  assert.equal(
    requiresSingleInstanceLock(["CodexQuota.exe", "--diagnostic-service-test"]),
    false,
  );
});
