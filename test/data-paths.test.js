import assert from "node:assert/strict";
import test from "node:test";
import { resolveCodexDataPaths } from "../src/desktop/data-paths.js";

test("Codex data discovery only accepts the expected AppX package directory", () => {
  const paths = resolveCodexDataPaths({
    homeDirectory: "C:\\Users\\Alice",
    localAppData: "C:\\Users\\Alice\\AppData\\Local",
    packageNames: [
      "OpenAI.Codex_..\\Unexpected",
      "Other.Application_abcd",
      "OpenAI.Codex_2p2nqsd0c76g0",
    ],
  });

  assert.deepEqual(paths, {
    codexSessionsPath: "C:\\Users\\Alice\\.codex\\sessions",
    resetCachePath:
      "C:\\Users\\Alice\\AppData\\Local\\Packages\\OpenAI.Codex_2p2nqsd0c76g0\\LocalCache\\Roaming\\Codex\\web\\Codex\\Default\\Cache\\Cache_Data",
  });
});
