import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

export async function temporaryDirectory() {
  const path = await mkdtemp(join(tmpdir(), "codex-quota-test-"));
  return {
    path,
    async [Symbol.asyncDispose]() {
      await rm(path, { recursive: true, force: true });
    },
  };
}
