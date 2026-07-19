import assert from "node:assert/strict";
import { join } from "node:path";
import test from "node:test";
import { openStartupPreferenceStore } from "../src/desktop/startup-preference-store.js";
import { temporaryDirectory } from "./support/temporary-directory.js";

test("login startup defaults on once and preserves a later opt-out", async () => {
  await using directory = await temporaryDirectory();
  const filePath = join(directory.path, "preferences.json");

  const firstRun = await openStartupPreferenceStore({ filePath });
  assert.equal(await firstRun.loadOrInitialize(), true);
  await firstRun.save(false);

  const restarted = await openStartupPreferenceStore({ filePath });
  assert.equal(await restarted.loadOrInitialize(), false);
});
