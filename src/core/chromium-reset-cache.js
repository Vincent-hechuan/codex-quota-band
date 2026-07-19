import { readdir, readFile, stat } from "node:fs/promises";
import { join } from "node:path";
import { brotliDecompressSync, gunzipSync, inflateSync } from "node:zlib";

const BLOCK_FILE_MAGIC = 0xc104cac3;
const BLOCK_HEADER_SIZE = 8_192;
const ENTRY_STORE_SIZE = 256;
const ENTRY_KEY_OFFSET = 96;
const INLINE_KEY_CAPACITY = 160;
const RESET_ENDPOINT = Buffer.from("/wham/rate-limit-reset-credits", "utf8");

export async function readResetInventory(cacheDirectory, now) {
  const candidates = await findResetEntries(cacheDirectory);
  if (candidates.length === 0) return unavailableInventory();
  candidates.sort((left, right) => right.cachedAt - left.cachedAt);
  return sanitizeInventory(candidates[0], now);
}
async function findResetEntries(cacheDirectory) {
  const entries = [];
  for (const name of await readdir(cacheDirectory)) {
    if (!/^data_\d+$/.test(name)) continue;
    const path = join(cacheDirectory, name);
    const bytes = await readFile(path);
    if (bytes.length < BLOCK_HEADER_SIZE || bytes.readUInt32LE(0) !== BLOCK_FILE_MAGIC) {
      continue;
    }
    if (bytes.readInt32LE(12) !== ENTRY_STORE_SIZE) continue;

    let occurrence = -1;
    while ((occurrence = bytes.indexOf(RESET_ENDPOINT, occurrence + 1)) >= 0) {
      const relative = occurrence - BLOCK_HEADER_SIZE;
      if (relative < 0) continue;
      const block = Math.floor(relative / ENTRY_STORE_SIZE);
      const entryOffset = BLOCK_HEADER_SIZE + block * ENTRY_STORE_SIZE;
      const parsed = await parseEntry(cacheDirectory, path, bytes, entryOffset);
      if (parsed) entries.push(parsed);
    }
  }
  return deduplicateEntries(entries);
}

async function parseEntry(cacheDirectory, entryPath, bytes, entryOffset) {
  if (entryOffset + ENTRY_STORE_SIZE > bytes.length) return null;
  if (bytes.readInt32LE(entryOffset + 20) !== 0) return null;
  const keyLength = bytes.readInt32LE(entryOffset + 32);
  if (keyLength <= 0 || keyLength > INLINE_KEY_CAPACITY) return null;
  const key = bytes.subarray(
    entryOffset + ENTRY_KEY_OFFSET,
    entryOffset + ENTRY_KEY_OFFSET + keyLength,
  );
  if (!key.includes(RESET_ENDPOINT)) return null;

  const metadataSize = bytes.readInt32LE(entryOffset + 40);
  const bodySize = bytes.readInt32LE(entryOffset + 44);
  if (metadataSize <= 0 || bodySize <= 0) return null;
  const metadataAddress = bytes.readUInt32LE(entryOffset + 56);
  const bodyAddress = bytes.readUInt32LE(entryOffset + 60);

  try {
    const metadata = await readAddress(cacheDirectory, metadataAddress, metadataSize);
    const body = await readAddress(cacheDirectory, bodyAddress, bodySize);
    const encoding = asciiHeader(metadata, "content-encoding") ?? "identity";
    const response = JSON.parse(decompress(body, encoding).toString("utf8"));
    if (!Number.isInteger(response?.available_count) || !Array.isArray(response?.credits)) {
      return null;
    }
    const responseDate = asciiHeader(metadata, "date");
    const cachedAt = responseDate ? new Date(responseDate) : (await stat(entryPath)).mtime;
    if (Number.isNaN(cachedAt.valueOf())) return null;
    return { cachedAt, response };
  } catch {
    return null;
  }
}

async function readAddress(cacheDirectory, rawAddress, size) {
  const initialized = (rawAddress & 0x80000000) !== 0;
  if (!initialized) throw new Error("uninitialized cache address");
  const type = (rawAddress >>> 28) & 0x7;
  if (type === 0) {
    const external = rawAddress & 0x0fffffff;
    const candidates = [
      `f_${external.toString(16).padStart(6, "0")}`,
      `f_${external.toString(16)}`,
    ];
    for (const name of candidates) {
      try {
        return (await readFile(join(cacheDirectory, name))).subarray(0, size);
      } catch (error) {
        if (error?.code !== "ENOENT") throw error;
      }
    }
    throw new Error("external cache file missing");
  }

  const blockSize = new Map([
    [1, 36],
    [2, 256],
    [3, 1_024],
    [4, 4_096],
  ]).get(type);
  if (!blockSize) throw new Error("unsupported cache block type");
  const file = (rawAddress >>> 16) & 0xff;
  const start = rawAddress & 0xffff;
  const bytes = await readFile(join(cacheDirectory, `data_${file}`));
  const offset = BLOCK_HEADER_SIZE + start * blockSize;
  if (offset + size > bytes.length) throw new Error("cache address out of bounds");
  return bytes.subarray(offset, offset + size);
}

function asciiHeader(metadata, name) {
  const text = metadata.toString("latin1");
  const match = text.match(new RegExp(`(?:^|\\0|\\r|\\n)${name}:([^\\0\\r\\n]+)`, "i"));
  return match?.[1]?.trim() ?? null;
}

function decompress(body, encoding) {
  switch (encoding.toLowerCase()) {
    case "br":
      return brotliDecompressSync(body);
    case "gzip":
      return gunzipSync(body);
    case "deflate":
      return inflateSync(body);
    case "identity":
    case "":
      return body;
    default:
      throw new Error("unsupported content encoding");
  }
}

function sanitizeInventory(candidate, now) {
  const available = candidate.response.credits
    .filter((credit) => credit?.status === "available")
    .map((credit) => sanitizeCredit(credit))
    .filter(Boolean)
    .sort((left, right) => left.expiresAt.localeCompare(right.expiresAt));
  const items = available.filter((credit) => new Date(credit.expiresAt) > now);
  const derived =
    items.length !== available.length ||
    items.length !== candidate.response.available_count;
  return {
    status: derived ? "cached_derived" : "cached",
    availableCount: items.length,
    cachedAt: candidate.cachedAt.toISOString(),
    items,
  };
}

function sanitizeCredit(credit) {
  if (typeof credit.id !== "string" || credit.id.length === 0) return null;
  const expiresAt = new Date(credit.expires_at);
  if (Number.isNaN(expiresAt.valueOf())) return null;
  return {
    id: credit.id,
    title:
      typeof credit.title === "string" && credit.title.trim()
        ? credit.title.trim()
        : "Full reset",
    status: "available",
    expiresAt: expiresAt.toISOString(),
  };
}

function deduplicateEntries(entries) {
  const unique = new Map();
  for (const entry of entries) {
    const key = `${entry.cachedAt.toISOString()}:${JSON.stringify(entry.response)}`;
    unique.set(key, entry);
  }
  return [...unique.values()];
}

function unavailableInventory() {
  return {
    status: "unavailable",
    availableCount: null,
    cachedAt: null,
    items: [],
  };
}
