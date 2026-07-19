import { readdir } from "node:fs/promises";
import { win32 as path } from "node:path";

const CODEX_PACKAGE_PATTERN = /^OpenAI\.Codex_[A-Za-z0-9]+$/;
const CACHE_SEGMENTS = [
  "LocalCache",
  "Roaming",
  "Codex",
  "web",
  "Codex",
  "Default",
  "Cache",
  "Cache_Data",
];

export function resolveCodexDataPaths({
  homeDirectory,
  localAppData,
  packageNames,
}) {
  const codexPackage = packageNames
    .filter((name) => CODEX_PACKAGE_PATTERN.test(name))
    .sort()
    .at(-1);

  return {
    codexSessionsPath: path.join(homeDirectory, ".codex", "sessions"),
    resetCachePath: codexPackage
      ? path.join(localAppData, "Packages", codexPackage, ...CACHE_SEGMENTS)
      : null,
  };
}

export async function discoverCodexDataPaths({ homeDirectory, localAppData }) {
  let packageNames = [];
  try {
    packageNames = await readdir(path.join(localAppData, "Packages"));
  } catch (error) {
    if (error?.code !== "ENOENT") {
      throw error;
    }
  }

  return resolveCodexDataPaths({ homeDirectory, localAppData, packageNames });
}
