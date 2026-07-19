import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

const TOKEN_STORE_VERSION = 1;
const HASH_PATTERN = /^[a-f0-9]{64}$/;

function tokenHash(token) {
  return createHash("sha256").update(token, "utf8").digest();
}

function authenticates(hashes, token) {
  if (typeof token !== "string" || token.length === 0) {
    return false;
  }

  const candidate = tokenHash(token);
  return [...hashes].some((hash) =>
    timingSafeEqual(Buffer.from(hash, "hex"), candidate),
  );
}

async function persist(filePath, hashes) {
  await mkdir(dirname(filePath), { recursive: true });
  const temporaryPath = `${filePath}.${process.pid}.${randomUUID()}.tmp`;
  const body = `${JSON.stringify({
    version: TOKEN_STORE_VERSION,
    tokenHashes: [...hashes].sort(),
  })}\n`;

  try {
    await writeFile(temporaryPath, body, { encoding: "utf8", flag: "wx", mode: 0o600 });
    await rename(temporaryPath, filePath);
  } catch (error) {
    await rm(temporaryPath, { force: true });
    throw error;
  }
}

function parseStore(body) {
  const parsed = JSON.parse(body);
  if (
    parsed?.version !== TOKEN_STORE_VERSION ||
    !Array.isArray(parsed.tokenHashes) ||
    !parsed.tokenHashes.every((hash) => typeof hash === "string" && HASH_PATTERN.test(hash))
  ) {
    throw new Error("authorized device store has an unsupported format");
  }
  return new Set(parsed.tokenHashes);
}

export function createMemoryHashedTokenStore() {
  const hashes = new Set();
  return {
    async add(token) {
      hashes.add(tokenHash(token).toString("hex"));
    },
    authenticate(token) {
      return authenticates(hashes, token);
    },
    async revokeAll() {
      hashes.clear();
    },
  };
}

export async function openHashedTokenStore({ filePath }) {
  let hashes;
  try {
    hashes = parseStore(await readFile(filePath, "utf8"));
  } catch (error) {
    if (error?.code !== "ENOENT") {
      throw error;
    }
    hashes = new Set();
  }

  return {
    async add(token) {
      hashes.add(tokenHash(token).toString("hex"));
      await persist(filePath, hashes);
    },
    authenticate(token) {
      return authenticates(hashes, token);
    },
    async revokeAll() {
      hashes.clear();
      await persist(filePath, hashes);
    },
  };
}
