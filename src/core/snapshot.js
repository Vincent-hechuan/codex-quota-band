import { readdir, readFile, stat } from "node:fs/promises";
import { join } from "node:path";
import { readResetInventory } from "./chromium-reset-cache.js";

export async function collectSnapshot(paths, now = new Date()) {
  const latest = await readLatestRateLimits(paths.codexSessionsPath);
  const windows = latest ? normalizeWindows(latest.rateLimits, now) : [];
  const resetInventory = paths.resetCachePath
    ? await readResetInventory(paths.resetCachePath, now)
    : {
        status: "missing",
        availableCount: null,
        cachedAt: null,
        items: [],
      };
  const allSourcesReady =
    latest && ["cached", "cached_derived"].includes(resetInventory.status);

  return {
    protocolVersion: 1,
    generatedAt: now.toISOString(),
    sourceStatus: allSourcesReady ? "ok" : latest ? "partial" : "unavailable",
    limitsCollectedAt: latest?.collectedAt ?? null,
    windows,
    resetInventory,
    link: {
      computer: "online",
      codex: latest ? "ok" : "unavailable",
    },
  };
}

async function readLatestRateLimits(root) {
  const files = await listJsonlFiles(root);
  const withTimes = await Promise.all(
    files.map(async (path) => ({ path, mtimeMs: (await stat(path)).mtimeMs })),
  );
  withTimes.sort((left, right) => right.mtimeMs - left.mtimeMs);

  let latest = null;
  for (const file of withTimes) {
    const text = await readFile(file.path, "utf8");
    for (const line of text.split(/\r?\n/)) {
      if (!line.includes('"rate_limits"')) continue;
      let event;
      try {
        event = JSON.parse(line);
      } catch {
        continue;
      }
      const rateLimits = findNamedObject(event, "rate_limits");
      if (!rateLimits) continue;
      const collectedAt = validIsoDate(event.timestamp) ?? new Date(file.mtimeMs).toISOString();
      if (!latest || collectedAt > latest.collectedAt) {
        latest = { collectedAt, rateLimits };
      }
    }
  }
  return latest;
}

async function listJsonlFiles(root) {
  const files = [];
  async function visit(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) await visit(path);
      else if (entry.isFile() && entry.name.endsWith(".jsonl")) files.push(path);
    }
  }
  await visit(root);
  return files;
}

function findNamedObject(value, targetKey) {
  if (!value || typeof value !== "object") return null;
  if (!Array.isArray(value) && value[targetKey] && typeof value[targetKey] === "object") {
    return value[targetKey];
  }
  for (const child of Object.values(value)) {
    const found = findNamedObject(child, targetKey);
    if (found) return found;
  }
  return null;
}

function normalizeWindows(rateLimits, now) {
  return [
    ["primary", rateLimits.primary],
    ["secondary", rateLimits.secondary],
  ]
    .filter(([, value]) => isValidWindow(value))
    .map(([slot, value]) => {
      const resetsAt = new Date(value.resets_at * 1_000);
      const isCurrent = resetsAt > now;
      return {
        id: `${rateLimits.limit_id ?? "codex"}:${slot}:${value.window_minutes}`,
        name: windowName(value.window_minutes),
        windowMinutes: value.window_minutes,
        remainingPercent: isCurrent ? 100 - value.used_percent : null,
        resetsAt: resetsAt.toISOString(),
        status: isCurrent ? "current" : "pending_sync",
      };
    });
}

function isValidWindow(value) {
  return (
    value &&
    Number.isFinite(value.used_percent) &&
    value.used_percent >= 0 &&
    value.used_percent <= 100 &&
    Number.isInteger(value.window_minutes) &&
    value.window_minutes > 0 &&
    Number.isFinite(value.resets_at)
  );
}

function windowName(minutes) {
  if (minutes === 300) return "five_hour";
  if (minutes === 10_080) return "weekly";
  return "custom";
}

function validIsoDate(value) {
  if (typeof value !== "string") return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? null : parsed.toISOString();
}
