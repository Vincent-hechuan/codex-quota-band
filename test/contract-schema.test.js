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
